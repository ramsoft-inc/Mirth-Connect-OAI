package com.ramsoft.logging;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.status.StatusLogger;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ships Mirth log events to the OmegaAI log receiver as JSON-array batches.
 *
 * <p>ACK-176: the previous version sent one HTTP request per log event, which made the Mirth
 * fleet the largest source of requests on the shared APIM gateway. Events are now buffered in a
 * bounded queue and POSTed as {@code [e1,e2,...]} by a single background thread: once per flush
 * interval, plus immediately for every full batch that is waiting. The log receiver already accepts
 * an array body (it is what the OmegaAI Link's Serilog HTTP sink sends).
 *
 * <p>Failure behaviour is deliberately lossy: the appender never blocks a channel thread, keeps at
 * most one request in flight, and drops a batch it could not deliver rather than retrying it. After
 * a failed send it stops early (size-triggered) flushes and sends at most one batch per interval
 * until a send succeeds again, so a slow or failing gateway sees less traffic from Mirth, not more.
 * The queue is bounded by event count and by total characters. Drops are counted and reported as a
 * WARN event from logger {@code RamsoftHttpAppender}, with the next batch or on its own.
 *
 * <p>The class name, both original constructors and {@link #updateEndpointAndPattern} are unchanged
 * so the existing Global Scripts registration and the "Check Mirth Health" deploy script keep working.
 *
 * <p>"Redeploy All" clears the global map, so Global Scripts constructs and starts a second instance
 * that log4j never attaches ({@code addAppender} keeps the first). Only one started instance per
 * appender name ships logs; a later one becomes a stand-in that starts no thread and forwards
 * {@link #updateEndpointAndPattern} (and anything appended to it) to the running one, so endpoint
 * and key updates from "Check Mirth Health" still reach the appender that is attached.
 */
public class RamsoftAsyncHttpAppender extends AbstractAppender {

    static final int DEFAULT_FLUSH_INTERVAL_MS = 2000;
    static final int DEFAULT_MAX_BATCH_EVENTS = 500;
    static final int DEFAULT_MAX_BATCH_CHARS = 1_000_000;
    static final int DEFAULT_MAX_QUEUE_EVENTS = 20_000;
    /** About 32 MB of heap (UTF-16), whatever the size of individual debug lines. */
    static final int DEFAULT_MAX_QUEUE_CHARS = 16_000_000;
    /** Budget for the final flush in {@link #stop}, so a down or slow gateway cannot hold up a Mirth shutdown. */
    static final long DEFAULT_FINAL_FLUSH_BUDGET_MS = 10_000;
    static final String DROP_REPORT_LOGGER = "RamsoftHttpAppender";

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final Pattern THREAD_PATTERN = Pattern.compile(".* on ([^()]+) \\([0-9a-f\\-]{36}\\), ([^()]+) \\(");
    /** Room kept in every batch for a drop report (one event, well under this many characters). */
    private static final int REPORT_RESERVE_CHARS = 1_024;

    /** The instance that ships logs, per appender name. */
    private static final ConcurrentMap<String, RamsoftAsyncHttpAppender> RUNNING = new ConcurrentHashMap<>();

    private volatile String url;
    private volatile String subscriptionKey;
    private volatile Layout<? extends Serializable> dynamicLayout;

    private final int flushIntervalMs;
    private final int maxBatchEvents;
    private final int maxBatchChars;
    private final long maxQueueChars;
    private final BlockingQueue<String> queue;
    private final AtomicLong queuedChars = new AtomicLong();
    private final AtomicBoolean flushRequested = new AtomicBoolean(false);
    private final AtomicLong droppedQueueFull = new AtomicLong();
    private final AtomicLong droppedSendFailed = new AtomicLong();
    private final AtomicLong batchesSent = new AtomicLong();
    private final AtomicLong eventsSent = new AtomicLong();
    private volatile boolean lastSendFailed;
    private volatile boolean stopRequested;
    volatile long finalFlushBudgetMs = DEFAULT_FINAL_FLUSH_BUDGET_MS;

    /** Set in {@link #start} on the shipping instance only. */
    private volatile OkHttpClient httpClient;
    private volatile ScheduledExecutorService flusher;
    /** Set in {@link #start} when another instance with this name is already shipping. */
    private volatile RamsoftAsyncHttpAppender runningInstance;

    // Guards carryOver and serialises flushes (flusher thread, stop(), tests).
    private final ReentrantLock flushLock = new ReentrantLock();
    private String carryOver;

    public RamsoftAsyncHttpAppender(String name, Filter filter, Layout<? extends Serializable> layout,
                                     boolean ignoreExceptions, String url, String subscriptionKey) {
        this(name, filter, layout, ignoreExceptions, url, subscriptionKey, DEFAULT_FLUSH_INTERVAL_MS,
                DEFAULT_MAX_BATCH_EVENTS, DEFAULT_MAX_BATCH_CHARS, DEFAULT_MAX_QUEUE_EVENTS, DEFAULT_MAX_QUEUE_CHARS);
    }

    public RamsoftAsyncHttpAppender(String name, Layout<? extends Serializable> layout,
                                     boolean ignoreExceptions, String url, String subscriptionKey) {
        this(name, null, layout, ignoreExceptions, url, subscriptionKey);
    }

    /** Full constructor; the batching limits are exposed for tests and for tuning from Global Scripts. */
    public RamsoftAsyncHttpAppender(String name, Filter filter, Layout<? extends Serializable> layout,
                                     boolean ignoreExceptions, String url, String subscriptionKey,
                                     int flushIntervalMs, int maxBatchEvents, int maxBatchChars,
                                     int maxQueueEvents, int maxQueueChars) {
        super(name, filter, layout, ignoreExceptions, Property.EMPTY_ARRAY);
        this.dynamicLayout = layout;
        this.url = url;
        this.subscriptionKey = subscriptionKey;
        this.flushIntervalMs = Math.max(50, flushIntervalMs);
        this.maxBatchEvents = Math.max(2, maxBatchEvents); // one slot is kept for a drop report
        this.maxBatchChars = Math.max(1, maxBatchChars);
        this.maxQueueChars = Math.max(1, maxQueueChars);
        this.queue = new ArrayBlockingQueue<>(Math.max(1, maxQueueEvents));
    }

    @Override
    public void start() {
        claimShippingOrStandIn();
        super.start();
    }

    private static boolean isShipping(RamsoftAsyncHttpAppender a) {
        return a.isStarted() && !a.stopRequested;
    }

    /** Becomes the shipping instance for this name, or a stand-in for the one that already is. */
    private synchronized void claimShippingOrStandIn() {
        String name = getName();
        while (true) {
            RamsoftAsyncHttpAppender current = RUNNING.putIfAbsent(name, this);
            if (current == null || current == this) {
                break;
            }
            if (isShipping(current)) {
                // A second registration (e.g. Global Scripts after "Redeploy All"): stand in for the running one.
                runningInstance = current;
                return;
            }
            RUNNING.remove(name, current); // left behind by an instance that has stopped
        }
        runningInstance = null;
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                // A retried batch is extra load on a gateway that is already failing; drop instead.
                .retryOnConnectionFailure(false)
                .build();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RamsoftHttpAppender-flusher");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::flushSafely, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
        flusher = executor;
    }

    /**
     * The instance this stand-in forwards to, or null when this instance ships itself. If the one it
     * stood in for has stopped, this instance takes over (or stands in for whichever instance now
     * ships), so updates and events are never handed to a stopped appender.
     */
    private RamsoftAsyncHttpAppender liveRunningInstance() {
        RamsoftAsyncHttpAppender running = runningInstance;
        if (running == null || isShipping(running)) {
            return running;
        }
        synchronized (this) {
            if (runningInstance == running && isStarted()) {
                claimShippingOrStandIn();
            }
            return runningInstance;
        }
    }

    // ✅ Allow layout + endpoint update after deploy
    public synchronized void updateEndpointAndPattern(String url, String subscriptionKey, Layout<? extends Serializable> layout) {
        this.url = url;
        this.subscriptionKey = subscriptionKey;
        this.dynamicLayout = layout;
        RamsoftAsyncHttpAppender running = liveRunningInstance();
        if (running != null) {
            running.updateEndpointAndPattern(url, subscriptionKey, layout);
        }
    }

    // ✅ Use dynamic layout instead of superclass one
    @Override
    public Layout<? extends Serializable> getLayout() {
        return this.dynamicLayout;
    }

    /** Escapes a value for a JSON string: quote, backslash and every control character. */
    static String jsonEscape(String input) {
        StringBuilder sb = new StringBuilder(input.length() + 8);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    @Override
    public void append(LogEvent event) {
        RamsoftAsyncHttpAppender running = liveRunningInstance();
        if (running != null) {
            running.append(event);
            return;
        }
        try {
            if (url == null || url.isEmpty()) {
                return;
            }
            String logMsg = toJson(event);
            if (logMsg == null) {
                return;
            }
            int chars = logMsg.length();
            // Reserve the characters first so concurrent channel threads cannot overshoot the cap together.
            if (queuedChars.addAndGet(chars) > maxQueueChars || !queue.offer(logMsg)) {
                queuedChars.addAndGet(-chars);
                // Never block a channel thread on log shipping, and never let a backlog grow the heap unbounded.
                droppedQueueFull.incrementAndGet();
                requestFlush();
                return;
            }
            if (fullBatchWaiting()) {
                requestFlush();
            }
        } catch (Exception e) {
            if (!ignoreExceptions()) {
                StatusLogger.getLogger().error("RamsoftAppender exception", e);
            }
        }
    }

    /** Renders one event with the current layout and injects channelName/connectorName. */
    private String toJson(LogEvent event) {
        Layout<? extends Serializable> layout = getLayout();
        if (layout == null) {
            return null;
        }
        String threadName = event.getThreadName() != null ? event.getThreadName() : "";
        String channelName = "Unknown";
        String connectorName = "Unknown";

        Matcher matcher = THREAD_PATTERN.matcher(threadName);
        if (matcher.find()) {
            channelName = matcher.group(1);
            connectorName = matcher.group(2);
        }

        // Get the base JSON from the layout
        String logMsg = new String(layout.toByteArray(event), StandardCharsets.UTF_8).trim();

        // Inject channelName and connectorName into JSON
        if (logMsg.endsWith("}")) {
            logMsg = logMsg.substring(0, logMsg.length() - 1) +
                ",\"channelName\":\"" + jsonEscape(channelName) + "\"" +
                ",\"connectorName\":\"" + jsonEscape(connectorName) + "\"}";
        }
        return logMsg;
    }

    /** A full batch is waiting, by event count (one slot kept for a drop report) or by size. */
    private boolean fullBatchWaiting() {
        return queue.size() >= maxBatchEvents - 1
                || queuedChars.get() >= Math.max(1, maxBatchChars - REPORT_RESERVE_CHARS);
    }

    private void requestFlush() {
        if (lastSendFailed) {
            // Backing off: only the scheduled flush may contact a failing gateway (one request per interval).
            return;
        }
        ScheduledExecutorService executor = flusher;
        if (executor != null && flushRequested.compareAndSet(false, true)) {
            try {
                executor.execute(() -> flushSafely(true));
            } catch (Exception e) {
                // Executor already shut down (appender stopping); the final flush in stop() covers it.
                flushRequested.set(false);
            }
        }
    }

    private void flushSafely() {
        flushSafely(false);
    }

    private void flushSafely(boolean early) {
        if (early) {
            flushRequested.set(false);
        }
        try {
            flushLock.lock();
            try {
                // An early flush may have been queued while a request was in flight; if that request
                // failed, backing off wins and the scheduled flush is the only one that tries again.
                if (!(early && lastSendFailed)) {
                    flushLocked(false, 0L);
                }
            } finally {
                flushLock.unlock();
            }
        } catch (Throwable t) {
            // An exception escaping a scheduled task would cancel every later flush.
            StatusLogger.getLogger().error("RamsoftAppender flush failed", t);
        }
    }

    /** Runs one regular flush now (what the flusher thread does each interval). */
    void flush() {
        flushLock.lock();
        try {
            flushLocked(false, 0L);
        } finally {
            flushLock.unlock();
        }
    }

    /**
     * A regular flush sends one batch, then keeps going only while another full batch is waiting, so
     * steady traffic produces one request per interval rather than one per round trip. The final
     * flush ({@code drain}) keeps going until the queue is empty or {@code deadlineNanos} passes.
     * Both stop at the first failed send; a regular flush also yields as soon as stop() is requested.
     */
    private void flushLocked(boolean drain, long deadlineNanos) {
        while (true) {
            if (!drain && stopRequested) {
                return;
            }
            if (drain && System.nanoTime() - deadlineNanos > 0) {
                return;
            }
            long full = droppedQueueFull.getAndSet(0);
            long failed = droppedSendFailed.getAndSet(0);
            boolean reporting = full > 0 || failed > 0;
            List<String> batch = takeBatch(reporting);
            int events = batch.size();
            if (reporting && events > 0 && bodyChars(batch) + REPORT_RESERVE_CHARS > maxBatchChars) {
                // A single event over the size cap goes on its own; the report waits for the next batch.
                droppedQueueFull.addAndGet(full);
                droppedSendFailed.addAndGet(failed);
                full = 0;
                failed = 0;
                reporting = false;
            }
            if (reporting) {
                addDropReport(batch, full, failed);
            }
            if (batch.isEmpty()) {
                return;
            }
            if (send(batch)) {
                lastSendFailed = false;
                batchesSent.incrementAndGet();
                eventsSent.addAndGet(events);
            } else {
                lastSendFailed = true;
                // The report went down with the batch: put its counts back so the next one is complete.
                droppedQueueFull.addAndGet(full);
                droppedSendFailed.addAndGet(failed + events);
                return;
            }
            boolean more = carryOver != null || (drain ? !queue.isEmpty() : fullBatchWaiting());
            if (!more) {
                return;
            }
        }
    }

    private List<String> takeBatch(boolean reserveForReport) {
        int maxEvents = reserveForReport ? maxBatchEvents - 1 : maxBatchEvents;
        int maxChars = reserveForReport ? Math.max(1, maxBatchChars - REPORT_RESERVE_CHARS) : maxBatchChars;
        List<String> batch = new ArrayList<>();
        int chars = 2;
        if (carryOver != null) {
            batch.add(carryOver);
            chars += carryOver.length();
            carryOver = null;
        }
        while (batch.size() < maxEvents) {
            String next = queue.poll();
            if (next == null) {
                break;
            }
            queuedChars.addAndGet(-next.length());
            if (!batch.isEmpty() && chars + 1 + next.length() > maxChars) {
                // Does not fit; it leads the next batch (a single event is always sent on its own).
                carryOver = next;
                break;
            }
            batch.add(next);
            chars += next.length() + 1;
        }
        return batch;
    }

    private static int bodyChars(List<String> batch) {
        int chars = 2 + Math.max(0, batch.size() - 1);
        for (String entry : batch) {
            chars += entry.length();
        }
        return chars;
    }

    private void addDropReport(List<String> batch, long full, long failed) {
        LogEvent report = Log4jLogEvent.newBuilder()
                .setLoggerName(DROP_REPORT_LOGGER)
                .setLevel(Level.WARN)
                .setThreadName(Thread.currentThread().getName())
                .setTimeMillis(System.currentTimeMillis())
                .setMessage(new SimpleMessage("RamsoftHttpAppender dropped " + (full + failed)
                        + " log events since the last delivered batch (queue full: " + full
                        + ", send failed: " + failed + ")"))
                .build();
        String json = toJson(report);
        if (json != null) {
            batch.add(json);
        }
    }

    /** POSTs one batch; true only on a 2xx. Never retried. */
    private boolean send(List<String> batch) {
        String target = url;
        String key = subscriptionKey;
        OkHttpClient client = httpClient;
        if (target == null || target.isEmpty() || client == null) {
            return false;
        }
        StringBuilder body = new StringBuilder(2 + batch.size() * 256);
        body.append('[');
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append(batch.get(i));
        }
        body.append(']');

        try {
            HttpUrl parsed = HttpUrl.parse(target);
            if (parsed == null) {
                return false;
            }
            HttpUrl.Builder httpUrlBuilder = parsed.newBuilder();
            if (key != null && !key.isEmpty()) {
                httpUrlBuilder.addQueryParameter("subscription-key", key);
            }
            Request request = new Request.Builder()
                    .url(httpUrlBuilder.build())
                    .post(RequestBody.create(JSON, body.toString()))
                    .build();
            try (Response response = client.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Stops the flusher and sends what is still queued within the final-flush budget. A regular flush
     * in progress finishes its current request and yields; the final flush checks the budget before
     * every request, so a stop takes at most the budget plus one request's timeouts.
     */
    @Override
    public boolean stop(long timeout, TimeUnit timeUnit) {
        setStopping();
        RamsoftAsyncHttpAppender running = runningInstance;
        if (running == null) {
            stopShipping();
            RUNNING.remove(getName(), this);
        }
        boolean stopped = super.stop(timeout, timeUnit, false);
        setStopped();
        return stopped;
    }

    private void stopShipping() {
        stopRequested = true;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(finalFlushBudgetMs);
        ScheduledExecutorService executor = flusher;
        if (executor != null) {
            executor.shutdown();
        }
        try {
            if (flushLock.tryLock(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                try {
                    flushLocked(true, deadline);
                } finally {
                    flushLock.unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            StatusLogger.getLogger().error("RamsoftAppender final flush failed", t);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /** Diagnostics for Global Scripts / tests: "batches=..,events=..,queued=..,droppedPending=..". */
    public String getStats() {
        RamsoftAsyncHttpAppender running = runningInstance;
        if (running != null) {
            return running.getStats();
        }
        return "batches=" + batchesSent.get()
                + ",events=" + eventsSent.get()
                + ",queued=" + queue.size()
                + ",droppedPending=" + (droppedQueueFull.get() + droppedSendFailed.get());
    }

    boolean isStandIn() {
        return runningInstance != null;
    }
}

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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ships Mirth log events to the OmegaAI log receiver as JSON-array batches.
 *
 * <p>ACK-176: the previous version sent one HTTP request per log event, which made ~215 Mirth
 * instances the largest source of requests on the shared APIM gateway. Events are now buffered
 * in a bounded queue and POSTed as {@code [e1,e2,...]} by a single background thread, either every
 * flush interval or as soon as a full batch is waiting. The log receiver already accepts an array
 * body (it is what the OmegaAI Link's Serilog HTTP sink sends).
 *
 * <p>Failure behaviour is deliberately lossy: the appender never blocks a channel thread, keeps at
 * most one request in flight, and drops a batch it could not deliver rather than retrying it, so a
 * slow or failing gateway sees less traffic from Mirth, not more. Drops are counted and reported
 * in the next delivered batch as a WARN event from logger {@code RamsoftHttpAppender}.
 *
 * <p>The class name, both original constructors and {@link #updateEndpointAndPattern} are unchanged
 * so the existing Global Scripts registration and the "Check Mirth Health" deploy script keep working.
 */
public class RamsoftAsyncHttpAppender extends AbstractAppender {

    static final int DEFAULT_FLUSH_INTERVAL_MS = 2000;
    static final int DEFAULT_MAX_BATCH_EVENTS = 500;
    static final int DEFAULT_MAX_BATCH_CHARS = 1_000_000;
    static final int DEFAULT_MAX_QUEUE_EVENTS = 20_000;

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final Pattern THREAD_PATTERN = Pattern.compile(".* on ([^()]+) \\([0-9a-f\\-]{36}\\), ([^()]+) \\(");
    static final String DROP_REPORT_LOGGER = "RamsoftHttpAppender";

    private final OkHttpClient httpClient;
    private volatile String url;
    private volatile String subscriptionKey;

    // Separate layout field for dynamic update
    private volatile Layout<? extends Serializable> dynamicLayout;

    private final BlockingQueue<String> queue;
    private final ScheduledExecutorService flusher;
    private final AtomicBoolean flushRequested = new AtomicBoolean(false);
    private final AtomicLong droppedQueueFull = new AtomicLong();
    private final AtomicLong droppedSendFailed = new AtomicLong();
    private final AtomicLong batchesSent = new AtomicLong();
    private final AtomicLong eventsSent = new AtomicLong();
    private final int maxBatchEvents;
    private final int maxBatchChars;

    // Only touched by the flusher thread (and by stop() after the flusher has terminated).
    private String carryOver;

    public RamsoftAsyncHttpAppender(String name, Filter filter, Layout<? extends Serializable> layout,
                                     boolean ignoreExceptions, String url, String subscriptionKey) {
        this(name, filter, layout, ignoreExceptions, url, subscriptionKey,
                DEFAULT_FLUSH_INTERVAL_MS, DEFAULT_MAX_BATCH_EVENTS, DEFAULT_MAX_BATCH_CHARS, DEFAULT_MAX_QUEUE_EVENTS);
    }

    public RamsoftAsyncHttpAppender(String name, Layout<? extends Serializable> layout,
                                     boolean ignoreExceptions, String url, String subscriptionKey) {
        this(name, null, layout, ignoreExceptions, url, subscriptionKey);
    }

    /** Full constructor; the batching limits are exposed for tests and for tuning from Global Scripts. */
    public RamsoftAsyncHttpAppender(String name, Filter filter, Layout<? extends Serializable> layout,
                                     boolean ignoreExceptions, String url, String subscriptionKey,
                                     int flushIntervalMs, int maxBatchEvents, int maxBatchChars, int maxQueueEvents) {
        super(name, filter, layout, ignoreExceptions, Property.EMPTY_ARRAY);
        this.dynamicLayout = layout;
        this.url = url;
        this.subscriptionKey = subscriptionKey;
        this.maxBatchEvents = Math.max(1, maxBatchEvents);
        this.maxBatchChars = Math.max(1, maxBatchChars);
        this.queue = new ArrayBlockingQueue<>(Math.max(1, maxQueueEvents));
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                // A retried batch is extra load on a gateway that is already failing; drop instead.
                .retryOnConnectionFailure(false)
                .build();
        this.flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RamsoftHttpAppender-flusher");
            t.setDaemon(true);
            return t;
        });
        long interval = Math.max(50, flushIntervalMs);
        this.flusher.scheduleWithFixedDelay(this::flushSafely, interval, interval, TimeUnit.MILLISECONDS);
    }

    // ✅ Allow layout + endpoint update after deploy
    public synchronized void updateEndpointAndPattern(String url, String subscriptionKey, Layout<? extends Serializable> layout) {
        this.url = url;
        this.subscriptionKey = subscriptionKey;
        this.dynamicLayout = layout;
    }

    // ✅ Use dynamic layout instead of superclass one
    @Override
    public Layout<? extends Serializable> getLayout() {
        return this.dynamicLayout;
    }

    private static String jsonEscape(String input) {
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public void append(LogEvent event) {
        try {
            if (url == null || url.isEmpty()) {
                return;
            }
            String logMsg = toJson(event);
            if (logMsg == null) {
                return;
            }
            if (!queue.offer(logMsg)) {
                // Never block a channel thread on log shipping.
                droppedQueueFull.incrementAndGet();
                requestFlush();
                return;
            }
            if (queue.size() >= maxBatchEvents) {
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

    private void requestFlush() {
        if (flushRequested.compareAndSet(false, true)) {
            try {
                flusher.execute(this::flushSafely);
            } catch (Exception e) {
                // Executor already shut down (appender stopping); the final flush in stop() covers it.
                flushRequested.set(false);
            }
        }
    }

    private void flushSafely() {
        flushRequested.set(false);
        try {
            flush();
        } catch (Throwable t) {
            // An exception escaping a scheduled task would cancel every later flush.
            StatusLogger.getLogger().error("RamsoftAppender flush failed", t);
        }
    }

    /** Sends everything currently queued, one batch (one request) at a time. */
    void flush() {
        while (true) {
            List<String> batch = takeBatch();
            if (batch.isEmpty()) {
                return;
            }
            addDropReport(batch);
            send(batch);
            if (carryOver == null && queue.isEmpty()) {
                return;
            }
        }
    }

    private List<String> takeBatch() {
        List<String> batch = new ArrayList<>();
        int chars = 2;
        if (carryOver != null) {
            batch.add(carryOver);
            chars += carryOver.length();
            carryOver = null;
        }
        while (batch.size() < maxBatchEvents) {
            String next = queue.poll();
            if (next == null) {
                break;
            }
            if (!batch.isEmpty() && chars + 1 + next.length() > maxBatchChars) {
                // Does not fit; it leads the next batch (a single event is always sent on its own).
                carryOver = next;
                break;
            }
            batch.add(next);
            chars += next.length() + 1;
        }
        return batch;
    }

    private void addDropReport(List<String> batch) {
        long full = droppedQueueFull.getAndSet(0);
        long failed = droppedSendFailed.getAndSet(0);
        if (full == 0 && failed == 0) {
            return;
        }
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

    private void send(List<String> batch) {
        String target = url;
        String key = subscriptionKey;
        if (target == null || target.isEmpty()) {
            return;
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
                droppedSendFailed.addAndGet(batch.size());
                return;
            }
            HttpUrl.Builder httpUrlBuilder = parsed.newBuilder();
            if (key != null && !key.isEmpty()) {
                httpUrlBuilder.addQueryParameter("subscription-key", key);
            }
            Request request = new Request.Builder()
                    .url(httpUrlBuilder.build())
                    .post(RequestBody.create(JSON, body.toString()))
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    batchesSent.incrementAndGet();
                    eventsSent.addAndGet(batch.size());
                } else {
                    droppedSendFailed.addAndGet(batch.size());
                }
            }
        } catch (Exception e) {
            droppedSendFailed.addAndGet(batch.size());
        }
    }

    @Override
    public boolean stop(long timeout, TimeUnit timeUnit) {
        setStopping();
        flusher.shutdown();
        try {
            flusher.awaitTermination(Math.max(1, timeout), timeUnit == null ? TimeUnit.MILLISECONDS : timeUnit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            flush();
        } catch (Throwable t) {
            StatusLogger.getLogger().error("RamsoftAppender final flush failed", t);
        }
        boolean stopped = super.stop(timeout, timeUnit, false);
        setStopped();
        return stopped;
    }

    /** Diagnostics for Global Scripts / tests: "batches=..,events=..,queued=..,droppedPending=..". */
    public String getStats() {
        return "batches=" + batchesSent.get()
                + ",events=" + eventsSent.get()
                + ",queued=" + queue.size()
                + ",droppedPending=" + (droppedQueueFull.get() + droppedSendFailed.get());
    }
}

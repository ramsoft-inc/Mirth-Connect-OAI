package com.ramsoft.logging;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.StringLayout;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.status.StatusLogger;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
 * <p>Every entry in a batch is a well-formed JSON object: the layout output is checked, and an event
 * the layout did not render as valid JSON is rebuilt by the appender (marked with
 * {@code Properties.RamsoftAppender}) instead of making the receiver reject the whole batch.
 *
 * <p>Failure behaviour is deliberately lossy: the appender never blocks a channel thread, keeps at
 * most one request in flight, and drops a batch it could not deliver rather than retrying it. After
 * a failed send it stops early (size-triggered) flushes and sends at most one batch per interval
 * until a send succeeds again, so a slow or failing gateway sees less traffic from Mirth, not more.
 * The queue is bounded by event count and by total characters. Drops are counted and reported as a
 * WARN event from logger {@code RamsoftHttpAppender}, with the next batch or on its own; the first
 * failed send of a run of failures is also written to the log4j status logger.
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
    /**
     * UTF-8 bytes per request body. Measured on pre-us01: the receiver answers 200 to any size, but a
     * 3 MB batch never reached Loki (2 MB did); 512 KiB keeps a wide margin under that.
     */
    static final int DEFAULT_MAX_BATCH_BYTES = 512 * 1024;
    static final int DEFAULT_MAX_QUEUE_EVENTS = 20_000;
    /** About 32 MB of heap (UTF-16), whatever the size of individual debug lines. */
    static final int DEFAULT_MAX_QUEUE_CHARS = 16_000_000;
    /** Budget for the final flush in {@link #stop}, so a down or slow gateway cannot hold up a Mirth shutdown. */
    static final long DEFAULT_FINAL_FLUSH_BUDGET_MS = 10_000;
    static final String DROP_REPORT_LOGGER = "RamsoftHttpAppender";
    /** Key under {@code Properties} on an event the appender rebuilt because the layout output was not valid JSON. */
    static final String REBUILT_PROPERTY = "RamsoftAppender";

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final Pattern THREAD_PATTERN = Pattern.compile(".* on ([^()]+) \\([0-9a-f\\-]{36}\\), ([^()]+) \\(");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
    /** Room kept in every batch for a drop report (one event, well under this many bytes). */
    private static final int REPORT_RESERVE_BYTES = 1_024;

    /** Serialises every change of which instance ships for a name: claims, takeovers and stops. */
    private static final Object CLAIM_LOCK = new Object();
    /** The instance that ships logs, per appender name. */
    private static final ConcurrentMap<String, RamsoftAsyncHttpAppender> RUNNING = new ConcurrentHashMap<>();

    private volatile String url;
    private volatile String subscriptionKey;
    private volatile Layout<? extends Serializable> dynamicLayout;
    /** service/key from the last event the layout rendered as valid JSON; put on events the appender rebuilds. */
    private volatile Identity identity;
    private volatile Layout<? extends Serializable> malformedReportedFor;

    private final int flushIntervalMs;
    private final int maxBatchEvents;
    private final int maxBatchBytes;
    private final long maxQueueChars;
    private final BlockingQueue<Entry> queue;
    /** Heap held by the queue (UTF-16 chars), for the queue cap. */
    private final AtomicLong queuedChars = new AtomicLong();
    /** Wire size of the queue (UTF-8 bytes), for the size-triggered flush. */
    private final AtomicLong queuedBytes = new AtomicLong();
    private final AtomicBoolean flushRequested = new AtomicBoolean(false);
    private final AtomicLong droppedQueueFull = new AtomicLong();
    private final AtomicLong droppedSendFailed = new AtomicLong();
    private final AtomicLong droppedNoEndpoint = new AtomicLong();
    private final AtomicLong batchesSent = new AtomicLong();
    private final AtomicLong eventsSent = new AtomicLong();
    private final AtomicLong eventsRebuilt = new AtomicLong();
    private volatile boolean lastSendFailed;
    private volatile boolean stopRequested;
    volatile long finalFlushBudgetMs = DEFAULT_FINAL_FLUSH_BUDGET_MS;

    /** True while this instance is the one shipping for its name. Written under {@link #CLAIM_LOCK}. */
    private volatile boolean shipping;
    /** Set when this instance ships. */
    private volatile OkHttpClient httpClient;
    private volatile ScheduledExecutorService flusher;
    /** Set when another instance with this name was already shipping at start. */
    private volatile RamsoftAsyncHttpAppender runningInstance;

    // Guards carryOver and serialises flushes (flusher thread, stop(), tests).
    private final ReentrantLock flushLock = new ReentrantLock();
    private Entry carryOver;

    public RamsoftAsyncHttpAppender(String name, Filter filter, Layout<? extends Serializable> layout,
                                     boolean ignoreExceptions, String url, String subscriptionKey) {
        this(name, filter, layout, ignoreExceptions, url, subscriptionKey, DEFAULT_FLUSH_INTERVAL_MS,
                DEFAULT_MAX_BATCH_EVENTS, DEFAULT_MAX_BATCH_BYTES, DEFAULT_MAX_QUEUE_EVENTS, DEFAULT_MAX_QUEUE_CHARS);
    }

    public RamsoftAsyncHttpAppender(String name, Layout<? extends Serializable> layout,
                                     boolean ignoreExceptions, String url, String subscriptionKey) {
        this(name, null, layout, ignoreExceptions, url, subscriptionKey);
    }

    /**
     * Full constructor; the batching limits are exposed for tests and for tuning from Global Scripts.
     * {@code maxBatchBytes} is the UTF-8 size of a request body; {@code maxQueueChars} bounds the heap
     * the queue holds.
     */
    public RamsoftAsyncHttpAppender(String name, Filter filter, Layout<? extends Serializable> layout,
                                     boolean ignoreExceptions, String url, String subscriptionKey,
                                     int flushIntervalMs, int maxBatchEvents, int maxBatchBytes,
                                     int maxQueueEvents, int maxQueueChars) {
        super(name, filter, layout, ignoreExceptions, Property.EMPTY_ARRAY);
        this.dynamicLayout = layout;
        this.url = url;
        this.subscriptionKey = subscriptionKey;
        this.flushIntervalMs = Math.max(50, flushIntervalMs);
        this.maxBatchEvents = Math.max(2, maxBatchEvents); // one slot is kept for a drop report
        this.maxBatchBytes = Math.max(1, maxBatchBytes);
        this.maxQueueChars = Math.max(1, maxQueueChars);
        this.queue = new ArrayBlockingQueue<>(Math.max(1, maxQueueEvents));
    }

    @Override
    public void start() {
        synchronized (CLAIM_LOCK) {
            stopRequested = false; // a start after a stop ships again
            claimLocked();
        }
        super.start();
    }

    private static boolean isShipping(RamsoftAsyncHttpAppender a) {
        return a.shipping && !a.stopRequested;
    }

    /** Becomes the shipping instance for this name, or a stand-in for the one that already is. */
    private void claimLocked() {
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
        ScheduledExecutorService existing = flusher;
        if (shipping && existing != null && !existing.isShutdown()) {
            return; // start() called again on the instance that is already shipping
        }
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
        shipping = true;
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
        synchronized (CLAIM_LOCK) {
            if (runningInstance == running && !stopRequested && isStarted()) {
                claimLocked();
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

    /** UTF-8 length of {@code s}, without encoding it (a lone surrogate counts 3, never less than it encodes to). */
    static int utf8Length(String s) {
        int bytes = 0;
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                bytes++;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    @Override
    public void append(LogEvent event) {
        RamsoftAsyncHttpAppender running = liveRunningInstance();
        if (running != null) {
            running.append(event);
            return;
        }
        try {
            String target = url;
            if (target == null || target.isEmpty()) {
                // Before "Check Mirth Health" sets the endpoint (Mirth startup). Reported once it is set.
                droppedNoEndpoint.incrementAndGet();
                return;
            }
            Entry entry = new Entry(toJson(event));
            int chars = entry.json.length();
            // Reserve the characters first so concurrent channel threads cannot overshoot the cap together.
            if (queuedChars.addAndGet(chars) > maxQueueChars) {
                queuedChars.addAndGet(-chars);
                dropQueueFull();
                return;
            }
            queuedBytes.addAndGet(entry.bytes);
            if (!queue.offer(entry)) {
                queuedChars.addAndGet(-chars);
                queuedBytes.addAndGet(-entry.bytes);
                dropQueueFull();
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

    private void dropQueueFull() {
        // Never block a channel thread on log shipping, and never let a backlog grow the heap unbounded.
        droppedQueueFull.incrementAndGet();
        requestFlush();
    }

    /**
     * Renders one event with the current layout and injects channelName/connectorName. Output the
     * receiver could not parse as a JSON object is replaced by an object the appender builds itself.
     */
    private String toJson(LogEvent event) {
        String threadName = event.getThreadName() != null ? event.getThreadName() : "";
        Layout<? extends Serializable> layout = getLayout();
        if (layout != null) {
            String rendered = render(layout, event).trim();
            JsonObjectCheck check = JsonObjectCheck.of(rendered);
            if (check.valid) {
                rememberIdentity(check);
                StringBuilder sb = new StringBuilder(rendered.length() + 96);
                sb.append(rendered, 0, rendered.length() - 1); // drop the closing brace
                appendNames(sb, threadName, check.members > 0);
                return sb.append('}').toString();
            }
            reportMalformed(layout);
        }
        eventsRebuilt.incrementAndGet();
        return rebuild(event, threadName, true);
    }

    /**
     * The layout's text without an encoding round trip. The layouts in use are built without a
     * charset, so on Windows their bytes are windows-1252: decoding those as UTF-8 turned every
     * non-ASCII character into U+FFFD or '?'.
     */
    private static String render(Layout<? extends Serializable> layout, LogEvent event) {
        Serializable serialized = layout.toSerializable(event);
        if (serialized instanceof String) {
            return (String) serialized;
        }
        Charset charset = layout instanceof StringLayout ? ((StringLayout) layout).getCharset() : StandardCharsets.UTF_8;
        return new String(layout.toByteArray(event), charset);
    }

    private void rememberIdentity(JsonObjectCheck check) {
        Identity current = identity;
        if (current == null || !Objects.equals(current.service, check.service) || !Objects.equals(current.key, check.key)) {
            identity = new Identity(check.service, check.key);
        }
    }

    private void reportMalformed(Layout<? extends Serializable> layout) {
        if (malformedReportedFor != layout) {
            malformedReportedFor = layout;
            StatusLogger.getLogger().error("RamsoftHttpAppender: the layout rendered an event that is not a valid JSON"
                    + " object; such events are rebuilt by the appender and marked Properties." + REBUILT_PROPERTY);
        }
    }

    private static void appendNames(StringBuilder sb, String threadName, boolean afterMember) {
        String channelName = "Unknown";
        String connectorName = "Unknown";
        Matcher matcher = THREAD_PATTERN.matcher(threadName);
        if (matcher.find()) {
            channelName = matcher.group(1);
            connectorName = matcher.group(2);
        }
        if (afterMember) {
            sb.append(',');
        }
        sb.append("\"channelName\":\"").append(jsonEscape(channelName))
                .append("\",\"connectorName\":\"").append(jsonEscape(connectorName)).append('"');
    }

    /**
     * Builds the event as a JSON object with the fields the layouts in use produce, taking service and
     * key from the last valid render. Used for drop reports and for events the layout broke.
     */
    private String rebuild(LogEvent event, String threadName, boolean layoutFailed) {
        Message message = event.getMessage();
        String loggerName = event.getLoggerName() != null ? event.getLoggerName() : "";
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"timestamp\":\"")
                .append(TIMESTAMP.format(Instant.ofEpochMilli(event.getTimeMillis()).atZone(ZoneId.systemDefault())))
                .append("\",\"level\":\"").append(event.getLevel())
                .append("\",\"logger\":\"").append(jsonEscape(loggerName.substring(loggerName.lastIndexOf('.') + 1)))
                .append("\",\"message\":\"").append(jsonEscape(message != null ? message.getFormattedMessage() : ""))
                .append("\",\"exception\":\"").append(jsonEscape(stackTrace(event.getThrown())))
                .append("\",\"thread\":\"").append(jsonEscape(threadName)).append('"');
        Identity id = identity;
        if (id != null && id.service != null) {
            sb.append(",\"service\":\"").append(jsonEscape(id.service)).append('"');
        }
        if (id != null && id.key != null) {
            sb.append(",\"key\":\"").append(jsonEscape(id.key)).append('"');
        }
        appendNames(sb, threadName, true);
        if (layoutFailed) {
            sb.append(",\"Properties\":{\"").append(REBUILT_PROPERTY)
                    .append("\":\"layout output was not a valid JSON object; rebuilt by the appender\"}");
        }
        return sb.append('}').toString();
    }

    private static String stackTrace(Throwable thrown) {
        if (thrown == null) {
            return "";
        }
        StringWriter sw = new StringWriter();
        thrown.printStackTrace(new PrintWriter(sw));
        return sw.toString().trim();
    }

    /** A full batch is waiting, by event count (one slot kept for a drop report) or by size. */
    private boolean fullBatchWaiting() {
        return queue.size() >= maxBatchEvents - 1
                || queuedBytes.get() >= Math.max(1, maxBatchBytes - REPORT_RESERVE_BYTES);
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
            String target = url;
            if (target == null || target.isEmpty()) {
                return; // nothing can be delivered yet; counts wait for an endpoint
            }
            long full = droppedQueueFull.getAndSet(0);
            long failed = droppedSendFailed.getAndSet(0);
            long noEndpoint = droppedNoEndpoint.getAndSet(0);
            boolean reporting = full > 0 || failed > 0 || noEndpoint > 0;
            List<Entry> batch = takeBatch(reporting);
            int events = batch.size();
            if (reporting && events > 0 && bodyBytes(batch) + REPORT_RESERVE_BYTES > maxBatchBytes) {
                // A single event over the size cap goes on its own; the report waits for the next batch.
                restoreDropCounts(full, failed, noEndpoint);
                reporting = false;
            }
            if (reporting) {
                batch.add(dropReport(full, failed, noEndpoint));
            }
            if (batch.isEmpty()) {
                return;
            }
            String failure = send(batch);
            if (failure == null) {
                if (lastSendFailed) {
                    StatusLogger.getLogger().warn("RamsoftHttpAppender: log batches are being delivered again");
                }
                lastSendFailed = false;
                batchesSent.incrementAndGet();
                eventsSent.addAndGet(events);
            } else {
                if (!lastSendFailed) {
                    // Once per run of failures: drops are reported in-band only after delivery recovers.
                    StatusLogger.getLogger().error("RamsoftHttpAppender: a log batch was not delivered ({}); batches"
                            + " are dropped and counted until a send succeeds", failure);
                }
                lastSendFailed = true;
                // The report went down with the batch: put its counts back so the next one is complete.
                if (reporting) {
                    restoreDropCounts(full, failed, noEndpoint);
                }
                droppedSendFailed.addAndGet(events);
                return;
            }
            // carryOver is no longer counted in queuedBytes, so it is checked on its own.
            boolean more = carryOver != null || (drain ? !queue.isEmpty() : fullBatchWaiting());
            if (!more) {
                return;
            }
        }
    }

    private void restoreDropCounts(long full, long failed, long noEndpoint) {
        droppedQueueFull.addAndGet(full);
        droppedSendFailed.addAndGet(failed);
        droppedNoEndpoint.addAndGet(noEndpoint);
    }

    private List<Entry> takeBatch(boolean reserveForReport) {
        int maxEvents = reserveForReport ? maxBatchEvents - 1 : maxBatchEvents;
        int maxBytes = reserveForReport ? Math.max(1, maxBatchBytes - REPORT_RESERVE_BYTES) : maxBatchBytes;
        List<Entry> batch = new ArrayList<>();
        int bytes = 2;
        if (carryOver != null) {
            batch.add(carryOver);
            bytes += carryOver.bytes;
            carryOver = null;
        }
        while (batch.size() < maxEvents) {
            Entry next = queue.poll();
            if (next == null) {
                break;
            }
            queuedChars.addAndGet(-next.json.length());
            queuedBytes.addAndGet(-next.bytes);
            if (!batch.isEmpty() && bytes + 1 + next.bytes > maxBytes) {
                // Does not fit; it leads the next batch (a single event is always sent on its own).
                carryOver = next;
                break;
            }
            batch.add(next);
            bytes += next.bytes + 1;
        }
        return batch;
    }

    private static int bodyBytes(List<Entry> batch) {
        int bytes = 2 + Math.max(0, batch.size() - 1);
        for (Entry entry : batch) {
            bytes += entry.bytes;
        }
        return bytes;
    }

    private Entry dropReport(long full, long failed, long noEndpoint) {
        String threadName = Thread.currentThread().getName();
        LogEvent report = Log4jLogEvent.newBuilder()
                .setLoggerName(DROP_REPORT_LOGGER)
                .setLevel(Level.WARN)
                .setThreadName(threadName)
                .setTimeMillis(System.currentTimeMillis())
                .setMessage(new SimpleMessage("RamsoftHttpAppender dropped " + (full + failed + noEndpoint)
                        + " log events since the last delivered batch (queue full: " + full
                        + ", send failed: " + failed + ", no endpoint yet: " + noEndpoint + ")"))
                .build();
        // Built by the appender, not the layout, so a report can always be sent.
        return new Entry(rebuild(report, threadName, false));
    }

    /** POSTs one batch; returns null on a 2xx, otherwise why it failed. Never retried. */
    private String send(List<Entry> batch) {
        String target = url;
        String key = subscriptionKey;
        OkHttpClient client = httpClient;
        if (target == null || target.isEmpty() || client == null) {
            return "no endpoint";
        }
        StringBuilder body = new StringBuilder(2 + bodyBytes(batch));
        body.append('[');
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append(batch.get(i).json);
        }
        body.append(']');

        try {
            HttpUrl parsed = HttpUrl.parse(target);
            if (parsed == null) {
                return "endpoint is not a valid URL";
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
                return response.isSuccessful() ? null : "HTTP " + response.code();
            }
        } catch (Exception e) {
            // The class name only: a message can carry the URL, and the URL carries the subscription key.
            return e.getClass().getSimpleName();
        }
    }

    /**
     * Stops the flusher and sends what is still queued within the final-flush budget, or within the
     * caller's timeout when that is shorter (log4j passes 0 for "no limit"). A regular flush in
     * progress finishes its current request and yields; the final flush checks the budget before
     * every request, so a stop takes at most the budget plus one request's timeouts.
     */
    @Override
    public boolean stop(long timeout, TimeUnit timeUnit) {
        setStopping();
        boolean wasShipping;
        synchronized (CLAIM_LOCK) {
            stopRequested = true;
            wasShipping = shipping;
            shipping = false;
        }
        if (wasShipping) {
            long budgetMs = finalFlushBudgetMs;
            if (timeout > 0) {
                budgetMs = Math.min(budgetMs, timeUnit.toMillis(timeout));
            }
            stopShipping(budgetMs);
            RUNNING.remove(getName(), this);
        }
        boolean stopped = super.stop(timeout, timeUnit, false);
        setStopped();
        return stopped;
    }

    private void stopShipping(long budgetMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMs);
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

    /** Diagnostics for Global Scripts / tests: "batches=..,events=..,queued=..,droppedPending=..,rebuilt=..". */
    public String getStats() {
        RamsoftAsyncHttpAppender running = runningInstance;
        if (running != null) {
            return running.getStats();
        }
        return "batches=" + batchesSent.get()
                + ",events=" + eventsSent.get()
                + ",queued=" + queue.size()
                + ",droppedPending=" + (droppedQueueFull.get() + droppedSendFailed.get() + droppedNoEndpoint.get())
                + ",rebuilt=" + eventsRebuilt.get();
    }

    boolean isStandIn() {
        return runningInstance != null;
    }

    /** One rendered event and its size on the wire. */
    private static final class Entry {
        final String json;
        final int bytes;

        Entry(String json) {
            this.json = json;
            this.bytes = utf8Length(json);
        }
    }

    private static final class Identity {
        final String service;
        final String key;

        Identity(String service, String key) {
            this.service = service;
            this.key = key;
        }
    }
}

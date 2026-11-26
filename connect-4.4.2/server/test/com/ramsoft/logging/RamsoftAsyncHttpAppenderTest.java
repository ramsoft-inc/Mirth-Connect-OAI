package com.ramsoft.logging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.layout.AbstractStringLayout;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.status.StatusData;
import org.apache.logging.log4j.status.StatusListener;
import org.apache.logging.log4j.status.StatusLogger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

public class RamsoftAsyncHttpAppenderTest {

    // The pattern "Check Mirth Health" sets with updateEndpointAndPattern before anything is sent
    // (the Global Scripts registration starts with an empty endpoint, so nothing ships before that).
    private static final String PATTERN = "{\"timestamp\":\"%d{yyyy-MM-dd'T'HH:mm:ss.SSS,local}\",\"level\":\"%p\","
            + "\"logger\":\"%logger{1}\",\"message\":\"%enc{%m}{JSON}\",\"exception\":\"%enc{%ex{full}}{JSON}\","
            + "\"thread\":\"%enc{%t}{JSON}\",\"service\":\"MirthConnectService\",\"key\":\"unit-test\"}%n";

    private static final String CHANNEL_THREAD =
            "JavaScript Writer JavaScript Task on Outbound OAI Polling (b06969aa-6233-4fde-a1ce-6238c9ae0c58), "
            + "Subscription Handle (1) < pool-1-thread-374";

    private static final long NEVER = 600_000;

    private HttpServer server;
    private final List<String> bodies = Collections.synchronizedList(new ArrayList<String>());
    private final List<String> queries = Collections.synchronizedList(new ArrayList<String>());
    private final AtomicInteger statusToReturn = new AtomicInteger(200);
    private volatile int responseDelayMs;
    private volatile CountDownLatch requestLatch = new CountDownLatch(0);
    private volatile CountDownLatch arrivedLatch = new CountDownLatch(0);
    private String url;
    private final List<RamsoftAsyncHttpAppender> appenders = new ArrayList<>();
    /** Requests being handled right now, and the most at once: two shippers would show up as 2. */
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private ExecutorService serverThreads;

    @Before
    public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/LogReceiver", exchange -> {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                bodies.add(readAll(exchange.getRequestBody()));
                queries.add(exchange.getRequestURI().getRawQuery());
                arrivedLatch.countDown();
                if (responseDelayMs > 0) {
                    try {
                        Thread.sleep(responseDelayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                byte[] reply = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(statusToReturn.get(), reply.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(reply);
                }
            } finally {
                inFlight.decrementAndGet();
            }
            requestLatch.countDown();
        });
        // Concurrent handling, so overlapping senders are visible in maxInFlight.
        serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/LogReceiver";
    }

    @After
    public void stopAll() {
        responseDelayMs = 0;
        for (RamsoftAsyncHttpAppender a : appenders) {
            if (!a.isStopped()) {
                a.stop(5, TimeUnit.SECONDS);
            }
        }
        server.stop(0);
        serverThreads.shutdownNow();
    }

    private RamsoftAsyncHttpAppender newAppender(String target, long flushMs, int maxEvents, int maxChars, int maxQueue) {
        return newAppender(target, "test-key", flushMs, maxEvents, maxChars, maxQueue, 16_000_000);
    }

    private RamsoftAsyncHttpAppender newAppender(String target, String key, long flushMs, int maxEvents, int maxChars,
                                                 int maxQueue, int maxQueueChars) {
        RamsoftAsyncHttpAppender a = new RamsoftAsyncHttpAppender("RamsoftHttp", null, layout(), false, target, key,
                (int) flushMs, maxEvents, maxChars, maxQueue, maxQueueChars);
        appenders.add(a);
        a.start();
        return a;
    }

    private static Layout<? extends Serializable> layout() {
        return PatternLayout.newBuilder().withPattern(PATTERN).build();
    }

    private static LogEvent event(String message, String thread) {
        return Log4jLogEvent.newBuilder()
                .setLoggerName("transformer")
                .setLevel(Level.INFO)
                .setThreadName(thread)
                .setTimeMillis(System.currentTimeMillis())
                .setMessage(new SimpleMessage(message))
                .build();
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static JsonArray array(String body) {
        JsonElement parsed = new JsonParser().parse(body);
        assertTrue("every request body must be a JSON array: " + body, parsed.isJsonArray());
        return parsed.getAsJsonArray();
    }

    private List<JsonObject> allEntries() {
        List<JsonObject> entries = new ArrayList<>();
        synchronized (bodies) {
            for (String body : bodies) {
                for (JsonElement e : array(body)) {
                    entries.add(e.getAsJsonObject());
                }
            }
        }
        return entries;
    }

    private List<JsonObject> entriesFromLogger(String logger) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject o : allEntries()) {
            if (logger.equals(o.get("logger").getAsString())) {
                out.add(o);
            }
        }
        return out;
    }

    private static int flusherThreads() {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if ("RamsoftHttpAppender-flusher".equals(t.getName()) && t.isAlive()) {
                n++;
            }
        }
        return n;
    }

    /**
     * Flusher threads once the count reaches {@code expected}, or after 3 s. stop() ends with
     * shutdownNow(), which only interrupts, so a stopped appender's thread can outlive stop() briefly.
     */
    private static int awaitFlusherThreads(int expected) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        int n = flusherThreads();
        while (n != expected && System.nanoTime() < end) {
            Thread.sleep(20);
            n = flusherThreads();
        }
        return n;
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    private static LogEvent eventWithError(String message, String thread, Throwable thrown) {
        return Log4jLogEvent.newBuilder()
                .setLoggerName("com.mirth.connect.transformer")
                .setLevel(Level.ERROR)
                .setThreadName(thread)
                .setTimeMillis(System.currentTimeMillis())
                .setMessage(new SimpleMessage(message))
                .setThrown(thrown)
                .build();
    }

    /** Collects what the appender writes to the log4j status logger. */
    private static final class StatusCapture implements StatusListener {
        final List<StatusData> data = Collections.synchronizedList(new ArrayList<StatusData>());

        @Override
        public void log(StatusData d) {
            if (d.getMessage().getFormattedMessage().contains("RamsoftHttpAppender")) {
                data.add(d);
            }
        }

        @Override
        public Level getStatusLevel() {
            return Level.WARN;
        }

        @Override
        public void close() {
        }

        List<String> messages(Level level) {
            List<String> out = new ArrayList<>();
            synchronized (data) {
                for (StatusData d : data) {
                    if (d.getLevel() == level) {
                        out.add(d.getMessage().getFormattedMessage());
                    }
                }
            }
            return out;
        }
    }

    @Test(timeout = 20000)
    public void manyEventsWithinOneIntervalGoOutAsOneArrayRequest() throws Exception {
        RamsoftAsyncHttpAppender appender = newAppender(url, NEVER, 500, 1_000_000, 10_000);
        for (int i = 0; i < 100; i++) {
            appender.append(event("line " + i, "main"));
        }
        appender.flush();

        assertEquals("100 events in one interval must be one request, not 100", 1, bodies.size());
        List<JsonObject> entries = allEntries();
        assertEquals(100, entries.size());
        for (int i = 0; i < 100; i++) {
            assertEquals("order must be preserved", "line " + i, entries.get(i).get("message").getAsString());
        }
        assertEquals("subscription-key=test-key", queries.get(0));
    }

    @Test(timeout = 20000)
    public void theScheduledFlushSendsWithoutBeingAsked() throws Exception {
        requestLatch = new CountDownLatch(1);
        RamsoftAsyncHttpAppender appender = newAppender(url, 200, 500, 1_000_000, 10_000);
        appender.append(event("on its own", "main"));
        assertTrue(requestLatch.await(10, TimeUnit.SECONDS));
        assertEquals("on its own", allEntries().get(0).get("message").getAsString());
    }

    @Test(timeout = 20000)
    public void channelAndConnectorAreParsedFromTheThreadName() throws Exception {
        RamsoftAsyncHttpAppender appender = newAppender(url, NEVER, 500, 1_000_000, 10_000);
        appender.append(event("from a channel", CHANNEL_THREAD));
        appender.append(event("from elsewhere", "main"));
        appender.flush();

        List<JsonObject> entries = allEntries();
        assertEquals("Outbound OAI Polling", entries.get(0).get("channelName").getAsString());
        assertEquals("Subscription Handle", entries.get(0).get("connectorName").getAsString());
        assertEquals("Unknown", entries.get(1).get("channelName").getAsString());
        assertEquals("Unknown", entries.get(1).get("connectorName").getAsString());
    }

    @Test(timeout = 20000)
    public void quotesBackslashesAndControlCharactersInNamesStayValidJson() throws Exception {
        String thread = "JavaScript Writer on Send \"ADT\"\tA\\B (b06969aa-6233-4fde-a1ce-6238c9ae0c58), "
                + "Dest\u0001 \"1\" (1) < pool-1-thread-9";
        RamsoftAsyncHttpAppender appender = newAppender(url, NEVER, 500, 1_000_000, 10_000);
        appender.append(event("odd names", thread));
        appender.flush();

        String body = bodies.get(0);
        for (char c : body.toCharArray()) {
            assertTrue("raw control character 0x" + Integer.toHexString(c) + " in body: " + body, c >= 0x20);
        }
        JsonObject entry = allEntries().get(0);
        assertEquals("Send \"ADT\"\tA\\B", entry.get("channelName").getAsString());
        assertEquals("Dest\u0001 \"1\"", entry.get("connectorName").getAsString());
        assertEquals(thread, entry.get("thread").getAsString());
    }

    @Test(timeout = 20000)
    public void aFullBatchIsSentWithoutWaitingForTheInterval() throws Exception {
        requestLatch = new CountDownLatch(2);
        RamsoftAsyncHttpAppender appender = newAppender(url, NEVER, 10, 1_000_000, 10_000);

        for (int i = 0; i < 25; i++) {
            appender.append(event("line " + i, "main"));
        }
        assertTrue("two full batches must be sent before the interval", requestLatch.await(10, TimeUnit.SECONDS));

        appender.stop(5, TimeUnit.SECONDS);
        assertEquals("stop() must flush the rest", 25, allEntries().size());
        for (String body : bodies) {
            assertTrue(array(body).size() <= 10);
        }
    }

    @Test(timeout = 20000)
    public void aBatchWorthOfCharactersIsSentWithoutWaitingForTheInterval() throws Exception {
        requestLatch = new CountDownLatch(1);
        RamsoftAsyncHttpAppender appender = newAppender(url, NEVER, 500, 3_000, 10_000);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            big.append('x');
        }
        for (int i = 0; i < 4; i++) {
            appender.append(event(big.toString(), "main")); // ~800 chars each: well under 500 events, over the size cap
        }
        assertTrue("a size-full queue must trigger an early flush", requestLatch.await(5, TimeUnit.SECONDS));
    }

    @Test(timeout = 20000)
    public void anEarlyFlushQueuedWhileARequestWasFailingDoesNotSendRightAfterIt() throws Exception {
        statusToReturn.set(503);
        responseDelayMs = 400;
        arrivedLatch = new CountDownLatch(1);
        RamsoftAsyncHttpAppender appender = newAppender(url, NEVER, 10, 1_000_000, 10_000);
        for (int i = 0; i < 10; i++) {
            appender.append(event("first " + i, "main")); // early flush #1 starts; its request is slow and fails
        }
        assertTrue(arrivedLatch.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < 10; i++) {
            appender.append(event("during " + i, "main")); // queues early flush #2 while #1 is still in flight
        }
        Thread.sleep(1_500);
        assertEquals("the queued early flush must back off once #1 failed", 1, bodies.size());
    }

    @Test(timeout = 20000)
    public void anOversizedEventGoesAloneAndItsDropReportFollowsSeparately() throws Exception {
        statusToReturn.set(503);
        RamsoftAsyncHttpAppender appender = newAppender(url, NEVER, 500, 1_500, 10_000);
        appender.append(event("lost", "main"));
        appender.flush();

        statusToReturn.set(200);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 2_000; i++) {
            big.append('x');
        }
        appender.append(event(big.toString(), "main"));
        appender.flush();
        appender.flush();

        for (String body : bodies) {
            assertTrue("only a lone event may exceed the size cap: " + body.length(),
                    body.length() <= 1_500 || array(body).size() == 1);
        }
        assertEquals(1, entriesFromLogger(RamsoftAsyncHttpAppender.DROP_REPORT_LOGGER).size());
    }

    @Test(timeout = 30000)
    public void steadyTrafficIsSentOncePerIntervalNotOncePerRoundTrip() throws Exception {
        responseDelayMs = 50;
        RamsoftAsyncHttpAppender appender = newAppender(url, 1_000, 500, 1_000_000, 10_000);

        long end = System.currentTimeMillis() + 3_500;
        int appended = 0;
        while (System.currentTimeMillis() < end) {
            appender.append(event("steady " + appended++, "main")); // ~200 events/s, far below a full batch per second
            Thread.sleep(5);
        }
        int requests = bodies.size();
        assertTrue("3.5 s of steady traffic at a 1 s interval must take about 3 requests, not one per round trip; got "
                + requests, requests <= 5);
        appender.stop(5, TimeUnit.SECONDS);
        assertEquals(appended, allEntries().size());
    }

    @Test(timeout = 20000)
    public void batchesRespectTheCharacterCap() throws Exception {
        RamsoftAsyncHttpAppender appender = newAppender(url, NEVER, 500, 2_000, 10_000);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 600; i++) {
            big.append('x');
        }
        for (int i = 0; i < 10; i++) {
            appender.append(event(i + big.toString(), "main"));
        }
        appender.stop(5, TimeUnit.SECONDS);

        assertEquals(10, allEntries().size());
        assertTrue("10 ~800-char events under a 2000-char cap need several requests", bodies.size() >= 4);
        for (String body : bodies) {
            int count = array(body).size();
            assertTrue("a batch over the cap may only hold a single event", body.length() <= 2_000 || count == 1);
        }
    }

    @Test(timeout = 20000)
    public void aFailedBatchIsDroppedNotRetriedAndReportedInTheNextBatch() throws Exception {
        statusToReturn.set(503);
        RamsoftAsyncHttpAppender appender = newAppender(url, NEVER, 500, 1_000_000, 10_000);
        for (int i = 0; i < 7; i++) {
            appender.append(event("lost " + i, "main"));
        }
        appender.flush();
        assertEquals("exactly one attempt, no retry", 1, bodies.size());

        statusToReturn.set(200);
        appender.append(event("after recovery", "main"));
        appender.flush();

        assertEquals(2, bodies.size());
        JsonArray second = array(bodies.get(1));
        assertEquals("after recovery", second.get(0).getAsJsonObject().get("message").getAsString());
        JsonObject report = second.get(1).getAsJsonObject();
        assertEquals(RamsoftAsyncHttpAppender.DROP_REPORT_LOGGER, report.get("logger").getAsString());
        assertEquals("WARN", report.get("level").getAsString());
        assertTrue(report.get("message").getAsString().contains("dropped 7 "));
        assertTrue(report.get("message").getAsString().contains("send failed: 7"));
        assertEquals("unit-test", report.get("key").getAsString());
    }

    @Test(timeout = 20000)
    public void consecutiveFailedBatchesAreAllCountedInTheNextDeliveredReport() throws Exception {
        statusToReturn.set(503);
        RamsoftAsyncHttpAppender appender = newAppender(url, NEVER, 500, 1_000_000, 10_000);
        for (int i = 0; i < 7; i++) {
            appender.append(event("lost " + i, "main"));
        }
        appender.flush();
        appender.append(event("also lost", "main"));
        appender.flush(); // carries the first report, and fails too

        statusToReturn.set(200);
        appender.append(event("after recovery", "main"));
        appender.flush();

        assertEquals(3, bodies.size());
        JsonArray third = array(bodies.get(2));
        assertEquals(2, third.size());
        String report = third.get(1).getAsJsonObject().get("message").getAsString();
        assertTrue(report, report.contains("dropped 8 "));
        assertTrue(report, report.contains("send failed: 8"));
        assertTrue(appender.getStats(), appender.getStats().contains("droppedPending=0"));
    }

    @Test(timeout = 20000)
    public void aPendingDropReportIsSentOnItsOwnWhenNothingElseIsQueued() throws Exception {
        statusToReturn.set(503);
        RamsoftAsyncHttpAppender appender = newAppender(url, NEVER, 500, 1_000_000, 10_000);
        appender.append(event("lost", "main"));
        appender.flush();

        statusToReturn.set(200);
        appender.flush(); // nothing queued, but a report is owed

        assertEquals(2, bodies.size());
        JsonArray second = array(bodies.get(1));
        assertEquals(1, second.size());
        assertEquals(RamsoftAsyncHttpAppender.DROP_REPORT_LOGGER, second.get(0).getAsJsonObject().get("logger").getAsString());
    }

    @Test(timeout = 20000)
    public void aDropReportDoesNotPushABatchOverItsEventLimit() throws Exception {
        statusToReturn.set(503);
        RamsoftAsyncHttpAppender appender = newAppender(url, NEVER, 5, 1_000_000, 10_000);
        appender.append(event("lost", "main"));
        appender.flush();

        statusToReturn.set(200);
        for (int i = 0; i < 12; i++) {
            appender.append(event("line " + i, "main"));
        }
        appender.stop(5, TimeUnit.SECONDS);

        for (String body : bodies) {
            assertTrue(body, array(body).size() <= 5);
        }
        assertEquals(1, entriesFromLogger(RamsoftAsyncHttpAppender.DROP_REPORT_LOGGER).size());
        // Every body the server saw: the failed attempt carrying "lost", then all 12 later events.
        assertEquals(13, entriesFromLogger("transformer").size());
    }

    @Test(timeout = 20000)
    public void afterAFailedSendTheGatewayGetsOneRequestPerFlushNotOnePerQueuedBatch() throws Exception {
        statusToReturn.set(503);
        requestLatch = new CountDownLatch(1);
        RamsoftAsyncHttpAppender appender = newAppender(url, NEVER, 10, 1_000_000, 10_000);

        for (int i = 0; i < 10; i++) {
            appender.append(event("line " + i, "main")); // a full batch triggers an early flush, which fails
        }
        assertTrue(requestLatch.await(10, TimeUnit.SECONDS));
        Thread.sleep(200);

        for (int i = 0; i < 100; i++) {
            appender.append(event("more " + i, "main")); // ten more full batches: no early flush while failing
        }
        Thread.sleep(500);
        assertEquals("no early flush may fire while the gateway is failing", 1, bodies.size());

        appender.flush();
        assertEquals("a flush stops at the first failed batch instead of trying all ten", 2, bodies.size());
    }

    @Test(timeout = 20000)
    public void stopGivesUpAtTheFirstFailedBatchSoShutdownIsNotHeldUp() throws Exception {
        statusToReturn.set(503);
        RamsoftAsyncHttpAppender appender = newAppender(url, NEVER, 10, 1_000_000, 10_000);
        appender.append(event("first", "main"));
        appender.flush(); // now failing: size-triggered flushes are off
        for (int i = 0; i < 50; i++) {
            appender.append(event("pending " + i, "main"));
        }

        appender.stop(5, TimeUnit.SECONDS);
        assertTrue(appender.isStopped());
        assertEquals("one attempt at the final flush, not one per queued batch", 2, bodies.size());
    }

    @Test(timeout = 30000)
    public void stopIsBoundedEvenWhileTheFlusherIsDrainingThroughASlowGateway() throws Exception {
        responseDelayMs = 500;
        requestLatch = new CountDownLatch(1);
        RamsoftAsyncHttpAppender appender = newAppender(url, NEVER, 10, 1_000_000, 10_000);
        appender.finalFlushBudgetMs = 1_500;
        for (int i = 0; i < 300; i++) {
            appender.append(event("pending " + i, "main")); // 30 full batches: ~15 s if drained one by one
        }
        assertTrue(requestLatch.await(10, TimeUnit.SECONDS)); // the flusher is now mid-drain

        long start = System.nanoTime();
        appender.stop(0, TimeUnit.MILLISECONDS); // log4j's shutdown passes 0
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertTrue("stop took " + elapsedMs + " ms", elapsedMs < 4_000);
        assertTrue(appender.isStopped());
        int atStop = bodies.size();
        assertTrue("requests: " + atStop, atStop < 30);
        Thread.sleep(1_500);
        assertEquals("the flusher must not keep sending after stop() returned", atStop, bodies.size());
    }

    @Test(timeout = 20000)
    public void aFullQueueDropsInsteadOfBlockingTheCaller() throws Exception {
        // Unreachable endpoint (closed port) and no early flush can drain it: the queue must fill.
        server.stop(0);
        RamsoftAsyncHttpAppender appender = newAppender("http://127.0.0.1:1/LogReceiver", NEVER, 1_000_000, 1_000_000, 50);

        long start = System.nanoTime();
        for (int i = 0; i < 5_000; i++) {
            appender.append(event("line " + i, "main"));
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue("append() must not block when the queue is full (took " + elapsedMs + " ms)", elapsedMs < 5_000);
        assertTrue(appender.getStats(), appender.getStats().contains("queued=50"));
    }

    @Test(timeout = 20000)
    public void theQueueIsAlsoBoundedByTotalSizeNotJustEventCount() throws Exception {
        server.stop(0);
        RamsoftAsyncHttpAppender appender = newAppender("http://127.0.0.1:1/LogReceiver", "k", NEVER, 1_000_000,
                1_000_000, 10_000, 5_000);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            big.append('x');
        }
        for (int i = 0; i < 100; i++) {
            appender.append(event(big.toString(), "main")); // ~600 chars each once rendered
        }
        String stats = appender.getStats();
        int queued = Integer.parseInt(stats.replaceAll(".*queued=(\\d+).*", "$1"));
        assertTrue(stats, queued > 0 && queued <= 9);
        assertFalse(stats, stats.contains("droppedPending=0"));
    }

    @Test(timeout = 20000)
    public void nothingIsSentOrQueuedWithoutAnEndpointButTheEventsAreCounted() throws Exception {
        RamsoftAsyncHttpAppender appender = newAppender("", 100, 500, 1_000_000, 10_000);
        for (int i = 0; i < 10; i++) {
            appender.append(event("line " + i, "main"));
        }
        Thread.sleep(400);
        assertTrue(bodies.isEmpty());
        assertTrue(appender.getStats(), appender.getStats().contains("queued=0"));
        assertTrue(appender.getStats(), appender.getStats().contains("droppedPending=10"));
    }

    @Test(timeout = 20000)
    public void updateEndpointAndPatternRedirectsLaterBatchesAndReportsWhatCameBefore() throws Exception {
        RamsoftAsyncHttpAppender appender = newAppender("", NEVER, 500, 1_000_000, 10_000);
        appender.append(event("before endpoint", "main"));
        appender.append(event("also before endpoint", "main"));

        appender.updateEndpointAndPattern(url, "rotated-key", layout());
        appender.append(event("after endpoint", "main"));
        appender.flush();

        assertEquals(1, bodies.size());
        assertEquals("subscription-key=rotated-key", queries.get(0));
        List<JsonObject> shipped = entriesFromLogger("transformer");
        assertEquals(1, shipped.size());
        assertEquals("after endpoint", shipped.get(0).get("message").getAsString());
        List<JsonObject> reports = entriesFromLogger(RamsoftAsyncHttpAppender.DROP_REPORT_LOGGER);
        assertEquals(1, reports.size());
        String report = reports.get(0).get("message").getAsString();
        assertTrue(report, report.contains("dropped 2 "));
        assertTrue(report, report.contains("no endpoint yet: 2"));
    }

    @Test(timeout = 20000)
    public void aSecondRegistrationUnderTheSameNameStartsNoThreadAndForwardsUpdatesToTheRunningOne() throws Exception {
        int threadsBefore = awaitFlusherThreads(0);
        RamsoftAsyncHttpAppender attached = newAppender(url, NEVER, 500, 1_000_000, 10_000);
        assertEquals(threadsBefore + 1, awaitFlusherThreads(threadsBefore + 1));

        // What Global Scripts does after "Redeploy All" cleared the global map.
        RamsoftAsyncHttpAppender second = newAppender("", "", NEVER, 500, 1_000_000, 10_000, 16_000_000);
        assertTrue(second.isStarted());
        assertTrue(second.isStandIn());
        assertEquals("the second registration must not start its own flusher", threadsBefore + 1, awaitFlusherThreads(threadsBefore + 1));

        // What "Check Mirth Health" does: it holds the second instance (from the global map).
        second.updateEndpointAndPattern(url, "rotated-key", layout());
        attached.append(event("from the attached appender", "main"));
        attached.flush();

        assertEquals(1, bodies.size());
        assertEquals("subscription-key=rotated-key", queries.get(0));

        second.stop(5, TimeUnit.SECONDS);
        assertTrue("stopping the stand-in must not stop the running appender", attached.isStarted());
        attached.stop(5, TimeUnit.SECONDS);
        assertEquals(threadsBefore, awaitFlusherThreads(threadsBefore));

        // Once the running one has stopped, a new registration ships on its own again.
        RamsoftAsyncHttpAppender third = newAppender(url, NEVER, 500, 1_000_000, 10_000);
        assertFalse(third.isStandIn());
    }

    @Test(timeout = 20000)
    public void aStandInTakesOverWhenTheAppenderItStoodInForHasStopped() throws Exception {
        int threadsBefore = awaitFlusherThreads(0);
        RamsoftAsyncHttpAppender first = newAppender(url, NEVER, 500, 1_000_000, 10_000);
        RamsoftAsyncHttpAppender second = newAppender("", "", NEVER, 500, 1_000_000, 10_000, 16_000_000);
        assertTrue(second.isStandIn());

        first.stop(5, TimeUnit.SECONDS);
        second.updateEndpointAndPattern(url, "after-takeover", layout()); // must not go to the stopped one

        assertFalse("with nothing left to stand in for, it ships itself", second.isStandIn());
        assertEquals(threadsBefore + 1, awaitFlusherThreads(threadsBefore + 1));
        second.append(event("shipped by the former stand-in", "main"));
        second.flush();

        assertEquals(1, bodies.size());
        assertEquals("subscription-key=after-takeover", queries.get(0));
        assertEquals("shipped by the former stand-in", allEntries().get(0).get("message").getAsString());
    }

    @Test(timeout = 20000)
    public void stopFlushesWhatIsStillQueued() throws Exception {
        RamsoftAsyncHttpAppender appender = newAppender(url, NEVER, 500, 1_000_000, 10_000);
        for (int i = 0; i < 3; i++) {
            appender.append(event("pending " + i, "main"));
        }
        assertTrue(bodies.isEmpty());
        appender.stop(5, TimeUnit.SECONDS);
        assertEquals(3, allEntries().size());
        assertTrue(appender.isStopped());
        assertFalse(entriesFromLogger("transformer").isEmpty());
    }

    // --- Malformed layout output (an unescaped device name, a bare %t) must not poison a batch -------

    @Test(timeout = 20000)
    public void anEventTheLayoutRendersAsInvalidJsonIsRebuiltAndTheRestOfTheBatchIsDelivered() throws Exception {
        RamsoftAsyncHttpAppender appender = newAppender(url, NEVER, 500, 1_000_000, 10_000);
        appender.append(event("before", CHANNEL_THREAD));
        // What "Check Mirth Health" builds when the device identifier contains a quote.
        appender.updateEndpointAndPattern(url, "test-key", PatternLayout.newBuilder()
                .withPattern(PATTERN.replace("unit-test", "device\"7")).build());
        appender.append(eventWithError("broken by the layout", CHANNEL_THREAD, new IllegalStateException("boom")));
        appender.append(event("also broken", "main"));
        appender.flush();

        assertEquals(1, bodies.size());
        JsonArray batch = array(bodies.get(0)); // the receiver parses the whole body as one array
        assertEquals(3, batch.size());
        assertEquals("before", batch.get(0).getAsJsonObject().get("message").getAsString());
        assertFalse(batch.get(0).getAsJsonObject().has("Properties"));

        JsonObject rebuilt = batch.get(1).getAsJsonObject();
        assertEquals("broken by the layout", rebuilt.get("message").getAsString());
        assertEquals("ERROR", rebuilt.get("level").getAsString());
        assertEquals("transformer", rebuilt.get("logger").getAsString());
        assertEquals(CHANNEL_THREAD, rebuilt.get("thread").getAsString());
        assertTrue(rebuilt.get("exception").getAsString().contains("IllegalStateException: boom"));
        assertEquals("Outbound OAI Polling", rebuilt.get("channelName").getAsString());
        assertEquals("Subscription Handle", rebuilt.get("connectorName").getAsString());
        assertEquals("MirthConnectService", rebuilt.get("service").getAsString());
        assertEquals("key and service come from the last valid render", "unit-test", rebuilt.get("key").getAsString());
        assertTrue(rebuilt.getAsJsonObject("Properties").has(RamsoftAsyncHttpAppender.REBUILT_PROPERTY));
        assertEquals("also broken", batch.get(2).getAsJsonObject().get("message").getAsString());
        assertTrue(appender.getStats(), appender.getStats().contains("rebuilt=2"));
    }

    @Test(timeout = 20000)
    public void aBareThreadNameWithAQuoteIsRebuiltRatherThanSentAsInvalidJson() throws Exception {
        RamsoftAsyncHttpAppender appender = newAppender(url, NEVER, 500, 1_000_000, 10_000);
        // The Global Scripts registration layout writes %t without %enc.
        appender.updateEndpointAndPattern(url, "test-key", PatternLayout.newBuilder()
                .withPattern(PATTERN.replace("%enc{%t}{JSON}", "%t")).build());
        String thread = "JavaScript Writer on Send \"ADT\" (b06969aa-6233-4fde-a1ce-6238c9ae0c58), Dest (1) < pool-1-thread-9";
        appender.append(event("quoted thread", thread));
        appender.append(event("plain thread", "main"));
        appender.flush();

        JsonArray batch = array(bodies.get(0));
        assertEquals(2, batch.size());
        assertEquals(thread, batch.get(0).getAsJsonObject().get("thread").getAsString());
        assertTrue(batch.get(0).getAsJsonObject().has("Properties"));
        assertFalse("a valid render is sent as the layout made it", batch.get(1).getAsJsonObject().has("Properties"));
    }

    @Test(timeout = 20000)
    public void aLayoutThatNeverRendersValidJsonStillDeliversEventsAndDropReports() throws Exception {
        statusToReturn.set(503);
        RamsoftAsyncHttpAppender appender = new RamsoftAsyncHttpAppender("RamsoftHttp", null,
                PatternLayout.newBuilder().withPattern(PATTERN.replace("unit-test", "device\"7")).build(),
                false, url, "test-key", (int) NEVER, 500, 1_000_000, 10_000, 16_000_000);
        appenders.add(appender);
        appender.start();
        appender.append(event("lost", "main"));
        appender.flush();

        statusToReturn.set(200);
        appender.append(event("delivered", "main"));
        appender.flush();

        JsonArray second = array(bodies.get(1));
        assertEquals(2, second.size());
        assertEquals("delivered", second.get(0).getAsJsonObject().get("message").getAsString());
        JsonObject report = second.get(1).getAsJsonObject();
        assertEquals(RamsoftAsyncHttpAppender.DROP_REPORT_LOGGER, report.get("logger").getAsString());
        assertTrue(report.get("message").getAsString().contains("send failed: 1"));
        assertFalse("no valid render, so no key to borrow", report.has("key"));
        assertFalse("a drop report is built by the appender, not rebuilt from a broken render", report.has("Properties"));
    }

    @Test(timeout = 20000)
    public void anEmptyObjectFromTheLayoutStillGetsValidChannelFields() throws Exception {
        RamsoftAsyncHttpAppender appender = newAppender(url, NEVER, 500, 1_000_000, 10_000);
        appender.updateEndpointAndPattern(url, "test-key", PatternLayout.newBuilder().withPattern("{ }%n").build());
        appender.append(event("x", CHANNEL_THREAD));
        appender.flush();

        JsonObject entry = array(bodies.get(0)).get(0).getAsJsonObject();
        assertEquals("Outbound OAI Polling", entry.get("channelName").getAsString());
        assertFalse(entry.has("Properties"));
    }

    // --- Review round 5 -------------------------------------------------------------------------------

    @Test(timeout = 30000)
    public void anInstanceStartingWhileTheOldOneDrainsStandsInInsteadOfShippingAlongside() throws Exception {
        responseDelayMs = 250;
        RamsoftAsyncHttpAppender old = newAppender(url, NEVER, 10, 1_000_000, 10_000);
        old.finalFlushBudgetMs = 8_000;
        for (int i = 0; i < 60; i++) {
            old.append(event("old " + i, "main"));
        }
        Thread stopper = new Thread(() -> old.stop(10, TimeUnit.SECONDS));
        stopper.start();
        Thread.sleep(200); // the old instance is now draining its backlog

        RamsoftAsyncHttpAppender successor = newAppender(url, NEVER, 10, 1_000_000, 10_000);
        assertTrue("an instance starting during the drain must stand in for it", successor.isStandIn());
        for (int i = 0; i < 20; i++) {
            successor.append(event("during drain " + i, "main"));
        }
        stopper.join();
        successor.append(event("after drain", "main"));
        successor.flush();

        assertEquals("only one instance may send at a time", 1, maxInFlight.get());
        assertFalse("after the drain the stand-in ships itself", successor.isStandIn());
        assertEquals(81, entriesFromLogger("transformer").size());
    }

    @Test(timeout = 20000)
    public void anEarlyFlushThatNoLongerFindsAFullBatchLeavesThePartialOneForTheInterval() throws Exception {
        responseDelayMs = 400;
        arrivedLatch = new CountDownLatch(1);
        RamsoftAsyncHttpAppender appender = newAppender(url, NEVER, 10, 1_000_000, 10_000);
        for (int i = 0; i < 10; i++) {
            appender.append(event("first " + i, "main")); // early flush #1 starts; its request is slow
        }
        assertTrue(arrivedLatch.await(5, TimeUnit.SECONDS));
        for (int i = 0; i < 13; i++) {
            appender.append(event("next " + i, "main")); // queues early flush #2 while #1 is in flight
        }
        Thread.sleep(1_500);
        // #1 sends both full batches; #2 then finds only 3 events and leaves them for the interval.
        assertEquals("no request for a partial batch before the interval", 2, bodies.size());
    }

    @Test(timeout = 20000)
    public void aFullQueueDropsBeforeRenderingSoTheChannelThreadPaysNothing() throws Exception {
        server.stop(0);
        CountingLayout layout = new CountingLayout();
        RamsoftAsyncHttpAppender appender = new RamsoftAsyncHttpAppender("RamsoftHttp", null, layout, false,
                "http://127.0.0.1:1/LogReceiver", "k", (int) NEVER, 1_000_000, 1_000_000, 50, 16_000_000);
        appenders.add(appender);
        appender.start();
        for (int i = 0; i < 1_000; i++) {
            appender.append(event("line " + i, "main"));
        }
        assertEquals("only the events that fit are rendered and redacted", 50, layout.renders.get());
        assertTrue(appender.getStats(), appender.getStats().contains("queued=50"));
    }

    @Test(timeout = 20000)
    public void anEventThatFailsToRenderIsCountedInTheDropReport() throws Exception {
        RamsoftAsyncHttpAppender appender = newAppender(url, NEVER, 500, 1_000_000, 10_000);
        appender.updateEndpointAndPattern(url, "test-key", new AbstractStringLayout(StandardCharsets.UTF_8) {
            @Override
            public String toSerializable(LogEvent event) {
                throw new IllegalStateException("layout bug");
            }
        });
        appender.append(event("cannot be rendered", "main"));
        appender.updateEndpointAndPattern(url, "test-key", layout());
        appender.append(event("fine", "main"));
        appender.flush();

        List<JsonObject> reports = entriesFromLogger(RamsoftAsyncHttpAppender.DROP_REPORT_LOGGER);
        assertEquals(1, reports.size());
        String report = reports.get(0).get("message").getAsString();
        assertTrue(report, report.contains("dropped 1 "));
        assertTrue(report, report.contains("render failed: 1"));
    }

    @Test(timeout = 20000)
    public void aPatternThatStaysBrokenIsReportedOnceEvenWhenItsLayoutIsRebuilt() throws Exception {
        StatusCapture capture = new StatusCapture();
        StatusLogger.getLogger().registerListener(capture);
        try {
            RamsoftAsyncHttpAppender appender = newAppender(url, NEVER, 500, 1_000_000, 10_000);
            String broken = PATTERN.replace("unit-test", "device\"7");
            for (int run = 0; run < 3; run++) {
                // What "Check Mirth Health" does on every run: a new PatternLayout with the same pattern.
                appender.updateEndpointAndPattern(url, "test-key", PatternLayout.newBuilder().withPattern(broken).build());
                appender.append(event("run " + run, "main"));
            }
            int malformed = 0;
            for (String m : capture.messages(Level.ERROR)) {
                if (m.contains("not a valid JSON")) {
                    malformed++;
                }
            }
            assertEquals(1, malformed);
        } finally {
            StatusLogger.getLogger().removeListener(capture);
        }
    }

    @Test(timeout = 20000)
    public void statsComeFromTheLiveInstanceNotFromOneThatHasStopped() throws Exception {
        RamsoftAsyncHttpAppender first = newAppender(url, NEVER, 500, 1_000_000, 10_000);
        first.append(event("shipped by the first", "main"));
        first.flush();
        RamsoftAsyncHttpAppender second = newAppender("", "", NEVER, 500, 1_000_000, 10_000, 16_000_000);
        assertTrue(second.getStats(), second.getStats().startsWith("batches=1,"));

        first.stop(5, TimeUnit.SECONDS);
        assertTrue("the stopped instance's numbers must not be reported: " + second.getStats(),
                second.getStats().startsWith("batches=0,"));
        assertFalse(second.isStandIn());
    }

    // --- Review round 6: the hand-over at stop() -----------------------------------------------------

    @Test(timeout = 30000)
    public void whatTheStoppingInstanceCouldNotSendInTimeIsShippedByTheStandInThatTakesOver() throws Exception {
        responseDelayMs = 300;
        RamsoftAsyncHttpAppender old = newAppender(url, NEVER, 10, 1_000_000, 10_000);
        old.finalFlushBudgetMs = 500;
        RamsoftAsyncHttpAppender standIn = newAppender("", "", NEVER, 10, 1_000_000, 10_000, 16_000_000);
        assertTrue(standIn.isStandIn());
        for (int i = 0; i < 60; i++) {
            old.append(event("old " + i, "main")); // 6 batches at 300 ms each: more than the budget allows
        }
        old.stop(10, TimeUnit.SECONDS);
        int sentByOld = entriesFromLogger("transformer").size();
        assertTrue("the budget must leave a backlog for this test: " + sentByOld, sentByOld < 60);

        assertFalse("the stand-in takes over at once, not on its next event", standIn.isStandIn());
        responseDelayMs = 0;
        standIn.stop(10, TimeUnit.SECONDS);
        assertEquals("nothing is stranded in the stopped instance", 60, entriesFromLogger("transformer").size());
        assertEquals("only one instance may send at a time", 1, maxInFlight.get());
    }

    @Test(timeout = 20000)
    public void dropCountsTheStoppingInstanceHadNotReportedAreReportedByTheStandIn() throws Exception {
        RamsoftAsyncHttpAppender old = newAppender("", "", NEVER, 500, 1_000_000, 10_000, 16_000_000);
        RamsoftAsyncHttpAppender standIn = newAppender("", "", NEVER, 500, 1_000_000, 10_000, 16_000_000);
        for (int i = 0; i < 3; i++) {
            old.append(event("before any endpoint " + i, "main"));
        }
        old.stop(5, TimeUnit.SECONDS);
        standIn.updateEndpointAndPattern(url, "test-key", layout());
        standIn.append(event("first delivered", "main"));
        standIn.flush();

        List<JsonObject> reports = entriesFromLogger(RamsoftAsyncHttpAppender.DROP_REPORT_LOGGER);
        assertEquals(1, reports.size());
        String report = reports.get(0).get("message").getAsString();
        assertTrue(report, report.contains("no endpoint yet: 3"));
    }

    @Test(timeout = 30000)
    public void aRequestStillOpenWhenTheBudgetRunsOutIsCancelledBeforeTheStandInTakesOver() throws Exception {
        responseDelayMs = 5_000;
        arrivedLatch = new CountDownLatch(1);
        RamsoftAsyncHttpAppender old = newAppender(url, NEVER, 10, 1_000_000, 10_000);
        old.finalFlushBudgetMs = 300;
        RamsoftAsyncHttpAppender standIn = newAppender("", "", NEVER, 10, 1_000_000, 10_000, 16_000_000);
        for (int i = 0; i < 9; i++) {
            old.append(event("in flight " + i, "main")); // a full batch (one slot kept for a report): sent now
        }
        assertTrue(arrivedLatch.await(5, TimeUnit.SECONDS));

        long start = System.nanoTime();
        old.stop(0, TimeUnit.MILLISECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue("stop took " + elapsedMs + " ms", elapsedMs < 2_500);

        responseDelayMs = 0;
        standIn.flush(); // nothing of its own yet: the report goes out alone
        // The cancelled batch is counted by the old instance when its request ends; the stand-in
        // reporting it proves that request had ended before the stand-in started sending.
        List<JsonObject> reports = entriesFromLogger(RamsoftAsyncHttpAppender.DROP_REPORT_LOGGER);
        assertEquals(1, reports.size());
        String report = reports.get(0).get("message").getAsString();
        assertTrue(report, report.contains("send failed: 9"));
        // The stand-in has rendered nothing yet; its report carries the identity it inherited.
        assertEquals("unit-test", reports.get(0).get("key").getAsString());
        assertEquals("MirthConnectService", reports.get(0).get("service").getAsString());
    }

    @Test(timeout = 20000)
    public void aRenderFailureCountedAfterTheHandOverIsReportedByTheStandIn() throws Exception {
        final CountDownLatch rendering = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        Layout<? extends Serializable> failing = new AbstractStringLayout(StandardCharsets.UTF_8) {
            @Override
            public String toSerializable(LogEvent event) {
                rendering.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("layout bug");
            }
        };
        RamsoftAsyncHttpAppender old = new RamsoftAsyncHttpAppender("RamsoftHttp", null, failing, true, url, "k",
                (int) NEVER, 500, 1_000_000, 10_000, 16_000_000);
        appenders.add(old);
        old.start();
        final RamsoftAsyncHttpAppender standIn = newAppender("", "", NEVER, 500, 1_000_000, 10_000, 16_000_000);
        Thread channel = new Thread(() -> standIn.append(event("cannot be rendered", "main"))); // forwarded to old
        channel.start();
        assertTrue(rendering.await(5, TimeUnit.SECONDS));

        old.stop(5, TimeUnit.SECONDS); // moves old's counts (none yet) to the stand-in
        release.countDown(); // now the render fails and old counts it
        channel.join(5_000);
        standIn.flush();

        List<JsonObject> reports = entriesFromLogger(RamsoftAsyncHttpAppender.DROP_REPORT_LOGGER);
        assertEquals(1, reports.size());
        String report = reports.get(0).get("message").getAsString();
        assertTrue(report, report.contains("render failed: 1"));
    }

    @Test(timeout = 20000)
    public void afterTwoStopsTheLastStandInShipsWithoutWaitingForAnEvent() throws Exception {
        RamsoftAsyncHttpAppender first = newAppender(url, NEVER, 500, 1_000_000, 10_000);
        RamsoftAsyncHttpAppender older = newAppender("", "", NEVER, 500, 1_000_000, 10_000, 16_000_000);
        RamsoftAsyncHttpAppender newest = newAppender("", "", NEVER, 500, 1_000_000, 10_000, 16_000_000);
        first.stop(5, TimeUnit.SECONDS);
        assertTrue("re-registered with the instance that took over", older.isStandIn());

        newest.append(event("queued by the second shipper", "main"));
        newest.stop(5, TimeUnit.SECONDS);
        // The older stand-in has neither appended nor been updated since: it was registered for the hand-over.
        assertFalse(older.isStandIn());
        older.flush();
        assertEquals(1, entriesFromLogger("transformer").size());
    }

    @Test(timeout = 20000)
    public void aFullBatchHandedOverIsSentWithoutWaitingForTheInterval() throws Exception {
        arrivedLatch = new CountDownLatch(1);
        RamsoftAsyncHttpAppender old = newAppender("http://127.0.0.1:1/LogReceiver", NEVER, 10, 1_000_000, 10_000);
        RamsoftAsyncHttpAppender standIn = newAppender(url, NEVER, 10, 1_000_000, 10_000);
        assertTrue(standIn.isStandIn());
        old.append(event("first", "main"));
        old.flush(); // refused: old backs off, so nothing it queues now is sent early
        for (int i = 0; i < 20; i++) {
            old.append(event("pending " + i, "main"));
        }
        old.stop(5, TimeUnit.SECONDS); // one more refused batch, then more than a full batch is handed over

        assertTrue("the successor must send a full inherited batch at once, not at its next interval",
                arrivedLatch.await(5, TimeUnit.SECONDS));
    }

    @Test(timeout = 20000)
    public void anUpdateNeverWaitsOnAnotherInstancesMonitor() throws Exception {
        final RamsoftAsyncHttpAppender old = newAppender(url, NEVER, 500, 1_000_000, 10_000);
        final RamsoftAsyncHttpAppender standIn = newAppender("", "", NEVER, 500, 1_000_000, 10_000, 16_000_000);
        old.stop(5, TimeUnit.SECONDS); // old now forwards updates to standIn, the reverse of before
        Thread update = new Thread(() -> old.updateEndpointAndPattern(url, "via-old", layout()));
        // Held monitors were the deadlock: standIn -> old before the hand-over, old -> standIn after it.
        synchronized (standIn) {
            update.start();
            update.join(3_000);
            assertFalse("the update waited on another instance's monitor", update.isAlive());
        }
        standIn.append(event("x", "main"));
        standIn.flush();
        assertEquals("subscription-key=via-old", queries.get(0));
    }

    @Test(timeout = 20000)
    public void anEventForwardedWhileTheOwnerHandsOverIsShippedByTheStandIn() throws Exception {
        final CountDownLatch rendering = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        Layout<? extends Serializable> blocking = new AbstractStringLayout(StandardCharsets.UTF_8) {
            @Override
            public String toSerializable(LogEvent event) {
                String message = event.getMessage().getFormattedMessage();
                if ("late".equals(message)) {
                    rendering.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return "{\"message\":\"" + RamsoftAsyncHttpAppender.jsonEscape(message) + "\"}";
            }
        };
        RamsoftAsyncHttpAppender old = new RamsoftAsyncHttpAppender("RamsoftHttp", null, blocking, false, url, "k",
                (int) NEVER, 500, 1_000_000, 10_000, 16_000_000);
        appenders.add(old);
        old.start();
        final RamsoftAsyncHttpAppender standIn = newAppender("", "", NEVER, 500, 1_000_000, 10_000, 16_000_000);
        Thread channel = new Thread(() -> standIn.append(event("late", "main"))); // forwarded to old
        channel.start();
        assertTrue(rendering.await(5, TimeUnit.SECONDS));

        old.stop(5, TimeUnit.SECONDS); // its queue is empty, so the drain and the hand-over finish now
        assertFalse(standIn.isStandIn());
        release.countDown();
        channel.join(5_000);
        standIn.flush();

        List<String> messages = new ArrayList<>();
        for (JsonObject o : allEntries()) {
            messages.add(o.get("message").getAsString());
        }
        assertEquals(Collections.singletonList("late"), messages);
    }

    @Test(timeout = 20000)
    public void theNewestStandInTakesOverAndAnOlderOneStandsInForIt() throws Exception {
        RamsoftAsyncHttpAppender old = newAppender(url, NEVER, 500, 1_000_000, 10_000);
        // Two "Redeploy All"s: Global Scripts holds only the newest, which "Check Mirth Health" updates.
        RamsoftAsyncHttpAppender older = newAppender("", "", NEVER, 500, 1_000_000, 10_000, 16_000_000);
        RamsoftAsyncHttpAppender newest = newAppender("", "", NEVER, 500, 1_000_000, 10_000, 16_000_000);
        old.stop(5, TimeUnit.SECONDS);

        assertFalse("the newest stand-in ships", newest.isStandIn());
        older.append(event("via the older one", "main"));
        assertTrue("the older one stands in for the newest", older.isStandIn());
        newest.flush();
        assertEquals("via the older one", allEntries().get(0).get("message").getAsString());
    }

    @Test(timeout = 20000)
    public void withNoStandInWhatIsLeftAtStopIsWrittenToTheStatusLogger() throws Exception {
        StatusCapture capture = new StatusCapture();
        StatusLogger.getLogger().registerListener(capture);
        try {
            statusToReturn.set(503);
            RamsoftAsyncHttpAppender appender = newAppender(url, NEVER, 10, 1_000_000, 10_000);
            appender.append(event("first", "main"));
            appender.flush(); // fails: 1 drop not reported yet
            for (int i = 0; i < 30; i++) {
                appender.append(event("pending " + i, "main"));
            }
            appender.stop(5, TimeUnit.SECONDS); // one more failed batch (9 events + the report), then it gives up

            List<String> warnings = new ArrayList<>();
            for (String m : capture.messages(Level.WARN)) {
                if (m.contains("unsent")) {
                    warnings.add(m);
                }
            }
            assertEquals(1, warnings.size());
            assertTrue(warnings.get(0), warnings.get(0).contains("with 21 log events unsent and 10 drops unreported"));
        } finally {
            StatusLogger.getLogger().removeListener(capture);
        }
    }

    /** Counts renders; a minimal valid JSON layout. */
    private static final class CountingLayout extends AbstractStringLayout {
        final AtomicInteger renders = new AtomicInteger();

        CountingLayout() {
            super(StandardCharsets.UTF_8);
        }

        @Override
        public String toSerializable(LogEvent event) {
            renders.incrementAndGet();
            return "{\"message\":\"" + RamsoftAsyncHttpAppender.jsonEscape(event.getMessage().getFormattedMessage()) + "\"}";
        }
    }

    // --- Credentials never leave the box ------------------------------------------------------------

    @Test(timeout = 20000)
    public void credentialsAreMaskedInTheShippedCopyOfEveryField() throws Exception {
        String jwt = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9" + "." + "eyJzdWIiOiIxMjM0NSJ9" + "." + "c2lnbmF0dXJlLXZhbHVl";
        String key = "306e0123456789abcdef0123456789ab";
        RamsoftAsyncHttpAppender appender = newAppender(url, NEVER, 500, 1_000_000, 10_000);
        appender.append(event("Bearer " + jwt, CHANNEL_THREAD));
        appender.append(event("Response = {\"logEndpoint\":\"https://h/LogReceiver?subscription-key=" + key + "\"}", "main"));
        appender.append(eventWithError("call failed", "main", new IllegalStateException("401 for token " + jwt)));
        appender.append(event("nothing secret here", "main"));
        appender.flush();

        String body = bodies.get(0);
        assertFalse("no token may reach the receiver", body.contains("eyJ"));
        assertFalse("no key may reach the receiver", body.contains(key));
        JsonArray batch = array(body);
        assertEquals(4, batch.size());
        assertEquals("Bearer " + LogRedactor.JWT_MASK, batch.get(0).getAsJsonObject().get("message").getAsString());
        assertEquals("Outbound OAI Polling", batch.get(0).getAsJsonObject().get("channelName").getAsString());
        assertEquals("Response = {\"logEndpoint\":\"https://h/LogReceiver?subscription-key=<redacted>\"}",
                batch.get(1).getAsJsonObject().get("message").getAsString());
        assertTrue(batch.get(2).getAsJsonObject().get("exception").getAsString().contains("401 for token " + LogRedactor.JWT_MASK));
        assertEquals("nothing secret here", batch.get(3).getAsJsonObject().get("message").getAsString());
        assertTrue(appender.getStats(), appender.getStats().contains("redacted=3"));
    }

    @Test(timeout = 20000)
    public void credentialsAreMaskedInARebuiltEventToo() throws Exception {
        String jwt = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9" + "." + "eyJzdWIiOiIxMjM0NSJ9" + "." + "c2lnbmF0dXJlLXZhbHVl";
        RamsoftAsyncHttpAppender appender = newAppender(url, NEVER, 500, 1_000_000, 10_000);
        appender.updateEndpointAndPattern(url, "test-key", PatternLayout.newBuilder()
                .withPattern(PATTERN.replace("unit-test", "device\"7")).build());
        appender.append(event("Bearer " + jwt, "main"));
        appender.flush();

        JsonObject entry = array(bodies.get(0)).get(0).getAsJsonObject();
        assertTrue(entry.has("Properties"));
        assertEquals("Bearer " + LogRedactor.JWT_MASK, entry.get("message").getAsString());
    }

    // --- Size is measured as sent: UTF-8 bytes -------------------------------------------------------

    @Test(timeout = 20000)
    public void batchesAreCappedByUtf8BytesNotCharacters() throws Exception {
        RamsoftAsyncHttpAppender appender = newAppender(url, NEVER, 500, 3_000, 10_000);
        String cjk = repeat('中', 300); // 300 chars, 900 bytes
        for (int i = 0; i < 10; i++) {
            appender.append(event(i + cjk, "main"));
        }
        appender.stop(5, TimeUnit.SECONDS);

        assertEquals(10, entriesFromLogger("transformer").size());
        assertEquals("the multi-byte text must really be on the wire", "0" + cjk,
                entriesFromLogger("transformer").get(0).get("message").getAsString());
        for (String body : bodies) {
            int bytes = body.getBytes(StandardCharsets.UTF_8).length;
            assertTrue("a batch over the cap may only hold a single event: " + bytes + " bytes",
                    bytes <= 3_000 || array(body).size() == 1);
        }
    }

    @Test(timeout = 20000)
    public void nonAsciiTextArrivesIntactWhateverCharsetTheLayoutWasBuiltWith() throws Exception {
        // Global Scripts and "Check Mirth Health" build the layout without a charset; on Windows that is
        // windows-1252, which cannot even represent most of this.
        String text = "Françoise Müller, 中文, Здраво, 😀";
        RamsoftAsyncHttpAppender appender = newAppender(url, NEVER, 500, 1_000_000, 10_000);
        appender.updateEndpointAndPattern(url, "test-key", PatternLayout.newBuilder()
                .withPattern(PATTERN).withCharset(Charset.forName("windows-1252")).build());
        appender.append(event(text, "main"));
        appender.flush();

        JsonObject entry = allEntries().get(0);
        assertEquals(text, entry.get("message").getAsString());
        assertFalse(entry.has("Properties"));
    }

    @Test
    public void utf8LengthMatchesTheEncoder() {
        String[] samples = {"", "ascii", "café", "中文", "emoji 😀", "lone \ud83d high", "\u0000\u007f\u0080߿ࠀ￿"};
        for (String s : samples) {
            int encoded = s.getBytes(StandardCharsets.UTF_8).length;
            int counted = RamsoftAsyncHttpAppender.utf8Length(s);
            assertTrue(s + ": counted " + counted + ", encoded " + encoded, counted >= encoded);
            if (!s.contains("lone")) {
                assertEquals(s, encoded, counted);
            }
        }
    }

    // --- Lifecycle -----------------------------------------------------------------------------------

    @Test(timeout = 20000)
    public void aStartAfterAStopShipsAgain() throws Exception {
        int threadsBefore = awaitFlusherThreads(0);
        RamsoftAsyncHttpAppender appender = newAppender(url, NEVER, 500, 1_000_000, 10_000);
        appender.stop(5, TimeUnit.SECONDS);
        assertEquals(threadsBefore, awaitFlusherThreads(threadsBefore));

        appender.start();
        assertEquals(threadsBefore + 1, awaitFlusherThreads(threadsBefore + 1));
        appender.append(event("after restart", "main"));
        appender.flush();
        assertEquals("after restart", allEntries().get(0).get("message").getAsString());
    }

    @Test(timeout = 20000)
    public void startingTwiceStartsOneFlusher() throws Exception {
        int threadsBefore = awaitFlusherThreads(0);
        RamsoftAsyncHttpAppender appender = newAppender(url, NEVER, 500, 1_000_000, 10_000);
        appender.start();
        assertFalse(appender.isStandIn());
        assertEquals(threadsBefore + 1, awaitFlusherThreads(threadsBefore + 1));
        appender.stop(5, TimeUnit.SECONDS);
        assertEquals("no orphaned flusher left running", threadsBefore, awaitFlusherThreads(threadsBefore));
    }

    @Test(timeout = 60000)
    public void registrationsStartingAtTheSameTimeLeaveExactlyOneShipper() throws Exception {
        int threadsBefore = awaitFlusherThreads(0);
        for (int round = 0; round < 40; round++) {
            final int n = 6;
            final String name = "RamsoftHttp-race-" + round;
            final CyclicBarrier barrier = new CyclicBarrier(n);
            final List<RamsoftAsyncHttpAppender> started = Collections.synchronizedList(new ArrayList<RamsoftAsyncHttpAppender>());
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Thread t = new Thread(() -> {
                    RamsoftAsyncHttpAppender a = new RamsoftAsyncHttpAppender(name, null, layout(), false, url, "k",
                            (int) NEVER, 500, 1_000_000, 10_000, 16_000_000);
                    started.add(a);
                    try {
                        barrier.await();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    a.start();
                });
                threads.add(t);
                t.start();
            }
            for (Thread t : threads) {
                t.join();
            }
            appenders.addAll(started);
            int shippers = 0;
            for (RamsoftAsyncHttpAppender a : started) {
                if (!a.isStandIn()) {
                    shippers++;
                }
            }
            assertEquals("round " + round + ": shipping instances", 1, shippers);
            assertEquals("round " + round + ": flusher threads", threadsBefore + 1, awaitFlusherThreads(threadsBefore + 1));
            for (RamsoftAsyncHttpAppender a : started) {
                a.stop(5, TimeUnit.SECONDS);
            }
            assertEquals(threadsBefore, awaitFlusherThreads(threadsBefore));
        }
    }

    @Test(timeout = 30000)
    public void stopKeepsToACallerTimeoutShorterThanTheBudget() throws Exception {
        responseDelayMs = 300;
        RamsoftAsyncHttpAppender appender = newAppender(url, NEVER, 10, 1_000_000, 10_000);
        appender.finalFlushBudgetMs = 10_000;
        for (int i = 0; i < 300; i++) {
            appender.append(event("pending " + i, "main"));
        }
        long start = System.nanoTime();
        appender.stop(1, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue("stop(1 s) took " + elapsedMs + " ms", elapsedMs < 2_500);
    }

    // --- A failing gateway leaves a local trace ------------------------------------------------------

    @Test(timeout = 20000)
    public void aRunOfFailedSendsIsWrittenToTheStatusLoggerOnceAndSoIsTheRecovery() throws Exception {
        StatusCapture capture = new StatusCapture();
        StatusLogger.getLogger().registerListener(capture);
        try {
            statusToReturn.set(401); // e.g. a rotated subscription key
            RamsoftAsyncHttpAppender appender = newAppender(url, NEVER, 500, 1_000_000, 10_000);
            for (int i = 0; i < 3; i++) {
                appender.append(event("lost " + i, "main"));
                appender.flush();
            }
            List<String> errors = capture.messages(Level.ERROR);
            assertEquals(errors.toString(), 1, errors.size());
            assertTrue(errors.get(0), errors.get(0).contains("HTTP 401"));
            assertFalse("the subscription key must not be logged", errors.get(0).contains("test-key"));

            statusToReturn.set(200);
            appender.append(event("delivered", "main"));
            appender.flush();
            appender.append(event("delivered again", "main"));
            appender.flush();
            assertEquals(1, capture.messages(Level.WARN).size());
            assertEquals(1, capture.messages(Level.ERROR).size());
        } finally {
            StatusLogger.getLogger().removeListener(capture);
        }
    }
}

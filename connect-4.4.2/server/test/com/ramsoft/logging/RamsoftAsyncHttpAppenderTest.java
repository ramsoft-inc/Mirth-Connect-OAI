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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.message.SimpleMessage;
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

    @Before
    public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/LogReceiver", exchange -> {
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
            requestLatch.countDown();
        });
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
    public void nothingIsSentOrQueuedWithoutAnEndpoint() throws Exception {
        RamsoftAsyncHttpAppender appender = newAppender("", 100, 500, 1_000_000, 10_000);
        for (int i = 0; i < 10; i++) {
            appender.append(event("line " + i, "main"));
        }
        Thread.sleep(400);
        assertTrue(bodies.isEmpty());
        assertTrue(appender.getStats(), appender.getStats().contains("queued=0"));
    }

    @Test(timeout = 20000)
    public void updateEndpointAndPatternRedirectsLaterBatches() throws Exception {
        RamsoftAsyncHttpAppender appender = newAppender("", NEVER, 500, 1_000_000, 10_000);
        appender.append(event("before endpoint", "main"));

        appender.updateEndpointAndPattern(url, "rotated-key", layout());
        appender.append(event("after endpoint", "main"));
        appender.flush();

        assertEquals(1, bodies.size());
        List<JsonObject> entries = allEntries();
        assertEquals(1, entries.size());
        assertEquals("after endpoint", entries.get(0).get("message").getAsString());
        assertEquals("subscription-key=rotated-key", queries.get(0));
    }

    @Test(timeout = 20000)
    public void aSecondRegistrationUnderTheSameNameStartsNoThreadAndForwardsUpdatesToTheRunningOne() throws Exception {
        int threadsBefore = flusherThreads();
        RamsoftAsyncHttpAppender attached = newAppender(url, NEVER, 500, 1_000_000, 10_000);
        assertEquals(threadsBefore + 1, flusherThreads());

        // What Global Scripts does after "Redeploy All" cleared the global map.
        RamsoftAsyncHttpAppender second = newAppender("", "", NEVER, 500, 1_000_000, 10_000, 16_000_000);
        assertTrue(second.isStarted());
        assertTrue(second.isStandIn());
        assertEquals("the second registration must not start its own flusher", threadsBefore + 1, flusherThreads());

        // What "Check Mirth Health" does: it holds the second instance (from the global map).
        second.updateEndpointAndPattern(url, "rotated-key", layout());
        attached.append(event("from the attached appender", "main"));
        attached.flush();

        assertEquals(1, bodies.size());
        assertEquals("subscription-key=rotated-key", queries.get(0));

        second.stop(5, TimeUnit.SECONDS);
        assertTrue("stopping the stand-in must not stop the running appender", attached.isStarted());
        attached.stop(5, TimeUnit.SECONDS);
        assertEquals(threadsBefore, flusherThreads());

        // Once the running one has stopped, a new registration ships on its own again.
        RamsoftAsyncHttpAppender third = newAppender(url, NEVER, 500, 1_000_000, 10_000);
        assertFalse(third.isStandIn());
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
}

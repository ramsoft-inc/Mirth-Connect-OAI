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

    // Same shape as the production pattern set by Global Scripts / "Check Mirth Health".
    private static final String PATTERN = "{\"timestamp\":\"%d{yyyy-MM-dd'T'HH:mm:ss.SSS,local}\",\"level\":\"%p\","
            + "\"logger\":\"%logger{1}\",\"message\":\"%enc{%m}{JSON}\",\"exception\":\"%enc{%ex{full}}{JSON}\","
            + "\"thread\":\"%enc{%t}{JSON}\",\"service\":\"MirthConnectService\",\"key\":\"unit-test\"}%n";

    private static final String CHANNEL_THREAD =
            "JavaScript Writer JavaScript Task on Outbound OAI Polling (b06969aa-6233-4fde-a1ce-6238c9ae0c58), "
            + "Subscription Handle (1) < pool-1-thread-374";

    private HttpServer server;
    private final List<String> bodies = Collections.synchronizedList(new ArrayList<String>());
    private final List<String> queries = Collections.synchronizedList(new ArrayList<String>());
    private final AtomicInteger statusToReturn = new AtomicInteger(200);
    private volatile CountDownLatch requestLatch = new CountDownLatch(0);
    private String url;
    private RamsoftAsyncHttpAppender appender;

    @Before
    public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/LogReceiver", exchange -> {
            bodies.add(readAll(exchange.getRequestBody()));
            queries.add(exchange.getRequestURI().getRawQuery());
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
        if (appender != null && !appender.isStopped()) {
            appender.stop(5, TimeUnit.SECONDS);
        }
        server.stop(0);
    }

    private RamsoftAsyncHttpAppender newAppender(String target, int flushMs, int maxEvents, int maxChars, int maxQueue) {
        RamsoftAsyncHttpAppender a = new RamsoftAsyncHttpAppender("RamsoftHttp", null, layout(), false, target, "test-key",
                flushMs, maxEvents, maxChars, maxQueue);
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

    private List<JsonObject> allEntries() {
        List<JsonObject> entries = new ArrayList<>();
        synchronized (bodies) {
            for (String body : bodies) {
                JsonElement parsed = new JsonParser().parse(body);
                assertTrue("every request body must be a JSON array: " + body, parsed.isJsonArray());
                for (JsonElement e : parsed.getAsJsonArray()) {
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

    @Test(timeout = 20000)
    public void manyEventsWithinOneIntervalGoOutAsOneArrayRequest() throws Exception {
        requestLatch = new CountDownLatch(1);
        appender = newAppender(url, 300, 500, 1_000_000, 10_000);

        for (int i = 0; i < 100; i++) {
            appender.append(event("line " + i, "main"));
        }
        assertTrue(requestLatch.await(10, TimeUnit.SECONDS));
        Thread.sleep(500);

        assertEquals("100 events in one interval must be one request, not 100", 1, bodies.size());
        List<JsonObject> entries = allEntries();
        assertEquals(100, entries.size());
        for (int i = 0; i < 100; i++) {
            assertEquals("order must be preserved", "line " + i, entries.get(i).get("message").getAsString());
        }
        assertEquals("subscription-key=test-key", queries.get(0));
    }

    @Test(timeout = 20000)
    public void channelAndConnectorAreParsedFromTheThreadName() throws Exception {
        requestLatch = new CountDownLatch(1);
        appender = newAppender(url, 200, 500, 1_000_000, 10_000);

        appender.append(event("from a channel", CHANNEL_THREAD));
        appender.append(event("from elsewhere", "main"));
        assertTrue(requestLatch.await(10, TimeUnit.SECONDS));

        List<JsonObject> entries = allEntries();
        assertEquals("Outbound OAI Polling", entries.get(0).get("channelName").getAsString());
        assertEquals("Subscription Handle", entries.get(0).get("connectorName").getAsString());
        assertEquals("Unknown", entries.get(1).get("channelName").getAsString());
        assertEquals("Unknown", entries.get(1).get("connectorName").getAsString());
    }

    @Test(timeout = 20000)
    public void aFullBatchIsSentWithoutWaitingForTheInterval() throws Exception {
        requestLatch = new CountDownLatch(2);
        // Interval far longer than the test timeout: only the size trigger can send these.
        appender = newAppender(url, 600_000, 10, 1_000_000, 10_000);

        for (int i = 0; i < 25; i++) {
            appender.append(event("line " + i, "main"));
        }
        assertTrue("two full batches of 10 must be sent before the interval", requestLatch.await(10, TimeUnit.SECONDS));

        appender.stop(5, TimeUnit.SECONDS);
        assertEquals("stop() must flush the remaining 5", 25, allEntries().size());
        for (String body : bodies) {
            assertTrue(new JsonParser().parse(body).getAsJsonArray().size() <= 10);
        }
    }

    @Test(timeout = 20000)
    public void batchesRespectTheCharacterCap() throws Exception {
        appender = newAppender(url, 600_000, 500, 2_000, 10_000);
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
            int count = new JsonParser().parse(body).getAsJsonArray().size();
            assertTrue("a batch over the cap may only hold a single event", body.length() <= 2_000 || count == 1);
        }
    }

    @Test(timeout = 20000)
    public void aFailedBatchIsDroppedNotRetriedAndReportedInTheNextBatch() throws Exception {
        statusToReturn.set(503);
        appender = newAppender(url, 600_000, 500, 1_000_000, 10_000);
        for (int i = 0; i < 7; i++) {
            appender.append(event("lost " + i, "main"));
        }
        appender.flush();
        assertEquals("exactly one attempt, no retry", 1, bodies.size());

        statusToReturn.set(200);
        appender.append(event("after recovery", "main"));
        appender.flush();

        assertEquals(2, bodies.size());
        JsonArray second = new JsonParser().parse(bodies.get(1)).getAsJsonArray();
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
        appender = newAppender(url, 600_000, 500, 1_000_000, 10_000);
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
        JsonArray third = new JsonParser().parse(bodies.get(2)).getAsJsonArray();
        assertEquals(2, third.size());
        String report = third.get(1).getAsJsonObject().get("message").getAsString();
        assertTrue(report, report.contains("dropped 8 "));
        assertTrue(report, report.contains("send failed: 8"));
        assertTrue(appender.getStats(), appender.getStats().contains("droppedPending=0"));
    }

    @Test(timeout = 20000)
    public void afterAFailedSendTheGatewayGetsOneRequestPerFlushNotOnePerQueuedBatch() throws Exception {
        statusToReturn.set(503);
        requestLatch = new CountDownLatch(1);
        appender = newAppender(url, 600_000, 10, 1_000_000, 10_000);

        for (int i = 0; i < 10; i++) {
            appender.append(event("line " + i, "main")); // the 10th triggers an early flush, which fails
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
        appender = newAppender(url, 600_000, 10, 1_000_000, 10_000);
        appender.append(event("first", "main"));
        appender.flush(); // now failing: size-triggered flushes are off
        for (int i = 0; i < 50; i++) {
            appender.append(event("pending " + i, "main"));
        }

        appender.stop(5, TimeUnit.SECONDS);
        assertTrue(appender.isStopped());
        assertEquals("one attempt at the final flush, not one per queued batch", 2, bodies.size());
    }

    @Test(timeout = 20000)
    public void aFullQueueDropsInsteadOfBlockingTheCaller() throws Exception {
        // Unreachable endpoint (closed port) and no early flush can drain it: the queue must fill.
        server.stop(0);
        appender = newAppender("http://127.0.0.1:1/LogReceiver", 600_000, 1_000_000, 1_000_000, 50);

        long start = System.nanoTime();
        for (int i = 0; i < 5_000; i++) {
            appender.append(event("line " + i, "main"));
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue("append() must not block when the queue is full (took " + elapsedMs + " ms)", elapsedMs < 5_000);
        assertTrue(appender.getStats(), appender.getStats().contains("queued=50"));
    }

    @Test(timeout = 20000)
    public void nothingIsSentOrQueuedWithoutAnEndpoint() throws Exception {
        appender = newAppender("", 100, 500, 1_000_000, 10_000);
        for (int i = 0; i < 10; i++) {
            appender.append(event("line " + i, "main"));
        }
        Thread.sleep(400);
        assertTrue(bodies.isEmpty());
        assertTrue(appender.getStats(), appender.getStats().contains("queued=0"));
    }

    @Test(timeout = 20000)
    public void updateEndpointAndPatternRedirectsLaterBatches() throws Exception {
        appender = newAppender("", 600_000, 500, 1_000_000, 10_000);
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
    public void stopFlushesWhatIsStillQueued() throws Exception {
        appender = newAppender(url, 600_000, 500, 1_000_000, 10_000);
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

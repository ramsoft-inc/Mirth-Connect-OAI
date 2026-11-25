package com.ramsoft.logging;

import org.apache.logging.log4j.core.*;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.status.StatusLogger;

import okhttp3.*;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RamsoftAsyncHttpAppender extends AbstractAppender {

    private final OkHttpClient httpClient;
    private volatile String url;
    private volatile String subscriptionKey;

    // Separate layout field for dynamic update
    private volatile Layout<? extends Serializable> dynamicLayout;

    private static final Pattern THREAD_PATTERN = Pattern.compile(".* on ([^()]+) \\([0-9a-f\\-]{36}\\), ([^()]+) \\(");

    public RamsoftAsyncHttpAppender(String name, Filter filter, Layout<? extends Serializable> layout,
                                     boolean ignoreExceptions, String url, String subscriptionKey) {
        super(name, filter, layout, ignoreExceptions, Property.EMPTY_ARRAY);
        this.dynamicLayout = layout;
        this.url = url;
        this.subscriptionKey = subscriptionKey;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .writeTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build();
    }

    public RamsoftAsyncHttpAppender(String name, Layout<? extends Serializable> layout,
                                     boolean ignoreExceptions, String url, String subscriptionKey) {
        this(name, null, layout, ignoreExceptions, url, subscriptionKey);
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

            String threadName = event.getThreadName() != null ? event.getThreadName() : "";
            String channelName = "Unknown";
            String connectorName = "Unknown";

            Matcher matcher = THREAD_PATTERN.matcher(threadName);
            if (matcher.find()) {
                channelName = matcher.group(1);
                connectorName = matcher.group(2);               
            }
            
            Layout<? extends Serializable> layout = getLayout();
            if (layout == null) {
                return;
            }
             // Get the base JSON from the layout
            byte[] logBytes = layout.toByteArray(event);
            String logMsg = new String(logBytes, StandardCharsets.UTF_8).trim();

            // Inject channelName and connectorName into JSON
            // Assume logMsg ends with } and no trailing newline
            if (logMsg.endsWith("}")) {
                logMsg = logMsg.substring(0, logMsg.length() - 1) +
                    ",\"channelName\":\"" + jsonEscape(channelName) + "\"" +
                    ",\"connectorName\":\"" + jsonEscape(connectorName) + "\"}";
            }

            RequestBody body = RequestBody.create(MediaType.parse("application/json"), logMsg);

            HttpUrl.Builder httpUrlBuilder = HttpUrl.parse(url).newBuilder();
            if (subscriptionKey != null && !subscriptionKey.isEmpty()) {
                httpUrlBuilder.addQueryParameter("subscription-key", subscriptionKey);
            }

            Request request = new Request.Builder()
                .url(httpUrlBuilder.build())
                .post(body)
                .build();

            httpClient.newCall(request).enqueue(new Callback() {
                public void onFailure(Call call, java.io.IOException e) {
                    // Non-blocking: ignore or log to fallback
                	//System.err.println("[RamsoftAppender] HTTP send failed: " + e.getMessage());
                }

                public void onResponse(Call call, Response response) {
                    response.close();
                }
            });

        } catch (Exception e) {
            if (!ignoreExceptions()) {
                StatusLogger.getLogger().error("RamsoftAppender exception", e);
            }
        }
    }
}

package com.mirth.connect.server.userutil;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Logger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.signalr.HubConnection;
import com.microsoft.signalr.HubConnectionBuilder;
import com.microsoft.signalr.HubConnectionState;
import ca.uhn.hl7v2.util.StringUtil;
import io.reactivex.Single;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class SubscribeSignalR {
    private String _signalREvent;
    private String _hubName;
    private String _userId;
    private String _hubEndpoint; // This will be updated by AcquireSignalRInfo
    private String _signalRAccessToken;
    private String _fhirAccessToken; // Access token for FHIR API to get SignalR connection info
    private HubConnection _hubConnection;
    private String _channelName;
    private String _clientID;
    private String _clientSecret;
    private String _authority;
    private String _scope;
    private String _authEndpoint; // Endpoint to get SignalR connection info
    private final String AUTHORITY_DOMAIN = "https://login.microsoftonline.com/"; // Made constant
    private VMRouter _vmRouter;
    private Logger logger = Logger.getLogger(getClass());

    // Reconnection parameters
    private static final int INITIAL_RETRY_DELAY_MS = 2000; // Initial delay for reconnection attempts
    private static final int MAX_RETRY_DELAY_MS = 30000; // Maximum delay for reconnection attempts
    private static final int TOKEN_REFRESH_BUFFER_MINUTES = 5; // Refresh token if it expires within this buffer
    private Disposable reconnectionDisposable; // To manage the reconnection observable

    // SignalR connection properties
    private long _keepAliveIntervalInMilliseconds;
    private long _serverTimeoutInMilliseconds;
    private OffsetDateTime _tokenExpiredTime; // Using java.time.OffsetDateTime

    private boolean isDisposed = false; // Flag to indicate if the connection should be stopped permanently

    private RSUtil _rsUtil; // Assuming RSUtil is a utility class for token acquisition

    public SubscribeSignalR(String signalREvent, String hubName, String userId, String hubEndpoint, String channelName,
                             String clientID, String clientSecret, String authority, String scope, String authEndpoint) throws Exception {
        this._signalREvent = signalREvent;
        this._hubName = hubName;
        this._userId = userId;
        this._hubEndpoint = hubEndpoint; // Initial value, will be updated by AcquireSignalRInfo
        this._channelName = channelName;
        this._clientID = clientID;
        this._clientSecret = clientSecret;
        this._authority = authority;
        this._scope = scope;
        this._vmRouter = new VMRouter();
        this._rsUtil = new RSUtil();

        // Ensure authEndpoint ends with a slash
        this._authEndpoint = authEndpoint.endsWith("/") ? authEndpoint : authEndpoint + "/";

        // Initial acquisition of FHIR and SignalR tokens
        // This is a blocking call in the constructor, which might be acceptable for initialization.
        // If this object is created frequently, consider making this asynchronous or lazy.
        try {
        	AcquireAllTokensAndSignalRInfo();
        } catch (Exception e) {
            LogError("Failed to acquire initial tokens and SignalR info: " + getStackTrace(e));
            // Depending on the application, you might want to throw a runtime exception here
            throw new Exception("Failed to acquire initial tokens and SignalR info: " + getStackTrace(e));
        }
    }

    public void LogInfo(String message) {
        logger.info(message);
    }

    public void LogError(String message) {
        logger.error(message);
    }

    /**
     * Acquires FHIR access token and then uses it to get SignalR connection info (URL and access token).
     * This method is crucial and should handle its own errors.
     * It updates _hubEndpoint, _signalRAccessToken, _tokenExpiredTime, _keepAliveIntervalInMilliseconds,
     * and _serverTimeoutInMilliseconds.
     *
     * @return The SignalR access token.
     * @throws Exception if token acquisition or SignalR info fetching fails.
     */
    private synchronized String AcquireAllTokensAndSignalRInfo() throws Exception {
        // Only refresh if token is null or close to expiry
        if (_signalRAccessToken == null || _tokenExpiredTime == null ||
            OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(TOKEN_REFRESH_BUFFER_MINUTES).isAfter(_tokenExpiredTime)) {

            LogInfo("Refreshing FHIR and SignalR access tokens...");
            String authorityUrl = AUTHORITY_DOMAIN + _authority;

            // 1. Acquire FHIR Access Token
            // Assuming GetAccessToken handles its own exceptions or throws them
            _fhirAccessToken = _rsUtil.GetAccessToken(_clientSecret, _clientID, authorityUrl, _scope);
            if (_fhirAccessToken == null || _fhirAccessToken.isEmpty()) {
                throw new Exception("Failed to acquire FHIR access token.");
            }
            LogInfo("FHIR access token acquired successfully.");

            // 2. Use FHIR token to get SignalR Connection Info
            try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
                String url = _authEndpoint + String.format("Token/SignalRConnectionInfo?hub=%s&user=%s", _hubName, _userId);
                HttpGet request = new HttpGet(url);
                request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + _fhirAccessToken);

                try (CloseableHttpResponse response = httpClient.execute(request)) {
                    HttpEntity entity = response.getEntity();
                    if (entity != null) {
                        String result = EntityUtils.toString(entity);
                        JsonObject resultObj = new JsonParser().parse(result).getAsJsonObject();
                        
                        if (resultObj.has("url") && resultObj.has("accessToken") && resultObj.has("expired")) {
                            _hubEndpoint = resultObj.get("url").getAsString();
                            _signalRAccessToken = resultObj.get("accessToken").getAsString();
                            String expiredDatetimeStr = resultObj.get("expired").getAsString();
                            SetHubConnectionAliveIntervalAndServerTimeout(expiredDatetimeStr);
                            LogInfo("SignalR connection info and access token acquired successfully.");
                        } else {
                            throw new Exception("SignalR connection info response missing required fields: " + result);
                        }
                    } else {
                        throw new Exception("SignalR connection info response entity is null.");
                    }
                }
            }
        }
        return _signalRAccessToken;
    }

    /**
     * Builds and returns a new HubConnection instance.
     * This method should not attempt to reconnect directly, but rather provide a fresh connection.
     * Reconnection logic is handled by the onClosed handler.
     *
     * @return A new HubConnection instance.
     * @throws Exception if connection building fails.
     */
    public HubConnection buildNewHubConnection() throws Exception {
        LogInfo("Building new SignalR HubConnection for endpoint: " + _hubEndpoint);
        // HubConnectionBuilder.create().withAccessTokenProvider expects a Single<String>
        // AcquireAllTokensAndSignalRInfo is blocking, so deferring it to Single.just()
        // ensures it's called when the token is needed by the provider.
        HubConnection hubConnection = HubConnectionBuilder.create(_hubEndpoint)
                .withAccessTokenProvider(Single.defer(() -> {
                    try {
                        return Single.just(AcquireAllTokensAndSignalRInfo());
                    } catch (Exception e) {
                        LogError("Failed to acquire SignalR access token for provider: " + getStackTrace(e));
                        return Single.error(e); // Propagate the error to the SignalR client
                    }
                }))
                .build();

        // Set keep-alive and server timeout based on token expiry
        hubConnection.setKeepAliveInterval(_keepAliveIntervalInMilliseconds);
        hubConnection.setServerTimeout(_serverTimeoutInMilliseconds);

        _hubConnection = hubConnection; // Store the current connection instance
        return _hubConnection;
    }

    /**
     * Stops the current HubConnection and disposes of reconnection attempts.
     * Sets a flag to prevent further automatic reconnections.
     */
    public void Stop() {
        LogInfo("Stopping SignalR HubConnection.");
        isDisposed = true; // Indicate that the connection should not be re-established automatically

        if (reconnectionDisposable != null && !reconnectionDisposable.isDisposed()) {
            reconnectionDisposable.dispose(); // Stop any pending reconnection attempts
            reconnectionDisposable = null;
        }

        if (_hubConnection != null) {
            try {
                _hubConnection.stop().blockingAwait(5, TimeUnit.SECONDS); // Block briefly to ensure stop completes
                
                LogInfo("SignalR HubConnection stopped successfully.");
            } catch (Exception e) {
                LogError("Error stopping HubConnection: " + getStackTrace(e));
            }
        }
    }

    /**
     * Sets the keep-alive and server timeout based on the token's expiration time.
     * Uses java.time for modern date/time handling.
     *
     * @param expiredDatetimeStr ISO 8601 formatted datetime string of token expiry.
     */
    public void SetHubConnectionAliveIntervalAndServerTimeout(String expiredDatetimeStr) {
        try {
            // Use java.time.format.DateTimeFormatter for ISO 8601 parsing
            // OffsetDateTime.parse can handle "2025-07-16T15:51:59.000000+00:00"
            _tokenExpiredTime = OffsetDateTime.parse(expiredDatetimeStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME);

            // Calculate difference in milliseconds
            long diffMillis = ChronoUnit.MILLIS.between(Instant.now(), _tokenExpiredTime.toInstant());

            // Ensure values are positive and reasonable
            _keepAliveIntervalInMilliseconds = Math.max(10000, diffMillis / 3); // At least 10 seconds, or 1/3 of remaining token life
            _serverTimeoutInMilliseconds = Math.max(30000, diffMillis / 2); // At least 30 seconds, or 1/2 of remaining token life

            LogInfo(String.format("Token expires at %s. Setting keepAliveInterval to %d ms and serverTimeout to %d ms.",
                    _tokenExpiredTime.toString(), _keepAliveIntervalInMilliseconds, _serverTimeoutInMilliseconds));

        } catch (Exception e) {
            LogError("Error setting HubConnection alive interval and server timeout: " + getStackTrace(e));
            // Fallback to default if parsing fails
            _keepAliveIntervalInMilliseconds = 15000; // Default SignalR client keep-alive
            _serverTimeoutInMilliseconds = 30000; // Default SignalR client server timeout
        }
    }

    /**
     * Checks the current status of the HubConnection.
     * @return The connection state as a String.
     */
    public String CheckStatus() {
        String connectionState = (_hubConnection != null) ? _hubConnection.getConnectionState().toString() : "DISCONNECTED (No HubConnection instance)";
        LogInfo("CheckStatus - HubConnection status: " + connectionState);
        return connectionState;
    }

    /**
     * Handles incoming messages from the SignalR hub by routing them to a Mirth channel.
     * @param message The received JsonObject message.
     */
    private void HandleMessage(JsonObject message) {
        LogInfo("Message received. Sending to channel '" + _channelName + "'");
        // Route message to a channel if there are messages pushed from server
        if (_vmRouter != null) {
            _vmRouter.routeMessage(_channelName, message.toString());
        } else {
            LogError("VMRouter is null. Cannot route message to channel: " + _channelName);
        }
    }

    /**
     * Creates and starts the SignalR HubConnection, sets up event handlers,
     * and manages reconnection logic.
     * @return A JsonObject indicating success or failure.
     */
    public JsonObject CreateAndStartHubConnection() {
        JsonObject resultJson = new JsonObject();
        resultJson.addProperty("status", "success");
        isDisposed = false; // Reset dispose flag when starting

        try {
            // Build a new connection instance
            buildNewHubConnection();

            // Set subscription on receive message
            _hubConnection.on(_signalREvent, this::HandleMessage, JsonObject.class);

            // Set up the onClosed handler for robust reconnection
            _hubConnection.onClosed(exception -> {
            	String exceptionMsg = Objects.toString((exception != null) ? exception.getMessage() : "", "");
                if (StringUtil.isNotBlank(exceptionMsg)) {                	
                	LogError("HubConnection on closed - Exception: " + exceptionMsg);
                	JsonObject errorResult = new JsonObject();
                	errorResult.addProperty("error", exceptionMsg);
                	errorResult.addProperty("status", "error");
                	errorResult.addProperty("detailError", getStackTrace(exception));
                	HandleMessage(errorResult); // Send error to channel
                }
                // Only attempt to reconnect if not explicitly disposed
                if (!isDisposed) {
                   LogInfo("Attempting to reconnect SignalR HubConnection...");
                    scheduleReconnect(INITIAL_RETRY_DELAY_MS, exceptionMsg); // Pass the message here
                } else {
                    LogInfo("HubConnection explicitly stopped. Not attempting to reconnect.");
                }
            });

            // Start the connection
            StartConnection();

        } catch (Exception e) {
            LogError("Error during initial HubConnection setup: " + getStackTrace(e));
            resultJson.addProperty("error", e.getMessage());
            resultJson.addProperty("status", "error");
            resultJson.addProperty("detailError", getStackTrace(e));
        }
        return resultJson;
    }

    /**
     * Starts the HubConnection. If it fails, it will be handled by onClosed.
     */
    private void StartConnection() {
        LogInfo("Attempting to start SignalR HubConnection.");
        // Use subscribeOn and observeOn to ensure the start operation is on a separate thread
        // and any subsequent actions are handled appropriately.
        // doOnError is crucial for catching immediate start failures.
        _hubConnection.start()
            .subscribeOn(Schedulers.io()) // Run start on an I/O thread
            .observeOn(Schedulers.io()) // Handle subsequent actions on an I/O thread
            .doOnError(e -> {
                LogError("Error starting HubConnection: " + getStackTrace(e));
                // The onClosed handler should ideally take care of reconnection.
                // If start() fails immediately, onClosed might not be triggered.
                // This `doOnError` ensures we log it.
                // For a robust retry, we could chain `retryWhen` here, but SignalR's onClosed
                // is designed to handle disconnections, so we rely on that for now.
            })
            .subscribe(() -> {
                LogInfo("HubConnection started successfully. State: " + _hubConnection.getConnectionState());
            }, e -> {
                // This is the error consumer for the subscribe.
                // If start() fails, this will also be called.
                LogError("HubConnection start subscription error: " + getStackTrace(e));
            });
    }

    /**
     * Schedules a reconnection attempt with an exponential back-off strategy.
     * @param delay The current delay for this attempt.
     */
    private synchronized void scheduleReconnect(long delay, String exceptionMsg) {
        if (isDisposed) {
            LogInfo("Reconnect attempt cancelled as connection is disposed.");
            return;
        }

        // Dispose of any previous reconnection attempts to prevent multiple concurrent retries
        if (reconnectionDisposable != null && !reconnectionDisposable.isDisposed()) {
            reconnectionDisposable.dispose();
        }

        LogInfo(String.format("Scheduling SignalR reconnection in %d ms.", delay));

        reconnectionDisposable = Single.timer(delay, TimeUnit.MILLISECONDS, Schedulers.io())
            .subscribe(
                __ -> {
                    if (!isDisposed) {
                        try {
                            if (_hubConnection != null && _hubConnection.getConnectionState() != HubConnectionState.DISCONNECTED) {
                                LogInfo("Stopping existing connection before reconnecting.");
                                _hubConnection.stop().blockingAwait(5, TimeUnit.SECONDS);
                            }
                            if (exceptionMsg != null && exceptionMsg.toLowerCase().contains("connection reset")) {
                                LogInfo("Detected 'connection reset' error. Re-creating and starting HubConnection.");
                                CreateAndStartHubConnection();
                            } else {
                                LogInfo("No 'connection reset' error. Attempting to start existing HubConnection.");
                                StartConnection();
                            }
                        } catch (Exception e) {
                            LogError("Error during scheduled reconnection attempt: " + getStackTrace(e));
                            long nextDelay = Math.min(delay * 2, MAX_RETRY_DELAY_MS);
                            scheduleReconnect(nextDelay, exceptionMsg);
                        }
                    } else {
                        LogInfo("Reconnect attempt aborted: connection disposed during delay.");
                    }
                },
                error -> {
                    LogError("Error in reconnection timer: " + getStackTrace(error));
                }
            );
    }

    /**
     * Restarts the HubConnection by stopping it and then creating a new one.
     */
    public void RestartHubConnection() {
        LogInfo("Initiating SignalR HubConnection restart.");
        Stop(); // Stop the current connection and cancel any pending reconnects
        // Give a small delay before attempting to restart to allow resources to clean up
        Single.timer(INITIAL_RETRY_DELAY_MS, TimeUnit.MILLISECONDS, Schedulers.io())
            .subscribe(
                __ -> CreateAndStartHubConnection(),
                error -> LogError("Error during restart delay: " + getStackTrace(error))
            );
    }

    /**
     * Helper method to get stack trace as a string.
     */
    private String getStackTrace(Throwable t) {
        if (t == null) {
            return "No stack trace available.";
        }
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        t.printStackTrace(pw);
        return sw.toString();
    }
}

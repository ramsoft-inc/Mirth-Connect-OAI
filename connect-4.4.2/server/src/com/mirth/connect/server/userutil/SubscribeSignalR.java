package com.mirth.connect.server.userutil;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.signalr.HubConnection;
import com.microsoft.signalr.HubConnectionBuilder;
import com.microsoft.signalr.HubConnectionState;

import io.reactivex.Single;
import io.reactivex.functions.Action;

public class SubscribeSignalR {
	private String _signalREvent;
    private String _hubName;
    private String _userId;
    private String _hubEndpoint;
    public String _signalRAccessToken;
    private String _fhirAccessToken;
	private HubConnection _hubConnection;
	private String _channelName;	
	private String _clientID;
	private String _clientSecret;
	private String _authority;
	private String _scope;
	private String _authEndpoint;
	private String authorityDomain = "https://login.microsoftonline.com/";
	private VMRouter _vmRouter; 
	private Logger logger = Logger.getLogger(getClass());
	private int timeOutInMiliseconds = 2000;
	private RSUtil _rsUtil;
	private DateTime _tokenExpiredTime;
	private long _keepAliveIntervalInMilliseconds;
	private long _serverTimeoutInMilliseconds;
	private boolean isDisposed = false;
	public SubscribeSignalR(String signalREvent, String hubName, String userId, String hubEndpoint, String channelName, 
			String clientID, String clientSecret, String authority, String scope, String authEndpoint) {
        this._signalREvent = signalREvent;
        this._hubName = hubName;
        this._userId = userId;
        this._hubEndpoint = hubEndpoint;
        this._channelName = channelName;
        this._clientID = clientID;
        this._clientSecret = clientSecret;
        this._authority = authority;
        this._scope	= scope;
        this._vmRouter = new VMRouter();
        this._rsUtil = new RSUtil();
        
        authEndpoint = authEndpoint.endsWith("/") ? authEndpoint : authEndpoint + "/";
        String authorityUrl = authorityDomain + _authority;
        _fhirAccessToken = _rsUtil.GetAccessToken(_clientSecret, _clientID, authorityUrl, _scope);
        
        this._authEndpoint	= authEndpoint;
        AcquireSignalRInfo();
    }
	public void LogInfo(String message) {
		logger.info( message );
	}
	public void LogError(String message) {
		logger.error( message );
	}
	public HubConnection getHubConnection() throws Exception {
		LogInfo("starting connect SignalR server: " + _hubEndpoint);
		HubConnection hubConnection = null;
		try {
			
			// build connection with access token
			hubConnection = HubConnectionBuilder.create(_hubEndpoint).withAccessTokenProvider(Single.defer(() -> {
				return Single.just(AcquireSignalRInfo());
			})).build();
			hubConnection.setKeepAliveInterval(_keepAliveIntervalInMilliseconds);
			hubConnection.setServerTimeout(_serverTimeoutInMilliseconds);
			_hubConnection = hubConnection;
		}
		catch (Exception e) {
			Thread.sleep(timeOutInMiliseconds);
			// retry
			hubConnection = HubConnectionBuilder.create(_hubEndpoint).withAccessTokenProvider(Single.defer(() -> {
				return Single.just(AcquireSignalRInfo());
			})).build();
			hubConnection.setKeepAliveInterval(_keepAliveIntervalInMilliseconds);
			hubConnection.setServerTimeout(_serverTimeoutInMilliseconds);
			_hubConnection = hubConnection;
		}
		return _hubConnection;
	}
	
	public void Stop() {
		 if (_hubConnection != null)
	     {
			 isDisposed = true;
			 _hubConnection.stop();
			 _hubConnection = null;
	     }
	}

	private void SetHubConnectionAliveIntervalAndServerTimeout(String expiredDatetimeStr) {
		try {
			
			DateTimeFormatter formatter = ISODateTimeFormat.dateTime();
			_tokenExpiredTime = formatter.parseDateTime(expiredDatetimeStr);
			long diffMillis = _tokenExpiredTime.getMillis() - DateTime.now().getMillis();
			_keepAliveIntervalInMilliseconds = diffMillis;
			_serverTimeoutInMilliseconds = diffMillis*2;
		}
		catch (Exception e) {
			LogError(e.getMessage());
		}
	}
	private String AcquireSignalRInfo() {
		try {
			if (_signalRAccessToken == null || DateTime.now().plusMinutes(10).getMillis() > _tokenExpiredTime.getMillis()) {
				String authorityUrl = authorityDomain + _authority;
		        _fhirAccessToken = _rsUtil.GetAccessToken(_clientSecret, _clientID, authorityUrl, _scope);
				CloseableHttpClient httpClient = HttpClients.createDefault();
		        try {
		        	String url = _authEndpoint + String.format("Token/SignalRConnectionInfo?hub=%s&user=%s", _hubName, _userId);
		            HttpGet request = new HttpGet(url);
	
		            // add request headers
		            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + _fhirAccessToken);
		            CloseableHttpResponse response = httpClient.execute(request);
		            try {	                
		                HttpEntity entity = response.getEntity();
		                if (entity != null) {
		                    // return it as a String
		                    String result = EntityUtils.toString(entity);
		                    JsonObject resultObj = new JsonParser().parse(result).getAsJsonObject();
		                    if (resultObj.has("url")) {
		                    	_hubEndpoint = resultObj.get("url").getAsString();
		                    }
		                    if (resultObj.has("accessToken")) {
		                    	_signalRAccessToken = resultObj.get("accessToken").getAsString();
		                    }
		                    if (resultObj.has("expired")) {
		                    	String expiredDatetimeStr = resultObj.get("expired").getAsString();
		                    	SetHubConnectionAliveIntervalAndServerTimeout(expiredDatetimeStr);
		                    }
		                }
	
		            } finally {
		                response.close();
		            }
		        } finally {
		            httpClient.close();
		        }
			}
		}
		catch (Exception e) {
			LogError(e.getMessage());
		}
		return _signalRAccessToken;
		
	}
	
	public String CheckStatus() {
		String connectionState = "";
		if (_hubConnection != null) {
			connectionState = _hubConnection.getConnectionState().toString();
		}
		LogInfo("CheckStatus - HubConnection status: " + connectionState);
		return connectionState;
	}
	
	private void HandleMessage(JsonObject message) {
		LogInfo("Message received. Send to channel '" + _channelName + "'");
		// route message to a channel if there are messages pushed from server
		_vmRouter.routeMessage(_channelName, message.toString()); 
	}
	public void CreateAndStartHubConnection() {
		try {
			isDisposed = false;
			// set Hub connection
			getHubConnection(); 			
			// set subscription on receive message
			_hubConnection.on(_signalREvent, (message) -> {	        	
				HandleMessage(message);
	        }, JsonObject.class);
			// Re-connect			
			_hubConnection.onClosed(exception ->{				
				String exceptionMsg = exception != null ? exception.getMessage() : "";
				if (!exceptionMsg.isBlank()) {					
					LogError("HubConnection on closed - Exception: " + exceptionMsg);
				}
				if (!isDisposed) {					
					if (exceptionMsg.contains("Connection reset")) {
						LogError("Create new hubconnection: start");
						_hubConnection.stop();
						CreateAndStartHubConnection();
						LogError("Create new hubconnection: end");
					}
					else {						
						LogInfo("HubConnection on closed: Start connection again" );
						Start();					
					}
				}
			});
			try {				
				Start();
			}
			catch (Exception e) {
				Thread.sleep(timeOutInMiliseconds);
				// retry
				Start();
			}
		}
		catch (Exception e) {
			LogError(e.getMessage());			
		}
    }
	private void Start() {		
		_hubConnection.start().doFinally(new Action() {
			@Override
			public void run() {
				LogInfo("Start.doOnComplete - HubConnection status: " + _hubConnection.getConnectionState());
				if (_hubConnection.getConnectionState() != HubConnectionState.CONNECTED) {
					LogInfo("Start.doOnComplete - Create new hubconnection - Start");
					_hubConnection.stop();
					CreateAndStartHubConnection();
					LogInfo("Start.doOnComplete - Create new hubconnection - End");
				}
			}			
		});
	}
	
	public void RestartHubConnection() {
		Stop();
		CreateAndStartHubConnection();
	}
}

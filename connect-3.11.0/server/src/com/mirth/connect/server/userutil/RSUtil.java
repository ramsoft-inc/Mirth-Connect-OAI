package com.mirth.connect.server.userutil;

import com.google.gson.JsonObject;
import com.microsoft.signalr.HubConnection;
import com.microsoft.signalr.HubConnectionBuilder;
import com.microsoft.signalr.OnClosedCallback;
import com.microsoft.signalr.Subscription;

import java.io.PrintStream;
import java.util.Enumeration;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Appender;
import org.apache.log4j.Logger;

import com.mirth.connect.server.logging.JuliToLog4JService;
import com.mirth.connect.server.logging.LogOutputStream;
import com.mirth.connect.server.logging.MirthLog4jFilter;

import io.reactivex.Single;
import io.reactivex.observers.DisposableCompletableObserver;

/**
 * @author RamSoft
 * Ramsoft class to connect and receive message from SignalR
 *
 */
public class RSUtil {
	private static VMRouter vmRouter = new VMRouter();
	private Logger logger = Logger.getLogger(getClass());
	public RSUtil() {}
	
	public HubConnection getHubConnection(String signalrConnectionString, String accessToken) {
		logger.info("starting connect SignalR server: " + signalrConnectionString);
		HubConnection hubConnection = null;
		if (!StringUtils.isEmpty(accessToken)) {
			// build connection with access token
			hubConnection = HubConnectionBuilder.create(signalrConnectionString).withAccessTokenProvider(Single.defer(() -> {
	            return Single.just(accessToken);
	        })).build();
		}
		else {
			hubConnection = HubConnectionBuilder.create(signalrConnectionString).build();
		}
		return hubConnection;
	}
	public Subscription getHubHandler (HubConnection hubConnection, String hubMethod, String channelName) {
		Subscription handler = hubConnection.on(hubMethod, (message) -> {
        	// route message to a channel if there are messages pushed from server
        	logger.info("Message received. Send to channel '" + channelName + "'");
        	vmRouter.routeMessage(channelName, message.toString());        	
        }, JsonObject.class);
		
        return handler;
	}
	public void disposeHandler (Subscription handler) {
		handler.unsubscribe();
	}
	public HubConnection startHandler (HubConnection hubConnection) {
		hubConnection.start().subscribe(new DisposableCompletableObserver() {
            @Override
            public void onComplete() {
            	logger.info("Connection Started");
            }

            @Override
            public void onError(Throwable e) {
            	logger.info("Connection Start Error");
            }            
        });
		hubConnection.onClosed(new OnClosedCallback() {
			@Override
			public void invoke(Exception arg0) {
				logger.info("Connection Closed");
				
			}			
		});
        return hubConnection;
	}
	public void stopHandler (HubConnection hubConnection) {
		hubConnection.stop().subscribe(new DisposableCompletableObserver() {
            @Override
            public void onComplete() {
            	logger.info("Connection Stopped");
            }

            @Override
            public void onError(Throwable e) {
            	logger.info("Connection Stop Error");
            }            
        });
	}
	
	public Subscription receiveMessageSignalR(String signalrConnectionString, String accessToken, String hubMethod, String channelName) {
		initializeLogging();
		logger.info("starting connect SignalR server: " + signalrConnectionString);
		HubConnection hubConnection = null;
		Subscription listenHandler = null;
		try {
			// set Hub connection
			hubConnection = getHubConnection(signalrConnectionString, accessToken); 
			// set subscription on receive message
			listenHandler = getHubHandler(hubConnection, hubMethod, channelName);			
			// start hub connection
			hubConnection = startHandler(hubConnection);
		}
		catch (Exception e) {
			logger.error(e.getMessage());			
		}
		return listenHandler;
    }
	private void initializeLogging() {
        // Route all System.err messages to log4j error
        System.setErr(new PrintStream(new LogOutputStream()));
        // Route all java.util.logging.Logger output to log4j
        JuliToLog4JService.getInstance().start();
        // Add a custom filter to appenders to suppress SAXParser warnings
        for (Enumeration<?> en = Logger.getRootLogger().getAllAppenders(); en.hasMoreElements();) {
            ((Appender) en.nextElement()).addFilter(new MirthLog4jFilter());
        }
    }
}

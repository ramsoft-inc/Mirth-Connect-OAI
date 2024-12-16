package com.mirth.connect.server.userutil;

import com.microsoft.aad.msal4j.ClientCredentialFactory;
import com.microsoft.aad.msal4j.ClientCredentialParameters;
import com.microsoft.aad.msal4j.ConfidentialClientApplication;
import com.microsoft.aad.msal4j.IAuthenticationResult;
import com.microsoft.aad.msal4j.IClientSecret;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import org.apache.log4j.Logger;


public class RSUtil {
	private Logger logger = Logger.getLogger(getClass());
	public RSUtil() {}
	
	public String GetAccessToken(String _clientSecret, String _clientID, String _authority, String _scope) {
		String tokenString = "";
		try {
			IClientSecret secret = ClientCredentialFactory.createFromSecret(_clientSecret);
			ConfidentialClientApplication app = ConfidentialClientApplication.builder(_clientID,secret).authority(_authority).build();
			
			ClientCredentialParameters parameters = ClientCredentialParameters.builder(Collections.singleton(_scope)).build();
			CompletableFuture<IAuthenticationResult> future = app.acquireToken(parameters);
			// Obtain token string from future object
			tokenString = future.get().accessToken();
		} catch (Exception e) {
			logger.error(e.getMessage());
		}
        return tokenString;
	}
}

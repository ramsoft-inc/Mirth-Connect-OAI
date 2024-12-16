package com.ramsoft.http;

import java.io.*; 


import org.apache.http.*; 
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HTTP;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.NameValuePair;

import org.apache.log4j.Logger;

import javax.net.ssl.*; 
import java.security.cert.*;

   
class DefaultTrustManager implements X509TrustManager
{
	// concept from http://hc.apache.org/index.html
	public void checkClientTrusted(X509Certificate[] arg0, String arg1)
			throws CertificateException {} // do nothing here 

	public void checkServerTrusted(X509Certificate[] arg0, String arg1)
			throws CertificateException {} // do nothing here 

	public X509Certificate[] getAcceptedIssuers() 
	{
		return null;
	}
}

public class RSXmlHttp	
{
	private Logger logger = Logger.getLogger(this.getClass());

	public static String GetResponseBodyString(final HttpEntity entity) throws IOException, ParseException 
	{
		if (entity == null)
		{
			throw new IllegalArgumentException("HTTP entity may not be null"); 
		}
			
		InputStream instream = entity.getContent();

		if (instream == null) 
		{ 
			return ""; 
		}
		else
		{
			if (entity.getContentLength() > Integer.MAX_VALUE) 
			{ 
				throw new IllegalArgumentException("HTTP entity too large to be buffered in memory"); 
			}
			
			String charset = GetEntityContentCharSet(entity);

			if (charset == null) 
			{
				charset = HTTP.DEFAULT_CONTENT_CHARSET;
			}

			Reader reader = new InputStreamReader(instream, charset);

			StringBuilder buffer = new StringBuilder();
			try 
			{
				char[] tmp = new char[8192];

				int l;

				while ((l = reader.read(tmp)) != -1) 
				{
					buffer.append(tmp, 0, l);
				}
			} 
			finally 
			{
				reader.close();
			}

			return buffer.toString();
		}
	}
	
	public static String GetEntityContentCharSet(final HttpEntity entity) throws ParseException 
	{
		if (entity == null) 
		{ 
			throw new IllegalArgumentException("HTTP entity may not be null"); 
		}

		String charset = null;

		if (entity.getContentType() != null) 
		{
			HeaderElement values[] = entity.getContentType().getElements();
			if (values.length > 0) 
			{
				NameValuePair param = values[0].getParameterByName("charset");

				if (param != null) 
				{
					charset = param.getValue();
				}
			}
		}

		return charset;
	}

	public String HttpSecurePostWithoutCertificateCheck(String URL, String messageToPost, String SecurityToken, int timeoutMillis) throws Exception 
	{
		String[] HeaderNames = null;
		String[] HeaderValues = null;			

		if ((SecurityToken != null) && (SecurityToken != ""))
		{
			HeaderNames = new String[]{"Cookie"};
			HeaderValues = new String[]{"token=" + SecurityToken};			
		}
		
		return HttpSecurePostWithHeaders(URL, messageToPost, HeaderNames, HeaderValues, timeoutMillis, "text/xml; charset=utf-8");		
	}  

	public String HttpSecurePostWithHeaders(String URL, String messageToPost, String[] HeaderNames, String[] HeaderValues, 
			int timeoutMillis, String ContentType) throws Exception 
	{
		
		String ResponseStr = "";
		TrustManager[] trustAllCerts = new TrustManager[] {new DefaultTrustManager()};
		SSLContext sc = SSLContext.getInstance("TLS");
		sc.init(null, trustAllCerts, new java.security.SecureRandom());
	
		SSLSocketFactory sf = new SSLSocketFactory(sc, SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
		Scheme sch = new Scheme("https", 443, sf);
		DefaultHttpClient httpclient = new DefaultHttpClient();
		HttpParams httpParams = httpclient.getParams();
		//java.net.SocketTimeoutException: Read timed out
		
		//set connection timeout - timeout to establish a connection
		HttpConnectionParams.setConnectionTimeout(httpParams, timeoutMillis);
		
		//set socket timeout -- timeout between packets
		HttpConnectionParams.setSoTimeout(httpParams, timeoutMillis);

		httpclient.getConnectionManager().getSchemeRegistry().register(sch);
		HttpPost httppost = new HttpPost(URL);

		try 
		{
			StringEntity reqEntity = new StringEntity(messageToPost);
			
			if (ContentType != null && ContentType != "")
			{
				reqEntity.setContentType(ContentType);
			}
			
			if ((HeaderNames != null) && (HeaderValues != null) && (HeaderNames.length > 0) && 
				(HeaderNames.length == HeaderValues.length))
			{
				for (int i = 0; i < HeaderNames.length; i++)
				{
			        httppost.addHeader(HeaderNames[i], HeaderValues[i]);
				}
			}
			
			httppost.setEntity(reqEntity);
			
			java.net.URI URI = httppost.getURI();
			
			logger.info("HttpSecurePostWithHeaders: " + URI.toString());
			HttpResponse response = httpclient.execute(httppost);
			logger.info("HttpSecurePostWithHeaders: httpclient.execute complete");

			HttpEntity resEntity = response.getEntity();
			if (resEntity != null) 
			{
				ResponseStr = GetResponseBodyString(resEntity);
			}
			return ResponseStr;
		} 
		finally 
		{
			logger.info("HttpSecurePostWithHeaders: shutdown connectionManager");
			httpclient.getConnectionManager().shutdown();
		}
	}  
	
	public String HttpSecureMethodWithHeaders(String MethodVerb, String URL, String requestMessage, String[] HeaderNames, String[] HeaderValues, 
			int timeoutMillis, String ContentType) throws Exception 
	{
		
		String ResponseStr = "";
		TrustManager[] trustAllCerts = new TrustManager[] {new DefaultTrustManager()};
		SSLContext sc = SSLContext.getInstance("TLS");
		sc.init(null, trustAllCerts, new java.security.SecureRandom());
	
		SSLSocketFactory sf = new SSLSocketFactory(sc, SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
		Scheme sch = new Scheme("https", 443, sf);
		DefaultHttpClient httpclient = new DefaultHttpClient();
		HttpParams httpParams = httpclient.getParams();
		//java.net.SocketTimeoutException: Read timed out
		
		//set connection timeout - timeout to establish a connection
		HttpConnectionParams.setConnectionTimeout(httpParams, timeoutMillis);
		
		//set socket timeout -- timeout between packets
		HttpConnectionParams.setSoTimeout(httpParams, timeoutMillis);

		httpclient.getConnectionManager().getSchemeRegistry().register(sch);
		
		
		HttpRequestBase httpMethod = null;
		
		logger.info("HttpSecureMethodWithHeaders: Verb " + MethodVerb);
		
		if (MethodVerb.equals("GET")) 
		{
			httpMethod = new HttpGet(URL);
		}
		else if (MethodVerb.equals("PUT"))
		{
			httpMethod = new HttpPut(URL);
		}
		else if (MethodVerb.equals("PATCH"))
		{
			httpMethod = new HttpPatch(URL);
		}
		else if (MethodVerb.equals("DELETE"))
		{
			httpMethod = new HttpDelete(URL);
		}
		else 
		{
			httpMethod = new HttpPost(URL);
		}

		logger.info("HttpSecureMethodWithHeaders: " + httpMethod.getMethod().toString());		
		
		try 
		{
			if ((httpMethod instanceof HttpPost) || (httpMethod instanceof HttpPatch) || (httpMethod instanceof HttpPut))
			{				
				StringEntity reqEntity = new StringEntity(requestMessage);
				
				if (ContentType != null && ContentType != "")
				{
					reqEntity.setContentType(ContentType);
				}
				
				HttpEntityEnclosingRequestBase httpRequesetMethod = (HttpEntityEnclosingRequestBase)(httpMethod);
				httpRequesetMethod.setEntity(reqEntity);				
			}
			
			if ((HeaderNames != null) && (HeaderValues != null) && (HeaderNames.length > 0) && 
				(HeaderNames.length == HeaderValues.length))
			{
				for (int i = 0; i < HeaderNames.length; i++)
				{
					httpMethod.addHeader(HeaderNames[i], HeaderValues[i]);
				}
			}
			
			java.net.URI URI = httpMethod.getURI();
			
			logger.info("HttpSecureMethodWithHeaders: " + URI.toString());
			HttpResponse response = httpclient.execute(httpMethod);
			logger.info("HttpSecureMethodWithHeaders: httpclient.execute complete");

			HttpEntity resEntity = response.getEntity();
			if (resEntity != null) 
			{
				ResponseStr = GetResponseBodyString(resEntity);
			}
			return ResponseStr;
		} 
		finally 
		{
			logger.info("HttpSecureMethodWithHeaders: shutdown connectionManager");
			httpclient.getConnectionManager().shutdown();
		}
	}  	
	

    /*public final static void main(String[] args) throws Exception 
    {
		RSXmlHttp XmlHttp = new RSXmlHttp();
		String URL = "https://secure.practicesuite.com/PracticeSuite/com.ps.adapters.inbound.http.HttpAdapter";
		String[] HeaderNames = new String[]{"Version", "Username", "Password", "Account", "Message Format", "Message Type", "Usage"};
		String[] HeaderValues = new String[]{"1.0", "hl7ramsoft", "hl7C0nnect135", "QA", "HL7", "RAMSOFT_ADT", "PROD"};

		String messageToPost = "MSH|^~\\&|RAMSOFT|RAMSOFT|RAMSOFT|RAMSOFT|20140509140442-0400||ADT^A08|2910|P|2.3.1||||||||\n" +
			"EVN|A08|20140509140442-0400||||\n" + 
			"PID|1|RAM1234^^^RAMSOFT^MR|RAM1234^^^RAMSOFT^MR||RAMSOFT^SERGE^A^^||19910517|M||UNK^DECLINED^HL70005|243 COLLEGE STR.^^TORONTO^ON^M6B1B1^CA||4166742997^^^^^^|^^^^^^|ENG|M||123123|123456789|||UNK^DECLINED^HL70189|\n" +
			"PV1||O||||||||||||||||||||||||||||||||||||||||||||||||||\n" +
			"GT1|1||RAMSOFT^SERGE^A^^||243 COLLEGE STR.^^TORONTO^ON^M6B1B1^CA|4166742997||19910517|M||SEL||||||^^^^^|||1|||||||||||||||||||||||||RAMSOFT DHIREN|3809840298||OTH|||||||\n"+
			"IN1|1|123321123|321321321|RAMSOFT MEDICAL|243 COLLEGE STR^^TORONTO^ON^M6B1B1^CA|RAMSOFT MEDICAL|3483874933999|321123321||||20110826|20190524||CO|RAMSOFT^SERGE^A|SEL|19910517|243 COLLEGE STR.^^TORONTO^ON^M6B1B1^CA|||||||||||||||||||||||1|M|^^^^^|||||123321123"
			;

		String ResponseStr = XmlHttp.HttpSecurePostWithHeaders(URL, messageToPost, HeaderNames, HeaderValues, 90000, "text/html; charset=UTF-8");
		System.out.println("Response content: " + ResponseStr);

	}*/

}
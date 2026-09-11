package com.smartrice.server.ai;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.dify")
public class DifyProperties {
	private String baseUrl = "http://localhost/v1";
	private String apiKey = "";
	private int connectTimeoutSeconds = 5;
	private int timeoutSeconds = 180;

	public String getBaseUrl() { return baseUrl; }
	public void setBaseUrl(String value) { baseUrl = value; }
	public String getApiKey() { return apiKey; }
	public void setApiKey(String value) { apiKey = value; }
	public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
	public void setConnectTimeoutSeconds(int value) { connectTimeoutSeconds = value; }
	public int getTimeoutSeconds() { return timeoutSeconds; }
	public void setTimeoutSeconds(int value) { timeoutSeconds = value; }

	public boolean configured() {
		try {
			URI uri = URI.create(baseUrl);
			return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
				&& uri.getHost() != null && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
				&& apiKey != null && !apiKey.isBlank() && !apiKey.contains("\r") && !apiKey.contains("\n")
				&& connectTimeoutSeconds > 0 && connectTimeoutSeconds <= 30 && timeoutSeconds > 0 && timeoutSeconds <= 600;
		} catch (RuntimeException ex) { return false; }
	}
}

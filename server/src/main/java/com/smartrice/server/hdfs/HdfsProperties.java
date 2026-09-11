package com.smartrice.server.hdfs;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.hdfs")
public class HdfsProperties {

	private String webUrl = "";
	private String user = "root";
	private String basePath = "/rice/output";
	private int connectTimeoutSeconds = 5;
	private int timeoutSeconds = 30;
	private Map<String, String> dataNodeHostOverrides = new LinkedHashMap<>();

	public String getWebUrl() { return webUrl; }
	public void setWebUrl(String webUrl) { this.webUrl = webUrl; }
	public String getUser() { return user; }
	public void setUser(String user) { this.user = user; }
	public String getBasePath() { return basePath; }
	public void setBasePath(String basePath) { this.basePath = basePath; }
	public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
	public void setConnectTimeoutSeconds(int value) { this.connectTimeoutSeconds = value; }
	public int getTimeoutSeconds() { return timeoutSeconds; }
	public void setTimeoutSeconds(int value) { this.timeoutSeconds = value; }
	public Map<String, String> getDataNodeHostOverrides() { return dataNodeHostOverrides; }
	public void setDataNodeHostOverrides(Map<String, String> value) { this.dataNodeHostOverrides = value; }
}

package com.smartrice.server.hive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Separate from spring.datasource, which remains owned by MySQL/JPA. */
@Component
@ConfigurationProperties(prefix = "app.hive")
public class HiveProperties {

	private String url = "";
	private String username = "";
	private String password = "";
	private int queryTimeoutSeconds = 30;
	private int socketTimeoutMs = 45_000;
	/** Idle HiveServer2 sessions kept for reuse; 0 restores one fresh session per query. */
	private int poolSize = 4;
	/** A session idle beyond this is reopened rather than reused, so a recycled session is never handed out. */
	private int poolIdleSeconds = 300;

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public int getQueryTimeoutSeconds() {
		return queryTimeoutSeconds;
	}

	public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
		this.queryTimeoutSeconds = queryTimeoutSeconds;
	}

	public int getSocketTimeoutMs() {
		return socketTimeoutMs;
	}

	public void setSocketTimeoutMs(int socketTimeoutMs) {
		this.socketTimeoutMs = socketTimeoutMs;
	}

	public int getPoolSize() {
		return poolSize;
	}

	public void setPoolSize(int poolSize) {
		this.poolSize = poolSize;
	}

	public int getPoolIdleSeconds() {
		return poolIdleSeconds;
	}

	public void setPoolIdleSeconds(int poolIdleSeconds) {
		this.poolIdleSeconds = poolIdleSeconds;
	}
}

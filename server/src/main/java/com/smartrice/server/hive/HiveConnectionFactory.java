package com.smartrice.server.hive;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Opens an independent HiveServer2 session on demand; callers must close it. */
@Component
public class HiveConnectionFactory implements AutoCloseable {

	private final HiveProperties properties;
	private final ConnectionOpener opener;
	private IsolatedHiveDriver isolatedDriver;
	private boolean closed;

	@Autowired
	public HiveConnectionFactory(HiveProperties properties) {
		this(properties, null);
	}

	HiveConnectionFactory(HiveProperties properties, ConnectionOpener opener) {
		this.properties = properties;
		this.opener = opener;
	}

	public synchronized Connection open() throws SQLException {
		if (closed) {
			throw new SQLException("Hive 连接工厂已关闭。", "08003");
		}
		String url = connectionUrl();
		Properties credentials = new Properties();
		credentials.setProperty("user", properties.getUsername() == null ? "" : properties.getUsername());
		credentials.setProperty("password", properties.getPassword() == null ? "" : properties.getPassword());
		try {
			if (opener != null) {
				return opener.open(url, credentials);
			}
			if (isolatedDriver == null) {
				isolatedDriver = new IsolatedHiveDriver();
			}
			return isolatedDriver.connect(url, credentials);
		}
		catch (SQLException | IOException | ReflectiveOperationException | RuntimeException | LinkageError ex) {
			// Driver errors can contain the URL and authentication values; never propagate their text/cause.
			throw new SQLException("无法连接 Hive，请检查服务地址、认证配置及服务状态。", "08001");
		}
	}

	@Override
	@PreDestroy
	public synchronized void close() throws IOException {
		closed = true;
		if (isolatedDriver != null) {
			isolatedDriver.close();
			isolatedDriver = null;
		}
	}

	public int queryTimeoutSeconds() throws SQLException {
		if (properties.getQueryTimeoutSeconds() <= 0) {
			throw invalidConfiguration();
		}
		return properties.getQueryTimeoutSeconds();
	}

	private String connectionUrl() throws SQLException {
		queryTimeoutSeconds();
		if (properties.getSocketTimeoutMs() <= 0) {
			throw invalidConfiguration();
		}
		String url = properties.getUrl();
		if (url == null || !url.startsWith("jdbc:hive2://")) {
			throw invalidConfiguration();
		}
		try {
			URI uri = URI.create(url.substring("jdbc:".length()));
			if (uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null
					|| uri.getPort() == 0 || uri.getPort() > 65_535
					|| uri.getPath() == null || !uri.getPath().startsWith("/")) {
				throw invalidConfiguration();
			}
		}
		catch (IllegalArgumentException ex) {
			throw invalidConfiguration();
		}

		// JDBC session parameters belong before the optional ?hive-conf and #hive-var sections.
		int suffixAt = url.length();
		for (char separator : new char[] {'?', '#'}) {
			int index = url.indexOf(separator);
			if (index >= 0) {
				suffixAt = Math.min(suffixAt, index);
			}
		}
		String[] sessionParts = url.substring(0, suffixAt).split(";", -1);
		StringBuilder configured = new StringBuilder(sessionParts[0]);
		for (int i = 1; i < sessionParts.length; i++) {
			String part = sessionParts[i];
			String key = part.substring(0, part.indexOf('=') >= 0 ? part.indexOf('=') : part.length()).toLowerCase(Locale.ROOT);
			if (key.equals("user") || key.equals("password") || key.equals("initfile")) {
				// Credentials stay in Properties; connection creation must not execute a configured SQL script.
				throw invalidConfiguration();
			}
			if (!part.isEmpty() && !key.equals("sockettimeout")) {
				configured.append(';').append(part);
			}
		}
		return configured.append(";socketTimeout=").append(properties.getSocketTimeoutMs())
			.append(url.substring(suffixAt)).toString();
	}

	private static SQLException invalidConfiguration() {
		return new SQLException("Hive 配置无效，请检查远程 JDBC 地址与正数超时设置，凭据应使用独立配置项。", "08001");
	}

	@FunctionalInterface
	interface ConnectionOpener {
		Connection open(String url, Properties credentials) throws SQLException;
	}
}

package com.smartrice.server.hive;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Hands out a HiveServer2 session on demand; callers must close it.
 *
 * <p>Opening one costs about 360ms cold and 50ms warm, which every query used to pay. Closing a
 * connection now returns the session to a small idle pool instead of ending it, so only the first
 * caller pays. Sessions idle past {@code app.hive.pool-idle-seconds} are reopened rather than reused.
 */
@Component
public class HiveConnectionFactory implements AutoCloseable {

	private static final int MAX_POOL_SIZE = 32;
	private static final int MAX_POOL_IDLE_SECONDS = 3600;
	private final HiveProperties properties;
	private final ConnectionOpener opener;
	private final Deque<Idle> idle = new ArrayDeque<>();
	private IsolatedHiveDriver isolatedDriver;
	private boolean closed;

	private record Idle(Connection connection, long idleSinceNanos) {
	}

	@Autowired
	public HiveConnectionFactory(HiveProperties properties) {
		this(properties, null);
	}

	HiveConnectionFactory(HiveProperties properties, ConnectionOpener opener) {
		this.properties = properties;
		this.opener = opener;
	}

	public Connection open() throws SQLException {
		Connection reused = borrow();
		return guard(reused == null ? create() : reused);
	}

	/** Most recently returned session first: it is the one least likely to have been recycled server side. */
	private synchronized Connection borrow() throws SQLException {
		if (closed) {
			throw new SQLException("Hive 连接工厂已关闭。", "08003");
		}
		long limit = poolIdleNanos();
		while (!idle.isEmpty()) {
			Idle candidate = idle.pollLast();
			if (System.nanoTime() - candidate.idleSinceNanos() <= limit) {
				return candidate.connection();
			}
			discard(candidate.connection());
		}
		return null;
	}

	private void release(Connection connection) {
		boolean pooled;
		synchronized (this) {
			pooled = !closed && idle.size() < poolCapacity();
			if (pooled) {
				idle.addLast(new Idle(connection, System.nanoTime()));
			}
		}
		if (!pooled) {
			discard(connection);
		}
	}

	private static void discard(Connection connection) {
		try {
			connection.close();
		}
		catch (SQLException ignored) {
			// A session that cannot be ended cleanly is already unusable; nothing is logged.
		}
	}

	// Out-of-range values disable pooling instead of failing every query: a misconfigured pool must not
	// be able to take the whole Hive integration down.
	private int poolCapacity() {
		return Math.max(0, Math.min(properties.getPoolSize(), MAX_POOL_SIZE));
	}

	private long poolIdleNanos() {
		int seconds = properties.getPoolIdleSeconds();
		return TimeUnit.SECONDS.toNanos(seconds <= 0 ? 0 : Math.min(seconds, MAX_POOL_IDLE_SECONDS));
	}

	private synchronized Connection create() throws SQLException {
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

	/** Returns a session to the pool on close(); every other call goes straight to the real connection. */
	private Connection guard(Connection delegate) {
		return (Connection) Proxy.newProxyInstance(ClassLoader.getPlatformClassLoader(),
			new Class<?>[] {Connection.class}, new PooledConnection(delegate));
	}

	private final class PooledConnection implements InvocationHandler {

		private final Connection delegate;
		private boolean returned;

		PooledConnection(Connection delegate) {
			this.delegate = delegate;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
			if (method.getDeclaringClass() == Object.class) {
				return switch (method.getName()) {
					case "equals" -> proxy == arguments[0];
					case "hashCode" -> System.identityHashCode(proxy);
					case "toString" -> "Pooled Hive connection";
					default -> throw new IllegalStateException("Unsupported object method");
				};
			}
			boolean noArguments = method.getParameterCount() == 0;
			if (noArguments && "close".equals(method.getName())) {
				// Idempotent: try-with-resources plus an explicit close must not pool one session twice.
				if (!returned) {
					returned = true;
					release(delegate);
				}
				return null;
			}
			if (noArguments && "isClosed".equals(method.getName()) && returned) {
				return Boolean.TRUE;
			}
			if (returned) {
				throw new SQLException("Hive 连接已归还连接池，请重新获取。", "08003");
			}
			try {
				return method.invoke(delegate, arguments);
			}
			catch (InvocationTargetException ex) {
				throw ex.getCause();
			}
		}
	}

	@Override
	@PreDestroy
	public void close() throws IOException {
		List<Connection> pending;
		synchronized (this) {
			closed = true;
			pending = idle.stream().map(Idle::connection).toList();
			idle.clear();
		}
		// Ending sessions outside the lock keeps a stalled HiveServer2 from blocking shutdown of the rest.
		pending.forEach(HiveConnectionFactory::discard);
		synchronized (this) {
			if (isolatedDriver != null) {
				isolatedDriver.close();
				isolatedDriver = null;
			}
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

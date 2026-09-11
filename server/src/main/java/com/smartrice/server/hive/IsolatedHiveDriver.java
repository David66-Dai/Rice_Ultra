package com.smartrice.server.hive;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import org.springframework.core.io.ClassPathResource;

/** Keeps Hive/Hadoop and their shaded dependencies outside Spring's application classpath. */
final class IsolatedHiveDriver implements AutoCloseable {

	private static final String CLEANUP_CLASS = IsolatedHiveDriverCleanup.class.getName();
	private final Path extractedJar;
	private final DriverClassLoader loader;
	private final Driver driver;

	IsolatedHiveDriver() throws IOException, ReflectiveOperationException {
		extractedJar = Files.createTempFile("rice-hive-driver-", ".jar");
		DriverClassLoader createdLoader = null;
		try {
			try (InputStream input = new ClassPathResource("hive-driver/hive-jdbc-standalone.jar").getInputStream()) {
				Files.copy(input, extractedJar, StandardCopyOption.REPLACE_EXISTING);
			}
			createdLoader = new DriverClassLoader(extractedJar.toUri().toURL(), IsolatedHiveDriver.class.getClassLoader());
			loader = createdLoader;
			ClassLoader previous = Thread.currentThread().getContextClassLoader();
			try {
				Thread.currentThread().setContextClassLoader(loader);
				driver = (Driver) Class.forName("org.apache.hive.jdbc.HiveDriver", true, loader).getDeclaredConstructor().newInstance();
			}
			finally {
				Thread.currentThread().setContextClassLoader(previous);
			}
		}
		catch (IOException | ReflectiveOperationException | RuntimeException | LinkageError ex) {
			if (createdLoader != null) {
				try {
					cleanupDrivers(createdLoader);
				}
				catch (Exception ignored) {
					// The original setup failure remains authoritative; no configuration values are logged.
				}
				finally {
					createdLoader.close();
				}
			}
			Files.deleteIfExists(extractedJar);
			throw ex;
		}
	}

	Connection connect(String url, Properties credentials) throws SQLException {
		ClassLoader previous = Thread.currentThread().getContextClassLoader();
		try {
			Thread.currentThread().setContextClassLoader(loader);
			Connection connection = driver.connect(url, credentials);
			if (connection == null) {
				throw new SQLException("Hive 驱动不支持指定地址。");
			}
			return (Connection) wrapJdbc(connection, loader);
		}
		finally {
			Thread.currentThread().setContextClassLoader(previous);
		}
	}

	static Object wrapJdbc(Object delegate, ClassLoader contextLoader) {
		Class<?> jdbcInterface = jdbcInterface(delegate);
		if (jdbcInterface == null) {
			return delegate;
		}
		return Proxy.newProxyInstance(ClassLoader.getPlatformClassLoader(), new Class<?>[] {jdbcInterface}, (proxy, method, arguments) -> {
			if (method.getDeclaringClass() == Object.class) {
				return switch (method.getName()) {
					case "equals" -> proxy == arguments[0];
					case "hashCode" -> System.identityHashCode(proxy);
					case "toString" -> "Isolated Hive JDBC " + jdbcInterface.getSimpleName();
					default -> throw new IllegalStateException("Unsupported object method");
				};
			}
			ClassLoader previous = Thread.currentThread().getContextClassLoader();
			try {
				Thread.currentThread().setContextClassLoader(contextLoader);
				return wrapJdbc(method.invoke(delegate, arguments), contextLoader);
			}
			catch (InvocationTargetException ex) {
				throw ex.getCause();
			}
			finally {
				Thread.currentThread().setContextClassLoader(previous);
			}
		});
	}

	private static Class<?> jdbcInterface(Object value) {
		if (value instanceof Connection) return Connection.class;
		if (value instanceof CallableStatement) return CallableStatement.class;
		if (value instanceof PreparedStatement) return PreparedStatement.class;
		if (value instanceof Statement) return Statement.class;
		if (value instanceof ResultSet) return ResultSet.class;
		if (value instanceof DatabaseMetaData) return DatabaseMetaData.class;
		if (value instanceof ResultSetMetaData) return ResultSetMetaData.class;
		if (value instanceof ParameterMetaData) return ParameterMetaData.class;
		return null;
	}

	@Override
	public void close() throws IOException {
		try {
			cleanupDrivers(loader);
		}
		catch (ReflectiveOperationException ex) {
			throw new IOException("无法注销隔离 Hive 驱动。");
		}
		finally {
			try {
				loader.close();
			}
			finally {
				Files.deleteIfExists(extractedJar);
			}
		}
	}

	private static void cleanupDrivers(ClassLoader loader) throws ReflectiveOperationException {
		ClassLoader previous = Thread.currentThread().getContextClassLoader();
		try {
			Thread.currentThread().setContextClassLoader(loader);
			Class.forName(CLEANUP_CLASS, true, loader).getMethod("deregister").invoke(null);
		}
		finally {
			Thread.currentThread().setContextClassLoader(previous);
		}
	}

	private static final class DriverClassLoader extends URLClassLoader {
		private final ClassLoader applicationLoader;

		DriverClassLoader(URL jar, ClassLoader applicationLoader) {
			super(new URL[] {jar}, ClassLoader.getPlatformClassLoader());
			this.applicationLoader = applicationLoader;
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if (name.startsWith("org.slf4j.")) {
				return applicationLoader.loadClass(name);
			}
			if (!name.equals(CLEANUP_CLASS)) {
				return super.loadClass(name, resolve);
			}
			synchronized (getClassLoadingLock(name)) {
				Class<?> loaded = findLoadedClass(name);
				if (loaded == null) {
					try (InputStream input = applicationLoader.getResourceAsStream(name.replace('.', '/') + ".class")) {
						if (input == null) throw new ClassNotFoundException(name);
						byte[] bytes = input.readAllBytes();
						loaded = defineClass(name, bytes, 0, bytes.length);
					}
					catch (IOException ex) {
						throw new ClassNotFoundException(name);
					}
				}
				if (resolve) resolveClass(loaded);
				return loaded;
			}
		}
	}
}

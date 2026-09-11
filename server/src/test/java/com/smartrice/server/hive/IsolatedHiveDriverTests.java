package com.smartrice.server.hive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.springframework.test.util.ReflectionTestUtils;

class IsolatedHiveDriverTests {

	@Test
	void officialDriverAndCoreDependenciesLoadOutsideApplicationClasspathAndTemporaryJarIsRemoved() throws Exception {
		Path extracted;
		ClassLoader previous = Thread.currentThread().getContextClassLoader();
		try (IsolatedHiveDriver isolated = new IsolatedHiveDriver()) {
			extracted = (Path) ReflectionTestUtils.getField(isolated, "extractedJar");
			Driver driver = (Driver) ReflectionTestUtils.getField(isolated, "driver");
			ClassLoader loader = driver.getClass().getClassLoader();
			assertThat(Files.exists(extracted)).isTrue();
			assertThat(loader).isNotSameAs(getClass().getClassLoader());
			assertThat(loader.getParent()).isSameAs(ClassLoader.getPlatformClassLoader());
			assertThat(loader.loadClass("org.slf4j.Logger")).isSameAs(Logger.class);
			for (String dependency : new String[] {
				"org.apache.hive.org.apache.thrift.protocol.TProtocol",
				"org.apache.hive.org.apache.http.client.HttpClient",
				"org.apache.hadoop.security.UserGroupInformation",
				"org.apache.hive.service.rpc.thrift.TCLIService"
			}) {
				assertThat(loader.loadClass(dependency).getClassLoader()).isSameAs(loader);
			}
			assertThatThrownBy(() -> Class.forName("org.apache.hive.jdbc.HiveDriver", false, getClass().getClassLoader()))
				.isInstanceOf(ClassNotFoundException.class);
			assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(previous);
		}
		assertThat(Files.exists(extracted)).isFalse();
		assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(previous);
	}

	@Test
	void jdbcCallsAndNestedResultSetsUseIsolatedContextAndRestoreItAfterEachCall() throws Exception {
		ClassLoader previous = Thread.currentThread().getContextClassLoader();
		ClassLoader isolated = new ClassLoader(ClassLoader.getPlatformClassLoader()) { };
		Connection delegate = mock(Connection.class);
		Statement statement = mock(Statement.class);
		ResultSet result = mock(ResultSet.class);
		when(delegate.createStatement()).thenAnswer(call -> {
			assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(isolated);
			return statement;
		});
		when(statement.executeQuery("SELECT fixture")).thenAnswer(call -> {
			assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(isolated);
			return result;
		});
		when(result.getDouble(1)).thenAnswer(call -> {
			assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(isolated);
			return 12.5;
		});
		Connection connection = (Connection) IsolatedHiveDriver.wrapJdbc(delegate, isolated);
		Statement wrappedStatement = connection.createStatement();
		assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(previous);
		ResultSet wrappedResult = wrappedStatement.executeQuery("SELECT fixture");
		assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(previous);
		assertThat(wrappedResult.getDouble(1)).isEqualTo(12.5);
		assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(previous);
		assertThat(connection).isEqualTo(connection);
	}

	@Test
	void jdbcFailureRestoresContextAndPreservesTheSqlExceptionType() throws Exception {
		ClassLoader previous = Thread.currentThread().getContextClassLoader();
		ClassLoader isolated = new ClassLoader(ClassLoader.getPlatformClassLoader()) { };
		ResultSet delegate = mock(ResultSet.class);
		when(delegate.next()).thenThrow(new SQLException("fixture failure"));
		ResultSet result = (ResultSet) IsolatedHiveDriver.wrapJdbc(delegate, isolated);
		assertThatThrownBy(result::next).isInstanceOf(SQLException.class).hasMessage("fixture failure");
		assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(previous);
	}
}

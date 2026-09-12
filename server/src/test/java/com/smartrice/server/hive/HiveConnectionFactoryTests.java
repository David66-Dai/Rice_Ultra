package com.smartrice.server.hive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class HiveConnectionFactoryTests {

	@Test
	void bindsSeparateHiveSettingsWithoutChangingMysqlProperties() {
		MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
			"app.hive.url", "jdbc:hive2://fixture.invalid:10000/farm",
			"app.hive.username", "fixture-user",
			"app.hive.password", "fixture-password",
			"app.hive.query-timeout-seconds", "12",
			"app.hive.socket-timeout-ms", "17000",
			"spring.datasource.url", "jdbc:mysql://mysql.invalid/rice"
		));
		HiveProperties properties = new Binder(source).bind("app.hive", Bindable.of(HiveProperties.class)).get();
		assertThat(properties.getUrl()).isEqualTo("jdbc:hive2://fixture.invalid:10000/farm");
		assertThat(properties.getUsername()).isEqualTo("fixture-user");
		assertThat(properties.getPassword()).isEqualTo("fixture-password");
		assertThat(properties.getQueryTimeoutSeconds()).isEqualTo(12);
		assertThat(properties.getSocketTimeoutMs()).isEqualTo(17000);
	}

	@Test
	void constructorDoesNotConnectAndMissingConfigurationFailsOnlyWhenOpened() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		try (HiveConnectionFactory factory = new HiveConnectionFactory(new HiveProperties(), (url, credentials) -> {
			assertThat(url).isNotNull();
			assertThat(credentials).isNotNull();
			calls.incrementAndGet();
			return null;
		})) {
			assertThat(calls.get()).isZero();
			assertThatThrownBy(factory::open).isInstanceOf(SQLException.class).hasNoCause();
			assertThat(calls.get()).isZero();
		}
	}

	@Test
	@SuppressWarnings("resource")
	void passesCredentialsSeparatelyAndLeavesConnectionLifetimeToCaller() throws Exception {
		HiveProperties properties = configured();
		Connection connection = mock(Connection.class);
		AtomicInteger calls = new AtomicInteger();
		try (HiveConnectionFactory factory = new HiveConnectionFactory(properties, (url, credentials) -> {
			calls.incrementAndGet();
			assertThat(url).isEqualTo("jdbc:hive2://fixture.invalid:10000/farm;socketTimeout=45000");
			assertThat(url).doesNotContain("fixture-user", "SECRET_SENTINEL");
			assertThat(credentials).containsExactlyInAnyOrderEntriesOf(Map.of("user", "fixture-user", "password", " SECRET_SENTINEL "));
			return connection;
		})) {
			assertThat(factory.queryTimeoutSeconds()).isEqualTo(30);
			assertThat(factory.open()).isSameAs(connection);
			assertThat(calls.get()).isEqualTo(1);
			verifyNoInteractions(connection);
		}
	}

	@Test
	void socketTimeoutIsReplacedInSessionSectionBeforeHiveConfigurationAndVariables() throws Exception {
		HiveProperties properties = configured();
		properties.setUrl("jdbc:hive2://fixture.invalid:10000/farm;auth=noSasl;socketTimeout=1?hive.query.name=fixture#fixture=value");
		try (HiveConnectionFactory factory = new HiveConnectionFactory(properties, (url, credentials) -> {
			assertThat(url).isEqualTo("jdbc:hive2://fixture.invalid:10000/farm;auth=noSasl;socketTimeout=45000?hive.query.name=fixture#fixture=value");
			assertThat(credentials).isNotNull();
			return mock(Connection.class);
		})) {
			try (Connection opened = factory.open()) {
				assertThat(opened).isNotNull();
			}
		}
	}

	@Test
	void rejectsEmbeddedMalformedCredentialAndStartupSqlUrlsWithoutOpening() throws Exception {
		for (String url : new String[] {
			"", "jdbc:mysql://fixture.invalid/farm", "jdbc:hive2://", "jdbc:hive2:///farm",
			"jdbc:hive2://fixture.invalid:10000", "jdbc:hive2://fixture.invalid:70000/farm",
			"jdbc:hive2://fixture.invalid:10000/farm;password=SECRET_SENTINEL",
			"jdbc:hive2://fixture.invalid:10000/farm;initFile=SECRET_SENTINEL.sql"
		}) {
			HiveProperties properties = configured();
			properties.setUrl(url);
			try (HiveConnectionFactory factory = new HiveConnectionFactory(properties, (ignoredUrl, credentials) -> {
				assertThat(ignoredUrl).isNotNull();
				assertThat(credentials).isNotNull();
				throw new AssertionError("Invalid configuration must not open a connection");
			})) {
				assertThatThrownBy(factory::open).isInstanceOf(SQLException.class)
					.hasMessageNotContaining("SECRET_SENTINEL").hasNoCause();
			}
		}
	}

	@Test
	void rejectsNonPositiveTimeoutsBeforeConnecting() throws Exception {
		HiveProperties properties = configured();
		try (HiveConnectionFactory factory = new HiveConnectionFactory(properties, (url, credentials) -> {
			assertThat(url).isNotNull();
			assertThat(credentials).isNotNull();
			throw new AssertionError("Invalid timeout must not open a connection");
		})) {
			properties.setQueryTimeoutSeconds(0);
			assertThatThrownBy(factory::queryTimeoutSeconds).isInstanceOf(SQLException.class);
			assertThatThrownBy(factory::open).isInstanceOf(SQLException.class);
			properties.setQueryTimeoutSeconds(30);
			properties.setSocketTimeoutMs(-1);
			assertThatThrownBy(factory::open).isInstanceOf(SQLException.class);
		}
	}

	@Test
	void driverErrorsNeverExposeCredentialValuesOrOriginalExceptionChain() throws Exception {
		try (HiveConnectionFactory factory = new HiveConnectionFactory(configured(), (url, credentials) -> {
			assertThat(url).isNotNull();
			assertThat(credentials).isNotNull();
			throw new SQLException("SECRET_SENTINEL", new IllegalStateException("NESTED_SECRET"));
		})) {
			assertThatThrownBy(factory::open).isInstanceOf(SQLException.class)
				.hasMessage("无法连接 Hive，请检查服务地址、认证配置及服务状态。")
				.hasMessageNotContaining("SECRET_SENTINEL").hasNoCause();
		}
	}

	private static HiveProperties configured() {
		HiveProperties properties = new HiveProperties();
		properties.setUrl("jdbc:hive2://fixture.invalid:10000/farm");
		properties.setUsername("fixture-user");
		properties.setPassword(" SECRET_SENTINEL ");
		return properties;
	}
}

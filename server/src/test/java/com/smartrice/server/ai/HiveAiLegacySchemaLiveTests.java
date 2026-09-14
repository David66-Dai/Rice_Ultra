package com.smartrice.server.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartrice.server.config.RiceConfiguration;
import com.smartrice.server.hive.HiveConnectionFactory;
import com.smartrice.server.hive.HiveProperties;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.context.properties.bind.Binder;

/** Explicit read-only schema inspection; no Spring context, MySQL or hardware is started. */
@EnabledIfEnvironmentVariable(named = "RICE_HIVE_AI_SCHEMA_LIVE_TEST", matches = "true")
class HiveAiLegacySchemaLiveTests {
	@Test
	void priorYearYieldRemainsAnExplicitReference() throws Exception {
		var environment = RiceConfiguration.loadEnvironment();
		HiveProperties properties = Binder.get(environment).bind("app.hive", HiveProperties.class)
			.orElseThrow(() -> new IllegalArgumentException("未配置 Hive"));
		try (var connections = new HiveConnectionFactory(properties)) {
			var repository = new HiveLegacyAiRepository(connections);
			var exactLimitations = new ArrayList<String>();
			var exact = repository.yieldBaseline("S01", "point_1", 2020, exactLimitations);
			assertThat(exact.baselineKgPerMu()).isEqualTo(474.377677);
			var recentLimitations = new ArrayList<String>();
			var recent = repository.yieldBaseline("S01", "point_1", 2026, recentLimitations);
			assertThat(recent.available()).isTrue();
			assertThat(recent.referenceYear()).isEqualTo(2025);
			assertThat(recent.matchType()).isEqualTo("latest_prior");
			assertThat(recent.baselineKgPerMu()).isEqualTo(465.474385);
			System.out.printf("Yield adapter verified: 2020 baseline %s; 2026 target uses year %d (%s).%n",
				exact.baselineKgPerMu(), recent.referenceYear(), recent.matchType());
		}
	}

	/**
	 * {@code pest_data} is still inspected here: it is the table the reserved archive source will
	 * read once the earlier-day pest and disease branch is wired up.
	 */
	@Test
	void inspectOnlyLegacyAgriculturalSchemasAndTwoSampleRows() throws Exception {
		var environment = RiceConfiguration.loadEnvironment();
		HiveProperties properties = Binder.get(environment).bind("app.hive", HiveProperties.class)
			.orElseThrow(() -> new IllegalArgumentException("未配置 Hive"));
		try (var connections = new HiveConnectionFactory(properties); var connection = connections.open()) {
			for (String table : List.of("pest_data", "rice_yield")) {
				try (var statement = connection.createStatement()) {
					statement.setQueryTimeout(30);
					try (var rows = statement.executeQuery("DESCRIBE " + table)) {
						List<String> fields = new ArrayList<>();
						while (rows.next()) fields.add(rows.getString(1) + ":" + rows.getString(2));
						System.out.println("Legacy Hive schema " + table + " " + String.join(", ", fields));
					}
					try (var rows = statement.executeQuery("SELECT * FROM " + table + " LIMIT 2")) {
						var metadata = rows.getMetaData();
						while (rows.next()) {
							List<String> values = new ArrayList<>();
							for (int column = 1; column <= metadata.getColumnCount(); column++) {
								values.add(metadata.getColumnLabel(column) + "=" + rows.getString(column));
							}
							System.out.println("Legacy Hive agricultural sample " + table + " " + String.join(", ", values));
						}
					}
				}
			}
		} catch (SQLException ex) {
			throw new SQLException("旧农业表只读检查失败，请检查 Hive 服务或表结构。", ex.getSQLState());
		}
	}
}

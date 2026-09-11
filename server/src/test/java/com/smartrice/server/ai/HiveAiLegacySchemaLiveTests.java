package com.smartrice.server.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartrice.server.config.RiceConfiguration;
import com.smartrice.server.hive.HiveConnectionFactory;
import com.smartrice.server.hive.HiveProperties;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.context.properties.bind.Binder;

/** Explicit read-only schema inspection; no Spring context, MySQL or hardware is started. */
@EnabledIfEnvironmentVariable(named = "RICE_HIVE_AI_SCHEMA_LIVE_TEST", matches = "true")
class HiveAiLegacySchemaLiveTests {
	@Test
	void nonPaddedDiseaseDatesAndPriorYearYieldRemainExplicitReferences() throws Exception {
		var environment = RiceConfiguration.loadEnvironment();
		HiveProperties properties = Binder.get(environment).bind("app.hive", HiveProperties.class)
			.orElseThrow(() -> new IllegalArgumentException("未配置 Hive"));
		try (var connections = new HiveConnectionFactory(properties)) {
			var repository = new HiveLegacyAiRepository(connections);
			var exact = repository.context("S01", "point_1", LocalDate.of(2020, 1, 1));
			assertThat(exact.disease().available()).describedAs("non-padded legacy date must remain readable").isTrue();
			assertThat(exact.disease().referenceDate()).isEqualTo(LocalDate.of(2020, 1, 1));
			assertThat(exact.disease().matchType()).isEqualTo("exact");
			assertThat(exact.yield().baselineKgPerMu()).isEqualTo(474.377677);
			var recent = repository.context("S01", "point_1", LocalDate.of(2026, 9, 11));
			assertThat(recent.yield().available()).isTrue();
			assertThat(recent.yield().referenceYear()).isEqualTo(2025);
			assertThat(recent.yield().matchType()).isEqualTo("latest_prior");
			assertThat(recent.yield().baselineKgPerMu()).isEqualTo(465.474385);
			assertThat(recent.disease().available()).isTrue();
			assertThat(recent.disease().referenceDate()).isBeforeOrEqualTo(LocalDate.of(2026, 9, 11));
			System.out.printf("Legacy adapter verified: exact day %s; 2026 target uses disease %s (%s), yield %d (%s).%n",
				exact.disease().referenceDate(), recent.disease().referenceDate(), recent.disease().matchType(),
				recent.yield().referenceYear(), recent.yield().matchType());
		}
	}

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

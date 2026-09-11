package com.smartrice.server.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartrice.server.hive.HiveConnectionFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HiveLegacyAiRepositoryTests {
	private static final LocalDate TARGET = LocalDate.of(2025, 5, 8);

	@Test
	void choosesExactDiseaseAndYearWithoutConvertingUnconfirmedDiseaseUnits() throws Exception {
		var fixture = new Fixture(List.of(
			Map.of("record_date", "2025-05-07", "bacterialleafblightrate", "0.1"),
			Map.of("record_date", "2025-05-08", "bacterialleafblightrate", "0.2", "pestrphnum", "22.0")),
			List.of("point", "2024firstcrop", "2025firstcrop", "2026firstcrop", "2025secondcrop"),
			List.of(Map.of("2025firstcrop", 465.4743845)));
		var result = fixture.repository.context("S01", "point_1", TARGET);
		assertThat(result.source()).isEqualTo("hive_legacy");
		assertThat(result.disease().available()).isTrue();
		assertThat(result.disease().referenceDate()).isEqualTo(TARGET);
		assertThat(result.disease().matchType()).isEqualTo("exact");
		var rate = result.disease().values().stream().filter(value -> value.field().equals("bacterialleafblightrate")).findFirst().orElseThrow();
		assertThat(rate.value()).isEqualTo("0.2");
		assertThat(rate.unit()).isEmpty();
		assertThat(result.yield().baselineKgPerMu()).isEqualTo(465.474385);
		assertThat(result.yield().referenceYear()).isEqualTo(2025);
		assertThat(result.yield().matchType()).isEqualTo("exact_year");
		assertThat(fixture.yieldSql).doesNotContain("2026", "secondcrop");
		assertThat(HiveLegacyAiRepository.DISEASE_SQL).doesNotContainIgnoringCase("group by")
			.doesNotContainIgnoringCase("order by");
		verify(fixture.disease).setString(1, "point_1");
		verify(fixture.disease).setString(2, TARGET.toString());
		verify(fixture.yield).setString(1, "point_1");
		verify(fixture.connection).close();
		assertThat(result.limitations()).anyMatch(text -> text.contains("不添加 %"));
	}

	@Test
	void fallsBackOnlyToPriorReferencesAndMarksPredictedBaseline() throws Exception {
		var fixture = new Fixture(List.of(
			Map.of("record_date", "2024-12-31", "pestrphnum", "14"),
			Map.of("record_date", "2024-11-01", "pestrphnum", "20")),
			List.of("2025firstcrop", "2024firstcrop_pred", "2026firstcrop"),
			List.of(Map.of("2025firstcrop", Double.NaN, "2024firstcrop_pred", 470.0)));
		var result = fixture.repository.context("S01", "point_1", TARGET);
		assertThat(result.disease().referenceDate()).isEqualTo(LocalDate.of(2024, 12, 31));
		assertThat(result.disease().matchType()).isEqualTo("latest_prior");
		assertThat(result.yield().referenceYear()).isEqualTo(2024);
		assertThat(result.yield().matchType()).isEqualTo("latest_prior");
		assertThat(result.yield().baselineKgPerMu()).isEqualTo(470.0);
		assertThat(result.limitations()).anyMatch(text -> text.contains("2024firstcrop_pred") && text.contains("预测基线"));
	}

	@Test
	void reportsMissingOnlyAfterQueryingAndDoesNotInventDemoData() throws Exception {
		var fixture = new Fixture(List.of(), List.of("point", "2025firstcrop"), List.of());
		var result = fixture.repository.context("S01", "point_1", TARGET);
		assertThat(result.disease().available()).isFalse();
		assertThat(result.disease().values()).isEmpty();
		assertThat(result.yield().available()).isFalse();
		assertThat(result.yield().baselineKgPerMu()).isNull();
		verify(fixture.disease).executeQuery();
		verify(fixture.schema).executeQuery();
		verify(fixture.yield).executeQuery();
		assertThat(result.limitations()).anyMatch(text -> text.contains("没有该站点"));
	}

	@Test
	void failingOneTableDoesNotDiscardTheOtherAndNeverLeaksDiagnostics() throws Exception {
		var fixture = new Fixture(List.of(), List.of("2025firstcrop"), List.of(Map.of("2025firstcrop", 400.0)));
		when(fixture.disease.executeQuery()).thenThrow(new SQLException("password=fixture-secret"));
		var result = fixture.repository.context("S01", "point_1", TARGET);
		assertThat(result.disease().available()).isFalse();
		assertThat(result.yield().available()).isTrue();
		assertThat(result.limitations()).noneMatch(text -> text.contains("fixture-secret") || text.contains("password"));
		assertThat(result.limitations()).anyMatch(text -> text.contains("病虫害表暂不可用"));
		verify(fixture.disease).close();
		verify(fixture.connection).close();
	}

	@Test
	void rejectsConflictingDiseaseDaysAndDuplicateYieldRows() throws Exception {
		var fixture = new Fixture(List.of(
			Map.of("record_date", TARGET.toString(), "pestrphnum", "14"),
			Map.of("record_date", TARGET.toString(), "pestrphnum", "20")), List.of("2025firstcrop"),
			List.of(Map.of("2025firstcrop", 400.0), Map.of("2025firstcrop", 401.0)));
		var result = fixture.repository.context("S01", "point_1", TARGET);
		assertThat(result.disease().available()).isFalse();
		assertThat(result.yield().available()).isFalse();
		assertThat(result.limitations()).anyMatch(text -> text.contains("冲突"));
		assertThat(result.limitations()).anyMatch(text -> text.contains("多条记录"));
	}

	@Test
	void unavailableConnectionReturnsExplicitLimitationsAndInvalidInputsDoNotQuery() throws Exception {
		HiveConnectionFactory connections = mock(HiveConnectionFactory.class);
		var repository = new HiveLegacyAiRepository(connections);
		assertThatThrownBy(() -> repository.context("S01", "point_2", TARGET)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> repository.context("S01'", "point_1", TARGET)).isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(connections);
		when(connections.open()).thenThrow(new SQLException("private fixture"));
		var result = repository.context("S01", "point_1", TARGET);
		assertThat(result.disease().available()).isFalse();
		assertThat(result.yield().available()).isFalse();
		assertThat(result.limitations()).anyMatch(text -> text.contains("暂不可用"));
		assertThat(result.limitations()).noneMatch(text -> text.contains("private fixture"));
	}

	private static ResultSet rows(List<Map<String, Object>> data) throws Exception {
		ResultSet result = mock(ResultSet.class);
		var index = new AtomicInteger(-1);
		var wasNull = new AtomicBoolean();
		when(result.next()).thenAnswer(call -> index.incrementAndGet() < data.size());
		when(result.getString(anyString())).thenAnswer(call -> {
			Object value = data.get(index.get()).get(call.getArgument(0, String.class));
			return value == null ? null : value.toString();
		});
		when(result.getString(anyInt())).thenAnswer(call -> data.get(index.get()).get("column1"));
		when(result.getDouble(anyString())).thenAnswer(call -> {
			Object value = data.get(index.get()).get(call.getArgument(0, String.class));
			wasNull.set(value == null);
			return value == null ? 0.0 : ((Number) value).doubleValue();
		});
		when(result.wasNull()).thenAnswer(call -> wasNull.get());
		return result;
	}

	private static final class Fixture {
		final HiveConnectionFactory connections = mock(HiveConnectionFactory.class);
		final Connection connection = mock(Connection.class);
		final PreparedStatement disease = mock(PreparedStatement.class);
		final PreparedStatement schema = mock(PreparedStatement.class);
		final PreparedStatement yield = mock(PreparedStatement.class);
		final HiveLegacyAiRepository repository = new HiveLegacyAiRepository(connections);
		String yieldSql;

		Fixture(List<Map<String, Object>> diseases, List<String> columns, List<Map<String, Object>> yields) throws Exception {
			when(connections.open()).thenReturn(connection);
			when(connections.queryTimeoutSeconds()).thenReturn(30);
			when(connection.prepareStatement(anyString())).thenAnswer(call -> {
				String sql = call.getArgument(0, String.class);
				if (sql.equals(HiveLegacyAiRepository.DISEASE_SQL)) return disease;
				if (sql.equals(HiveLegacyAiRepository.YIELD_SCHEMA_SQL)) return schema;
				yieldSql = sql;
				return yield;
			});
			ResultSet diseaseRows = rows(diseases);
			ResultSet schemaRows = rows(columns.stream().map(name -> Map.<String, Object>of("column1", name)).toList());
			ResultSet yieldRows = rows(yields);
			when(disease.executeQuery()).thenReturn(diseaseRows);
			when(schema.executeQuery()).thenReturn(schemaRows);
			when(yield.executeQuery()).thenReturn(yieldRows);
		}
	}
}

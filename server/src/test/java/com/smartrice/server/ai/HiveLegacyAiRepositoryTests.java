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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Only the yield baseline lives here now; pest and disease figures moved to the inspection records. */
class HiveLegacyAiRepositoryTests {
	private static final int TARGET_YEAR = 2025;

	@Test
	void selectsTheTargetYearBaselineAndIgnoresLaterYearsAndOtherSeasons() throws Exception {
		var fixture = new Fixture(
			List.of("point", "2024firstcrop", "2025firstcrop", "2026firstcrop", "2025secondcrop"),
			List.of(Map.of("2025firstcrop", 465.4743845)));
		var limitations = new ArrayList<String>();
		var result = fixture.repository.yieldBaseline("S01", "point_1", TARGET_YEAR, limitations);
		assertThat(result.available()).isTrue();
		assertThat(result.baselineKgPerMu()).isEqualTo(465.474385);
		assertThat(result.referenceYear()).isEqualTo(2025);
		assertThat(result.matchType()).isEqualTo("exact_year");
		assertThat(result.season()).isEqualTo("firstcrop");
		assertThat(fixture.yieldSql).doesNotContain("2026", "secondcrop");
		verify(fixture.yield).setString(1, "point_1");
		verify(fixture.connection).close();
	}

	@Test
	void fallsBackOnlyToPriorYearsAndMarksAPredictedBaseline() throws Exception {
		var fixture = new Fixture(List.of("2025firstcrop", "2024firstcrop_pred", "2026firstcrop"),
			List.of(Map.of("2025firstcrop", Double.NaN, "2024firstcrop_pred", 470.0)));
		var limitations = new ArrayList<String>();
		var result = fixture.repository.yieldBaseline("S01", "point_1", TARGET_YEAR, limitations);
		assertThat(result.referenceYear()).isEqualTo(2024);
		assertThat(result.matchType()).isEqualTo("latest_prior");
		assertThat(result.baselineKgPerMu()).isEqualTo(470.0);
		assertThat(limitations).anyMatch(text -> text.contains("2024firstcrop_pred") && text.contains("预测基线"));
	}

	@Test
	void reportsMissingOnlyAfterQueryingAndDoesNotInventDemoData() throws Exception {
		var fixture = new Fixture(List.of("point", "2025firstcrop"), List.of());
		var limitations = new ArrayList<String>();
		var result = fixture.repository.yieldBaseline("S01", "point_1", TARGET_YEAR, limitations);
		assertThat(result.available()).isFalse();
		assertThat(result.baselineKgPerMu()).isNull();
		verify(fixture.schema).executeQuery();
		verify(fixture.yield).executeQuery();
		assertThat(limitations).anyMatch(text -> text.contains("没有该站点"));
	}

	@Test
	void refusesToPickABaselineWhenTheStationHasDuplicateRows() throws Exception {
		var fixture = new Fixture(List.of("2025firstcrop"),
			List.of(Map.of("2025firstcrop", 400.0), Map.of("2025firstcrop", 401.0)));
		var limitations = new ArrayList<String>();
		var result = fixture.repository.yieldBaseline("S01", "point_1", TARGET_YEAR, limitations);
		assertThat(result.available()).isFalse();
		assertThat(limitations).anyMatch(text -> text.contains("多条记录"));
	}

	@Test
	void unavailableConnectionReturnsExplicitLimitationsAndInvalidInputsDoNotQuery() throws Exception {
		HiveConnectionFactory connections = mock(HiveConnectionFactory.class);
		var repository = new HiveLegacyAiRepository(connections);
		var limitations = new ArrayList<String>();
		assertThatThrownBy(() -> repository.yieldBaseline("S01", "point_2", TARGET_YEAR, limitations))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> repository.yieldBaseline("S01'", "point_1", TARGET_YEAR, limitations))
			.isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(connections);
		when(connections.open()).thenThrow(new SQLException("password=private fixture"));
		var result = repository.yieldBaseline("S01", "point_1", TARGET_YEAR, limitations);
		assertThat(result.available()).isFalse();
		assertThat(limitations).anyMatch(text -> text.contains("暂不可用"));
		assertThat(limitations).noneMatch(text -> text.contains("private fixture") || text.contains("password"));
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
		final PreparedStatement schema = mock(PreparedStatement.class);
		final PreparedStatement yield = mock(PreparedStatement.class);
		final HiveLegacyAiRepository repository = new HiveLegacyAiRepository(connections);
		String yieldSql;

		Fixture(List<String> columns, List<Map<String, Object>> yields) throws Exception {
			when(connections.open()).thenReturn(connection);
			when(connections.queryTimeoutSeconds()).thenReturn(30);
			when(connection.prepareStatement(anyString())).thenAnswer(call -> {
				String sql = call.getArgument(0, String.class);
				if (sql.equals(HiveLegacyAiRepository.YIELD_SCHEMA_SQL)) return schema;
				yieldSql = sql;
				return yield;
			});
			ResultSet schemaRows = rows(columns.stream().map(name -> Map.<String, Object>of("column1", name)).toList());
			ResultSet yieldRows = rows(yields);
			when(schema.executeQuery()).thenReturn(schemaRows);
			when(yield.executeQuery()).thenReturn(yieldRows);
		}
	}
}

package com.smartrice.server.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

class HiveAiRepositoryTests {
	private static final LocalDate END = LocalDate.of(2020, 1, 7);

	@Test
	void averagesDuplicateDaysPreservesUnitsAndNullsAndClosesAllResources() throws Exception {
		var fixture = new Fixture(List.of("2020-01-07", "2020-01-01", "2020-01-07"), List.of(
			Map.of("light_lux", 100.0, "wind_speed_m_s", 0.0, "electrical_conductivity_ds_m", 1.14),
			Map.of("light_lux", 50.0, "ph", 6.2),
			Map.of("light_lux", 300.0, "wind_speed_m_s", 0.0, "nitrogen_concentration_ppm", 118.0,
				"phosphorus_concentration_ppm", Double.NaN, "potassium_concentration_ppm", Double.POSITIVE_INFINITY)));
		var result = fixture.repository.window("point_10", END.minusDays(6), END);
		assertThat(result.rawRowCount()).isEqualTo(3);
		assertThat(result.daily()).hasSize(2);
		var target = result.daily().get(END);
		assertThat(target).hasSize(11);
		assertThat(target.get("light_lux")).isEqualTo(200.0);
		assertThat(target.get("wind_speed_m_s")).isZero();
		assertThat(target.get("electrical_conductivity_ds_m")).isEqualTo(1.14);
		assertThat(target.get("nitrogen_concentration_ppm")).isEqualTo(118.0);
		assertThat(target.get("phosphorus_concentration_ppm")).isNull();
		assertThat(target.get("potassium_concentration_ppm")).isNull();
		assertThat(HiveAiRepository.WINDOW_SQL).doesNotContainIgnoringCase("group by")
			.doesNotContainIgnoringCase("avg(").contains("LIMIT 100000")
			.contains("farm.env_daily").contains("growth_stage")
			// The partition column stays bare so Hive can prune instead of scanning every day.
			.doesNotContainIgnoringCase("to_date(").doesNotContainIgnoringCase("cast(");
		verify(fixture.statement).setString(1, "point_10");
		verify(fixture.statement).setString(2, "2020-01-01");
		verify(fixture.statement).setString(3, "2020-01-07");
		verify(fixture.statement).setQueryTimeout(30);
		verify(fixture.rows).close();
		verify(fixture.statement).close();
		verify(fixture.connection).close();
	}

	@Test
	void roundsRepeatingFractionsAndDoesNotOverflowFiniteAverages() throws Exception {
		var fixture = new Fixture(List.of("2020-01-07", "2020-01-07", "2020-01-07"), List.of(
			Map.of("light_lux", Double.MAX_VALUE, "wind_speed_m_s", 1.0),
			Map.of("light_lux", Double.MAX_VALUE, "wind_speed_m_s", 0.0),
			Map.of("wind_speed_m_s", 0.0)));
		var target = fixture.repository.window("point_1", END.minusDays(6), END).daily().get(END);
		assertThat(target.get("wind_speed_m_s")).isEqualTo(0.333333);
		assertThat(target.get("light_lux")).isEqualTo(Double.MAX_VALUE);
	}

	@Test
	void rejectsInvalidWindowsAndStationBeforeOpeningConnection() {
		HiveConnectionFactory connections = mock(HiveConnectionFactory.class);
		var repository = new HiveAiRepository(connections);
		assertThatThrownBy(() -> repository.window("point_1' OR 1=1", END.minusDays(6), END))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> repository.window("point_1", END.minusDays(30), END))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> repository.window("point_1", END, END.minusDays(1)))
			.isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(connections);
	}

	@Test
	void refusesInvalidReturnedDatesAndClosesResourcesOnSqlFailure() throws Exception {
		var fixture = new Fixture(List.of("malformed"), List.of(Map.of("light_lux", 1.0)));
		assertThatThrownBy(() -> fixture.repository.window("point_1", END.minusDays(6), END))
			.isInstanceOf(SQLException.class).hasMessageNotContaining("malformed");
		verify(fixture.rows).close();
		verify(fixture.connection).close();
		var failed = new Fixture(List.of(), List.of());
		when(failed.statement.executeQuery()).thenThrow(new SQLException("private fixture diagnostic"));
		assertThatThrownBy(() -> failed.repository.window("point_1", END.minusDays(6), END))
			.isInstanceOf(SQLException.class);
		verify(failed.statement).close();
		verify(failed.connection).close();
	}

	@Test
	void hittingRowLimitFailsRatherThanReturningAClippedSample() throws Exception {
		var fixture = new Fixture(List.of(), List.of());
		var closed = new AtomicBoolean();
		// Avoid retaining millions of Mockito invocations while exercising the real production bound.
		ResultSet manyRows = (ResultSet) java.lang.reflect.Proxy.newProxyInstance(
			ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
				case "next", "wasNull" -> true;
				case "getString" -> END.toString();
				case "getDouble" -> 0.0;
				case "close" -> { closed.set(true); yield null; }
				default -> throw new UnsupportedOperationException(method.getName());
			});
		when(fixture.statement.executeQuery()).thenReturn(manyRows);
		assertThatThrownBy(() -> fixture.repository.window("point_1", END.minusDays(6), END))
			.isInstanceOf(HiveAiRepository.RowLimitException.class);
		assertThat(closed).isTrue();
		verify(fixture.connection).close();
	}

	static final class Fixture {
		final HiveConnectionFactory connections = mock(HiveConnectionFactory.class);
		final Connection connection = mock(Connection.class);
		final PreparedStatement statement = mock(PreparedStatement.class);
		final ResultSet rows = mock(ResultSet.class);
		final HiveAiRepository repository = new HiveAiRepository(connections);

		Fixture(List<String> dates, List<Map<String, Double>> samples) throws Exception {
			this(dates, samples, List.of());
		}

		/** {@code stages} lines up with {@code dates}; a shorter list leaves the rest without a stage. */
		Fixture(List<String> dates, List<Map<String, Double>> samples, List<String> stages) throws Exception {
			when(connections.open()).thenReturn(connection);
			when(connections.queryTimeoutSeconds()).thenReturn(30);
			when(connection.prepareStatement(HiveAiRepository.WINDOW_SQL)).thenReturn(statement);
			when(statement.executeQuery()).thenReturn(rows);
			var index = new AtomicInteger(-1);
			var wasNull = new AtomicBoolean();
			when(rows.next()).thenAnswer(call -> index.incrementAndGet() < dates.size());
			when(rows.getString("record_date")).thenAnswer(call -> dates.get(index.get()));
			when(rows.getString(HiveAiRepository.GROWTH_STAGE))
				.thenAnswer(call -> index.get() < stages.size() ? stages.get(index.get()) : null);
			when(rows.getDouble(anyString())).thenAnswer(call -> {
				Double value = samples.get(index.get()).get(call.getArgument(0, String.class));
				wasNull.set(value == null);
				return value == null ? 0.0 : value;
			});
			when(rows.wasNull()).thenAnswer(call -> wasNull.get());
		}
	}
}

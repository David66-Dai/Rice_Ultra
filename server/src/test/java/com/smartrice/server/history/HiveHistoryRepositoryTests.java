package com.smartrice.server.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartrice.server.hive.HiveConnectionFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class HiveHistoryRepositoryTests {

	@Test
	void mapsConfirmedSampleAndPreservesMissingValuesInsteadOfInventingZeros() throws Exception {
		ResultSet rows = mock(ResultSet.class);
		Map<String, Double> values = Map.of("light_lux", 161.1, "temperature_celsius", 4.9,
			"humidity_percent", 44.2, "wind_speed_m_s", 0.0, "soil_temperature_celsius", 7.1,
			"soil_moisture_percent", 29.1, "ph", 6.2, "electrical_conductivity_ds_m", 1.14,
			"nitrogen_concentration_ppm", 118.0, "phosphorus_concentration_ppm", Double.NaN);
		AtomicBoolean wasNull = new AtomicBoolean();
		when(rows.getDouble(anyString())).thenAnswer(call -> {
			Double value = values.get(call.getArgument(0, String.class));
			wasNull.set(value == null);
			return value == null ? 0.0 : value;
		});
		when(rows.wasNull()).thenAnswer(call -> wasNull.get());
		var result = HiveHistoryRepository.toDayData(rows, "S01", LocalDate.of(2020, 1, 1));
		assertThat(result.environment().lightKlx()).isEqualTo(0.1611);
		assertThat(result.environment().windSpeedMs()).isZero();
		assertThat(result.environment().soilTemperatureC()).isEqualTo(7.1);
		assertThat(result.environment().soilMoisturePercent()).isEqualTo(29.1);
		assertThat(result.environment().soilEcMsCm()).isEqualTo(1.14);
		assertThat(result.environment().soilNitrogenPpm()).isEqualTo(118.0);
		assertThat(result.environment().soilPhosphorusPpm()).isNull();
		assertThat(result.environment().soilPotassiumPpm()).isNull();
		assertThat(result.environment().rainfallMmH()).isNull();
		assertThat(result.pestDisease()).isNull();
		assertThat(result.spectrum()).isNull();
	}

	@Test
	void rangeCountsActualDaysAndBindsStationAndDateThenClosesResources() throws Exception {
		HiveConnectionFactory connections = mock(HiveConnectionFactory.class);
		Connection connection = mock(Connection.class);
		PreparedStatement statement = mock(PreparedStatement.class);
		ResultSet rows = mock(ResultSet.class);
		when(connections.open()).thenReturn(connection);
		when(connections.queryTimeoutSeconds()).thenReturn(30);
		when(connection.prepareStatement(HiveHistoryRepository.RANGE_SQL)).thenReturn(statement);
		when(statement.executeQuery()).thenReturn(rows);
		when(rows.next()).thenReturn(true, true, true, false);
		when(rows.getString("record_date")).thenReturn("2020-01-02", "2020-01-01", "2020-01-01");
		var result = new HiveHistoryRepository(connections).range("S01", LocalDate.of(2026, 9, 11)).orElseThrow();
		assertThat(result.recordCount()).isEqualTo(2);
		verify(statement).setString(1, "point_1");
		verify(statement).setString(2, "2026-09-11");
		verify(statement).setQueryTimeout(30);
		verify(rows).close();
		verify(statement).close();
		verify(connection).close();
	}

	@Test
	void dailyMeanUsesOnlyPresentFiniteSamplesAndKeepsRealZero() throws Exception {
		HiveConnectionFactory connections = mock(HiveConnectionFactory.class);
		Connection connection = mock(Connection.class);
		PreparedStatement statement = mock(PreparedStatement.class);
		ResultSet rows = mock(ResultSet.class);
		when(connections.open()).thenReturn(connection);
		when(connections.queryTimeoutSeconds()).thenReturn(30);
		when(connection.prepareStatement(HiveHistoryRepository.DAILY_SQL)).thenReturn(statement);
		when(statement.executeQuery()).thenReturn(rows);
		var samples = java.util.List.of(
			Map.of("light_lux", 100.0, "wind_speed_m_s", 0.0, "nitrogen_concentration_ppm", 100.0),
			Map.of("light_lux", 300.0, "wind_speed_m_s", 0.0, "phosphorus_concentration_ppm", Double.NaN));
		var index = new java.util.concurrent.atomic.AtomicInteger(-1);
		AtomicBoolean wasNull = new AtomicBoolean();
		when(rows.next()).thenAnswer(call -> index.incrementAndGet() < samples.size());
		when(rows.getString("record_date")).thenReturn("2020-01-01");
		when(rows.getDouble(anyString())).thenAnswer(call -> {
			Double value = samples.get(index.get()).get(call.getArgument(0, String.class));
			wasNull.set(value == null);
			return value == null ? 0.0 : value;
		});
		when(rows.wasNull()).thenAnswer(call -> wasNull.get());
		var day = new HiveHistoryRepository(connections).days("S01", LocalDate.of(2020, 1, 1))
			.get(LocalDate.of(2020, 1, 1));
		assertThat(day.environment().lightKlx()).isEqualTo(0.2);
		assertThat(day.environment().windSpeedMs()).isZero();
		assertThat(day.environment().soilNitrogenPpm()).isEqualTo(100.0);
		assertThat(day.environment().soilPhosphorusPpm()).isNull();
		verify(connection).close();
	}

	@Test
	void failedDailyQueryStillClosesSessionAndRejectsInjectedStationIdentifiers() throws Exception {
		HiveConnectionFactory connections = mock(HiveConnectionFactory.class);
		Connection connection = mock(Connection.class);
		PreparedStatement statement = mock(PreparedStatement.class);
		when(connections.open()).thenReturn(connection);
		when(connections.queryTimeoutSeconds()).thenReturn(30);
		when(connection.prepareStatement(HiveHistoryRepository.DAILY_SQL)).thenReturn(statement);
		when(statement.executeQuery()).thenThrow(new SQLException("fixture failure"));
		assertThatThrownBy(() -> new HiveHistoryRepository(connections).days("S10", LocalDate.of(2020, 1, 2)))
			.isInstanceOf(SQLException.class);
		verify(statement).setString(1, "point_10");
		verify(statement).setString(2, "2020-01-02");
		verify(statement).setString(3, "2020-01-01");
		verify(statement).close();
		verify(connection).close();
		assertThatThrownBy(() -> HiveHistoryRepository.toHiveStation("S01' OR 1=1"))
			.isInstanceOf(IllegalArgumentException.class);
	}
}

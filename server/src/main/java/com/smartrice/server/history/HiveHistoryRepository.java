package com.smartrice.server.history;

import com.smartrice.server.hive.HiveConnectionFactory;
import com.smartrice.server.history.HistoryDailyResponse.EnvironmentAverages;
import com.smartrice.server.history.HistoryDailyResponse.HistoryDayData;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.DoubleSummaryStatistics;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.springframework.stereotype.Repository;

/** Read-only queries against the confirmed Hive schema. No table/column names come from clients. */
@Repository
public class HiveHistoryRepository {

	private static final String TABLE = "agri_env_data";
	private static final String STATION_FILTER = "trim(`station`) = ?";
	private static final String DAY = "to_date(trim(`date`))";
	// Projection/filter queries avoid launching a cluster aggregation for each UI interaction.
	static final String RANGE_SQL = "SELECT " + DAY + " AS record_date FROM " + TABLE
		+ " WHERE " + STATION_FILTER + " AND " + DAY + " <= CAST(? AS DATE)";
	private static final List<String> METRICS = List.of("light_lux", "temperature_celsius", "humidity_percent",
		"wind_speed_m_s", "soil_temperature_celsius", "soil_moisture_percent", "ph",
		"electrical_conductivity_ds_m", "nitrogen_concentration_ppm", "phosphorus_concentration_ppm",
		"potassium_concentration_ppm");
	static final String DAILY_SQL = "SELECT " + DAY + " AS record_date, " + String.join(", ", METRICS)
		+ " FROM " + TABLE + " WHERE " + STATION_FILTER
		+ " AND " + DAY + " IN (CAST(? AS DATE), CAST(? AS DATE))";

	private final HiveConnectionFactory connections;

	public HiveHistoryRepository(HiveConnectionFactory connections) {
		this.connections = connections;
	}

	public Optional<HistoryRangeResponse> range(String stationId, LocalDate today) throws SQLException {
		try (Connection connection = connections.open();
				PreparedStatement statement = connection.prepareStatement(RANGE_SQL)) {
			configure(statement, stationId);
			statement.setString(2, today.toString());
			try (ResultSet rows = statement.executeQuery()) {
				TreeSet<LocalDate> days = new TreeSet<>();
				while (rows.next()) days.add(readDate(rows, "record_date"));
				if (days.isEmpty()) return Optional.empty();
				return Optional.of(new HistoryRangeResponse(stationId, days.first(), days.last(), days.size()));
			}
		}
	}

	public Map<LocalDate, HistoryDayData> days(String stationId, LocalDate date) throws SQLException {
		try (Connection connection = connections.open();
				PreparedStatement statement = connection.prepareStatement(DAILY_SQL)) {
			configure(statement, stationId);
			statement.setString(2, date.toString());
			statement.setString(3, date.minusDays(1).toString());
			try (ResultSet rows = statement.executeQuery()) {
				Map<LocalDate, Map<String, DoubleSummaryStatistics>> totals = new LinkedHashMap<>();
				while (rows.next()) {
					LocalDate recordDate = readDate(rows, "record_date");
					Map<String, DoubleSummaryStatistics> metrics = totals.computeIfAbsent(recordDate, key -> new LinkedHashMap<>());
					for (String field : METRICS) {
						Double value = number(rows, field);
						if (value != null) metrics.computeIfAbsent(field, key -> new DoubleSummaryStatistics()).accept(value);
					}
				}
				Map<LocalDate, HistoryDayData> result = new LinkedHashMap<>();
				for (var day : totals.entrySet()) {
					result.put(day.getKey(), buildDayData(field -> {
						DoubleSummaryStatistics summary = day.getValue().get(field);
						return summary == null || !Double.isFinite(summary.getAverage()) ? null : summary.getAverage();
					}, stationId, day.getKey()));
				}
				return result;
			}
		}
	}

	private void configure(PreparedStatement statement, String stationId) throws SQLException {
		statement.setQueryTimeout(connections.queryTimeoutSeconds());
		statement.setFetchSize(1000);
		statement.setString(1, toHiveStation(stationId));
	}

	static String toHiveStation(String stationId) {
		if (stationId == null || !stationId.matches("S(?:0[1-9]|10)")) {
			throw new IllegalArgumentException("Invalid station identifier");
		}
		int number = Integer.parseInt(stationId.substring(1));
		return "point_" + number;
	}

	private static LocalDate readDate(ResultSet rows, String field) throws SQLException {
		String value = rows.getString(field);
		try {
			return LocalDate.parse(value);
		} catch (RuntimeException ex) {
			throw new SQLException("Hive returned an invalid historical date", "22007");
		}
	}

	private static Double number(ResultSet rows, String field) throws SQLException {
		double value = rows.getDouble(field);
		return rows.wasNull() || !Double.isFinite(value) ? null : value;
	}

	static HistoryDayData toDayData(ResultSet rows, String stationId, LocalDate date) throws SQLException {
		return buildDayData(field -> number(rows, field), stationId, date);
	}

	@FunctionalInterface
	private interface NumericFields {
		Double read(String field) throws SQLException;
	}

	private static HistoryDayData buildDayData(NumericFields values, String stationId, LocalDate date) throws SQLException {
		Double lightLux = values.read("light_lux");
		return new HistoryDayData(date, stationId, new EnvironmentAverages(
			lightLux == null ? null : lightLux / 1000.0,
			values.read("wind_speed_m_s"),
			null, // This table contains no rainfall observations.
			values.read("temperature_celsius"),
			values.read("humidity_percent"),
			values.read("nitrogen_concentration_ppm"),
			values.read("phosphorus_concentration_ppm"),
			values.read("potassium_concentration_ppm"),
			values.read("ph"),
			values.read("electrical_conductivity_ds_m"), // 1 dS/m = 1 mS/cm.
			values.read("soil_temperature_celsius"),
			values.read("soil_moisture_percent")
		), null, null, "hive");
	}
}

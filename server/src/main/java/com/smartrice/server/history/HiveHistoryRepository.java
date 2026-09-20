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

	private static final String TABLE = "farm.env_daily";
	private static final String STATION_FILTER = "trim(`station`) = ?";
	// `date` is this table's partition column and is already stored as YYYY-MM-DD, so the bounds are
	// compared against the raw column: wrapping it in to_date would hide the partition from Hive and
	// turn every lookup into a full scan. readDate still rejects any value that is not a padded date.
	private static final String DAY = "`date`";
	// Projection/filter queries avoid launching a cluster aggregation for each UI interaction.
	// The range comes from the metastore, not the data: `date` is the only partition column and every
	// partition holds exactly one row per station, so the partition list already answers this question.
	// Reading the column out of the files instead costs about 7ms per partition — over 18s across 2457 of
	// them — and rewriting it as MIN/MAX/COUNT is far worse still, because that launches a MapReduce job.
	static final String RANGE_SQL = "SHOW PARTITIONS " + TABLE;
	private static final String PARTITION_PREFIX = "date=";
	private static final List<String> METRICS = List.of("light_lux", "temperature_celsius", "humidity_percent",
		"wind_speed_m_s", "soil_temperature_celsius", "soil_moisture_percent", "ph",
		"electrical_conductivity_ds_m", "nitrogen_concentration_ppm", "phosphorus_concentration_ppm",
		"potassium_concentration_ppm");
	static final String DAILY_SQL = "SELECT " + DAY + " AS record_date, " + String.join(", ", METRICS)
		+ " FROM " + TABLE + " WHERE " + STATION_FILTER
		+ " AND " + DAY + " IN (?, ?)";

	private final HiveConnectionFactory connections;

	public HiveHistoryRepository(HiveConnectionFactory connections) {
		this.connections = connections;
	}

	/**
	 * The partition list is shared by every station, so {@code stationId} is validated and echoed back but
	 * no longer narrows the result. That holds only while each partition keeps one row per station; a
	 * station that ever stops reporting would still be given the table-wide range.
	 */
	public Optional<HistoryRangeResponse> range(String stationId, LocalDate today) throws SQLException {
		toHiveStation(stationId);
		try (Connection connection = connections.open();
				PreparedStatement statement = connection.prepareStatement(RANGE_SQL)) {
			statement.setQueryTimeout(connections.queryTimeoutSeconds());
			statement.setFetchSize(1000);
			try (ResultSet rows = statement.executeQuery()) {
				TreeSet<LocalDate> days = new TreeSet<>();
				while (rows.next()) {
					LocalDate day = readPartitionDate(rows);
					if (!day.isAfter(today)) days.add(day);
				}
				if (days.isEmpty()) return Optional.empty();
				return Optional.of(new HistoryRangeResponse(stationId, days.first(), days.last(), days.size()));
			}
		}
	}

	/** SHOW PARTITIONS answers with one {@code date=YYYY-MM-DD} specification per row, in a single column. */
	private static LocalDate readPartitionDate(ResultSet rows) throws SQLException {
		String value = rows.getString(1);
		String specification = value == null ? "" : value.trim();
		if (!specification.startsWith(PARTITION_PREFIX)) {
			throw new SQLException("Hive returned an unexpected partition specification", "22007");
		}
		try {
			return LocalDate.parse(specification.substring(PARTITION_PREFIX.length()));
		} catch (RuntimeException ex) {
			throw new SQLException("Hive returned an invalid partition date", "22007");
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

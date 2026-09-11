package com.smartrice.server.ai;

import com.smartrice.server.hive.HiveConnectionFactory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Repository;

/** Bounded, read-only projection queries; all grouping happens locally, never in a Hive cluster job. */
@Repository
public class HiveAiRepository {
	static final int MAX_RAW_ROWS = 100_000;
	static final List<MetricDefinition> FIELDS = List.of(
		new MetricDefinition("light_lux", "光照强度", "lux"),
		new MetricDefinition("temperature_celsius", "空气温度", "℃"),
		new MetricDefinition("humidity_percent", "空气湿度", "%"),
		new MetricDefinition("wind_speed_m_s", "风速", "m/s"),
		new MetricDefinition("soil_temperature_celsius", "土壤温度", "℃"),
		new MetricDefinition("soil_moisture_percent", "土壤湿度", "%"),
		new MetricDefinition("ph", "土壤酸碱度", "pH"),
		new MetricDefinition("electrical_conductivity_ds_m", "土壤电导率", "dS/m"),
		new MetricDefinition("nitrogen_concentration_ppm", "氮浓度", "ppm"),
		new MetricDefinition("phosphorus_concentration_ppm", "磷浓度", "ppm"),
		new MetricDefinition("potassium_concentration_ppm", "钾浓度", "ppm")
	);
	private static final String DAY = "to_date(trim(`date`))";
	static final String WINDOW_SQL = "SELECT " + DAY + " AS record_date, "
		+ String.join(", ", FIELDS.stream().map(MetricDefinition::field).toList())
		+ " FROM agri_env_data WHERE trim(`station`) = ? AND " + DAY + " >= CAST(? AS DATE)"
		+ " AND " + DAY + " <= CAST(? AS DATE) LIMIT " + MAX_RAW_ROWS;

	private final HiveConnectionFactory connections;

	public HiveAiRepository(HiveConnectionFactory connections) {
		this.connections = connections;
	}

	public WindowRows window(String hiveStation, LocalDate start, LocalDate end) throws SQLException {
		if (hiveStation == null || !hiveStation.matches("point_(?:[1-9]|10)")
				|| start == null || end == null || start.isAfter(end)
				|| ChronoUnit.DAYS.between(start, end) >= 30) {
			throw new IllegalArgumentException("Invalid bounded Hive observation window");
		}
		try (Connection connection = connections.open();
				PreparedStatement statement = connection.prepareStatement(WINDOW_SQL)) {
			statement.setQueryTimeout(connections.queryTimeoutSeconds());
			statement.setFetchSize(1000);
			statement.setString(1, hiveStation);
			statement.setString(2, start.toString());
			statement.setString(3, end.toString());
			try (ResultSet rows = statement.executeQuery()) {
				Map<LocalDate, Map<String, Average>> totals = new TreeMap<>();
				int count = 0;
				while (rows.next()) {
					if (Thread.currentThread().isInterrupted()) throw new SQLException("Observation query interrupted", "57014");
					if (++count >= MAX_RAW_ROWS) throw new RowLimitException();
					LocalDate day = readDate(rows);
					if (day.isBefore(start) || day.isAfter(end)) {
						throw new SQLException("Hive returned a date outside the requested window", "22007");
					}
					Map<String, Average> metrics = totals.computeIfAbsent(day, key -> new LinkedHashMap<>());
					for (MetricDefinition definition : FIELDS) {
						double value = rows.getDouble(definition.field());
						if (!rows.wasNull() && Double.isFinite(value)) {
							metrics.computeIfAbsent(definition.field(), key -> new Average()).add(value);
						}
					}
				}
				Map<LocalDate, Map<String, Double>> daily = new TreeMap<>();
				for (var entry : totals.entrySet()) {
					Map<String, Double> values = new LinkedHashMap<>();
					for (MetricDefinition definition : FIELDS) {
						Average average = entry.getValue().get(definition.field());
						values.put(definition.field(), average == null ? null : average.mean());
					}
					daily.put(entry.getKey(), Collections.unmodifiableMap(values));
				}
				return new WindowRows(count, Collections.unmodifiableMap(daily));
			}
		}
	}

	private static LocalDate readDate(ResultSet rows) throws SQLException {
		try {
			return LocalDate.parse(rows.getString("record_date"));
		} catch (RuntimeException ex) {
			throw new SQLException("Hive returned an invalid observation date", "22007");
		}
	}

	static Double rounded(BigDecimal value) {
		double result = value.setScale(6, RoundingMode.HALF_UP).doubleValue();
		return Double.isFinite(result) ? (result == 0.0 ? 0.0 : result) : null;
	}

	static final class Average {
		private BigDecimal sum = BigDecimal.ZERO;
		private int count;

		void add(double value) {
			sum = sum.add(BigDecimal.valueOf(value));
			count++;
		}

		Double mean() {
			return count == 0 ? null : rounded(sum.divide(BigDecimal.valueOf(count), 6, RoundingMode.HALF_UP));
		}
	}

	public record WindowRows(int rawRowCount, Map<LocalDate, Map<String, Double>> daily) {
	}

	public record MetricDefinition(String field, String label, String unit) {
	}

	static final class RowLimitException extends SQLException {
		RowLimitException() {
			super("Hive observation window reached the row limit", "54000");
		}
	}
}

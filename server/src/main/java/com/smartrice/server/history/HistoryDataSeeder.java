package com.smartrice.server.history;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class HistoryDataSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(HistoryDataSeeder.class);
	private static final int BATCH_SIZE = 500;

	private static final String INSERT_SQL = """
		INSERT INTO historical_daily_data (
			record_date, station_id,
			avg_light_klx, avg_wind_speed_m_s, avg_rainfall_mm_h,
			avg_air_temperature_c, avg_air_humidity_percent,
			avg_soil_nitrogen_mg_kg, avg_soil_phosphorus_mg_kg,
			avg_soil_potassium_mg_kg, avg_soil_ph, avg_soil_ec_ms_cm,
			disease_count, pest_density_per_100_plants, affected_area_percent,
			pest_disease_risk_index, recognition_confidence_percent,
			ndvi, ndre, gndvi, chlorophyll_spad,
			reflectance_450nm_percent, reflectance_550nm_percent,
			reflectance_650nm_percent, reflectance_720nm_percent,
			reflectance_800nm_percent, reflectance_900nm_percent
		) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
		""";

	private final HistoricalDailyDataRepository repository;
	private final JdbcTemplate jdbcTemplate;
	private final ResourceLoader resourceLoader;

	@Value("${app.history.seed.enabled:true}")
	private boolean enabled;

	@Value("${app.history.seed.resource:classpath:data/agri_history_cleaned.csv}")
	private String resourceLocation;

	public HistoryDataSeeder(HistoricalDailyDataRepository repository, JdbcTemplate jdbcTemplate,
			ResourceLoader resourceLoader) {
		this.repository = repository;
		this.jdbcTemplate = jdbcTemplate;
		this.resourceLoader = resourceLoader;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) throws IOException {
		if (!enabled) {
			log.info("历史数据自动导入已关闭");
			return;
		}
		long existing = repository.count();
		if (existing > 0) {
			log.info("历史数据表已有 {} 条记录，跳过种子数据导入", existing);
			return;
		}

		Resource resource = resourceLoader.getResource(resourceLocation);
		if (!resource.exists()) {
			throw new IllegalStateException("历史数据种子文件不存在: " + resourceLocation);
		}

		int imported = importResource(resource);
		log.info("历史数据种子导入完成，共写入 {} 条记录", imported);
	}

	private int importResource(Resource resource) throws IOException {
		int imported = 0;
		List<Object[]> batch = new ArrayList<>(BATCH_SIZE);

		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
			String headerLine = reader.readLine();
			if (headerLine == null) {
				throw new IllegalStateException("历史数据种子文件为空");
			}
			Map<String, Integer> columns = headerIndex(removeBom(headerLine).split(",", -1));
			validateColumns(columns);

			String line;
			int lineNumber = 1;
			while ((line = reader.readLine()) != null) {
				lineNumber++;
				if (line.isBlank()) {
					continue;
				}
				String[] values = line.split(",", -1);
				batch.add(toInsertValues(values, columns, lineNumber));
				if (batch.size() >= BATCH_SIZE) {
					jdbcTemplate.batchUpdate(INSERT_SQL, batch);
					imported += batch.size();
					batch.clear();
				}
			}
		}

		if (!batch.isEmpty()) {
			jdbcTemplate.batchUpdate(INSERT_SQL, batch);
			imported += batch.size();
		}
		return imported;
	}

	private Object[] toInsertValues(String[] row, Map<String, Integer> columns, int lineNumber) {
		LocalDate recordDate;
		try {
			recordDate = LocalDate.parse(value(row, columns, "date"));
		}
		catch (RuntimeException ex) {
			throw new IllegalArgumentException("历史数据第 " + lineNumber + " 行日期无效", ex);
		}
		String stationId = value(row, columns, "station_id");
		validateStation(stationId, lineNumber);

		long seed = recordDate.toEpochDay() * 37 + Integer.parseInt(stationId.substring(1)) * 101L;
		double rain = wave(seed, 0.47) > 0.7 ? round((wave(seed, 0.47) - 0.7) * 6.5, 2) : 0;
		int diseaseCount = (int) Math.floor(wave(seed, 0.71) * 4);
		double pestDensity = round(1.2 + wave(seed, 0.93) * 4.8, 2);
		double affectedArea = round(0.3 + wave(seed, 1.17) * 2.1, 2);
		double riskIndex = round(6 + wave(seed, 0.57) * 12, 2);
		double confidence = round(93 + wave(seed, 1.41) * 5, 2);
		double ndvi = round(0.72 + wave(seed, 0.81) * 0.13, 3);
		double ndre = round(0.38 + wave(seed, 1.03) * 0.1, 3);
		double gndvi = round(0.61 + wave(seed, 1.29) * 0.12, 3);
		double chlorophyll = round(38 + wave(seed, 0.67) * 7, 2);

		return new Object[] {
			Date.valueOf(recordDate),
			stationId,
			number(row, columns, "avg_light_klx", lineNumber),
			number(row, columns, "avg_wind_speed_m_s", lineNumber),
			rain,
			number(row, columns, "avg_air_temperature_c", lineNumber),
			number(row, columns, "avg_air_humidity_percent", lineNumber),
			number(row, columns, "avg_soil_nitrogen_mg_kg", lineNumber),
			number(row, columns, "avg_soil_phosphorus_mg_kg", lineNumber),
			number(row, columns, "avg_soil_potassium_mg_kg", lineNumber),
			number(row, columns, "avg_soil_ph", lineNumber),
			number(row, columns, "avg_soil_ec_ms_cm", lineNumber),
			diseaseCount,
			pestDensity,
			affectedArea,
			riskIndex,
			confidence,
			ndvi,
			ndre,
			gndvi,
			chlorophyll,
			reflectance(seed, 0, 8),
			reflectance(seed, 1, 12),
			reflectance(seed, 2, 18),
			reflectance(seed, 3, 43),
			reflectance(seed, 4, 68),
			reflectance(seed, 5, 74)
		};
	}

	private static Map<String, Integer> headerIndex(String[] headers) {
		Map<String, Integer> result = new HashMap<>();
		for (int index = 0; index < headers.length; index++) {
			result.put(headers[index].trim(), index);
		}
		return result;
	}

	private static void validateColumns(Map<String, Integer> columns) {
		List<String> required = List.of(
			"date", "station_id", "avg_light_klx", "avg_wind_speed_m_s",
			"avg_air_temperature_c", "avg_air_humidity_percent",
			"avg_soil_nitrogen_mg_kg", "avg_soil_phosphorus_mg_kg",
			"avg_soil_potassium_mg_kg", "avg_soil_ph", "avg_soil_ec_ms_cm"
		);
		List<String> missing = required.stream().filter(column -> !columns.containsKey(column)).toList();
		if (!missing.isEmpty()) {
			throw new IllegalStateException("历史数据种子文件缺少列: " + String.join(", ", missing));
		}
	}

	private static void validateStation(String stationId, int lineNumber) {
		if (!stationId.matches("S(?:0[1-9]|10)")) {
			throw new IllegalArgumentException("历史数据第 " + lineNumber + " 行站点无效: " + stationId);
		}
	}

	private static String value(String[] row, Map<String, Integer> columns, String column) {
		int index = columns.get(column);
		if (index >= row.length) {
			return "";
		}
		return row[index].trim();
	}

	private static double number(String[] row, Map<String, Integer> columns, String column, int lineNumber) {
		try {
			return Double.parseDouble(value(row, columns, column));
		}
		catch (NumberFormatException ex) {
			throw new IllegalArgumentException(
				"历史数据第 " + lineNumber + " 行 " + column + " 不是有效数字", ex
			);
		}
	}

	private static double wave(long seed, double factor) {
		return (Math.sin(seed * factor) + 1) / 2;
	}

	private static double reflectance(long seed, int bandIndex, double base) {
		return round(base + (wave(seed, 0.4 + bandIndex * 0.13) - 0.5) * 6, 2);
	}

	private static double round(double value, int decimals) {
		double scale = Math.pow(10, decimals);
		return Math.round(value * scale) / scale;
	}

	private static String removeBom(String value) {
		return value.startsWith("\uFEFF") ? value.substring(1) : value;
	}
}

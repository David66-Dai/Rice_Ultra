package com.smartrice.server.ai;

import com.smartrice.server.hive.HiveConnectionFactory;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Repository;

/** Read-only adapter for the legacy Hive tables confirmed in the farm database. */
@Repository
public class HiveLegacyAiRepository {
	private static final String DISEASE_TABLE = "farm.pest_data";
	private static final String YIELD_TABLE = "farm.rice_yield";
	private static final Pattern YIELD_COLUMN = Pattern.compile("^(\\d{4})firstcrop(_pred)?$");
	private static final int DISEASE_ROW_LIMIT = 100_000;
	private static final List<ValueDefinition> DISEASE_FIELDS = List.of(
		new ValueDefinition("growthperiod", "生育期", ""),
		new ValueDefinition("growthstatus", "生长状况", ""),
		new ValueDefinition("bacterialleafblightrate", "白叶枯病发病率（原值）", ""),
		new ValueDefinition("brownspotrate", "褐斑病发病率（原值）", ""),
		new ValueDefinition("tungrovirusrate", "东格鲁病毒病发病率（原值）", ""),
		new ValueDefinition("pestrphnum", "稻飞虱数量", "只"),
		new ValueDefinition("pestscsnum", "二化螟数量", "只"),
		new ValueDefinition("pestcmnum", "稻纵卷叶螟数量", "只")
	);
	static final String DISEASE_SQL = "SELECT to_date(trim(`date`)) AS record_date, "
		+ String.join(", ", DISEASE_FIELDS.stream().map(ValueDefinition::field).toList())
		+ " FROM " + DISEASE_TABLE + " WHERE trim(`point`) = ?"
		+ " AND to_date(trim(`date`)) <= CAST(? AS DATE) LIMIT " + DISEASE_ROW_LIMIT;
	static final String YIELD_SCHEMA_SQL = "DESCRIBE " + YIELD_TABLE;
	private final HiveConnectionFactory connections;

	public HiveLegacyAiRepository(HiveConnectionFactory connections) {
		this.connections = connections;
	}

	public AiLegacyContext context(String stationId, String hiveStation, LocalDate target) {
		if (stationId == null || !stationId.matches("S(?:0[1-9]|10)") || hiveStation == null
				|| !hiveStation.equals("point_" + Integer.parseInt(stationId.substring(1))) || target == null) {
			throw new IllegalArgumentException("Invalid legacy reference query");
		}
		List<String> limitations = new ArrayList<>(List.of(
			"病虫害和产量暂沿用旧版 Hive 数据，独立保留参考日期或年份，不代表目标日期的新观测。",
			"旧表值保留原有单位和口径，未经本次农艺校准；产量基线不等于实际收获产量或本次预测结果。",
			"旧病害率字段的比例或百分数口径尚未确认，按原值展示，不添加 % 或自动乘以 100。",
			"旧虫害数量为原表计数，未提供采样面积，不能推导每百株或每亩虫口密度。"
		));
		AiLegacyContext.Disease disease = missingDisease();
		AiLegacyContext.YieldReference yield = missingYield();
		try (Connection connection = connections.open()) {
			try {
				disease = disease(connection, hiveStation, target, limitations);
			} catch (SQLException ex) {
				limitations.add("旧版病虫害表暂不可用或数据结构不兼容，未替换为演示数据。");
			}
			try {
				checkInterrupted();
				yield = readYield(connection, hiveStation, target.getYear(), limitations);
			} catch (SQLException ex) {
				limitations.add("旧版产量表暂不可用或数据结构不兼容，未替换为演示数据。");
			}
		} catch (SQLException ex) {
			// Neither diagnostics nor causes escape: JDBC messages may carry connection secrets.
			limitations.add("旧版 Hive 参考数据暂不可用，当前环境证据仍可独立查看。");
		}
		return new AiLegacyContext("hive_legacy", stationId, disease, yield, limitations);
	}

	private AiLegacyContext.Disease disease(Connection connection, String point, LocalDate target,
			List<String> limitations) throws SQLException {
		try (PreparedStatement statement = prepared(connection, DISEASE_SQL)) {
			statement.setString(1, point);
			statement.setString(2, target.toString());
			try (ResultSet rows = statement.executeQuery()) {
				LocalDate newest = null;
				List<AiLegacyContext.Value> chosen = List.of();
				boolean conflict = false;
				int count = 0;
				while (rows.next()) {
					checkInterrupted();
					if (++count >= DISEASE_ROW_LIMIT) throw new SQLException("Legacy source reached row limit", "54000");
					LocalDate day;
					try {
						day = LocalDate.parse(rows.getString("record_date"));
					} catch (RuntimeException ex) {
						throw new SQLException("Invalid legacy date", "22007");
					}
					if (day.isAfter(target)) throw new SQLException("Future legacy reference rejected", "22007");
					if (newest != null && day.isBefore(newest)) continue;
					List<AiLegacyContext.Value> values = new ArrayList<>();
					for (var field : DISEASE_FIELDS) {
						String value = rows.getString(field.field());
						value = value == null || value.isBlank() ? null : value.trim();
						if ((!field.unit().isEmpty() || field.field().endsWith("rate")) && value != null) {
							try { new BigDecimal(value); }
							catch (NumberFormatException ex) { value = null; }
						}
						values.add(new AiLegacyContext.Value(field.field(), field.label(), field.unit(), value));
					}
					if (day.equals(newest)) conflict |= !chosen.equals(values);
					else { newest = day; chosen = values; conflict = false; }
				}
				if (newest == null) {
					limitations.add("旧版病虫害表在目标日期及之前没有该站点记录。");
					return missingDisease();
				}
				if (conflict || chosen.stream().allMatch(value -> value.value() == null)) {
					limitations.add("旧版病虫害最新参考日的数据存在冲突或全部缺失，暂不使用该记录。");
					return missingDisease();
				}
				if (chosen.stream().anyMatch(value -> value.value() == null)) limitations.add("旧版病虫害参考记录有缺失或无效字段，未补成 0。");
				String match = newest.equals(target) ? "exact" : "latest_prior";
				limitations.add("病虫害参考日期为 " + newest + (match.equals("exact") ? "，与目标日相同。" : "，仅为目标日之前最近记录。"));
				return new AiLegacyContext.Disease(true, DISEASE_TABLE, newest, match, chosen);
			}
		}
	}

	private AiLegacyContext.YieldReference readYield(Connection connection, String point, int year,
			List<String> limitations) throws SQLException {
		List<YieldColumn> columns = new ArrayList<>();
		try (PreparedStatement statement = prepared(connection, YIELD_SCHEMA_SQL); ResultSet rows = statement.executeQuery()) {
			int count = 0;
			while (rows.next()) {
				checkInterrupted();
				if (++count > 2000) throw new SQLException("Legacy schema exceeds bound", "54000");
				String name = rows.getString(1);
				if (name == null) continue;
				var match = YIELD_COLUMN.matcher(name.trim());
				if (match.matches()) {
					int referenceYear = Integer.parseInt(match.group(1));
					if (referenceYear >= 1 && referenceYear <= year) columns.add(new YieldColumn(name.trim(), referenceYear, match.group(2) != null));
				}
			}
		}
		columns.sort(Comparator.comparingInt(YieldColumn::year).reversed().thenComparing(YieldColumn::predicted));
		if (columns.isEmpty()) {
			limitations.add("旧版产量表没有目标年份及之前的 firstcrop 基线列。");
			return missingYield();
		}
		String sql = "SELECT " + String.join(", ", columns.stream().map(column -> "`" + column.name() + "`").toList())
			+ " FROM " + YIELD_TABLE + " WHERE trim(`point`) = ? LIMIT 2";
		try (PreparedStatement statement = prepared(connection, sql)) {
			statement.setString(1, point);
			try (ResultSet rows = statement.executeQuery()) {
				if (!rows.next()) {
					limitations.add("旧版产量表没有该站点的基线记录。");
					return missingYield();
				}
				YieldColumn selected = null;
				Double baseline = null;
				for (YieldColumn column : columns) {
					double value = rows.getDouble(column.name());
					if (!rows.wasNull() && Double.isFinite(value) && value >= 0) {
						selected = column;
						baseline = HiveAiRepository.rounded(BigDecimal.valueOf(value));
						break;
					}
				}
				if (rows.next()) {
					limitations.add("旧版产量表同一站点存在多条记录，未任意选择基线。");
					return missingYield();
				}
				if (selected == null) {
					limitations.add("旧版产量表该站点在可用年份的 firstcrop 基线均缺失或无效。");
					return missingYield();
				}
				limitations.add("产量引用旧表列 " + selected.name() + "，年份 " + selected.year()
					+ "，口径为第一季基线 kg/亩" + (selected.predicted() ? "；_pred 后缀表示旧版预测基线。" : "。"));
				return new AiLegacyContext.YieldReference(true, YIELD_TABLE, selected.year(), "firstcrop",
					selected.year() == year ? "exact_year" : "latest_prior", baseline);
			}
		}
	}

	private PreparedStatement prepared(Connection connection, String sql) throws SQLException {
		checkInterrupted();
		PreparedStatement statement = connection.prepareStatement(sql);
		try {
			statement.setQueryTimeout(connections.queryTimeoutSeconds());
			statement.setFetchSize(1000);
			return statement;
		} catch (SQLException ex) {
			statement.close();
			throw ex;
		}
	}

	private static void checkInterrupted() throws SQLException {
		if (Thread.currentThread().isInterrupted()) throw new SQLException("Legacy query interrupted", "57014");
	}

	private static AiLegacyContext.Disease missingDisease() {
		return new AiLegacyContext.Disease(false, DISEASE_TABLE, null, "missing", List.of());
	}

	private static AiLegacyContext.YieldReference missingYield() {
		return new AiLegacyContext.YieldReference(false, YIELD_TABLE, null, "firstcrop", "missing", null);
	}

	private record ValueDefinition(String field, String label, String unit) { }
	private record YieldColumn(String name, int year, boolean predicted) { }
}

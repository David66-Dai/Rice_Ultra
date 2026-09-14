package com.smartrice.server.ai;

import com.smartrice.server.hive.HiveConnectionFactory;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Repository;

/**
 * Read-only adapter for the yield baseline kept in the legacy Hive table. Pest and disease figures no
 * longer come from here: the same-day numbers are summarised from the MySQL inspection records and
 * earlier days go through the reserved archive source.
 */
@Repository
public class HiveLegacyAiRepository {
	private static final String YIELD_TABLE = "farm.rice_yield";
	private static final Pattern YIELD_COLUMN = Pattern.compile("^(\\d{4})firstcrop(_pred)?$");
	static final String YIELD_SCHEMA_SQL = "DESCRIBE " + YIELD_TABLE;
	private final HiveConnectionFactory connections;

	public HiveLegacyAiRepository(HiveConnectionFactory connections) {
		this.connections = connections;
	}

	/** Appends its own caveats to {@code limitations}; never throws for an unavailable legacy table. */
	public AiLegacyContext.YieldReference yieldBaseline(String stationId, String hiveStation, int year,
			List<String> limitations) {
		if (stationId == null || !stationId.matches("S(?:0[1-9]|10)") || hiveStation == null
				|| !hiveStation.equals("point_" + Integer.parseInt(stationId.substring(1)))) {
			throw new IllegalArgumentException("Invalid legacy reference query");
		}
		try (Connection connection = connections.open()) {
			checkInterrupted();
			return readYield(connection, hiveStation, year, limitations);
		} catch (SQLException ex) {
			// Neither diagnostics nor causes escape: JDBC messages may carry connection secrets.
			limitations.add("旧版产量表暂不可用或数据结构不兼容，未替换为演示数据。");
			return missingYield();
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

	static AiLegacyContext.YieldReference missingYield() {
		return new AiLegacyContext.YieldReference(false, YIELD_TABLE, null, "firstcrop", "missing", null);
	}

	private record YieldColumn(String name, int year, boolean predicted) { }
}

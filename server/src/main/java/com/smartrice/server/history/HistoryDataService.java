package com.smartrice.server.history;

import com.smartrice.server.history.HistoryDailyResponse.HistoryDayData;
import com.smartrice.server.pest.PestDiseaseService;
import com.smartrice.server.pest.PestDiseaseSummary;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class HistoryDataService {

	private static final Logger log = LoggerFactory.getLogger(HistoryDataService.class);
	private static final Pattern STATION_ID = Pattern.compile("S(?:0[1-9]|10)");
	private static final ZoneId FIELD_ZONE = ZoneId.of("Asia/Shanghai");
	private final HiveHistoryRepository repository;
	private final PestDiseaseService pestDiseases;

	public HistoryDataService(HiveHistoryRepository repository, PestDiseaseService pestDiseases) {
		this.repository = repository;
		this.pestDiseases = pestDiseases;
	}

	public HistoryRangeResponse range(String stationId) {
		String station = validateStation(stationId);
		try {
			return repository.range(station, LocalDate.now(FIELD_ZONE))
				.orElseThrow(() -> notFound(station, null));
		} catch (SQLException ex) {
			throw unavailable(ex);
		}
	}

	public HistoryDailyResponse daily(String stationId, LocalDate date) {
		String station = validateStation(stationId);
		if (date == null || date.isAfter(LocalDate.now(FIELD_ZONE))) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日期不能为空，且不能查询未来日期");
		}
		try {
			Map<LocalDate, HistoryDayData> days = repository.days(station, date);
			HistoryDayData current = days.get(date);
			if (current == null) throw notFound(station, date);
			return new HistoryDailyResponse(withPestDisease(current, station, date), days.get(date.minusDays(1)));
		} catch (SQLException ex) {
			throw unavailable(ex);
		}
	}

	/** An unavailable pest source degrades that one card; the environment archive still returns. */
	private HistoryDayData withPestDisease(HistoryDayData day, String station, LocalDate date) {
		PestDiseaseSummary summary;
		try {
			summary = pestDiseases.daily(station, date);
		} catch (RuntimeException ex) {
			log.warn("病虫害数据读取失败，站点={}，日期={}", station, date);
			summary = PestDiseaseSummary.empty("unavailable", "", station, date,
				List.of("病虫害数据源暂不可用，环境归档仍可查看。"));
		}
		return new HistoryDayData(day.date(), day.stationId(), day.environment(), summary, day.spectrum(), day.source());
	}

	private static String validateStation(String stationId) {
		String normalized = stationId == null ? "" : stationId.trim().toUpperCase(java.util.Locale.ROOT);
		if (!STATION_ID.matcher(normalized).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "stationId 必须为 S01-S10");
		}
		return normalized;
	}

	private static ResponseStatusException notFound(String station, LocalDate date) {
		return new ResponseStatusException(HttpStatus.NOT_FOUND,
			station + (date == null ? "" : " 在 " + date) + " 没有历史数据");
	}

	private static ResponseStatusException unavailable(SQLException ex) {
		log.warn("Hive 历史数据查询失败，SQLState={}，errorCode={}", ex.getSQLState(), ex.getErrorCode());
		return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
			"Hive 历史数据库暂不可用，请检查服务端连接配置或稍后重试");
	}
}

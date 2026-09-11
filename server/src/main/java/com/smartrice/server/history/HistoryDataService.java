package com.smartrice.server.history;

import com.smartrice.server.history.HistoryDailyResponse.EnvironmentAverages;
import com.smartrice.server.history.HistoryDailyResponse.HistoryDayData;
import com.smartrice.server.history.HistoryDailyResponse.PestDiseaseArchive;
import com.smartrice.server.history.HistoryDailyResponse.SpectralArchive;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class HistoryDataService {

	private static final Pattern STATION_ID = Pattern.compile("S(?:0[1-9]|10)");

	private final HistoricalDailyDataRepository repository;

	public HistoryDataService(HistoricalDailyDataRepository repository) {
		this.repository = repository;
	}

	public HistoryRangeResponse range(String stationId) {
		String normalizedStation = validateStation(stationId);
		LocalDate today = LocalDate.now();
		HistoricalDailyData first = repository
			.findFirstByStationIdAndRecordDateLessThanEqualOrderByRecordDateAsc(normalizedStation, today)
			.orElseThrow(() -> notFound(normalizedStation, null));
		HistoricalDailyData last = repository
			.findFirstByStationIdAndRecordDateLessThanEqualOrderByRecordDateDesc(normalizedStation, today)
			.orElseThrow(() -> notFound(normalizedStation, null));
		long count = repository.countByStationIdAndRecordDateLessThanEqual(normalizedStation, today);
		return new HistoryRangeResponse(normalizedStation, first.recordDate, last.recordDate, count);
	}

	public HistoryDailyResponse daily(String stationId, LocalDate recordDate) {
		String normalizedStation = validateStation(stationId);
		if (recordDate == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "date 不能为空");
		}
		if (recordDate.isAfter(LocalDate.now())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能查询未来日期的历史数据");
		}

		HistoricalDailyData current = repository
			.findByStationIdAndRecordDate(normalizedStation, recordDate)
			.orElseThrow(() -> notFound(normalizedStation, recordDate));
		HistoryDayData previous = repository
			.findByStationIdAndRecordDate(normalizedStation, recordDate.minusDays(1))
			.map(HistoryDataService::toDayData)
			.orElse(null);
		return new HistoryDailyResponse(toDayData(current), previous);
	}

	private static String validateStation(String stationId) {
		String normalized = stationId == null ? "" : stationId.trim().toUpperCase();
		if (!STATION_ID.matcher(normalized).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "stationId 必须为 S01-S10");
		}
		return normalized;
	}

	private static ResponseStatusException notFound(String stationId, LocalDate recordDate) {
		String suffix = recordDate == null ? "" : " 在 " + recordDate;
		return new ResponseStatusException(
			HttpStatus.NOT_FOUND,
			stationId + suffix + " 没有历史数据"
		);
	}

	private static HistoryDayData toDayData(HistoricalDailyData data) {
		return new HistoryDayData(
			data.recordDate,
			data.stationId,
			new EnvironmentAverages(
				data.avgLightKlx,
				data.avgWindSpeedMs,
				data.avgRainfallMmH,
				data.avgAirTemperatureC,
				data.avgAirHumidityPercent,
				data.avgSoilNitrogenMgKg,
				data.avgSoilPhosphorusMgKg,
				data.avgSoilPotassiumMgKg,
				data.avgSoilPh,
				data.avgSoilEcMsCm
			),
			new PestDiseaseArchive(
				data.diseaseCount,
				data.pestDensityPer100Plants,
				data.affectedAreaPercent,
				data.pestDiseaseRiskIndex,
				data.recognitionConfidencePercent
			),
			new SpectralArchive(
				data.ndvi,
				data.ndre,
				data.gndvi,
				data.chlorophyllSpad,
				List.of(
					data.reflectance450nmPercent,
					data.reflectance550nmPercent,
					data.reflectance650nmPercent,
					data.reflectance720nmPercent,
					data.reflectance800nmPercent,
					data.reflectance900nmPercent
				)
			)
		);
	}
}

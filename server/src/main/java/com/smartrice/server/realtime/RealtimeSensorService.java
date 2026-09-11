package com.smartrice.server.realtime;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RealtimeSensorService {

	private static final Pattern STATION_ID = Pattern.compile("S(?:0[1-9]|10)");
	private static final ZoneId FIELD_ZONE = ZoneId.of("Asia/Shanghai");

	private final RealtimeSensorReadingRepository repository;

	public RealtimeSensorService(RealtimeSensorReadingRepository repository) {
		this.repository = repository;
	}

	public RealtimeTodayResponse today(String stationId) {
		String normalizedStation = validateStation(stationId);
		LocalDate today = LocalDate.now(FIELD_ZONE);
		Instant start = today.atStartOfDay(FIELD_ZONE).toInstant();
		Instant end = today.plusDays(1).atStartOfDay(FIELD_ZONE).toInstant();
		List<RealtimeSensorReadingResponse> readings = repository
			.findByStationIdAndSampledAtGreaterThanEqualAndSampledAtLessThanOrderBySampledAtAsc(
				normalizedStation, start, end
			)
			.stream()
			.map(RealtimeSensorService::toResponse)
			.toList();
		return new RealtimeTodayResponse(normalizedStation, today, readings);
	}

	public RealtimeSensorReadingResponse latest(String stationId) {
		String normalizedStation = validateStation(stationId);
		return repository.findFirstByStationIdOrderBySampledAtDesc(normalizedStation)
			.map(RealtimeSensorService::toResponse)
			.orElseThrow(() -> new ResponseStatusException(
				HttpStatus.NOT_FOUND, normalizedStation + " 暂无实时传感器数据"
			));
	}

	private static String validateStation(String stationId) {
		String normalized = stationId == null ? "" : stationId.trim().toUpperCase();
		if (!STATION_ID.matcher(normalized).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "stationId 必须为 S01-S10");
		}
		return normalized;
	}

	private static RealtimeSensorReadingResponse toResponse(RealtimeSensorReading data) {
		return new RealtimeSensorReadingResponse(
			data.sampledAt,
			data.lightKlx,
			data.windSpeedMs,
			data.rainfallMmH,
			data.airTemperatureC,
			data.airHumidityPercent,
			data.soilNitrogenMgKg,
			data.soilPhosphorusMgKg,
			data.soilPotassiumMgKg,
			data.soilPh,
			data.soilEcMsCm
		);
	}
}

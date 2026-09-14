package com.smartrice.server.realtime;

import com.smartrice.server.diagnosis.AlertLevel;
import com.smartrice.server.diagnosis.InspectionDiagnosis;
import com.smartrice.server.diagnosis.InspectionDiagnosisRepository;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PreventionSafetyGate {

	private final PreventionControlProperties properties;
	private final RealtimeSensorReadingRepository readings;
	private final InspectionDiagnosisRepository diagnoses;

	public PreventionSafetyGate(PreventionControlProperties properties,
			RealtimeSensorReadingRepository readings, InspectionDiagnosisRepository diagnoses) {
		this.properties = properties;
		this.readings = readings;
		this.diagnoses = diagnoses;
	}

	public void verifyStart(String stationId, String device, boolean manual) {
		if (!properties.isSpraySafetyEnabled() || !DeviceCommandService.PUMP.equals(device)) return;
		RealtimeSensorReading latest = readings.findFirstByStationIdOrderBySampledAtDesc(stationId)
			.orElseThrow(() -> conflict("缺少实时风速，禁止开启喷药"));
		Instant now = Instant.now();
		Duration sensorAge = positive(properties.getSensorMaxAge(), Duration.ofMinutes(2));
		if (latest.sampledAt == null || latest.sampledAt.isBefore(now.minus(sensorAge))
				|| latest.sampledAt.isAfter(now.plusSeconds(30))) {
			throw conflict("实时风速已过期，禁止开启喷药");
		}
		Double wind = latest.windSpeedMs;
		if (wind == null || !Double.isFinite(wind)) {
			throw conflict("实时风速无效，禁止开启喷药");
		}
		double maximum = maximumWind();
		if (wind > maximum) {
			throw conflict("当前风速 %.2f m/s 超过喷药上限 %.2f m/s，禁止开启喷药".formatted(wind, maximum));
		}
		if (manual) verifyLeafEvidence(stationId, now);
	}

	public boolean exceedsWindLimit(Double windSpeedMs) {
		return properties.isSpraySafetyEnabled() && windSpeedMs != null
			&& Double.isFinite(windSpeedMs) && windSpeedMs > maximumWind();
	}

	public double maximumWind() {
		double value = properties.getMaxSprayWindSpeedMs();
		if (!Double.isFinite(value) || value < 0) {
			throw new IllegalStateException("app.prevention-control.max-spray-wind-speed-m-s 配置无效");
		}
		return value;
	}

	private void verifyLeafEvidence(String stationId, Instant now) {
		InspectionDiagnosis leaf = diagnoses.findFirstByStationIdAndTaskOrderByCreatedAtDesc(stationId, "leaf")
			.orElseThrow(() -> conflict("缺少叶害识别信息，人工喷药被拒绝"));
		Duration maximumAge = positive(properties.getLeafEvidenceMaxAge(), Duration.ofHours(24));
		if (leaf.getCreatedAt() == null || leaf.getCreatedAt().isBefore(now.minus(maximumAge))
				|| leaf.getCreatedAt().isAfter(now.plusSeconds(30))) {
			throw conflict("叶害识别信息已过期，请重新识别后再人工喷药");
		}
		if (AlertLevel.parse(leaf.getAlertLevel()) != AlertLevel.RED) {
			throw conflict("最新叶害识别未达到红色告警，人工喷药被拒绝");
		}
	}

	private static Duration positive(Duration value, Duration fallback) {
		return value == null || value.isNegative() || value.isZero() ? fallback : value;
	}

	private static ResponseStatusException conflict(String message) {
		return new ResponseStatusException(HttpStatus.CONFLICT, message);
	}
}

package com.smartrice.server.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RealtimeSensorIngestionService {
	private final RealtimeSensorReadingRepository readings;
	private final RedisPendingSampleRepository pending;
	private final ObjectMapper json;
	private final boolean redisEnabled;
	private final String deviceId;
	private final ApplicationEventPublisher events;

	public RealtimeSensorIngestionService(RealtimeSensorReadingRepository readings,
			RedisPendingSampleRepository pending, ObjectMapper json,
			@Value("${app.realtime.redis.enabled:false}") boolean redisEnabled,
			@Value("${app.realtime.redis.device-id:environment_platform_01}") String deviceId,
			ApplicationEventPublisher events) {
		if (!deviceId.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("Invalid Redis device-id");
		this.readings = readings;
		this.pending = pending;
		this.json = json;
		this.redisEnabled = redisEnabled;
		this.deviceId = deviceId;
		this.events = events;
	}

	@Transactional
	public void save(RealtimeSensorReading reading) {
		readings.save(reading);
		if (redisEnabled) {
			var sample = new RedisPendingSample(deviceId, reading.sampledAt, payload(reading));
			sample.startedAt = reading.startedAt == null ? reading.sampledAt : reading.startedAt;
			pending.save(sample);
		}
		events.publishEvent(new WindReadingEvent(reading.stationId, reading.windSpeedMs, reading.sampledAt));
	}

	String payload(RealtimeSensorReading reading) {
		var data = new LinkedHashMap<String, Object>();
		data.put("date", new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(reading.sampledAt));
		data.put("station", "point_" + Integer.parseInt(reading.stationId.substring(1)));
		data.put("light_lux", reading.lightKlx == null ? null : reading.lightKlx * 1000);
		data.put("temperature_celsius", reading.airTemperatureC);
		data.put("humidity_percent", reading.airHumidityPercent);
		data.put("wind_speed_m_s", reading.windSpeedMs);
		data.put("soil_temperature_celsius", reading.soilTemperatureC);
		data.put("soil_moisture_percent", reading.soilMoisturePercent);
		data.put("ph", reading.soilPh);
		data.put("electrical_conductivity_ds_m", reading.soilEcMsCm);
		data.put("nitrogen_concentration_ppm", reading.soilNitrogenMgKg);
		data.put("phosphorus_concentration_ppm", reading.soilPhosphorusMgKg);
		data.put("potassium_concentration_ppm", reading.soilPotassiumMgKg);
		try {
			return json.writeValueAsString(data);
		} catch (JsonProcessingException ex) {
			throw new IllegalStateException("Cannot serialize sensor reading");
		}
	}
}

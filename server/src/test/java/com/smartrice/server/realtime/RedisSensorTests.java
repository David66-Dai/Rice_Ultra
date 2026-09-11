package com.smartrice.server.realtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;

class RedisSensorTests {
	@Test void ingestionPreservesLegacySchemaUnitsAndMissingValues() throws Exception {
		var readings = mock(RealtimeSensorReadingRepository.class);
		var pending = mock(RedisPendingSampleRepository.class);
		var json = new ObjectMapper();
		var service = new RealtimeSensorIngestionService(readings, pending, json, true, "environment_platform_01");
		var reading = reading();
		service.save(reading);
		verify(readings).save(reading);
		var captor = ArgumentCaptor.forClass(RedisPendingSample.class);
		verify(pending).save(captor.capture());
		var sample = captor.getValue();
		var data = json.readTree(sample.payload);
		assertThat(data.size()).isEqualTo(13);
		assertThat(data.get("date").asText()).isEqualTo("2026-09-11T01:02:03.123456Z");
		assertThat(data.get("station").asText()).isEqualTo("point_1");
		assertThat(data.get("light_lux").asDouble()).isEqualTo(12300);
		assertThat(data.get("soil_temperature_celsius").asDouble()).isEqualTo(23.5);
		assertThat(data.get("soil_moisture_percent").isNull()).isTrue();
		assertThat(data.get("electrical_conductivity_ds_m").asDouble()).isEqualTo(1.2);
		assertThat(data.has("rainfall_mm_h")).isFalse();
		assertThat(sample.deviceId).isEqualTo("environment_platform_01");
	}

	@Test void disabledRedisKeepsOnlyMysqlPersistence() {
		var readings = mock(RealtimeSensorReadingRepository.class);
		var pending = mock(RedisPendingSampleRepository.class);
		new RealtimeSensorIngestionService(readings, pending, new ObjectMapper(), false, "device").save(reading());
		verify(readings).save(any());
		verifyNoInteractions(pending);
	}

	@Test void outageRetainsSampleAndRetryAcknowledgesBeforeDeletion() {
		var pending = mock(RedisPendingSampleRepository.class);
		var sample = new RedisPendingSample("device", Instant.now(), "{}");
		when(pending.findTop100ByOrderBySampledAtAscIdAsc()).thenReturn(List.of(sample));
		var publisher = spy(new RedisSensorPublisher(pending, mock(StringRedisTemplate.class), 30));
		doThrow(new RedisConnectionFailureException("test outage")).doNothing().when(publisher).publish(sample);
		publisher.publishPending();
		verify(pending, never()).deleteById(any());
		publisher.publishPending();
		verify(pending).deleteById(sample.id);
	}

	@Test void absentRedisAcknowledgementDoesNotDeleteSample() {
		var pending = mock(RedisPendingSampleRepository.class);
		var sample = new RedisPendingSample("device", Instant.now(), "{}");
		when(pending.findTop100ByOrderBySampledAtAscIdAsc()).thenReturn(List.of(sample));
		var redis = mock(StringRedisTemplate.class);
		doReturn(Instant.now().toEpochMilli()).when(redis).execute(any(RedisCallback.class));
		new RedisSensorPublisher(pending, redis, 30).publishPending();
		verify(pending, never()).deleteById(any());
	}

	static RealtimeSensorReading reading() {
		var reading = new RealtimeSensorReading();
		reading.stationId = "S01";
		reading.sampledAt = Instant.parse("2026-09-11T01:02:03.123456789Z");
		reading.lightKlx = 12.3;
		reading.soilTemperatureC = 23.5;
		reading.soilEcMsCm = 1.2;
		return reading;
	}
}

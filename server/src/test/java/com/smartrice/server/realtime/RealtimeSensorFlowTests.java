package com.smartrice.server.realtime;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "app.auth.bootstrap-admin.enabled=false")
class RealtimeSensorFlowTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	RealtimeSensorReadingRepository repository;

	@BeforeEach
	void clearReadings() {
		repository.deleteAll();
	}

	@Test
	void todayReturnsEmptyListUntilSensorWritesData() throws Exception {
		mvc.perform(get("/api/realtime/today")
				.param("stationId", "S01")
				.with(jwt()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.stationId").value("S01"))
			.andExpect(jsonPath("$.readings").isEmpty());
	}

	@Test
	void todayAndLatestReturnPersistedSensorReading() throws Exception {
		RealtimeSensorReading reading = new RealtimeSensorReading();
		reading.stationId = "S01";
		reading.sampledAt = Instant.now();
		reading.airTemperatureC = 26.8;
		reading.airHumidityPercent = 78.0;
		reading.soilPh = 6.5;
		repository.saveAndFlush(reading);

		mvc.perform(get("/api/realtime/today")
				.param("stationId", "S01")
				.with(jwt()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.readings.length()").value(1))
			.andExpect(jsonPath("$.readings[0].airTemperatureC").value(26.8))
			.andExpect(jsonPath("$.readings[0].soilPh").value(6.5));

		mvc.perform(get("/api/realtime/latest")
				.param("stationId", "S01")
				.with(jwt()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.airHumidityPercent").value(78.0));
	}

	@Test
	void realtimeEndpointsValidateStationAndRequireAuthentication() throws Exception {
		mvc.perform(get("/api/realtime/today")
				.param("stationId", "S11")
				.with(jwt()))
			.andExpect(status().isBadRequest());

		mvc.perform(get("/api/realtime/today").param("stationId", "S01"))
			.andExpect(status().isUnauthorized());
	}
}

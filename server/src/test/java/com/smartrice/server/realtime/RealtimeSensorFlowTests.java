package com.smartrice.server.realtime;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestPropertySource(properties = "app.auth.bootstrap-admin.enabled=false")
class RealtimeSensorFlowTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	RealtimeSensorReadingRepository repository;

	@Autowired
	RealtimeSensorIngestionService ingestion;

	@BeforeEach
	void clearReadings() {
		repository.deleteAll();
	}

	@Test
	void todayReturnsEmptyListUntilSensorWritesData() throws Exception {
		snapshot(get("/api/realtime/today").param("stationId", "S01"))
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

		snapshot(get("/api/realtime/today").param("stationId", "S01"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.readings.length()").value(1))
			.andExpect(jsonPath("$.readings[0].airTemperatureC").value(26.8))
			.andExpect(jsonPath("$.readings[0].soilPh").value(6.5));

		snapshot(get("/api/realtime/latest").param("stationId", "S01"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.airHumidityPercent").value(78.0));
	}

	@Test
	void waitingClientIsReleasedAsSoonAsTheNextSampleLands() throws Exception {
		RealtimeSensorReading first = sample("S01", 26.8);
		first.sampledAt = first.sampledAt.minusSeconds(5);
		repository.saveAndFlush(first);

		MvcResult waiting = mvc.perform(get("/api/realtime/today")
				.param("stationId", "S01")
				.param("after", storedCursor("S01"))
				.param("waitSeconds", "25")
				.with(jwt()))
			.andExpect(request().asyncStarted())
			.andReturn();

		ingestion.save(sample("S01", 27.4));

		mvc.perform(asyncDispatch(waiting))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.readings.length()").value(2))
			.andExpect(jsonPath("$.readings[1].airTemperatureC").value(27.4));
	}

	@Test
	void anotherStationsSampleDoesNotReleaseAWaitingClient() throws Exception {
		MvcResult waiting = mvc.perform(get("/api/realtime/today")
				.param("stationId", "S01")
				.param("after", "")
				.param("waitSeconds", "25")
				.with(jwt()))
			.andExpect(request().asyncStarted())
			.andReturn();

		ingestion.save(sample("S02", 25.1));
		ingestion.save(sample("S01", 28.3));

		// 若被 S02 的采样错误唤醒，这里拿到的会是 S01 的空快照
		mvc.perform(asyncDispatch(waiting))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.readings.length()").value(1))
			.andExpect(jsonPath("$.readings[0].airTemperatureC").value(28.3));
	}

	@Test
	void realtimeEndpointsValidateStationAndRequireAuthentication() throws Exception {
		mvc.perform(get("/api/realtime/today")
				.param("stationId", "S11")
				.with(jwt()))
			.andExpect(status().isBadRequest());

		mvc.perform(get("/api/realtime/today").param("stationId", "S01"))
			.andExpect(status().isUnauthorized());

		mvc.perform(get("/api/realtime/today")
				.param("stationId", "S01")
				.param("after", "not-a-timestamp")
				.with(jwt()))
			.andExpect(status().isBadRequest());
	}

	/** A snapshot request still resolves through the async dispatch the long poll introduced. */
	private ResultActions snapshot(MockHttpServletRequestBuilder builder) throws Exception {
		MvcResult started = mvc.perform(builder.with(jwt())).andExpect(request().asyncStarted()).andReturn();
		return mvc.perform(asyncDispatch(started));
	}

	/** The cursor a browser would echo back: the stored sampling instant of the newest reading. */
	private String storedCursor(String stationId) {
		return repository.findFirstByStationIdOrderBySampledAtDesc(stationId).orElseThrow().sampledAt.toString();
	}

	private static RealtimeSensorReading sample(String stationId, double airTemperatureC) {
		RealtimeSensorReading reading = new RealtimeSensorReading();
		reading.stationId = stationId;
		reading.sampledAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		reading.airTemperatureC = airTemperatureC;
		return reading;
	}
}

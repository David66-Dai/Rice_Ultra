package com.smartrice.server.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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
class HistoryDataFlowTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	HistoricalDailyDataRepository repository;

	@Test
	void seedImportsAllRowsAndFillsMissingModuleFields() {
		assertThat(repository.count()).isEqualTo(24_570);
		HistoricalDailyData sample = repository
			.findByStationIdAndRecordDate("S10", LocalDate.of(2026, 9, 10))
			.orElseThrow();

		assertThat(sample.avgRainfallMmH).isGreaterThanOrEqualTo(0);
		assertThat(sample.recognitionConfidencePercent).isBetween(93.0, 98.0);
		assertThat(sample.ndvi).isBetween(0.72, 0.85);
		assertThat(sample.reflectance900nmPercent).isPositive();
	}

	@Test
	void rangeAndDailyEndpointsReturnDatabaseData() throws Exception {
		mvc.perform(get("/api/history/range")
				.param("stationId", "S02")
				.with(jwt()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.stationId").value("S02"))
			.andExpect(jsonPath("$.startDate").value("2020-01-01"))
			.andExpect(jsonPath("$.recordCount").isNumber());

		mvc.perform(get("/api/history/daily")
				.param("stationId", "S02")
				.param("date", "2026-09-10")
				.with(jwt()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.current.stationId").value("S02"))
			.andExpect(jsonPath("$.current.date").value("2026-09-10"))
			.andExpect(jsonPath("$.current.environment.soilNitrogenMgKg").isNumber())
			.andExpect(jsonPath("$.current.pestDisease.riskIndex").isNumber())
			.andExpect(jsonPath("$.current.spectrum.reflectancePercent.length()").value(6))
			.andExpect(jsonPath("$.previous.date").value("2026-09-09"));
	}

	@Test
	void endpointsRejectInvalidStationAndFutureDate() throws Exception {
		mvc.perform(get("/api/history/range")
				.param("stationId", "S11")
				.with(jwt()))
			.andExpect(status().isBadRequest());

		mvc.perform(get("/api/history/daily")
				.param("stationId", "S01")
				.param("date", LocalDate.now().plusDays(1).toString())
				.with(jwt()))
			.andExpect(status().isBadRequest());
	}

	@Test
	void endpointsRequireAuthentication() throws Exception {
		mvc.perform(get("/api/history/range").param("stationId", "S01"))
			.andExpect(status().isUnauthorized());
	}
}

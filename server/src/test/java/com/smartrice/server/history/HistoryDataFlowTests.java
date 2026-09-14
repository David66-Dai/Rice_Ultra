package com.smartrice.server.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartrice.server.history.HistoryDailyResponse.EnvironmentAverages;
import com.smartrice.server.history.HistoryDailyResponse.HistoryDayData;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class HistoryDataFlowTests {

	private static final ZoneId FIELD_ZONE = ZoneId.of("Asia/Shanghai");
	private static final LocalDate DATE = LocalDate.of(2020, 1, 1);

	@Autowired MockMvc mvc;
	@MockitoBean HiveHistoryRepository repository;

	static HistoryDayData sample(LocalDate date) {
		return new HistoryDayData(date, "S01",
			new EnvironmentAverages(0.1611, 0.0, null, 4.9, 44.2,
				118.0, 21.0, 78.0, 6.2, 1.14, 7.1, 29.1),
			null, null, "hive");
	}

	@Test
	void rangeAndDailyReturnHiveEnvironmentWithoutInventingOtherArchives() throws Exception {
		when(repository.range(eq("S01"), any())).thenReturn(Optional.of(new HistoryRangeResponse("S01", DATE, DATE, 1)));
		when(repository.days("S01", DATE)).thenReturn(Map.of(DATE, sample(DATE)));
		mvc.perform(get("/api/history/range").param("stationId", "S01").with(jwt()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.startDate").value("2020-01-01"))
			.andExpect(jsonPath("$.recordCount").value(1));
		mvc.perform(get("/api/history/daily").param("stationId", "S01").param("date", "2020-01-01").with(jwt()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.current.source").value("hive"))
			.andExpect(jsonPath("$.current.environment.lightKlx").value(0.1611))
			.andExpect(jsonPath("$.current.environment.soilNitrogenPpm").value(118))
			.andExpect(jsonPath("$.current.environment.soilTemperatureC").value(7.1))
			.andExpect(jsonPath("$.current.environment.soilMoisturePercent").value(29.1))
			.andExpect(jsonPath("$.current.environment.rainfallMmH").isEmpty())
			// 2020-01-01 is not the field day, so the catalogue comes back present but without values.
			.andExpect(jsonPath("$.current.pestDisease.available").value(false))
			.andExpect(jsonPath("$.current.pestDisease.source").value("none"))
			.andExpect(jsonPath("$.current.pestDisease.items.length()").value(6))
			.andExpect(jsonPath("$.current.pestDisease.items[0].key").value("bacterial_leaf_blight"))
			.andExpect(jsonPath("$.current.pestDisease.items[0].value").isEmpty())
			.andExpect(jsonPath("$.current.pestDisease.items[3].key").value("rice_planthopper"))
			.andExpect(jsonPath("$.current.spectrum").isEmpty())
			.andExpect(jsonPath("$.previous").isEmpty());
	}

	@Test
	void returnsOnlyTheActualPreviousDay() throws Exception {
		LocalDate next = DATE.plusDays(1);
		when(repository.days("S01", next)).thenReturn(Map.of(DATE, sample(DATE), next, sample(next)));
		mvc.perform(get("/api/history/daily").param("stationId", "S01").param("date", next.toString()).with(jwt()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.previous.date").value(DATE.toString()));
	}

	@Test
	void missingHiveDataReturns404WithoutMysqlOrGeneratedFallback() throws Exception {
		when(repository.range(eq("S01"), any())).thenReturn(Optional.empty());
		when(repository.days("S01", DATE)).thenReturn(Map.of());
		mvc.perform(get("/api/history/range").param("stationId", "S01").with(jwt()))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("history_not_found"));
		mvc.perform(get("/api/history/daily").param("stationId", "S01").param("date", DATE.toString()).with(jwt()))
			.andExpect(status().isNotFound());
	}

	@Test
	void hiveFailureIsExplicitAndDoesNotExposeDriverDetails() throws Exception {
		when(repository.range(eq("S01"), any())).thenThrow(new SQLException("PASSWORD_CANARY jdbc:hive2://private-host"));
		String body = mvc.perform(get("/api/history/range").param("stationId", "S01").with(jwt()))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("hive_history_unavailable"))
			.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain("PASSWORD_CANARY", "private-host");
	}

	@Test
	void rejectsInvalidInputsBeforeQueryingAndRetainsAuthentication() throws Exception {
		mvc.perform(get("/api/history/range").param("stationId", "S11").with(jwt())).andExpect(status().isBadRequest());
		mvc.perform(get("/api/history/daily").param("stationId", "S01").param("date", "bad-date").with(jwt()))
			.andExpect(status().isBadRequest());
		mvc.perform(get("/api/history/daily").param("stationId", "S01")
				.param("date", LocalDate.now(FIELD_ZONE).plusDays(1).toString()).with(jwt()))
			.andExpect(status().isBadRequest());
		mvc.perform(get("/api/history/range").param("stationId", "S01")).andExpect(status().isUnauthorized());
		verifyNoInteractions(repository);
	}
}

package com.smartrice.server.history;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smartrice.server.history.HistoryDailyResponse.EnvironmentAverages;
import com.smartrice.server.history.HistoryDailyResponse.HistoryDayData;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HistoryDataFlowTests {

	private static final ZoneId FIELD_ZONE = ZoneId.of("Asia/Shanghai");

	private HiveHistoryRepository repository;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		repository = Objects.requireNonNull(mock(HiveHistoryRepository.class));
		var json = new ObjectMapper().registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		mvc = MockMvcBuilders.standaloneSetup(new HistoryDataController(new HistoryDataService(repository)))
			.setControllerAdvice(new HistoryExceptionHandler())
			.setMessageConverters(new MappingJackson2HttpMessageConverter(json))
			.build();
	}

	@Test
	void rangeAndDailyEndpointsReturnHiveMappedData() throws Exception {
		LocalDate date = LocalDate.of(2020, 1, 1);
		when(repository.range("S02", LocalDate.now(FIELD_ZONE)))
			.thenReturn(Optional.of(new HistoryRangeResponse("S02", date, date, 2)));
		when(repository.days("S02", date)).thenReturn(Map.of(
			date, day(date, "S02"),
			date.minusDays(1), day(date.minusDays(1), "S02")));

		mvc.perform(get("/api/history/range").param("stationId", "S02"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.stationId").value("S02"))
			.andExpect(jsonPath("$.startDate").value("2020-01-01"))
			.andExpect(jsonPath("$.recordCount").value(2));

		mvc.perform(get("/api/history/daily")
				.param("stationId", "S02")
				.param("date", "2020-01-01"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.current.stationId").value("S02"))
			.andExpect(jsonPath("$.current.date").value("2020-01-01"))
			.andExpect(jsonPath("$.current.source").value("hive"))
			.andExpect(jsonPath("$.current.environment.soilNitrogenPpm").value(118.0))
			.andExpect(jsonPath("$.previous.date").value("2019-12-31"));
	}

	@Test
	void endpointsRejectInvalidStationAndFutureDate() throws Exception {
		mvc.perform(get("/api/history/range").param("stationId", "S11"))
			.andExpect(status().isBadRequest());

		mvc.perform(get("/api/history/daily")
				.param("stationId", "S01")
				.param("date", LocalDate.now(FIELD_ZONE).plusDays(1).toString()))
			.andExpect(status().isBadRequest());
	}

	private static HistoryDayData day(LocalDate date, String station) {
		return new HistoryDayData(date, station,
			new EnvironmentAverages(0.1611, 0.0, null, 4.9, 44.2, 118.0, null, null, 6.2, 1.14, 7.1, 29.1),
			null, null, "hive");
	}
}

package com.smartrice.server.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartrice.server.config.RiceConfiguration;
import com.smartrice.server.diagnosis.InspectionDiagnosisRepository;
import com.smartrice.server.hive.HiveConnectionFactory;
import com.smartrice.server.hive.HiveProperties;
import com.smartrice.server.pest.PestDiseaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import java.time.ZoneId;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Explicit, read-only live check; no Spring context, MySQL connection or serial collector is created. */
@EnabledIfEnvironmentVariable(named = "RICE_HIVE_LIVE_TEST", matches = "true")
class HiveHistoryLiveTests {

	@Test
	void realHiveRangeAndSampleMatchTheProvidedTable() throws Exception {
		var environment = RiceConfiguration.loadEnvironment();
		HiveProperties properties = Binder.get(environment).bind("app.hive", HiveProperties.class)
			.orElseThrow(() -> new IllegalArgumentException("Missing app.hive configuration"));
		try (var connections = new HiveConnectionFactory(properties)) {
		try (var connection = connections.open(); var statement = connection.createStatement()) {
			statement.setQueryTimeout(30);
			try (var rows = statement.executeQuery("SELECT `date`, light_lux, growth_stage FROM farm.env_daily WHERE station = 'point_1' AND `date` = '2020-01-01' LIMIT 1")) {
				assertThat(rows.next()).isTrue();
				assertThat(rows.getDouble("light_lux")).isEqualTo(161.1);
			}
			System.out.println("Hive connection and read-only supplied row query verified.");
		}
		var repository = new HiveHistoryRepository(connections);
		// 2020-01-01 is not the field day, so the pest summary comes back empty by design.
		var service = new HistoryDataService(repository, new PestDiseaseService(
			org.mockito.Mockito.mock(InspectionDiagnosisRepository.class), new ObjectMapper()));
		HistoryRangeResponse range;
		try {
			range = repository.range("S01", LocalDate.now(ZoneId.of("Asia/Shanghai"))).orElseThrow();
		} catch (SQLException ex) {
			String message = String.valueOf(ex.getMessage());
			for (String secret : new String[] { properties.getUsername(), properties.getPassword() }) {
				if (secret != null && !secret.isEmpty()) message = message.replace(secret, "[redacted]");
			}
			throw new SQLException(message, ex.getSQLState(), ex.getErrorCode());
		}
		assertThat(range.recordCount()).isPositive();
		assertThat(range.endDate()).isBeforeOrEqualTo(LocalDate.now(ZoneId.of("Asia/Shanghai")));
		var sample = service.daily("S01", LocalDate.of(2020, 1, 1));
		assertThat(sample.current().environment().lightKlx()).isEqualTo(0.1611);
		assertThat(sample.current().environment().airTemperatureC()).isEqualTo(4.9);
		assertThat(sample.current().environment().soilNitrogenPpm()).isEqualTo(118.0);
		assertThat(sample.current().environment().soilTemperatureC()).isEqualTo(7.1);
		assertThat(sample.current().pestDisease().available()).isFalse();
		assertThat(sample.current().pestDisease().source()).isEqualTo(PestDiseaseService.NO_SAME_DAY_SOURCE);
		assertThat(sample.current().pestDisease().items()).hasSize(6);
		assertThat(sample.current().spectrum()).isNull();
		var latest = service.daily("S01", range.endDate());
		assertThat(latest.current().date()).isEqualTo(range.endDate());
		var json = new ObjectMapper().registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		var mvc = MockMvcBuilders.standaloneSetup(new HistoryDataController(service))
			.setControllerAdvice(new HistoryExceptionHandler())
			.setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
		mvc.perform(get("/api/history/daily").param("stationId", "S02").param("date", "2020-01-01"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.current.date").value("2020-01-01"))
			.andExpect(jsonPath("$.current.source").value("hive"))
			.andExpect(jsonPath("$.current.environment.lightKlx").value(0.2064))
			.andExpect(jsonPath("$.current.environment.soilTemperatureC").value(9.7))
			.andExpect(jsonPath("$.current.environment.soilNitrogenPpm").value(140));
		System.out.printf("Hive read-only check: S01 %s..%s, %d days; supplied sample and latest day verified.%n",
			range.startDate(), range.endDate(), range.recordCount());
		}
	}
}

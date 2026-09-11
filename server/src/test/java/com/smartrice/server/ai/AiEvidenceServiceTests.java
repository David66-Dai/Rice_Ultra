package com.smartrice.server.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AiEvidenceServiceTests {
	private static final LocalDate END = LocalDate.of(2020, 1, 7);
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2020-01-07T16:00:00Z"), ZoneOffset.UTC);

	@Test
	void statisticsUseEqualDayWeightsAndReturnCompleteCalendarAndExactEndpoints() throws Exception {
		var fixture = new HiveAiRepositoryTests.Fixture(List.of("2020-01-01", "2020-01-07", "2020-01-07"), List.of(
			Map.of("light_lux", 10.0, "wind_speed_m_s", 0.0),
			Map.of("light_lux", 40.0, "wind_speed_m_s", 0.0),
			Map.of("light_lux", 80.0, "wind_speed_m_s", 0.0)));
		AiEvidence result = new AiEvidenceService(fixture.repository, CLOCK).evidence(" s01 ", END, 7, null);
		assertThat(result.stationId()).isEqualTo("S01");
		assertThat(result.hiveStation()).isEqualTo("point_1");
		assertThat(result.growthStage()).isEqualTo("unknown");
		assertThat(result.observedDays()).isEqualTo(2);
		assertThat(result.rawRowCount()).isEqualTo(3);
		assertThat(result.daily()).hasSize(7);
		assertThat(result.missingDates()).containsExactly(END.minusDays(5), END.minusDays(4), END.minusDays(3),
			END.minusDays(2), END.minusDays(1));
		assertThat(result.daily().get(1).values().values()).containsOnlyNulls();
		var light = metric(result, "light_lux");
		assertThat(light.count()).isEqualTo(2);
		assertThat(light.missingCount()).isEqualTo(5);
		assertThat(light.mean()).isEqualTo(35.0); // (10 + (40 + 80) / 2) / 2, not raw-row mean.
		assertThat(light.first()).isEqualTo(10.0);
		assertThat(light.last()).isEqualTo(60.0);
		assertThat(light.change()).isEqualTo(50.0);
		assertThat(light.min()).isEqualTo(10.0);
		assertThat(light.max()).isEqualTo(60.0);
		assertThat(light.unit()).isEqualTo("lux");
		assertThat(metric(result, "wind_speed_m_s").mean()).isZero();
		assertThat(metric(result, "ph").mean()).isNull();
		assertThat(metric(result, "ph").missingCount()).isEqualTo(7);
	}

	@Test
	void missingBoundaryIsNotReplacedByFirstAvailableObservation() throws Exception {
		var repository = mock(HiveAiRepository.class);
		when(repository.window("point_1", END.minusDays(13), END)).thenReturn(new HiveAiRepository.WindowRows(2,
			Map.of(END.minusDays(1), Map.of("light_lux", 1.0, "ph", 6.0), END, Map.of("light_lux", 2.0))));
		var result = new AiEvidenceService(repository, CLOCK).evidence("S01", END, 14, "HEADING");
		assertThat(metric(result, "light_lux").first()).isNull();
		assertThat(metric(result, "light_lux").last()).isEqualTo(2.0);
		assertThat(metric(result, "light_lux").change()).isNull();
		assertThat(metric(result, "ph").last()).isNull();
		assertThat(metric(result, "ph").change()).isNull();
		assertThat(metric(result, "ph").count()).isEqualTo(1);
		assertThat(metric(result, "ph").missingCount()).isEqualTo(13);
		assertThat(result.growthStage()).isEqualTo("heading");
		assertThat(result.limitations()).anyMatch(text -> text.contains("用户选择"));
	}

	@Test
	void rejectsMissingTargetAndAllInvalidTargetEvenWhenEarlierDaysHaveData() throws Exception {
		var repository = mock(HiveAiRepository.class);
		when(repository.window(anyString(), any(), any())).thenReturn(new HiveAiRepository.WindowRows(1,
			Map.of(END.minusDays(1), Map.of("light_lux", 100.0))));
		var service = new AiEvidenceService(repository, CLOCK);
		assertStatus(() -> service.evidence("S01", END, 7, null), 404);
		when(repository.window(anyString(), any(), any())).thenReturn(new HiveAiRepository.WindowRows(2,
			Map.of(END.minusDays(1), Map.of("light_lux", 100.0), END, Map.of("light_lux", Double.NaN))));
		assertStatus(() -> service.evidence("S01", END, 7, null), 422);
	}

	@Test
	void validatesWindowStationStageAndDateBeforeCallingHive() {
		var repository = mock(HiveAiRepository.class);
		var service = new AiEvidenceService(repository, CLOCK);
		assertStatus(() -> service.evidence("S11", END, 7, null), 400);
		assertStatus(() -> service.evidence("S01' OR 1=1", END, 7, null), 400);
		assertStatus(() -> service.evidence("S01", END, 31, null), 400);
		assertStatus(() -> service.evidence("S01", END, 0, null), 400);
		assertStatus(() -> service.evidence("S01", null, 7, null), 400);
		assertStatus(() -> service.evidence("S01", END.plusDays(2), 7, null), 400);
		assertStatus(() -> service.evidence("S01", LocalDate.MIN, 30, null), 400);
		assertStatus(() -> service.evidence("S01", END, 7, "arbitrary prompt"), 400);
		verifyNoInteractions(repository);
	}

	@Test
	void acceptsBeijingTodayAndThirtyDayWindowWithoutInferringGrowthStage() throws Exception {
		LocalDate beijingToday = END.plusDays(1);
		var repository = mock(HiveAiRepository.class);
		when(repository.window("point_10", beijingToday.minusDays(29), beijingToday))
			.thenReturn(new HiveAiRepository.WindowRows(1, Map.of(beijingToday, Map.of("wind_speed_m_s", 0.0))));
		var result = new AiEvidenceService(repository, CLOCK).evidence("s10", beijingToday, 30, null);
		assertThat(result.windowDays()).isEqualTo(30);
		assertThat(result.growthStage()).isEqualTo("unknown");
		assertThat(result.limitations()).anyMatch(text -> text.contains("生育期未知"));
		verify(repository).window("point_10", beijingToday.minusDays(29), beijingToday);
	}

	@Test
	void interruptionStopsBeforeStartingLegacyQueries() throws Exception {
		var repository = mock(HiveAiRepository.class);
		var legacy = mock(HiveLegacyAiRepository.class);
		when(repository.window(anyString(), any(), any())).thenReturn(new HiveAiRepository.WindowRows(1,
			Map.of(END, Map.of("light_lux", 100.0))));
		var service = new AiEvidenceService(repository, legacy, CLOCK);
		Thread.currentThread().interrupt();
		try {
			assertStatus(() -> service.evidence("S01", END, 7, null), 503);
			verifyNoInteractions(legacy);
		} finally {
			Thread.interrupted();
		}
	}

	@Test
	void hidesSqlDiagnosticsAndReturnsBoundedDataErrors() throws Exception {
		var repository = mock(HiveAiRepository.class);
		when(repository.window(anyString(), any(), any())).thenThrow(new SQLException("password=fixture-secret"));
		var service = new AiEvidenceService(repository, CLOCK);
		assertThatThrownBy(() -> service.evidence("S01", END, 7, null))
			.isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
				assertThat(ex.getStatusCode().value()).isEqualTo(503);
				assertThat(ex.getMessage()).doesNotContain("fixture-secret", "password");
				assertThat(ex.getCause()).isNull();
			});
		doThrow(new HiveAiRepository.RowLimitException()).when(repository).window(anyString(), any(), any());
		assertStatus(() -> service.evidence("S01", END, 7, null), 422);
	}

	private static AiEvidence.Metric metric(AiEvidence evidence, String field) {
		return evidence.metrics().stream().filter(metric -> metric.field().equals(field)).findFirst().orElseThrow();
	}

	private static void assertStatus(Runnable task, int status) {
		assertThatThrownBy(task::run).isInstanceOfSatisfying(ResponseStatusException.class,
			ex -> assertThat(ex.getStatusCode().value()).isEqualTo(status));
	}
}

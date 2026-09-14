package com.smartrice.server.astrbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import com.smartrice.server.auth.UserAccountRepository;
import com.smartrice.server.realtime.DeviceActivityService;
import com.smartrice.server.realtime.DeviceControlResponse;
import com.smartrice.server.realtime.DeviceState;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AstrBotIntegrationServiceTests {

	private final AstrBotControlProperties properties = new AstrBotControlProperties();
	private final UserAccountRepository users = mock(UserAccountRepository.class);
	private final DeviceActivityService devices = mock(DeviceActivityService.class);
	private final AstrBotScheduledStopRepository stops = mock(AstrBotScheduledStopRepository.class);
	private final AstrBotAlertSender alerts = mock(AstrBotAlertSender.class);
	private final AstrBotIntegrationService service = new AstrBotIntegrationService(properties, users, devices, stops, alerts);

	@AfterEach
	void closeTimer() {
		service.close();
	}

	@Test
	void startupImmediatelyCompensatesOutstandingStopAndMarksItComplete() {
		AstrBotScheduledStop stop = scheduled();
		when(stops.findByStatusIn(any())).thenReturn(List.of(stop));
		when(devices.automaticStopAfterRestart("operator", "操作员", "S01", "pump", 1L))
			.thenReturn(new DeviceControlResponse("S01", "pump", false, "FA03", Instant.now(),
				new DeviceState("S01", "pump", false, 1, Instant.now(), "operator")));

		service.recoverScheduledStops();

		verify(devices, timeout(3000)).automaticStopAfterRestart("operator", "操作员", "S01", "pump", 1L);
		verify(alerts, timeout(3000)).sendDeviceFeedback(eq("S01"), eq("pump"), eq(false),
			eq("自动关闭"), anyString());
		verify(stops, timeout(3000)).saveAndFlush(stop);
		assertThat(stop.getStatus()).isEqualTo("COMPLETED");
	}

	@Test
	void newerCommandSupersedesRecoveredTimerWithoutSendingAnotherStop() {
		AstrBotScheduledStop stop = scheduled();
		when(stops.findByStatusIn(any())).thenReturn(List.of(stop));
		when(devices.automaticStopAfterRestart("operator", "操作员", "S01", "pump", 1L))
			.thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "newer command"));

		service.recoverScheduledStops();

		verify(stops, timeout(3000)).saveAndFlush(stop);
		assertThat(stop.getStatus()).isEqualTo("SUPERSEDED");
	}

	private AstrBotScheduledStop scheduled() {
		return new AstrBotScheduledStop("00000000-0000-0000-0000-000000000001", "operator", "操作员",
			"S01", "pump", 1L, Instant.now().plusSeconds(30), "SCHEDULED", "fingerprint");
	}
}

package com.smartrice.server.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartrice.server.auth.UserAccount;
import com.smartrice.server.auth.UserAccountRepository;
import com.smartrice.server.astrbot.AstrBotAlertSender;
import com.smartrice.server.diagnosis.InspectionDiagnosisFixtures;
import com.smartrice.server.diagnosis.InspectionDiagnosisRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PreventionSafetyFlowTests {

	@Autowired MockMvc mvc;
	@Autowired ObjectMapper json;
	@Autowired UserAccountRepository users;
	@Autowired DevicesProperties permissions;
	@Autowired PreventionControlProperties properties;
	@Autowired PreventionPolicyRepository policies;
	@Autowired RealtimeSensorReadingRepository readings;
	@Autowired RealtimeSensorIngestionService ingestion;
	@Autowired InspectionDiagnosisRepository diagnoses;
	@Autowired StationDeviceRepository devices;
	@MockitoBean DeviceActuator actuator;
	@MockitoBean AstrBotAlertSender alerts;
	private UserAccount operator;

	@BeforeEach
	void setUp() {
		reset(actuator, alerts);
		when(actuator.isAvailable()).thenReturn(true);
		devices.deleteAll();
		readings.deleteAll();
		diagnoses.deleteAll();
		policies.deleteAll();
		users.deleteAll();
		operator = new UserAccount();
		operator.setUsername("operator");
		operator.setDisplayName("操作员");
		operator.setPasswordHash("{noop}offline");
		operator.setRole("ADMIN");
		operator = users.saveAndFlush(operator);
		permissions.setControlUsers(List.of("operator"));
		properties.setSpraySafetyEnabled(true);
		properties.setMaxSprayWindSpeedMs(3.0);
		properties.setSensorMaxAge(java.time.Duration.ofMinutes(2));
		properties.setLeafEvidenceMaxAge(java.time.Duration.ofHours(24));
		properties.setRequireAstrBotConfirmationDefault(true);
	}

	@Test
	void manualSprayRequiresRedLeafEvidenceAndHighWindStopsRunningPump() throws Exception {
		readings.saveAndFlush(reading(2.0, Instant.now()));
		mvc.perform(control(true)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.message").value("缺少叶害识别信息，人工喷药被拒绝"));

		diagnoses.saveAndFlush(InspectionDiagnosisFixtures.row("S01", "leaf", "Brown Spot", "褐斑病",
			0, "red", "{}", Instant.now()));
		mvc.perform(control(true)).andExpect(status().isOk());
		verify(actuator).sendCommand(0x01);

		ingestion.save(reading(3.1, Instant.now().plusMillis(1)));
		verify(actuator).sendCommand(0x03);
		StationDevice pump = devices.findByStationIdAndDevice("S01", "pump").orElseThrow();
		assertThat(pump.isEnabled()).isFalse();
		assertThat(pump.getUpdatedBy()).isEqualTo("wind_safety");
		verify(alerts).sendDeviceFeedback(eq("S01"), eq("pump"), eq(false),
			eq("风速联锁关闭"), anyString());
	}

	@Test
	void sprayStartFailsClosedForStaleOrExcessiveWind() throws Exception {
		diagnoses.saveAndFlush(InspectionDiagnosisFixtures.row("S01", "leaf", "Brown Spot", "褐斑病",
			0, "red", "{}", Instant.now()));
		readings.saveAndFlush(reading(2.0, Instant.now().minusSeconds(121)));
		mvc.perform(control(true)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.message").value("实时风速已过期，禁止开启喷药"));
		readings.deleteAll();
		readings.saveAndFlush(reading(3.01, Instant.now()));
		mvc.perform(control(true)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("超过喷药上限")));
	}

	@Test
	void authorizedOperatorCanToggleConfirmationPolicyAndAllSnapshotsSeeIt() throws Exception {
		JsonNode initial = snapshot();
		assertThat(initial.path("preventionPolicy").path("requireAstrBotConfirmation").asBoolean()).isTrue();
		mvc.perform(post("/api/devices/prevention-policy").with(jwt())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"requireAstrBotConfirmation\":false,\"expectedRevision\":0}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.requireAstrBotConfirmation").value(false))
			.andExpect(jsonPath("$.revision").value(1));
		assertThat(snapshot().path("preventionPolicy").path("requireAstrBotConfirmation").asBoolean()).isFalse();
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder control(boolean enabled) {
		return post("/api/devices/control").with(jwt()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"stationId\":\"S01\",\"device\":\"pump\",\"enabled\":" + enabled + "}");
	}

	private RequestPostProcessor jwt() {
		return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
			.jwt(token -> token.claim("uid", operator.getId()));
	}

	private JsonNode snapshot() throws Exception {
		MvcResult pending = mvc.perform(get("/api/devices/sync").param("waitSeconds", "0").with(jwt()))
			.andExpect(request().asyncStarted()).andReturn();
		return json.readTree(mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
	}

	private RealtimeSensorReading reading(double wind, Instant sampledAt) {
		RealtimeSensorReading reading = new RealtimeSensorReading();
		reading.stationId = "S01";
		reading.sampledAt = sampledAt;
		reading.windSpeedMs = wind;
		return reading;
	}
}

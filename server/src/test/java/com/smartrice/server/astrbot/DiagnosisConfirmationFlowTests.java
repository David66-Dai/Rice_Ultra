package com.smartrice.server.astrbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartrice.server.auth.UserAccount;
import com.smartrice.server.auth.UserAccountRepository;
import com.smartrice.server.diagnosis.InferenceClient;
import com.smartrice.server.realtime.DeviceActuator;
import com.smartrice.server.realtime.DevicesProperties;
import com.smartrice.server.realtime.PreventionControlProperties;
import com.smartrice.server.realtime.PreventionPolicyRepository;
import com.smartrice.server.realtime.StationDeviceRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DiagnosisConfirmationFlowTests {

	private static final String TOKEN = "offline-diagnosis-confirmation-token-123456789";
	@Autowired MockMvc mvc;
	@Autowired ObjectMapper json;
	@Autowired UserAccountRepository users;
	@Autowired DevicesProperties permissions;
	@Autowired AstrBotControlProperties astrBot;
	@Autowired PreventionControlProperties prevention;
	@Autowired PreventionPolicyRepository policies;
	@Autowired AstrBotDiagnosisConfirmationRepository confirmations;
	@Autowired AstrBotScheduledStopRepository scheduledStops;
	@Autowired StationDeviceRepository devices;
	@MockitoBean DeviceActuator actuator;
	@MockitoBean InferenceClient inference;
	@MockitoBean AstrBotAlertSender alertSender;
	private UserAccount operator;

	@BeforeEach
	void setUp() {
		reset(actuator, inference, alertSender);
		when(actuator.isAvailable()).thenReturn(true);
		confirmations.deleteAll();
		scheduledStops.deleteAll();
		policies.deleteAll();
		devices.deleteAll();
		users.deleteAll();
		operator = new UserAccount();
		operator.setUsername("operator");
		operator.setDisplayName("微信操作员");
		operator.setPasswordHash("{noop}offline");
		operator.setRole("USER");
		operator = users.saveAndFlush(operator);
		permissions.setControlUsers(List.of("operator"));
		astrBot.setEnabled(true);
		astrBot.setApiToken(TOKEN);
		astrBot.setIdentities(List.of(binding()));
		prevention.setRequireAstrBotConfirmationDefault(true);
		prevention.setSpraySafetyEnabled(false);
		prevention.setAlertUmos(List.of("umo:test"));
		when(inference.predict(eq("leaf"), any())).thenReturn(Map.of(
			"task", "leaf", "label", "Brown Spot", "label_zh", "褐斑病",
			"confidence", 0.95, "has_leaf_damage", true));
	}

	@Test
	void redDiagnosisWaitsForDeliveredWeChatConfirmationBeforeOneDeviceWrite() throws Exception {
		String diagnosisJson = mvc.perform(multipart("/api/diagnosis/leaf")
				.file(new MockMultipartFile("file", "leaf.jpg", "image/jpeg", new byte[] {1, 2, 3}))
				.param("stationId", "S01")
				.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
					.jwt(token -> token.claim("uid", operator.getId()))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.confirmationRequired").value(true))
			.andExpect(jsonPath("$.pendingConfirmationId").isNotEmpty())
			.andExpect(jsonPath("$.activatedDevice").value(nullValue()))
			.andReturn().getResponse().getContentAsString();
		verify(actuator, never()).sendCommand(anyInt());
		JsonNode diagnosis = json.readTree(diagnosisJson);
		String id = diagnosis.path("pendingConfirmationId").asText();
		verify(alertSender).sendAfterCommit(id);

		AstrBotDiagnosisConfirmation row = confirmations.findById(id).orElseThrow();
		row.setDeliveryStatus("SENT");
		confirmations.saveAndFlush(row);
		String implicitBody = json.writeValueAsString(Map.of(
			"identity", Map.of("umo", "umo:test", "senderId", "sender-1"),
			"confirmationId", id, "originalText", "不要确认这条告警"));
		mvc.perform(post("/api/astrbot/diagnosis/confirm").header("X-AstrBot-Token", TOKEN)
				.contentType(MediaType.APPLICATION_JSON).content(implicitBody))
			.andExpect(status().isBadRequest());
		verify(actuator, never()).sendCommand(anyInt());
		String body = json.writeValueAsString(Map.of(
			"identity", Map.of("umo", "umo:test", "senderId", "sender-1"),
			"confirmationId", id,
			"originalText", "/agri_confirm_alert " + id));
		mvc.perform(post("/api/astrbot/diagnosis/confirm").header("X-AstrBot-Token", TOKEN)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CONFIRMED"))
			.andExpect(jsonPath("$.autoOffAt").isNotEmpty())
			.andExpect(jsonPath("$.control.device").value("pump"))
			.andExpect(jsonPath("$.control.state.enabled").value(true));
		mvc.perform(post("/api/astrbot/diagnosis/confirm").header("X-AstrBot-Token", TOKEN)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CONFIRMED"))
			.andExpect(jsonPath("$.control").value(nullValue()));
		verify(actuator, times(1)).sendCommand(0x01);
		assertThat(confirmations.findById(id).orElseThrow().getConfirmedBy()).isEqualTo("operator");
		assertThat(scheduledStops.findById(id).orElseThrow().getStatus()).isEqualTo("SCHEDULED");
	}

	private AstrBotControlProperties.IdentityBinding binding() {
		var binding = new AstrBotControlProperties.IdentityBinding();
		binding.setUmo("umo:test");
		binding.setSenderId("sender-1");
		binding.setUsername("operator");
		return binding;
	}
}

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
	private static final String QQ_GROUP_UMO = "aiocqhttp:GroupMessage:123456789";
	private static final String QQ_SENDER_ID = "987654321";
	private static final String WECHAT_UMO = "wx:FriendMessage:offline-wechat-session";
	private static final String WECHAT_SENDER_ID = "offline-wechat-sender";
	@Autowired MockMvc mvc;
	@Autowired ObjectMapper json;
	@Autowired UserAccountRepository users;
	@Autowired DevicesProperties permissions;
	@Autowired AstrBotControlProperties astrBot;
	@Autowired PreventionControlProperties prevention;
	@Autowired PreventionPolicyRepository policies;
	@Autowired AstrBotDiagnosisConfirmationRepository confirmations;
	@Autowired AstrBotDiagnosisConfirmationTargetRepository confirmationTargets;
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
		confirmationTargets.deleteAll();
		confirmations.deleteAll();
		scheduledStops.deleteAll();
		policies.deleteAll();
		devices.deleteAll();
		users.deleteAll();
		operator = new UserAccount();
		operator.setUsername("operator");
		operator.setDisplayName("AstrBot 操作员");
		operator.setPasswordHash("{noop}offline");
		operator.setRole("USER");
		operator = users.saveAndFlush(operator);
		permissions.setControlUsers(List.of("operator"));
		astrBot.setEnabled(true);
		astrBot.setApiToken(TOKEN);
		astrBot.setIdentities(List.of(binding(QQ_GROUP_UMO, QQ_SENDER_ID), binding(WECHAT_UMO, WECHAT_SENDER_ID)));
		prevention.setRequireAstrBotConfirmationDefault(true);
		prevention.setSpraySafetyEnabled(false);
		// Both platforms are configured targets; only the one that actually received may confirm.
		prevention.setAlertUmos(List.of(QQ_GROUP_UMO, WECHAT_UMO));
		when(inference.predict(eq("leaf"), any())).thenReturn(Map.of(
			"task", "leaf", "label", "Brown Spot", "label_zh", "褐斑病",
			"confidence", 0.95, "has_leaf_damage", true));
	}

	@Test
	void redDiagnosisWaitsForDeliveredAstrBotConfirmationBeforeOneDeviceWrite() throws Exception {
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

		// Only QQ received the alert; WeChat is down for this confirmation.
		markDelivered(id, QQ_GROUP_UMO);
		String implicitBody = json.writeValueAsString(Map.of(
			"identity", Map.of("umo", QQ_GROUP_UMO, "senderId", QQ_SENDER_ID),
			"confirmationId", id, "originalText", "不要确认这条告警"));
		mvc.perform(post("/api/astrbot/diagnosis/confirm").header("X-AstrBot-Token", TOKEN)
				.contentType(MediaType.APPLICATION_JSON).content(implicitBody))
			.andExpect(status().isBadRequest());
		verify(actuator, never()).sendCommand(anyInt());
		String body = json.writeValueAsString(Map.of(
			"identity", Map.of("umo", QQ_GROUP_UMO, "senderId", QQ_SENDER_ID),
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

	@Test
	void onlyTheSessionThatActuallyReceivedTheAlertMayReleaseTheDevice() throws Exception {
		String id = redDiagnosis();
		// WeChat delivery failed, QQ succeeded: WeChat must not be able to release the device,
		// and the QQ session that did receive the alert still can.
		markDelivered(id, QQ_GROUP_UMO);
		markFailed(id, WECHAT_UMO);

		mvc.perform(post("/api/astrbot/diagnosis/confirm").header("X-AstrBot-Token", TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content(confirmBody(WECHAT_UMO, WECHAT_SENDER_ID, id)))
			.andExpect(status().isConflict());
		verify(actuator, never()).sendCommand(anyInt());

		mvc.perform(post("/api/astrbot/diagnosis/confirm").header("X-AstrBot-Token", TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content(confirmBody(QQ_GROUP_UMO, QQ_SENDER_ID, id)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CONFIRMED"));
		verify(actuator, times(1)).sendCommand(0x01);
	}

	@Test
	void anUndeliveredConfirmationNeverStartsTheDeviceFromAnySession() throws Exception {
		String id = redDiagnosis();

		for (String[] session : new String[][] {
			{QQ_GROUP_UMO, QQ_SENDER_ID}, {WECHAT_UMO, WECHAT_SENDER_ID}}) {
			mvc.perform(post("/api/astrbot/diagnosis/confirm").header("X-AstrBot-Token", TOKEN)
					.contentType(MediaType.APPLICATION_JSON)
					.content(confirmBody(session[0], session[1], id)))
				.andExpect(status().isConflict());
		}
		verify(actuator, never()).sendCommand(anyInt());
		assertThat(confirmations.findById(id).orElseThrow().getStatus()).isEqualTo("PENDING");
	}

	@Test
	void qqConfirmationRequiresExactConfiguredUmoAndSenderIdentity() throws Exception {
		String body = json.writeValueAsString(Map.of(
			"identity", Map.of("umo", QQ_GROUP_UMO, "senderId", "other-qq-user"),
			"confirmationId", "00000000-0000-4000-8000-000000000099",
			"originalText", "/agri_confirm_alert 00000000-0000-4000-8000-000000000099"));

		mvc.perform(post("/api/astrbot/diagnosis/confirm").header("X-AstrBot-Token", TOKEN)
				.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isForbidden());
		verify(actuator, never()).sendCommand(anyInt());
	}

	private AstrBotControlProperties.IdentityBinding binding(String umo, String senderId) {
		var binding = new AstrBotControlProperties.IdentityBinding();
		binding.setUmo(umo);
		binding.setSenderId(senderId);
		binding.setUsername("operator");
		return binding;
	}

	/** Runs one red leaf diagnosis and returns its pending confirmation id. */
	private String redDiagnosis() throws Exception {
		String body = mvc.perform(multipart("/api/diagnosis/leaf")
				.file(new MockMultipartFile("file", "leaf.jpg", "image/jpeg", new byte[] {1, 2, 3}))
				.param("stationId", "S01")
				.with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
					.jwt(token -> token.claim("uid", operator.getId()))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.confirmationRequired").value(true))
			.andReturn().getResponse().getContentAsString();
		return json.readTree(body).path("pendingConfirmationId").asText();
	}

	private void markDelivered(String confirmationId, String umo) {
		AstrBotDiagnosisConfirmationTarget target = new AstrBotDiagnosisConfirmationTarget(confirmationId, umo);
		target.setDeliveryStatus("SENT");
		target.setAttempts(1);
		target.setSentAt(java.time.Instant.now());
		confirmationTargets.saveAndFlush(target);
	}

	private void markFailed(String confirmationId, String umo) {
		AstrBotDiagnosisConfirmationTarget target = new AstrBotDiagnosisConfirmationTarget(confirmationId, umo);
		target.setDeliveryStatus("FAILED");
		target.setAttempts(1);
		target.setLastError("offline: session unreachable");
		confirmationTargets.saveAndFlush(target);
	}

	private String confirmBody(String umo, String senderId, String confirmationId) throws Exception {
		return json.writeValueAsString(Map.of(
			"identity", Map.of("umo", umo, "senderId", senderId),
			"confirmationId", confirmationId,
			"originalText", "/agri_confirm_alert " + confirmationId));
	}
}

package com.smartrice.server.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartrice.server.auth.JwtService;
import com.smartrice.server.auth.UserAccount;
import com.smartrice.server.auth.UserAccountRepository;
import com.smartrice.server.notifications.NotificationEventRepository;
import com.smartrice.server.notifications.NotificationReadStateRepository;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** All device writes target a Mockito mock; test bootstrap forces isolated H2 and disables COM. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DeviceActivityFlowTests {

	@Autowired MockMvc mvc;
	@Autowired ObjectMapper json;
	@Autowired UserAccountRepository users;
	@Autowired NotificationEventRepository events;
	@Autowired NotificationReadStateRepository reads;
	@Autowired DevicesProperties permissions;
	@Autowired DeviceActivityService activity;
	@Autowired JwtService jwtService;
	@MockitoBean SerialSensorCollector serial;
	private UserAccount operator;
	private UserAccount secondOperator;
	private UserAccount viewer;
	private String operatorToken;
	private String secondToken;
	private String viewerToken;

	@BeforeEach
	void setUp() {
		reset(serial);
		when(serial.isConnected()).thenReturn(true);
		reads.deleteAll();
		events.deleteAll();
		users.deleteAll();
		operator = account("operator", "操作员甲");
		secondOperator = account("second", "操作员乙");
		viewer = account("viewer", "观察员");
		permissions.setControlUsers(List.of("operator", "second"));
		operatorToken = jwtService.issue(operator).token();
		secondToken = jwtService.issue(secondOperator).token();
		viewerToken = jwtService.issue(viewer).token();
	}

	@Test
	void defaultEmptyAllowlistDeniesEvenAdminAndMissingAuthentication() throws Exception {
		permissions.setControlUsers(List.of());
		JsonNode snapshot = snapshot(operatorToken);
		assertThat(snapshot.path("canControl").asBoolean()).isFalse();
		mvc.perform(controlRequest(operatorToken, command(snapshot)))
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("device_forbidden"));
		mvc.perform(post("/api/devices/control").contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(command(snapshot))))
			.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/devices/sync")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/notifications/read").contentType(MediaType.APPLICATION_JSON).content("{\"throughId\":0}"))
			.andExpect(status().isUnauthorized());
		verify(serial, never()).sendCommand(anyInt());
	}

	@Test
	void allowedActorIsRecordedAndBroadcastToReadOnlyViewer() throws Exception {
		JsonNode before = snapshot(operatorToken);
		Map<String, Object> payload = command(before);
		JsonNode response = json.readTree(mvc.perform(controlRequest(operatorToken, payload))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		assertThat(response.path("state").path("revision").asLong())
			.isEqualTo(before.path("devices").get(0).path("revision").asLong() + 1);
		JsonNode viewerSnapshot = snapshot(viewerToken);
		assertThat(viewerSnapshot.path("canControl").asBoolean()).isFalse();
		assertThat(viewerSnapshot.path("devices").get(0).path("enabled").asBoolean()).isEqualTo(payload.get("enabled"));
		assertThat(viewerSnapshot.path("notifications").get(0).path("message").asText()).contains("操作员甲（operator）用户", "智能灌溉水泵功能");
		assertThat(viewerSnapshot.path("notifications").get(0).path("actorUsername").asText()).isEqualTo("operator");
		assertThat(viewerSnapshot.path("unreadCount").asInt()).isEqualTo(1);
		verify(serial).sendCommand(Boolean.TRUE.equals(payload.get("enabled")) ? 0x01 : 0x03);
	}

	@Test
	void twoConcurrentControllersWithSameRevisionWriteOnlyOnce() throws Exception {
		Map<String, Object> payload = command(snapshot(operatorToken));
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> {
				start.await();
				return mvc.perform(controlRequest(operatorToken, payload)).andReturn().getResponse().getStatus();
			});
			var second = executor.submit(() -> {
				start.await();
				return mvc.perform(controlRequest(secondToken, payload)).andReturn().getResponse().getStatus();
			});
			start.countDown();
			assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder(200, 409);
		}
		verify(serial, times(1)).sendCommand(anyInt());
		assertThat(events.count()).isEqualTo(1);
	}

	@Test
	void explicitReassertionWithLatestRevisionWritesAgainButStaleRepeatDoesNot() throws Exception {
		Map<String, Object> payload = command(snapshot(operatorToken));
		mvc.perform(controlRequest(operatorToken, payload)).andExpect(status().isOk());
		JsonNode state = snapshot(operatorToken).path("devices").get(0);
		mvc.perform(controlRequest(operatorToken, Map.of("stationId", "S01", "device", "pump",
			"enabled", state.path("enabled").asBoolean(), "expectedRevision", state.path("revision").asLong())))
			.andExpect(status().isOk());
		mvc.perform(controlRequest(operatorToken, payload)).andExpect(status().isConflict());
		verify(serial, times(2)).sendCommand(anyInt());
		assertThat(events.count()).isEqualTo(2);
	}

	@Test
	void failedWriteRollsBackNotificationAndBroadcastsUnknownState() throws Exception {
		JsonNode before = snapshot(operatorToken);
		doThrow(new IllegalStateException("mock partial write")).when(serial).sendCommand(anyInt());
		mvc.perform(controlRequest(operatorToken, command(before))).andExpect(status().isServiceUnavailable());
		JsonNode after = snapshot(viewerToken);
		assertThat(after.path("notifications")).isEmpty();
		assertThat(after.path("devices").get(0).path("enabled").isNull()).isTrue();
		assertThat(after.path("devices").get(0).path("revision").asLong())
			.isEqualTo(before.path("devices").get(0).path("revision").asLong() + 1);
		assertThat(events.count()).isZero();
	}

	@Test
	void oneControlWakesTwoAuthenticatedLongPollingClients() throws Exception {
		JsonNode before = snapshot(operatorToken);
		String cursor = before.path("cursor").asText();
		MvcResult first = pending(operatorToken, cursor);
		MvcResult second = pending(viewerToken, cursor);
		assertThat(first.getRequest().isAsyncStarted()).isTrue();
		mvc.perform(controlRequest(operatorToken, command(before))).andExpect(status().isOk());
		for (MvcResult waiting : List.of(first, second)) {
			waiting.getAsyncResult(3000);
			mvc.perform(asyncDispatch(waiting)).andExpect(status().isOk())
				.andExpect(jsonPath("$.notifications.length()").value(1))
				.andExpect(jsonPath("$.unreadCount").value(1));
		}
	}

	@Test
	void accountDisabledOrDeletedAfterTokenIssuanceCannotControlSyncOrRead() throws Exception {
		Map<String, Object> payload = command(snapshot(operatorToken));
		operator.setEnabled(false);
		users.saveAndFlush(operator);
		mvc.perform(controlRequest(operatorToken, payload)).andExpect(status().isForbidden());
		mvc.perform(get("/api/devices/sync").header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
			.andExpect(status().isForbidden());
		mvc.perform(readRequest(operatorToken, 0)).andExpect(status().isForbidden());
		users.delete(operator);
		mvc.perform(controlRequest(operatorToken, payload)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/devices/sync").header(HttpHeaders.AUTHORIZATION, "Bearer " + operatorToken))
			.andExpect(status().isUnauthorized());
		mvc.perform(readRequest(operatorToken, 0)).andExpect(status().isUnauthorized());
		verify(serial, never()).sendCommand(anyInt());
	}

	@Test
	void waitingClientRechecksAccountBeforeDeliveringBroadcast() throws Exception {
		MvcResult waiting = pending(viewerToken, snapshot(viewerToken).path("cursor").asText());
		viewer.setEnabled(false);
		users.saveAndFlush(viewer);
		activity.publishPestDisease("S01", "测试病虫害识别通知");
		waiting.getAsyncResult(3000);
		mvc.perform(asyncDispatch(waiting)).andExpect(status().isForbidden());
	}

	@Test
	void disconnectedSerialStillHasAuthorizedEndpointAndViewerPermissions() throws Exception {
		when(serial.isConnected()).thenReturn(false);
		JsonNode before = snapshot(operatorToken);
		assertThat(before.path("available").asBoolean()).isFalse();
		mvc.perform(controlRequest(operatorToken, command(before))).andExpect(status().isServiceUnavailable());
		mvc.perform(controlRequest(viewerToken, command(before))).andExpect(status().isForbidden());
		verify(serial, never()).sendCommand(anyInt());
	}

	@Test
	void readCursorIsPersistentPerUserAndCannotReadFutureNotifications() throws Exception {
		long first = activity.publishPestDisease("S01", "检测到待确认病害").id();
		MvcResult ownTab = pending(operatorToken, snapshot(operatorToken).path("cursor").asText());
		mvc.perform(readRequest(operatorToken, Long.MAX_VALUE)).andExpect(status().isOk())
			.andExpect(jsonPath("$.unreadCount").value(0));
		ownTab.getAsyncResult(3000);
		mvc.perform(asyncDispatch(ownTab)).andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(0));
		assertThat(reads.findById(operator.getId()).orElseThrow().getThroughId()).isEqualTo(first);
		assertThat(snapshot(viewerToken).path("unreadCount").asInt()).isEqualTo(1);
		activity.publishPestDisease(null, "新的识别任务完成");
		assertThat(snapshot(operatorToken).path("unreadCount").asInt()).isEqualTo(1);
		mvc.perform(readRequest(operatorToken, 0)).andExpect(status().isOk()).andExpect(jsonPath("$.unreadCount").value(1));
	}

	@Test
	void diagnosisExtensionIsBroadcastAndHistoryLimitDoesNotTruncateUnreadCount() throws Exception {
		for (int i = 0; i < 103; i++) {
			activity.publishPestDisease("S02", "识别结果 " + i);
		}
		JsonNode snapshot = snapshot(viewerToken);
		assertThat(snapshot.path("notifications")).hasSize(100);
		assertThat(snapshot.path("unreadCount").asInt()).isEqualTo(103);
		assertThat(snapshot.path("notifications").get(0).path("message").asText()).isEqualTo("识别结果 102");
		assertThat(snapshot.path("notifications").get(0).path("type").asText()).isEqualTo("pest_disease");
		assertThat(snapshot.path("notifications").get(0).path("device").isNull()).isTrue();
		mvc.perform(post("/api/notifications/publish").header(HttpHeaders.AUTHORIZATION, "Bearer " + viewerToken)
			.contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isNotFound());
		verify(serial, never()).sendCommand(anyInt());
	}

	@Test
	void missingRevisionOrEnabledIsRejectedBeforeAnyDeviceWrite() throws Exception {
		mvc.perform(controlRequest(operatorToken, Map.of("stationId", "S01", "device", "pump", "enabled", true)))
			.andExpect(status().isBadRequest());
		mvc.perform(controlRequest(operatorToken, Map.of("stationId", "S01", "device", "pump", "expectedRevision", 0)))
			.andExpect(status().isBadRequest());
		verify(serial, never()).sendCommand(anyInt());
	}

	private UserAccount account(String username, String displayName) {
		UserAccount account = new UserAccount();
		account.setUsername(username);
		account.setDisplayName(displayName);
		account.setPasswordHash("{noop}test-only");
		account.setRole("ADMIN");
		return users.saveAndFlush(account);
	}

	private JsonNode snapshot(String token) throws Exception {
		MvcResult result = mvc.perform(get("/api/devices/sync").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(request().asyncStarted()).andReturn();
		return json.readTree(mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
	}

	private MvcResult pending(String token, String cursor) throws Exception {
		return mvc.perform(get("/api/devices/sync").param("after", cursor).param("waitSeconds", "25")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(request().asyncStarted()).andReturn();
	}

	private Map<String, Object> command(JsonNode snapshot) {
		JsonNode state = snapshot.path("devices").get(0);
		return Map.of("stationId", "S01", "device", "pump", "enabled", !state.path("enabled").asBoolean(),
			"expectedRevision", state.path("revision").asLong());
	}

	private MockHttpServletRequestBuilder controlRequest(String token, Map<String, Object> payload) throws Exception {
		return post("/api/devices/control").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
	}

	private MockHttpServletRequestBuilder readRequest(String token, long throughId) {
		return post("/api/notifications/read").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON).content("{\"throughId\":" + throughId + "}");
	}
}

package com.smartrice.server.astrbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
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
import com.smartrice.server.realtime.DevicesProperties;
import com.smartrice.server.realtime.SerialSensorCollector;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/** Uses H2 and a mocked collector. No COM port or external service is touched. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AstrBotControlFlowTests {

	private static final String TOKEN = "offline-astrbot-integration-token-1234567890";
	@Autowired MockMvc mvc;
	@Autowired ObjectMapper json;
	@Autowired UserAccountRepository users;
	@Autowired NotificationEventRepository events;
	@Autowired AstrBotScheduledStopRepository stops;
	@Autowired AstrBotControlProperties astrBot;
	@Autowired DevicesProperties permissions;
	@Autowired JwtService jwtService;
	@MockitoBean SerialSensorCollector serial;
	private UserAccount operator;
	private String viewerToken;

	@BeforeEach
	void setUp() {
		reset(serial);
		when(serial.isConnected()).thenReturn(true);
		events.deleteAll();
		stops.deleteAll();
		users.deleteAll();
		operator = account("operator", "机器人操作员");
		UserAccount viewer = account("viewer", "观察员");
		viewerToken = jwtService.issue(viewer).token();
		permissions.setControlUsers(List.of("operator"));
		astrBot.setEnabled(true);
		astrBot.setApiToken(TOKEN);
		astrBot.setAllowTestControl(false);
		astrBot.setMaxDurationSeconds(300);
		astrBot.setIdentities(List.of(binding("umo:test", "sender-1", "operator")));
	}

	@Test
	void exactIdentityMapsToPlatformPermissionAndReturnsOnlyDeviceSnapshot() throws Exception {
		mvc.perform(post("/api/astrbot/devices/sync").header("X-AstrBot-Token", TOKEN)
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(syncBody())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value("operator"))
			.andExpect(jsonPath("$.canControl").value(true))
			.andExpect(jsonPath("$.devices.length()").value(2))
			.andExpect(jsonPath("$.notifications").doesNotExist());
		verify(serial, never()).sendCommand(anyInt());
	}

	@Test
	void wrongTokenIdentityDisabledUserAndRemovedPermissionAllFailClosed() throws Exception {
		mvc.perform(sync("wrong-token-with-at-least-thirty-two-characters", syncBody()))
			.andExpect(status().isUnauthorized());
		mvc.perform(sync(TOKEN, Map.of("identity", Map.of("umo", "other", "senderId", "sender-1"))))
			.andExpect(status().isForbidden());
		astrBot.setIdentities(List.of(binding("umo:test", "sender-1", "operator"),
			binding("umo:test", "sender-1", "viewer")));
		mvc.perform(sync(TOKEN, syncBody())).andExpect(status().isForbidden());
		astrBot.setIdentities(List.of(binding("umo:test", "sender-1", "operator")));
		operator.setEnabled(false);
		users.saveAndFlush(operator);
		mvc.perform(sync(TOKEN, syncBody())).andExpect(status().isForbidden());
		operator.setEnabled(true);
		users.saveAndFlush(operator);
		permissions.setControlUsers(List.of());
		JsonNode snapshot = syncJson();
		assertThat(snapshot.path("canControl").asBoolean()).isFalse();
		mvc.perform(control(command(snapshot, true, true, false, "确认开启")))
			.andExpect(status().isForbidden());
		verify(serial, never()).sendCommand(anyInt());
	}

	@Test
	void normalStartRequiresConfirmationThenBroadcastsAsMappedPlatformUser() throws Exception {
		JsonNode snapshot = syncJson();
		Map<String, Object> pending = command(snapshot, true, false, false, "打开水泵 5 秒");
		mvc.perform(control(pending)).andExpect(status().isConflict());
		verify(serial, never()).sendCommand(anyInt());

		Map<String, Object> confirmed = command(snapshot, true, true, false, "确认打开水泵 5 秒");
		mvc.perform(control(confirmed)).andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value("operator"))
			.andExpect(jsonPath("$.testMode").value(false))
			.andExpect(jsonPath("$.secondaryConfirmationSkipped").value(false))
			.andExpect(jsonPath("$.autoOffAt").isNotEmpty());
		verify(serial).sendCommand(0x01);
		assertThat(stops.findAll()).singleElement().satisfies(stop -> {
			assertThat(stop.getStatus()).isEqualTo("SCHEDULED");
			assertThat(stop.getFingerprint()).hasSize(64);
		});
		JsonNode web = webSnapshot(viewerToken);
		assertThat(web.path("devices").get(0).path("enabled").asBoolean()).isTrue();
		assertThat(web.path("notifications").get(0).path("message").asText())
			.contains("机器人操作员（operator）用户开启智能灌溉水泵功能");
	}

	@Test
	void explicitTestCanSkipConfirmationOnlyWhenServerSwitchIsEnabled() throws Exception {
		JsonNode snapshot = syncJson();
		Map<String, Object> command = command(snapshot, true, false, true, "/agri_test 水泵 1");
		mvc.perform(control(command)).andExpect(status().isForbidden());
		verify(serial, never()).sendCommand(anyInt());
		astrBot.setAllowTestControl(true);
		mvc.perform(control(command)).andExpect(status().isOk())
			.andExpect(jsonPath("$.testMode").value(true))
			.andExpect(jsonPath("$.secondaryConfirmationSkipped").value(true));
		verify(serial).sendCommand(0x01);
		permissions.setControlUsers(List.of());
		verify(serial, timeout(3000)).sendCommand(0x03);
	}

	@Test
	void testFlagWithoutExplicitTextAndStopWithTestFlagAreRejected() throws Exception {
		astrBot.setAllowTestControl(true);
		JsonNode snapshot = syncJson();
		mvc.perform(control(command(snapshot, true, false, true, "打开水泵"))).andExpect(status().isBadRequest());
		mvc.perform(control(command(snapshot, false, false, true, "/agri_test 停止水泵"))).andExpect(status().isBadRequest());
		verify(serial, never()).sendCommand(anyInt());
	}

	@Test
	void duplicateRequestReturnsRecordedResponseButChangedPayloadConflictsWithoutSecondWrite() throws Exception {
		JsonNode snapshot = syncJson();
		Map<String, Object> request = command(snapshot, true, true, false, "确认开启");
		mvc.perform(control(request)).andExpect(status().isOk());
		mvc.perform(control(request)).andExpect(status().isOk());
		Map<String, Object> changed = new java.util.HashMap<>(request);
		changed.put("device", "lamp");
		mvc.perform(control(changed)).andExpect(status().isConflict());
		verify(serial).sendCommand(0x01);
	}

	private UserAccount account(String username, String displayName) {
		UserAccount user = new UserAccount();
		user.setUsername(username);
		user.setDisplayName(displayName);
		user.setPasswordHash("{noop}offline-test");
		user.setRole("USER");
		return users.saveAndFlush(user);
	}

	private AstrBotControlProperties.IdentityBinding binding(String umo, String sender, String username) {
		var binding = new AstrBotControlProperties.IdentityBinding();
		binding.setUmo(umo);
		binding.setSenderId(sender);
		binding.setUsername(username);
		return binding;
	}

	private Map<String, Object> syncBody() {
		return Map.of("identity", Map.of("umo", "umo:test", "senderId", "sender-1"));
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder sync(String token, Object body)
			throws Exception {
		return post("/api/astrbot/devices/sync").header("X-AstrBot-Token", token)
			.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
	}

	private JsonNode syncJson() throws Exception {
		return json.readTree(mvc.perform(sync(TOKEN, syncBody())).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
	}

	private Map<String, Object> command(JsonNode snapshot, boolean enabled, boolean confirmed,
			boolean testMode, String originalText) {
		JsonNode pump = snapshot.path("devices").get(0);
		Map<String, Object> value = new java.util.HashMap<>();
		value.put("identity", syncBody().get("identity"));
		value.put("requestId", UUID.randomUUID().toString());
		value.put("stationId", "S01");
		value.put("device", "pump");
		value.put("enabled", enabled);
		value.put("expectedRevision", pump.path("revision").asLong());
		if (enabled) value.put("durationSeconds", testMode ? 1 : 300);
		value.put("confirmed", confirmed);
		value.put("testMode", testMode);
		value.put("originalText", originalText);
		return value;
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder control(Object body)
			throws Exception {
		return post("/api/astrbot/devices/control").header("X-AstrBot-Token", TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
	}

	private JsonNode webSnapshot(String token) throws Exception {
		MvcResult result = mvc.perform(get("/api/devices/sync").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(request().asyncStarted()).andReturn();
		return json.readTree(mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
	}
}

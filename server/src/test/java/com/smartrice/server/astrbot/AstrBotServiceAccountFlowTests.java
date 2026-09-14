package com.smartrice.server.astrbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartrice.server.auth.UserAccount;
import com.smartrice.server.auth.UserAccountRepository;
import com.smartrice.server.realtime.DevicesProperties;
import com.smartrice.server.realtime.SerialSensorCollector;
import com.smartrice.server.realtime.StationDeviceRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The QQ bot maps to the {@code astrbot} service account: it cannot log in to the web at all,
 * yet the server still authorizes it for reads and for control it has been granted — but only
 * from the exact configured UMO and sender id. Uses H2 and a mocked collector.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AstrBotServiceAccountFlowTests {

	private static final String TOKEN = "offline-astrbot-service-account-token-12345";
	private static final String QQ_UMO = "qq_ricebot:FriendMessage:0F1E2D3C4B5A69788796A5B4C3D2E1F0";
	private static final String QQ_SENDER = "0F1E2D3C4B5A69788796A5B4C3D2E1F0";

	@Autowired MockMvc mvc;
	@Autowired ObjectMapper json;
	@Autowired UserAccountRepository users;
	@Autowired StationDeviceRepository stationDevices;
	@Autowired AstrBotScheduledStopRepository stops;
	@Autowired AstrBotControlProperties astrBot;
	@Autowired DevicesProperties permissions;
	@MockitoBean SerialSensorCollector serial;

	@BeforeEach
	void setUp() {
		reset(serial);
		when(serial.isConnected()).thenReturn(true);
		when(serial.isAvailable()).thenReturn(true);
		stops.deleteAll();
		stationDevices.deleteAll();
		users.deleteAll();

		UserAccount bot = new UserAccount();
		bot.setUsername("astrbot");
		bot.setDisplayName("AstrBot 机器人");
		// Whatever ends up in the hash column, it is never a credential anybody can present.
		bot.setPasswordHash("{bcrypt}$2a$10$0123456789012345678901uJ8Q2z2Qm6h8rhlOMxjJ0nS0kRmT5qy");
		bot.setRole("SERVICE");
		bot.setEnabled(true);
		bot.setLoginEnabled(false);
		users.saveAndFlush(bot);

		permissions.setControlUsers(List.of("astrbot"));
		astrBot.setEnabled(true);
		astrBot.setApiToken(TOKEN);
		astrBot.setAllowTestControl(false);
		astrBot.setDefaultSprayDurationSeconds(60);
		astrBot.setMaxDurationSeconds(300);
		astrBot.setIdentities(List.of(binding(QQ_UMO, QQ_SENDER, "astrbot")));
	}

	@Test
	void theServiceAccountReadsAndControlsThroughTheExactQqIdentity() throws Exception {
		JsonNode snapshot = syncJson();
		assertThat(snapshot.path("username").asText()).isEqualTo("astrbot");
		assertThat(snapshot.path("displayName").asText()).isEqualTo("AstrBot 机器人");
		assertThat(snapshot.path("canControl").asBoolean()).isTrue();
		verify(serial, never()).sendCommand(anyInt());

		mvc.perform(post("/api/astrbot/agriculture/status").header("X-AstrBot-Token", TOKEN)
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(syncBody())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value("astrbot"));
		verify(serial, never()).sendCommand(anyInt());

		mvc.perform(control(command(snapshot, true, true, "确认开启喷药")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value("astrbot"))
			.andExpect(jsonPath("$.control.state.enabled").value(true));
		verify(serial, timeout(2000)).sendCommand(0x01);
	}

	@Test
	void aWrongUmoOrSenderIsRefusedAndNoDeviceCommandIsEverSent() throws Exception {
		JsonNode snapshot = syncJson();

		Map<String, Object> wrongUmo = command(snapshot, true, true, "确认开启喷药");
		wrongUmo.put("identity", Map.of("umo", "qq_ricebot:GroupMessage:999", "senderId", QQ_SENDER));
		mvc.perform(control(wrongUmo)).andExpect(status().isForbidden());

		Map<String, Object> wrongSender = command(snapshot, true, true, "确认开启喷药");
		wrongSender.put("identity", Map.of("umo", QQ_UMO, "senderId", "someone-else"));
		mvc.perform(control(wrongSender)).andExpect(status().isForbidden());

		mvc.perform(post("/api/astrbot/devices/sync").header("X-AstrBot-Token", TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(
					Map.of("identity", Map.of("umo", QQ_UMO, "senderId", "someone-else")))))
			.andExpect(status().isForbidden());

		// A disabled service account is refused outright, mapping or not.
		UserAccount bot = users.findByUsernameIgnoreCase("astrbot").orElseThrow();
		bot.setEnabled(false);
		users.saveAndFlush(bot);
		mvc.perform(sync(TOKEN, syncBody())).andExpect(status().isForbidden());

		verify(serial, never()).sendCommand(anyInt());
	}

	private AstrBotControlProperties.IdentityBinding binding(String umo, String sender, String username) {
		var binding = new AstrBotControlProperties.IdentityBinding();
		binding.setUmo(umo);
		binding.setSenderId(sender);
		binding.setUsername(username);
		return binding;
	}

	private Map<String, Object> syncBody() {
		return Map.of("identity", Map.of("umo", QQ_UMO, "senderId", QQ_SENDER));
	}

	private MockHttpServletRequestBuilder sync(String token, Object body) throws Exception {
		return post("/api/astrbot/devices/sync").header("X-AstrBot-Token", token)
			.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
	}

	private JsonNode syncJson() throws Exception {
		return json.readTree(mvc.perform(sync(TOKEN, syncBody())).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
	}

	private MockHttpServletRequestBuilder control(Object body) throws Exception {
		return post("/api/astrbot/devices/control").header("X-AstrBot-Token", TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
	}

	private Map<String, Object> command(JsonNode snapshot, boolean enabled, boolean confirmed,
			String originalText) {
		JsonNode pump = snapshot.path("devices").get(0);
		Map<String, Object> value = new HashMap<>();
		value.put("identity", syncBody().get("identity"));
		value.put("requestId", UUID.randomUUID().toString());
		value.put("stationId", "S01");
		value.put("device", "pump");
		value.put("enabled", enabled);
		value.put("expectedRevision", pump.path("revision").asLong());
		if (enabled) value.put("durationSeconds", 300);
		value.put("confirmed", confirmed);
		value.put("testMode", false);
		value.put("originalText", originalText);
		return value;
	}
}

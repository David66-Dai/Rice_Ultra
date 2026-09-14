package com.smartrice.server.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartrice.server.auth.UserAccount;
import com.smartrice.server.auth.UserAccountRepository;
import com.smartrice.server.diagnosis.InferenceClient;
import com.smartrice.server.diagnosis.InspectionDiagnosisRepository;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DeviceControlFlowTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	ObjectMapper json;

	@Autowired
	StationDeviceRepository devices;

	@Autowired
	InspectionDiagnosisRepository diagnoses;

	@Autowired
	UserAccountRepository users;

	@Autowired
	DevicesProperties permissions;

	@MockitoBean
	DeviceActuator actuator;

	@MockitoBean
	InferenceClient inference;

	private UserAccount operator;

	@BeforeEach
	void reset() {
		diagnoses.deleteAll();
		devices.deleteAll();
		users.deleteAll();
		operator = new UserAccount();
		operator.setUsername("operator");
		operator.setDisplayName("操作员");
		operator.setPasswordHash("{noop}test-only");
		operator.setRole("ADMIN");
		operator = users.saveAndFlush(operator);
		permissions.setControlUsers(List.of("operator"));
		when(actuator.isAvailable()).thenReturn(true);
	}

	private RequestPostProcessor jwt() {
		return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
			.jwt(token -> token.claim("uid", operator.getId()));
	}

	@Test
	void stateDefaultsOffAndRequiresAuthentication() throws Exception {
		mvc.perform(get("/api/devices/state").param("stationId", "S01"))
			.andExpect(status().isUnauthorized());

		mvc.perform(get("/api/devices/state").param("stationId", "S01").with(jwt()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.stationId").value("S01"))
			.andExpect(jsonPath("$.pump").value(false))
			.andExpect(jsonPath("$.lamp").value(false));
	}

	@Test
	void operatorCanToggleSprayAndLamp() throws Exception {
		mvc.perform(post("/api/devices/control")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"stationId\":\"S01\",\"device\":\"pump\",\"enabled\":true}")
				.with(jwt()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.device").value("pump"))
			.andExpect(jsonPath("$.enabled").value(true))
			.andExpect(jsonPath("$.command").value("FA01"));
		verify(actuator).sendCommand(0x01);

		mvc.perform(get("/api/devices/state").param("stationId", "S01").with(jwt()))
			.andExpect(jsonPath("$.pump").value(true))
			.andExpect(jsonPath("$.lamp").value(false));

		mvc.perform(post("/api/devices/control")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"stationId\":\"S01\",\"device\":\"lamp\",\"enabled\":true}")
				.with(jwt()))
			.andExpect(jsonPath("$.command").value("FA02"));
		verify(actuator).sendCommand(0x02);

		mvc.perform(post("/api/devices/control")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"stationId\":\"S01\",\"device\":\"pump\",\"enabled\":true}")
				.with(jwt()))
			.andExpect(status().isOk());
		verify(actuator, times(1)).sendCommand(0x01);
	}

	@Test
	void rejectsOfflineStationAndUnknownDevice() throws Exception {
		mvc.perform(post("/api/devices/control")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"stationId\":\"S02\",\"device\":\"pump\",\"enabled\":true}")
				.with(jwt()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("当前仅 S01 支持设备控制"));
		verify(actuator, never()).sendCommand(anyInt());

		mvc.perform(post("/api/devices/control")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"stationId\":\"S01\",\"device\":\"fan\",\"enabled\":true}")
				.with(jwt()))
			.andExpect(status().isBadRequest());
	}

	@Test
	void serialFailureDoesNotMarkDeviceOn() throws Exception {
		doThrow(new IllegalStateException("串口 COM4 当前未连接")).when(actuator).sendCommand(anyInt());

		mvc.perform(post("/api/devices/control")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"stationId\":\"S01\",\"device\":\"pump\",\"enabled\":true}")
				.with(jwt()))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.message").value("串口 COM4 当前未连接"));

		mvc.perform(get("/api/devices/state").param("stationId", "S01").with(jwt()))
			.andExpect(jsonPath("$.pump").value(false));
	}

	@Test
	void leafRedEnablesSprayAndPestRedEnablesLamp() throws Exception {
		when(inference.predict(eq("leaf"), any())).thenReturn(Map.of(
			"task", "leaf",
			"label", "Bacterial Leaf Blight",
			"label_zh", "细菌性叶枯病",
			"confidence", 0.93,
			"has_leaf_damage", true
		));
		when(inference.predict(eq("pest"), any())).thenReturn(
			Map.of("task", "pest", "count", 1, "detections", List.of(Map.of("class_name", "灰飞虱", "confidence", 0.88))),
			Map.of("task", "pest", "count", 2, "detections", List.of(
				Map.of("class_name", "白背飞虱", "confidence", 0.7),
				Map.of("class_name", "白背飞虱", "confidence", 0.6)
			))
		);

		mvc.perform(multipart("/api/diagnosis/leaf").file(image("blight.jpg")).param("stationId", "S01").with(jwt()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.activatedDevice").value("pump"));
		verify(actuator).sendCommand(0x01);

		mvc.perform(multipart("/api/diagnosis/pest").file(image("one.jpg")).param("stationId", "S01").with(jwt()))
			.andExpect(jsonPath("$.alertLevel").value("yellow"));
		verify(actuator, never()).sendCommand(0x02);
		JsonNode yellow = deviceSnapshot().path("notifications").get(0);
		assertThat(yellow.path("type").asText()).isEqualTo("pest_disease");
		assertThat(yellow.path("message").asText())
			.contains("S01 黄色预警", "灰飞虱", "1 只");

		mvc.perform(multipart("/api/diagnosis/pest").file(image("two.jpg")).param("stationId", "S01").with(jwt()))
			.andExpect(jsonPath("$.activatedDevice").value("lamp"));
		verify(actuator).sendCommand(0x02);

		mvc.perform(get("/api/devices/state").param("stationId", "S01").with(jwt()))
			.andExpect(jsonPath("$.pump").value(true))
			.andExpect(jsonPath("$.lamp").value(true));
	}

	@Test
	void redDiagnosesWakeAuthoritativeDeviceSyncSnapshot() throws Exception {
		when(inference.predict(eq("leaf"), any())).thenReturn(Map.of(
			"task", "leaf",
			"label", "Bacterial Leaf Blight",
			"label_zh", "细菌性叶枯病",
			"confidence", 0.93,
			"has_leaf_damage", true
		));
		JsonNode initial = deviceSnapshot();
		assertThat(initial.path("devices").get(0).path("device").asText()).isEqualTo("pump");
		assertThat(initial.path("devices").get(0).path("enabled").isNull()).isTrue();

		MvcResult waiting = mvc.perform(get("/api/devices/sync")
				.param("after", initial.path("cursor").asText())
				.param("waitSeconds", "25")
				.with(jwt()))
			.andExpect(request().asyncStarted())
			.andReturn();

		mvc.perform(multipart("/api/diagnosis/leaf").file(image("blight.jpg"))
				.param("stationId", "S01").with(jwt()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.activatedDevice").value("pump"));

		waiting.getAsyncResult(3000);
		mvc.perform(asyncDispatch(waiting))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.devices[0].device").value("pump"))
			.andExpect(jsonPath("$.devices[0].enabled").value(true))
			.andExpect(jsonPath("$.devices[0].revision").value(1))
			.andExpect(jsonPath("$.devices[0].updatedBy").value("diagnosis"))
			.andExpect(jsonPath("$.notifications[0].type").value("device_control"))
			.andExpect(jsonPath("$.notifications[0].device").value("pump"))
			.andExpect(jsonPath("$.notifications[0].enabled").value(true))
			.andExpect(jsonPath("$.notifications[0].actorUsername").value("diagnosis"))
			.andExpect(jsonPath("$.notifications[0].message").value("系统识别（diagnosis）用户开启智能喷药功能"));

		// The diagnosis also publishes a pest/disease notification after the device wakeup.
		// Start the next long poll from a fresh cursor so it waits for the pest device change.
		JsonNode afterLeaf = deviceSnapshot();

		when(inference.predict(eq("pest"), any())).thenReturn(Map.of(
			"task", "pest",
			"count", 2,
			"detections", List.of(
				Map.of("class_name", "白背飞虱", "confidence", 0.78),
				Map.of("class_name", "白背飞虱", "confidence", 0.72)
			)
		));
		MvcResult pestWaiting = mvc.perform(get("/api/devices/sync")
				.param("after", afterLeaf.path("cursor").asText())
				.param("waitSeconds", "25")
				.with(jwt()))
			.andExpect(request().asyncStarted())
			.andReturn();

		mvc.perform(multipart("/api/diagnosis/pest").file(image("pests.jpg"))
				.param("stationId", "S01").with(jwt()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.activatedDevice").value("lamp"));

		pestWaiting.getAsyncResult(3000);
		mvc.perform(asyncDispatch(pestWaiting))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.devices[0].device").value("pump"))
			.andExpect(jsonPath("$.devices[0].enabled").value(true))
			.andExpect(jsonPath("$.devices[0].revision").value(1))
			.andExpect(jsonPath("$.devices[1].device").value("lamp"))
			.andExpect(jsonPath("$.devices[1].enabled").value(true))
			.andExpect(jsonPath("$.devices[1].revision").value(1))
			.andExpect(jsonPath("$.devices[1].updatedBy").value("diagnosis"))
			.andExpect(jsonPath("$.notifications[0].type").value("device_control"))
			.andExpect(jsonPath("$.notifications[0].device").value("lamp"))
			.andExpect(jsonPath("$.notifications[0].enabled").value(true))
			.andExpect(jsonPath("$.notifications[0].actorUsername").value("diagnosis"))
			.andExpect(jsonPath("$.notifications[0].message").value("系统识别（diagnosis）用户开启智能驱虫灯功能"));
	}

	@Test
	void serialFailureDoesNotBlockDiagnosis() throws Exception {
		when(inference.predict(eq("leaf"), any())).thenReturn(Map.of(
			"task", "leaf",
			"label", "Bacterial Leaf Blight",
			"label_zh", "细菌性叶枯病",
			"confidence", 0.93,
			"has_leaf_damage", true
		));
		doThrow(new IllegalStateException("串口 COM4 当前未连接")).when(actuator).sendCommand(anyInt());

		mvc.perform(multipart("/api/diagnosis/leaf").file(image("blight.jpg")).param("stationId", "S01").with(jwt()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.alertLevel").value("red"))
			.andExpect(jsonPath("$.activatedDevice").value(nullValue()))
			.andExpect(jsonPath("$.deviceError").value("串口 COM4 当前未连接"));

		mvc.perform(get("/api/devices/state").param("stationId", "S01").with(jwt()))
			.andExpect(jsonPath("$.pump").value(false));
	}

	private static MockMultipartFile image(String name) {
		return new MockMultipartFile("file", name, "image/jpeg", new byte[] {1, 2, 3, 4});
	}

	private JsonNode deviceSnapshot() throws Exception {
		MvcResult result = mvc.perform(get("/api/devices/sync")
				.param("waitSeconds", "0").with(jwt()))
			.andExpect(request().asyncStarted())
			.andReturn();
		return json.readTree(mvc.perform(asyncDispatch(result))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
	}
}

package com.smartrice.server.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartrice.server.realtime.StationDeviceRepository;
import com.smartrice.server.realtime.DeviceActuator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class DiagnosisFlowTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	InspectionDiagnosisRepository diagnoses;

	@Autowired
	StationDeviceRepository devices;

	@MockitoBean
	InferenceClient inference;

	@MockitoBean
	DeviceActuator actuator;

	@BeforeEach
	void reset() {
		diagnoses.deleteAll();
		devices.deleteAll();
		when(actuator.isAvailable()).thenReturn(true);
	}

	@Test
	void requiresAuthentication() throws Exception {
		mvc.perform(get("/api/diagnosis/stations")).andExpect(status().isUnauthorized());
		mvc.perform(multipart("/api/diagnosis/leaf").file(image("leaf.jpg")).param("stationId", "S01"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void leafDiseaseTurnsStationRedAndPersists() throws Exception {
		when(inference.predict(eq("leaf"), any())).thenReturn(Map.of(
			"task", "leaf",
			"label", "Bacterial Leaf Blight",
			"label_zh", "细菌性叶枯病",
			"confidence", 0.93,
			"has_leaf_damage", true
		));

		mvc.perform(multipart("/api/diagnosis/leaf").file(image("blight.jpg")).param("stationId", "S01").with(jwt()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.stationId").value("S01"))
			.andExpect(jsonPath("$.task").value("leaf"))
			.andExpect(jsonPath("$.labelZh").value("细菌性叶枯病"))
			.andExpect(jsonPath("$.alertLevel").value("red"))
			.andExpect(jsonPath("$.stationAlertLevel").value("red"))
			.andExpect(jsonPath("$.activatedDevice").value("pump"));

		assertThat(diagnoses.count()).isEqualTo(1);
		mvc.perform(get("/api/devices/state").param("stationId", "S01").with(jwt()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.pump").value(true))
			.andExpect(jsonPath("$.lamp").value(false));
		mvc.perform(get("/api/diagnosis/stations").with(jwt()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.stations[0].stationId").value("S01"))
			.andExpect(jsonPath("$.stations[0].alertLevel").value("red"))
			.andExpect(jsonPath("$.stations[0].leafAlertLevel").value("red"))
			.andExpect(jsonPath("$.stations[1].alertLevel").value("green"));
	}

	@Test
	void healthyLeafKeepsGreenAndDoesNotClearPestRed() throws Exception {
		when(inference.predict(eq("pest"), any())).thenReturn(Map.of(
			"task", "pest",
			"count", 3,
			"detections", List.of(
				Map.of("class_name", "褐飞虱", "confidence", 0.8),
				Map.of("class_name", "褐飞虱", "confidence", 0.7),
				Map.of("class_name", "二化螟", "confidence", 0.6)
			)
		));
		when(inference.predict(eq("leaf"), any())).thenReturn(Map.of(
			"task", "leaf",
			"label", "Healthy Leaf",
			"label_zh", "健康叶片",
			"confidence", 0.99,
			"has_leaf_damage", false
		));

		mvc.perform(multipart("/api/diagnosis/pest").file(image("pests.jpg")).param("stationId", "S01").with(jwt()))
			.andExpect(jsonPath("$.alertLevel").value("red"))
			.andExpect(jsonPath("$.stationAlertLevel").value("red"))
			.andExpect(jsonPath("$.detectionCount").value(3))
			.andExpect(jsonPath("$.activatedDevice").value("lamp"));

		mvc.perform(multipart("/api/diagnosis/leaf").file(image("healthy.jpg")).param("stationId", "S01").with(jwt()))
			.andExpect(jsonPath("$.alertLevel").value("green"))
			.andExpect(jsonPath("$.stationAlertLevel").value("red"));

		mvc.perform(get("/api/devices/state").param("stationId", "S01").with(jwt()))
			.andExpect(jsonPath("$.pump").value(false))
			.andExpect(jsonPath("$.lamp").value(true));

		assertThat(diagnoses.count()).isEqualTo(2);
		mvc.perform(get("/api/diagnosis/stations").with(jwt()))
			.andExpect(jsonPath("$.stations[0].stationId").value("S01"))
			.andExpect(jsonPath("$.stations[0].alertLevel").value("red"))
			.andExpect(jsonPath("$.stations[0].leafAlertLevel").value("green"))
			.andExpect(jsonPath("$.stations[0].pestCount").value(3));
	}

	@Test
	void pestCountsMapToYellowThenRed() throws Exception {
		when(inference.predict(eq("pest"), any())).thenReturn(
			Map.of("task", "pest", "count", 1, "detections", List.of(Map.of("class_name", "灰飞虱", "confidence", 0.88))),
			Map.of("task", "pest", "count", 0, "detections", List.of()),
			Map.of("task", "pest", "count", 2, "detections", List.of(
				Map.of("class_name", "白背飞虱", "confidence", 0.7),
				Map.of("class_name", "白背飞虱", "confidence", 0.6)
			))
		);

		mvc.perform(multipart("/api/diagnosis/pest").file(image("one.jpg")).param("stationId", "S01").with(jwt()))
			.andExpect(jsonPath("$.alertLevel").value("yellow"))
			.andExpect(jsonPath("$.stationAlertLevel").value("yellow"));

		mvc.perform(get("/api/devices/state").param("stationId", "S01").with(jwt()))
			.andExpect(jsonPath("$.lamp").value(false));

		mvc.perform(multipart("/api/diagnosis/pest").file(image("none.jpg")).param("stationId", "S01").with(jwt()))
			.andExpect(jsonPath("$.alertLevel").value("green"))
			.andExpect(jsonPath("$.stationAlertLevel").value("green"));

		mvc.perform(multipart("/api/diagnosis/pest").file(image("two.jpg")).param("stationId", "S01").with(jwt()))
			.andExpect(jsonPath("$.alertLevel").value("red"))
			.andExpect(jsonPath("$.stationAlertLevel").value("red"))
			.andExpect(jsonPath("$.activatedDevice").value("lamp"));

		mvc.perform(get("/api/devices/state").param("stationId", "S01").with(jwt()))
			.andExpect(jsonPath("$.pump").value(false))
			.andExpect(jsonPath("$.lamp").value(true));

		assertThat(diagnoses.count()).isEqualTo(3);
	}

	@Test
	void rejectsUnknownStationEmptyFileAndOfflineStation() throws Exception {
		mvc.perform(multipart("/api/diagnosis/leaf").file(image("x.jpg")).param("stationId", "S11").with(jwt()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_diagnosis_request"));

		mvc.perform(multipart("/api/diagnosis/pest")
				.file(new MockMultipartFile("file", "empty.jpg", "image/jpeg", new byte[0]))
				.param("stationId", "S01")
				.with(jwt()))
			.andExpect(status().isBadRequest());

		mvc.perform(multipart("/api/diagnosis/leaf").file(image("x.jpg")).param("stationId", "S02").with(jwt()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("站点离线，暂不可识别"));
		verifyNoInteractions(inference);

		assertThat(diagnoses.count()).isZero();
	}

	@Test
	void hyperspectralLeafStoresAsLeafTask() throws Exception {
		when(inference.predict(eq("leaf-hsi"), any())).thenReturn(Map.of(
			"task", "leaf-hsi",
			"label", "Brown Spot",
			"label_zh", "褐斑病",
			"confidence", 0.88,
			"has_leaf_damage", true,
			"severity", 2.1
		));

		mvc.perform(multipart("/api/diagnosis/leaf-hsi")
				.file(new MockMultipartFile("file", "cube.h5", "application/octet-stream", new byte[] {1, 2, 3, 4}))
				.param("stationId", "S01")
				.with(jwt()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.stationId").value("S01"))
			.andExpect(jsonPath("$.task").value("leaf"))
			.andExpect(jsonPath("$.labelZh").value("褐斑病"))
			.andExpect(jsonPath("$.alertLevel").value("yellow"))
			.andExpect(jsonPath("$.stationAlertLevel").value("yellow"))
			.andExpect(jsonPath("$.result.task").value("leaf-hsi"))
			.andExpect(jsonPath("$.activatedDevice").value(nullValue()));

		assertThat(diagnoses.findAll()).singleElement().satisfies(row -> {
			assertThat(row.getTask()).isEqualTo("leaf");
			assertThat(row.getLabelZh()).isEqualTo("褐斑病");
		});
	}

	private static MockMultipartFile image(String name) {
		return new MockMultipartFile("file", name, "image/jpeg", new byte[] {1, 2, 3, 4});
	}
}

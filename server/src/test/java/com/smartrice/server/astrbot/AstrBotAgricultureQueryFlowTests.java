package com.smartrice.server.astrbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartrice.server.auth.UserAccount;
import com.smartrice.server.auth.UserAccountRepository;
import com.smartrice.server.diagnosis.InspectionDiagnosis;
import com.smartrice.server.diagnosis.InspectionDiagnosisFixtures;
import com.smartrice.server.diagnosis.InspectionDiagnosisRepository;
import com.smartrice.server.realtime.DeviceActivityService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AstrBotAgricultureQueryFlowTests {

	private static final String TOKEN = "offline-astrbot-query-token-123456789012345";
	@Autowired MockMvc mvc;
	@Autowired ObjectMapper json;
	@Autowired UserAccountRepository users;
	@Autowired InspectionDiagnosisRepository diagnoses;
	@Autowired AstrBotControlProperties properties;
	@Autowired DeviceActivityService activity;

	@BeforeEach
	void setUp() {
		diagnoses.deleteAll();
		users.deleteAll();
		UserAccount user = new UserAccount();
		user.setUsername("operator");
		user.setDisplayName("查询员");
		user.setPasswordHash("{noop}offline");
		user.setRole("USER");
		users.saveAndFlush(user);
		properties.setEnabled(true);
		properties.setApiToken(TOKEN);
		properties.setMaxQueryRangeDays(31);
		properties.setIdentities(List.of(binding()));
	}

	@Test
	void currentDataAndAlertsUseMappedIdentityWithoutDatabaseCredentials() throws Exception {
		InspectionDiagnosis row = InspectionDiagnosisFixtures.row("S01", "pest", "白背飞虱", "白背飞虱",
			2, "red", "{\"detections\":[{\"class_name\":\"白背飞虱\"},{\"class_name\":\"白背飞虱\"}]}",
			Instant.now());
		row.setConfidence(0.88);
		diagnoses.saveAndFlush(row);
		activity.publishPestDiseaseAfterCommit("S01", "S01 红色告警：识别到白背飞虱，共 2 只", row.getId());
		String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();

		mvc.perform(post("/api/astrbot/agriculture/status").header("X-AstrBot-Token", TOKEN)
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("identity", identity()))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value("operator"))
			.andExpect(jsonPath("$.maxRangeDays").value(31));

		Map<String, Object> query = Map.of("identity", identity(), "stationId", "ST-001",
			"startDate", today, "endDate", today);
		mvc.perform(post("/api/astrbot/agriculture/data").header("X-AstrBot-Token", TOKEN)
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(query)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.stationId").value("S01"))
			.andExpect(jsonPath("$.days[0].source").value("inspection_diagnosis"))
			.andExpect(jsonPath("$.days[0].items[3].value").value(2))
			.andExpect(jsonPath("$.days[0].items[3].alertLevel").value("red"));

		mvc.perform(post("/api/astrbot/agriculture/alerts").header("X-AstrBot-Token", TOKEN)
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(query)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.count").value(1))
			.andExpect(jsonPath("$.alerts[0].deliveryStatus").value("platform_published"));
	}

	@Test
	void invalidIdentityAndOversizedRangeFailClosed() throws Exception {
		String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
		String old = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(31).toString();
		Map<String, Object> query = Map.of("identity", identity(), "stationId", "S01",
			"startDate", old, "endDate", today);
		mvc.perform(post("/api/astrbot/agriculture/data").header("X-AstrBot-Token", TOKEN)
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(query)))
			.andExpect(status().isBadRequest());
		mvc.perform(post("/api/astrbot/agriculture/status").header("X-AstrBot-Token", TOKEN)
				.contentType(MediaType.APPLICATION_JSON).content("{\"identity\":{\"umo\":\"other\",\"senderId\":\"sender-1\"}}"))
			.andExpect(status().isForbidden());
		assertThat(diagnoses.count()).isZero();
	}

	private AstrBotControlProperties.IdentityBinding binding() {
		var binding = new AstrBotControlProperties.IdentityBinding();
		binding.setUmo("umo:test");
		binding.setSenderId("sender-1");
		binding.setUsername("operator");
		return binding;
	}

	private Map<String, String> identity() {
		return Map.of("umo", "umo:test", "senderId", "sender-1");
	}
}

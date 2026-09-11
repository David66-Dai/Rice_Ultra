package com.smartrice.server.ai;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AiAnalysisFlowTests {
	@Autowired MockMvc mvc;
	@MockitoBean AiEvidenceService evidence;
	@MockitoBean DifyWorkflowClient dify;
	@MockitoBean AiReportStore reports;

	@BeforeEach void configured() {
		when(dify.configured()).thenReturn(true);
		when(reports.configured()).thenReturn(true);
	}

	@Test void protectsAllAnalysisAndArchiveEndpointsAndNeverLeaksConfig() throws Exception {
		mvc.perform(get("/api/ai/status")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/ai/evidence").param("stationId", "S01").param("date", "2020-01-01")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/ai/analyses").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/ai/analyses/any-id")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/ai/analyses").param("stationId", "S01")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/ai/status").with(jwt())).andExpect(status().isOk()).andExpect(content().json("{\"configured\":true}"));
		verifyNoInteractions(evidence);
		verify(reports, never()).read(anyString(), anyString(), any());
		verify(reports, never()).list(anyString(), anyString(), any(), any());
	}

	@Test void handlesBadInputsBeforeStartingAJobOrReadingArchives() throws Exception {
		mvc.perform(post("/api/ai/analyses").with(jwt()).contentType("application/json")
			.content("{\"stationId\":\"S01\",\"date\":\"bad-date\",\"windowDays\":7}"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_ai_request"));
		mvc.perform(post("/api/ai/analyses").with(jwt()).contentType("application/json")
			.content("{\"stationId\":\"S01\",\"date\":\"2020-01-01\",\"windowDays\":365}"))
			.andExpect(status().isBadRequest());
		mvc.perform(get("/api/ai/analyses").with(jwt()).param("stationId", "S01").param("generatedDate", "2026-02-30"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_ai_request"));
		mvc.perform(get("/api/ai/analyses").with(jwt()).param("generatedDate", "2026-09-11"))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(evidence);
		verify(reports, never()).prepare(anyString(), any());
		verify(reports, never()).list(anyString(), anyString(), any(), any());
	}

	@Test void listsSavedMetadataWithAuthenticatedOwnerAndGeneratedDateFilter() throws Exception {
		AiAnalysisJob metadata = archived(true);
		when(reports.list(eq("alice"), eq("S01"), eq(LocalDate.of(2026, 9, 11)), any())).thenReturn(List.of(metadata));
		mvc.perform(get("/api/ai/analyses").with(jwt().jwt(token -> token.subject("alice")))
			.param("stationId", "S01").param("generatedDate", "2026-09-11"))
			.andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value("S01_20260911_1104"))
			.andExpect(jsonPath("$[0].expired").value(true)).andExpect(jsonPath("$[0].result").doesNotExist())
			.andExpect(jsonPath("$[0].archivePath").value("/rice/output/point_1/output_20260911_1104.json"));
		verify(reports).list(eq("alice"), eq("S01"), eq(LocalDate.of(2026, 9, 11)), any());
		verifyNoInteractions(evidence);
		verify(dify, never()).run(any(), anyString());
	}

	@Test void expiredPersistedReportRemainsReadableButAnotherOwnerCannotReadIt() throws Exception {
		String id = "S01_20260911_1104";
		AiAnalysisJob archived = archived(false);
		when(reports.read(eq("alice"), eq(id), any())).thenReturn(archived);
		when(reports.read(eq("bob"), eq(id), any())).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到当前账号的分析报告"));
		mvc.perform(get("/api/ai/analyses/" + id).with(jwt().jwt(token -> token.subject("alice"))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("succeeded"))
			.andExpect(jsonPath("$.expired").value(true)).andExpect(jsonPath("$.result.summary").value("已归档报告正文"))
			.andExpect(jsonPath("$.generatedAt").value("2026-09-11T03:04:00Z"))
			.andExpect(jsonPath("$.expiresAt").value("2026-09-11T04:04:00Z"));
		mvc.perform(get("/api/ai/analyses/" + id).with(jwt().jwt(token -> token.subject("bob"))))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.result").doesNotExist());
		verifyNoInteractions(evidence);
		verify(dify, never()).run(any(), anyString());
	}

	private static AiAnalysisJob archived(boolean metadataOnly) {
		Instant generated = Instant.parse("2026-09-11T03:04:00Z");
		return new AiAnalysisJob("S01_20260911_1104", "S01", LocalDate.of(2020, 1, 1), 7, "succeeded",
			generated.minusSeconds(30), generated.plusSeconds(5), null,
			metadataOnly ? null : new AiAnalysisResult(null, "天气分析", "土壤分析", "风险分析", "已归档报告正文", "run-fixture"),
			generated, generated.plusSeconds(3600), true, "S01_20260911_1104", "/rice/output/point_1/output_20260911_1104.json");
	}
}

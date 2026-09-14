package com.smartrice.server.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smartrice.server.hdfs.WebHdfsClient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class AiReportStoreTests {
	final WebHdfsClient hdfs = mock(WebHdfsClient.class);
	final Map<String, byte[]> files = new LinkedHashMap<>();
	final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
	final Instant GENERATED = Instant.parse("2026-09-11T06:47:37Z");
	final AiAnalysisRequest request = new AiAnalysisRequest("S01", LocalDate.of(2020, 1, 1), 7, "unknown");
	AiReportStore store;
	@BeforeEach void setup() {
		when(hdfs.configured()).thenReturn(true);
		when(hdfs.absolutePath(anyString())).thenAnswer(call -> "/rice/output/" + call.getArgument(0));
		when(hdfs.read(anyString())).thenAnswer(call -> Optional.ofNullable(files.get(call.getArgument(0))));
		when(hdfs.list(anyString())).thenAnswer(call -> {
			String prefix = call.getArgument(0) + "/";
			return files.keySet().stream().filter(path -> path.startsWith(prefix))
				.map(path -> new WebHdfsClient.FileEntry(path.substring(prefix.length()), false, 0L)).toList();
		});
		doAnswer(call -> {
			String path = call.getArgument(0);
			if (files.containsKey(path)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Existing file");
			files.put(path, call.getArgument(1)); return null;
		}).when(hdfs).writeAtomic(anyString(), any());
		store = new AiReportStore(hdfs, json);
	}
	private AiAnalysisJob completed() {
		AiEvidence evidence = new AiEvidence("S01", "point_1", request.date().minusDays(6), request.date(), 7, 7,
			List.of(), 7, List.of(), List.of(), "unknown", null, "unknown", List.of(), null);
		return new AiAnalysisJob("temporary-task", "S01", request.date(), 7, "succeeded", GENERATED.minusSeconds(20), GENERATED, null,
			new AiAnalysisResult(evidence, "weather", "soil", "risk", "summary", "dify-test"));
	}
	@Test void oneCompleteFileUsesGenerationDateAndBeijingMinuteNotAnalysisDate() throws Exception {
		AiAnalysisJob saved = store.save("alice", request, completed(), GENERATED);
		assertThat(files.keySet()).containsExactly("point_1/output_20260911_1447.json");
		assertThat(saved.id()).isEqualTo("S01_20260911_1447");
		assertThat(saved.archivePath()).isEqualTo("/rice/output/point_1/output_20260911_1447.json");
		assertThat(saved.generatedAt()).isEqualTo(Instant.parse("2026-09-11T06:47:00Z"));
		assertThat(saved.date()).isEqualTo(LocalDate.of(2020, 1, 1));
		String content = new String(files.values().iterator().next(), java.nio.charset.StandardCharsets.UTF_8);
		assertThat(content).contains("summary", "dify-test").doesNotContain("alice", "api-key", "password");
		assertThat(json.readTree(content).path("request").path("growthStage").asText()).isEqualTo("unknown");
	}
	@Test void restartReadsFromHdfsAndExpiryNeverDeletesAnArchivedReport() {
		AiAnalysisJob saved = store.save("alice", request, completed(), GENERATED);
		AiReportStore restarted = new AiReportStore(hdfs, json);
		Instant boundary = Instant.parse("2026-09-11T07:47:00Z");
		assertThat(restarted.read("alice", saved.id(), boundary).expired()).isFalse();
		AiAnalysisJob expired = restarted.read("alice", saved.id(), boundary.plusSeconds(1));
		assertThat(expired.expired()).isTrue();
		assertThat(expired.result().summary()).isEqualTo("summary");
		assertThat(files).hasSize(1);
		verify(hdfs, times(2)).read("point_1/output_20260911_1447.json");
	}
	@Test void historyFiltersGenerationDateAndOwnerAndReturnsMetadataOnly() {
		store.save("alice", request, completed(), GENERATED);
		store.save("bob", request, completed(), GENERATED.plusSeconds(60));
		List<AiAnalysisJob> history = store.list("alice", "S01", LocalDate.of(2026, 9, 11), GENERATED.plusSeconds(7200));
		assertThat(history).hasSize(1);
		assertThat(history.getFirst().expired()).isTrue();
		assertThat(history.getFirst().result()).isNull();
		assertThat(store.list("alice", "S01", LocalDate.of(2020, 1, 1), GENERATED)).isEmpty();
		assertThatThrownBy(() -> store.read("bob", "S01_20260911_1447", GENERATED)).hasMessageContaining("404");
	}
	@Test void minuteCollisionsAreRejectedWithoutOverwritingTheExistingReport() {
		store.save("alice", request, completed(), GENERATED);
		byte[] original = files.values().iterator().next().clone();
		assertThatThrownBy(() -> store.prepare("S01", GENERATED.plusSeconds(5))).hasMessageContaining("409");
		assertThatThrownBy(() -> store.save("alice", request, completed(), GENERATED.plusSeconds(5))).isInstanceOf(ResponseStatusException.class);
		assertThat(files.values().iterator().next()).isEqualTo(original);
	}
	@Test void badPathsAndUnavailableHdfsNeverBecomeAnEmptyHistory() {
		assertThatThrownBy(() -> store.read("alice", "../../config", GENERATED)).hasMessageContaining("404");
		assertThatThrownBy(() -> store.read("alice", "S01_20260230_1447", GENERATED)).hasMessageContaining("404");
		assertThatThrownBy(() -> store.list("alice", "S11", null, GENERATED)).hasMessageContaining("400");
		doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "HDFS 暂不可用")).when(hdfs).list("point_1");
		assertThatThrownBy(() -> store.list("alice", "S01", null, GENERATED)).hasMessageContaining("503");
	}
}

package com.smartrice.server.ai;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smartrice.server.config.RiceConfiguration;
import com.smartrice.server.hdfs.HdfsProperties;
import com.smartrice.server.hdfs.WebHdfsClient;
import com.smartrice.server.hive.HiveConnectionFactory;
import com.smartrice.server.hive.HiveProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.context.properties.bind.Binder;

/** Explicitly generates one real farm report and preserves its single HDFS archive. No application/serial/MySQL. */
@EnabledIfEnvironmentVariable(named = "RICE_HDFS_REPORT_LIVE_TEST", matches = "true")
class AiHdfsLiveTests {
	@Test void generatedReportSurvivesAServiceRestartAndRemainsReadableAfterExpiry() throws Exception {
		var binder = Binder.get(RiceConfiguration.loadEnvironment());
		var hive = binder.bind("app.hive", HiveProperties.class).get();
		var dify = binder.bind("app.dify", DifyProperties.class).get();
		var config = binder.bind("app.hdfs", HdfsProperties.class).get();
		var json = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		WebHdfsClient hdfs = new WebHdfsClient(config, json);
		AiReportStore store = new AiReportStore(hdfs, json);
		String owner = "hdfs-archive-verification";
		try (var connections = new HiveConnectionFactory(hive)) {
			var evidence = new AiEvidenceService(new HiveAiRepository(connections), new HiveLegacyAiRepository(connections));
			var client = new DifyWorkflowClient(dify, json);
			AiAnalysisService service = new AiAnalysisService(evidence, client, store);
			AiAnalysisJob saved;
			try {
				var request = new AiAnalysisRequest("S01", LocalDate.of(2026, 9, 11), 7, "unknown");
				var submitted = service.submit(owner, request);
				Instant deadline = Instant.now().plusSeconds(240);
				do {
					Thread.sleep(500);
					saved = service.get(owner, submitted.id());
				} while (saved.completedAt() == null && Instant.now().isBefore(deadline));
				assertThat(saved.status()).as(saved.error()).isEqualTo("succeeded");
				assertThat(saved.archivePath()).matches("/rice/output/point_1/output_\\d{8}_\\d{4}\\.json");
				assertThat(saved.result().evidence().metrics()).hasSize(11);
				assertThat(saved.expired()).isFalse();
			} finally { service.close(); }
			AiAnalysisService restarted = new AiAnalysisService(evidence, client, new AiReportStore(hdfs, json),
				Clock.fixed(saved.expiresAt().plusMillis(1), ZoneOffset.UTC));
			try {
				AiAnalysisJob past = restarted.get(owner, saved.archiveId());
				assertThat(past.expired()).isTrue();
				assertThat(past.result().summary()).isEqualTo(saved.result().summary());
				String expectedArchiveId = saved.archiveId();
				assertThat(restarted.list(owner, "S01", saved.generatedAt().atZone(AiReportStore.ZONE).toLocalDate()))
					.anySatisfy(job -> { assertThat(job.id()).isEqualTo(expectedArchiveId); assertThat(job.expired()).isTrue(); assertThat(job.result()).isNull(); });
				Files.writeString(Path.of("target", "ai-hdfs-live-result.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(saved));
				System.out.printf("HDFS report archived and read after restart/expiry: %s, generatedAt=%s, expiresAt=%s.%n",
					saved.archivePath(), saved.generatedAt(), saved.expiresAt());
			} finally { restarted.close(); }
		} finally { hdfs.close(); }
	}
}

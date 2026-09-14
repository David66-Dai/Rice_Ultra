package com.smartrice.server.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smartrice.server.config.RiceConfiguration;
import com.smartrice.server.hive.HiveConnectionFactory;
import com.smartrice.server.hive.HiveProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.context.properties.bind.Binder;

/** Explicit live model call using only farm data; no application, MySQL, auth user or serial collector. */
@EnabledIfEnvironmentVariable(named = "RICE_DIFY_LIVE_TEST", matches = "true")
class AiDifyLiveTests {
	@Test void hiveToPublishedDifyWorkflowReturnsFourReports() throws Exception {
		var environment = RiceConfiguration.loadEnvironment();
		var binder = Binder.get(environment);
		var hive = binder.bind("app.hive", HiveProperties.class).get();
		var dify = binder.bind("app.dify", DifyProperties.class).get();
		assertThat(dify.configured()).as("Fill local app.dify.api-key").isTrue();
		var json = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		try (var connections = new HiveConnectionFactory(hive)) {
			var source = new AiEvidenceService(new HiveAiRepository(connections), new HiveLegacyAiRepository(connections),
				null, Clock.system(ZoneId.of("Asia/Shanghai")))
				.evidence("S01", LocalDate.of(2026, 9, 11), 7, "unknown");
			assertThat(source.observedDays()).isEqualTo(7);
			assertThat(source.metrics()).hasSize(11);
			assertThat(source.legacyContext().yield().available()).isTrue();
			assertThat(source.legacyContext().yield().referenceYear()).isEqualTo(2025);
			var output = new DifyWorkflowClient(dify, json).run(source, "rice-ultra-validation");
			assertChinesePlainText("环境分析", output.weatherAnalysis());
			assertChinesePlainText("土壤分析", output.soilAnalysis());
			assertChinesePlainText("风险分析", output.riskAnalysis());
			assertChineseSummary(output.summary());
			assertThat(output.summary()).containsAnyOf("2025", "二〇二五", "二零二五");
			var result = new AiAnalysisResult(source, output.weatherAnalysis(), output.soilAnalysis(), output.riskAnalysis(), output.summary(), output.workflowRunId());
			Path report = Path.of("target", "ai-live-result.json");
			Files.writeString(report, json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
			System.out.printf("Live Hive + Dify verified: %s %s..%s, %d observed days, 11 metrics, legacy yield year %d. Four reports saved in target/ai-live-result.json.%n",
				source.stationId(), source.startDate(), source.endDate(), source.observedDays(), source.legacyContext().yield().referenceYear());
		}
	}

	/** Check the raw workflow text, before UI rendering can hide unsupported formatting. */
	static void assertChinesePlainText(String section, String text) {
		assertThatCode(() -> AiReportLanguage.requireChinesePlainText(text))
			.as("%s正文必须符合生产端中文纯文本规范", section).doesNotThrowAnyException();
	}

	static void assertChineseSummary(String text) {
		assertThatCode(() -> AiReportLanguage.requireChineseSummary(text))
			.as("综合建议必须符合生产端正文规范并给出两种指定方案").doesNotThrowAnyException();
	}
}

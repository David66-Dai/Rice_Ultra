package com.smartrice.server.ai;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class DifyWorkflowClientTests {
	private static final String SUMMARY = "方案A：高成本高效率型。方案B：低成本稳定型。";
	HttpServer server;
	DifyProperties config;
	ObjectMapper mapper = new ObjectMapper();
	String parameters = "{\"user_input_form\":[{\"paragraph\":{\"variable\":\"environment_json\",\"required\":true,\"max_length\":131072}}]}";
	String response = "{\"workflow_run_id\":\"run-1\",\"data\":{\"status\":\"succeeded\",\"outputs\":{\"weather_analysis\":\"气象\",\"soil_analysis\":\"土壤\",\"risk_analysis\":\"风险\",\"summary\":\"" + SUMMARY + "\"}}}";
	int responseStatus = 200;
	String sent;
	String authorization;
	AtomicInteger runs = new AtomicInteger();
	@BeforeEach void start() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/", exchange -> {
			boolean run = exchange.getRequestURI().getPath().endsWith("workflows/run");
			if (run) {
				runs.incrementAndGet();
				sent = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
				authorization = exchange.getRequestHeaders().getFirst("Authorization");
			}
			byte[] bytes = (run ? response : parameters).getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(run ? responseStatus : 200, bytes.length);
			exchange.getResponseBody().write(bytes); exchange.close();
		});
		server.start();
		config = new DifyProperties();
		config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
		config.setApiKey("test-only-key");
	}
	@AfterEach void stop() { server.stop(0); }
	@Test void sendsFullEvidenceAndValidatesAllFourOutputs() throws Exception {
		var data = Map.of("soil_n", 118, "missing", "缺测保持空值");
		var result = new DifyWorkflowClient(config, mapper).run(data, "opaque-user");
		var body = mapper.readTree(sent);
		assertThat(mapper.readTree(body.path("inputs").path("environment_json").asText())).isEqualTo(mapper.valueToTree(data));
		assertThat(body.path("response_mode").asText()).isEqualTo("blocking");
		assertThat(body.path("user").asText()).isEqualTo("opaque-user");
		assertThat(authorization).isEqualTo("Bearer test-only-key");
		assertThat(sent).doesNotContain("test-only-key");
		assertThat(result.summary()).isEqualTo(SUMMARY);
		assertThat(result.workflowRunId()).isEqualTo("run-1");
		assertThat(runs).hasValue(1);
	}
	@Test void rejectsPublishedInputLengthBeforeRunningWithoutTruncation() {
		parameters = parameters.replace("131072", "5");
		assertThatThrownBy(() -> new DifyWorkflowClient(config, mapper).run(Map.of("long", "这是不能截断的原始数据"), "u"))
			.isInstanceOf(ResponseStatusException.class).hasMessageContaining("长度不足");
		assertThat(runs).hasValue(0);
	}
	@Test void rejectsLegacyWorkflowContractBeforeSendingData() {
		parameters = parameters.replace("environment_json", "input1");
		assertThatThrownBy(() -> new DifyWorkflowClient(config, mapper).run(Map.of(), "u"))
			.isInstanceOf(ResponseStatusException.class).hasMessageContaining("契约不匹配");
		assertThat(runs).hasValue(0);
	}
	@Test void upstreamErrorDoesNotLeakBodyOrRetry() {
		responseStatus = 500; response = "secret-password private-server";
		assertThatThrownBy(() -> new DifyWorkflowClient(config, mapper).run(Map.of(), "u"))
			.isInstanceOf(ResponseStatusException.class).hasMessageNotContaining("secret-password").hasMessageNotContaining("private-server");
		assertThat(runs).hasValue(1);
	}
	@Test void failedWorkflowAndMissingOutputsAreNeverReportedAsSuccess() {
		response = response.replace("succeeded", "failed");
		assertThatThrownBy(() -> new DifyWorkflowClient(config, mapper).run(Map.of(), "u")).hasMessageContaining("执行失败");
		response = "{\"data\":{\"status\":\"succeeded\",\"outputs\":{\"summary\":\"只有总结\"}}}";
		assertThatThrownBy(() -> new DifyWorkflowClient(config, mapper).run(Map.of(), "u")).hasMessageContaining("输出不完整");
	}
	@Test void rejectsNonChineseBodyWithoutRewritingItOrRetryingTheWorkflow() {
		response = response.replace("气象", "光照100lux。");
		assertThatThrownBy(() -> new DifyWorkflowClient(config, mapper).run(Map.of(), "u"))
			.isInstanceOf(ResponseStatusException.class)
			.hasMessageContaining("报告格式不符合要求，请确认工作流已更新并重新生成");
		assertThat(runs).hasValue(1);
	}
	@Test void rejectsMarkdownAndMissingPlanInsteadOfReturningAReport() {
		response = response.replace("土壤", "**土壤分析**");
		assertThatThrownBy(() -> new DifyWorkflowClient(config, mapper).run(Map.of(), "u"))
			.isInstanceOf(ResponseStatusException.class).hasMessageContaining("报告格式不符合要求");
		assertThat(runs).hasValue(1);
		response = response.replace("**土壤分析**", "土壤分析").replace(SUMMARY, "仅有中文综合建议。");
		assertThatThrownBy(() -> new DifyWorkflowClient(config, mapper).run(Map.of(), "u"))
			.isInstanceOf(ResponseStatusException.class).hasMessageContaining("报告格式不符合要求");
		assertThat(runs).hasValue(2);
	}
	@Test void statusDoesNotAcceptCredentialsEmbeddedInUrl() {
		assertThat(config.configured()).isTrue();
		config.setBaseUrl("http://user:password@localhost/v1");
		assertThat(config.configured()).isFalse();
		config.setBaseUrl("http://localhost/v1"); config.setApiKey("");
		assertThat(config.configured()).isFalse();
	}
}

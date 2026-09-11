package com.smartrice.server.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Only the backend sees the app key. Requests never retry a potentially billable workflow run. */
@Component
public class DifyWorkflowClient {
	private final DifyProperties properties;
	private final ObjectMapper json;
	public DifyWorkflowClient(DifyProperties properties, ObjectMapper json) {
		this.properties = properties;
		this.json = json;
	}
	public boolean configured() { return properties.configured(); }
	public record Output(String weatherAnalysis, String soilAnalysis, String riskAnalysis, String summary, String workflowRunId) {}

	public Output run(Object evidence, String user) {
		if (!configured()) throw error(HttpStatus.SERVICE_UNAVAILABLE, "AI 工作流尚未配置，请在服务端填写新版 Dify 应用密钥");
		try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
				.followRedirects(HttpClient.Redirect.NEVER).build()) {
			String input = json.writeValueAsString(evidence);
			if (input.length() > 131072) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "分析数据超过单次输入上限，请缩短分析窗口");
			// Check the published form, not only a local assumed limit; never truncate evidence.
			JsonNode parameters = exchange(client, "parameters", null);
			validateInput(parameters, input.codePointCount(0, input.length()));
			JsonNode response = exchange(client, "workflows/run", json.writeValueAsString(Map.of(
				"inputs", Map.of("environment_json", input), "response_mode", "blocking", "user", user)));
			JsonNode data = response.path("data");
			if (!"succeeded".equals(data.path("status").asText()))
				throw error(HttpStatus.BAD_GATEWAY, "Dify 工作流执行失败，请检查工作流运行日志后手动重试");
			JsonNode outputs = data.path("outputs");
			String weather = output(outputs, "weather_analysis");
			String soil = output(outputs, "soil_analysis");
			String risk = output(outputs, "risk_analysis");
			String summary = output(outputs, "summary");
			AiReportLanguage.validateReports(weather, soil, risk, summary);
			return new Output(weather, soil, risk, summary, response.path("workflow_run_id").asText(""));
		} catch (HttpTimeoutException ex) {
			throw error(HttpStatus.GATEWAY_TIMEOUT, "AI 分析等待超时，工作流可能仍在运行，请先查看 Dify 运行记录再手动重试");
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw error(HttpStatus.SERVICE_UNAVAILABLE, "AI 分析已中断，请稍后重试");
		} catch (IOException | IllegalArgumentException ex) {
			throw error(HttpStatus.BAD_GATEWAY, "无法读取 Dify 响应，请检查本机 Dify 服务及工作流配置");
		}
	}

	static void validateInput(JsonNode parameters, int length) {
		boolean found = false;
		for (JsonNode entry : parameters.path("user_input_form")) {
			var fields = entry.elements();
			while (fields.hasNext()) {
				JsonNode field = fields.next();
				String name = field.path("variable").asText();
				if ("environment_json".equals(name)) {
					found = true;
					if (field.has("max_length") && field.path("max_length").canConvertToInt() && field.path("max_length").asInt() < length)
						throw error(HttpStatus.UNPROCESSABLE_ENTITY, "Dify 已发布的 environment_json 长度不足，请调大开始节点输入上限并重新发布");
				} else if (field.path("required").asBoolean(false)) {
					throw error(HttpStatus.SERVICE_UNAVAILABLE, "Dify 输入契约不匹配，请导入并发布新版农业分析工作流");
				}
			}
		}
		if (!found) throw error(HttpStatus.SERVICE_UNAVAILABLE, "Dify 缺少 environment_json 输入，请配置新版农业分析工作流的密钥");
	}

	private JsonNode exchange(HttpClient client, String path, String body) throws IOException, InterruptedException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(properties.getBaseUrl().replaceAll("/+$", "") + "/" + path))
			.timeout(Duration.ofSeconds(body == null ? Math.min(15, properties.getTimeoutSeconds()) : properties.getTimeoutSeconds()))
			.header("Authorization", "Bearer " + properties.getApiKey().trim()).header("Accept", "application/json");
		if (body == null) builder.GET();
		else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
		HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		int status = response.statusCode();
		if (status == 401 || status == 403) throw error(HttpStatus.SERVICE_UNAVAILABLE, "Dify 应用认证失败，请检查服务端新版工作流密钥");
		if (status == 429) throw error(HttpStatus.TOO_MANY_REQUESTS, "Dify 当前请求较多或模型额度不足，请稍后重试");
		if (status < 200 || status >= 300) throw error(HttpStatus.BAD_GATEWAY, "Dify 请求未成功，请检查已发布工作流及模型服务");
		if (response.body().length() > 1_048_576) throw error(HttpStatus.BAD_GATEWAY, "Dify 响应超过处理上限");
		JsonNode parsed = json.readTree(response.body());
		if (parsed == null || !parsed.isObject()) throw error(HttpStatus.BAD_GATEWAY, "Dify 返回了无效响应");
		return parsed;
	}
	private static String output(JsonNode outputs, String field) {
		JsonNode value = outputs.path(field);
		if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 20000)
			throw error(HttpStatus.BAD_GATEWAY, "Dify 输出不完整或格式不匹配，请检查新版工作流结束节点");
		return value.asText().strip();
	}
	private static ResponseStatusException error(HttpStatus status, String text) { return new ResponseStatusException(status, text); }
}

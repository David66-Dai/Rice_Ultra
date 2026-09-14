package com.smartrice.server.astrbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartrice.server.realtime.PreventionControlProperties;
import com.smartrice.server.realtime.DeviceActivityService;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Delivers persisted diagnosis confirmations through AstrBot's proactive-message API. */
@Service
public class AstrBotAlertSender {

	private final PreventionControlProperties properties;
	private final AstrBotDiagnosisConfirmationRepository repository;
	private final ObjectMapper json;
	private final HttpClient http;
	private final DeviceActivityService activity;
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task ->
		Thread.ofPlatform().daemon(true).name("astrbot-diagnosis-alert").unstarted(task));

	@Autowired
	public AstrBotAlertSender(PreventionControlProperties properties,
			AstrBotDiagnosisConfirmationRepository repository, ObjectMapper json, DeviceActivityService activity) {
		this(properties, repository, json, activity,
			HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
	}

	AstrBotAlertSender(PreventionControlProperties properties,
			AstrBotDiagnosisConfirmationRepository repository, ObjectMapper json,
			DeviceActivityService activity, HttpClient http) {
		this.properties = properties;
		this.repository = repository;
		this.json = json;
		this.http = http;
		this.activity = activity;
	}

	public void sendAfterCommit(String id) {
		Runnable task = () -> executor.execute(() -> deliver(id));
		if (TransactionSynchronizationManager.isActualTransactionActive()
				&& TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override public void afterCommit() { task.run(); }
			});
		}
		else task.run();
	}

	/** Proactive feedback for automatic safety actions; direct slash commands reply in their own chat turn. */
	public void sendDeviceFeedback(String stationId, String device, boolean enabled, String action, String detail) {
		if (!properties.isAstrBotAlertEnabled()) return;
		executor.execute(() -> {
			try {
				Settings settings = settings();
				String message = feedbackMessage(stationId, device, action, detail);
				for (String umo : settings.umos()) send(settings, umo, message);
			}
			catch (RuntimeException ex) {
				activity.publishPestDisease(stationId, "AstrBot 防治设备反馈发送失败，请查看大屏共享状态");
			}
		});
	}

	private synchronized void deliver(String id) {
		AstrBotDiagnosisConfirmation row = repository.findById(id).orElse(null);
		if (row == null || !"PENDING".equals(row.getStatus()) || Instant.now().isAfter(row.getDueAt())) return;
		try {
			Settings settings = settings();
			for (String umo : settings.umos()) {
				for (String block : alertBlocks(row.getAlertMessage())) send(settings, umo, block);
			}
			row.setDeliveryStatus("SENT");
			row.setSentAt(Instant.now());
			row.setLastError(null);
			row.setAttempts(row.getAttempts() + 1);
			repository.saveAndFlush(row);
		}
		catch (RuntimeException ex) {
			row.setDeliveryStatus("FAILED");
			row.setAttempts(row.getAttempts() + 1);
			row.setLastError(truncate(ex.getMessage(), 1000));
			repository.saveAndFlush(row);
			if (row.getAttempts() == 1) {
				activity.publishPestDisease(row.getStationId(),
					"AstrBot 微信防治告警发送失败，确认单不会放行设备；后台将自动重试");
			}
			if (row.getAttempts() < 5 && Instant.now().isBefore(row.getDueAt())) {
				long delay = switch (row.getAttempts()) { case 1 -> 10; case 2 -> 30; case 3 -> 120; default -> 600; };
				executor.schedule(() -> deliver(id), delay, TimeUnit.SECONDS);
			}
		}
	}

	private void send(Settings settings, String umo, String message) {
		try {
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("umo", umo);
			// AstrBot's plain adapter may strip leading/trailing newlines. Zero-width spaces retain
			// the deliberate line breaks on platforms using that adapter.
			body.put("message", List.of(Map.of("type", "plain", "text", "\u200b" + message + "\u200b")));
			HttpRequest request = HttpRequest.newBuilder(settings.endpoint())
				.timeout(Duration.ofSeconds(15))
				.header("Authorization", "Bearer " + settings.apiKey())
				.header("Content-Type", "application/json; charset=utf-8")
				.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
				.build();
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.body() == null || response.body().length() > 65536
					|| response.statusCode() < 200 || response.statusCode() >= 300
					|| !"ok".equals(json.readTree(response.body()).path("status").asText())) {
				throw new IllegalStateException("AstrBot 微信告警接口拒绝请求");
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("AstrBot 微信告警发送被中断", ex);
		}
		catch (Exception ex) {
			if (ex instanceof RuntimeException runtime) throw runtime;
			throw new IllegalStateException("AstrBot 微信告警发送失败", ex);
		}
	}

	private Settings settings() {
		if (!properties.isAstrBotAlertEnabled()) throw new IllegalStateException("AstrBot 微信告警未启用");
		String key = properties.getAstrBotApiKey() == null ? "" : properties.getAstrBotApiKey().trim();
		List<String> umos = properties.getAlertUmos().stream().filter(value -> value != null && !value.isBlank())
			.map(String::trim).distinct().toList();
		URI base;
		try { base = URI.create(properties.getAstrBotBaseUrl().trim()); }
		catch (RuntimeException ex) { throw new IllegalStateException("AstrBot 告警地址无效"); }
		boolean loopback = base.getHost() != null && List.of("127.0.0.1", "localhost", "::1").contains(base.getHost());
		if (base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
				|| !(base.getPath() == null || base.getPath().isEmpty() || "/".equals(base.getPath()))
				|| !("https".equalsIgnoreCase(base.getScheme()) || ("http".equalsIgnoreCase(base.getScheme()) && loopback))
				|| key.isEmpty() || umos.isEmpty()) {
			throw new IllegalStateException("AstrBot 微信告警配置不完整或不安全");
		}
		String normalized = base.toString().replaceAll("/+$", "");
		return new Settings(URI.create(normalized + "/api/v1/im/message"), key, umos);
	}

	@EventListener(ApplicationReadyEvent.class)
	void recover() {
		for (AstrBotDiagnosisConfirmation row : repository.findByStatusAndDeliveryStatusIn(
			"PENDING", List.of("PENDING", "FAILED"))) {
			if (row.getAttempts() < 5 && Instant.now().isBefore(row.getDueAt())) sendAfterCommit(row.getId());
		}
	}

	private static String truncate(String value, int maximum) {
		if (value == null) return "AstrBot 微信告警发送失败";
		return value.length() <= maximum ? value : value.substring(0, maximum);
	}

	/** Weixin OC flattens newlines in one text item, so each alert section becomes its own bubble. */
	static List<String> alertBlocks(String message) {
		List<String> lines = java.util.Arrays.stream((message == null ? "" : message).split("\\R"))
			.map(String::trim).filter(line -> !line.isEmpty()).toList();
		if (lines.size() < 5) return lines.isEmpty() ? List.of("🔴【病虫害防治确认】") : lines;
		String title = lines.get(0) + " " + lines.get(1);
		String recognition = lines.get(2) + "；" + lines.get(3);
		String confirmation = lines.get(4);
		String instruction = String.join(" ", lines.subList(5, lines.size()));
		return List.of(title, recognition, confirmation, instruction);
	}

	static String feedbackMessage(String stationId, String device, String action, String detail) {
		return "✅【防治设备反馈】站点：" + stationId + "｜设备：" + deviceName(device)
			+ "｜操作：" + action + "｜结果：" + detail + " 实际状态请以现场设备反馈为准。";
	}

	private static String deviceName(String device) {
		return "pump".equals(device) ? "智能喷药" : "lamp".equals(device) ? "智能驱虫灯" : "设备";
	}

	@PreDestroy
	void close() { executor.shutdownNow(); }

	private record Settings(URI endpoint, String apiKey, List<String> umos) {}
}

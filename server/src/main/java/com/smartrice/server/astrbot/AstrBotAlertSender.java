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

/**
 * Delivers persisted diagnosis confirmations through AstrBot's proactive-message API.
 *
 * <p>Every configured alert session (WeChat, QQ, ...) is delivered, retried and recorded on its
 * own row. One platform being unreachable can neither stop another platform from being alerted
 * nor invalidate a confirmation the reachable platform already received, and a retry never
 * re-sends to a session that already got the alert.</p>
 */
@Service
public class AstrBotAlertSender {

	private static final int MAX_ATTEMPTS = 5;

	private final PreventionControlProperties properties;
	private final AstrBotDiagnosisConfirmationRepository repository;
	private final AstrBotDiagnosisConfirmationTargetRepository targets;
	private final ObjectMapper json;
	private final HttpClient http;
	private final DeviceActivityService activity;
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task ->
		Thread.ofPlatform().daemon(true).name("astrbot-diagnosis-alert").unstarted(task));

	@Autowired
	public AstrBotAlertSender(PreventionControlProperties properties,
			AstrBotDiagnosisConfirmationRepository repository,
			AstrBotDiagnosisConfirmationTargetRepository targets, ObjectMapper json,
			DeviceActivityService activity) {
		this(properties, repository, targets, json, activity,
			HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
	}

	AstrBotAlertSender(PreventionControlProperties properties,
			AstrBotDiagnosisConfirmationRepository repository,
			AstrBotDiagnosisConfirmationTargetRepository targets, ObjectMapper json,
			DeviceActivityService activity, HttpClient http) {
		this.properties = properties;
		this.repository = repository;
		this.targets = targets;
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
			Settings settings;
			try {
				settings = settings();
			}
			catch (RuntimeException ex) {
				activity.publishPestDisease(stationId, "AstrBot 防治设备反馈发送失败，请查看大屏共享状态");
				return;
			}
			String message = feedbackMessage(stationId, device, action, detail);
			int failed = 0;
			for (String umo : settings.umos()) {
				// Each session is attempted on its own: WeChat failing must not silence QQ.
				try {
					send(settings, umo, message);
				}
				catch (RuntimeException ex) {
					failed++;
				}
			}
			if (failed > 0) {
				activity.publishPestDisease(stationId, failed == settings.umos().size()
					? "AstrBot 防治设备反馈发送失败，请查看大屏共享状态"
					: "AstrBot 防治设备反馈有 " + failed + " 个会话未送达，请查看大屏共享状态");
			}
		});
	}

	/** One delivery pass: every configured session is attempted independently. */
	private void deliver(String id) {
		List<String> umos;
		try {
			umos = settings().umos();
		}
		catch (RuntimeException ex) {
			recordConfigurationFailure(id, ex);
			return;
		}
		for (String umo : umos) {
			deliverTarget(id, umo);
		}
	}

	private synchronized void deliverTarget(String id, String umo) {
		AstrBotDiagnosisConfirmation row = repository.findById(id).orElse(null);
		if (!deliverable(row)) return;
		AstrBotDiagnosisConfirmationTarget target = targets
			.findById(new AstrBotDiagnosisConfirmationTarget.Key(id, umo))
			.orElseGet(() -> new AstrBotDiagnosisConfirmationTarget(id, umo));
		// A session that already received this alert is never messaged again.
		if (target.isDelivered()) return;
		target.setAttempts(target.getAttempts() + 1);
		try {
			Settings settings = settings();
			if (!settings.umos().contains(umo)) return;
			for (String block : alertBlocks(row.getAlertMessage())) send(settings, umo, block);
			target.setDeliveryStatus("SENT");
			target.setSentAt(Instant.now());
			target.setLastError(null);
			targets.saveAndFlush(target);
			summarize(row, settings.umos());
		}
		catch (RuntimeException ex) {
			target.setDeliveryStatus("FAILED");
			target.setLastError(truncate(ex.getMessage(), 1000));
			targets.saveAndFlush(target);
			row.setAttempts(row.getAttempts() + 1);
			row.setLastError(target.getLastError());
			summarize(row, configuredUmos());
			if (row.getAttempts() == 1) {
				activity.publishPestDisease(row.getStationId(),
					"AstrBot 防治消息告警发送失败，确认单不会放行设备；后台将自动重试");
			}
			if (target.getAttempts() < MAX_ATTEMPTS && Instant.now().isBefore(row.getDueAt())) {
				executor.schedule(() -> deliverTarget(id, umo), backoff(target.getAttempts()), TimeUnit.SECONDS);
			}
		}
	}

	/** Alert configuration is broken or unsafe, so no session is reachable: retry the whole pass. */
	private synchronized void recordConfigurationFailure(String id, RuntimeException cause) {
		AstrBotDiagnosisConfirmation row = repository.findById(id).orElse(null);
		if (!deliverable(row)) return;
		row.setAttempts(row.getAttempts() + 1);
		row.setLastError(truncate(cause.getMessage(), 1000));
		summarize(row, List.of());
		if (row.getAttempts() == 1) {
			activity.publishPestDisease(row.getStationId(),
				"AstrBot 防治消息告警发送失败，确认单不会放行设备；后台将自动重试");
		}
		if (row.getAttempts() < MAX_ATTEMPTS && Instant.now().isBefore(row.getDueAt())) {
			executor.schedule(() -> deliver(id), backoff(row.getAttempts()), TimeUnit.SECONDS);
		}
	}

	/**
	 * The parent row only summarises its sessions for the dashboard and for restart recovery.
	 * Whether a chat may confirm is always decided from that same chat's own session row.
	 */
	private void summarize(AstrBotDiagnosisConfirmation row, List<String> configured) {
		List<AstrBotDiagnosisConfirmationTarget> rows = targets.findByKeyConfirmationId(row.getId());
		List<String> delivered = rows.stream().filter(AstrBotDiagnosisConfirmationTarget::isDelivered)
			.map(AstrBotDiagnosisConfirmationTarget::getUmo).toList();
		boolean anyFailed = rows.stream().anyMatch(target -> "FAILED".equals(target.getDeliveryStatus()));
		boolean everySessionDelivered = !configured.isEmpty() && delivered.containsAll(configured);
		// An empty configured list means the alert settings themselves are broken or unsafe:
		// nothing is reachable, which the dashboard must read as a failure, not as "still sending".
		row.setDeliveryStatus(everySessionDelivered ? "SENT"
			: !delivered.isEmpty() ? "PARTIAL"
			: anyFailed || configured.isEmpty() ? "FAILED" : "PENDING");
		row.setSentAt(rows.stream().filter(AstrBotDiagnosisConfirmationTarget::isDelivered)
			.map(AstrBotDiagnosisConfirmationTarget::getSentAt)
			.filter(sentAt -> sentAt != null).min(Instant::compareTo).orElse(null));
		if ("SENT".equals(row.getDeliveryStatus())) row.setLastError(null);
		repository.saveAndFlush(row);
	}

	private static boolean deliverable(AstrBotDiagnosisConfirmation row) {
		return row != null && "PENDING".equals(row.getStatus()) && Instant.now().isBefore(row.getDueAt());
	}

	private static long backoff(int attempts) {
		return switch (attempts) { case 1 -> 10; case 2 -> 30; case 3 -> 120; default -> 600; };
	}

	private List<String> configuredUmos() {
		try {
			return settings().umos();
		}
		catch (RuntimeException ex) {
			return List.of();
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
				throw new IllegalStateException("AstrBot 消息告警接口拒绝请求");
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("AstrBot 消息告警发送被中断", ex);
		}
		catch (Exception ex) {
			if (ex instanceof RuntimeException runtime) throw runtime;
			throw new IllegalStateException("AstrBot 消息告警发送失败", ex);
		}
	}

	private Settings settings() {
		if (!properties.isAstrBotAlertEnabled()) throw new IllegalStateException("AstrBot 消息告警未启用");
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
			throw new IllegalStateException("AstrBot 消息告警配置不完整或不安全");
		}
		String normalized = base.toString().replaceAll("/+$", "");
		return new Settings(URI.create(normalized + "/api/v1/im/message"), key, umos);
	}

	@EventListener(ApplicationReadyEvent.class)
	void recover() {
		// Sessions already marked SENT are skipped inside deliverTarget, so a restart only
		// retries the sessions that never received the alert.
		for (AstrBotDiagnosisConfirmation row : repository.findByStatusAndDeliveryStatusIn(
			"PENDING", List.of("PENDING", "PARTIAL", "FAILED"))) {
			if (Instant.now().isBefore(row.getDueAt())) sendAfterCommit(row.getId());
		}
	}

	private static String truncate(String value, int maximum) {
		if (value == null) return "AstrBot 消息告警发送失败";
		return value.length() <= maximum ? value : value.substring(0, maximum);
	}

	/** Some AstrBot adapters flatten newlines in one text item, so each alert section is sent separately. */
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

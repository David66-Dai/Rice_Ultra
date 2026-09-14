package com.smartrice.server.astrbot;

import com.smartrice.server.auth.UserAccount;
import com.smartrice.server.auth.UserAccountRepository;
import com.smartrice.server.realtime.DeviceActivityService;
import com.smartrice.server.realtime.DeviceControlRequest;
import com.smartrice.server.realtime.DeviceControlResponse;
import com.smartrice.server.realtime.DeviceSyncResponse;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AstrBotIntegrationService {

	private static final int MAX_REQUEST_CACHE = 1000;
	private static final Pattern EXPLICIT_TEST = Pattern.compile("测试|\\b(?:test|agri_test)\\b",
		Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	private final AstrBotControlProperties properties;
	private final UserAccountRepository users;
	private final DeviceActivityService devices;
	private final AstrBotScheduledStopRepository stops;
	private final AstrBotAlertSender alerts;
	private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task ->
		Thread.ofPlatform().daemon(true).name("astrbot-device-auto-off").unstarted(task));
	private final LinkedHashMap<UUID, RequestRecord> requests = new LinkedHashMap<>();

	public AstrBotIntegrationService(AstrBotControlProperties properties, UserAccountRepository users,
			DeviceActivityService devices, AstrBotScheduledStopRepository stops, AstrBotAlertSender alerts) {
		this.properties = properties;
		this.users = users;
		this.devices = devices;
		this.stops = stops;
		this.alerts = alerts;
	}

	public AstrBotDeviceSnapshot snapshot(String token, AstrBotIdentity identity) {
		UserAccount user = authenticate(token, identity);
		DeviceSyncResponse snapshot = devices.snapshotForUserId(user.getId());
		Duration ttl = properties.getConfirmationTtl();
		long ttlSeconds = ttl == null ? 120 : Math.max(15, Math.min(600, ttl.toSeconds()));
		return new AstrBotDeviceSnapshot(user.getUsername(), user.getDisplayName(), snapshot.canControl(),
			snapshot.available(), snapshot.devices(), properties.isAllowTestControl(), ttlSeconds,
			configuredDefaultSprayDuration(), true, configuredMaxDuration());
	}

	public synchronized AstrBotControlResponse control(String token, AstrBotControlRequest request) {
		UserAccount user = authenticate(token, request.identity());
		String device = normalizeDevice(request.device());
		validateControl(request, device);
		String fingerprint = fingerprint(request, device, user.getId());
		RequestRecord previous = requests.get(request.requestId());
		if (previous != null) {
			if (!previous.fingerprint().equals(fingerprint)) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "同一请求编号不能更改设备、状态或测试模式");
			}
			if (previous.response() != null) {
				return previous.response();
			}
			throw new ResponseStatusException(HttpStatus.CONFLICT, "该请求此前结果不确定，请同步状态后使用新消息决定下一步");
		}
		if (request.enabled()) {
			AstrBotScheduledStop persisted = stops.findById(request.requestId().toString()).orElse(null);
			if (persisted != null) {
				if (!persisted.getFingerprint().equals(fingerprint)) {
					throw new ResponseStatusException(HttpStatus.CONFLICT, "同一请求编号不能更改设备、状态或测试模式");
				}
				throw new ResponseStatusException(HttpStatus.CONFLICT,
					"该开启请求已由先前进程处理，请查看共享状态，不要重复开启");
			}
		}
		remember(request.requestId(), new RequestRecord(fingerprint, null));
		AstrBotScheduledStop scheduled = null;
		try {
			if (request.enabled()) {
				Instant dueAt = request.durationSeconds() == null
					? Instant.now() : Instant.now().plusSeconds(request.durationSeconds());
				scheduled = stops.saveAndFlush(new AstrBotScheduledStop(request.requestId().toString(),
					user.getUsername(), user.getDisplayName(), request.stationId().toUpperCase(Locale.ROOT), device,
					request.expectedRevision() + 1, dueAt,
					"PREPARED", fingerprint));
			}
			DeviceControlResponse result = devices.controlForUserId(user.getId(), new DeviceControlRequest(
				request.stationId(), device, request.enabled(), request.expectedRevision()));
			Instant autoOffAt = null;
			if (request.enabled() && request.durationSeconds() != null) {
				autoOffAt = result.sentAt().plusSeconds(request.durationSeconds());
				scheduled.setExpectedRevision(result.state().revision());
				scheduled.setStatus("SCHEDULED");
				stops.saveAndFlush(scheduled);
				scheduleStop(scheduled, request.durationSeconds(), 0, false);
			}
			else if (request.enabled()) {
				scheduled.setExpectedRevision(result.state().revision());
				scheduled.setStatus("INDEFINITE");
				stops.saveAndFlush(scheduled);
			}
			AstrBotControlResponse response = new AstrBotControlResponse(request.requestId(), user.getUsername(),
				request.testMode(), request.testMode(), autoOffAt, result);
			requests.put(request.requestId(), new RequestRecord(fingerprint, response));
			return response;
		}
		catch (RuntimeException ex) {
			// Keep the reservation. Neither the plugin nor a network retry may issue a second ON for this ID.
			if (scheduled != null && request.enabled()) {
				// If the write was uncertain, the shared state has advanced to this expected revision;
				// if no write was attempted, the revision mismatch makes this a harmless no-op.
				scheduleStop(scheduled, 0, 0, false);
			}
			throw ex;
		}
	}

	/** Recognition-confirmed spray uses the same durable 60-second default timer as AstrBot spray. */
	public synchronized Instant scheduleConfirmedDiagnosisStop(UUID confirmationId, UserAccount user,
			DeviceControlResponse control) {
		int duration = configuredDefaultSprayDuration();
		Instant dueAt = control.sentAt().plusSeconds(duration);
		AstrBotScheduledStop stop = new AstrBotScheduledStop(confirmationId.toString(), user.getUsername(),
			user.getDisplayName(), control.stationId(), control.device(), control.state().revision(), dueAt,
			"SCHEDULED", digest("diagnosis-confirmation|" + confirmationId));
		try {
			stops.saveAndFlush(stop);
			scheduleStop(stop, duration, 0, false);
			return dueAt;
		}
		catch (RuntimeException ex) {
			// Never leave confirmed spraying unbounded when its durable safety timer cannot be saved.
			devices.automaticStop(user.getUsername(), user.getDisplayName(), new DeviceControlRequest(
				control.stationId(), control.device(), false, control.state().revision()));
			throw ex;
		}
	}

	private void validateControl(AstrBotControlRequest request, String device) {
		if (!"S01".equalsIgnoreCase(request.stationId().trim())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前仅 S01 支持设备控制");
		}
		if (request.enabled()) {
			boolean indefiniteLamp = "lamp".equals(device) && request.durationSeconds() == null;
			if ((!indefiniteLamp && request.durationSeconds() == null)
					|| (request.durationSeconds() != null && request.durationSeconds() > configuredMaxDuration())) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"喷药必须提供时长；驱虫灯可不提供时长持续开启，显式时长须在 1 到 "
						+ configuredMaxDuration() + " 秒之间");
			}
			if (request.testMode()) {
				if (request.durationSeconds() == null) {
					throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "测试开启必须提供有限运行时长");
				}
				if (!properties.isAllowTestControl()) {
					throw new ResponseStatusException(HttpStatus.FORBIDDEN, "AstrBot 测试控制未启用");
				}
				if (request.originalText() == null || !EXPLICIT_TEST.matcher(request.originalText()).find()) {
					throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "测试控制必须来自明确包含“测试”或 test 的原消息");
				}
			}
			else if (!request.confirmed()) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "普通 AstrBot 开启操作需要二次确认");
			}
		}
		else if (request.durationSeconds() != null || request.testMode()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "停止操作不能携带运行时长或测试模式");
		}
		if (!("pump".equals(device) || "lamp".equals(device))) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备必须为 pump 或 lamp");
		}
	}

	/** Shared authentication gate for the read-only AstrBot agriculture adapter. */
	UserAccount authenticate(String token, AstrBotIdentity identity) {
		String configured = properties.getApiToken();
		if (!properties.isEnabled() || configured == null || configured.length() < 32) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AstrBot 控制集成尚未配置");
		}
		byte[] actual = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
		if (!MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8), actual)) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "AstrBot 集成令牌无效");
		}
		if (identity == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 AstrBot 身份");
		}
		List<AstrBotControlProperties.IdentityBinding> matches = properties.getIdentities().stream()
			.filter(binding -> identity.umo().equals(binding.getUmo())
				&& identity.senderId().equals(binding.getSenderId()))
			.toList();
		if (matches.size() != 1 || matches.getFirst().getUsername() == null
				|| matches.getFirst().getUsername().isBlank()) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN,
				matches.isEmpty() ? "当前 AstrBot 会话或发送者没有映射到平台账号"
					: "当前 AstrBot 身份存在重复或无效的平台账号映射");
		}
		String username = matches.getFirst().getUsername();
		UserAccount user = users.findByUsernameIgnoreCase(username.trim())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "映射的平台账号不存在"));
		if (!user.isEnabled()) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "映射的平台账号已停用");
		}
		return user;
	}

	private void scheduleStop(AstrBotScheduledStop stop, int durationSeconds, int attempt, boolean recovered) {
		long delay = attempt == 0 ? durationSeconds : 5;
		if (delay == 0) {
			performStop(stop, durationSeconds, attempt, recovered);
		}
		else {
			timer.schedule(() -> performStop(stop, durationSeconds, attempt, recovered), delay, TimeUnit.SECONDS);
		}
	}

	private void performStop(AstrBotScheduledStop stop, int durationSeconds, int attempt, boolean recovered) {
		try {
			DeviceControlResponse result;
			if (recovered) {
				result = devices.automaticStopAfterRestart(stop.getUsername(), stop.getDisplayName(),
					stop.getStationId(), stop.getDevice(), stop.getExpectedRevision());
			}
			else {
				result = devices.automaticStop(stop.getUsername(), stop.getDisplayName(), new DeviceControlRequest(
					stop.getStationId(), stop.getDevice(), false, stop.getExpectedRevision()));
			}
			finishStop(stop, "COMPLETED");
			alerts.sendDeviceFeedback(result.stationId(), result.device(), false, "自动关闭",
				recovered ? "服务重启后已执行安全关闭。" : "已到达设定时长，已执行自动关闭。");
		}
		catch (ResponseStatusException ex) {
			// Retry while the same revision remains current. A conflict means a newer user action won.
			if (ex.getStatusCode().value() == 503) {
				scheduleStop(stop, durationSeconds, attempt + 1, recovered);
			}
			else if (ex.getStatusCode().value() == 409) {
				finishStop(stop, "SUPERSEDED");
				alerts.sendDeviceFeedback(stop.getStationId(), stop.getDevice(), false, "自动关闭已取消",
					"设备状态已被新的操作更新，旧定时任务未覆盖新操作。");
			}
		}
		catch (RuntimeException ignored) {
			// The shared device service already publishes unknown state for uncertain writes.
			alerts.sendDeviceFeedback(stop.getStationId(), stop.getDevice(), false, "自动关闭结果未知",
				"请查看大屏共享状态并现场检查设备。");
		}
	}

	private void finishStop(AstrBotScheduledStop stop, String status) {
		stop.setStatus(status);
		stops.saveAndFlush(stop);
	}

	@EventListener(ApplicationReadyEvent.class)
	void recoverScheduledStops() {
		for (AstrBotScheduledStop stop : stops.findByStatusIn(List.of("PREPARED", "SCHEDULED"))) {
			// After a process restart, stop immediately. DeviceActivityService permits recovery only
			// while its state remains untouched/unknown, so a newer web command always wins.
			scheduleStop(stop, 0, 0, true);
		}
	}

	private void remember(UUID id, RequestRecord record) {
		if (requests.size() >= MAX_REQUEST_CACHE) {
			requests.remove(requests.keySet().iterator().next());
		}
		requests.put(id, record);
	}

	private int configuredMaxDuration() {
		return Math.max(1, Math.min(3600, properties.getMaxDurationSeconds()));
	}

	private int configuredDefaultSprayDuration() {
		return Math.max(1, Math.min(configuredMaxDuration(), properties.getDefaultSprayDurationSeconds()));
	}

	private static String digest(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}

	private static String normalizeDevice(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}

	private static String fingerprint(AstrBotControlRequest request, String device, Long userId) {
		String value = userId + "|" + request.identity().umo() + "|" + request.identity().senderId() + "|"
			+ request.stationId().toUpperCase(Locale.ROOT) + "|" + device + "|" + request.enabled() + "|"
			+ request.expectedRevision() + "|" + request.durationSeconds() + "|" + request.testMode();
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}

	@PreDestroy
	void close() {
		timer.shutdownNow();
	}

	private record RequestRecord(String fingerprint, AstrBotControlResponse response) {
	}
}

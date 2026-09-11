package com.smartrice.server.realtime;

import com.smartrice.server.auth.AuthException;
import com.smartrice.server.auth.JwtService;
import com.smartrice.server.auth.UserAccount;
import com.smartrice.server.auth.UserAccountRepository;
import com.smartrice.server.notifications.NotificationEvent;
import com.smartrice.server.notifications.NotificationEventRepository;
import com.smartrice.server.notifications.NotificationReadState;
import com.smartrice.server.notifications.NotificationReadStateRepository;
import com.smartrice.server.notifications.PlatformNotification;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;

/**
 * One server owns the physical serial port. Its lock covers revision checks, writes and publication.
 * State describes the last successful command and resets to unknown on server restart.
 * Notifications and each user's read cursor survive restarts in the configured database.
 */
@Service
public class DeviceActivityService {

	private final UserAccountRepository users;
	private final DevicesProperties permissions;
	private final ObjectProvider<SerialSensorCollector> serial;
	private final NotificationEventRepository events;
	private final NotificationReadStateRepository reads;
	private final TransactionTemplate transactions;
	private final Map<String, DeviceState> devices = new LinkedHashMap<>();
	private final Map<DeferredResult<DeviceSyncResponse>, Jwt> waiting = new LinkedHashMap<>();
	private final String session = UUID.randomUUID().toString();
	private long sequence;

	public DeviceActivityService(UserAccountRepository users, DevicesProperties permissions,
			ObjectProvider<SerialSensorCollector> serial, NotificationEventRepository events,
			NotificationReadStateRepository reads, PlatformTransactionManager transactionManager) {
		this.users = users;
		this.permissions = permissions;
		this.serial = serial;
		this.events = events;
		this.reads = reads;
		this.transactions = new TransactionTemplate(transactionManager);
		devices.put("pump", new DeviceState("S01", "pump", null, 0, null, null));
		devices.put("lamp", new DeviceState("S01", "lamp", null, 0, null, null));
	}

	public synchronized DeviceControlResponse control(Jwt jwt, DeviceControlRequest request) {
		return controlAs(currentUser(jwt), request, true, false);
	}

	/** Restricted integration entry point; it re-reads the mapped platform account on every request. */
	public synchronized DeviceControlResponse controlForUserId(Long userId, DeviceControlRequest request) {
		return controlAs(currentUser(userId), request, true, false);
	}

	/** Safety timer for an accepted command. Revoking permission must not prevent its scheduled stop. */
	public synchronized DeviceControlResponse automaticStop(String username, String displayName,
			DeviceControlRequest request) {
		DeviceControlRequest effective = request;
		String normalized = request.device() == null ? "" : request.device().trim().toLowerCase(Locale.ROOT);
		DeviceState current = devices.get(normalized);
		// A failed stop write advances this service's state to unknown. Retrying the same safety stop
		// is allowed only while no different actor has written a newer command.
		if (current != null && current.enabled() == null && Objects.equals(username, current.updatedBy())
				&& request.expectedRevision() != null && current.revision() > request.expectedRevision()) {
			effective = new DeviceControlRequest(request.stationId(), normalized, false, current.revision());
		}
		return controlAs(new Actor(username, displayName), effective, false, true);
	}

	/** Crash recovery may stop only the untouched, unknown state of a freshly started service. */
	public synchronized DeviceControlResponse automaticStopAfterRestart(String username, String displayName,
			String stationId, String device) {
		String normalized = device == null ? "" : device.trim().toLowerCase(Locale.ROOT);
		DeviceState state = devices.get(normalized);
		if (state == null || state.enabled() != null
				|| (state.revision() != 0 && !Objects.equals(username, state.updatedBy()))) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
				"服务重启后的设备状态已被新操作更新，不执行旧定时停止");
		}
		return controlAs(new Actor(username, displayName),
			new DeviceControlRequest(stationId, normalized, false, state.revision()), false, true);
	}

	private DeviceControlResponse controlAs(UserAccount user, DeviceControlRequest request,
			boolean enforcePermission, boolean automatic) {
		return controlAs(new Actor(user.getUsername(), user.getDisplayName()), request, enforcePermission, automatic);
	}

	private DeviceControlResponse controlAs(Actor actor, DeviceControlRequest request,
			boolean enforcePermission, boolean automatic) {
		if (enforcePermission && !permissions.permits(actor.username())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号没有设备控制权限，请联系管理员授权");
		}
		if (request.stationId() == null || !"S01".equalsIgnoreCase(request.stationId().trim())) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前仅 S01 支持设备控制");
		}
		String device = request.device() == null ? "" : request.device().trim().toLowerCase(Locale.ROOT);
		if (!devices.containsKey(device) || request.enabled() == null || request.expectedRevision() == null
				|| request.expectedRevision() < 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提供 pump 或 lamp、enabled 和有效的 expectedRevision");
		}
		DeviceState previous = devices.get(device);
		if (request.expectedRevision() != previous.revision()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "设备状态已被其他操作更新，请同步最新状态后重试");
		}
		SerialSensorCollector collector = serial.getIfAvailable();
		if (collector == null || !collector.isConnected()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "设备串口当前未连接");
		}
		int code = commandCode(device, request.enabled());
		Instant now = Instant.now();
		String function = "pump".equals(device) ? "智能灌溉水泵" : "智能驱虫灯";
		String displayName = actor.displayName();
		String actorLabel = displayName == null || displayName.isBlank() || displayName.equals(actor.username())
			? actor.username() : displayName + "（" + actor.username() + "）";
		String action = request.enabled() ? "开启" : automatic ? "定时关闭" : "关闭";
		String message = actorLabel + "用户" + action + function + "功能";
		boolean[] writeAttempted = {false};
		try {
			transactions.executeWithoutResult(status -> {
				// Flush first so a broken database never starts an unrecordable hardware action.
				events.saveAndFlush(new NotificationEvent("device_control", message, now, "S01",
					actor.username(), displayName, device, request.enabled()));
				try {
					writeAttempted[0] = true;
					collector.sendCommand(code);
				}
				catch (RuntimeException ex) {
					throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "设备指令发送失败，请检查设备连接", ex);
				}
			});
		}
		catch (RuntimeException ex) {
			// A partial serial write or a failed DB commit after writing is uncertain.
			// Publish unknown state to browsers, without a successful-control notification.
			if (writeAttempted[0]) {
				devices.put(device, new DeviceState("S01", device, null, previous.revision() + 1, now, actor.username()));
				changed();
			}
			if (ex instanceof ResponseStatusException responseError) {
				throw responseError;
			}
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
				writeAttempted[0] ? "设备记录失败，状态不确定，请检查设备后重试" : "通知存储暂不可用，未发送设备指令", ex);
		}
		DeviceState next = new DeviceState("S01", device, request.enabled(), previous.revision() + 1,
			now, actor.username());
		devices.put(device, next);
		changed();
		return response(next, code);
	}

	public synchronized DeferredResult<DeviceSyncResponse> sync(Jwt jwt, String after, int waitSeconds) {
		UserAccount user = currentUser(jwt);
		int wait = Math.max(0, Math.min(25, waitSeconds));
		DeferredResult<DeviceSyncResponse> result = new DeferredResult<>((long) wait * 1000);
		if (wait == 0 || after == null || !after.equals(cursor())) {
			result.setResult(snapshot(user));
			return result;
		}
		result.onTimeout(() -> complete(result, jwt));
		result.onCompletion(() -> remove(result));
		result.onError(error -> remove(result));
		waiting.put(result, jwt);
		return result;
	}

	public synchronized DeviceSyncResponse markRead(Jwt jwt, long throughId) {
		UserAccount user = currentUser(jwt);
		if (throughId < 0) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "throughId 不能为负数");
		}
		long previous = readThrough(user.getId());
		long next = Math.max(previous, Math.min(throughId, events.latestId()));
		if (next > previous) {
			reads.saveAndFlush(new NotificationReadState(user.getId(), next));
			changed();
		}
		return snapshot(user);
	}

	/** Immediate snapshot used by a narrowly scoped server-side integration. */
	public synchronized DeviceSyncResponse snapshotForUserId(Long userId) {
		return snapshot(currentUser(userId));
	}

	/** Trusted internal extension point for diagnosis jobs; intentionally has no public publish API. */
	public synchronized PlatformNotification publishPestDisease(String stationId, String message) {
		if (message == null || message.isBlank() || message.length() > 2000
				|| (stationId != null && !stationId.matches("S(?:0[1-9]|10)"))) {
			throw new IllegalArgumentException("病虫害通知需要有效站点及 1-2000 字消息");
		}
		NotificationEvent saved = events.saveAndFlush(new NotificationEvent("pest_disease", message.trim(),
			Instant.now(), stationId, null, null, null, null));
		changed();
		return saved.toResponse();
	}

	private UserAccount currentUser(Jwt jwt) {
		if (jwt == null || !(jwt.getClaims().get(JwtService.CLAIM_UID) instanceof Number uid)
				|| (jwt.getExpiresAt() != null && !jwt.getExpiresAt().isAfter(Instant.now()))) {
			throw AuthException.unauthorized();
		}
		return currentUser(uid.longValue());
	}

	private UserAccount currentUser(Long userId) {
		if (userId == null) {
			throw AuthException.unauthorized();
		}
		UserAccount user = users.findById(userId).orElseThrow(AuthException::unauthorized);
		if (!user.isEnabled()) {
			throw AuthException.accountDisabled();
		}
		return user;
	}

	private DeviceSyncResponse snapshot(UserAccount user) {
		SerialSensorCollector collector = serial.getIfAvailable();
		return new DeviceSyncResponse(cursor(), permissions.permits(user.getUsername()),
			collector != null && collector.isConnected(), List.copyOf(devices.values()),
			events.findTop100ByOrderByIdDesc().stream().map(NotificationEvent::toResponse).toList(),
			events.countByIdGreaterThan(readThrough(user.getId())));
	}

	private long readThrough(Long userId) {
		return reads.findById(userId).map(NotificationReadState::getThroughId).orElse(0L);
	}

	private String cursor() {
		return session + ":" + sequence;
	}

	private void changed() {
		sequence++;
		List<Map.Entry<DeferredResult<DeviceSyncResponse>, Jwt>> pending = new ArrayList<>(waiting.entrySet());
		waiting.clear();
		for (var entry : pending) {
			complete(entry.getKey(), entry.getValue());
		}
	}

	private synchronized void complete(DeferredResult<DeviceSyncResponse> result, Jwt jwt) {
		waiting.remove(result);
		if (!result.isSetOrExpired()) {
			try {
				result.setResult(snapshot(currentUser(jwt)));
			}
			catch (RuntimeException ex) {
				result.setErrorResult(ex);
			}
		}
	}

	private synchronized void remove(DeferredResult<DeviceSyncResponse> result) {
		waiting.remove(result);
	}

	private static int commandCode(String device, boolean enabled) {
		return "pump".equals(device) ? (enabled ? 0x01 : 0x03) : (enabled ? 0x02 : 0x04);
	}

	private static DeviceControlResponse response(DeviceState state, int code) {
		return new DeviceControlResponse(state.stationId(), state.device(), state.enabled(),
			"FA%02X".formatted(code), state.updatedAt(), state);
	}

	private record Actor(String username, String displayName) {
	}
}

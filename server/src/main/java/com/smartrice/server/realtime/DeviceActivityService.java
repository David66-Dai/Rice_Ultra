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
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;

/**
 * Authenticated orchestration for every web, AstrBot and diagnosis device action.
 * Persistent device state is the shared source of truth; this service adds authorization,
 * optimistic revisions, notifications and long-poll wakeups around the single command path.
 */
@Service
public class DeviceActivityService {

	private final UserAccountRepository users;
	private final DevicesProperties permissions;
	private final DeviceCommandService commands;
	private final NotificationEventRepository events;
	private final NotificationReadStateRepository reads;
	private final TransactionTemplate transactions;
	private final TransactionTemplate recoveryTransactions;
	private final Map<String, DeviceState> uncertainOverrides = new LinkedHashMap<>();
	private final Map<DeferredResult<DeviceSyncResponse>, Jwt> waiting = new LinkedHashMap<>();
	private final String session = UUID.randomUUID().toString();
	private long sequence;

	public DeviceActivityService(UserAccountRepository users, DevicesProperties permissions,
			DeviceCommandService commands, NotificationEventRepository events,
			NotificationReadStateRepository reads, PlatformTransactionManager transactionManager) {
		this.users = users;
		this.permissions = permissions;
		this.commands = commands;
		this.events = events;
		this.reads = reads;
		this.transactions = new TransactionTemplate(transactionManager);
		this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.recoveryTransactions = new TransactionTemplate(transactionManager);
		this.recoveryTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public DeviceStatusResponse status(String stationId) {
		return commands.status(stationId);
	}

	public synchronized DeviceControlResponse control(Jwt jwt, DeviceControlRequest request) {
		boolean compatibilityRequest = request != null && request.expectedRevision() == null;
		return controlAs(currentUser(jwt), request, true, false, compatibilityRequest);
	}

	/** Restricted integration entry point; it re-reads the mapped platform account on every request. */
	public synchronized DeviceControlResponse controlForUserId(Long userId, DeviceControlRequest request) {
		return controlAs(currentUser(userId), request, true, false, false);
	}

	/** A red diagnosis may act only with the initiating platform user's current permission. */
	public synchronized DeviceControlResponse enableFromDiagnosis(Jwt jwt, String stationId, String device) {
		UserAccount user = currentUser(jwt);
		DeviceState current = currentState(stationId, device);
		return controlAs(user, new DeviceControlRequest(stationId, device, true, current.revision()),
			true, false, true);
	}

	/** System diagnosis linkage shares the same persistent state, notification and wakeup path. */
	public synchronized DeviceControlResponse enableFromDiagnosis(String username, String displayName,
			String stationId, String device) {
		DeviceState current = currentState(stationId, device);
		return controlAs(new Actor(username, displayName),
			new DeviceControlRequest(stationId, device, true, current.revision()), false, false, true);
	}

	/** Safety timer for an accepted command. Revoking permission must not prevent its scheduled stop. */
	public synchronized DeviceControlResponse automaticStop(String username, String displayName,
			DeviceControlRequest request) {
		DeviceControlRequest effective = request;
		String device = request.device() == null ? "" : request.device().trim().toLowerCase(Locale.ROOT);
		DeviceState current = currentState(request.stationId(), device);
		// A failed stop advances the durable state to unknown. The same timer may retry only
		// while no different actor has written a newer command.
		if (current.enabled() == null && Objects.equals(username, current.updatedBy())
				&& request.expectedRevision() != null && current.revision() > request.expectedRevision()) {
			effective = new DeviceControlRequest(request.stationId(), device, false, current.revision());
		}
		return controlAs(new Actor(username, displayName), effective, false, true, false);
	}

	/** Restart recovery stops only the still-current command recorded for this scheduled action. */
	public synchronized DeviceControlResponse automaticStopAfterRestart(String username, String displayName,
			String stationId, String device, long expectedRevision) {
		DeviceState state = currentState(stationId, device);
		if (state.revision() != expectedRevision || Boolean.FALSE.equals(state.enabled())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
				"服务重启后的设备状态已被新操作更新，不执行旧定时停止");
		}
		return controlAs(new Actor(username, displayName),
			new DeviceControlRequest(stationId, device, false, expectedRevision), false, true, false);
	}

	private DeviceControlResponse controlAs(UserAccount user, DeviceControlRequest request,
			boolean enforcePermission, boolean automatic, boolean skipIfAlreadyDesired) {
		return controlAs(new Actor(user.getUsername(), user.getDisplayName()), request,
			enforcePermission, automatic, skipIfAlreadyDesired);
	}

	private DeviceControlResponse controlAs(Actor actor, DeviceControlRequest request,
			boolean enforcePermission, boolean automatic, boolean skipIfAlreadyDesired) {
		if (enforcePermission && !permissions.permits(actor.username())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号没有设备控制权限，请联系管理员授权");
		}
		if (request == null || request.enabled() == null
				|| (request.expectedRevision() != null && request.expectedRevision() < 0)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请提供 enabled 和有效的 expectedRevision");
		}
		DeviceState previous = currentState(request.stationId(), request.device());
		long expectedRevision = request.expectedRevision() == null
			? previous.revision() : request.expectedRevision();
		if (expectedRevision != previous.revision()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "设备状态已被其他操作更新，请同步最新状态后重试");
		}
		boolean absentMeansOff = request.expectedRevision() == null && previous.enabled() == null;
		if (skipIfAlreadyDesired && (Objects.equals(previous.enabled(), request.enabled())
				|| (absentMeansOff && !request.enabled()))) {
			return commands.noOpResponse(previous.stationId(), previous.device(), request.enabled());
		}
		if (!commands.available()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "设备串口当前未连接");
		}

		Instant attemptedAt = Instant.now();
		String function = DeviceCommandService.PUMP.equals(previous.device()) ? "智能灌溉水泵" : "智能驱虫灯";
		String displayName = actor.displayName();
		String actorLabel = displayName == null || displayName.isBlank() || displayName.equals(actor.username())
			? actor.username() : displayName + "（" + actor.username() + "）";
		String action = request.enabled() ? "开启" : automatic ? "定时关闭" : "关闭";
		String message = actorLabel + "用户" + action + function + "功能";
		boolean[] writeAttempted = {false};
		DeviceControlResponse[] response = {null};
		try {
			transactions.executeWithoutResult(status -> {
				// Flush first so broken notification storage never starts an unrecordable action.
				events.saveAndFlush(new NotificationEvent("device_control", message, attemptedAt,
					previous.stationId(), actor.username(), displayName, previous.device(), request.enabled()));
				response[0] = commands.apply(previous.stationId(), previous.device(), request.enabled(),
					expectedRevision, actor.username(), () -> writeAttempted[0] = true);
			});
		}
		catch (RuntimeException ex) {
			if (writeAttempted[0]) {
				persistUnknown(previous, actor.username(), attemptedAt);
				changed();
			}
			if (ex instanceof ResponseStatusException responseError) {
				throw responseError;
			}
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
				writeAttempted[0] ? "设备记录失败，状态不确定，请检查设备后重试"
					: "通知存储暂不可用，未发送设备指令", ex);
		}
		uncertainOverrides.remove(previous.device());
		changed();
		return response[0];
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
		List<DeviceState> states = commands.states().stream().map(this::withUncertainty).toList();
		return new DeviceSyncResponse(cursor(), permissions.permits(user.getUsername()), commands.available(), states,
			events.findTop100ByOrderByIdDesc().stream().map(NotificationEvent::toResponse).toList(),
			events.countByIdGreaterThan(readThrough(user.getId())));
	}

	private DeviceState currentState(String stationId, String device) {
		return withUncertainty(commands.state(stationId, device));
	}

	private DeviceState withUncertainty(DeviceState persisted) {
		DeviceState uncertain = uncertainOverrides.get(persisted.device());
		return uncertain != null && uncertain.revision() >= persisted.revision() ? uncertain : persisted;
	}

	private void persistUnknown(DeviceState previous, String actor, Instant attemptedAt) {
		DeviceState fallback = new DeviceState(previous.stationId(), previous.device(), null,
			previous.revision() + 1, attemptedAt, actor);
		try {
			DeviceState persisted = recoveryTransactions.execute(status -> commands.markUnknown(
				previous.stationId(), previous.device(), actor, previous.revision(), attemptedAt));
			uncertainOverrides.put(previous.device(), persisted == null ? fallback : persisted);
		}
		catch (RuntimeException ignored) {
			uncertainOverrides.put(previous.device(), fallback);
		}
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

	private record Actor(String username, String displayName) {
	}
}

package com.smartrice.server.astrbot;

import com.smartrice.server.auth.UserAccount;
import com.smartrice.server.diagnosis.AlertLevel;
import com.smartrice.server.diagnosis.InspectionDiagnosis;
import com.smartrice.server.diagnosis.InspectionDiagnosisRepository;
import com.smartrice.server.realtime.DeviceActivityService;
import com.smartrice.server.realtime.DeviceCommandService;
import com.smartrice.server.realtime.DeviceControlResponse;
import com.smartrice.server.realtime.DeviceState;
import com.smartrice.server.realtime.PreventionControlProperties;
import com.smartrice.server.realtime.PreventionPolicyService;
import com.smartrice.server.realtime.PreventionSafetyGate;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AstrBotDiagnosisConfirmationService {
	private static final Pattern EXPLICIT_CONFIRMATION = Pattern.compile(
		"确认|同意|执行|\\bconfirm\\b|agri_confirm_alert", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	private static final Pattern NEGATED_CONFIRMATION = Pattern.compile(
		"(?:不|不要|别|拒绝|取消).{0,8}(?:确认|同意|执行|confirm|agri_confirm_alert)",
		Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	private final AstrBotDiagnosisConfirmationRepository confirmations;
	private final AstrBotDiagnosisConfirmationTargetRepository targets;
	private final InspectionDiagnosisRepository diagnoses;
	private final AstrBotIntegrationService integration;
	private final DeviceActivityService devices;
	private final PreventionPolicyService policy;
	private final PreventionSafetyGate safety;
	private final PreventionControlProperties properties;
	private final AstrBotAlertSender alerts;

	public AstrBotDiagnosisConfirmationService(AstrBotDiagnosisConfirmationRepository confirmations,
			AstrBotDiagnosisConfirmationTargetRepository targets,
			InspectionDiagnosisRepository diagnoses, AstrBotIntegrationService integration,
			DeviceActivityService devices, PreventionPolicyService policy, PreventionSafetyGate safety,
			PreventionControlProperties properties, AstrBotAlertSender alerts) {
		this.confirmations = confirmations;
		this.targets = targets;
		this.diagnoses = diagnoses;
		this.integration = integration;
		this.devices = devices;
		this.policy = policy;
		this.safety = safety;
		this.properties = properties;
		this.alerts = alerts;
	}

	public synchronized AstrBotDiagnosisConfirmation prepare(InspectionDiagnosis diagnosis,
			String device, String summary) {
		if (diagnosis == null || diagnosis.getId() == null || AlertLevel.parse(diagnosis.getAlertLevel()) != AlertLevel.RED) {
			throw new IllegalArgumentException("只有已保存的红色识别结果可以创建防治确认");
		}
		AstrBotDiagnosisConfirmation existing = confirmations.findByDiagnosisId(diagnosis.getId()).orElse(null);
		if (existing != null) return existing;
		DeviceState state = devices.currentStateForIntegration(diagnosis.getStationId(), device);
		Instant now = Instant.now();
		Duration ttl = properties.getDiagnosisConfirmationTtl();
		if (ttl == null || ttl.isNegative() || ttl.isZero()) ttl = Duration.ofMinutes(10);
		if (ttl.compareTo(Duration.ofMinutes(1)) < 0) ttl = Duration.ofMinutes(1);
		if (ttl.compareTo(Duration.ofHours(1)) > 0) ttl = Duration.ofHours(1);
		String id = UUID.randomUUID().toString();
		String message = String.join("\n",
			"🔴 Rice Ultra 病虫害防治确认",
			"站点：" + diagnosis.getStationId(),
			summary,
			"拟开启：" + (DeviceCommandService.PUMP.equals(device) ? "智能喷药" : "智能驱虫灯"),
			"确认编号：" + id,
			"请在 " + ttl.toMinutes() + " 分钟内由收到告警的授权用户发送：/agri_confirm_alert " + id,
			"确认时后端会再次核验设备版本与安全条件；未确认不会开启设备。");
		AstrBotDiagnosisConfirmation row = confirmations.saveAndFlush(new AstrBotDiagnosisConfirmation(id,
			diagnosis.getId(), diagnosis.getStationId(), device, state.revision(), message, now, now.plus(ttl)));
		alerts.sendAfterCommit(row.getId());
		return row;
	}

	public synchronized AstrBotDiagnosisConfirmationResponse confirm(String token,
			AstrBotDiagnosisConfirmationRequest request) {
		UserAccount user = integration.authenticate(token, request.identity());
		if (request.originalText() == null || !EXPLICIT_CONFIRMATION.matcher(request.originalText()).find()
				|| NEGATED_CONFIRMATION.matcher(request.originalText()).find()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须由用户当前原消息明确确认该 AstrBot 消息告警");
		}
		if (!properties.getAlertUmos().stream().filter(value -> value != null)
				.anyMatch(value -> value.trim().equals(request.identity().umo()))) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前 AstrBot 会话不是该告警的授权接收会话");
		}
		AstrBotDiagnosisConfirmation row = confirmations.findById(request.confirmationId().toString())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "防治确认编号不存在"));
		if ("CONFIRMED".equals(row.getStatus())) return response(row, user.getUsername(), null, null);
		if (!"PENDING".equals(row.getStatus())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "该防治确认已失效或结果未知，不能重复开启");
		}
		if (Instant.now().isAfter(row.getDueAt())) {
			row.setStatus("EXPIRED");
			confirmations.saveAndFlush(row);
			throw new ResponseStatusException(HttpStatus.CONFLICT, "防治确认已过期，请重新识别");
		}
		// Delivery is tracked per session, so only the chat that actually received this alert may
		// release the device. Another platform succeeding never speaks for this one.
		AstrBotDiagnosisConfirmationTarget target = targets
			.findById(new AstrBotDiagnosisConfirmationTarget.Key(row.getId(), request.identity().umo()))
			.orElse(null);
		if (target == null || !target.isDelivered()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
				"当前 AstrBot 会话尚未成功收到这条告警，不能确认开启");
		}
		if (!policy.current().requireAstrBotConfirmation()) {
			row.setStatus("CANCELLED");
			confirmations.saveAndFlush(row);
			throw new ResponseStatusException(HttpStatus.CONFLICT, "识别联动确认开关已关闭，该确认单已取消");
		}
		InspectionDiagnosis diagnosis = diagnoses.findById(row.getDiagnosisId())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "原始识别记录不存在"));
		String expectedDevice = "leaf".equals(diagnosis.getTask())
			? DeviceCommandService.PUMP : DeviceCommandService.LAMP;
		if (AlertLevel.parse(diagnosis.getAlertLevel()) != AlertLevel.RED
				|| !expectedDevice.equals(row.getDevice()) || !diagnosis.getStationId().equals(row.getStationId())) {
			row.setStatus("CANCELLED");
			confirmations.saveAndFlush(row);
			throw new ResponseStatusException(HttpStatus.CONFLICT, "原始识别依据不再符合防治确认要求");
		}
		DeviceState current = devices.currentStateForIntegration(row.getStationId(), row.getDevice());
		if (current.revision() != row.getExpectedRevision()) {
			row.setStatus("SUPERSEDED");
			confirmations.saveAndFlush(row);
			throw new ResponseStatusException(HttpStatus.CONFLICT, "设备状态已被其他操作更新，请重新识别");
		}
		safety.verifyStart(row.getStationId(), row.getDevice(), false);
		row.setStatus("EXECUTING");
		confirmations.saveAndFlush(row);
		try {
			DeviceControlResponse control = devices.enableFromConfirmedDiagnosis(user.getId(), row.getStationId(),
				row.getDevice(), row.getExpectedRevision());
			Instant autoOffAt = DeviceCommandService.PUMP.equals(row.getDevice())
				? integration.scheduleConfirmedDiagnosisStop(request.confirmationId(), user, control) : null;
			row.setStatus("CONFIRMED");
			row.setConfirmedAt(Instant.now());
			row.setConfirmedBy(user.getUsername());
			confirmations.saveAndFlush(row);
			return response(row, user.getUsername(), autoOffAt, control);
		}
		catch (ResponseStatusException ex) {
			row.setStatus(ex.getStatusCode().value() == 403 ? "PENDING" : "UNKNOWN");
			confirmations.saveAndFlush(row);
			throw ex;
		}
		catch (RuntimeException ex) {
			row.setStatus("UNKNOWN");
			confirmations.saveAndFlush(row);
			throw ex;
		}
	}

	private static AstrBotDiagnosisConfirmationResponse response(AstrBotDiagnosisConfirmation row,
			String username, Instant autoOffAt, DeviceControlResponse control) {
		return new AstrBotDiagnosisConfirmationResponse(UUID.fromString(row.getId()), row.getStatus(),
			row.getStationId(), row.getDevice(), username, row.getConfirmedAt(), autoOffAt, control);
	}
}

package com.smartrice.server.diagnosis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartrice.server.diagnosis.StationAlertListResponse.StationAlertStatus;
import com.smartrice.server.astrbot.AstrBotDiagnosisConfirmation;
import com.smartrice.server.astrbot.AstrBotDiagnosisConfirmationService;
import com.smartrice.server.realtime.DeviceActivityService;
import com.smartrice.server.realtime.DeviceCommandService;
import com.smartrice.server.realtime.PreventionPolicyService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DiagnosisService {

	static final Pattern STATION_ID = Pattern.compile("S(?:0[1-9]|10)");
	private static final List<String> STATIONS = List.of(
		"S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08", "S09", "S10");

	private final InferenceClient inference;
	private final InspectionDiagnosisRepository diagnoses;
	private final DeviceActivityService devices;
	private final ObjectMapper json;
	private final PreventionPolicyService preventionPolicy;
	private final AstrBotDiagnosisConfirmationService confirmations;

	public DiagnosisService(InferenceClient inference, InspectionDiagnosisRepository diagnoses,
			DeviceActivityService devices, ObjectMapper json, PreventionPolicyService preventionPolicy,
			AstrBotDiagnosisConfirmationService confirmations) {
		this.inference = inference;
		this.diagnoses = diagnoses;
		this.devices = devices;
		this.json = json;
		this.preventionPolicy = preventionPolicy;
		this.confirmations = confirmations;
	}

	@Transactional
	public DiagnosisResponse diagnose(String stationId, String task, MultipartFile file) {
		String station = validateStation(stationId);
		if (!"S01".equals(station)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "站点离线，暂不可识别");
		}
		String inferTask = validateTask(task);
		String kind = persistTask(inferTask);
		if (file == null || file.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
				"leaf-hsi".equals(inferTask) ? "请上传高光谱立方体（.h5）" : "请上传图片文件");
		}
		Map<String, Object> result = inference.predict(inferTask, file);
		ParsedPrediction parsed = parse(inferTask, result, file.getOriginalFilename());
		InspectionDiagnosis row = new InspectionDiagnosis();
		row.setStationId(station);
		row.setTask(kind);
		row.setFilename(truncate(parsed.filename, 255));
		row.setLabel(truncate(parsed.label, 128));
		row.setLabelZh(truncate(parsed.labelZh, 128));
		row.setConfidence(parsed.confidence);
		row.setDetectionCount(parsed.detectionCount);
		row.setAlertLevel(parsed.alert.json());
		row.setResultJson(writeJson(result));
		InspectionDiagnosis saved = diagnoses.save(row);
		String activatedDevice = null;
		String deviceError = null;
		AstrBotDiagnosisConfirmation confirmation = null;
		if (parsed.alert == AlertLevel.RED) {
			String device = "leaf".equals(kind) ? DeviceCommandService.PUMP : DeviceCommandService.LAMP;
			try {
				if (preventionPolicy.current().requireAstrBotConfirmation()) {
					confirmation = confirmations.prepare(saved, device, alertSubject(kind, parsed));
				}
				else {
					devices.enableFromDiagnosis("diagnosis", "系统识别", station, device);
					activatedDevice = device;
				}
			}
			catch (ResponseStatusException ex) {
				deviceError = ex.getReason() == null ? "设备联动失败" : ex.getReason();
			}
			catch (RuntimeException ex) {
				deviceError = "设备联动失败";
			}
		}
		if (parsed.alert != AlertLevel.GREEN) {
			devices.publishPestDiseaseAfterCommit(station,
				alertMessage(station, kind, parsed, activatedDevice, deviceError, confirmation), saved.getId());
		}
		return toResponse(saved, result, combinedAlert(station).json(), activatedDevice, deviceError, confirmation);
	}

	public StationAlertListResponse stationAlerts() {
		List<StationAlertStatus> stations = new ArrayList<>(STATIONS.size());
		for (String station : STATIONS) {
			stations.add(statusOf(station));
		}
		return new StationAlertListResponse(stations);
	}

	private StationAlertStatus statusOf(String station) {
		InspectionDiagnosis leaf = diagnoses.findFirstByStationIdAndTaskOrderByCreatedAtDesc(station, "leaf").orElse(null);
		InspectionDiagnosis pest = diagnoses.findFirstByStationIdAndTaskOrderByCreatedAtDesc(station, "pest").orElse(null);
		AlertLevel leafAlert = leaf == null ? AlertLevel.GREEN : AlertLevel.parse(leaf.getAlertLevel());
		AlertLevel pestAlert = pest == null ? AlertLevel.GREEN : AlertLevel.parse(pest.getAlertLevel());
		Instant updated = latest(leaf == null ? null : leaf.getCreatedAt(), pest == null ? null : pest.getCreatedAt());
		return new StationAlertStatus(
			station,
			AlertLevel.max(leafAlert, pestAlert).json(),
			leafAlert.json(),
			leaf == null ? null : leaf.getLabel(),
			leaf == null ? null : leaf.getLabelZh(),
			leaf == null ? null : leaf.getConfidence(),
			pestAlert.json(),
			pest == null ? null : pest.getDetectionCount(),
			pest == null ? null : pest.getLabelZh() != null ? pest.getLabelZh() : pest.getLabel(),
			updated
		);
	}

	private AlertLevel combinedAlert(String station) {
		AlertLevel leaf = diagnoses.findFirstByStationIdAndTaskOrderByCreatedAtDesc(station, "leaf")
			.map(row -> AlertLevel.parse(row.getAlertLevel()))
			.orElse(AlertLevel.GREEN);
		AlertLevel pest = diagnoses.findFirstByStationIdAndTaskOrderByCreatedAtDesc(station, "pest")
			.map(row -> AlertLevel.parse(row.getAlertLevel()))
			.orElse(AlertLevel.GREEN);
		return AlertLevel.max(leaf, pest);
	}

	static String validateStation(String stationId) {
		String normalized = stationId == null ? "" : stationId.trim().toUpperCase(Locale.ROOT);
		if (!STATION_ID.matcher(normalized).matches()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "stationId 必须为 S01-S10");
		}
		return normalized;
	}

	private static String validateTask(String task) {
		if ("leaf".equals(task) || "pest".equals(task) || "leaf-hsi".equals(task)) {
			return task;
		}
		throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "识别任务必须为 leaf、pest 或 leaf-hsi");
	}

	private static String persistTask(String task) {
		return "leaf-hsi".equals(task) ? "leaf" : task;
	}

	private ParsedPrediction parse(String task, Map<String, Object> result, String fallbackName) {
		String filename = firstText(result.get("filename"), fallbackName);
		if ("leaf".equals(task) || "leaf-hsi".equals(task)) {
			String label = firstText(result.get("label"), null);
			String labelZh = firstText(result.get("label_zh"), firstText(result.get("labelZh"), label));
			Double confidence = asDouble(result.get("confidence"));
			Boolean hasDamage = asBoolean(result.get("has_leaf_damage"));
			if (hasDamage == null) {
				hasDamage = asBoolean(result.get("hasLeafDamage"));
			}
			AlertLevel alert = "leaf-hsi".equals(task)
				? AlertLevel.fromLeafHsi(label, labelZh, hasDamage)
				: AlertLevel.fromLeaf(label, labelZh, hasDamage);
			return new ParsedPrediction(filename, label, labelZh, confidence, 0, alert);
		}
		int count = pestCount(result);
		String pestLabel = pestLabel(result);
		Double confidence = pestConfidence(result);
		return new ParsedPrediction(filename, pestLabel, pestLabel, confidence, count, AlertLevel.fromPest(count));
	}

	private static int pestCount(Map<String, Object> result) {
		Number count = asNumber(result.get("count"));
		if (count != null) {
			return Math.max(0, count.intValue());
		}
		Object detections = result.get("detections");
		if (detections instanceof Collection<?> items) {
			return items.size();
		}
		return 0;
	}

	private static String pestLabel(Map<String, Object> result) {
		List<Map<String, Object>> detections = detections(result.get("detections"));
		if (detections.isEmpty()) {
			return null;
		}
		Map<String, Integer> votes = new LinkedHashMap<>();
		for (Map<String, Object> detection : detections) {
			String name = firstText(detection.get("class_name"), firstText(detection.get("className"), "未命名"));
			votes.merge(name, 1, Integer::sum);
		}
		return votes.entrySet().stream()
			.max(Map.Entry.comparingByValue())
			.map(Map.Entry::getKey)
			.orElse(null);
	}

	private static Double pestConfidence(Map<String, Object> result) {
		List<Map<String, Object>> detections = detections(result.get("detections"));
		return detections.stream()
			.map(item -> asDouble(item.get("confidence")))
			.filter(value -> value != null)
			.max(Double::compareTo)
			.orElse(null);
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> detections(Object raw) {
		if (!(raw instanceof Collection<?> items)) {
			return List.of();
		}
		List<Map<String, Object>> detections = new ArrayList<>();
		for (Object item : items) {
			if (item instanceof Map<?, ?> map) {
				detections.add((Map<String, Object>) map);
			}
		}
		return detections;
	}

	private DiagnosisResponse toResponse(InspectionDiagnosis row, Map<String, Object> result, String stationAlert,
			String activatedDevice, String deviceError, AstrBotDiagnosisConfirmation confirmation) {
		return new DiagnosisResponse(
			row.getId(),
			row.getStationId(),
			row.getTask(),
			row.getFilename(),
			row.getLabel(),
			row.getLabelZh(),
			row.getConfidence(),
			row.getDetectionCount(),
			row.getAlertLevel(),
			stationAlert,
			row.getCreatedAt(),
			result,
			activatedDevice,
			deviceError,
			confirmation != null,
			confirmation == null ? null : confirmation.getId(),
			confirmation == null ? null : confirmation.getDeliveryStatus()
		);
	}

	private String writeJson(Map<String, Object> result) {
		try {
			return json.writeValueAsString(result);
		}
		catch (JsonProcessingException ex) {
			return "{}";
		}
	}

	private static Instant latest(Instant left, Instant right) {
		if (left == null) {
			return right;
		}
		if (right == null) {
			return left;
		}
		return left.isAfter(right) ? left : right;
	}

	private static String firstText(Object value, String fallback) {
		if (value instanceof String text && !text.isBlank()) {
			return text.trim();
		}
		return fallback;
	}

	private static String truncate(String value, int max) {
		if (value == null) {
			return null;
		}
		return value.length() <= max ? value : value.substring(0, max);
	}

	private static Number asNumber(Object value) {
		return value instanceof Number number ? number : null;
	}

	private static Double asDouble(Object value) {
		return value instanceof Number number ? number.doubleValue() : null;
	}

	private static Boolean asBoolean(Object value) {
		if (value instanceof Boolean flag) {
			return flag;
		}
		return null;
	}

	private static String alertMessage(String station, String task, ParsedPrediction parsed,
			String activatedDevice, String deviceError, AstrBotDiagnosisConfirmation confirmation) {
		String level = parsed.alert == AlertLevel.RED ? "红色告警" : "黄色预警";
		String subject = alertSubject(task, parsed);
		String linkage = "";
		if (confirmation != null) {
			linkage = "；等待 AstrBot 微信确认，确认编号 " + confirmation.getId() + "，未确认不会开启设备";
		}
		else if (activatedDevice != null) {
			linkage = "；已联动开启" + (DeviceCommandService.PUMP.equals(activatedDevice) ? "喷药" : "驱虫灯");
		}
		else if (deviceError != null) {
			linkage = "；设备联动未执行：" + deviceError;
		}
		return station + " " + level + "：" + subject + linkage;
	}

	private static String alertSubject(String task, ParsedPrediction parsed) {
		String subject;
		if ("leaf".equals(task)) {
			String label = firstText(parsed.labelZh, firstText(parsed.label, "未知病害"));
			String confidence = parsed.confidence == null ? ""
				: String.format(Locale.ROOT, "，置信度 %.1f%%", parsed.confidence <= 1
					? parsed.confidence * 100 : parsed.confidence);
			subject = "识别到" + label + confidence;
		}
		else {
			String label = firstText(parsed.labelZh, firstText(parsed.label, "虫害"));
			subject = "识别到" + label + "，共 " + parsed.detectionCount + " 只";
		}
		return subject;
	}

	private record ParsedPrediction(
		String filename,
		String label,
		String labelZh,
		Double confidence,
		int detectionCount,
		AlertLevel alert
	) {
	}
}

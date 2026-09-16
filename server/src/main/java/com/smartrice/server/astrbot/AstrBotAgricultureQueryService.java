package com.smartrice.server.astrbot;

import com.smartrice.server.astrbot.AstrBotAlertQueryResponse.AlertItem;
import com.smartrice.server.auth.UserAccount;
import com.smartrice.server.diagnosis.InspectionDiagnosis;
import com.smartrice.server.diagnosis.InspectionDiagnosisRepository;
import com.smartrice.server.notifications.NotificationEventRepository;
import com.smartrice.server.pest.PestDiseaseService;
import com.smartrice.server.pest.PestDiseaseSummary;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Read-only successor to the legacy AstrBot plugin that connected to MySQL directly. */
@Service
public class AstrBotAgricultureQueryService {

	private static final ZoneId FIELD_ZONE = ZoneId.of("Asia/Shanghai");
	private static final List<String> STATIONS = List.of(
		"S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08", "S09", "S10");
	private static final List<String> ALERT_LEVELS = List.of("yellow", "red");
	private static final int MAX_ALERTS = 200;

	private final AstrBotIntegrationService integration;
	private final AstrBotControlProperties properties;
	private final PestDiseaseService pestDiseases;
	private final InspectionDiagnosisRepository diagnoses;
	private final NotificationEventRepository notifications;

	public AstrBotAgricultureQueryService(AstrBotIntegrationService integration,
			AstrBotControlProperties properties, PestDiseaseService pestDiseases,
			InspectionDiagnosisRepository diagnoses, NotificationEventRepository notifications) {
		this.integration = integration;
		this.properties = properties;
		this.pestDiseases = pestDiseases;
		this.diagnoses = diagnoses;
		this.notifications = notifications;
	}

	public AstrBotQueryStatus status(String token, AstrBotIdentity identity) {
		UserAccount user = integration.authenticate(token, identity);
		return new AstrBotQueryStatus(user.getUsername(), user.getDisplayName(), "S01",
			maxRangeDays(), "Rice Ultra Java API: inspection_diagnosis + Hive archive");
	}

	public AstrBotAgricultureQueryResponse data(String token, AstrBotAgricultureQueryRequest request) {
		UserAccount user = integration.authenticate(token, request.identity());
		String station = station(request.stationId(), true);
		LocalDate start = date(request.startDate(), "startDate");
		LocalDate end = date(request.endDate(), "endDate");
		validateRange(start, end);
		List<String> targets = "ALL".equals(station) ? STATIONS : List.of(station);
		List<PestDiseaseSummary> days = new ArrayList<>();
		for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
			for (String target : targets) {
				days.add(pestDiseases.daily(target, day));
			}
		}
		return new AstrBotAgricultureQueryResponse(user.getUsername(), station, start, end,
			days.size(), List.copyOf(days));
	}

	public AstrBotAlertQueryResponse alerts(String token, AstrBotAgricultureQueryRequest request) {
		UserAccount user = integration.authenticate(token, request.identity());
		String station = station(request.stationId(), true);
		LocalDate start = date(request.startDate(), "startDate");
		LocalDate end = date(request.endDate(), "endDate");
		validateRange(start, end);
		Instant from = start.atStartOfDay(FIELD_ZONE).toInstant();
		Instant until = end.plusDays(1).atStartOfDay(FIELD_ZONE).toInstant();
		var page = PageRequest.of(0, MAX_ALERTS + 1);
		List<InspectionDiagnosis> rows = "ALL".equals(station)
			? diagnoses.findByAlertLevelInAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
				ALERT_LEVELS, from, until, page)
			: diagnoses.findByStationIdAndAlertLevelInAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
				station, ALERT_LEVELS, from, until, page);
		boolean truncated = rows.size() > MAX_ALERTS;
		List<InspectionDiagnosis> selected = rows.stream().limit(MAX_ALERTS).toList();
		Set<Long> published = selected.isEmpty() ? Set.of()
			: notifications.findByTypeAndSourceIdIn("pest_disease",
				selected.stream().map(diagnosis -> Objects.requireNonNull(diagnosis).getId()).toList()).stream()
				.map(item -> item.getSourceId()).collect(java.util.stream.Collectors.toSet());
		List<AlertItem> alerts = selected.stream().map(row -> alert(row, published.contains(row.getId()))).toList();
		return new AstrBotAlertQueryResponse(user.getUsername(), station, start, end,
			alerts.size(), truncated, alerts);
	}

	private AlertItem alert(InspectionDiagnosis row, boolean published) {
		return new AlertItem(row.getId(), row.getStationId(), row.getTask(), row.getAlertLevel(),
			row.getLabel(), row.getLabelZh(), row.getConfidence(), row.getDetectionCount(),
			row.getCreatedAt(), published ? "platform_published" : "not_tracked");
	}

	private int maxRangeDays() {
		return Math.max(1, Math.min(366, properties.getMaxQueryRangeDays()));
	}

	private void validateRange(LocalDate start, LocalDate end) {
		if (end.isBefore(start)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startDate 不能晚于 endDate");
		}
		long days = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
		if (days > maxRangeDays()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
				"单次最多查询 " + maxRangeDays() + " 天");
		}
	}

	private static LocalDate date(String value, String field) {
		try {
			String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
			LocalDate today = LocalDate.now(FIELD_ZONE);
			return switch (normalized) {
				case "today", "今天" -> today;
				case "yesterday", "昨天" -> today.minusDays(1);
				default -> LocalDate.parse(normalized);
			};
		}
		catch (RuntimeException ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
				field + " 必须是 YYYY-MM-DD、今天或昨天");
		}
	}

	private static String station(String value, boolean allowAll) {
		String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
		if (normalized.matches("ST-00[1-9]")) normalized = "S0" + normalized.charAt(5);
		else if ("ST-010".equals(normalized)) normalized = "S10";
		if (allowAll && List.of("ALL", "全部", "所有").contains(normalized)) return "ALL";
		if (!normalized.matches("S(?:0[1-9]|10)")) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "stationId 必须为 S01-S10 或 ALL");
		}
		return normalized;
	}
}

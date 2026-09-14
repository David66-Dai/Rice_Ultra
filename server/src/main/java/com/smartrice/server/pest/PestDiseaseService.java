package com.smartrice.server.pest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartrice.server.diagnosis.InspectionDiagnosis;
import com.smartrice.server.diagnosis.InspectionDiagnosisRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * One day of pest and disease figures for a station, summarised from the MySQL inspection records.
 *
 * <p>Only the current field day has inspection records to summarise. Earlier days return
 * {@link #NO_SAME_DAY_SOURCE} with the catalogue rows present but empty, and the dashboards fill
 * those in themselves — the backend never invents figures for a day it did not observe.
 */
@Service
public class PestDiseaseService {

	public static final String SOURCE = "inspection_diagnosis";
	/** Marks a day outside the inspection records, so a caller knows the blanks are not a failure. */
	public static final String NO_SAME_DAY_SOURCE = "none";
	static final String TABLE = "inspection_diagnosis";
	private static final ZoneId FIELD_ZONE = ZoneId.of("Asia/Shanghai");
	// A station records a handful of inspections per day; the cap only stops a runaway read.
	private static final int MAX_ROWS = 2000;

	private final InspectionDiagnosisRepository diagnoses;
	private final ObjectMapper json;
	private final Clock clock;

	@Autowired
	public PestDiseaseService(InspectionDiagnosisRepository diagnoses, ObjectMapper json) {
		this(diagnoses, json, Clock.system(FIELD_ZONE));
	}

	PestDiseaseService(InspectionDiagnosisRepository diagnoses, ObjectMapper json, Clock clock) {
		this.diagnoses = diagnoses;
		this.json = json;
		this.clock = clock;
	}

	public PestDiseaseSummary daily(String stationId, LocalDate date) {
		if (stationId == null || !stationId.matches("S(?:0[1-9]|10)") || date == null) {
			throw new IllegalArgumentException("Invalid pest and disease lookup");
		}
		if (!date.equals(LocalDate.now(clock.withZone(FIELD_ZONE)))) {
			return PestDiseaseSummary.empty(NO_SAME_DAY_SOURCE, "", stationId, date,
				List.of("该日期不是当天，没有可统计的田间巡检识别记录。"));
		}
		return sameDay(stationId, date);
	}

	private PestDiseaseSummary sameDay(String stationId, LocalDate date) {
		// created_at is stored as an instant, so the field day is bounded in Asia/Shanghai first.
		Instant start = date.atStartOfDay(FIELD_ZONE).toInstant();
		Instant end = date.plusDays(1).atStartOfDay(FIELD_ZONE).toInstant();
		List<InspectionDiagnosis> rows = diagnoses
			.findByStationIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
				stationId, start, end, PageRequest.of(0, MAX_ROWS));
		List<String> notes = new ArrayList<>(List.of(
			"病害为当天该站点识别出该病害的次数，虫害为当天各次识别检出的虫体只数合计。",
			"褐飞虱、白背飞虱、灰飞虱合并计入稻飞虱。",
			"识别为健康叶片的记录不计入任何病害。",
			"数据来自田间巡检的逐次识别结果，反映已拍摄的样本，不是全田普查。"
		));
		if (rows.isEmpty()) {
			notes.addFirst("当天该站点还没有巡检识别记录。");
			return PestDiseaseSummary.empty(SOURCE, TABLE, stationId, date, notes);
		}
		if (rows.size() >= MAX_ROWS) notes.add("当天识别记录超过 " + MAX_ROWS + " 条，仅统计最早的 " + MAX_ROWS + " 条。");

		Map<String, Integer> counts = new LinkedHashMap<>();
		Instant last = null;
		boolean degraded = false;
		for (InspectionDiagnosis row : rows) {
			if (row.getCreatedAt() != null && (last == null || row.getCreatedAt().isAfter(last))) last = row.getCreatedAt();
			String task = row.getTask() == null ? "" : row.getTask().trim().toLowerCase(Locale.ROOT);
			if ("leaf".equals(task)) {
				String key = PestDiseaseCatalog.diseaseKey(row.getLabelZh());
				if (key == null) key = PestDiseaseCatalog.diseaseKey(row.getLabel());
				if (key != null) counts.merge(key, 1, Integer::sum);
			} else if ("pest".equals(task)) {
				degraded |= !countDetections(row, counts);
			}
		}
		if (degraded) notes.add("部分虫害记录的识别明细无法解析，已按该次识别的主类别计入检出数量。");

		List<PestDiseaseSummary.Item> items = PestDiseaseCatalog.ENTRIES.stream()
			.map(entry -> {
				int value = counts.getOrDefault(entry.key(), 0);
				return new PestDiseaseSummary.Item(entry.key(), entry.category(), entry.label(), entry.unit(),
					value, PestDiseaseCatalog.alertLevel(entry, value));
			})
			.toList();
		return new PestDiseaseSummary(true, SOURCE, TABLE, stationId, date, last, rows.size(), items, notes);
	}

	/** Counts each detected insect by its own class; returns false when the detail had to be inferred. */
	private boolean countDetections(InspectionDiagnosis row, Map<String, Integer> counts) {
		JsonNode detections = null;
		try {
			JsonNode parsed = json.readTree(row.getResultJson() == null ? "" : row.getResultJson());
			JsonNode candidate = parsed.path("detections");
			if (candidate.isArray()) detections = candidate;
		} catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ex) {
			// The stored payload is treated as data; a malformed row must not fail the whole day.
			detections = null;
		}
		if (detections == null) {
			String key = PestDiseaseCatalog.pestKey(row.getLabelZh());
			if (key == null) key = PestDiseaseCatalog.pestKey(row.getLabel());
			if (key != null && row.getDetectionCount() > 0) counts.merge(key, row.getDetectionCount(), Integer::sum);
			return false;
		}
		for (JsonNode detection : detections) {
			String key = PestDiseaseCatalog.pestKey(detection.path("class_name").asText(null));
			if (key != null) counts.merge(key, 1, Integer::sum);
		}
		return true;
	}
}

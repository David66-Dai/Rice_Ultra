package com.smartrice.server.pest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartrice.server.diagnosis.InspectionDiagnosis;
import com.smartrice.server.diagnosis.InspectionDiagnosisFixtures;
import com.smartrice.server.diagnosis.InspectionDiagnosisRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PestDiseaseServiceTests {
	private static final ZoneId FIELD_ZONE = ZoneId.of("Asia/Shanghai");
	private static final LocalDate TODAY = LocalDate.of(2026, 9, 12);
	// 2026-09-12 06:00Z is 14:00 in the field, so the clock sits inside the field day.
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-12T06:00:00Z"), FIELD_ZONE);

	private final InspectionDiagnosisRepository diagnoses = mock(InspectionDiagnosisRepository.class);
	private final PestDiseaseService service = new PestDiseaseService(diagnoses, new ObjectMapper(), CLOCK);

	@Test
	void countsDiseaseRecognitionsAndEachDetectedInsectSeparately() {
		when(diagnoses.findByStationIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
			eq("S01"), any(), any(), any())).thenReturn(List.of(
				leaf("Tungro Virus", "东格鲁病毒", "2026-09-12T06:03:57Z"),
				leaf("Brown Spot", "褐斑病", "2026-09-12T06:04:01Z"),
				leaf("Tungro Virus", "东格鲁病毒", "2026-09-12T06:05:00Z"),
				// Two insects in one photo must count as two, not as one recognition.
				pest("稻纵卷叶螟", 2, "2026-09-12T06:03:51Z", "稻纵卷叶螟", "稻纵卷叶螟"),
				pest("褐飞虱", 1, "2026-09-12T06:59:56Z", "褐飞虱")));
		var summary = service.daily("S01", TODAY);
		assertThat(summary.available()).isTrue();
		assertThat(summary.source()).isEqualTo(PestDiseaseService.SOURCE);
		assertThat(summary.recognitionCount()).isEqualTo(5);
		assertThat(summary.lastDiagnosedAt()).isEqualTo(Instant.parse("2026-09-12T06:59:56Z"));
		assertThat(values(summary)).containsExactly(
			entry("bacterial_leaf_blight", 0), entry("brown_spot", 1), entry("tungro_virus", 2),
			entry("rice_planthopper", 1), entry("striped_stem_borer", 0), entry("rice_leaf_roller", 2));
	}

	@Test
	void threePlanthopperSpeciesFoldIntoTheSingleCatalogueRow() {
		when(diagnoses.findByStationIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
			eq("S01"), any(), any(), any())).thenReturn(List.of(
				pest("褐飞虱", 3, "2026-09-12T06:00:00Z", "褐飞虱", "白背飞虱", "灰飞虱")));
		assertThat(value(service.daily("S01", TODAY), "rice_planthopper")).isEqualTo(3);
	}

	@Test
	void healthyLeavesAndUnknownClassesAreNotCountedAsDisease() {
		when(diagnoses.findByStationIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
			eq("S01"), any(), any(), any())).thenReturn(List.of(
				leaf("Healthy Leaf", "健康叶片", "2026-09-12T06:00:00Z"),
				leaf("Leaf Smut", "叶黑粉病", "2026-09-12T06:01:00Z")));
		var summary = service.daily("S01", TODAY);
		assertThat(summary.recognitionCount()).isEqualTo(2);
		assertThat(values(summary)).allSatisfy(item -> assertThat(item.getValue()).isZero());
	}

	@Test
	void alertColoursFollowTheFieldInspectionThresholds() {
		when(diagnoses.findByStationIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
			eq("S01"), any(), any(), any())).thenReturn(List.of(
				leaf("Brown Spot", "褐斑病", "2026-09-12T06:00:00Z"),
				pest("二化螟", 1, "2026-09-12T06:01:00Z", "二化螟"),
				pest("稻纵卷叶螟", 2, "2026-09-12T06:02:00Z", "稻纵卷叶螟", "稻纵卷叶螟")));
		var summary = service.daily("S01", TODAY);
		assertThat(level(summary, "brown_spot")).isEqualTo("red");          // any disease recognition
		assertThat(level(summary, "bacterial_leaf_blight")).isEqualTo("green");
		assertThat(level(summary, "striped_stem_borer")).isEqualTo("yellow"); // exactly one insect
		assertThat(level(summary, "rice_leaf_roller")).isEqualTo("red");      // two or more
		assertThat(level(summary, "rice_planthopper")).isEqualTo("green");
	}

	@Test
	void malformedDetectionJsonFallsBackToTheTopClassAndSaysSo() {
		InspectionDiagnosis broken = row("pest", "褐飞虱", "褐飞虱", 4, "2026-09-12T06:00:00Z", "{not json");
		when(diagnoses.findByStationIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
			eq("S01"), any(), any(), any())).thenReturn(List.of(broken));
		var summary = service.daily("S01", TODAY);
		assertThat(value(summary, "rice_planthopper")).isEqualTo(4);
		assertThat(summary.notes()).anyMatch(note -> note.contains("无法解析"));
	}

	@Test
	void aDayWithNoRecognitionsStillReturnsTheFullCatalogueAtZero() {
		when(diagnoses.findByStationIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
			eq("S01"), any(), any(), any())).thenReturn(List.of());
		var summary = service.daily("S01", TODAY);
		assertThat(summary.available()).isFalse();
		assertThat(summary.source()).isEqualTo(PestDiseaseService.SOURCE);
		assertThat(summary.items()).hasSize(6).allSatisfy(item -> assertThat(item.value()).isNull());
		assertThat(summary.notes()).anyMatch(note -> note.contains("还没有巡检识别记录"));
	}

	@Test
	void earlierDaysAreNotQueriedAndComeBackWithoutValues() {
		var summary = service.daily("S01", TODAY.minusDays(1));
		assertThat(summary.available()).isFalse();
		assertThat(summary.source()).isEqualTo(PestDiseaseService.NO_SAME_DAY_SOURCE);
		assertThat(summary.items()).hasSize(6).allSatisfy(item -> assertThat(item.value()).isNull());
		verifyNoInteractions(diagnoses);
	}

	@Test
	void theFieldDayIsBoundedInBeijingTimeNotInUtc() {
		when(diagnoses.findByStationIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
			eq("S01"), any(), any(), any())).thenReturn(List.of());
		service.daily("S01", TODAY);
		var start = ArgumentCaptor.forClass(Instant.class);
		var end = ArgumentCaptor.forClass(Instant.class);
		verify(diagnoses).findByStationIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
			eq("S01"), start.capture(), end.capture(), any());
		assertThat(start.getValue()).isEqualTo(Instant.parse("2026-09-11T16:00:00Z"));
		assertThat(end.getValue()).isEqualTo(Instant.parse("2026-09-12T16:00:00Z"));
	}

	@Test
	void rejectsStationsAndDatesItCannotTrust() {
		assertThatThrownBy(() -> service.daily("S11", TODAY)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.daily("S01' OR 1=1", TODAY)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.daily("S01", null)).isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(diagnoses);
	}

	private static Map.Entry<String, Integer> entry(String key, int value) {
		return Map.entry(key, value);
	}

	private static List<Map.Entry<String, Integer>> values(PestDiseaseSummary summary) {
		return summary.items().stream().map(item -> Map.entry(item.key(), item.value())).toList();
	}

	private static Integer value(PestDiseaseSummary summary, String key) {
		return item(summary, key).value();
	}

	private static String level(PestDiseaseSummary summary, String key) {
		return item(summary, key).alertLevel();
	}

	private static PestDiseaseSummary.Item item(PestDiseaseSummary summary, String key) {
		return summary.items().stream().filter(item -> item.key().equals(key)).findFirst().orElseThrow();
	}

	private static InspectionDiagnosis leaf(String label, String labelZh, String at) {
		return row("leaf", label, labelZh, 0, at,
			"{\"task\":\"leaf\",\"label\":\"" + label + "\",\"label_zh\":\"" + labelZh + "\"}");
	}

	private static InspectionDiagnosis pest(String topLabel, int count, String at, String... classNames) {
		StringBuilder detections = new StringBuilder();
		for (String name : classNames) {
			if (!detections.isEmpty()) detections.append(',');
			detections.append("{\"class_name\":\"").append(name).append("\",\"confidence\":0.5}");
		}
		return row("pest", topLabel, topLabel, count, at,
			"{\"task\":\"pest\",\"count\":" + count + ",\"detections\":[" + detections + "]}");
	}

	private static InspectionDiagnosis row(String task, String label, String labelZh, int count, String at, String json) {
		return InspectionDiagnosisFixtures.row("S01", task, label, labelZh, count, "green", json, Instant.parse(at));
	}
}

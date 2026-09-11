package com.smartrice.server.ai;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Source observations and reproducible statistics; this is not a model-generated assessment. */
public record AiEvidence(
	String stationId,
	String hiveStation,
	LocalDate startDate,
	LocalDate endDate,
	int windowDays,
	int observedDays,
	List<LocalDate> missingDates,
	int rawRowCount,
	List<Metric> metrics,
	List<Day> daily,
	String growthStage,
	List<String> limitations,
	AiLegacyContext legacyContext
) {
	public AiEvidence {
		missingDates = List.copyOf(missingDates);
		metrics = List.copyOf(metrics);
		daily = List.copyOf(daily);
		limitations = List.copyOf(limitations);
	}

	public record Metric(String field, String label, String unit, int count, int missingCount,
		Double mean, Double min, Double max, Double first, Double last, Double change) {
	}

	public record Day(LocalDate date, Map<String, Double> values) {
		public Day {
			// Map.copyOf rejects nulls, while null is essential to the observation contract.
			values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
		}
	}
}

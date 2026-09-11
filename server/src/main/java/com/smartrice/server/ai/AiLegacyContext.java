package com.smartrice.server.ai;

import java.time.LocalDate;
import java.util.List;

/** Dated legacy references, explicitly separate from the current eleven-field environment evidence. */
public record AiLegacyContext(String source, String stationId, Disease disease, YieldReference yield,
	List<String> limitations) {
	public AiLegacyContext {
		limitations = List.copyOf(limitations);
	}

	public record Disease(boolean available, String sourceTable, LocalDate referenceDate, String matchType,
		List<Value> values) {
		public Disease {
			values = List.copyOf(values);
		}
	}

	public record Value(String field, String label, String unit, String value) {
	}

	public record YieldReference(boolean available, String sourceTable, Integer referenceYear, String season,
		String matchType, Double baselineKgPerMu) {
	}
}

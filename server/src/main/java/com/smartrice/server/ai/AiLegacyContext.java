package com.smartrice.server.ai;

import com.smartrice.server.pest.PestDiseaseSummary;
import java.util.List;

/** Dated references, explicitly separate from the current eleven-field environment evidence. */
public record AiLegacyContext(String source, String stationId, PestDiseaseSummary pestDisease, YieldReference yield,
	List<String> limitations) {
	public AiLegacyContext {
		limitations = List.copyOf(limitations);
	}

	public record YieldReference(boolean available, String sourceTable, Integer referenceYear, String season,
		String matchType, Double baselineKgPerMu) {
	}
}

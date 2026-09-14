package com.smartrice.server.pest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One day of pest and disease figures for a station, in the fixed catalogue order. Both dashboards
 * read this same shape so the card looks identical whichever source filled it in.
 */
public record PestDiseaseSummary(
	boolean available,
	/** {@code inspection_diagnosis} for same-day recognitions, {@code none} for any other day. */
	String source,
	String sourceTable,
	String stationId,
	LocalDate referenceDate,
	/** Latest recognition time of the day; empty when the day has no recognition records. */
	Instant lastDiagnosedAt,
	int recognitionCount,
	List<Item> items,
	List<String> notes
) {
	public PestDiseaseSummary {
		items = List.copyOf(items);
		notes = List.copyOf(notes);
	}

	public record Item(String key, String category, String label, String unit, Integer value, String alertLevel) {
	}

	/** Catalogue rows with no value, so an unavailable day still renders the full card. */
	public static PestDiseaseSummary empty(String source, String sourceTable, String stationId, LocalDate date,
			List<String> notes) {
		return new PestDiseaseSummary(false, source, sourceTable, stationId, date, null, 0,
			PestDiseaseCatalog.ENTRIES.stream()
				.map(entry -> new Item(entry.key(), entry.category(), entry.label(), entry.unit(), null,
					PestDiseaseCatalog.alertLevel(entry, null)))
				.toList(),
			notes);
	}
}

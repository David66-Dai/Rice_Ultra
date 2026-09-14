package com.smartrice.server.pest;

import com.smartrice.server.diagnosis.AlertLevel;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The fixed three diseases and three pests shown by both dashboards. The list never varies with the
 * day being viewed, so the card stays stable: an item without data keeps its place and reports no
 * value instead of disappearing.
 */
public final class PestDiseaseCatalog {

	public static final String DISEASE = "disease";
	public static final String PEST = "pest";

	/** Recognitions per day for diseases; individual insects counted for pests. */
	public static final String DISEASE_UNIT = "次";
	public static final String PEST_UNIT = "只";

	public static final List<Entry> ENTRIES = List.of(
		new Entry("bacterial_leaf_blight", DISEASE, "细菌性叶枯病", DISEASE_UNIT),
		new Entry("brown_spot", DISEASE, "褐斑病", DISEASE_UNIT),
		new Entry("tungro_virus", DISEASE, "东格鲁病毒", DISEASE_UNIT),
		// The pest model separates 褐飞虱 / 白背飞虱 / 灰飞虱, which all fold into this one row.
		new Entry("rice_planthopper", PEST, "稻飞虱", PEST_UNIT),
		new Entry("striped_stem_borer", PEST, "二化螟", PEST_UNIT),
		new Entry("rice_leaf_roller", PEST, "稻纵卷叶螟", PEST_UNIT)
	);

	/** Leaf classifier labels, English and Chinese, mapped onto the three catalogue diseases. */
	private static final Map<String, String> DISEASE_KEYS = Map.of(
		"bacterial leaf blight", "bacterial_leaf_blight",
		"细菌性叶枯病", "bacterial_leaf_blight",
		"白叶枯病", "bacterial_leaf_blight",
		"brown spot", "brown_spot",
		"褐斑病", "brown_spot",
		"tungro virus", "tungro_virus",
		"东格鲁病毒", "tungro_virus"
	);

	/** Pest detector class names mapped onto the three catalogue pests. */
	private static final Map<String, String> PEST_KEYS = Map.of(
		"褐飞虱", "rice_planthopper",
		"白背飞虱", "rice_planthopper",
		"灰飞虱", "rice_planthopper",
		"稻飞虱", "rice_planthopper",
		"二化螟", "striped_stem_borer",
		"稻纵卷叶螟", "rice_leaf_roller"
	);

	private PestDiseaseCatalog() {
	}

	public static String diseaseKey(String label) {
		return DISEASE_KEYS.get(normalize(label));
	}

	public static String pestKey(String className) {
		return PEST_KEYS.get(normalize(className));
	}

	/**
	 * Per item, using the same thresholds as the field inspection station light: any disease
	 * recognition is red, while a pest turns yellow at one insect and red from two.
	 */
	public static String alertLevel(Entry entry, Integer value) {
		if (value == null) return AlertLevel.GREEN.json();
		if (PEST.equals(entry.category())) return AlertLevel.fromPest(value).json();
		return (value > 0 ? AlertLevel.RED : AlertLevel.GREEN).json();
	}

	private static String normalize(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}

	public record Entry(String key, String category, String label, String unit) {
	}
}

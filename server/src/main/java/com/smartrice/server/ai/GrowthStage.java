package com.smartrice.server.ai;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Growth stage codes and their Chinese names. The dashboards call this 生长周期.
 *
 * <p>{@code farm.env_daily.growth_stage} stores exactly the seven Chinese names below, so the archive
 * maps onto the existing codes one to one. {@code unknown} is not a stored value: it only marks a day
 * the archive left blank.
 */
public final class GrowthStage {

	public static final String UNKNOWN = "unknown";
	/** Source of the effective stage: read from Hive, supplied by the operator, or neither. */
	public static final String FROM_ARCHIVE = "hive";
	public static final String FROM_USER = "user";
	public static final String NONE = "unknown";

	private static final Map<String, String> LABELS = new LinkedHashMap<>();
	private static final Map<String, String> CODES = new LinkedHashMap<>();

	static {
		LABELS.put("seedling", "育秧期");
		LABELS.put("tillering", "分蘖期");
		LABELS.put("jointing", "拔节期");
		LABELS.put("booting", "孕穗期");
		LABELS.put("heading", "抽穗期");
		LABELS.put("filling", "灌浆期");
		LABELS.put("mature", "成熟期");
		LABELS.forEach((code, label) -> CODES.put(label, code));
	}

	public static final Set<String> VALID = Set.of(UNKNOWN, "seedling", "tillering", "jointing",
		"booting", "heading", "filling", "mature");

	private GrowthStage() {
	}

	/** Returns the code for a stored Chinese name, or null when the value is absent or unrecognised. */
	public static String fromArchive(String storedLabel) {
		return storedLabel == null ? null : CODES.get(storedLabel.trim());
	}

	/** Chinese name for a code; null for {@code unknown} and anything unrecognised. */
	public static String label(String code) {
		return code == null ? null : LABELS.get(code.trim().toLowerCase(Locale.ROOT));
	}

	public static String normalize(String code) {
		String value = code == null ? UNKNOWN : code.trim().toLowerCase(Locale.ROOT);
		return VALID.contains(value) ? value : null;
	}
}

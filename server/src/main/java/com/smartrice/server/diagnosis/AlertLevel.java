package com.smartrice.server.diagnosis;

import java.util.Locale;
import java.util.Set;

public enum AlertLevel {
	GREEN,
	YELLOW,
	RED;

	public String json() {
		return name().toLowerCase(Locale.ROOT);
	}

	public static AlertLevel parse(String value) {
		if (value == null || value.isBlank()) {
			return GREEN;
		}
		return switch (value.trim().toLowerCase(Locale.ROOT)) {
			case "red" -> RED;
			case "yellow" -> YELLOW;
			default -> GREEN;
		};
	}

	public static AlertLevel max(AlertLevel left, AlertLevel right) {
		AlertLevel a = left == null ? GREEN : left;
		AlertLevel b = right == null ? GREEN : right;
		return a.ordinal() >= b.ordinal() ? a : b;
	}

	private static final Set<String> LEAF_DISEASES = Set.of(
		"bacterial leaf blight",
		"brown spot",
		"tungro virus",
		"细菌性叶枯病",
		"褐斑病",
		"东格鲁病毒"
	);

	public static AlertLevel fromLeaf(String label, String labelZh, Boolean hasDamage) {
		return leafDisease(label, labelZh, hasDamage) ? RED : GREEN;
	}

	public static AlertLevel fromLeafHsi(String label, String labelZh, Boolean hasDamage) {
		return leafDisease(label, labelZh, hasDamage) ? YELLOW : GREEN;
	}

	private static boolean leafDisease(String label, String labelZh, Boolean hasDamage) {
		String combined = ((label == null ? "" : label) + " " + (labelZh == null ? "" : labelZh))
			.toLowerCase(Locale.ROOT);
		for (String disease : LEAF_DISEASES) {
			if (combined.contains(disease)) {
				return true;
			}
		}
		return Boolean.TRUE.equals(hasDamage);
	}

	public static AlertLevel fromPest(int count) {
		if (count >= 2) {
			return RED;
		}
		if (count == 1) {
			return YELLOW;
		}
		return GREEN;
	}
}

package com.smartrice.server.ai;

import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Validates report bodies without rewriting text, evidence, wire keys or units. */
public final class AiReportLanguage {
	private static final Pattern LATIN = Pattern.compile("[A-Za-z]");
	private static final Pattern ALLOWED_PLAN_NAMES = Pattern.compile("方案[AB]");
	private static final Pattern CHINESE = Pattern.compile("\\p{IsHan}");
	private static final Pattern MARKUP = Pattern.compile("[`*#_|]|~~|<!--|-->");
	private static final Pattern MARKDOWN_LINE = Pattern.compile(
		"(?m)^[\\t ]*(?:[-+•●▪‣◦][\\t ]+|[0-9]+[.)][\\t ]+|>[\\t ]*|[-=]{3,}[\\t ]*$)");
	private static final Pattern MARKDOWN_LINK = Pattern.compile("!?\\[[^\\]\\r\\n]*\\]\\s*(?:\\(|\\[)");
	private static final Pattern HTML_TAG = Pattern.compile("<\\s*/?\\s*[A-Za-z][^>]*>");
	private static final Pattern JSON_OBJECT = Pattern.compile("[\\{\\[]\\s*\"[^\"\\r\\n]*\"\\s*:");

	private AiReportLanguage() { }

	public static void validateReports(String weather, String soil, String risk, String summary) {
		requireChinesePlainText(weather);
		requireChinesePlainText(soil);
		requireChinesePlainText(risk);
		requireChineseSummary(summary);
	}

	static void requireChinesePlainText(String text) {
		if (text == null || text.isBlank() || !CHINESE.matcher(text).find()) throw invalid();
		// Replace the permitted names in one pass: repeated replacements could accidentally accept "方案AB".
		String withoutPlanLetters = ALLOWED_PLAN_NAMES.matcher(text).replaceAll("方案");
		if (LATIN.matcher(withoutPlanLetters).find() || MARKUP.matcher(text).find()
				|| MARKDOWN_LINE.matcher(text).find() || MARKDOWN_LINK.matcher(text).find()
				|| HTML_TAG.matcher(text).find() || JSON_OBJECT.matcher(text).find()) throw invalid();
	}

	static void requireChineseSummary(String text) {
		requireChinesePlainText(text);
		if (!text.contains("方案A：高成本高效率型") || !text.contains("方案B：低成本稳定型")) throw invalid();
	}

	private static ResponseStatusException invalid() {
		return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
			"报告格式不符合要求，请确认工作流已更新并重新生成");
	}
}

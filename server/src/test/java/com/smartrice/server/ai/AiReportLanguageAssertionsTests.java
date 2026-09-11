package com.smartrice.server.ai;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** Offline checks of the live acceptance assertions; never constructs a model or database client. */
class AiReportLanguageAssertionsTests {
	@Test void permitsNaturalChineseNumbersUnitsAndOnlyTheTwoPlanLetters() {
		String summary = "依据2025年旧版产量资料，当前空气温度为28摄氏度。\n\n"
			+ "方案A：高成本高效率型\n加强人工巡查并及时复测。\n\n"
			+ "方案B：低成本稳定型\n持续观察，按计划复测。";
		assertThatCode(() -> AiReportLanguage.requireChineseSummary(summary)).doesNotThrowAnyException();
		assertThatCode(() -> AiReportLanguage.requireChinesePlainText(
			"土壤酸碱度为6.2，电导率为1.14分西门子每米，氮浓度为118百万分之一。气温变化为-1.5摄氏度。"))
			.doesNotThrowAnyException();
		assertThatCode(() -> AiReportLanguage.requireChinesePlainText(
			"一、环境判断（依据2025年9月11日观测）\n\n【复测建议】\n2025-09-11的资料用于比较，请结合现场情况判断。"))
			.doesNotThrowAnyException();
	}

	@Test void rejectsEnglishUnitsFieldNamesAndLettersOutsideTheExactPlanExceptions() {
		for (String text : List.of("温度为28C。", "酸碱度pH为6.2。", "光照为100lux。", "浓度为118ppm。",
			"字段temperature_celsius。", "方案C：其他策略。", "方案AB：其他策略。", "英文建议OK。")) {
			assertThatThrownBy(() -> AiReportLanguage.requireChinesePlainText(text)).isInstanceOf(ResponseStatusException.class);
		}
	}

	@Test void rejectsObviousMarkdownHtmlTablesAndTechnicalObjects() {
		for (String text : List.of("# 环境分析\n建议复测。", "**重点**建议复测。", "建议`复测`。", "_重点_建议复测。",
			"- 建议复测。", "+ 建议复测。", "1. 建议复测。", "2) 建议复测。", "• 建议复测。", "> 建议复测。",
			"环境分析\n---\n建议复测。", "环境分析\n===\n建议复测。", "|指标|结果|\n|温度|偏高|",
			"[说明](/资料)", "![说明](/图片)", "<p>建议复测。</p>", "<!--说明-->建议复测。",
			"{\"环境分析\":\"建议复测\"}")) {
			assertThatThrownBy(() -> AiReportLanguage.requireChinesePlainText(text)).isInstanceOf(ResponseStatusException.class);
		}
	}

	@Test void rejectsBlankReportsAndMissingOrReversedPlanPositioning() {
		assertThatThrownBy(() -> AiReportLanguage.requireChinesePlainText(" ")).isInstanceOf(ResponseStatusException.class);
		assertThatThrownBy(() -> AiReportLanguage.requireChinesePlainText("2025 28")).isInstanceOf(ResponseStatusException.class);
		assertThatThrownBy(() -> AiReportLanguage.requireChineseSummary("方案A：高成本高效率型\n建议复测。"))
			.isInstanceOf(ResponseStatusException.class);
		assertThatThrownBy(() -> AiReportLanguage.requireChineseSummary("方案A：低成本稳定型\n方案B：高成本高效率型"))
			.isInstanceOf(ResponseStatusException.class);
	}
}

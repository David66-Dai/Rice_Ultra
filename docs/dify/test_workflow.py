import json
import unittest

from validate_workflow import (
    DIRECTORY, NODE_IDS, SUMMARY_HEADINGS, ValidationError, export_prompts, read_dsl,
    validate_dsl, validate_input, validate_outputs, validate_report_style,
)


class WorkflowDeliveryTests(unittest.TestCase):
    def setUp(self):
        self.dsl = read_dsl(DIRECTORY / "rice_environment_analysis.yml")
        self.payload = json.loads((DIRECTORY / "environment_input.example.json").read_text(encoding="utf-8"))

    def test_delivery_and_complete_calendar_sample_pass(self):
        maximum = validate_dsl(self.dsl)
        self.assertGreaterEqual(maximum, 65536)
        validate_input(self.payload, maximum)

    def test_missing_optional_legacy_is_valid(self):
        del self.payload["legacyContext"]
        validate_input(self.payload)

    def test_short_input_limit_and_wrong_output_fail(self):
        self.dsl["workflow"]["graph"]["nodes"][0]["data"]["variables"][0]["max_length"] = 1024
        with self.assertRaises(ValidationError):
            validate_dsl(self.dsl)
        self.dsl = read_dsl(DIRECTORY / "rice_environment_analysis.yml")
        self.dsl["workflow"]["graph"]["nodes"][-1]["data"]["outputs"][0]["value_selector"] = [NODE_IDS[0], "environment_json"]
        with self.assertRaises(ValidationError):
            validate_dsl(self.dsl)

    def test_unavailable_prompt_reference_and_external_node_fail(self):
        self.dsl["workflow"]["graph"]["nodes"][1]["data"]["prompt_template"][1]["text"] += "{{#missing.text#}}"
        with self.assertRaises(ValidationError):
            validate_dsl(self.dsl)
        self.dsl = read_dsl(DIRECTORY / "rice_environment_analysis.yml")
        self.dsl["workflow"]["graph"]["nodes"][1]["data"]["type"] = "http-request"
        with self.assertRaises(ValidationError):
            validate_dsl(self.dsl)

    def test_missing_values_cannot_be_zero_or_have_fabricated_change(self):
        self.payload["daily"][1]["values"]["humidity_percent"] = 0
        with self.assertRaises(ValidationError):
            validate_input(self.payload)
        self.payload["daily"][1]["values"]["humidity_percent"] = None
        humidity = next(item for item in self.payload["metrics"] if item["field"] == "humidity_percent")
        humidity["change"] = -80
        with self.assertRaises(ValidationError):
            validate_input(self.payload)

    def test_raw_row_count_is_not_effective_day_count(self):
        self.payload["metrics"][0]["count"] = self.payload["rawRowCount"]
        with self.assertRaises(ValidationError):
            validate_input(self.payload)

    def test_units_and_calendar_cannot_be_silently_changed(self):
        self.payload["metrics"][0]["unit"] = "kLx"
        with self.assertRaises(ValidationError):
            validate_input(self.payload)
        self.payload["metrics"][0]["unit"] = "lux"
        self.payload["daily"].pop(1)
        with self.assertRaises(ValidationError):
            validate_input(self.payload)

    def test_legacy_same_day_and_prior_year_contract(self):
        legacy = self.payload["legacyContext"]
        legacy["disease"].update(available=True, referenceDate="2026-09-09", matchType="exact",
                                 values=[{"field": "fixture_only", "label": "测试字段", "unit": "", "value": "仅用于契约测试"}])
        legacy["yield"].update(available=True, referenceYear=2025, matchType="latest_prior", baselineKgPerMu=500)
        validate_input(self.payload)
        legacy["disease"]["referenceDate"] = "2026-09-08"
        legacy["disease"]["matchType"] = "latest_prior"
        validate_input(self.payload)

    def test_legacy_cannot_use_future_dates_or_future_years(self):
        legacy = self.payload["legacyContext"]
        legacy["yield"].update(available=True, referenceYear=2027, matchType="exact_year", baselineKgPerMu=500)
        with self.assertRaises(ValidationError):
            validate_input(self.payload)
        legacy["yield"].update(available=False, referenceYear=None, matchType="missing", baselineKgPerMu=None)
        legacy["disease"].update(available=True, referenceDate="2026-09-10", matchType="exact", values=[])
        with self.assertRaises(ValidationError):
            validate_input(self.payload)

    def test_missing_legacy_does_not_accept_fabricated_yield(self):
        self.payload["legacyContext"]["yield"]["baselineKgPerMu"] = 500
        with self.assertRaises(ValidationError):
            validate_input(self.payload)

    def test_prompt_export_preserves_node_ids_and_exact_prompt_text(self):
        exported = export_prompts(self.dsl)["nodes"]
        self.assertEqual([node["id"] for node in exported], NODE_IDS[1:5])
        original = self.dsl["workflow"]["graph"]["nodes"][1]["data"]["prompt_template"]
        self.assertEqual(exported[0]["system"], original[0]["text"])
        self.assertEqual(exported[0]["user"], original[1]["text"])
        self.assertEqual(set(exported[0]), {"id", "system", "user"})

    def test_prompt_style_and_two_plan_requirements_are_mandatory(self):
        system = self.dsl["workflow"]["graph"]["nodes"][1]["data"]["prompt_template"][0]
        system["text"] = system["text"].replace("不得出现拉丁字母", "输出语言随意")
        with self.assertRaises(ValidationError):
            validate_dsl(self.dsl)
        self.dsl = read_dsl(DIRECTORY / "rice_environment_analysis.yml")
        for prompt in self.dsl["workflow"]["graph"]["nodes"][4]["data"]["prompt_template"]:
            prompt["text"] = prompt["text"].replace(SUMMARY_HEADINGS[2], "只有一个方案")
        with self.assertRaises(ValidationError):
            validate_dsl(self.dsl)


def prose(length=200):
    # Synthetic text checks format only; it is not a generated or factual farm report.
    sentence = "结合现有监测情况安排巡田，先确认重点区域的变化，再根据现场情况逐步调整管理。"
    return (sentence * 20)[:length]


def summary_fixture():
    sections = [
        prose(110),
        "组织专业人员联合排查与集中检测，投入相对较高，确认偏干后分区补水。" + prose(95),
        "使用现有人手和现有工具，从重点区域逐步推进，额外投入较低，确认堵塞后疏通沟渠。" + prose(95),
        prose(110),
    ]
    return "\n\n".join(heading + "\n\n" + section for heading, section in zip(SUMMARY_HEADINGS, sections))


class ReportStyleTests(unittest.TestCase):
    def test_plain_chinese_outputs_and_only_two_fixed_latin_labels_pass(self):
        short = "一号监测站在2026年9月11日的空气温度为28摄氏度、湿度为80%。" + prose(110)
        validate_report_style(short)
        validate_report_style(summary_fixture(), summary=True)
        validate_outputs({"weather_analysis": short, "soil_analysis": prose(220),
                          "risk_analysis": prose(220), "summary": summary_fixture()})

    def test_latin_units_station_codes_and_technical_terms_fail(self):
        for token in ("S01", "Hive", "Dify", "JSON", "SQL", "pH", "ppm", "m/s", "℃", "数据库", "字段名", "Ｈｉｖｅ"):
            with self.subTest(token=token), self.assertRaises(ValidationError):
                validate_report_style(prose(220) + token)

    def test_markdown_tables_objects_and_html_fail(self):
        for marker in ("# 标题\n", "**重点**", "`内容`", "- 项目\n", "1. 项目\n", "|项目|值|", "{结果}", "<段落>内容</段落>"):
            with self.subTest(marker=marker), self.assertRaises(ValidationError):
                validate_report_style(marker + prose(220))

    def test_missing_repeated_reordered_or_identical_plans_fail(self):
        valid = summary_fixture()
        invalid = [
            valid.replace(SUMMARY_HEADINGS[2], "稳步计划"),
            valid + "再次采用其他方案。",
            valid.replace(SUMMARY_HEADINGS[0], "其他判断"),
            valid.replace("使用现有人手和现有工具", "采用新购大型仪器"),
        ]
        for text in invalid:
            with self.assertRaises(ValidationError):
                validate_report_style(text, summary=True)

    def test_report_lengths_are_bounded(self):
        for text in ("建议巡田。", prose(70), prose(500)):
            with self.assertRaises(ValidationError):
                validate_report_style(text)
        with self.assertRaises(ValidationError):
            validate_report_style(summary_fixture() + prose(400), summary=True)


if __name__ == "__main__":
    unittest.main()

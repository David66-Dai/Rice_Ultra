"""Offline checks for this Dify delivery. No service calls or private config reads."""
from __future__ import annotations

import argparse
from datetime import date, timedelta
import json
import math
from pathlib import Path
import re
import sys
import unicodedata

import yaml

DIRECTORY = Path(__file__).resolve().parent
FIELDS = {
    "light_lux": "lux", "temperature_celsius": "°C", "humidity_percent": "%",
    "wind_speed_m_s": "m/s", "soil_temperature_celsius": "°C", "soil_moisture_percent": "%",
    "ph": "pH", "electrical_conductivity_ds_m": "dS/m", "nitrogen_concentration_ppm": "ppm",
    "phosphorus_concentration_ppm": "ppm", "potassium_concentration_ppm": "ppm",
}
NODE_IDS = [str(1910000000001 + index) for index in range(6)]
OUTPUTS = dict(zip(("weather_analysis", "soil_analysis", "risk_analysis", "summary"), NODE_IDS[1:5]))
SUMMARY_HEADINGS = ("整体判断", "方案A：高成本高效率型", "方案B：低成本稳定型", "选择建议")


class ValidationError(ValueError):
    pass


def require(condition: bool, message: str):
    if not condition:
        raise ValidationError(message)


class UniqueLoader(yaml.SafeLoader):
    def construct_mapping(self, node, deep=False):
        self.flatten_mapping(node)
        result = {}
        for key_node, value_node in node.value:
            key = self.construct_object(key_node, deep=deep)
            require(isinstance(key, str), "YAML keys must be strings")
            require(key not in result, "YAML contains duplicate keys")
            result[key] = self.construct_object(value_node, deep=deep)
        return result


def read_dsl(path: Path):
    try:
        return yaml.load(path.read_text(encoding="utf-8-sig"), Loader=UniqueLoader)
    except (OSError, UnicodeError, yaml.YAMLError):
        raise ValidationError("Cannot parse DSL YAML; values omitted") from None


def validate_dsl(dsl: dict) -> int:
    require(isinstance(dsl, dict) and dsl.get("kind") == "app", "DSL must be an app mapping")
    require(isinstance(dsl.get("version"), str), "DSL version must be a string")
    require(dsl.get("app", {}).get("mode") == "workflow", "App must use workflow mode")
    require(dsl.get("dependencies") == [], "Delivery must not pin unverified plugin packages")
    workflow = dsl["workflow"]
    require(workflow.get("environment_variables") == [], "Delivery must contain no environment credentials")
    require(workflow.get("conversation_variables") == [], "Delivery must be stateless")
    graph = workflow["graph"]
    nodes = {node["id"]: node for node in graph["nodes"]}
    require(len(nodes) == len(graph["nodes"]) == 6 and set(nodes) == set(NODE_IDS), "Expected six unique delivery nodes")
    types = [nodes[node_id]["data"]["type"] for node_id in NODE_IDS]
    require(types == ["start", "llm", "llm", "llm", "llm", "end"], "Only Start, four LLMs and End are allowed")
    edges = graph["edges"]
    require(len(edges) == 5, "Expected five graph edges")
    require({(edge["source"], edge["target"]) for edge in edges} == set(zip(NODE_IDS, NODE_IDS[1:])), "Graph must be a connected acyclic sequence")
    for edge in edges:
        require(edge.get("sourceHandle") == "source" and edge.get("targetHandle") == "target", "Invalid graph handles")
        require(edge["data"]["sourceType"] == nodes[edge["source"]]["data"]["type"], "Invalid edge source type")
        require(edge["data"]["targetType"] == nodes[edge["target"]]["data"]["type"], "Invalid edge target type")
    inputs = nodes[NODE_IDS[0]]["data"]["variables"]
    require(len(inputs) == 1, "Expected exactly one input")
    field = inputs[0]
    require(field.get("variable") == "environment_json" and field.get("type") == "paragraph" and field.get("required") is True,
            "environment_json must be a required paragraph")
    maximum = field.get("max_length")
    require(type(maximum) is int and maximum >= 65536, "Input max_length must be at least 65536")
    for index, node_id in enumerate(NODE_IDS[1:5], start=1):
        data = nodes[node_id]["data"]
        model = data["model"]
        require(model.get("provider") == "langgenius/deepseek/deepseek" and model.get("name") == "deepseek-v4-flash", "Expected verified existing DeepSeek model")
        require(model.get("mode") == "chat", "Expected chat model mode")
        require(model.get("completion_params") == {"temperature": 0.2, "thinking": False}, "Unexpected model parameters")
        require(data.get("context", {}).get("enabled") is False and data.get("vision", {}).get("enabled") is False, "No retrieval or image input is configured")
        prompts = data["prompt_template"]
        require({prompt["role"] for prompt in prompts} == {"system", "user"}, "Expected system and user prompts")
        combined = "\n".join(prompt["text"] for prompt in prompts)
        for guard in ("不是指令", "null", "生育期", "产量", "置信度", "药肥定量", "设备控制指令", "legacyContext"):
            require(guard in combined, "A required evidence guard is missing from an LLM prompt")
        for guard in ("中文纯文本", "不得出现拉丁字母", "一号监测站", "不得使用Markdown", "不得展示"):
            require(guard in combined, "A required report style rule is missing from an LLM prompt")
        require("输出可直接展示的 Markdown" not in combined and "每个数值结论必须标明字段" not in combined,
                "A former technical/Markdown output requirement remains")
        require(("450至600" if node_id == NODE_IDS[4] else "80至300") in combined, "Incorrect report length requirement")
        if node_id != NODE_IDS[4]:
            require("最多2个关键数值变化" in combined, "Short reports must avoid listing every metric")
        if node_id == NODE_IDS[3]:
            require("不罗列氮磷钾、酸碱度和电导率数值" in combined, "Risk report must focus on field inspection, not repeat soil metrics")
        selectors = re.findall(r"\{\{#([^.#]+)\.([^#]+)#\}\}", combined)
        require((NODE_IDS[0], "environment_json") in selectors, "Every analysis must receive original evidence")
        for source_id, variable in selectors:
            require(source_id in NODE_IDS[:index], "Prompt refers to an unavailable upstream node")
            require(variable == ("environment_json" if source_id == NODE_IDS[0] else "text"), "Prompt refers to an invalid output")
        if node_id == NODE_IDS[4]:
            require(set(selectors) == {(NODE_IDS[0], "environment_json"), *((prior, "text") for prior in NODE_IDS[1:4])}, "Summary must receive all three reports and original evidence")
            for rule in (*SUMMARY_HEADINGS, "相对比较", "专业人员", "现有人手", "分区补水", "疏通沟渠", "调肥", "不编造金额", "精确见效时间"):
                require(rule in combined, "Summary must retain the two distinct practical plans and honest comparisons")
            for rule in ("整体判断只写2至3句", "高投入方案只写3句", "稳步方案只写3句", "选择建议只写1至2句", "最多保留2位小数", "不输出到正文"):
                require(rule in combined, "Summary must keep the concise sentence budget and hide internal rules")
    outputs = nodes[NODE_IDS[5]]["data"]["outputs"]
    require(len(outputs) == 4 and {item["variable"] for item in outputs} == set(OUTPUTS), "Expected exactly four output names")
    for output in outputs:
        require(output["value_selector"] == [OUTPUTS[output["variable"]], "text"], "Output must select the corresponding LLM string")
    return maximum


def export_prompts(dsl):
    """Return a secret-free payload for copying prompts into the existing app UI."""
    nodes = {node["id"]: node for node in dsl["workflow"]["graph"]["nodes"]}
    result = []
    for node_id in NODE_IDS[1:5]:
        by_role = {prompt["role"]: prompt["text"] for prompt in nodes[node_id]["data"]["prompt_template"]}
        result.append({"id": node_id, "system": by_role["system"], "user": by_role["user"]})
    return {"nodes": result}


def validate_report_style(text, summary=False):
    """Check visible formatting, not agronomic correctness or factual grounding."""
    require(isinstance(text, str) and bool(text.strip()), "Report text must not be empty")
    visible = text.strip()
    require(not any(symbol in visible for symbol in ("#", "*", "`", "|", "{", "}", "[", "]", "<", ">", "℃")),
            "Report must be plain paragraphs without markup, tables, objects or abbreviated temperature units")
    require(re.search(r"(?m)^\s*(?:[-+•●▪]\s*|\d+[.)、]\s*)", visible) is None,
            "Report must not use list markers or numbered lists")
    require(not any(term in visible for term in ("字段名", "表名", "数据库", "接口", "技术校验")),
            "Technical implementation details must not appear in report prose")
    if summary:
        require(visible.count("方案A") >= 1 and visible.count("方案B") >= 1, "Both fixed plan labels are required")
        paragraphs = [part.strip() for part in visible.splitlines() if part.strip()]
        indices = []
        for heading in SUMMARY_HEADINGS:
            require(paragraphs.count(heading) == 1, "Summary requires the four exact standalone headings")
            indices.append(paragraphs.index(heading))
        require(indices == sorted(indices) and indices[0] == 0, "Summary headings must appear in the required order")
        boundaries = [*indices[1:], len(paragraphs)]
        sections = []
        for first, last in zip(indices, boundaries):
            require(last > first + 1, "Each summary section must contain prose")
            sections.append("".join(paragraphs[first + 1:last]))
        require(sections[1] != sections[2], "The two plan descriptions must differ")
        require(any(word in sections[1] for word in ("专业", "集中检测", "联合排查")), "High-input plan needs professional or coordinated action")
        require(any(word in sections[2] for word in ("现有人手", "现有人工", "现有工具", "已有工具")), "Steady plan should use existing resources")
        require(any(word in sections[2] for word in ("逐步", "分阶段", "稳步")), "Steady plan needs phased action")
        visible = visible.replace("方案A", "方案").replace("方案B", "方案")
    normalized = unicodedata.normalize("NFKC", visible)
    require(not any("LATIN" in unicodedata.name(character, "") for character in normalized),
            "Only the two fixed summary plan labels may contain Latin letters")
    count = len(re.findall(r"[\u3400-\u9fff]", visible))
    minimum, maximum = (250, 650) if summary else (80, 300)
    require(minimum <= count <= maximum, "Report length is outside the requested Chinese-character range")


def validate_outputs(outputs):
    require(isinstance(outputs, dict) and set(outputs) == set(OUTPUTS), "Expected the four workflow output strings")
    for name, text in outputs.items():
        validate_report_style(text, summary=name == "summary")


def finite(value):
    return type(value) in (int, float) and math.isfinite(value)


def parse_date(value):
    require(isinstance(value, str) and re.fullmatch(r"\d{4}-\d{2}-\d{2}", value) is not None, "Date must be YYYY-MM-DD")
    try:
        return date.fromisoformat(value)
    except ValueError:
        raise ValidationError("Invalid calendar date") from None


def strings(value, field):
    require(isinstance(value, list) and all(isinstance(item, str) for item in value), field + " must be a string array")


def validate_legacy(legacy, station_id, end):
    require(isinstance(legacy, dict) and legacy.get("source") == "hive_legacy", "Invalid legacy source")
    require(legacy.get("stationId") == station_id, "Legacy station must match requested station")
    strings(legacy.get("limitations"), "legacyContext.limitations")
    disease = legacy["disease"]
    crop_yield = legacy["yield"]
    for item in (disease, crop_yield):
        require(type(item.get("available")) is bool and isinstance(item.get("sourceTable"), str) and bool(item["sourceTable"]), "Invalid legacy availability/sourceTable")
    require(disease.get("matchType") in ("exact", "latest_prior", "missing"), "Invalid disease matchType")
    require(isinstance(disease.get("values"), list), "Legacy disease values must be an array")
    if disease["available"]:
        reference = parse_date(disease.get("referenceDate"))
        require(reference <= end, "Future disease records must not be used")
        require(disease["matchType"] == ("exact" if reference == end else "latest_prior"), "Disease date and matchType disagree")
        require(bool(disease["values"]), "Available disease record must contain values")
        for value in disease["values"]:
            require(isinstance(value, dict) and all(isinstance(value.get(key), str) for key in ("field", "label", "unit", "value")), "Legacy disease values must contain labeled strings")
    else:
        require(disease["matchType"] == "missing" and disease.get("referenceDate") is None and not disease["values"], "Missing disease record must not carry fabricated values")
    require(isinstance(crop_yield.get("season"), str), "Yield season must be a string")
    if crop_yield["available"]:
        year = crop_yield.get("referenceYear")
        require(type(year) is int and 1 <= year <= end.year, "Future or invalid yield year")
        require(crop_yield.get("matchType") == ("exact_year" if year == end.year else "latest_prior"), "Yield year and matchType disagree")
        require(finite(crop_yield.get("baselineKgPerMu")) and crop_yield["baselineKgPerMu"] >= 0, "Yield baseline must be a nonnegative number")
    else:
        require(crop_yield.get("matchType") == "missing" and crop_yield.get("referenceYear") is None and crop_yield.get("baselineKgPerMu") is None, "Missing yield baseline must remain null")


def validate_input(payload: dict, maximum=131072):
    require(isinstance(payload, dict), "Input must be a JSON object before serialization")
    require(len(json.dumps(payload, ensure_ascii=False, allow_nan=False)) <= maximum, "Serialized input exceeds max_length")
    station_id = payload.get("stationId")
    require(isinstance(station_id, str) and re.fullmatch(r"S(?:0[1-9]|10)", station_id) is not None, "Invalid stationId")
    require(payload.get("hiveStation") == "point_" + str(int(station_id[1:])), "Hive station mapping mismatch")
    start, end = parse_date(payload["startDate"]), parse_date(payload["endDate"])
    require(start <= end, "Window starts after it ends")
    window = (end - start).days + 1
    require(type(payload.get("windowDays")) is int and payload["windowDays"] == window, "windowDays mismatch")
    calendar = [(start + timedelta(days=offset)).isoformat() for offset in range(window)]
    missing = payload["missingDates"]
    require(isinstance(missing, list) and len(set(missing)) == len(missing) and set(missing) <= set(calendar), "Invalid missingDates")
    require(type(payload.get("observedDays")) is int and payload["observedDays"] == window - len(missing), "observedDays mismatch")
    require(type(payload.get("rawRowCount")) is int and payload["rawRowCount"] >= payload["observedDays"], "rawRowCount cannot be below observedDays")
    daily = payload["daily"]
    require(isinstance(daily, list) and [row["date"] for row in daily] == calendar, "daily must contain the complete ordered calendar")
    for row in daily:
        require(isinstance(row.get("values"), dict) and set(row["values"]) == set(FIELDS), "daily must retain all 11 original fields")
        require(all(value is None or finite(value) for value in row["values"].values()), "daily contains a non-finite or nonnumeric observation")
        if row["date"] in missing:
            require(all(value is None for value in row["values"].values()), "Missing days must not contain synthesized values")
    metrics = payload["metrics"]
    require(isinstance(metrics, list) and len(metrics) == len(FIELDS) and {item["field"] for item in metrics} == set(FIELDS), "Expected each of the 11 metrics exactly once")
    for metric in metrics:
        field = metric["field"]
        require(metric.get("unit") == FIELDS[field] and isinstance(metric.get("label"), str), "Metric label/unit mismatch")
        values = [row["values"][field] for row in daily if row["values"][field] is not None]
        require(type(metric.get("count")) is int and metric["count"] == len(values), "Metric count is valid days, not raw rows")
        require(type(metric.get("missingCount")) is int and metric["missingCount"] == window - len(values), "Metric missingCount mismatch")
        first, last = daily[0]["values"][field], daily[-1]["values"][field]
        expected = {
            "mean": sum(values) / len(values) if values else None,
            "min": min(values) if values else None, "max": max(values) if values else None,
            "first": first, "last": last, "change": last - first if first is not None and last is not None else None,
        }
        for key, value in expected.items():
            actual = metric.get(key)
            require(actual is None if value is None else finite(actual) and math.isclose(actual, value, rel_tol=0, abs_tol=0.000001), "Metric aggregate or window endpoint mismatch")
    require(isinstance(payload.get("growthStage"), str) and bool(payload["growthStage"]), "growthStage must be declared or unknown")
    strings(payload.get("limitations"), "limitations")
    if payload.get("legacyContext") is not None:
        validate_legacy(payload["legacyContext"], station_id, end)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dsl", type=Path, default=DIRECTORY / "rice_environment_analysis.yml")
    parser.add_argument("--input", type=Path, default=DIRECTORY / "environment_input.example.json")
    parser.add_argument("--export-prompts", action="store_true", help="Print only JSON containing each LLM node id/system/user")
    parser.add_argument("--outputs", type=Path, help="Optionally check a JSON object containing the four actual output strings")
    args = parser.parse_args()
    try:
        dsl = read_dsl(args.dsl)
        maximum = validate_dsl(dsl)
        if args.export_prompts:
            # ASCII JSON avoids console-encoding corruption; JSON decoding restores exact Chinese text.
            print(json.dumps(export_prompts(dsl)))
            return 0
        payload = json.loads(args.input.read_text(encoding="utf-8-sig"))
        validate_input(payload, maximum)
        if args.outputs:
            validate_outputs(json.loads(args.outputs.read_text(encoding="utf-8-sig")))
    except (ValidationError, KeyError, TypeError, ValueError, OSError):
        print("Offline validation failed; check DSL and input contract. Source values omitted.", file=sys.stderr)
        return 1
    print("PASS: 6 nodes, 5 edges, one paragraph input, 4 string outputs, evidence/legacy sample contract.")
    print("Offline only: no Hive, Dify, model or device connection was made.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

from __future__ import annotations

import json
import re
from datetime import date as date_type
from datetime import datetime, timedelta
from typing import Any
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo

import httpx
from astrbot.api import AstrBotConfig, logger
from astrbot.api.event import AstrMessageEvent, filter
from astrbot.api.star import Context, Star


STATION_PATTERN = re.compile(r"^S(?:0[1-9]|10)$")


class AgriQueryPlugin(Star):
    """通过 Rice Ultra Java 后端只读查询病虫害数据与平台告警。"""

    def __init__(self, context: Context, config: AstrBotConfig):
        super().__init__(context)
        self.config = config

    def _settings(self):
        base = str(self.config.get("api_base", "")).strip().rstrip("/")
        token = str(self.config.get("api_token", ""))
        station = self._station(self.config.get("default_station", "S01"))
        parsed = urlsplit(base)
        valid = (
            parsed.scheme in {"http", "https"}
            and parsed.hostname
            and not parsed.username
            and not parsed.password
            and not parsed.query
            and not parsed.fragment
            and parsed.path in {"", "/"}
            and (parsed.scheme == "https" or parsed.hostname in {"127.0.0.1", "localhost", "::1"})
            and len(token) >= 32
        )
        if not valid:
            raise ValueError("请配置有效的 Java API 地址和至少 32 位的 AstrBot 独立集成令牌")
        return base, token, station

    @staticmethod
    def _identity(event: AstrMessageEvent) -> dict[str, str]:
        umo = str(getattr(event, "unified_msg_origin", "") or "")
        sender = str(event.get_sender_id() or "")
        if not umo or not sender:
            raise ValueError("当前消息缺少 UMO 或发送者 ID，不能查询农业数据")
        return {"umo": umo, "senderId": sender}

    @staticmethod
    def _station(value: Any, allow_all: bool = False) -> str:
        station = str(value or "").strip().upper()
        if station.startswith("ST-00") and len(station) == 6 and station[-1] in "123456789":
            station = "S0" + station[-1]
        elif station == "ST-010":
            station = "S10"
        if allow_all and station in {"ALL", "全部", "所有"}:
            return "ALL"
        if not STATION_PATTERN.fullmatch(station):
            raise ValueError("站点编号必须为 S01-S10（兼容旧写法 ST-001-ST-010）")
        return station

    @staticmethod
    def _date(value: Any, field: str) -> date_type:
        normalized = str(value or "").strip().lower()
        today = datetime.now(ZoneInfo("Asia/Shanghai")).date()
        relative = {"today": today, "今天": today, "yesterday": today - timedelta(days=1), "昨天": today - timedelta(days=1)}
        if normalized in relative:
            return relative[normalized]
        try:
            return datetime.strptime(normalized, "%Y-%m-%d").date()
        except ValueError as exc:
            raise ValueError(f"{field} 必须是 YYYY-MM-DD、今天或昨天") from exc

    async def _post(self, event: AstrMessageEvent, path: str, body: dict[str, Any]):
        base, token, _ = self._settings()
        payload = {"identity": self._identity(event), **body}
        try:
            async with httpx.AsyncClient(
                base_url=base,
                headers={"X-AstrBot-Token": token},
                timeout=15,
                follow_redirects=False,
                trust_env=False,
            ) as client:
                response = await client.post(path, json=payload)
            data = response.json()
            if not isinstance(data, dict) or len(response.content) > 512_000:
                raise ValueError("invalid response")
            if response.status_code != 200:
                message = data.get("message") if isinstance(data.get("message"), str) else "Java API 拒绝了查询"
                raise ValueError(message[:500])
            return data
        except httpx.HTTPError as exc:
            raise ValueError("Java 农业查询 API 当前不可用") from exc

    @staticmethod
    def _dump(data: Any) -> str:
        return json.dumps(data, ensure_ascii=False, default=str)

    async def _data(self, event, station, start, end):
        return await self._post(event, "/api/astrbot/agriculture/data", {
            "stationId": station, "startDate": start.isoformat(), "endDate": end.isoformat(),
        })

    async def _alerts(self, event, station, start, end):
        return await self._post(event, "/api/astrbot/agriculture/alerts", {
            "stationId": station, "startDate": start.isoformat(), "endDate": end.isoformat(),
        })

    @filter.llm_tool(name="query_station_monitoring")
    async def query_station_monitoring(self, event: AstrMessageEvent, point: str, date: str):
        """查询一个站点某日的病虫害汇总。point 使用 S01-S10，也兼容 ST-001-ST-010。"""
        try:
            day = self._date(date, "date")
            return self._dump(await self._data(event, self._station(point), day, day))
        except Exception as exc:
            logger.warning("农业单日查询失败: %s", exc)
            return f"农业数据查询失败：{exc}"

    @filter.llm_tool(name="query_monitoring_history")
    async def query_monitoring_history(self, event: AstrMessageEvent, point: str, start_date: str, end_date: str):
        """查询一个站点的病虫害历史；日期使用 YYYY-MM-DD、今天或昨天。"""
        try:
            start, end = self._date(start_date, "start_date"), self._date(end_date, "end_date")
            return self._dump(await self._data(event, self._station(point), start, end))
        except Exception as exc:
            logger.warning("农业历史查询失败: %s", exc)
            return f"农业数据查询失败：{exc}"

    @filter.llm_tool(name="query_all_monitoring_by_date")
    async def query_all_monitoring_by_date(self, event: AstrMessageEvent, date: str):
        """查询某日全部 S01-S10 站点的病虫害汇总。"""
        try:
            day = self._date(date, "date")
            return self._dump(await self._data(event, "ALL", day, day))
        except Exception as exc:
            logger.warning("全部站点农业查询失败: %s", exc)
            return f"农业数据查询失败：{exc}"

    @filter.llm_tool(name="query_red_alerts")
    async def query_red_alerts(self, event: AstrMessageEvent, point: str, start_date: str, end_date: str):
        """查询站点或 ALL 在日期范围内的黄色、红色病虫害告警。"""
        try:
            start, end = self._date(start_date, "start_date"), self._date(end_date, "end_date")
            data = await self._alerts(event, self._station(point, allow_all=True), start, end)
            data["alerts"] = [item for item in data.get("alerts", []) if item.get("alertLevel") == "red"]
            data["count"] = len(data["alerts"])
            return self._dump(data)
        except Exception as exc:
            logger.warning("红色告警查询失败: %s", exc)
            return f"告警查询失败：{exc}"

    @filter.llm_tool(name="query_alert_status")
    async def query_alert_status(self, event: AstrMessageEvent, point: str, date: str):
        """查询站点某日已发布到 Rice Ultra 平台的黄色和红色识别告警。"""
        try:
            day = self._date(date, "date")
            return self._dump(await self._alerts(event, self._station(point), day, day))
        except Exception as exc:
            logger.warning("告警状态查询失败: %s", exc)
            return f"告警查询失败：{exc}"

    @filter.command("agri_query_status")
    async def agri_query_status(self, event: AstrMessageEvent):
        """检查 AstrBot 身份映射及 Java 农业查询接口。"""
        try:
            data = await self._post(event, "/api/astrbot/agriculture/status", {})
            yield event.plain_result("农业查询插件运行正常。\n" + self._dump(data))
        except Exception as exc:
            yield event.plain_result(f"农业查询插件不可用：{exc}\n当前 UMO：{getattr(event, 'unified_msg_origin', '')}")

    @filter.command("agri_query_all")
    async def agri_query_all(self, event: AstrMessageEvent, date: str):
        """不经过 AI，直接查询某一天全部站点的病虫害汇总。"""
        result = await self.query_all_monitoring_by_date(event, date)
        yield event.plain_result(result)

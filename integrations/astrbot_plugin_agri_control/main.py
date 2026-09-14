from __future__ import annotations

import json
from typing import Any

from astrbot.api import AstrBotConfig
from astrbot.api.event import AstrMessageEvent, filter
from astrbot.api.star import Context, Star

from .core import ControlClient, Identity


def _text(value: Any) -> str:
    return "" if value is None else str(value)


def _identity(event: AstrMessageEvent) -> Identity:
    return Identity(
        umo=_text(getattr(event, "unified_msg_origin", "")),
        sender_id=_text(event.get_sender_id()),
        message_id=_text(getattr(getattr(event, "message_obj", None), "message_id", "")),
        text=_text(event.get_message_str()),
    )


def _result(value: dict[str, Any]) -> str:
    return json.dumps(value, ensure_ascii=False, default=str)


def _device_name(value: Any) -> str:
    return {"pump": "智能喷药", "lamp": "智能驱虫灯"}.get(str(value), "设备")


def _identity_text(value: dict[str, Any]) -> str:
    if "umo" not in value and "sender_id" not in value:
        return ""
    umo = _text(value.get("umo")) or "（缺失）"
    sender_id = _text(value.get("sender_id")) or "（缺失）"
    return f"当前 UMO：{umo}\n当前 sender ID：{sender_id}"


def _command_text(value: dict[str, Any]) -> str:
    """Render direct slash-command results for people; LLM tools retain JSON above."""
    if not isinstance(value, dict):
        return "操作结果异常，请查看网页共享状态。"
    message = str(value.get("message") or "")
    identity = _identity_text(value)
    if value.get("ok") is not True:
        result = "操作未执行：" + (message or "后端未返回可用原因。")
        return result + ("\n" + identity if identity else "")

    status = value.get("status")
    state = value.get("state") if isinstance(value.get("state"), dict) else {}
    device = _device_name(value.get("device") or state.get("device"))
    if status == "pending_confirmation":
        confirmation_id = value.get("confirmation_id") or value.get("confirmationId")
        suffix = f"确认编号：{confirmation_id}。" if confirmation_id else ""
        return f"{device}尚未开启，已生成待确认请求。{suffix}请由同一用户发送 /agri_confirm 确认。"
    if status == "confirmed":
        auto_off = value.get("auto_off_at")
        timer = "喷药将按后端设定时长自动停止。" if device == "智能喷药" and auto_off else ""
        return f"AstrBot 消息告警已确认，已提交开启{device}指令。{timer}实际设备状态仍需现场确认。"
    if status == "command_sent":
        enabled = state.get("enabled")
        action = "开启" if enabled is True else "关闭" if enabled is False else "处理"
        timer = "系统将按设定时长自动停止。" if value.get("auto_off_at") else ""
        return f"已提交{action}{device}指令。{timer}实际设备状态仍需现场确认。"
    if status == "multiple":
        return message or "已分别提交两台设备的停止指令，实际状态仍需现场确认。"
    if value.get("mapped_username"):
        permission = "具备控制权限" if value.get("can_control") else "仅可查看"
        availability = "设备链路可用" if value.get("device_available") else "设备链路不可用"
        status_text = f"当前映射平台账号：{value['mapped_username']}；{permission}；{availability}。"
        return (identity + "\n" if identity else "") + status_text
    if status == "completed":
        return "已查询到最近一次操作结果。" + (message if message else "")
    return message or "操作已处理，请以网页共享状态和现场反馈为准。"


class AgriControlPlugin(Star):
    """通过 Rice Ultra Java 后端安全控制喷药和驱虫灯。"""

    def __init__(self, context: Context, config: AstrBotConfig):
        super().__init__(context)
        self.client = ControlClient(config)

    @filter.llm_tool(name="start_agri_device")
    async def start_agri_device(
        self, event: AstrMessageEvent, device: str, seconds: float = 0, station_code: str = ""
    ) -> str:
        """准备开启农业设备。此工具不会立即开启，而会返回二次确认编号。

        Args:
            device(string): pump/喷药，或 lamp/驱虫灯/杀虫灯。
            seconds(number): 可选正整数秒；未指定时喷药默认 60 秒，驱虫灯持续开启直至关闭。
            station_code(string): 站点编号；未指定时留空使用插件配置。
        """
        return _result(await self.client.start(_identity(event), device, seconds, station_code))

    @filter.llm_tool(name="confirm_agri_device")
    async def confirm_agri_device(self, event: AstrMessageEvent, confirmation_id: str = "") -> str:
        """仅在同一用户明确确认刚才的待确认请求后调用；不得自行代替用户确认。

        Args:
            confirmation_id(string): start_agri_device 返回的确认编号；留空确认当前用户最近一条待确认请求。
        """
        return _result(await self.client.confirm(_identity(event), confirmation_id))

    @filter.llm_tool(name="stop_agri_device")
    async def stop_agri_device(
        self, event: AstrMessageEvent, device: str, station_code: str = ""
    ) -> str:
        """立即停止指定设备。停止不需要二次确认。

        Args:
            device(string): pump/喷药、lamp/驱虫灯/杀虫灯，或 all/全部。
            station_code(string): 站点编号；未指定时留空使用插件配置。
        """
        return _result(await self.client.stop(_identity(event), device, station_code))

    @filter.llm_tool(name="query_agri_control")
    async def query_agri_control(self, event: AstrMessageEvent, request_id: str = "") -> str:
        """查询当前 AstrBot 用户的最近一次或指定设备请求。

        Args:
            request_id(string): 之前返回的请求 UUID；留空查询最近一次。
        """
        return _result(await self.client.query(_identity(event), request_id))

    @filter.llm_tool(name="confirm_diagnosis_control")
    async def confirm_diagnosis_control(
        self, event: AstrMessageEvent, confirmation_id: str
    ) -> str:
        """仅当用户明确确认一条 AstrBot 病虫害消息告警时调用。

        Args:
            confirmation_id(string): AstrBot 消息告警中的完整确认 UUID，必须原样传入。
        """
        return _result(await self.client.confirm_diagnosis(_identity(event), confirmation_id))

    @filter.llm_tool(name="test_agri_device")
    async def test_agri_device(
        self, event: AstrMessageEvent, device: str, seconds: float = 0, station_code: str = ""
    ) -> str:
        """仅当用户本条原话明确说测试时跳过聊天二次确认；仍执行全部后端权限与安全检查。

        Args:
            device(string): pump/喷药，或 lamp/驱虫灯/杀虫灯。
            seconds(number): 测试运行秒数；未指定时固定为 5 秒，测试不会无限开启。
            station_code(string): 站点编号；未指定时留空使用插件配置。
        """
        return _result(await self.client.start(_identity(event), device, seconds, station_code, test=True))

    @filter.command("agri_control_status")
    async def agri_control_status(self, event: AstrMessageEvent):
        """查看 UMO、sender ID、平台账号映射及设备权限，不操作设备。"""
        yield event.plain_result(_command_text(await self.client.describe(_identity(event))))

    @filter.command("agri_start")
    async def agri_start(
        self, event: AstrMessageEvent, device: str, seconds: int = 0, station_code: str = ""
    ):
        """准备开启设备：/agri_start 驱虫灯，或 /agri_start 喷药 90"""
        yield event.plain_result(_command_text(
            await self.client.start(_identity(event), device, seconds, station_code)
        ))

    @filter.command("agri_confirm")
    async def agri_confirm(self, event: AstrMessageEvent, confirmation_id: str = ""):
        """确认最近请求：/agri_confirm [确认编号]"""
        yield event.plain_result(_command_text(
            await self.client.confirm(_identity(event), confirmation_id)
        ))

    @filter.command("agri_stop")
    async def agri_stop(self, event: AstrMessageEvent, device: str, station_code: str = ""):
        """立即停止设备：/agri_stop 喷药"""
        yield event.plain_result(_command_text(
            await self.client.stop(_identity(event), device, station_code)
        ))

    @filter.command("agri_result")
    async def agri_result(self, event: AstrMessageEvent, request_id: str = ""):
        """查询请求：/agri_result [request UUID]"""
        yield event.plain_result(_command_text(
            await self.client.query(_identity(event), request_id)
        ))

    @filter.command("agri_confirm_alert")
    async def agri_confirm_alert(self, event: AstrMessageEvent, confirmation_id: str):
        """确认病虫害 AstrBot 消息告警：/agri_confirm_alert <确认UUID>"""
        yield event.plain_result(_command_text(
            await self.client.confirm_diagnosis(_identity(event), confirmation_id)
        ))

    @filter.command("agri_test")
    async def agri_test(
        self, event: AstrMessageEvent, device: str, seconds: int = 0, station_code: str = ""
    ):
        """显式测试命令：/agri_test 喷药 5"""
        yield event.plain_result(_command_text(
            await self.client.start(_identity(event), device, seconds, station_code, test=True)
        ))

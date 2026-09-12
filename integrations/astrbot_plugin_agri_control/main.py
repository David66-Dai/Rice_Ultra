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


class AgriControlPlugin(Star):
    """通过 Rice Ultra Java 后端安全控制水泵和驱虫灯。"""

    def __init__(self, context: Context, config: AstrBotConfig):
        super().__init__(context)
        self.client = ControlClient(config)

    @filter.llm_tool(name="start_agri_device")
    async def start_agri_device(
        self, event: AstrMessageEvent, device: str, seconds: float = 5, station_code: str = ""
    ) -> str:
        """准备开启农业设备。此工具不会立即开启，而会返回二次确认编号。

        Args:
            device(string): pump/水泵/药泵/喷洒，或 lamp/驱虫灯。
            seconds(number): 运行秒数，必须为正整数；未指定时为 5 秒。
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
            device(string): pump/水泵、lamp/驱虫灯，或 all/全部。
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

    @filter.llm_tool(name="test_agri_device")
    async def test_agri_device(
        self, event: AstrMessageEvent, device: str, seconds: float = 5, station_code: str = ""
    ) -> str:
        """仅当用户本条原话明确说测试时跳过聊天二次确认；仍执行全部后端权限与安全检查。

        Args:
            device(string): pump/水泵/药泵/喷洒，或 lamp/驱虫灯。
            seconds(number): 测试运行秒数，必须为正整数。
            station_code(string): 站点编号；未指定时留空使用插件配置。
        """
        return _result(await self.client.start(_identity(event), device, seconds, station_code, test=True))

    @filter.command("agri_control_status")
    async def agri_control_status(self, event: AstrMessageEvent):
        """查看 UMO、sender ID、平台账号映射及设备权限，不操作设备。"""
        yield event.plain_result(_result(await self.client.describe(_identity(event))))

    @filter.command("agri_start")
    async def agri_start(
        self, event: AstrMessageEvent, device: str, seconds: int = 5, station_code: str = ""
    ):
        """准备开启设备：/agri_start 驱虫灯 5"""
        yield event.plain_result(_result(
            await self.client.start(_identity(event), device, seconds, station_code)
        ))

    @filter.command("agri_confirm")
    async def agri_confirm(self, event: AstrMessageEvent, confirmation_id: str = ""):
        """确认最近请求：/agri_confirm [确认编号]"""
        yield event.plain_result(_result(
            await self.client.confirm(_identity(event), confirmation_id)
        ))

    @filter.command("agri_stop")
    async def agri_stop(self, event: AstrMessageEvent, device: str, station_code: str = ""):
        """立即停止设备：/agri_stop 驱虫灯"""
        yield event.plain_result(_result(
            await self.client.stop(_identity(event), device, station_code)
        ))

    @filter.command("agri_result")
    async def agri_result(self, event: AstrMessageEvent, request_id: str = ""):
        """查询请求：/agri_result [request UUID]"""
        yield event.plain_result(_result(
            await self.client.query(_identity(event), request_id)
        ))

    @filter.command("agri_test")
    async def agri_test(
        self, event: AstrMessageEvent, device: str, seconds: int = 5, station_code: str = ""
    ):
        """显式测试命令：/agri_test 驱虫灯 5"""
        yield event.plain_result(_result(
            await self.client.start(_identity(event), device, seconds, station_code, test=True)
        ))

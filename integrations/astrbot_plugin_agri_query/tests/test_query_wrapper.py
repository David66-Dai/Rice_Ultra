"""AstrBot query wrapper tests with local stubs; no AstrBot process or network starts."""
from __future__ import annotations

import asyncio
import importlib.util
from pathlib import Path
import sys
import types

import pytest


class FakeEvent:
    def __init__(self, umo: str, sender):
        self.unified_msg_origin = umo
        self._sender = sender

    def get_sender_id(self):
        return self._sender

    def plain_result(self, text):
        return ("plain", text)


@pytest.fixture
def wrapper(monkeypatch):
    def decorator(function):
        return function

    class Filter:
        @staticmethod
        def llm_tool(*, name):
            return decorator

        @staticmethod
        def command(name):
            return decorator

    class FakeLogger:
        @staticmethod
        def warning(*args, **kwargs):
            return None

    class FakeStar:
        def __init__(self, context):
            self.context = context

    api_module = types.ModuleType("astrbot.api")
    api_module.AstrBotConfig = dict
    api_module.logger = FakeLogger()
    event_module = types.ModuleType("astrbot.api.event")
    event_module.AstrMessageEvent = FakeEvent
    event_module.filter = Filter
    star_module = types.ModuleType("astrbot.api.star")
    star_module.Context = object
    star_module.Star = FakeStar
    for name, module in {
        "astrbot": types.ModuleType("astrbot"),
        "astrbot.api": api_module,
        "astrbot.api.event": event_module,
        "astrbot.api.star": star_module,
    }.items():
        monkeypatch.setitem(sys.modules, name, module)

    path = Path(__file__).resolve().parents[1] / "main.py"
    spec = importlib.util.spec_from_file_location("rice_ultra_agri_query_wrapper_test", path)
    module = importlib.util.module_from_spec(spec)
    monkeypatch.setitem(sys.modules, spec.name, module)
    spec.loader.exec_module(module)
    return module


@pytest.mark.parametrize(("umo", "sender"), [
    ("qq-main:GroupMessage:987654321", "123456789"),
    ("qq-main:FriendMessage:123456789", 123456789),
])
def test_qq_group_and_private_identity_are_preserved(wrapper, umo, sender):
    assert wrapper.AgriQueryPlugin._identity(FakeEvent(umo, sender)) == {
        "umo": umo,
        "senderId": str(sender),
    }


def test_query_status_displays_full_identity_on_success(wrapper):
    event = FakeEvent("qq-main:GroupMessage:987654321", "123456789")
    plugin = wrapper.AgriQueryPlugin(object(), {})

    async def successful_post(event, path, body):
        assert path == "/api/astrbot/agriculture/status"
        return {
            "username": "operator",
            "displayName": "QQ 操作员",
            "defaultStation": "S01",
            "maxRangeDays": 31,
            "dataSource": "rice-ultra-java-api",
        }

    plugin._post = successful_post

    async def collect():
        return [item async for item in plugin.agri_query_status(event)]

    text = asyncio.run(collect())[0][1]
    assert "农业查询插件运行正常" in text
    assert "当前 UMO：qq-main:GroupMessage:987654321" in text
    assert "当前 sender ID：123456789" in text
    assert "映射账号：QQ 操作员（operator）" in text
    assert "默认站点：S01" in text
    assert "{" not in text


def test_query_status_displays_full_identity_on_failure(wrapper):
    event = FakeEvent("qq-main:FriendMessage:123456789", 123456789)
    plugin = wrapper.AgriQueryPlugin(object(), {})

    async def failed_post(event, path, body):
        raise ValueError("当前 AstrBot 身份未映射")

    plugin._post = failed_post

    async def collect():
        return [item async for item in plugin.agri_query_status(event)]

    text = asyncio.run(collect())[0][1]
    assert "农业查询插件不可用：当前 AstrBot 身份未映射" in text
    assert "当前 UMO：qq-main:FriendMessage:123456789" in text
    assert "当前 sender ID：123456789" in text


def test_metadata_declares_supported_chat_adapters_and_version():
    metadata = (Path(__file__).resolve().parents[1] / "metadata.yaml").read_text(encoding="utf-8")
    assert "version: v2.1.0" in metadata
    assert 'astrbot_version: ">=4.18,<5"' in metadata
    for adapter in ("weixin_oc", "aiocqhttp", "qq_official", "qq_official_webhook"):
        assert f"  - {adapter}" in metadata

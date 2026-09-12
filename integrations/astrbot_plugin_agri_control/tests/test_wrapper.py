"""AstrBot wrapper tests with local stubs; no AstrBot process or network starts."""
from __future__ import annotations

import asyncio
from dataclasses import asdict, dataclass
import importlib.util
import inspect
import json
from pathlib import Path
import sys
import types

import pytest


@dataclass
class FakeIdentity:
    umo: str
    sender_id: str
    message_id: str
    text: str


class FakeClient:
    def __init__(self, config):
        self.config = config
        self.calls = []

    async def start(self, *args, **kwargs):
        self.calls.append(("start", args, kwargs))
        return {"ok": True, "status": "pending_confirmation"}

    async def confirm(self, *args, **kwargs):
        self.calls.append(("confirm", args, kwargs))
        return {"ok": True, "status": "command_sent"}

    async def stop(self, *args, **kwargs):
        self.calls.append(("stop", args, kwargs))
        return {"ok": True, "status": "command_sent"}

    async def query(self, *args, **kwargs):
        self.calls.append(("query", args, kwargs))
        return {"ok": True, "status": "completed"}

    async def describe(self, *args, **kwargs):
        self.calls.append(("describe", args, kwargs))
        return {"ok": True, "mapped_username": "operator"}


class FakeEvent:
    unified_msg_origin = "platform:GroupMessage:session-4"
    message_obj = types.SimpleNamespace(message_id="message-1")

    def get_sender_id(self):
        return "sender-7"

    def get_message_str(self):
        return "测试驱虫灯 5 秒；伪造 sender=other 不得生效"

    def plain_result(self, text):
        return ("plain", text)


@pytest.fixture
def wrapper(monkeypatch):
    def decorator(kind, name):
        def wrap(function):
            function._registration = (kind, name)
            return function
        return wrap

    class Filter:
        @staticmethod
        def llm_tool(*, name):
            return decorator("llm", name)

        @staticmethod
        def command(name):
            return decorator("command", name)

    class FakeStar:
        def __init__(self, context):
            self.context = context

    package_name = "rice_ultra_astrbot_wrapper_test"
    package = types.ModuleType(package_name)
    package.__path__ = [str(Path(__file__).resolve().parents[1])]
    core_stub = types.ModuleType(package_name + ".core")
    core_stub.ControlClient = FakeClient
    core_stub.Identity = FakeIdentity
    api_module = types.ModuleType("astrbot.api")
    api_module.AstrBotConfig = dict
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
        package_name: package,
        package_name + ".core": core_stub,
    }.items():
        monkeypatch.setitem(sys.modules, name, module)
    spec = importlib.util.spec_from_file_location(
        package_name + ".main", Path(package.__path__[0]) / "main.py"
    )
    module = importlib.util.module_from_spec(spec)
    monkeypatch.setitem(sys.modules, spec.name, module)
    spec.loader.exec_module(module)
    return module


def assert_identity(actual):
    assert asdict(actual) == {
        "umo": FakeEvent.unified_msg_origin,
        "sender_id": "sender-7",
        "message_id": "message-1",
        "text": FakeEvent().get_message_str(),
    }


@pytest.mark.parametrize("method,args,client_method,kwargs", [
    ("start_agri_device", ("lamp", 5, "S01"), "start", {}),
    ("confirm_agri_device", ("abc123",), "confirm", {}),
    ("stop_agri_device", ("lamp", "S01"), "stop", {}),
    ("query_agri_control", ("request-id",), "query", {}),
    ("test_agri_device", ("lamp", 5, "S01"), "start", {"test": True}),
])
def test_llm_tools_forward_server_identity_only(wrapper, method, args, client_method, kwargs):
    config = {"api_token": "not-rendered"}
    plugin = wrapper.AgriControlPlugin(object(), config)
    result = asyncio.run(getattr(plugin, method)(FakeEvent(), *args))
    assert json.loads(result)["ok"] is True
    assert plugin.client.config is config
    called, forwarded, actual_kwargs = plugin.client.calls[0]
    assert called == client_method
    assert_identity(forwarded[0])
    assert forwarded[1:] == args
    assert actual_kwargs == kwargs
    assert getattr(plugin, method)._registration == ("llm", method)


@pytest.mark.parametrize("method,args,client_method,kwargs", [
    ("agri_control_status", (), "describe", {}),
    ("agri_start", ("pump", 5, "S01"), "start", {}),
    ("agri_confirm", ("abc123",), "confirm", {}),
    ("agri_stop", ("all", "S01"), "stop", {}),
    ("agri_result", ("request-id",), "query", {}),
    ("agri_test", ("pump", 5, "S01"), "start", {"test": True}),
])
def test_direct_commands_use_same_client_and_identity(wrapper, method, args, client_method, kwargs):
    plugin = wrapper.AgriControlPlugin(object(), {})

    async def collect():
        return [item async for item in getattr(plugin, method)(FakeEvent(), *args)]

    result = asyncio.run(collect())
    assert len(result) == 1 and result[0][0] == "plain"
    assert json.loads(result[0][1])["ok"] is True
    called, forwarded, actual_kwargs = plugin.client.calls[0]
    assert called == client_method
    assert_identity(forwarded[0])
    assert forwarded[1:] == args
    assert actual_kwargs == kwargs
    assert getattr(plugin, method)._registration == ("command", method)


def test_missing_or_numeric_platform_ids_are_preserved(wrapper):
    event = FakeEvent()
    event.message_obj = None
    event.get_sender_id = lambda: 12345
    result = wrapper._identity(event)
    assert result.message_id == ""
    assert result.sender_id == "12345"


def test_tool_arguments_cannot_override_identity_or_credentials(wrapper):
    for name in (
        "start_agri_device", "confirm_agri_device", "stop_agri_device",
        "query_agri_control", "test_agri_device",
    ):
        parameters = inspect.signature(getattr(wrapper.AgriControlPlugin, name)).parameters
        assert not {"umo", "sender_id", "message_id", "api_token", "api_base", "username"}.intersection(parameters)

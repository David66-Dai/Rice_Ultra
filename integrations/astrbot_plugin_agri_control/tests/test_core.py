"""Offline plugin tests; all HTTP requests use MockTransport."""
from __future__ import annotations

import asyncio
import importlib.util
import json
from pathlib import Path
import sys

import httpx
import pytest


MODULE_PATH = Path(__file__).resolve().parents[1] / "core.py"
SPEC = importlib.util.spec_from_file_location("rice_ultra_astrbot_core_test", MODULE_PATH)
core = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = core
SPEC.loader.exec_module(core)

TOKEN = "offline-integration-token-123456789012345"


class FakeJavaApi:
    def __init__(self):
        self.requests = []
        self.state = {"stationId": "S01", "device": "pump", "enabled": None, "revision": 0,
                      "updatedAt": None, "updatedBy": None}
        self.lamp = {"stationId": "S01", "device": "lamp", "enabled": None, "revision": 0,
                     "updatedAt": None, "updatedBy": None}
        self.can_control = True
        self.available = True
        self.test_allowed = False
        self.timeout_after_control = False
        self.username = "operator"

    @property
    def controls(self):
        return [request for request in self.requests if request.url.path.endswith("/control")]

    def __call__(self, request):
        self.requests.append(request)
        assert request.url.scheme == "http"
        assert request.url.host == "127.0.0.1"
        assert request.url.port == 8185
        assert request.headers["x-astrbot-token"] == TOKEN
        body = json.loads(request.content)
        assert body["identity"] == {"umo": "umo:test", "senderId": "sender-1"}
        if request.url.path.endswith("/sync"):
            return httpx.Response(200, json={
                "username": self.username,
                "displayName": "机器人操作员",
                "canControl": self.can_control,
                "available": self.available,
                "devices": [self.state, self.lamp],
                "testControlAllowed": self.test_allowed,
                "confirmationTtlSeconds": 120,
                "maxDurationSeconds": 300,
            })
        if request.url.path.endswith("/control"):
            target = self.state if body["device"] == "pump" else self.lamp
            if not self.can_control:
                return httpx.Response(403, json={"code": "device_forbidden", "message": "没有权限"})
            if not self.available:
                return httpx.Response(503, json={"code": "device_unavailable", "message": "串口离线"})
            if body["expectedRevision"] != target["revision"]:
                return httpx.Response(409, json={"code": "device_conflict", "message": "设备状态已变化"})
            if body["testMode"] and not self.test_allowed:
                return httpx.Response(403, json={"code": "device_forbidden", "message": "测试未开放"})
            target.update(enabled=body["enabled"], revision=target["revision"] + 1,
                          updatedAt="2026-09-11T08:00:00Z", updatedBy=self.username)
            payload = {
                "requestId": body["requestId"], "username": self.username,
                "testMode": body["testMode"],
                "secondaryConfirmationSkipped": body["testMode"],
                "autoOffAt": "2026-09-11T08:00:05Z" if body["enabled"] else None,
                "control": {
                    "stationId": "S01", "device": body["device"], "enabled": body["enabled"],
                    "command": "FA01", "sentAt": "2026-09-11T08:00:00Z", "state": dict(target),
                },
            }
            if self.timeout_after_control:
                raise httpx.ReadTimeout("response lost", request=request)
            return httpx.Response(200, json=payload)
        raise AssertionError(f"unexpected route: {request.url}")


@pytest.fixture
def fixture(tmp_path):
    api = FakeJavaApi()
    config = {
        "api_base": "http://127.0.0.1:8185",
        "api_token": TOKEN,
        "default_station": "S01",
        "allow_test_command": False,
    }

    def make(**updates):
        return core.ControlClient(
            {**config, **updates},
            state_path=tmp_path / "state.sqlite3",
            transport=httpx.MockTransport(api),
        )

    return make, api


def identity(message_id="message-1", text="打开水泵 5 秒", umo="umo:test", sender="sender-1"):
    return core.Identity(umo=umo, sender_id=sender, message_id=message_id, text=text)


def run(awaitable):
    return asyncio.run(awaitable)


def test_normal_start_only_prepares_and_same_user_confirmation_controls(fixture):
    make, api = fixture
    client = make()
    prepared = run(client.start(identity(), "水泵", 5))
    assert prepared["status"] == "pending_confirmation"
    assert prepared["requires_confirmation"] is True
    assert len(api.controls) == 0
    confirmed = run(client.confirm(identity(message_id="confirmation-message"), prepared["confirmation_id"]))
    assert confirmed["ok"] is True
    assert confirmed["state"]["enabled"] is True
    assert confirmed["username"] == "operator"
    assert len(api.controls) == 1
    body = json.loads(api.controls[0].content)
    assert body["confirmed"] is True and body["testMode"] is False
    assert body["durationSeconds"] == 5


def test_other_identity_cannot_confirm_even_with_confirmation_id(fixture):
    make, api = fixture
    client = make()
    prepared = run(client.start(identity(), "pump", 5))
    result = run(client.confirm(identity(umo="umo:other"), prepared["confirmation_id"]))
    assert result["code"] == "CONFIRMATION_NOT_FOUND"
    assert not api.controls


def test_expired_confirmation_never_posts(fixture, monkeypatch):
    make, api = fixture
    client = make()
    prepared = run(client.start(identity(), "pump", 5))
    monkeypatch.setattr(core.time, "time", lambda: prepared["expires_at"] + 1)
    result = run(client.confirm(identity(), prepared["confirmation_id"]))
    assert result["code"] == "CONFIRMATION_EXPIRED"
    assert not api.controls


def test_state_change_before_confirmation_requires_new_request(fixture):
    make, api = fixture
    client = make()
    prepared = run(client.start(identity(), "pump", 5))
    api.state["revision"] += 1
    result = run(client.confirm(identity(), prepared["confirmation_id"]))
    assert result["code"] == "STATE_CHANGED"
    assert not api.controls


@pytest.mark.parametrize("text", ["测试水泵 3 秒", "/agri_test pump 3", "test pump 3 seconds"])
def test_explicit_test_skips_confirmation_only_when_both_switches_allow(fixture, text):
    make, api = fixture
    api.test_allowed = True
    result = run(make(allow_test_command=True).start(identity(text=text), "pump", 3, test=True))
    assert result["ok"] is True
    assert result["secondary_confirmation_skipped"] is True
    assert len(api.controls) == 1
    body = json.loads(api.controls[0].content)
    assert body["confirmed"] is False and body["testMode"] is True


@pytest.mark.parametrize("local,server,text", [
    (False, True, "测试水泵"), (True, False, "测试水泵"), (True, True, "打开水泵"),
])
def test_test_mode_fails_closed_without_both_switches_and_explicit_text(fixture, local, server, text):
    make, api = fixture
    api.test_allowed = server
    result = run(make(allow_test_command=local).start(identity(text=text), "pump", 3, test=True))
    assert result["ok"] is False
    assert not api.controls


def test_stop_is_immediate_and_does_not_require_message_id_or_confirmation(fixture):
    make, api = fixture
    result = run(make().stop(identity(message_id="", text="立即停止水泵"), "pump"))
    assert result["ok"] is True
    body = json.loads(api.controls[0].content)
    assert body["enabled"] is False
    assert body["confirmed"] is False
    assert "durationSeconds" not in body


def test_duplicate_message_and_confirmation_never_send_twice(fixture):
    make, api = fixture
    client = make()
    prepared = run(client.start(identity(), "pump", 5))
    duplicate = run(client.start(identity(), "pump", 5))
    assert duplicate["confirmation_id"] == prepared["confirmation_id"]
    first = run(client.confirm(identity(), prepared["confirmation_id"]))
    second = run(client.confirm(identity(), prepared["confirmation_id"]))
    assert first == second
    assert len(api.controls) == 1


def test_lost_control_response_is_unknown_and_duplicate_does_not_retry(fixture):
    make, api = fixture
    api.test_allowed = True
    api.timeout_after_control = True
    client = make(allow_test_command=True)
    event = identity(text="测试水泵")
    first = run(client.start(event, "pump", 3, test=True))
    second = run(client.start(event, "pump", 3, test=True))
    assert first["code"] == second["code"] == "RESULT_UNKNOWN"
    assert len(api.controls) == 1


def test_permission_availability_duration_and_identity_fail_before_control(fixture):
    make, api = fixture
    api.can_control = False
    assert run(make().start(identity(), "pump", 5))["code"] == "FORBIDDEN"
    api.can_control = True
    api.available = False
    assert run(make().start(identity(message_id="m2"), "pump", 5))["code"] == "DEVICE_UNAVAILABLE"
    api.available = True
    assert run(make().start(identity(message_id="m3"), "pump", 301))["code"] == "DURATION_LIMIT"
    assert run(make().start(identity(message_id="m4", umo=""), "pump", 5))["code"] == "MISSING_IDENTITY"
    assert not api.controls


@pytest.mark.parametrize("updates", [
    {"api_token": "short"},
    {"api_base": "http://192.168.1.10:8080"},
    {"api_base": "https://example.invalid/path"},
    {"default_station": "S11"},
    {"allow_test_command": "true"},
])
def test_invalid_plugin_configuration_fails_without_network(fixture, updates):
    make, api = fixture
    result = run(make(**updates).describe(identity()))
    assert result["code"] == "CONFIG_ERROR"
    assert not api.requests


def test_status_reports_real_platform_mapping_and_never_exposes_token(fixture):
    make, api = fixture
    result = run(make().describe(identity()))
    assert result["mapped_username"] == "operator"
    assert result["can_control"] is True
    assert TOKEN not in json.dumps(result)
    assert not api.controls

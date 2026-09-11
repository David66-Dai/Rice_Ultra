"""AstrBot adapter for the Rice Ultra Java device API.

This module has no AstrBot or serial dependency so its safety behavior can be tested offline.
"""
from __future__ import annotations

from contextlib import closing
from dataclasses import dataclass
import hashlib
import json
import math
from pathlib import Path
import re
import secrets
import sqlite3
import time
from urllib.parse import urlsplit
import uuid

import httpx


REQUEST_NAMESPACE = uuid.UUID("1a7713e2-37d9-4e45-a238-9dd16b660c5c")
TEST_INTENT = re.compile(r"测试|\b(?:test|agri_test)\b", re.IGNORECASE)


@dataclass(frozen=True)
class Identity:
    umo: str
    sender_id: str
    message_id: str
    text: str


class Problem(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def failure(code: str, message: str, **extra):
    return {"ok": False, "code": code, "message": message, **extra}


class ControlClient:
    def __init__(self, config, *, state_path: Path | str | None = None, transport=None):
        self.raw_config = config
        self.state_path = Path(state_path) if state_path is not None else (
            Path.cwd() / "data" / "plugin_data" / "astrbot_plugin_agri_control" / "control.sqlite3"
        )
        self.transport = transport

    def _config(self):
        try:
            api_base = str(self.raw_config.get("api_base", "")).strip().rstrip("/")
            api_token = str(self.raw_config.get("api_token", ""))
            station = str(self.raw_config.get("default_station", "S01")).strip().upper()
            allow_test = self.raw_config.get("allow_test_command", False)
            parsed = urlsplit(api_base)
            if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password:
                raise ValueError()
            if parsed.query or parsed.fragment or parsed.path not in {"", "/"}:
                raise ValueError()
            if parsed.scheme == "http" and parsed.hostname not in {"127.0.0.1", "localhost", "::1"}:
                raise ValueError()
            if len(api_token) < 32 or not re.fullmatch(r"S(?:0[1-9]|10)", station):
                raise ValueError()
            if type(allow_test) is not bool:
                raise ValueError()
            return {
                "api_base": api_base,
                "api_token": api_token,
                "station": station,
                "allow_test": allow_test,
                "secrets": (api_token,),
            }
        except (AttributeError, TypeError, ValueError):
            raise Problem(
                "CONFIG_ERROR",
                "请在 AstrBot 插件设置中填写 Java API 地址和至少 32 位的独立集成令牌。",
            ) from None

    @staticmethod
    def _validate_identity(identity: Identity):
        if not identity.umo or not identity.sender_id:
            raise Problem("MISSING_IDENTITY", "当前消息缺少 UMO 或发送者 ID，不能进行设备操作。")
        if len(identity.umo) > 512 or len(identity.sender_id) > 256:
            raise Problem("INVALID_IDENTITY", "当前消息身份格式无效。")

    @staticmethod
    def _device(value, allow_all=False):
        aliases = {
            "pump": "pump", "water_pump": "pump", "spray": "pump", "水泵": "pump",
            "喷洒": "pump", "喷药": "pump", "药泵": "pump",
            "lamp": "lamp", "驱虫灯": "lamp", "灭虫灯": "lamp", "灯": "lamp",
        }
        if isinstance(value, str):
            normalized = value.strip().lower()
            if allow_all and normalized in {"all", "全部", "所有"}:
                return "all"
            if normalized in aliases:
                return aliases[normalized]
        raise Problem("INVALID_DEVICE", "设备只能是智能灌溉水泵（pump）或智能驱虫灯（lamp）。")

    @staticmethod
    def _duration(value):
        if type(value) not in (int, float) or not math.isfinite(value) or int(value) != value or int(value) < 1:
            raise Problem("INVALID_DURATION", "运行时长必须是正整数秒。")
        return int(value)

    def _database(self):
        self.state_path.parent.mkdir(parents=True, exist_ok=True)
        db = sqlite3.connect(str(self.state_path), timeout=3)
        db.execute(
            "CREATE TABLE IF NOT EXISTS requests ("
            "request_id TEXT PRIMARY KEY, owner TEXT NOT NULL, fingerprint TEXT NOT NULL, "
            "command TEXT NOT NULL, status TEXT NOT NULL, confirmation_id TEXT, expires_at REAL, "
            "result TEXT, created REAL NOT NULL)"
        )
        db.execute("CREATE INDEX IF NOT EXISTS idx_requests_owner_created ON requests(owner, created)")
        return db

    @staticmethod
    def _owner(identity: Identity):
        raw = json.dumps([identity.umo, identity.sender_id], ensure_ascii=False, separators=(",", ":"))
        return hashlib.sha256(raw.encode()).hexdigest()

    @staticmethod
    def _request_id(identity: Identity, device: str, enabled: bool, test_mode: bool):
        if enabled and not identity.message_id:
            raise Problem("MISSING_MESSAGE_ID", "消息平台没有提供消息编号，无法保证开启请求不重复。")
        key = identity.message_id or str(uuid.uuid4())
        raw = json.dumps(
            [identity.umo, identity.sender_id, key, device, enabled, test_mode],
            ensure_ascii=False,
            separators=(",", ":"),
        )
        return str(uuid.uuid5(REQUEST_NAMESPACE, raw))

    @staticmethod
    def _fingerprint(command):
        return hashlib.sha256(json.dumps(command, ensure_ascii=False, sort_keys=True).encode()).hexdigest()

    async def _http(self, path: str, cfg, body):
        try:
            async with httpx.AsyncClient(
                base_url=cfg["api_base"],
                headers={"X-AstrBot-Token": cfg["api_token"]},
                timeout=8,
                trust_env=False,
                follow_redirects=False,
                transport=self.transport,
            ) as client:
                response = await client.post(path, json=body)
            if len(response.content) > 65536:
                raise ValueError()
            payload = response.json()
            if not isinstance(payload, dict):
                raise ValueError()
            return response.status_code, payload
        except (httpx.HTTPError, ValueError, TypeError):
            raise Problem(
                "API_UNAVAILABLE",
                "Java 控制 API 连接失败、超时或响应无效；若已确认提交，结果可能未知，不能自动重发开启。",
            ) from None

    @staticmethod
    def _identity_body(identity: Identity):
        return {"umo": identity.umo, "senderId": identity.sender_id}

    async def _sync(self, identity: Identity, cfg):
        status, payload = await self._http(
            "/api/astrbot/devices/sync", cfg, {"identity": self._identity_body(identity)}
        )
        if status != 200:
            raise self._api_problem(status, payload)
        if (
            not isinstance(payload.get("username"), str)
            or type(payload.get("canControl")) is not bool
            or type(payload.get("available")) is not bool
            or not isinstance(payload.get("devices"), list)
            or type(payload.get("testControlAllowed")) is not bool
            or type(payload.get("confirmationTtlSeconds")) is not int
            or type(payload.get("maxDurationSeconds")) is not int
        ):
            raise Problem("INVALID_API_RESPONSE", "Java 控制 API 返回了无效的同步数据。")
        return payload

    @staticmethod
    def _api_problem(status, payload):
        messages = {
            400: "请求格式无效，未发送设备指令。",
            401: "AstrBot 集成令牌无效。",
            403: "当前 AstrBot 身份映射或平台账号没有设备控制权限。",
            409: "设备状态已变化或请求冲突，请重新发起操作。",
            503: "设备控制链路当前不可用。",
        }
        server = payload.get("message") if isinstance(payload.get("message"), str) else ""
        message = server[:500] if server else messages.get(status, "Java 控制 API 拒绝了请求。")
        return Problem("API_HTTP_" + str(status), message)

    @staticmethod
    def _state(snapshot, station, device):
        matches = [
            item for item in snapshot["devices"]
            if isinstance(item, dict) and item.get("stationId") == station and item.get("device") == device
        ]
        if len(matches) != 1 or type(matches[0].get("revision")) is not int:
            raise Problem("INVALID_API_RESPONSE", "Java 控制 API 没有返回目标设备的有效版本。")
        return matches[0]

    def _save_new(self, identity, request_id, command, status, confirmation_id=None, expires_at=None):
        fingerprint = self._fingerprint(command)
        with closing(self._database()) as db, db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute(
                "SELECT fingerprint,status,confirmation_id,expires_at,result FROM requests WHERE request_id=?",
                (request_id,),
            ).fetchone()
            if row:
                if row[0] != fingerprint:
                    raise Problem("REQUEST_CONFLICT", "同一条消息已生成不同的设备请求，不能更改参数重发。")
                return row
            db.execute(
                "INSERT INTO requests VALUES (?,?,?,?,?,?,?,?,?)",
                (
                    request_id, self._owner(identity), fingerprint,
                    json.dumps(command, ensure_ascii=False), status, confirmation_id,
                    expires_at, None, time.time(),
                ),
            )
        return None

    def _set_result(self, request_id, status, result=None):
        encoded = json.dumps(result, ensure_ascii=False) if result is not None else None
        with closing(self._database()) as db, db:
            db.execute("UPDATE requests SET status=?,result=? WHERE request_id=?", (status, encoded, request_id))

    @staticmethod
    def _known_result(row, request_id):
        status, confirmation_id, expires_at, raw_result = row[1], row[2], row[3], row[4]
        if raw_result:
            return json.loads(raw_result)
        if status == "pending_confirmation":
            return {
                "ok": True, "status": status, "requires_confirmation": True,
                "request_id": request_id, "confirmation_id": confirmation_id,
                "expires_at": expires_at,
                "message": "等待同一用户二次确认，尚未发送设备指令。",
            }
        return failure(
            "RESULT_UNKNOWN", "此前请求结果未知，请查看共享状态；不要自动重复开启。",
            status="unknown", request_id=request_id,
        )

    async def describe(self, identity: Identity):
        base = {
            "umo": identity.umo,
            "sender_id": identity.sender_id,
            "has_message_id": bool(identity.message_id),
        }
        try:
            self._validate_identity(identity)
            cfg = self._config()
            snapshot = await self._sync(identity, cfg)
            return {
                "ok": True, **base, "configured": True,
                "mapped_username": snapshot["username"],
                "can_control": snapshot["canControl"],
                "device_available": snapshot["available"],
                "test_command_enabled": cfg["allow_test"] and snapshot["testControlAllowed"],
                "default_station": cfg["station"],
            }
        except Problem as error:
            return {"ok": False, **base, "configured": False, "code": error.code, "message": error.message}

    async def start(self, identity: Identity, device, seconds=5, station_code="", test=False):
        try:
            self._validate_identity(identity)
            cfg = self._config()
            normalized = self._device(device)
            duration = self._duration(seconds)
            station = (station_code or cfg["station"]).strip().upper() if isinstance(station_code, str) else ""
            if station != cfg["station"]:
                raise Problem("FORBIDDEN_STATION", "该站点不是插件绑定的 Java 设备站点。")
            if type(test) is not bool:
                raise Problem("INVALID_TEST_MODE", "测试模式必须由专用工具或命令选择。")
            request_id = self._request_id(identity, normalized, True, test)
            command = {
                "station": station, "device": normalized, "enabled": True,
                "duration_seconds": duration, "test_mode": test,
            }
            if test:
                if not cfg["allow_test"] or not TEST_INTENT.search(identity.text or ""):
                    raise Problem("TEST_CONTROL_DISABLED", "测试命令未启用，或当前原消息未明确包含“测试”。")
                prior = self._save_new(identity, request_id, command, "dispatching")
                if prior:
                    return self._known_result(prior, request_id)
                return await self._execute(identity, cfg, request_id, command, confirmed=False, test_mode=True)

            snapshot = await self._sync(identity, cfg)
            self._assert_ready(snapshot, duration)
            state = self._state(snapshot, station, normalized)
            confirmation_id = secrets.token_hex(4)
            ttl = max(15, min(600, snapshot["confirmationTtlSeconds"]))
            expires_at = time.time() + ttl
            command["expected_revision"] = state["revision"]
            prior = self._save_new(
                identity, request_id, command, "pending_confirmation", confirmation_id, expires_at
            )
            if prior:
                return self._known_result(prior, request_id)
            return {
                "ok": True, "status": "pending_confirmation", "requires_confirmation": True,
                "request_id": request_id, "confirmation_id": confirmation_id,
                "expires_at": expires_at,
                "message": f"请在 {ttl} 秒内由同一用户确认；当前尚未发送{self._device_label(normalized)}开启指令。",
            }
        except Problem as error:
            return failure(error.code, error.message)
        except Exception:
            return failure("LOCAL_ERROR", "插件本地状态异常，未发送设备指令。")

    async def confirm(self, identity: Identity, confirmation_id=""):
        try:
            self._validate_identity(identity)
            cfg = self._config()
            with closing(self._database()) as db:
                if confirmation_id:
                    row = db.execute(
                        "SELECT request_id,command,status,expires_at FROM requests "
                        "WHERE owner=? AND confirmation_id=?",
                        (self._owner(identity), confirmation_id.strip()),
                    ).fetchone()
                else:
                    row = db.execute(
                        "SELECT request_id,command,status,expires_at FROM requests "
                        "WHERE owner=? AND status='pending_confirmation' ORDER BY created DESC LIMIT 1",
                        (self._owner(identity),),
                    ).fetchone()
            if not row:
                raise Problem("CONFIRMATION_NOT_FOUND", "当前会话用户没有待确认的设备请求。")
            request_id, raw_command, status, expires_at = row
            if status != "pending_confirmation":
                return await self.query(identity, request_id)
            if not isinstance(expires_at, (int, float)) or expires_at < time.time():
                self._set_result(request_id, "expired")
                raise Problem("CONFIRMATION_EXPIRED", "二次确认已过期，请重新发起开启请求。")
            command = json.loads(raw_command)
            snapshot = await self._sync(identity, cfg)
            self._assert_ready(snapshot, command["duration_seconds"])
            state = self._state(snapshot, command["station"], command["device"])
            if state["revision"] != command["expected_revision"]:
                self._set_result(request_id, "rejected")
                raise Problem("STATE_CHANGED", "设备状态在确认前已变化，请重新发起操作。")
            self._set_result(request_id, "dispatching")
            return await self._execute(identity, cfg, request_id, command, confirmed=True, test_mode=False)
        except Problem as error:
            return failure(error.code, error.message)
        except Exception:
            return failure("LOCAL_ERROR", "确认记录读取失败，未发送设备指令。")

    async def stop(self, identity: Identity, device, station_code=""):
        try:
            self._validate_identity(identity)
            cfg = self._config()
            normalized = self._device(device, allow_all=True)
            station = (station_code or cfg["station"]).strip().upper() if isinstance(station_code, str) else ""
            if station != cfg["station"]:
                raise Problem("FORBIDDEN_STATION", "该站点不是插件绑定的 Java 设备站点。")
            targets = ("pump", "lamp") if normalized == "all" else (normalized,)
            results = []
            for target in targets:
                request_id = self._request_id(identity, target, False, False)
                command = {"station": station, "device": target, "enabled": False, "test_mode": False}
                prior = self._save_new(identity, request_id, command, "dispatching")
                if prior:
                    results.append(self._known_result(prior, request_id))
                    continue
                results.append(await self._execute(
                    identity, cfg, request_id, command, confirmed=False, test_mode=False
                ))
            if len(results) == 1:
                return results[0]
            return {
                "ok": all(item.get("ok") for item in results),
                "status": "multiple", "message": "已分别处理水泵和驱虫灯停止请求。", "results": results,
            }
        except Problem as error:
            return failure(error.code, error.message)
        except Exception:
            return failure("STOP_ERROR", "停止请求结果未知，请检查共享状态和实际设备。")

    async def _execute(self, identity, cfg, request_id, command, *, confirmed, test_mode):
        try:
            snapshot = await self._sync(identity, cfg)
            self._assert_ready(snapshot, command.get("duration_seconds"))
            if test_mode and not snapshot["testControlAllowed"]:
                raise Problem("TEST_CONTROL_DISABLED", "Java 后端未启用 AstrBot 测试控制。")
            state = self._state(snapshot, command["station"], command["device"])
            expected = command.get("expected_revision", state["revision"])
            if expected != state["revision"]:
                self._set_result(request_id, "rejected")
                raise Problem("STATE_CHANGED", "设备状态已变化，请重新发起操作。")
            body = {
                "identity": self._identity_body(identity), "requestId": request_id,
                "stationId": command["station"], "device": command["device"],
                "enabled": command["enabled"], "expectedRevision": expected,
                "confirmed": confirmed, "testMode": test_mode,
                "originalText": identity.text[:1000],
            }
            if command["enabled"]:
                body["durationSeconds"] = command["duration_seconds"]
            status, payload = await self._http("/api/astrbot/devices/control", cfg, body)
            if status != 200:
                self._set_result(request_id, "rejected")
                raise self._api_problem(status, payload)
            result = self._validate_result(payload, request_id, snapshot["username"], command, test_mode, cfg)
            self._set_result(request_id, "completed", result)
            return result
        except Problem as error:
            if error.code in {"API_UNAVAILABLE", "INVALID_API_RESPONSE"}:
                unknown = failure(
                    "RESULT_UNKNOWN", "控制结果未知，请查看网页共享状态或明确发送停止命令；不要重复开启。",
                    status="unknown", request_id=request_id,
                )
                self._set_result(request_id, "unknown", unknown)
                return unknown
            rejected = failure(error.code, error.message, request_id=request_id)
            self._set_result(request_id, "rejected", rejected)
            return rejected

    @staticmethod
    def _assert_ready(snapshot, duration=None):
        if not snapshot["canControl"]:
            raise Problem("FORBIDDEN", "映射的平台账号没有设备控制权限。")
        if not snapshot["available"]:
            raise Problem("DEVICE_UNAVAILABLE", "Java 后端当前未连接设备串口。")
        if duration is not None and duration > snapshot["maxDurationSeconds"]:
            raise Problem("DURATION_LIMIT", f"运行时长超过后端上限 {snapshot['maxDurationSeconds']} 秒。")

    @staticmethod
    def _validate_result(payload, request_id, username, command, test_mode, cfg):
        control = payload.get("control")
        state = control.get("state") if isinstance(control, dict) else None
        valid = payload.get("requestId") == request_id and payload.get("username") == username
        valid = valid and type(payload.get("testMode")) is bool and payload["testMode"] == test_mode
        valid = valid and type(payload.get("secondaryConfirmationSkipped")) is bool
        valid = valid and isinstance(state, dict) and state.get("stationId") == command["station"]
        valid = valid and state.get("device") == command["device"] and state.get("enabled") is command["enabled"]
        valid = valid and type(state.get("revision")) is int
        if not valid:
            raise Problem("INVALID_API_RESPONSE", "Java 控制 API 返回的身份、请求或设备状态不一致。")
        safe = {
            "ok": True, "status": "command_sent", "request_id": request_id,
            "message": (
                ("测试命令已跳过二次确认；" if test_mode else "")
                + ("开启" if command["enabled"] else "关闭")
                + "指令已发送，实际设备状态仍需现场反馈确认。"
            ),
            "username": username, "test_mode": test_mode,
            "secondary_confirmation_skipped": payload["secondaryConfirmationSkipped"],
            "auto_off_at": payload.get("autoOffAt"),
            "state": {key: state.get(key) for key in (
                "stationId", "device", "enabled", "revision", "updatedAt", "updatedBy"
            )},
        }
        encoded = json.dumps(safe, ensure_ascii=False)
        for secret in cfg["secrets"]:
            encoded = encoded.replace(secret, "[redacted]")
        return json.loads(encoded)

    async def query(self, identity: Identity, request_id=""):
        try:
            self._validate_identity(identity)
            with closing(self._database()) as db:
                if request_id:
                    try:
                        request_id = str(uuid.UUID(request_id))
                    except (ValueError, TypeError, AttributeError):
                        raise Problem("INVALID_REQUEST_ID", "请求编号必须是有效 UUID。") from None
                    row = db.execute(
                        "SELECT request_id,fingerprint,status,confirmation_id,expires_at,result "
                        "FROM requests WHERE owner=? AND request_id=?",
                        (self._owner(identity), request_id),
                    ).fetchone()
                else:
                    row = db.execute(
                        "SELECT request_id,fingerprint,status,confirmation_id,expires_at,result "
                        "FROM requests WHERE owner=? ORDER BY created DESC LIMIT 1",
                        (self._owner(identity),),
                    ).fetchone()
            if not row:
                raise Problem("NOT_FOUND", "当前会话用户没有可查询的设备请求。")
            return self._known_result((row[1], row[2], row[3], row[4], row[5]), row[0])
        except Problem as error:
            return failure(error.code, error.message)
        except Exception:
            return failure("LOCAL_ERROR", "本地请求记录读取失败。")

    @staticmethod
    def _device_label(device):
        return "智能灌溉水泵" if device == "pump" else "智能驱虫灯"

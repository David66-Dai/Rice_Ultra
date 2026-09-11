"""Read the inference section of the shared, server-only YAML configuration."""
from __future__ import annotations

from dataclasses import dataclass
import os
from pathlib import Path
from typing import Any, Mapping

import yaml


DEFAULT_CONFIG_PATH = Path(__file__).resolve().parents[2] / "conf" / "config.yaml"


class ConfigurationError(ValueError):
    """A configuration error whose message never includes configuration values."""


class _UniqueKeyLoader(yaml.SafeLoader):
    """Safe YAML loader which also rejects duplicate and non-string keys."""

    def construct_mapping(self, node: yaml.MappingNode, deep: bool = False) -> dict:
        self.flatten_mapping(node)
        result: dict[str, Any] = {}
        for key_node, value_node in node.value:
            key = self.construct_object(key_node, deep=deep)
            if not isinstance(key, str):
                raise ConfigurationError("Shared YAML configuration keys must be strings.")
            if key in result:
                raise ConfigurationError("Shared YAML configuration contains a duplicate key.")
            result[key] = self.construct_object(value_node, deep=deep)
        return result


@dataclass(frozen=True)
class InferenceSettings:
    host: str
    port: int
    reload: bool
    allowed_origins: tuple[str, ...]


def _mapping(value: Any, field: str, allowed: set[str] | None = None) -> dict:
    if not isinstance(value, dict):
        raise ConfigurationError(f"{field} must be a YAML mapping.")
    if allowed is not None and set(value) - allowed:
        raise ConfigurationError(f"{field} contains an unsupported configuration key.")
    return value


def _host(value: Any) -> str:
    if not isinstance(value, str) or not value or any(c.isspace() for c in value):
        raise ConfigurationError("app.inference.host must be a non-empty host string without whitespace.")
    if "/" in value:
        raise ConfigurationError("app.inference.host must be a host, without a URL scheme or path.")
    return value


def _port(value: Any) -> int:
    if type(value) is not int or not 1 <= value <= 65535:
        raise ConfigurationError("app.inference.port must be an integer from 1 to 65535.")
    return value


def _reload(value: Any) -> bool:
    if type(value) is not bool:
        raise ConfigurationError("app.inference.reload must be a boolean.")
    return value


def _origins(value: Any) -> tuple[str, ...]:
    if not isinstance(value, list) or any(
        not isinstance(item, str) or not item or any(c.isspace() for c in item)
        for item in value
    ):
        raise ConfigurationError("app.inference.cors.allowed-origins must be a list of non-empty origin strings.")
    return tuple(value)


def _origins_from_environment(value: str) -> Any:
    if value.strip().startswith("["):
        try:
            return yaml.load(value, Loader=_UniqueKeyLoader)
        except (yaml.YAMLError, ConfigurationError):
            raise ConfigurationError("APP_INFERENCE_CORS_ALLOWED_ORIGINS must be a valid list or comma-separated origins.") from None
    return [origin.strip() for origin in value.split(",")]


def load_inference_settings(
    config_path: str | Path | None = None,
    environ: Mapping[str, str] | None = None,
) -> InferenceSettings:
    """Load the base YAML document; Spring profile documents are not Python settings.

    RICE_CONFIG_PATH overrides the repository-relative default. Explicit relative
    paths resolve against the working directory. APP_INFERENCE_* variables take
    precedence over YAML values. Only the inference subsection is consumed.
    """
    env = os.environ if environ is None else environ
    selected_path = config_path if config_path is not None else env.get("RICE_CONFIG_PATH")
    if selected_path == "":
        raise ConfigurationError("RICE_CONFIG_PATH must not be empty.")
    path = Path(selected_path).expanduser() if selected_path is not None else DEFAULT_CONFIG_PATH
    try:
        with path.open("r", encoding="utf-8-sig") as stream:
            documents = list(yaml.load_all(stream, Loader=_UniqueKeyLoader))
    except OSError:
        raise ConfigurationError("Cannot read shared configuration. Create conf/config.yaml from conf/config.example.yaml or set RICE_CONFIG_PATH.") from None
    except (yaml.YAMLError, UnicodeError):
        raise ConfigurationError("Shared configuration must contain valid UTF-8 YAML; configuration values are omitted from this error.") from None

    if not documents:
        raise ConfigurationError("Shared configuration must contain a base YAML document.")
    for document in documents:
        _mapping(document, "Shared configuration document")
    application = _mapping(documents[0].get("app"), "app")
    inference = _mapping(
        application.get("inference"),
        "app.inference",
        {"host", "port", "reload", "cors", "base-url"},
    )
    cors = _mapping(inference.get("cors", {}), "app.inference.cors", {"allowed-origins"})
    if "base-url" in inference and not isinstance(inference["base-url"], str):
        raise ConfigurationError("app.inference.base-url must be a string.")

    # Validate the source even when an environment variable overrides it, so a
    # malformed shared file is not silently accepted by just one service.
    host = _host(inference.get("host", "127.0.0.1"))
    port = _port(inference.get("port", 8001))
    reload_enabled = _reload(inference.get("reload", True))
    origins = _origins(cors.get("allowed-origins", ["*"]))

    if "APP_INFERENCE_HOST" in env:
        host = _host(env["APP_INFERENCE_HOST"])
    if "APP_INFERENCE_PORT" in env:
        raw_port = env["APP_INFERENCE_PORT"]
        if not raw_port.isascii() or not raw_port.isdecimal():
            raise ConfigurationError("APP_INFERENCE_PORT must be an integer from 1 to 65535.")
        try:
            port = _port(int(raw_port))
        except (ValueError, OverflowError):
            raise ConfigurationError("APP_INFERENCE_PORT must be an integer from 1 to 65535.") from None
    if "APP_INFERENCE_RELOAD" in env:
        raw_reload = env["APP_INFERENCE_RELOAD"].lower()
        if raw_reload not in {"true", "false"}:
            raise ConfigurationError("APP_INFERENCE_RELOAD must be true or false.")
        reload_enabled = raw_reload == "true"
    if "APP_INFERENCE_CORS_ALLOWED_ORIGINS" in env:
        origins = _origins(_origins_from_environment(env["APP_INFERENCE_CORS_ALLOWED_ORIGINS"]))
    return InferenceSettings(host, port, reload_enabled, origins)

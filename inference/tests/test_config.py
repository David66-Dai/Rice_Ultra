"""Offline configuration contract checks: python -m unittest discover -s inference/tests."""
from __future__ import annotations

from contextlib import contextmanager
import io
import os
from pathlib import Path
import sys
import tempfile
import traceback
import unittest
from unittest.mock import Mock, patch
from types import SimpleNamespace

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.config import ConfigurationError, DEFAULT_CONFIG_PATH, load_inference_settings
import run as inference_launcher


class InferenceConfigurationTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "config.yaml"
        self.base = {"app": {"inference": {
            "host": "127.0.0.1", "port": 8123, "reload": False,
            "base-url": "http://${app.inference.host}:${app.inference.port}",
            "cors": {"allowed-origins": ["http://localhost:5173"]},
        }}, "spring": {"datasource": {"password": "private-test-password"}}}
        self.write_base()

    def write_base(self):
        self.path.write_text(yaml.safe_dump(self.base), encoding="utf-8")

    @contextmanager
    def working_directory(self, directory):
        previous = Path.cwd()
        try:
            os.chdir(directory)
            yield
        finally:
            os.chdir(previous)

    def test_loads_shared_base_and_ignores_profile_documents(self):
        with self.path.open("a", encoding="utf-8") as stream:
            stream.write("---\nspring:\n  config:\n    activate:\n      on-profile: mysql\n  datasource:\n    password: profile-password\n")
        settings = load_inference_settings(self.path, {})
        self.assertEqual((settings.host, settings.port, settings.reload), ("127.0.0.1", 8123, False))
        self.assertEqual(settings.allowed_origins, ("http://localhost:5173",))

    def test_environment_overrides(self):
        settings = load_inference_settings(environ={
            "RICE_CONFIG_PATH": str(self.path), "APP_INFERENCE_HOST": "0.0.0.0",
            "APP_INFERENCE_PORT": "9000", "APP_INFERENCE_RELOAD": "true",
            "APP_INFERENCE_CORS_ALLOWED_ORIGINS": "http://localhost:3000,https://example.test",
        })
        self.assertEqual((settings.host, settings.port, settings.reload), ("0.0.0.0", 9000, True))
        self.assertEqual(settings.allowed_origins, ("http://localhost:3000", "https://example.test"))
        settings = load_inference_settings(self.path, {"APP_INFERENCE_CORS_ALLOWED_ORIGINS": '["*"]'})
        self.assertEqual(settings.allowed_origins, ("*",))

    def test_default_path_is_source_relative_and_independent_of_working_directory(self):
        expected = Path(__file__).resolve().parents[2] / "conf" / "config.yaml"
        self.assertEqual(DEFAULT_CONFIG_PATH, expected)
        with self.working_directory(self.directory.name), patch("app.config.DEFAULT_CONFIG_PATH", self.path):
            self.assertEqual(load_inference_settings(environ={}).port, 8123)

    def test_explicit_relative_path_resolves_from_working_directory(self):
        with self.working_directory(self.directory.name):
            self.assertEqual(load_inference_settings(environ={"RICE_CONFIG_PATH": "config.yaml"}).port, 8123)

    def test_launcher_passes_yaml_settings_to_uvicorn_without_starting_a_server(self):
        run_uvicorn = Mock()
        with patch.dict(os.environ, {"RICE_CONFIG_PATH": str(self.path)}, clear=True), patch.dict(
            sys.modules, {"uvicorn": SimpleNamespace(run=run_uvicorn)}
        ), self.working_directory(self.directory.name):
            self.assertEqual(inference_launcher.main(), 0)
        self.assertEqual(run_uvicorn.call_args.args, ("app.main:app",))
        self.assertEqual(run_uvicorn.call_args.kwargs["host"], "127.0.0.1")
        self.assertEqual(run_uvicorn.call_args.kwargs["port"], 8123)
        self.assertFalse(run_uvicorn.call_args.kwargs["reload"])

    def test_launcher_reports_config_failure_before_importing_uvicorn(self):
        with patch.dict(os.environ, {"RICE_CONFIG_PATH": str(self.path.parent / "missing.yaml")}, clear=True), patch(
            "sys.stderr", new_callable=io.StringIO
        ) as stderr:
            self.assertEqual(inference_launcher.main(), 2)
        self.assertIn("Inference configuration error", stderr.getvalue())

    def test_rejects_invalid_types_ports_and_unknown_keys(self):
        cases = [
            ("host", ""), ("host", "http://localhost"), ("host", 123),
            ("port", 0), ("port", 65536), ("port", True), ("port", "8001"),
            ("reload", "false"), ("cors", "*"), ("cors", {"allowed-origins": "*"}),
            ("cors", {"allowed-origins": [False]}), ("cors", {"typo": True}),
            ("base-url", 12), ("typo", "private-test-password"),
        ]
        for key, value in cases:
            with self.subTest(key=key, value=value):
                original = self.base["app"]["inference"].copy()
                self.base["app"]["inference"][key] = value
                self.write_base()
                with self.assertRaises(ConfigurationError) as caught:
                    load_inference_settings(self.path, {})
                self.assertNotIn("private-test-password", str(caught.exception))
                self.base["app"]["inference"] = original

    def test_rejects_invalid_environment(self):
        cases = [
            ("APP_INFERENCE_PORT", "8001.0"), ("APP_INFERENCE_PORT", "true"),
            ("APP_INFERENCE_PORT", "0"), ("APP_INFERENCE_PORT", "65536"),
            ("APP_INFERENCE_RELOAD", "1"), ("APP_INFERENCE_CORS_ALLOWED_ORIGINS", "[broken-secret"),
            ("APP_INFERENCE_HOST", ""),
        ]
        for key, value in cases:
            with self.subTest(key=key):
                with self.assertRaises(ConfigurationError):
                    load_inference_settings(self.path, {key: value})

    def test_rejects_duplicate_keys_in_any_document(self):
        for fragment in [
            "app:\n  inference:\n    port: 8001\n    port: 8123\n",
            yaml.safe_dump(self.base) + "---\nspring:\n  password: secret-one\n  password: secret-two\n",
        ]:
            self.path.write_text(fragment, encoding="utf-8")
            with self.assertRaisesRegex(ConfigurationError, "duplicate key"):
                load_inference_settings(self.path, {})

    def test_parse_errors_and_missing_files_do_not_echo_secrets(self):
        self.path.write_text("app: [secret-marker\n", encoding="utf-8")
        try:
            load_inference_settings(self.path, {})
        except ConfigurationError:
            formatted = traceback.format_exc()
        else:
            self.fail("Malformed YAML was accepted")
        self.assertNotIn("secret-marker", formatted)
        with self.assertRaises(ConfigurationError):
            load_inference_settings(self.path.parent / "missing.yaml", {})
        with self.assertRaises(ConfigurationError):
            load_inference_settings(environ={"RICE_CONFIG_PATH": ""})

    def test_rejects_empty_or_non_mapping_documents(self):
        for content in ["", "[]", "app: null", "app:\n  inference: []", yaml.safe_dump(self.base) + "---\n[]"]:
            self.path.write_text(content, encoding="utf-8")
            with self.assertRaises(ConfigurationError):
                load_inference_settings(self.path, {})


if __name__ == "__main__":
    unittest.main()

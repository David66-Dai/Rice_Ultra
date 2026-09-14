"""Hyperspectral cube endpoint: python -m unittest inference.tests.test_hyperspectral."""
from __future__ import annotations

from io import BytesIO
from pathlib import Path
import sys
import tempfile
import unittest

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT))
sys.path.insert(0, str(REPO_ROOT / "inference"))


class HyperspectralInferenceTests(unittest.TestCase):
    def test_health_reports_leaf_hsi_key(self):
        from fastapi.testclient import TestClient
        from app.main import app

        with TestClient(app) as client:
            payload = client.get("/health").json()
        self.assertIn("leaf_hsi", payload["models"])

    def test_predict_leaf_hsi_on_synthetic_cube(self):
        from fastapi.testclient import TestClient
        from app.hsi import leaf_hsi_weights_path
        from app.main import app
        from model_train.hyperspectral.generate import render_cube
        from model_train.hyperspectral.io import write_h5
        import numpy as np

        if not leaf_hsi_weights_path().is_file():
            self.skipTest("hsi weights missing")
        rng = np.random.default_rng(0)
        cube, wavelengths, leaf, lesion = render_cube(
            "Brown Spot", "indica", "tillering", 2, rng, height=16, width=16, bands=64,
        )
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "cube.h5"
            write_h5(path, cube, wavelengths, {"class_name": "Brown Spot"}, leaf, lesion)
            data = path.read_bytes()
        with TestClient(app) as client:
            response = client.post(
                "/predict/leaf-hsi",
                files={"file": ("cube.h5", BytesIO(data), "application/octet-stream")},
            )
        self.assertEqual(response.status_code, 200, response.text)
        body = response.json()
        self.assertEqual(body["task"], "leaf-hsi")
        self.assertIn("label", body)
        self.assertIn("spectrum", body)
        self.assertGreater(len(body["spectrum"]["wavelengths"]), 8)

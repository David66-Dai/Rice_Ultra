"""Shared class names, wavelengths, and cube geometry."""
from __future__ import annotations

from pathlib import Path
import numpy as np

CLASS_NAMES = (
	"Healthy Leaf",
	"Bacterial Leaf Blight",
	"Brown Spot",
	"Tungro Virus",
)
CLASS_TO_IDX = {name: index for index, name in enumerate(CLASS_NAMES)}
HEALTHY_CLASS = "Healthy Leaf"

VARIETIES = ("indica", "japonica", "hybrid")
GROWTH_STAGES = ("tillering", "jointing", "heading")
SEVERITY_COVER = {0: 0.0, 1: 0.08, 2: 0.25, 3: 0.50}

DEFAULT_HEIGHT = 64
DEFAULT_WIDTH = 64
DEFAULT_BANDS = 64
WAVELENGTH_START_NM = 400.0
WAVELENGTH_END_NM = 1000.0

WAVELENGTHS = np.linspace(WAVELENGTH_START_NM, WAVELENGTH_END_NM, DEFAULT_BANDS, dtype=np.float32)

PACKAGE_DIR = Path(__file__).resolve().parent
DATA_DIR = PACKAGE_DIR / "data"
CHECKPOINT_DIR = PACKAGE_DIR / "checkpoints"


def wavelengths_for(bands: int) -> np.ndarray:
	return np.linspace(WAVELENGTH_START_NM, WAVELENGTH_END_NM, bands, dtype=np.float32)


def band_index(wavelengths: np.ndarray, target_nm: float) -> int:
	return int(np.argmin(np.abs(wavelengths.astype(np.float64) - target_nm)))

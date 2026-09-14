"""Vegetation indices, spectral texture, and PCA helpers."""
from __future__ import annotations

import numpy as np

from .constants import band_index
from .preprocess import snv


def vegetation_indices(spectrum: np.ndarray, wavelengths: np.ndarray) -> dict[str, float]:
	red = float(spectrum[band_index(wavelengths, 670.0)])
	red_edge = float(spectrum[band_index(wavelengths, 720.0)])
	nir = float(spectrum[band_index(wavelengths, 800.0)])
	green = float(spectrum[band_index(wavelengths, 550.0)])
	blue = float(spectrum[band_index(wavelengths, 450.0)])
	def _norm(a: float, b: float) -> float:
		return (a - b) / (a + b + 1e-6)
	return {
		"ndvi": _norm(nir, red),
		"ndre": _norm(nir, red_edge),
		"psri": (red - green) / (nir + 1e-6),
		"sipi": (nir - blue) / (nir - red + 1e-6),
	}


def texture_variance(cube: np.ndarray, mask: np.ndarray) -> float:
	if cube.shape[0] < 3 or cube.shape[1] < 3:
		return float(cube[mask].var()) if mask.any() else 0.0
	padded = np.pad(cube, ((1, 1), (1, 1), (0, 0)), mode="edge")
	acc = np.zeros(cube.shape[:2], dtype=np.float64)
	for dy in (-1, 0, 1):
		for dx in (-1, 0, 1):
			acc += padded[1 + dy:1 + dy + cube.shape[0], 1 + dx:1 + dx + cube.shape[1]].mean(axis=-1)
	mean = acc / 9.0
	return float(mean[mask].var()) if mask.any() else 0.0


def feature_vector(spectrum: np.ndarray, wavelengths: np.ndarray, texture: float) -> np.ndarray:
	indices = vegetation_indices(spectrum, wavelengths)
	return np.concatenate([
		snv(spectrum[None, :])[0],
		np.array([indices["ndvi"], indices["ndre"], indices["psri"], indices["sipi"], texture], dtype=np.float32),
	]).astype(np.float32)

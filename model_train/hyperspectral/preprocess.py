"""Reflectance correction, smoothing, SNV, and NDVI background removal."""
from __future__ import annotations

import numpy as np

from .constants import band_index

DARK_OFFSET = 0.01
WHITE_SCALE = 0.96
NDVI_THRESHOLD = 0.25


def reflectance_correct(cube: np.ndarray, dark: float = DARK_OFFSET, white: float = WHITE_SCALE) -> np.ndarray:
	corrected = (cube.astype(np.float32) - dark) / max(white - dark, 1e-6)
	return np.clip(corrected, 0.0, 1.5)


def savgol_smooth(cube: np.ndarray, window: int = 7, poly: int = 2) -> np.ndarray:
	bands = cube.shape[-1]
	window = min(window, bands if bands % 2 == 1 else bands - 1)
	if window < 3:
		return cube
	try:
		from scipy.signal import savgol_filter
		return savgol_filter(cube, window_length=window, polyorder=min(poly, window - 1), axis=-1, mode="interp").astype(np.float32)
	except Exception:
		kernel = np.ones(window, dtype=np.float32) / window
		padded = np.pad(cube, ((0, 0), (0, 0), (window // 2, window // 2)), mode="edge")
		smooth = np.apply_along_axis(lambda spec: np.convolve(spec, kernel, mode="valid"), -1, padded)
		return smooth.astype(np.float32)


def snv(spectra: np.ndarray) -> np.ndarray:
	mean = spectra.mean(axis=-1, keepdims=True)
	std = spectra.std(axis=-1, keepdims=True)
	std = np.where(std < 1e-6, 1.0, std)
	return ((spectra - mean) / std).astype(np.float32)


def ndvi_map(cube: np.ndarray, wavelengths: np.ndarray) -> np.ndarray:
	red = cube[..., band_index(wavelengths, 670.0)]
	nir = cube[..., band_index(wavelengths, 800.0)]
	return (nir - red) / np.clip(nir + red, 1e-6, None)


def leaf_from_ndvi(cube: np.ndarray, wavelengths: np.ndarray, threshold: float = NDVI_THRESHOLD) -> np.ndarray:
	return ndvi_map(cube, wavelengths) >= threshold


def preprocess_cube(cube: np.ndarray, wavelengths: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
	corrected = reflectance_correct(cube)
	smoothed = savgol_smooth(corrected)
	mask = leaf_from_ndvi(smoothed, wavelengths)
	if int(mask.sum()) < 8:
		mask = smoothed.mean(axis=-1) > 0.08
	return smoothed, mask

"""Extract labelled ROI spectra from generated cubes and split by cube id."""
from __future__ import annotations

from pathlib import Path

import numpy as np

from .constants import CLASS_TO_IDX, HEALTHY_CLASS
from .features import feature_vector, texture_variance
from .io import load_cube
from .preprocess import preprocess_cube, snv


def mean_spectrum(cube: np.ndarray, mask: np.ndarray) -> np.ndarray:
	pixels = cube[mask]
	if pixels.size == 0:
		return cube.reshape(-1, cube.shape[-1]).mean(axis=0).astype(np.float32)
	return pixels.mean(axis=0).astype(np.float32)


def extra_patches(cube: np.ndarray, mask: np.ndarray, rng: np.random.Generator, count: int = 2) -> list[np.ndarray]:
	ys, xs = np.where(mask)
	if ys.size == 0:
		return []
	spectra = []
	height, width = mask.shape
	for _ in range(count):
		idx = int(rng.integers(0, ys.size))
		y, x = int(ys[idx]), int(xs[idx])
		y0, y1 = max(0, y - 4), min(height, y + 5)
		x0, x1 = max(0, x - 4), min(width, x + 5)
		block = cube[y0:y1, x0:x1]
		block_mask = mask[y0:y1, x0:x1]
		spectra.append(mean_spectrum(block, block_mask))
	return spectra


def extract_samples(data_dir: Path, seed: int = 2026) -> dict[str, np.ndarray | list]:
	rng = np.random.default_rng(seed)
	h5_dir = data_dir / "h5"
	paths = sorted(h5_dir.glob("*.h5"))
	if not paths:
		raise FileNotFoundError(f"未找到立方体: {h5_dir}")
	cube_ids: list[str] = []
	labels: list[int] = []
	severities: list[float] = []
	spectra: list[np.ndarray] = []
	features: list[np.ndarray] = []
	wavelengths_out: np.ndarray | None = None
	for path in paths:
		cube, wavelengths, meta = load_cube(path)
		processed, ndvi_mask = preprocess_cube(cube, wavelengths)
		stored_leaf = meta.get("leaf_mask")
		stored_lesion = meta.get("lesion_mask")
		class_name = str(meta.get("class_name", HEALTHY_CLASS))
		if stored_lesion is not None and np.asarray(stored_lesion).any() and class_name != HEALTHY_CLASS:
			roi = np.asarray(stored_lesion, dtype=bool) & ndvi_mask
		else:
			roi = np.asarray(stored_leaf, dtype=bool) if stored_leaf is not None else ndvi_mask
		if not roi.any():
			roi = ndvi_mask
		primary = mean_spectrum(processed, roi)
		texture = texture_variance(processed, roi)
		wavelengths_out = wavelengths
		bundle = [primary, *extra_patches(processed, roi, rng)]
		for spectrum in bundle:
			cube_ids.append(path.stem)
			labels.append(CLASS_TO_IDX[class_name])
			severities.append(float(meta.get("severity", 0)))
			spectra.append(snv(spectrum[None, :])[0])
			features.append(feature_vector(spectrum, wavelengths, texture))
	assert wavelengths_out is not None
	return {
		"cube_ids": np.array(cube_ids),
		"y": np.array(labels, dtype=np.int64),
		"severity": np.array(severities, dtype=np.float32),
		"spectra": np.stack(spectra).astype(np.float32),
		"features": np.stack(features).astype(np.float32),
		"wavelengths": wavelengths_out,
	}


def split_by_cube(cube_ids: np.ndarray, y: np.ndarray, seed: int = 2026) -> dict[str, np.ndarray]:
	rng = np.random.default_rng(seed)
	unique = np.array(sorted(set(cube_ids.tolist())))
	# stratify by the cube's first sample label
	cube_label = {cube: int(y[cube_ids == cube][0]) for cube in unique}
	train, val, test = [], [], []
	for label in sorted(set(cube_label.values())):
		group = [cube for cube in unique if cube_label[cube] == label]
		rng.shuffle(group)
		n = len(group)
		n_train = max(1, int(round(n * 0.70)))
		n_val = max(0, int(round(n * 0.15)))
		if n_train + n_val >= n and n > 1:
			n_val = max(0, n - n_train - 1)
		train.extend(group[:n_train])
		val.extend(group[n_train:n_train + n_val])
		test.extend(group[n_train + n_val:])
		if not test and group:
			moved = train.pop()
			test.append(moved)
	sets = {"train": set(train), "val": set(val), "test": set(test)}
	return {name: np.array([cube_ids[i] in cubes for i in range(len(cube_ids))]) for name, cubes in sets.items()}

"""Physically inspired synthetic rice-leaf hyperspectral cubes (ENVI + HDF5)."""
from __future__ import annotations

import argparse
import csv
from pathlib import Path

import numpy as np

from .constants import (
	CLASS_NAMES,
	DATA_DIR,
	DEFAULT_BANDS,
	DEFAULT_HEIGHT,
	DEFAULT_WIDTH,
	GROWTH_STAGES,
	HEALTHY_CLASS,
	SEVERITY_COVER,
	VARIETIES,
	wavelengths_for,
)
from .io import write_envi, write_h5

VARIETY_CHL = {"indica": 1.00, "japonica": 1.12, "hybrid": 0.94}
STAGE_CHL = {"tillering": 0.88, "jointing": 1.05, "heading": 0.97}


def _sigmoid(x: np.ndarray) -> np.ndarray:
	return 1.0 / (1.0 + np.exp(-x))


def base_leaf_reflectance(wavelengths: np.ndarray, chlorophyll: float, nir: float) -> np.ndarray:
	"""VIS–NIR rice leaf: carotenoid/chlorophyll wells, green peak, red edge, NIR plateau."""
	wl = wavelengths.astype(np.float64)
	blue = 0.045 + 0.025 * _sigmoid((wl - 470) / 18)
	green = 0.07 + 0.11 / chlorophyll * np.exp(-((wl - 555) ** 2) / (2 * 28 ** 2))
	red_well = 0.055 / chlorophyll + 0.02 * _sigmoid((wl - 720) / 12)
	red_well = red_well * (1.0 - 0.55 * np.exp(-((wl - 675) ** 2) / (2 * 22 ** 2)))
	red_edge = nir * _sigmoid((wl - 720) / 12)
	water = 1.0 - 0.08 * np.exp(-((wl - 970) ** 2) / (2 * 35 ** 2))
	spec = np.maximum(blue, green)
	spec = np.minimum(spec, 0.22)
	spec = spec * (0.55 + 0.45 * (1.0 - np.exp(-((wl - 500) ** 2) / (2 * 90 ** 2))))
	spec = np.where(wl < 700, np.maximum(spec * red_well * 8.0, 0.03), spec)
	spec = np.where(wl >= 680, red_edge * water, spec)
	spec = np.clip(spec, 0.02, 0.85)
	return spec.astype(np.float32)


def disease_spectrum(class_name: str, base: np.ndarray, wavelengths: np.ndarray, severity: int) -> np.ndarray:
	wl = wavelengths.astype(np.float64)
	spec = base.astype(np.float64).copy()
	weight = SEVERITY_COVER.get(severity, 0.0)
	if class_name == "Bacterial Leaf Blight":
		spec = spec + weight * 0.08 * np.exp(-((wl - 670) ** 2) / (2 * 35 ** 2))
		spec = spec * (1.0 - weight * 0.28 * _sigmoid((wl - 760) / 20))
		shift = np.interp(wl, wl - 8 * weight, spec)
		spec = (1.0 - 0.35 * weight) * spec + 0.35 * weight * shift
	elif class_name == "Brown Spot":
		spec = spec * (1.0 - weight * 0.55 * _sigmoid((wl - 750) / 15))
		spec = spec + weight * 0.06 * np.exp(-((wl - 620) ** 2) / (2 * 40 ** 2))
		spec = spec + weight * 0.04 * np.exp(-((wl - 920) ** 2) / (2 * 50 ** 2))
	elif class_name == "Tungro Virus":
		spec = spec * (1.0 - weight * 0.7 * np.exp(-((wl - 555) ** 2) / (2 * 25 ** 2)))
		spec = spec + weight * 0.07 * np.exp(-((wl - 650) ** 2) / (2 * 30 ** 2))
		spec = spec * (1.0 - weight * 0.18 * _sigmoid((wl - 780) / 25))
		shift = np.interp(wl, wl - 12 * weight, spec)
		spec = (1.0 - 0.4 * weight) * spec + 0.4 * weight * shift
	return np.clip(spec, 0.015, 0.9).astype(np.float32)


def soil_spectrum(wavelengths: np.ndarray) -> np.ndarray:
	wl = wavelengths.astype(np.float64)
	return np.clip(0.05 + 0.00012 * (wl - 400) + 0.02 * np.sin((wl - 400) / 80), 0.04, 0.18).astype(np.float32)


def leaf_mask(height: int, width: int) -> np.ndarray:
	yy, xx = np.ogrid[:height, :width]
	cy, cx = (height - 1) / 2.0, (width - 1) / 2.0
	ry, rx = height * 0.42, width * 0.18
	return ((yy - cy) ** 2) / (ry ** 2) + ((xx - cx) ** 2) / (rx ** 2) <= 1.0


def lesion_mask(class_name: str, leaf: np.ndarray, severity: int, rng: np.random.Generator) -> np.ndarray:
	cover = SEVERITY_COVER.get(severity, 0.0)
	mask = np.zeros_like(leaf, dtype=bool)
	if class_name == HEALTHY_CLASS or cover <= 0:
		return mask
	ys, xs = np.where(leaf)
	if ys.size == 0:
		return mask
	target = max(1, int(round(ys.size * cover)))
	if class_name == "Tungro Virus":
		chosen = rng.choice(ys.size, size=min(target, ys.size), replace=False)
		mask[ys[chosen], xs[chosen]] = True
		return mask
	if class_name == "Bacterial Leaf Blight":
		height, width = leaf.shape
		cx = width // 2
		for offset in range(-2, 3):
			col = np.clip(cx + offset, 0, width - 1)
			mask[:, col] = leaf[:, col]
		# thin additional veins
		for _ in range(2):
			col = int(rng.integers(max(1, width // 4), max(2, 3 * width // 4)))
			mask[:, col] |= leaf[:, col]
		# trim to coverage
		on = np.flatnonzero(mask.ravel())
		if on.size > target:
			keep = rng.choice(on, size=target, replace=False)
			trimmed = np.zeros_like(mask)
			trimmed.ravel()[keep] = True
			return trimmed
		return mask
	# Brown spot: scattered disks
	height, width = leaf.shape
	placed = 0
	attempts = 0
	while placed < target and attempts < 400:
		attempts += 1
		y = int(rng.integers(0, height))
		x = int(rng.integers(0, width))
		if not leaf[y, x]:
			continue
		radius = int(rng.integers(1, 4))
		yy, xx = np.ogrid[:height, :width]
		disk = (yy - y) ** 2 + (xx - x) ** 2 <= radius ** 2
		new = disk & leaf & ~mask
		added = int(new.sum())
		if added == 0:
			continue
		mask |= new
		placed += added
	return mask


def render_cube(
	class_name: str,
	variety: str,
	growth_stage: str,
	severity: int,
	rng: np.random.Generator,
	height: int = DEFAULT_HEIGHT,
	width: int = DEFAULT_WIDTH,
	bands: int = DEFAULT_BANDS,
) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
	wavelengths = wavelengths_for(bands)
	chl = VARIETY_CHL[variety] * STAGE_CHL[growth_stage]
	nir = 0.50 + 0.08 * (chl - 1.0)
	healthy = base_leaf_reflectance(wavelengths, chl, nir)
	diseased = disease_spectrum(class_name, healthy, wavelengths, severity)
	soil = soil_spectrum(wavelengths)
	leaf = leaf_mask(height, width)
	lesion = lesion_mask(class_name, leaf, severity, rng)
	cube = np.broadcast_to(soil, (height, width, bands)).copy()
	noise = rng.normal(0.0, 0.006, size=cube.shape).astype(np.float32)
	# white-ref / dark-current analog: scale plus offset that preprocess will invert
	dark = 0.01
	white = 0.96
	cube[leaf] = healthy
	cube[lesion] = diseased
	cube = np.clip(cube * white + dark + noise, 0.0, 1.0).astype(np.float32)
	return cube, wavelengths, leaf, lesion


def labels_for_index(index: int) -> dict[str, object]:
	class_name = CLASS_NAMES[index % len(CLASS_NAMES)]
	variety = VARIETIES[(index // len(CLASS_NAMES)) % len(VARIETIES)]
	growth_stage = GROWTH_STAGES[(index // (len(CLASS_NAMES) * len(VARIETIES))) % len(GROWTH_STAGES)]
	if class_name == HEALTHY_CLASS:
		severity = 0
	else:
		severity = 1 + (index // 9) % 3
	return {
		"class_name": class_name,
		"variety": variety,
		"growth_stage": growth_stage,
		"severity": severity,
	}


def generate_dataset(
	count: int,
	output_dir: Path | None = None,
	height: int = DEFAULT_HEIGHT,
	width: int = DEFAULT_WIDTH,
	bands: int = DEFAULT_BANDS,
	seed: int = 2026,
) -> Path:
	root = output_dir or DATA_DIR
	h5_dir = root / "h5"
	envi_dir = root / "envi"
	h5_dir.mkdir(parents=True, exist_ok=True)
	envi_dir.mkdir(parents=True, exist_ok=True)
	manifest_path = root / "manifest.csv"
	rng = np.random.default_rng(seed)
	rows: list[dict[str, object]] = []
	for index in range(count):
		meta = labels_for_index(index)
		cube, wavelengths, leaf, lesion = render_cube(
			str(meta["class_name"]),
			str(meta["variety"]),
			str(meta["growth_stage"]),
			int(meta["severity"]),
			rng,
			height=height,
			width=width,
			bands=bands,
		)
		stem = f"cube_{index:04d}"
		h5_path = h5_dir / f"{stem}.h5"
		write_h5(h5_path, cube, wavelengths, meta, leaf, lesion)
		write_envi(envi_dir / stem, cube, wavelengths)
		ys, xs = np.where(leaf)
		rows.append({
			"cube_id": stem,
			"h5": str(h5_path.relative_to(root)).replace("\\", "/"),
			"envi": f"envi/{stem}",
			"class_name": meta["class_name"],
			"variety": meta["variety"],
			"growth_stage": meta["growth_stage"],
			"severity": meta["severity"],
			"roi_y0": int(ys.min()) if ys.size else 0,
			"roi_y1": int(ys.max()) if ys.size else 0,
			"roi_x0": int(xs.min()) if xs.size else 0,
			"roi_x1": int(xs.max()) if xs.size else 0,
			"leaf_pixels": int(leaf.sum()),
			"lesion_pixels": int(lesion.sum()),
		})
	with manifest_path.open("w", encoding="utf-8", newline="") as handle:
		writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
		writer.writeheader()
		writer.writerows(rows)
	return root


def main(argv: list[str] | None = None) -> None:
	parser = argparse.ArgumentParser(description="Generate synthetic rice-leaf hyperspectral cubes")
	parser.add_argument("--count", type=int, default=240)
	parser.add_argument("--height", type=int, default=DEFAULT_HEIGHT)
	parser.add_argument("--width", type=int, default=DEFAULT_WIDTH)
	parser.add_argument("--bands", type=int, default=DEFAULT_BANDS)
	parser.add_argument("--out", type=Path, default=DATA_DIR)
	parser.add_argument("--seed", type=int, default=2026)
	args = parser.parse_args(argv)
	path = generate_dataset(args.count, args.out, args.height, args.width, args.bands, args.seed)
	print(f"wrote {args.count} cubes under {path}")


if __name__ == "__main__":
	main()

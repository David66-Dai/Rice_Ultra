"""Classify a hyperspectral cube with the trained 1D-CNN."""
from __future__ import annotations

from pathlib import Path

import numpy as np

from .constants import CHECKPOINT_DIR, CLASS_NAMES, HEALTHY_CLASS
from .dataset import mean_spectrum
from .features import vegetation_indices
from .io import load_cube_from_bytes
from .models import Spectral1DCNN
from .preprocess import preprocess_cube, snv

_model = None
_meta = None
_device = None


def weights_path() -> Path:
	import os
	override = os.environ.get("LEAF_HSI_MODEL_PATH", "").strip()
	return Path(override).expanduser() if override else CHECKPOINT_DIR / "hsi_1dcnn.pt"


def _load():
	global _model, _meta, _device
	if _model is not None:
		return _model, _meta, _device
	import torch
	path = weights_path()
	if not path.is_file():
		raise FileNotFoundError(str(path))
	device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
	ckpt = torch.load(path, map_location=device, weights_only=False)
	bands = int(ckpt.get("bands", 64))
	class_names = list(ckpt.get("class_names") or CLASS_NAMES)
	model = Spectral1DCNN(bands, len(class_names))
	model.load_state_dict(ckpt["model_state"])
	model.to(device)
	model.eval()
	_model, _meta, _device = model, ckpt, device
	return _model, _meta, _device


def reset_runtime() -> None:
	global _model, _meta, _device
	_model = _meta = _device = None


def predict_cube_path(path: str | Path) -> dict:
	path = Path(path)
	if not path.is_file():
		raise FileNotFoundError(str(path))
	if path.suffix.lower() == ".hdr":
		from io import BytesIO
		import zipfile

		buffer = BytesIO()
		with zipfile.ZipFile(buffer, "w") as archive:
			archive.write(path, arcname=path.name)
			companion = path.with_suffix(".dat")
			if companion.is_file():
				archive.write(companion, arcname=companion.name)
		return predict_cube_bytes(buffer.getvalue(), f"{path.stem}.zip")
	return predict_cube_bytes(path.read_bytes(), path.name)


def predict_cube_bytes(data: bytes, filename: str | None = None) -> dict:
	import torch

	cube, wavelengths, _meta = load_cube_from_bytes(data, filename)
	if cube.ndim != 3:
		raise ValueError("立方体必须是 高×宽×波段")
	processed, mask = preprocess_cube(cube, wavelengths)
	raw = mean_spectrum(processed, mask)
	model, meta, device = _load()
	expected = int(meta.get("bands", raw.shape[0]))
	if raw.shape[0] != expected:
		xp = np.linspace(0.0, 1.0, raw.shape[0])
		fp = np.linspace(0.0, 1.0, expected)
		raw = np.interp(fp, xp, raw).astype(np.float32)
		wavelengths = np.interp(fp, xp, wavelengths).astype(np.float32)
	spectrum = snv(raw[None, :])[0]
	tensor = torch.from_numpy(spectrum).float().view(1, 1, -1).to(device)
	with torch.no_grad():
		logits, severity = model(tensor)
		probabilities = torch.softmax(logits, dim=1)[0]
	index = int(probabilities.argmax().item())
	class_names = list(meta.get("class_names") or CLASS_NAMES)
	label = class_names[index]
	healthy = str(meta.get("healthy_folder_name", HEALTHY_CLASS))
	indices = vegetation_indices(raw, wavelengths)
	scores = {class_names[i]: float(probabilities[i].item()) for i in range(len(class_names))}
	return {
		"task": "leaf-hsi",
		"filename": filename,
		"label": label,
		"label_zh": _zh(label),
		"confidence": float(probabilities[index].item()),
		"has_leaf_damage": label != healthy,
		"severity": float(np.clip(severity.squeeze().cpu().item() * 3.0, 0.0, 3.0)),
		"alert_level": "yellow" if label != healthy else "green",
		"all_probabilities": scores,
		"spectrum": {
			"wavelengths": [float(v) for v in wavelengths.tolist()],
			"reflectance": [float(v) for v in raw.tolist()],
			"ndvi": indices["ndvi"],
			"ndre": indices["ndre"],
		},
	}


def _zh(label: str) -> str:
	mapping = {
		"Healthy Leaf": "健康叶片",
		"Bacterial Leaf Blight": "细菌性叶枯病",
		"Brown Spot": "褐斑病",
		"Tungro Virus": "东格鲁病毒",
	}
	return mapping.get(label, label)

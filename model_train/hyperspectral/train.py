"""Train RandomForest (PCA features) and 1D-CNN (spectra) on synthetic cubes."""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np

from .constants import CHECKPOINT_DIR, CLASS_NAMES, CLASS_TO_IDX, DATA_DIR, HEALTHY_CLASS
from .dataset import extract_samples, split_by_cube
from .models import Spectral1DCNN


def _metrics(y_true: np.ndarray, y_pred: np.ndarray) -> dict:
	from sklearn.metrics import accuracy_score, confusion_matrix, f1_score
	return {
		"accuracy": float(accuracy_score(y_true, y_pred)),
		"macro_f1": float(f1_score(y_true, y_pred, average="macro")),
		"confusion_matrix": confusion_matrix(y_true, y_pred, labels=list(range(len(CLASS_NAMES)))).tolist(),
	}


def train_random_forest(features: np.ndarray, y: np.ndarray, split: dict[str, np.ndarray], checkpoint_dir: Path) -> dict:
	from sklearn.decomposition import PCA
	from sklearn.ensemble import RandomForestClassifier
	from sklearn.pipeline import Pipeline
	from sklearn.preprocessing import StandardScaler
	import joblib

	components = min(16, features.shape[1], int(split["train"].sum()) - 1)
	pipeline = Pipeline([
		("scale", StandardScaler()),
		("pca", PCA(n_components=max(2, components), random_state=2026)),
		("rf", RandomForestClassifier(n_estimators=200, random_state=2026, n_jobs=1)),
	])
	pipeline.fit(features[split["train"]], y[split["train"]])
	joblib.dump(pipeline, checkpoint_dir / "hsi_rf.joblib")
	pred = pipeline.predict(features[split["test"]])
	return _metrics(y[split["test"]], pred)


def train_cnn(
	spectra: np.ndarray,
	y: np.ndarray,
	severity: np.ndarray,
	split: dict[str, np.ndarray],
	checkpoint_dir: Path,
	epochs: int = 40,
) -> tuple[dict, float]:
	import torch
	from torch.utils.data import DataLoader, TensorDataset

	device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
	model = Spectral1DCNN(spectra.shape[1], len(CLASS_NAMES)).to(device)
	optimizer = torch.optim.Adam(model.parameters(), lr=1e-3)
	ce = torch.nn.CrossEntropyLoss()
	mse = torch.nn.MSELoss()

	def loader(mask: np.ndarray, shuffle: bool) -> DataLoader:
		x = torch.from_numpy(spectra[mask]).unsqueeze(1)
		labels = torch.from_numpy(y[mask])
		sev = torch.from_numpy(severity[mask] / 3.0)
		return DataLoader(TensorDataset(x, labels, sev), batch_size=32, shuffle=shuffle)

	train_loader = loader(split["train"], True)
	val_loader = loader(split["val"], False) if split["val"].any() else None
	best_state = None
	best_acc = -1.0
	for _ in range(epochs):
		model.train()
		for xb, yb, sb in train_loader:
			xb, yb, sb = xb.to(device), yb.to(device), sb.to(device)
			logits, sev_hat = model(xb)
			loss = ce(logits, yb) + 0.25 * mse(sev_hat.squeeze(-1), sb)
			optimizer.zero_grad()
			loss.backward()
			optimizer.step()
		if val_loader is None:
			continue
		model.eval()
		correct = total = 0
		with torch.no_grad():
			for xb, yb, _sb in val_loader:
				logits, _ = model(xb.to(device))
				correct += int((logits.argmax(1).cpu() == yb).sum())
				total += int(yb.numel())
		acc = correct / max(total, 1)
		if acc >= best_acc:
			best_acc = acc
			best_state = {key: value.detach().cpu().clone() for key, value in model.state_dict().items()}
	if best_state is not None:
		model.load_state_dict(best_state)
	model.eval()
	test_x = torch.from_numpy(spectra[split["test"]]).unsqueeze(1).to(device)
	with torch.no_grad():
		logits, sev_hat = model(test_x)
		pred = logits.argmax(1).cpu().numpy()
		sev_pred = sev_hat.squeeze(-1).cpu().numpy() * 3.0
	mae = float(np.mean(np.abs(sev_pred - severity[split["test"]])))
	payload = {
		"model_state": model.state_dict(),
		"num_classes": len(CLASS_NAMES),
		"bands": int(spectra.shape[1]),
		"class_names": list(CLASS_NAMES),
		"class_to_idx": CLASS_TO_IDX,
		"healthy_folder_name": HEALTHY_CLASS,
	}
	torch.save(payload, checkpoint_dir / "hsi_1dcnn.pt")
	return _metrics(y[split["test"]], pred), mae


def save_confusion_plot(matrix: list[list[int]], path: Path) -> None:
	try:
		import matplotlib.pyplot as plt
	except ImportError:
		return
	arr = np.asarray(matrix)
	fig, ax = plt.subplots(figsize=(5.5, 4.5))
	im = ax.imshow(arr, cmap="Blues")
	ax.set_xticks(range(len(CLASS_NAMES)), [name.replace(" ", "\n") for name in CLASS_NAMES], fontsize=8)
	ax.set_yticks(range(len(CLASS_NAMES)), [name.replace(" ", "\n") for name in CLASS_NAMES], fontsize=8)
	for i in range(arr.shape[0]):
		for j in range(arr.shape[1]):
			ax.text(j, i, str(arr[i, j]), ha="center", va="center")
	fig.colorbar(im, ax=ax, fraction=0.046)
	ax.set_title("1D-CNN confusion")
	fig.tight_layout()
	fig.savefig(path, dpi=140)
	plt.close(fig)


def train_all(data_dir: Path | None = None, checkpoint_dir: Path | None = None, epochs: int = 40) -> dict:
	data_dir = data_dir or DATA_DIR
	checkpoint_dir = checkpoint_dir or CHECKPOINT_DIR
	checkpoint_dir.mkdir(parents=True, exist_ok=True)
	samples = extract_samples(data_dir)
	split = split_by_cube(samples["cube_ids"], samples["y"])
	rf_metrics = train_random_forest(samples["features"], samples["y"], split, checkpoint_dir)
	cnn_metrics, severity_mae = train_cnn(
		samples["spectra"], samples["y"], samples["severity"], split, checkpoint_dir, epochs=epochs,
	)
	label_map = {
		"class_names": list(CLASS_NAMES),
		"class_to_idx": CLASS_TO_IDX,
		"healthy_folder_name": HEALTHY_CLASS,
		"wavelengths": samples["wavelengths"].tolist(),
	}
	(checkpoint_dir / "label_map.json").write_text(json.dumps(label_map, indent=2), encoding="utf-8")
	metrics = {
		"random_forest": rf_metrics,
		"cnn": cnn_metrics,
		"severity_mae": severity_mae,
		"n_samples": int(len(samples["y"])),
		"n_cubes": int(len(set(samples["cube_ids"].tolist()))),
	}
	(checkpoint_dir / "metrics.json").write_text(json.dumps(metrics, indent=2), encoding="utf-8")
	save_confusion_plot(cnn_metrics["confusion_matrix"], checkpoint_dir / "confusion_cnn.png")
	return metrics

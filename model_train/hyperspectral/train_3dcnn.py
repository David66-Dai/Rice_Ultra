"""Optional 3D-CNN on 16x16x32 patches. Skipped unless --3dcnn is passed."""
from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn

from .constants import CHECKPOINT_DIR, CLASS_NAMES, DATA_DIR
from .io import load_cube
from .preprocess import preprocess_cube


class Tiny3DCNN(nn.Module):
	def __init__(self, bands: int = 32, num_classes: int = 4):
		super().__init__()
		self.net = nn.Sequential(
			nn.Conv3d(1, 8, 3, padding=1),
			nn.ReLU(inplace=True),
			nn.AdaptiveAvgPool3d((4, 4, 4)),
			nn.Flatten(),
			nn.Linear(8 * 4 * 4 * 4, num_classes),
		)

	def forward(self, x: torch.Tensor) -> torch.Tensor:
		return self.net(x)


def _patches(data_dir, limit: int = 64):
	paths = sorted((data_dir / "h5").glob("*.h5"))[:limit]
	xs, ys = [], []
	from .constants import CLASS_TO_IDX
	for path in paths:
		cube, wavelengths, meta = load_cube(path)
		processed, mask = preprocess_cube(cube, wavelengths)
		if processed.shape[-1] >= 32:
			processed = processed[..., :32]
		height, width, bands = processed.shape
		y0 = max(0, (height - 16) // 2)
		x0 = max(0, (width - 16) // 2)
		patch = np.zeros((16, 16, 32), dtype=np.float32)
		crop = processed[y0:y0 + 16, x0:x0 + 16]
		h, w, c = crop.shape
		patch[:h, :w, :c] = crop
		xs.append(patch)
		ys.append(CLASS_TO_IDX[str(meta.get("class_name", CLASS_NAMES[0]))])
	return np.stack(xs), np.array(ys, dtype=np.int64)


def main(argv: list[str] | None = None) -> None:
	parser = argparse.ArgumentParser(description="Optional tiny 3D-CNN (skip on CPU-only machines)")
	parser.add_argument("--data", type=str, default=str(DATA_DIR))
	parser.add_argument("--epochs", type=int, default=8)
	args = parser.parse_args(argv)
	if not torch.cuda.is_available():
		print("no GPU; skip 3D-CNN")
		return
	x, y = _patches(Path(args.data))
	device = torch.device("cuda")
	model = Tiny3DCNN().to(device)
	opt = torch.optim.Adam(model.parameters(), lr=1e-3)
	loss_fn = nn.CrossEntropyLoss()
	xb = torch.from_numpy(x).unsqueeze(1).to(device)
	yb = torch.from_numpy(y).to(device)
	for _ in range(args.epochs):
		opt.zero_grad()
		loss = loss_fn(model(xb), yb)
		loss.backward()
		opt.step()
	CHECKPOINT_DIR.mkdir(parents=True, exist_ok=True)
	torch.save({"model_state": model.state_dict(), "class_names": list(CLASS_NAMES)}, CHECKPOINT_DIR / "hsi_3dcnn.pt")
	print("saved", CHECKPOINT_DIR / "hsi_3dcnn.pt")


if __name__ == "__main__":
	main()

"""1D CNN over reflectance spectra with an optional severity head."""
from __future__ import annotations

import torch
import torch.nn as nn


class Spectral1DCNN(nn.Module):
	def __init__(self, bands: int, num_classes: int = 4):
		super().__init__()
		self.encoder = nn.Sequential(
			nn.Conv1d(1, 16, kernel_size=5, padding=2),
			nn.GroupNorm(4, 16),
			nn.ReLU(inplace=True),
			nn.MaxPool1d(2),
			nn.Conv1d(16, 32, kernel_size=5, padding=2),
			nn.GroupNorm(8, 32),
			nn.ReLU(inplace=True),
			nn.AdaptiveAvgPool1d(8),
		)
		self.embed = nn.Sequential(
			nn.Flatten(),
			nn.Linear(32 * 8, 64),
			nn.ReLU(inplace=True),
		)
		self.cls = nn.Linear(64, num_classes)
		self.severity = nn.Linear(64, 1)

	def forward(self, x: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
		hidden = self.embed(self.encoder(x))
		return self.cls(hidden), self.severity(hidden)

"""Pipeline entry: generate cubes, extract samples, train RF + 1D-CNN."""
from __future__ import annotations

import argparse
from pathlib import Path

from .constants import CHECKPOINT_DIR, DATA_DIR, DEFAULT_BANDS, DEFAULT_HEIGHT, DEFAULT_WIDTH
from .generate import generate_dataset
from .train import train_all


def main(argv: list[str] | None = None) -> None:
	parser = argparse.ArgumentParser(description="Synthetic hyperspectral rice-leaf pipeline")
	parser.add_argument("--all", action="store_true", help="generate + train")
	parser.add_argument("--generate", action="store_true")
	parser.add_argument("--train", action="store_true")
	parser.add_argument("--3dcnn", dest="train_3dcnn", action="store_true")
	parser.add_argument("--count", type=int, default=240)
	parser.add_argument("--height", type=int, default=DEFAULT_HEIGHT)
	parser.add_argument("--width", type=int, default=DEFAULT_WIDTH)
	parser.add_argument("--bands", type=int, default=DEFAULT_BANDS)
	parser.add_argument("--epochs", type=int, default=40)
	parser.add_argument("--out", type=Path, default=DATA_DIR)
	parser.add_argument("--checkpoints", type=Path, default=CHECKPOINT_DIR)
	args = parser.parse_args(argv)
	run_generate = args.all or args.generate
	run_train = args.all or args.train
	if not run_generate and not run_train and not args.train_3dcnn:
		run_generate = run_train = True
	if run_generate:
		generate_dataset(args.count, args.out, args.height, args.width, args.bands)
		print(f"generated {args.count} cubes in {args.out}")
	if run_train:
		metrics = train_all(args.out, args.checkpoints, epochs=args.epochs)
		print(metrics)
	if args.train_3dcnn:
		from .train_3dcnn import main as train_3d
		train_3d(["--data", str(args.out)])


if __name__ == "__main__":
	main()

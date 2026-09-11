"""
水稻五大害虫 YOLO 检测训练。

类别：稻纵卷叶螟、二化螟、褐飞虱、白背飞虱、灰飞虱
标注：YOLO 检测格式（同名 .txt，每行 class cx cy w h，归一化 0~1）

用法（在本目录执行）：
  python train.py
  python train.py --weights yolo11s.pt --epochs 200 --batch 16
  python train.py --resume
"""
from __future__ import annotations

import argparse
import sys
from collections import Counter
from pathlib import Path

from ultralytics import YOLO


ROOT = Path(__file__).resolve().parent
REPO_ROOT = ROOT.parents[1]
DATA_YAML = ROOT / "data.yaml"
CLASS_NAMES = ("稻纵卷叶螟", "二化螟", "褐飞虱", "白背飞虱", "灰飞虱")


def _setup_stdio() -> None:
    if sys.platform == "win32":
        try:
            sys.stdout.reconfigure(encoding="utf-8")
            sys.stderr.reconfigure(encoding="utf-8")
        except Exception:
            pass


def resolve_weights(name: str) -> str:
    candidate = Path(name)
    if candidate.is_file():
        return str(candidate.resolve())
    for folder in (ROOT, REPO_ROOT):
        local = folder / name
        if local.is_file():
            return str(local.resolve())
    return name


def pick_device(explicit: str | None) -> int | str:
    if explicit:
        if explicit.lower() == "cpu":
            return "cpu"
        if "," in explicit:
            return [int(x) for x in explicit.split(",")]
        return int(explicit)
    try:
        import torch

        return 0 if torch.cuda.is_available() else "cpu"
    except Exception:
        return "cpu"


def count_split(split: str) -> tuple[int, Counter]:
    labels_dir = ROOT / split / "labels"
    boxes: Counter = Counter()
    n_files = 0
    if not labels_dir.is_dir():
        return 0, boxes
    for path in labels_dir.glob("*.txt"):
        n_files += 1
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line:
                continue
            cls = int(float(line.split()[0]))
            boxes[cls] += 1
    return n_files, boxes


def print_dataset_stats() -> None:
    print(f"[*] 数据配置: {DATA_YAML}")
    for split in ("train", "val"):
        n_files, boxes = count_split(split)
        total = sum(boxes.values())
        print(f"[*] {split}: {n_files} 张, {total} 框")
        for i, name in enumerate(CLASS_NAMES):
            print(f"    {i} {name}: {boxes.get(i, 0)}")
        extra = {k: v for k, v in boxes.items() if k not in range(len(CLASS_NAMES))}
        if extra:
            raise SystemExit(f"标签中出现未定义类别: {extra}")


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="水稻五大害虫 YOLO 检测训练")
    p.add_argument("--data", type=Path, default=DATA_YAML, help="数据集 yaml")
    p.add_argument(
        "--weights",
        default="yolo11x.pt",
        help="预训练权重。优先用本目录或仓库根目录的同名文件",
    )
    p.add_argument("--epochs", type=int, default=200)
    p.add_argument("--imgsz", type=int, default=640)
    p.add_argument("--batch", type=int, default=8, help="显存不足可改为 4 或 2；-1 为自动 batch")
    p.add_argument("--patience", type=int, default=40)
    p.add_argument("--device", default=None, help='如 0、cpu、"0,1"')
    p.add_argument("--workers", type=int, default=None)
    p.add_argument("--name", default="rice_pests_v2")
    p.add_argument("--resume", action="store_true", help="从 runs 中同名实验的 last.pt 续训")
    return p.parse_args()


def main() -> None:
    _setup_stdio()
    args = parse_args()
    data_yaml = args.data.resolve()
    if not data_yaml.is_file():
        raise SystemExit(f"未找到数据集配置: {data_yaml}")

    weights = resolve_weights(args.weights)
    device = pick_device(args.device)
    workers = args.workers
    if workers is None:
        workers = 0 if sys.platform == "win32" else 8

    print(f"[*] 脚本目录: {ROOT}")
    print(f"[*] 预训练权重: {weights}")
    print(f"[*] 设备: {device}")
    print(f"[*] 轮数: {args.epochs}, 图像尺寸: {args.imgsz}, batch: {args.batch}")
    print_dataset_stats()

    project = ROOT / "runs" / "detect"
    last_ckpt = project / args.name / "weights" / "last.pt"
    if args.resume:
        if not last_ckpt.is_file():
            raise SystemExit(f"未找到可续训权重: {last_ckpt}")
        model = YOLO(str(last_ckpt))
        model.train(resume=True)
        return

    model = YOLO(weights)
    model.train(
        data=str(data_yaml),
        epochs=args.epochs,
        imgsz=args.imgsz,
        batch=args.batch,
        patience=args.patience,
        device=device,
        workers=workers,
        project=str(project),
        name=args.name,
        exist_ok=True,
        pretrained=True,
        optimizer="AdamW",
        lr0=0.001,
        lrf=0.01,
        cos_lr=True,
        warmup_epochs=5.0,
        weight_decay=0.0005,
        seed=42,
        deterministic=True,
        amp=True,
        cache=True,
        plots=True,
        save=True,
        save_period=20,
        val=True,
        # 样本少、类别不均衡：加强增强， mosaic 后期关闭
        mosaic=1.0,
        mixup=0.15,
        copy_paste=0.3,
        close_mosaic=20,
        hsv_h=0.015,
        hsv_s=0.7,
        hsv_v=0.4,
        degrees=15.0,
        translate=0.15,
        scale=0.5,
        shear=2.0,
        flipud=0.1,
        fliplr=0.5,
        erasing=0.2,
        freeze=10,
    )


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n已中断。")
        sys.exit(130)
    except Exception as e:
        print(f"\n训练出错: {e}", file=sys.stderr)
        import traceback

        traceback.print_exc()
        sys.exit(1)

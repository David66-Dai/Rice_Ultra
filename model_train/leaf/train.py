"""
Train a leaf disease classifier on images under Original Image/ (folder names = classes).

"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import matplotlib.pyplot as plt
import torch
import torch.nn as nn
from torch.utils.data import DataLoader, Dataset, random_split
from torchvision import datasets, models, transforms
from tqdm import tqdm


SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_DATA = SCRIPT_DIR / "Original Image"


class SubsetWithTransform(Dataset):
    def __init__(self, subset, transform):
        self.subset = subset
        self.transform = transform

    def __getitem__(self, idx):
        img, y = self.subset[idx]
        if self.transform is not None:
            img = self.transform(img)
        return img, y

    def __len__(self):
        return len(self.subset)


def build_model(num_classes: int, device: torch.device) -> nn.Module:
    # ========== 🔴 演示点1：ResNet18 迁移学习 ==========
    # 演讲时敲这行 → 展示如何用一行代码加载预训练模型
    # 讲解：ImageNet 预训练权重让模型自带"视觉理解能力"
    try:
        w = models.ResNet18_Weights.IMAGENET1K_V1
        m = models.resnet18(weights=w)
    except Exception as e:
        print(
            "[!] 未能加载 ImageNet 预训练权重（网络问题或缓存损坏），改用随机初始化继续训练：",
            e,
            sep="\n",
        )
        m = models.resnet18(weights=None)
    in_f = m.fc.in_features
    # ========== 🔴 演示点2：修改分类头 ==========
    # 原 ResNet18 输出 1000 类，改成我们的 4 类
    # 敲：nn.Linear(in_f, num_classes) → 只训练 FC 层即可
    m.fc = nn.Linear(in_f, num_classes)
    return m.to(device)


def save_loss_curve(
    train_losses: list[float],
    val_losses: list[float],
    out_path: Path,
) -> None:
    epochs = range(1, len(train_losses) + 1)
    fig, ax = plt.subplots(figsize=(8, 5))
    ax.plot(epochs, train_losses, marker="o", label="train_loss")
    ax.plot(epochs, val_losses, marker="s", label="val_loss")
    ax.set_xlabel("Epoch")
    ax.set_ylabel("Loss")
    ax.set_title("Training / Validation Loss")
    ax.legend()
    ax.grid(True, alpha=0.3)
    fig.tight_layout()
    out_path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out_path, dpi=150)
    plt.close(fig)


def class_weights_from_subset(
    subset, num_classes: int, device: torch.device, show_progress: bool = True
) -> torch.Tensor:
    # ========== 🔴 演示点4：类别加权 ==========
    # 现场敲核心公式：weights = total_samples / (num_classes × class_count)
    # 讲解：小样本类别获得更大权重，防止模型"偏科"
    counts = torch.zeros(num_classes)
    it = range(len(subset))
    if show_progress:
        it = tqdm(it, desc="统计训练集类别分布", unit="img", leave=False)
    for i in it:
        _, y = subset[i]
        counts[y] += 1
    counts = counts.clamp(min=1.0)
    w = counts.sum() / (num_classes * counts)
    return w.to(device)

def main():
    if sys.platform == "win32":
        try:
            sys.stdout.reconfigure(encoding="utf-8")
            sys.stderr.reconfigure(encoding="utf-8")
        except Exception:
            pass

    p = argparse.ArgumentParser()
    p.add_argument("--data", type=Path, default=DEFAULT_DATA, help="Root folder with class subfolders")
    p.add_argument("--epochs", type=int, default=50)
    p.add_argument("--batch-size", type=int, default=16)
    p.add_argument("--lr", type=float, default=1e-4)
    p.add_argument("--val-ratio", type=float, default=0.2)
    p.add_argument("--out", type=Path, default=SCRIPT_DIR / "checkpoints" / "best_model.pt")
    p.add_argument(
        "--loss-curve",
        type=Path,
        default=SCRIPT_DIR / "checkpoints" / "loss_curve.png",
        help="训练结束后保存的 loss 曲线图路径",
    )
    p.add_argument("--no-progress", action="store_true", help="关闭进度条（适合重定向到日志文件）")
    args = p.parse_args()
    show_pbar = not args.no_progress

    data_root = args.data.resolve()
    if not data_root.is_dir():
        raise SystemExit(
            f"找不到数据目录: {data_root}\n"
            f"请确认与 train.py 同目录下存在文件夹: Original Image\n"
            f"或在命令行指定: py -3 train.py --data \"你的路径\\Original Image\""
        )

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    torch.manual_seed(42)

    # ========== 🔴 演示点3：数据增强策略 ==========
    # 演讲时逐行解读每个变换的意义：
    #   RandomResizedCrop → 模拟不同拍摄距离
    #   RandomHorizontalFlip → 模拟左右朝向
    #   ColorJitter → 模拟不同光照条件
    # 效果：训练集"虚拟翻倍"，模型泛化能力大幅提升
    train_tf = transforms.Compose(
        [
            transforms.Resize((256, 256)),
            transforms.RandomResizedCrop(224, scale=(0.8, 1.0)),
            transforms.RandomHorizontalFlip(),
            transforms.ColorJitter(0.15, 0.15, 0.1, 0.05),
            transforms.ToTensor(),
            transforms.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225]),
        ]
    )
    val_tf = transforms.Compose(
        [
            transforms.Resize((224, 224)),
            transforms.ToTensor(),
            transforms.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225]),
        ]
    )

    base = datasets.ImageFolder(str(data_root), transform=None)
    class_names = base.classes
    num_classes = len(class_names)
    if num_classes < 2:
        raise SystemExit("数据根目录下至少需要 2 个类别子文件夹。")

    n = len(base)
    n_val = max(1, int(n * args.val_ratio))
    n_train = n - n_val
    gen = torch.Generator().manual_seed(42)
    train_sub, val_sub = random_split(base, [n_train, n_val], generator=gen)

    print(f"[*] 脚本目录: {SCRIPT_DIR}")
    print(f"[*] 数据目录: {data_root}")
    print(f"[*] 设备: {device}")
    print(f"[*] 类别数: {num_classes} -> {class_names}")
    print(f"[*] 总样本数: {n}，训练集: {n_train}，验证集: {n_val}，每轮 batch 数 训练≈{(n_train + args.batch_size - 1) // args.batch_size} 验证≈{(n_val + args.batch_size - 1) // args.batch_size}")
    print(f"[*] 训练轮数: {args.epochs}，输出: {args.out}")
    print()

    train_ds = SubsetWithTransform(train_sub, train_tf)
    val_ds = SubsetWithTransform(val_sub, val_tf)

    pin = device.type == "cuda"
    train_loader = DataLoader(
        train_ds, batch_size=args.batch_size, shuffle=True, num_workers=0, pin_memory=pin
    )
    val_loader = DataLoader(
        val_ds, batch_size=args.batch_size, shuffle=False, num_workers=0, pin_memory=pin
    )

    model = build_model(num_classes, device)
    crit = nn.CrossEntropyLoss(
        weight=class_weights_from_subset(train_sub, num_classes, device, show_progress=show_pbar)
    )
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    best_acc = 0.0
    history = {"train_loss": [], "val_loss": [], "val_acc": []}
    meta = {
        "class_names": class_names,
        "class_to_idx": base.class_to_idx,
        "healthy_folder_name": "Healthy Leaf",
    }

    # ========== 🔴 演示点5：核心训练循环 ==========
    # 现场敲框架：model.train() → optimizer.zero_grad() → loss.backward() → optimizer.step()
    # 讲解：标准的 PyTorch 三步走，每个 batch 更新一次参数
    for epoch in range(1, args.epochs + 1):
        model.train()
        loss_sum = 0.0
        n_seen = 0
        train_iter = train_loader
        if show_pbar:
            train_iter = tqdm(
                train_loader,
                desc=f"Epoch {epoch}/{args.epochs} 训练",
                unit="batch",
                leave=True,
                ncols=100,
            )
        for x, y in train_iter:
            x, y = x.to(device), y.to(device)
            opt.zero_grad()
            logits = model(x)
            loss = crit(logits, y)
            loss.backward()
            opt.step()
            loss_sum += loss.item() * x.size(0)
            n_seen += x.size(0)
            if show_pbar:
                train_iter.set_postfix(
                    loss=f"{loss.item():.4f}",
                    avg=f"{loss_sum / max(1, n_seen):.4f}",
                )
        train_loss = loss_sum / max(1, n_seen)

        model.eval()
        correct = 0
        total = 0
        val_loss_sum = 0.0
        val_iter = val_loader
        if show_pbar:
            val_iter = tqdm(
                val_loader,
                desc=f"Epoch {epoch}/{args.epochs} 验证",
                unit="batch",
                leave=True,
                ncols=100,
            )
        with torch.no_grad():
            for x, y in val_iter:
                x, y = x.to(device), y.to(device)
                logits = model(x)
                loss = crit(logits, y)
                val_loss_sum += loss.item() * x.size(0)
                pred = logits.argmax(dim=1)
                correct += (pred == y).sum().item()
                total += y.size(0)
                if show_pbar:
                    val_iter.set_postfix(acc=f"{100.0 * correct / max(1, total):.1f}%")
        val_acc = correct / max(1, total)
        val_loss = val_loss_sum / max(1, total)

        history["train_loss"].append(train_loss)
        history["val_loss"].append(val_loss)
        history["val_acc"].append(val_acc)

        improved = ""
        if val_acc >= best_acc:
            best_acc = val_acc
            torch.save(
                {
                    "model_state": model.state_dict(),
                    "num_classes": num_classes,
                    "meta": meta,
                },
                args.out,
            )
            improved = "  [已保存最佳模型]"
        line = (
            f"第 {epoch}/{args.epochs} 轮结束  "
            f"train_loss={train_loss:.4f}  val_loss={val_loss:.4f}  "
            f"val_acc={val_acc:.2%}  最佳 val_acc={best_acc:.2%}{improved}"
        )
        if show_pbar:
            tqdm.write(line)
        else:
            print(line)

    save_loss_curve(history["train_loss"], history["val_loss"], args.loss_curve)
    print(f"Saved loss curve to {args.loss_curve}")

    with open(args.out.with_suffix(".json"), "w", encoding="utf-8") as f:
        json.dump(
            {
                **meta,
                "best_val_acc": best_acc,
                "checkpoint": str(args.out),
                "loss_curve": str(args.loss_curve),
                "history": history,
            },
            f,
            ensure_ascii=False,
            indent=2,
        )
    print(f"Saved best model (val_acc={best_acc:.4f}) to {args.out}")


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

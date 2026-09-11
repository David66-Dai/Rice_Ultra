"""
Load trained checkpoint and run inference on one image (CLI).
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import torch
import torch.nn as nn
from PIL import Image
from torchvision import models, transforms

SCRIPT_DIR = Path(__file__).resolve().parent

DISPLAY_ZH = {
    "Bacterial Leaf Blight": "细菌性叶枯病",
    "Brown Spot": "褐斑病",
    "Healthy Leaf": "健康叶片",
    "Tungro Virus": "东格鲁病毒",
}


def load_model(ckpt_path: Path, device: torch.device) -> tuple[nn.Module, dict]:
    ckpt = torch.load(ckpt_path, map_location=device, weights_only=False)
    meta = ckpt.get("meta") or {}
    class_names = meta.get("class_names")
    if not class_names:
        raise ValueError("Checkpoint missing meta.class_names")
    num_classes = int(ckpt.get("num_classes", len(class_names)))
    w = models.ResNet18_Weights.IMAGENET1K_V1
    m = models.resnet18(weights=None)
    m.fc = nn.Linear(m.fc.in_features, num_classes)
    m.load_state_dict(ckpt["model_state"])
    m.to(device)
    m.eval()
    return m, meta


def infer_one(
    model: nn.Module,
    meta: dict,
    image_path: Path,
    device: torch.device,
) -> dict:

    tfm = transforms.Compose(
        [
            transforms.Resize((224, 224)),
            transforms.ToTensor(),
            transforms.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225]),
        ]
    )
    img = Image.open(image_path).convert("RGB")
    x = tfm(img).unsqueeze(0).to(device)

    with torch.no_grad():
        logits = model(x)
        prob = torch.softmax(logits, dim=1)[0]
    idx = int(prob.argmax().item())
    class_names: list = meta["class_names"]
    name = class_names[idx]
    healthy_key = meta.get("healthy_folder_name", "Healthy Leaf")
    is_damage = name != healthy_key
    top_p = float(prob[idx].item())
    all_scores = {class_names[i]: float(prob[i].item()) for i in range(len(class_names))}
    return {
        "predicted_class": name,
        "predicted_class_zh": DISPLAY_ZH.get(name, name),
        "confidence": top_p,
        "has_leaf_damage": is_damage,
        "all_probabilities": all_scores,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("image", type=Path, help="Path to image file")
    ap.add_argument("--ckpt", type=Path, default=SCRIPT_DIR / "checkpoints" / "best_model.pt")
    ap.add_argument("--json", action="store_true", help="Print JSON only")
    args = ap.parse_args()

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    if not args.ckpt.is_file():
        raise SystemExit(f"Checkpoint not found: {args.ckpt}. Run train.py first.")

    model, meta = load_model(args.ckpt, device)
    out = infer_one(model, meta, args.image.resolve(), device)

    if args.json:
        print(json.dumps(out, ensure_ascii=False, indent=2))
    else:
        print(f"预测类别: {out['predicted_class_zh']} ({out['predicted_class']})")
        print(f"置信度: {out['confidence']:.2%}")
        print(f"是否存在叶害: {'是' if out['has_leaf_damage'] else '否'}")
        print("各类概率:", json.dumps(out["all_probabilities"], ensure_ascii=False))


if __name__ == "__main__":
    main()

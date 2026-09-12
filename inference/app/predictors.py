"""Lazy-load the trained ResNet18 leaf classifier and YOLO pest detector."""
from __future__ import annotations

from io import BytesIO
import os
from pathlib import Path
import threading

from fastapi import HTTPException
from PIL import Image, UnidentifiedImageError

from .labels import PEST_CLASS_NAMES, leaf_display_zh

REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_LEAF_WEIGHTS = REPO_ROOT / "model_train" / "leaf" / "checkpoints" / "best_model.pt"
DEFAULT_PEST_WEIGHTS = REPO_ROOT / "model_train" / "pest" / "runs" / "detect" / "rice_pests_v2" / "weights" / "best.pt"

_leaf_lock = threading.Lock()
_pest_lock = threading.Lock()
_leaf_model = None
_leaf_meta: dict | None = None
_leaf_device = None
_pest_model = None


def leaf_weights_path() -> Path:
    override = os.environ.get("LEAF_MODEL_PATH", "").strip()
    return Path(override).expanduser() if override else DEFAULT_LEAF_WEIGHTS


def pest_weights_path() -> Path:
    override = os.environ.get("PEST_MODEL_PATH", "").strip()
    if override:
        return Path(override).expanduser()
    for name in ("best.pt", "last.pt"):
        candidate = DEFAULT_PEST_WEIGHTS.with_name(name)
        if candidate.is_file():
            return candidate
    return DEFAULT_PEST_WEIGHTS


def _open_rgb(data: bytes) -> Image.Image:
    if not data:
        raise HTTPException(status_code=400, detail="上传文件为空")
    try:
        return Image.open(BytesIO(data)).convert("RGB")
    except UnidentifiedImageError as exc:
        raise HTTPException(status_code=400, detail="无法解析图片，请上传 JPG / PNG / WEBP") from exc
    except OSError as exc:
        raise HTTPException(status_code=400, detail="无法读取图片文件") from exc


def _require_file(path: Path, kind: str) -> Path:
    if not path.is_file():
        raise HTTPException(status_code=503, detail=f"未找到{kind}模型权重，请先完成训练")
    return path


def _load_leaf():
    global _leaf_model, _leaf_meta, _leaf_device
    with _leaf_lock:
        if _leaf_model is not None:
            return _leaf_model, _leaf_meta, _leaf_device
        try:
            import torch
            import torch.nn as nn
            from torchvision import models
        except ImportError as exc:
            raise HTTPException(status_code=503, detail="叶害推理依赖未安装，请安装 torch 与 torchvision") from exc

        path = _require_file(leaf_weights_path(), "叶害")
        device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        ckpt = torch.load(path, map_location=device, weights_only=False)
        meta = ckpt.get("meta") or {}
        class_names = meta.get("class_names")
        if not class_names:
            raise HTTPException(status_code=503, detail="叶害权重缺少类别信息")
        model = models.resnet18(weights=None)
        model.fc = nn.Linear(model.fc.in_features, int(ckpt.get("num_classes", len(class_names))))
        model.load_state_dict(ckpt["model_state"])
        model.to(device)
        model.eval()
        _leaf_model, _leaf_meta, _leaf_device = model, meta, device
        return _leaf_model, _leaf_meta, _leaf_device


def _load_pest():
    global _pest_model
    with _pest_lock:
        if _pest_model is not None:
            return _pest_model
        try:
            from ultralytics import YOLO
        except ImportError as exc:
            raise HTTPException(status_code=503, detail="虫害推理依赖未安装，请安装 ultralytics") from exc
        path = _require_file(pest_weights_path(), "虫害")
        _pest_model = YOLO(str(path))
        return _pest_model


def predict_leaf(data: bytes, filename: str | None) -> dict:
    import torch
    from torchvision import transforms

    model, meta, device = _load_leaf()
    image = _open_rgb(data)
    transform = transforms.Compose([
        transforms.Resize((224, 224)),
        transforms.ToTensor(),
        transforms.Normalize(mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225]),
    ])
    tensor = transform(image).unsqueeze(0).to(device)
    with torch.no_grad():
        probabilities = torch.softmax(model(tensor), dim=1)[0]
    index = int(probabilities.argmax().item())
    class_names = list(meta["class_names"])
    label = class_names[index]
    healthy_key = str(meta.get("healthy_folder_name", "Healthy Leaf"))
    scores = {class_names[i]: float(probabilities[i].item()) for i in range(len(class_names))}
    return {
        "task": "leaf",
        "filename": filename,
        "label": label,
        "label_zh": leaf_display_zh(label),
        "confidence": float(probabilities[index].item()),
        "has_leaf_damage": label != healthy_key,
        "all_probabilities": scores,
    }


def predict_pest(data: bytes, filename: str | None) -> dict:
    import numpy as np

    model = _load_pest()
    image = _open_rgb(data)
    results = model.predict(
        source=np.asarray(image),
        conf=0.25,
        iou=0.45,
        imgsz=640,
        verbose=False,
        save=False,
    )
    result = results[0]
    names = result.names if isinstance(result.names, dict) else {
        i: name for i, name in enumerate(result.names)
    }
    detections: list[dict] = []
    if result.boxes is not None:
        for box in result.boxes:
            class_id = int(box.cls[0])
            x1, y1, x2, y2 = (float(value) for value in box.xyxy[0].tolist())
            label = str(names.get(class_id, PEST_CLASS_NAMES[class_id] if class_id < len(PEST_CLASS_NAMES) else class_id))
            detections.append({
                "class_name": label,
                "confidence": float(box.conf[0]),
                "bbox": [x1, y1, x2, y2],
            })
    return {
        "task": "pest",
        "filename": filename,
        "count": len(detections),
        "detections": detections,
    }

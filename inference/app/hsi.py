"""Hyperspectral leaf-disease inference helpers."""
from __future__ import annotations

import sys
from pathlib import Path

from fastapi import HTTPException

REPO_ROOT = Path(__file__).resolve().parents[2]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from model_train.hyperspectral.predict import predict_cube_bytes, reset_runtime, weights_path


def leaf_hsi_weights_path() -> Path:
    return weights_path()


def predict_leaf_hsi(data: bytes, filename: str | None) -> dict:
    if not data:
        raise HTTPException(status_code=400, detail="上传文件为空")
    try:
        return predict_cube_bytes(data, filename)
    except FileNotFoundError as exc:
        raise HTTPException(status_code=503, detail="未找到高光谱叶害模型权重，请先完成训练") from exc
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc) or "无法解析高光谱立方体") from exc
    except Exception as exc:
        raise HTTPException(status_code=400, detail="无法解析高光谱立方体") from exc


def reset_leaf_hsi_runtime() -> None:
    reset_runtime()

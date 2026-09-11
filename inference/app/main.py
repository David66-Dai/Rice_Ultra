"""数智稻安 — 推理服务骨架（叶害 / 虫害）。

启动（仓库根目录）:
  pip install -r inference/requirements.txt
  npm run inference

或:
  cd inference && uvicorn app.main:app --reload --port 8001
"""
from __future__ import annotations

from fastapi import FastAPI, File, UploadFile
from fastapi.middleware.cors import CORSMiddleware

app = FastAPI(title="Smart Rice Inference", version="0.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "service": "smart-rice-security-inference"}


@app.post("/predict/leaf")
async def predict_leaf(file: UploadFile = File(...)) -> dict:
    """占位：后续接入 model_train/leaf 的 ResNet18 checkpoint。"""
    raw = await file.read()
    return {
        "task": "leaf",
        "filename": file.filename,
        "bytes": len(raw),
        "label": "Healthy Leaf",
        "label_zh": "健康叶片",
        "confidence": 0.0,
        "note": "stub — wire ResNet18 weights next",
    }


@app.post("/predict/pest")
async def predict_pest(file: UploadFile = File(...)) -> dict:
    """占位：后续接入 model_train/pest 的 YOLO weights。"""
    raw = await file.read()
    return {
        "task": "pest",
        "filename": file.filename,
        "bytes": len(raw),
        "detections": [],
        "note": "stub — wire YOLO11 weights next",
    }

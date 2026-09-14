"""数智稻安 — 叶害 ResNet18 / 虫害 YOLO 推理服务。

启动（仓库根目录）:
  pip install -r inference/requirements.txt
  npm run inference
"""
from __future__ import annotations

from fastapi import FastAPI, File, UploadFile
from fastapi.middleware.cors import CORSMiddleware

from .config import load_inference_settings
from .hsi import leaf_hsi_weights_path, predict_leaf_hsi
from .predictors import leaf_weights_path, pest_weights_path, predict_leaf, predict_pest

settings = load_inference_settings()
app = FastAPI(title="Smart Rice Inference", version="0.2.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=list(settings.allowed_origins),
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health")
def health() -> dict:
    return {
        "status": "ok",
        "service": "smart-rice-security-inference",
        "models": {
            "leaf": leaf_weights_path().is_file(),
            "pest": pest_weights_path().is_file(),
            "leaf_hsi": leaf_hsi_weights_path().is_file(),
        },
    }


@app.post("/predict/leaf")
async def leaf(file: UploadFile = File(...)) -> dict:
    raw = await file.read()
    return predict_leaf(raw, file.filename)


@app.post("/predict/pest")
async def pest(file: UploadFile = File(...)) -> dict:
    raw = await file.read()
    return predict_pest(raw, file.filename)


@app.post("/predict/leaf-hsi")
async def leaf_hsi(file: UploadFile = File(...)) -> dict:
    raw = await file.read()
    return predict_leaf_hsi(raw, file.filename)

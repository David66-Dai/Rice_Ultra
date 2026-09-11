"""
水稻五大害虫检测 Gradio 测试页。

类别：稻纵卷叶螟、二化螟、褐飞虱、白背飞虱、灰飞虱

用法（在本目录执行）：
  python gradio_app.py
浏览器打开终端提示的地址（默认 http://127.0.0.1:7860）
"""
from __future__ import annotations

import argparse
import sys
import time
from collections import Counter
from pathlib import Path

import cv2
import gradio as gr
import numpy as np
from ultralytics import YOLO


ROOT = Path(__file__).resolve().parent
WEIGHTS_DIR = ROOT / "runs" / "detect" / "rice_pests_v2" / "weights"
CLASS_NAMES = ("稻纵卷叶螟", "二化螟", "褐飞虱", "白背飞虱", "灰飞虱")
EXAMPLE_PREFIXES = (
    ("稻纵卷叶螟", "IP000"),
    ("二化螟", "IP003"),
    ("褐飞虱", "IP007"),
    ("白背飞虱", "IP008"),
    ("灰飞虱", "IP009"),
)

_model: YOLO | None = None
_weights_path: Path | None = None 


def _setup_stdio() -> None:
    if sys.platform == "win32":
        try:
            sys.stdout.reconfigure(encoding="utf-8")
            sys.stderr.reconfigure(encoding="utf-8")
        except Exception:
            pass


def resolve_weights(explicit: str | None) -> Path:
    if explicit:
        path = Path(explicit)
        if not path.is_file():
            raise FileNotFoundError(f"未找到权重: {path}")
        return path.resolve()
    for name in ("best.pt", "last.pt"):
        path = WEIGHTS_DIR / name
        if path.is_file():
            return path.resolve()
    raise FileNotFoundError(
        f"未找到训练权重，请先完成训练。期望路径: {WEIGHTS_DIR / 'best.pt'}"
    )


def get_model() -> YOLO:
    global _model
    if _model is None:
        if _weights_path is None:
            raise RuntimeError("尚未指定权重路径")
        _model = YOLO(str(_weights_path))
    return _model


def collect_examples() -> list[list[str]]:
    val_dir = ROOT / "val" / "images"
    examples: list[list[str]] = []
    if not val_dir.is_dir():
        return examples
    for _, prefix in EXAMPLE_PREFIXES:
        hits = sorted(val_dir.glob(f"{prefix}*.jpg"))
        if hits:
            examples.append([str(hits[0])])
    return examples


def to_rgb(image: np.ndarray) -> np.ndarray:
    if image.ndim == 2:
        return cv2.cvtColor(image, cv2.COLOR_GRAY2RGB)
    if image.shape[2] == 4:
        return cv2.cvtColor(image, cv2.COLOR_RGBA2RGB)
    return image


def run_detect(
    image: np.ndarray | None,
    conf: float,
    iou: float,
    imgsz: int,
) -> tuple[np.ndarray | None, str]:
    if image is None:
        return None, "请上传一张图片，或点击下方验证集样例。"
    
    image = to_rgb(image)
    model = get_model()
    t0 = time.perf_counter()
    results = model.predict(
        source=image,
        conf=float(conf),
        iou=float(iou),
        imgsz=int(imgsz),
        verbose=False,
        save=False,
    )
    elapsed_ms = (time.perf_counter() - t0) * 1000
    r = results[0]
    names = r.names if isinstance(r.names, dict) else {i: n for i, n in enumerate(r.names)}
    
    plot_bgr = r.plot()
    out_rgb = cv2.cvtColor(plot_bgr, cv2.COLOR_BGR2RGB)

    counts: Counter[str] = Counter()
    lines = ["| 类别 | 置信度 | 中心x | 中心y | 宽 | 高 |", "| --- | --- | --- | --- | --- | --- |"]
    if r.boxes is None or len(r.boxes) == 0:
        count_md = "  \n".join(f"- {name}：**0**" for name in CLASS_NAMES)
        return (
            out_rgb,
            f"未检测到害虫（耗时 {elapsed_ms:.0f} ms）。\n\n{count_md}\n\n"
            + "\n".join(lines)
            + "\n| — | — | — | — | — | — |",
        )

    for b in r.boxes:
        cid = int(b.cls[0])
        cf = float(b.conf[0])
        x1, y1, x2, y2 = (float(v) for v in b.xyxy[0].tolist())
        lab = str(names.get(cid, CLASS_NAMES[cid] if cid < len(CLASS_NAMES) else cid))
        counts[lab] += 1
        lines.append(
            f"| {lab} | {cf:.3f} | {(x1 + x2) / 2:.0f} | {(y1 + y2) / 2:.0f} | "
            f"{x2 - x1:.0f} | {y2 - y1:.0f} |"
        )

    count_md = "  \n".join(f"- {name}：**{counts.get(name, 0)}**" for name in CLASS_NAMES)
    md = (
        f"**检出 {len(r.boxes)} 只**（耗时 {elapsed_ms:.0f} ms）\n\n"
        f"{count_md}\n\n"
        + "\n".join(lines)
    )
    return out_rgb, md

def build_ui() -> gr.Blocks:
    weights_text = str(_weights_path) if _weights_path else "未加载"
    examples = collect_examples()
    with gr.Blocks(title="水稻五大害虫检测") as demo:
        gr.Markdown(
            "## 水稻五大害虫检测\n"
            "识别：**稻纵卷叶螟、二化螟、褐飞虱、白背飞虱、灰飞虱**。\n\n"
            f"当前权重：`{weights_text}`"
        )
        with gr.Row():
            with gr.Column():
                inp = gr.Image(type="numpy", label="上传图像", height=420)
                conf = gr.Slider(0.05, 0.95, value=0.25, step=0.05, label="置信度阈值")
                iou = gr.Slider(0.10, 0.90, value=0.45, step=0.05, label="NMS IoU")
                imgsz = gr.Slider(320, 1280, value=640, step=32, label="推理边长 imgsz")
                btn = gr.Button("检测", variant="primary")
            with gr.Column():
                out_img = gr.Image(type="numpy", label="检测结果")
                out_txt = gr.Markdown()

        btn.click(fn=run_detect, inputs=[inp, conf, iou, imgsz], outputs=[out_img, out_txt])
        inp.change(fn=run_detect, inputs=[inp, conf, iou, imgsz], outputs=[out_img, out_txt])
        if examples:
            gr.Examples(
                examples=examples,
                inputs=[inp],
                label="验证集样例（每类一张）",
            )
    return demo


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="水稻五大害虫 Gradio 测试")
    p.add_argument("--weights", default=None, help="权重路径，默认使用 best.pt")
    p.add_argument("--port", type=int, default=7860)
    p.add_argument("--share", action="store_true")
    return p.parse_args()


if __name__ == "__main__":
    _setup_stdio()
    args = parse_args()
    _weights_path = resolve_weights(args.weights)
    print(f"[*] 加载权重: {_weights_path}")
    get_model()
    build_ui().launch(
        server_name="0.0.0.0",
        server_port=args.port,
        share=args.share,
        inbrowser=True,
    )
    

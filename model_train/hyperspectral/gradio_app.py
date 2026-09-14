"""Gradio 高光谱叶害测试台。

仓库根目录：
  python -m model_train.hyperspectral.gradio_app
"""
from __future__ import annotations

import argparse
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

from .constants import PACKAGE_DIR
from .predict import predict_cube_path, weights_path

TEST_DIR = PACKAGE_DIR / "Test"
SAMPLE_LABELS = {
	"01_Healthy_Leaf.h5": "健康叶片",
	"02_Bacterial_Leaf_Blight.h5": "细菌性叶枯病",
	"03_Brown_Spot.h5": "褐斑病",
	"04_Tungro_Virus.h5": "东格鲁病毒",
}

plt.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei", "Segoe UI", "DejaVu Sans"]
plt.rcParams["axes.unicode_minus"] = False


def _sample_choices() -> list[tuple[str, str]]:
	choices: list[tuple[str, str]] = []
	for name, label in SAMPLE_LABELS.items():
		if (TEST_DIR / name).is_file():
			choices.append((f"{label}（{name}）", name))
	if choices:
		return choices
	return [(path.name, path.name) for path in sorted(TEST_DIR.glob("*.h5"))]


def _choice_to_path(choice: str | None) -> Path | None:
	if not choice:
		return None
	direct = Path(choice)
	if direct.is_file():
		return direct
	path = TEST_DIR / Path(choice).name
	return path if path.is_file() else None


def _format_result(result: dict) -> str:
	alert = "病害" if result.get("has_leaf_damage") else "健康"
	spectrum = result.get("spectrum") or {}
	return (
		f"### {result.get('label_zh', result.get('label'))}\n"
		f"- 英文类别：`{result.get('label')}`\n"
		f"- 置信度：**{float(result.get('confidence', 0.0)):.1%}**\n"
		f"- 严重度：**{float(result.get('severity', 0.0)):.2f} / 3**\n"
		f"- 判定：{alert}（告警 `{result.get('alert_level')}`）\n"
		f"- NDVI：{float(spectrum.get('ndvi') or 0.0):.3f}　NDRE：{float(spectrum.get('ndre') or 0.0):.3f}\n"
		f"- 文件：`{result.get('filename')}`"
	)


def _prob_figure(result: dict):
	scores = result.get("all_probabilities") or {}
	fig, ax = plt.subplots(figsize=(6.2, 3.4))
	names = list(scores.keys())
	values = [float(scores[name]) for name in names]
	zh = {
		"Healthy Leaf": "健康",
		"Bacterial Leaf Blight": "细菌性叶枯",
		"Brown Spot": "褐斑病",
		"Tungro Virus": "东格鲁病毒",
	}
	labels = [zh.get(name, name) for name in names]
	colors = ["#2e7d32" if name == result.get("label") else "#90caf9" for name in names]
	ax.bar(labels, values, color=colors)
	ax.set_ylim(0.0, 1.0)
	ax.set_ylabel("概率")
	ax.set_title("四类概率")
	fig.tight_layout()
	return fig


def _spectrum_figure(result: dict):
	spectrum = result.get("spectrum") or {}
	wavelengths = np.asarray(spectrum.get("wavelengths") or [], dtype=np.float32)
	reflectance = np.asarray(spectrum.get("reflectance") or [], dtype=np.float32)
	fig, ax = plt.subplots(figsize=(6.2, 3.4))
	if wavelengths.size and reflectance.size:
		ax.plot(wavelengths, reflectance, color="#1565c0", linewidth=2.0)
	ax.set_xlabel("波长 (nm)")
	ax.set_ylabel("反射率")
	ax.set_title("ROI 平均光谱")
	ax.set_xlim(400, 1000)
	ax.grid(True, alpha=0.3)
	fig.tight_layout()
	return fig


def _empty():
	return "等待识别。请选择内置样本或上传立方体。", None, None


def _upload_path(upload) -> Path | None:
	if upload is None or upload == "":
		return None
	if isinstance(upload, dict):
		raw = upload.get("path") or upload.get("orig_name")
		return Path(raw) if raw else None
	if hasattr(upload, "name") and not isinstance(upload, (str, Path)):
		return Path(upload.name)
	return Path(str(upload))


def predict_ui(sample_choice: str | None, upload) -> tuple[str, object, object]:
	path = _upload_path(upload)
	if path is None and sample_choice:
		path = _choice_to_path(sample_choice)
	if path is None:
		summary, *_ = _empty()
		return "请先选择内置测试样本，或上传 `.h5` / `.zip` / `.npy` / `.hdr`。", None, None
	try:
		result = predict_cube_path(path)
	except FileNotFoundError:
		return f"未找到模型权重：`{weights_path()}`。请先完成训练。", None, None
	except Exception as exc:
		return f"识别失败：{exc}", None, None
	return _format_result(result), _prob_figure(result), _spectrum_figure(result)


def batch_builtin() -> tuple[list[list[str]], str]:
	rows: list[list[str]] = []
	for name, label in SAMPLE_LABELS.items():
		path = TEST_DIR / name
		if not path.is_file():
			rows.append([label, name, "缺失", "-", "-"])
			continue
		try:
			result = predict_cube_path(path)
			hit = "正确" if result.get("label_zh") == label else "不一致"
			rows.append([
				label,
				result.get("label_zh", ""),
				f"{float(result.get('confidence', 0.0)):.1%}",
				f"{float(result.get('severity', 0.0)):.2f}",
				hit,
			])
		except Exception as exc:
			rows.append([label, name, "失败", str(exc), "-"])
	ok = sum(1 for row in rows if row[-1] == "正确")
	return rows, f"内置 4 类样本：{ok} / {len(rows)} 与标签一致（合成数据，仅用于管线检查）。"


def build_app():
	import gradio as gr

	choices = _sample_choices()
	default = choices[0][1] if choices else None
	with gr.Blocks(title="高光谱叶害测试台") as demo:
		gr.Markdown(
			"# 高光谱水稻叶害测试台\n"
			"加载 `checkpoints/hsi_1dcnn.pt`，对 `Test/` 内置样本或上传立方体做 1D-CNN 识别。"
			"合成数据只能验证流程，不能当作田间实测。"
		)
		with gr.Row():
			with gr.Column(scale=1):
				sample = gr.Dropdown(
					choices=choices,
					value=default,
					label="内置测试样本（Test/）",
				)
				run_sample = gr.Button("识别所选样本", variant="primary")
				upload = gr.File(
					label="或上传立方体（.h5 / .zip / .npy / .npz / .hdr）",
					file_types=[".h5", ".hdf5", ".zip", ".npy", ".npz", ".hdr"],
					type="filepath",
				)
				gr.Markdown("上传 `.hdr` 时，同目录需要有对应的 `.dat`。")
				run_upload = gr.Button("识别上传文件")
				batch = gr.Button("一键测完 4 类内置样本")
			with gr.Column(scale=2):
				summary = gr.Markdown()
				probs = gr.Plot(label="类别概率")
				curve = gr.Plot(label="光谱曲线")
		table = gr.Dataframe(
			headers=["样本标签", "预测", "置信度", "严重度", "对照"],
			label="四类样本对照",
			interactive=False,
		)
		batch_note = gr.Markdown()
		run_sample.click(lambda choice: predict_ui(choice, None), inputs=sample, outputs=[summary, probs, curve], api_name="predict_sample")
		run_upload.click(lambda file: predict_ui(None, file), inputs=upload, outputs=[summary, probs, curve], api_name="predict_upload")
		sample.change(lambda choice: predict_ui(choice, None), inputs=sample, outputs=[summary, probs, curve], api_name="preview_sample")
		batch.click(batch_builtin, outputs=[table, batch_note], api_name="batch_builtin")
		demo.load(lambda: predict_ui(default, None), outputs=[summary, probs, curve], api_name="startup")
	return demo


def main(argv: list[str] | None = None) -> None:
	parser = argparse.ArgumentParser(description="Hyperspectral leaf-disease Gradio tester")
	parser.add_argument("--host", default="127.0.0.1")
	parser.add_argument("--port", type=int, default=7860)
	parser.add_argument("--share", action="store_true")
	args = parser.parse_args(argv)
	# Windows 代理会让 Gradio 自检 localhost 返回 502
	import os
	os.environ.setdefault("NO_PROXY", "127.0.0.1,localhost")
	os.environ.setdefault("no_proxy", "127.0.0.1,localhost")
	demo = build_app()
	demo.launch(
		server_name=args.host,
		server_port=args.port,
		share=args.share,
		ssr_mode=False,
		show_error=True,
	)


if __name__ == "__main__":
	main()

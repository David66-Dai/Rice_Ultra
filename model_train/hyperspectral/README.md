# 高光谱水稻叶害识别

本目录是 **合成 VIS–NIR 立方体 → 预处理 → 训练 → 本地预测** 的独立包。  
现网 RGB 叶害模型仍在 `model_train/leaf`（ResNet18），**不会被这里替换**。

合成数据按水稻叶片光谱形状生成（叶绿素吸收谷、红边、近红外高原），再叠品种 / 生育期 / 病斑。测试集分数会偏高，**不能替代田间成像光谱仪实测**。现场部署需要用真实立方体重训。

所有命令都在 **仓库根目录** `D:\Rice_Ultra-main` 执行。

## 环境

Python ≥ 3.10。建议在已有推理环境里加装依赖：

```powershell
pip install -r model_train/hyperspectral/requirements.txt
```

需要：`numpy`、`h5py`、`scipy`、`scikit-learn`、`joblib`、`torch`、`matplotlib`。

## 一键跑通（推荐）

已有 `data/` 立方体和 `checkpoints/hsi_1dcnn.pt` 时，不必再生成或训练。从零开始：

```powershell
python -m model_train.hyperspectral.run --all
```

等价于：生成 240 张 `64×64×64` 立方体（400–1000 nm）→ 提取 ROI 光谱 → 训练 RandomForest + 1D-CNN。

不传参数时同样会「生成 + 训练」：

```powershell
python -m model_train.hyperspectral
python -m model_train.hyperspectral.run
```

常用参数：

| 参数 | 默认 | 含义 |
| --- | --- | --- |
| `--generate` | 关 | 只生成立方体 |
| `--train` | 关 | 只训练（需已有 `data/h5/*.h5`） |
| `--all` | 关 | 生成 + 训练 |
| `--3dcnn` | 关 | 额外训一个很小的 3D-CNN（无 GPU 会跳过） |
| `--count` | 240 | 立方体数量 |
| `--height` / `--width` / `--bands` | 64 | 空间尺寸与波段数 |
| `--epochs` | 40 | 1D-CNN 轮数 |
| `--out` | `model_train/hyperspectral/data` | 数据目录 |
| `--checkpoints` | `model_train/hyperspectral/checkpoints` | 权重目录 |

示例：已有数据只重训、少轮数：

```powershell
python -m model_train.hyperspectral.run --train --epochs 20
```

只生成 32 张小立方体做试跑：

```powershell
python -m model_train.hyperspectral.run --generate --count 32 --height 32 --width 32 --bands 32
```

## 分步命令

### 1. 生成合成数据

```powershell
python -m model_train.hyperspectral.generate --count 240 --seed 2026
```

写出：

```
data/
  h5/cube_0000.h5 …          # 训练与预测主格式
  envi/cube_0000.hdr+.dat    # ENVI BSQ float32，便于用光谱软件打开
  manifest.csv               # 类别、品种、生育期、严重度、ROI
```

四类叶害（与 RGB 叶害命名对齐）：

- `Healthy Leaf` 健康叶片
- `Bacterial Leaf Blight` 细菌性叶枯病
- `Brown Spot` 褐斑病
- `Tungro Virus` 东格鲁病毒

品种 `indica` / `japonica` / `hybrid`，生育期 `tillering` / `jointing` / `heading`，严重度 0–3。

`data/` 已写入本目录 `.gitignore`，默认不进版本库。

### 2. 训练

```powershell
python -m model_train.hyperspectral.train
```

流程：读 `data/h5/*.h5` → 暗电流/白板校正、Savitzky–Golay 平滑、NDVI 去背景 → ROI 平均光谱 → **SNV** 后送 1D-CNN；RandomForest 用 SNV 光谱 + NDVI/NDRE/PSRI/SIPI + 纹理。按立方体 ID 划分，避免同一立方体进训练又进测试。

产出：

| 文件 | 说明 |
| --- | --- |
| `checkpoints/hsi_1dcnn.pt` | 线上用的 1D-CNN（类别 + 严重度头） |
| `checkpoints/hsi_rf.joblib` | PCA + RandomForest 基线 |
| `checkpoints/label_map.json` | 类别名与波长 |
| `checkpoints/metrics.json` | 测试集 accuracy / macro-F1 / 严重度 MAE |
| `checkpoints/confusion_cnn.png` | 1D-CNN 混淆矩阵 |

当前合成数据上的参考分数（240 立方体）：RF accuracy 1.00；1D-CNN accuracy ≈ 0.95，严重度 MAE ≈ 0.18。这是合成数据，不代表田间表现。

可选 3D-CNN（无 NVIDIA GPU 会打印 `no GPU; skip 3D-CNN` 后退出）：

```powershell
python -m model_train.hyperspectral.run --3dcnn
python -m model_train.hyperspectral.train_3dcnn --data model_train/hyperspectral/data
```

权重：`checkpoints/hsi_3dcnn.pt`。推理默认**不用**这个文件。

### 3. 对单个立方体做预测

没有单独 CLI。在仓库根目录开 Python：

```python
from pathlib import Path
from model_train.hyperspectral.predict import predict_cube_bytes

path = Path("model_train/hyperspectral/data/h5/cube_0000.h5")
result = predict_cube_bytes(path.read_bytes(), path.name)
print(result["label"], result["label_zh"], result["confidence"], result["severity"])
print(result["spectrum"]["ndvi"], result["spectrum"]["ndre"])
```

返回字段：

- `label` / `label_zh` / `confidence`：类别与置信度
- `has_leaf_damage`：是否非健康
- `severity`：0–3
- `all_probabilities`：四类概率
- `spectrum.wavelengths` / `reflectance`：ROI 平均**原始反射率**（给曲线展示用）
- `spectrum.ndvi` / `ndre`

模型输入是 SNV 光谱；返回给界面的曲线是校正后、SNV 前的反射率。

换权重路径：

```powershell
$env:LEAF_HSI_MODEL_PATH = "D:\Rice_Ultra-main\model_train\hyperspectral\checkpoints\hsi_1dcnn.pt"
```

### 4. Gradio 测试台

```powershell
pip install -r model_train/hyperspectral/requirements.txt
python -m model_train.hyperspectral.gradio_app
```

浏览器打开 http://127.0.0.1:7860 。可选用 `Test/` 里四类内置立方体，或上传 `.h5` / `.zip` / `.npy` / `.hdr`。`--port` 改端口，`--share` 生成临时公网链接。

支持的立方体格式：

| 格式 | 说明 |
| --- | --- |
| `.h5` / `.hdf5` | 数据集 `reflectance`（高×宽×波段）、`wavelengths` |
| `.hdr` + `.dat` | ENVI，本生成器写 BSQ |
| `.zip` | 内含上述 hdr/dat 或 h5 |
| `.npy` | `float32` 立方体，波长按 400–1000 nm 均分 |
| `.npz` | 键 `reflectance`、`wavelengths` |

## 模块对照

| 文件 | 作用 |
| --- | --- |
| `run.py` | 总入口 |
| `generate.py` | 合成立方体 |
| `io.py` | 读写 H5 / ENVI / npy |
| `preprocess.py` | 反射率校正、平滑、SNV、NDVI 掩膜 |
| `features.py` | 植被指数与纹理 |
| `dataset.py` | 抽光谱、按立方体划分 |
| `models.py` | `Spectral1DCNN` |
| `train.py` | RF + 1D-CNN |
| `train_3dcnn.py` | 可选 3D-CNN |
| `predict.py` | 加载 `hsi_1dcnn.pt` 并分类 |
| `gradio_app.py` | 本地 Gradio 测试台 |
| `constants.py` | 类别、波长、默认尺寸 |

## 注意

- 必须在仓库根目录执行 `python -m model_train.hyperspectral...`，这样包名才是 `model_train.hyperspectral`（Windows 上目录名大小写要与 import 一致）。
- 重新 `--generate` 会覆盖 `data/h5` 与 `data/envi`。
- 本包目前只提供本地 Python 预测；RGB 叶害 / 虫害仍走 `inference` 的 `/predict/leaf`、`/predict/pest`。

"""Load and save HWC reflectance cubes as HDF5 or ENVI."""
from __future__ import annotations

from io import BytesIO
from pathlib import Path
import zipfile

import numpy as np

try:
	import h5py
except ImportError:  # pragma: no cover
	h5py = None


def write_h5(
	path: Path,
	cube: np.ndarray,
	wavelengths: np.ndarray,
	meta: dict,
	leaf_mask: np.ndarray | None = None,
	lesion_mask: np.ndarray | None = None,
) -> None:
	if h5py is None:
		raise RuntimeError("需要安装 h5py 才能写入 HDF5")
	path.parent.mkdir(parents=True, exist_ok=True)
	with h5py.File(path, "w") as handle:
		handle.create_dataset("reflectance", data=np.asarray(cube, dtype=np.float32), compression="gzip")
		handle.create_dataset("wavelengths", data=np.asarray(wavelengths, dtype=np.float32))
		if leaf_mask is not None:
			handle.create_dataset("leaf_mask", data=np.asarray(leaf_mask, dtype=np.uint8))
		if lesion_mask is not None:
			handle.create_dataset("lesion_mask", data=np.asarray(lesion_mask, dtype=np.uint8))
		for key, value in meta.items():
			handle.attrs[key] = value


def write_envi(stem: Path, cube: np.ndarray, wavelengths: np.ndarray) -> None:
	"""Write BSQ float32 ENVI pair (.hdr + .dat)."""
	stem.parent.mkdir(parents=True, exist_ok=True)
	height, width, bands = cube.shape
	dat = stem.with_suffix(".dat")
	hdr = stem.with_suffix(".hdr")
	np.ascontiguousarray(np.transpose(cube.astype(np.float32), (2, 0, 1))).tofile(dat)
	wl = ", ".join(f"{float(value):.3f}" for value in wavelengths)
	hdr.write_text(
		"ENVI\n"
		"description = {smart-rice synthetic rice leaf}\n"
		f"samples = {width}\n"
		f"lines = {height}\n"
		f"bands = {bands}\n"
		"header offset = 0\n"
		"file type = ENVI Standard\n"
		"data type = 4\n"
		"interleave = bsq\n"
		"byte order = 0\n"
		f"wavelength = {{{wl}}}\n"
		"wavelength units = Nanometers\n",
		encoding="ascii",
	)


def read_h5(path: Path) -> tuple[np.ndarray, np.ndarray, dict]:
	if h5py is None:
		raise RuntimeError("需要安装 h5py 才能读取 HDF5")
	with h5py.File(path, "r") as handle:
		cube = np.asarray(handle["reflectance"], dtype=np.float32)
		wavelengths = np.asarray(handle["wavelengths"], dtype=np.float32)
		meta = {key: _attr(value) for key, value in handle.attrs.items()}
		if "leaf_mask" in handle:
			meta["leaf_mask"] = np.asarray(handle["leaf_mask"], dtype=bool)
		if "lesion_mask" in handle:
			meta["lesion_mask"] = np.asarray(handle["lesion_mask"], dtype=bool)
	return cube, wavelengths, meta


def read_envi(hdr_path: Path) -> tuple[np.ndarray, np.ndarray, dict]:
	header = _parse_hdr(hdr_path.read_text(encoding="ascii", errors="ignore"))
	width = int(header["samples"])
	height = int(header["lines"])
	bands = int(header["bands"])
	interleave = str(header.get("interleave", "bsq")).lower()
	dat = hdr_path.with_suffix(".dat")
	if not dat.is_file():
		dat = hdr_path.with_suffix(".img")
	raw = np.fromfile(dat, dtype=np.float32)
	expected = height * width * bands
	if raw.size < expected:
		raise ValueError("ENVI 数据长度不足")
	raw = raw[:expected]
	if interleave == "bsq":
		cube = raw.reshape(bands, height, width).transpose(1, 2, 0)
	elif interleave == "bil":
		cube = raw.reshape(height, bands, width).transpose(0, 2, 1)
	else:
		cube = raw.reshape(height, width, bands)
	wavelengths = np.asarray(header.get("wavelength", np.arange(bands)), dtype=np.float32)
	if wavelengths.size != bands:
		wavelengths = np.linspace(400.0, 1000.0, bands, dtype=np.float32)
	return cube.astype(np.float32), wavelengths, {"source": str(hdr_path)}


def load_cube(path: Path) -> tuple[np.ndarray, np.ndarray, dict]:
	suffix = path.suffix.lower()
	if suffix in {".h5", ".hdf5"}:
		return read_h5(path)
	if suffix == ".npy":
		cube = np.load(path).astype(np.float32)
		bands = cube.shape[-1]
		return cube, np.linspace(400.0, 1000.0, bands, dtype=np.float32), {}
	if suffix == ".npz":
		payload = np.load(path)
		cube = np.asarray(payload["reflectance"], dtype=np.float32)
		wavelengths = np.asarray(payload["wavelengths"], dtype=np.float32)
		return cube, wavelengths, {}
	if suffix == ".hdr":
		return read_envi(path)
	if suffix == ".zip":
		return load_cube_from_bytes(path.read_bytes(), path.name)
	raise ValueError(f"不支持的立方体格式: {path.suffix}")


def load_cube_from_bytes(data: bytes, filename: str | None = None) -> tuple[np.ndarray, np.ndarray, dict]:
	name = (filename or "cube.h5").lower()
	if name.endswith(".zip"):
		return _load_zip(data)
	if name.endswith((".npy",)):
		cube = np.load(BytesIO(data)).astype(np.float32)
		bands = cube.shape[-1]
		return cube, np.linspace(400.0, 1000.0, bands, dtype=np.float32), {}
	if name.endswith(".npz"):
		payload = np.load(BytesIO(data))
		cube = np.asarray(payload["reflectance"], dtype=np.float32)
		wavelengths = np.asarray(payload["wavelengths"], dtype=np.float32)
		return cube, wavelengths, {}
	if h5py is None:
		raise RuntimeError("需要安装 h5py 才能读取 HDF5")
	with h5py.File(BytesIO(data), "r") as handle:
		cube = np.asarray(handle["reflectance"], dtype=np.float32)
		wavelengths = np.asarray(handle["wavelengths"], dtype=np.float32)
		meta = {key: _attr(value) for key, value in handle.attrs.items()}
	return cube, wavelengths, meta


def _load_zip(data: bytes) -> tuple[np.ndarray, np.ndarray, dict]:
	with zipfile.ZipFile(BytesIO(data)) as archive:
		names = archive.namelist()
		hdr_name = next((item for item in names if item.lower().endswith(".hdr")), None)
		if hdr_name is None:
			h5_name = next((item for item in names if item.lower().endswith((".h5", ".hdf5"))), None)
			if h5_name is None:
				raise ValueError("压缩包中未找到 ENVI hdr 或 HDF5")
			return load_cube_from_bytes(archive.read(h5_name), h5_name)
		from tempfile import TemporaryDirectory
		with TemporaryDirectory() as tmp:
			root = Path(tmp)
			for item in names:
				target = root / Path(item).name
				target.write_bytes(archive.read(item))
			return read_envi(root / Path(hdr_name).name)


def _parse_hdr(text: str) -> dict:
	header: dict = {}
	key = None
	buffer = ""
	in_brace = False
	for raw in text.splitlines():
		line = raw.strip()
		if not line or line.upper() == "ENVI":
			continue
		if in_brace:
			buffer += " " + line
			if "}" in line:
				header[key] = _hdr_value(buffer)
				in_brace = False
				key = None
				buffer = ""
			continue
		if "=" not in line:
			continue
		name, value = line.split("=", 1)
		name = name.strip().lower()
		value = value.strip()
		if value.startswith("{") and "}" not in value:
			in_brace = True
			key = name
			buffer = value
			continue
		header[name] = _hdr_value(value)
	if "wavelength" in header and isinstance(header["wavelength"], str):
		header["wavelength"] = np.array(
			[float(item) for item in header["wavelength"].split(",") if item.strip()],
			dtype=np.float32,
		)
	return header


def _hdr_value(value: str):
	text = value.strip()
	if text.startswith("{") and text.endswith("}"):
		text = text[1:-1].strip()
		if any(ch.isalpha() for ch in text) and "," not in text:
			return text
		try:
			return np.array([float(item) for item in text.split(",") if item.strip()], dtype=np.float32)
		except ValueError:
			return text
	return text


def _attr(value):
	if isinstance(value, bytes):
		return value.decode("utf-8")
	if hasattr(value, "item"):
		try:
			return value.item()
		except (ValueError, AttributeError):
			return value
	return value

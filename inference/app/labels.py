"""Leaf / pest class names shared by the inference service (no torch import)."""
from __future__ import annotations

LEAF_DISPLAY_ZH = {
    "Bacterial Leaf Blight": "细菌性叶枯病",
    "Brown Spot": "褐斑病",
    "Healthy Leaf": "健康叶片",
    "Tungro Virus": "东格鲁病毒",
}

LEAF_DISEASE_NAMES = frozenset({
    "bacterial leaf blight",
    "brown spot",
    "tungro virus",
    "细菌性叶枯病",
    "褐斑病",
    "东格鲁病毒",
})

LEAF_HEALTHY_NAMES = frozenset({
    "healthy leaf",
    "健康叶片",
})

PEST_CLASS_NAMES = ("稻纵卷叶螟", "二化螟", "褐飞虱", "白背飞虱", "灰飞虱")


def _norm(value: str | None) -> str:
    return (value or "").strip().lower()


def leaf_display_zh(label: str) -> str:
    return LEAF_DISPLAY_ZH.get(label, label)


def is_healthy_leaf(*labels: str | None) -> bool:
    return any(_norm(label) in LEAF_HEALTHY_NAMES for label in labels if label)


def is_leaf_disease(*labels: str | None) -> bool:
    return any(_norm(label) in LEAF_DISEASE_NAMES for label in labels if label)

"""Offline leaf-label contract checks: python -m unittest inference.tests.test_labels."""
from __future__ import annotations

from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.labels import is_healthy_leaf, is_leaf_disease, leaf_display_zh


class LeafLabelTests(unittest.TestCase):
    def test_chinese_display_names(self):
        self.assertEqual(leaf_display_zh("Bacterial Leaf Blight"), "细菌性叶枯病")
        self.assertEqual(leaf_display_zh("Brown Spot"), "褐斑病")
        self.assertEqual(leaf_display_zh("Tungro Virus"), "东格鲁病毒")
        self.assertEqual(leaf_display_zh("Healthy Leaf"), "健康叶片")

    def test_diseases_are_detected_in_either_language(self):
        for name in ("Bacterial Leaf Blight", "Brown Spot", "Tungro Virus", "细菌性叶枯病", "褐斑病", "东格鲁病毒"):
            self.assertTrue(is_leaf_disease(name), name)
            self.assertFalse(is_healthy_leaf(name), name)

    def test_healthy_leaf_is_not_a_disease(self):
        self.assertTrue(is_healthy_leaf("Healthy Leaf"))
        self.assertTrue(is_healthy_leaf("健康叶片"))
        self.assertFalse(is_leaf_disease("Healthy Leaf", "健康叶片"))

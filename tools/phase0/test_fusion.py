import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import unittest
import numpy as np

from eval_fusion import calc_f1, compute_fusion_preds, sweep_weighted_f1, sigmoid
from jev_collections import COLLECTION_STATEMENTS, QUESTIONS


class TestFusion(unittest.TestCase):
    def test_calc_f1(self):
        # Perfect match
        self.assertAlmostEqual(calc_f1([True, False, True], [True, False, True]), 1.0)
        # All False
        self.assertAlmostEqual(calc_f1([False, False], [False, False]), 0.0)
        # Partial
        # TP = 1, FP = 1, FN = 1 -> 2*1 / (2*1 + 1 + 1) = 2/4 = 0.5
        self.assertAlmostEqual(calc_f1([True, True, False], [True, False, True]), 0.5)

    def test_sigmoid(self):
        self.assertAlmostEqual(float(sigmoid(0.0)), 0.5)
        self.assertGreater(float(sigmoid(2.0)), 0.5)
        self.assertLess(float(sigmoid(-2.0)), 0.5)

    def test_fusion_preds_image_and_jev(self):
        p_img = np.array([0.6, 0.4, 0.8, 0.2])
        p_jev = [0.2, 0.7, None, 0.1]

        # image alone (thr 0.5)
        preds_img = compute_fusion_preds("image", p_img, p_jev)
        self.assertEqual(preds_img, [True, False, True, False])

        # jev alone (thr 0.5, None -> False)
        preds_jev = compute_fusion_preds("jev", p_img, p_jev)
        self.assertEqual(preds_jev, [False, True, False, False])

        # mean ((pi + pj)/2, None -> pi)
        # 1: (0.6+0.2)/2 = 0.4 -> False
        # 2: (0.4+0.7)/2 = 0.55 -> True
        # 3: 0.8 (pj is None) -> True
        # 4: (0.2+0.1)/2 = 0.15 -> False
        preds_mean = compute_fusion_preds("mean", p_img, p_jev)
        self.assertEqual(preds_mean, [False, True, True, False])

        # or (max(pi, pj), None -> pi)
        # 1: max(0.6, 0.2) = 0.6 -> True
        # 2: max(0.4, 0.7) = 0.7 -> True
        # 3: max(0.8, 0.0) = 0.8 -> True
        # 4: max(0.2, 0.1) = 0.2 -> False
        preds_or = compute_fusion_preds("or", p_img, p_jev)
        self.assertEqual(preds_or, [True, True, True, False])

    def test_fusion_band(self):
        # band: keep image unless 0.3 <= p_img < 0.6, then use mean
        # case 1: p_img = 0.2 (< 0.3) -> keep image (False) even if p_jev = 0.9
        self.assertEqual(compute_fusion_preds("band", np.array([0.2]), [0.9]), [False])
        # case 2: p_img = 0.7 (>= 0.6) -> keep image (True) even if p_jev = 0.1
        self.assertEqual(compute_fusion_preds("band", np.array([0.7]), [0.1]), [True])
        # case 3: p_img = 0.45 (in [0.3, 0.6)), p_jev = 0.8 -> mean = 0.625 -> True
        self.assertEqual(compute_fusion_preds("band", np.array([0.45]), [0.8]), [True])
        # case 4: p_img = 0.45, p_jev = 0.1 -> mean = 0.275 -> False
        self.assertEqual(compute_fusion_preds("band", np.array([0.45]), [0.1]), [False])

    def test_sweep_weighted_f1(self):
        p_img = np.array([0.9, 0.1])
        p_jev = [0.1, 0.9]
        truth = [True, False]
        # w=1.0 will predict [True, False] matching truth perfectly
        best_f1, best_w = sweep_weighted_f1(p_img, p_jev, truth)
        self.assertAlmostEqual(best_f1, 1.0)
        self.assertGreaterEqual(best_w, 0.6)

    def test_collection_questions(self):
        expected_cats = {"chats", "study", "tweets", "memes", "animals"}
        self.assertEqual(set(COLLECTION_STATEMENTS.keys()), expected_cats)
        self.assertEqual(set(QUESTIONS.keys()), expected_cats)
        for cat in expected_cats:
            self.assertEqual(QUESTIONS[cat]["type"], "noul")
            self.assertTrue(len(QUESTIONS[cat]["instructions"]) > 10)


if __name__ == "__main__":
    unittest.main()

#!/usr/bin/env python3
"""Unit tests for Phase 0 evaluation harness (tools/phase0/evaluate.py).

All tests use fast synthetic data only.
"""

from __future__ import annotations

import base64
import gzip
import json
import struct
import tempfile
import unittest
from pathlib import Path

import numpy as np

try:
    from common import EMBEDDING_DIM
    from evaluate import (
        assign_competitive,
        assign_threshold,
        decode_embedding,
        find_oracle_threshold,
        knn_scores,
        knn_scores_category,
        load_backup,
        load_npz_embeddings,
        predict_logreg,
        prepare_dataset,
        prf,
        refine_query,
        run_protocol,
        train_logreg,
        validate_dimensions,
        z_scores,
    )
except ImportError:
    from tools.phase0.common import EMBEDDING_DIM
    from tools.phase0.evaluate import (
        assign_competitive,
        assign_threshold,
        decode_embedding,
        find_oracle_threshold,
        knn_scores,
        knn_scores_category,
        load_backup,
        load_npz_embeddings,
        predict_logreg,
        prepare_dataset,
        prf,
        refine_query,
        run_protocol,
        train_logreg,
        validate_dimensions,
        z_scores,
    )


class TestRefineQuery(unittest.TestCase):
    def test_empty_feedback_returns_base(self):
        base = np.array([1.0, 0.0, 0.0], dtype=np.float32)
        res1 = refine_query(base, [], [])
        np.testing.assert_array_equal(res1, base)

        res2 = refine_query(base, None, None)
        np.testing.assert_array_equal(res2, base)

    def test_matches_hand_computed_example(self):
        # 2D example: base = [1.0, 0.0]
        # pos = [[0.0, 1.0]], neg = [[0.0, -1.0]]
        # alpha=0.7, beta=0.4, maxFeedback=10
        # wP = 1/10 = 0.1, wN = 1/10 = 0.1
        # q = [1.0, 0.7 * 0.1 * 1.0 - 0.4 * 0.1 * (-1.0)] = [1.0, 0.07 + 0.04] = [1.0, 0.11]
        # norm = sqrt(1.0^2 + 0.11^2) = sqrt(1.0121)
        base = np.array([1.0, 0.0], dtype=np.float32)
        pos = [np.array([0.0, 1.0], dtype=np.float32)]
        neg = [np.array([0.0, -1.0], dtype=np.float32)]
        expected = np.array([1.0, 0.11], dtype=np.float64) / np.sqrt(1.0121)

        res = refine_query(base, pos, neg, alpha=0.7, beta=0.4, maxFeedback=10)
        np.testing.assert_allclose(res, expected, rtol=1e-5, atol=1e-5)

    def test_weight_cap_at_max_feedback(self):
        base = np.array([1.0, 0.0], dtype=np.float32)
        pos_unit = np.array([0.0, 1.0], dtype=np.float32)
        # 10 items (cap reached: wP = 10/10 = 1.0)
        pos_10 = [pos_unit.copy() for _ in range(10)]
        # 20 items (cap maintained: wP = min(20, 10)/10 = 1.0)
        pos_20 = [pos_unit.copy() for _ in range(20)]

        res_10 = refine_query(base, pos_10, [], maxFeedback=10)
        res_20 = refine_query(base, pos_20, [], maxFeedback=10)
        np.testing.assert_allclose(res_10, res_20, rtol=1e-6)

        # 5 items should have wP = 5/10 = 0.5, thus different output
        pos_5 = [pos_unit.copy() for _ in range(5)]
        res_5 = refine_query(base, pos_5, [], maxFeedback=10)
        self.assertFalse(np.allclose(res_5, res_10))

    def test_output_normalized(self):
        rng = np.random.default_rng(42)
        base = rng.normal(size=128).astype(np.float32)
        base /= np.linalg.norm(base)
        pos = [rng.normal(size=128).astype(np.float32) for _ in range(6)]
        neg = [rng.normal(size=128).astype(np.float32) for _ in range(4)]

        res = refine_query(base, pos, neg)
        norm = np.linalg.norm(res)
        self.assertAlmostEqual(float(norm), 1.0, places=5)


class TestZScores(unittest.TestCase):
    def test_tiny_hand_example(self):
        # image = [1.0, 0.0]
        # bg = [[0.0, 1.0], [1.0, 0.0], [0.0, -1.0]]
        # dots = [0.0, 1.0, 0.0]
        # mean = 1/3, pop_var = 2/9, pop_std = sqrt(2)/3
        # query = [1.0, 0.0], query dot = 1.0
        # z = (1 - 1/3) / (sqrt(2)/3) = (2/3) / (sqrt(2)/3) = sqrt(2)
        image = np.array([1.0, 0.0], dtype=np.float32)
        bg = np.array([[0.0, 1.0], [1.0, 0.0], [0.0, -1.0]], dtype=np.float32)
        query = np.array([1.0, 0.0], dtype=np.float32)

        z = z_scores(image, query, bg)
        self.assertAlmostEqual(float(z), float(np.sqrt(2.0)), places=5)

    def test_std_under_threshold_returns_zero(self):
        image = np.array([1.0, 0.0], dtype=np.float32)
        # Identical background vectors -> dot products all equal -> std = 0.0 < 1e-6
        bg = np.array([[0.5, 0.5], [0.5, 0.5], [0.5, 0.5]], dtype=np.float32)
        query = np.array([1.0, 0.0], dtype=np.float32)

        z = z_scores(image, query, bg)
        self.assertEqual(float(z), 0.0)


class TestAssignCompetitive(unittest.TestCase):
    def test_clear_winner_assigned_alone(self):
        # Category 0 is clear winner: score 1.8 >= 1.5, margin (1.8 - 1.0) = 0.8 >= 0.25
        S = np.array([[1.8, 1.0, 0.5]], dtype=np.float32)
        Y = assign_competitive(S, threshold=1.5, margin=0.25)
        np.testing.assert_array_equal(Y, [[True, False, False]])

    def test_near_tie_under_margin_nothing(self):
        # Margin (1.8 - 1.7) = 0.1 < 0.25 -> neither assigned
        S = np.array([[1.8, 1.7, 0.5]], dtype=np.float32)
        Y = assign_competitive(S, threshold=1.5, margin=0.25)
        np.testing.assert_array_equal(Y, [[False, False, False]])

    def test_below_threshold_nothing(self):
        # Best score 1.2 < 1.5 threshold -> nothing assigned
        S = np.array([[1.2, 0.5, 0.1]], dtype=np.float32)
        Y = assign_competitive(S, threshold=1.5, margin=0.25)
        np.testing.assert_array_equal(Y, [[False, False, False]])


class TestKnnAndLogisticRegression(unittest.TestCase):
    def test_separate_two_gaussian_blobs_in_128d(self):
        rng = np.random.default_rng(2026)
        # Center 1 and Center 2 well separated in 128 dimensions
        c1 = np.ones(128, dtype=np.float32) / np.sqrt(128.0)
        c2 = -np.ones(128, dtype=np.float32) / np.sqrt(128.0)

        # Train blobs
        P_train = rng.normal(loc=c1, scale=0.1, size=(25, 128)).astype(np.float32)
        N_train = rng.normal(loc=c2, scale=0.1, size=(50, 128)).astype(np.float32)
        P_train /= np.linalg.norm(P_train, axis=1, keepdims=True)
        N_train /= np.linalg.norm(N_train, axis=1, keepdims=True)

        # Test blobs
        P_test = rng.normal(loc=c1, scale=0.1, size=(50, 128)).astype(np.float32)
        N_test = rng.normal(loc=c2, scale=0.1, size=(50, 128)).astype(np.float32)
        P_test /= np.linalg.norm(P_test, axis=1, keepdims=True)
        N_test /= np.linalg.norm(N_test, axis=1, keepdims=True)

        X_test = np.vstack([P_test, N_test])
        y_test = np.array([1] * 50 + [0] * 50)

        # 1. Test KNN scores separation
        s_knn = knn_scores_category(X_test, P_train, N_train, k_neighbors=3)
        pred_knn = (s_knn > 0).astype(int)
        acc_knn = np.mean(pred_knn == y_test)
        self.assertGreater(acc_knn, 0.95)

        # 2. Test Logistic Regression separation
        X_train = np.vstack([P_train, N_train])
        y_train = np.array([1.0] * 25 + [0.0] * 50)
        theta = train_logreg(X_train, y_train, lam=1.0)
        probs = predict_logreg(X_test, theta)
        pred_lr = (probs >= 0.5).astype(int)
        acc_lr = np.mean(pred_lr == y_test)
        self.assertGreater(acc_lr, 0.95)


class TestPrecisionRecallF1(unittest.TestCase):
    def test_hand_example_including_category_with_no_positives(self):
        # 3 samples, 2 categories
        # Category 0: true = [1, 1, 0], pred = [1, 0, 0] -> TP=1, FP=0, FN=1 -> P=1.0, R=0.5, F1=2/3
        # Category 1: true = [0, 0, 0] (no positives!), pred = [0, 1, 0] -> TP=0, FP=1, FN=0 -> P=0, R=0, F1=0
        Y_true = np.array([
            [True, False],
            [True, False],
            [False, False],
        ])
        Y_pred = np.array([
            [True, False],
            [False, True],
            [False, False],
        ])

        metrics = prf(Y_true, Y_pred)
        np.testing.assert_allclose(metrics["precision"], [1.0, 0.0])
        np.testing.assert_allclose(metrics["recall"], [0.5, 0.0])
        np.testing.assert_allclose(metrics["f1"], [2.0 / 3.0, 0.0])
        self.assertAlmostEqual(metrics["macro_f1"], (2.0 / 3.0 + 0.0) / 2.0)


class TestDecodeEmbedding(unittest.TestCase):
    def test_round_trip_struct_pack(self):
        rng = np.random.default_rng(77)
        floats = rng.uniform(-1.0, 1.0, size=128).astype(np.float32).tolist()
        raw_bytes = struct.pack("<128f", *floats)
        b64 = base64.b64encode(raw_bytes).decode("ascii")

        decoded = decode_embedding(b64)
        self.assertIsNotNone(decoded)
        self.assertEqual(decoded.shape, (128,))
        np.testing.assert_allclose(decoded, floats, rtol=1e-6)

    def test_invalid_base64_and_lengths(self):
        self.assertIsNone(decode_embedding(None))
        self.assertIsNone(decode_embedding(""))
        self.assertIsNone(decode_embedding("not_valid_base64_string!"))

        # Wrong length: 64 floats instead of 128
        short_raw = struct.pack("<64f", *([0.0] * 64))
        short_b64 = base64.b64encode(short_raw).decode("ascii")
        self.assertIsNone(decode_embedding(short_b64))

        # Non-finite float (NaN)
        nan_floats = [0.0] * 128
        nan_floats[0] = float("nan")
        nan_b64 = base64.b64encode(struct.pack("<128f", *nan_floats)).decode("ascii")
        self.assertIsNone(decode_embedding(nan_b64))


class TestLoadBackup(unittest.TestCase):
    def test_reads_both_plain_and_gzip_files(self):
        rng = np.random.default_rng(88)
        emb = rng.uniform(-1.0, 1.0, size=128).astype(np.float32)
        b64_str = base64.b64encode(struct.pack("<128f", *emb.tolist())).decode("ascii")

        backup_payload = {
            "version": 1,
            "createdAtEpochSec": 1700000000,
            "appVersion": "1.0",
            "settings": {
                "autoFillSensitivity": "BALANCED",
                "indexingQuality": "BALANCED",
                "autoIndexNewScreenshots": True,
                "measuredSecPerImageAt280": None,
            },
            "collections": [
                {"key": "col1", "name": "Chats", "description": "", "colorHex": "#FF0000", "autoFill": True}
            ],
            "screenshots": [
                {
                    "displayName": "screenshot_01.png",
                    "dateAdded": 1700000000,
                    "sizeBytes": 12345,
                    "ocrText": "hello",
                    "visualLabels": "chat",
                    "isProcessed": True,
                    "embeddingB64": b64_str,
                    "memberships": [{"collectionKey": "col1", "source": "MANUAL", "score": None}],
                }
            ],
        }

        json_bytes = json.dumps(backup_payload).encode("utf-8")

        with tempfile.TemporaryDirectory() as td:
            plain_file = Path(td) / "backup.json"
            plain_file.write_bytes(json_bytes)

            gz_file = Path(td) / "backup.json.gz"
            gz_file.write_bytes(gzip.compress(json_bytes))

            b_plain = load_backup(plain_file)
            b_gz = load_backup(gz_file)

            self.assertEqual(len(b_plain["collections"]), 1)
            self.assertEqual(len(b_gz["collections"]), 1)
            self.assertEqual(len(b_plain["screenshots"]), 1)
            self.assertEqual(len(b_gz["screenshots"]), 1)

            self.assertEqual(b_plain["screenshots"][0]["displayName"], "screenshot_01.png")
            self.assertEqual(b_gz["screenshots"][0]["displayName"], "screenshot_01.png")
            np.testing.assert_allclose(b_plain["screenshots"][0]["embedding"], emb, rtol=1e-5)
            np.testing.assert_allclose(b_gz["screenshots"][0]["embedding"], emb, rtol=1e-5)


class TestOracleThreshold(unittest.TestCase):
    def test_find_oracle_threshold_maximizes_f1(self):
        scores = np.array([0.9, 0.8, 0.7, 0.4, 0.2])
        y_true = np.array([True, True, False, True, False])
        t = find_oracle_threshold(scores, y_true)
        # Optimal threshold is 0.4, yielding TP=3, FP=1, FN=0 -> F1 = 6/7 ~= 0.857
        self.assertAlmostEqual(t, 0.4)


class TestSyntheticProtocol(unittest.TestCase):
    def test_run_protocol_synthetic(self):
        rng = np.random.default_rng(999)
        categories = ["chats", "study", "tweets", "memes", "animals"]
        usable = []

        for i in range(150):
            emb = rng.normal(size=128).astype(np.float32)
            emb /= np.linalg.norm(emb)
            # Assign 0, 1, or 2 categories
            lbls = set()
            if i % 2 == 0:
                lbls.add(categories[i % len(categories)])
            if i % 7 == 0:
                lbls.add(categories[(i + 1) % len(categories)])
            usable.append({
                "displayName": f"img_{i:03d}.png",
                "embedding": emb,
                "labels": lbls,
                "swipe_pos": {},
                "swipe_neg": {},
            })

        bg = rng.normal(size=(20, 128)).astype(np.float32)
        bg /= np.linalg.norm(bg, axis=1, keepdims=True)
        cat_vecs = {}
        for c in categories:
            v = rng.normal(size=128).astype(np.float32)
            cat_vecs[c] = v / np.linalg.norm(v)

        text_vectors = {"background": bg, "categories": cat_vecs}

        results = run_protocol(
            usable_screenshots=usable,
            text_vectors=text_vectors,
            categories=categories,
            shots=[5],
            repeats=2,
            seed=0,
        )

        self.assertIn(5, results["shots"])
        shot_5 = results["shots"][5]
        self.assertIn("A@1.5", shot_5["methods"])
        self.assertIn("D+comp", shot_5["methods"])
        self.assertIn("C (oracle)", shot_5["methods"])
        self.assertGreaterEqual(shot_5["methods"]["A@1.5"]["macro_f1"]["mean"], 0.0)

    def test_run_protocol_synthetic_d256(self):
        rng = np.random.default_rng(2026)
        categories = ["chats", "study", "tweets", "memes", "animals"]
        dim = 256
        usable = []

        for i in range(120):
            emb = rng.normal(size=dim).astype(np.float32)
            emb /= np.linalg.norm(emb)
            lbls = set()
            if i % 2 == 0:
                lbls.add(categories[i % len(categories)])
            if i % 5 == 0:
                lbls.add(categories[(i + 1) % len(categories)])
            usable.append({
                "displayName": f"img_d256_{i:03d}.png",
                "embedding": emb,
                "labels": lbls,
                "swipe_pos": {},
                "swipe_neg": {},
            })

        bg = rng.normal(size=(15, dim)).astype(np.float32)
        bg /= np.linalg.norm(bg, axis=1, keepdims=True)
        cat_vecs = {}
        for c in categories:
            v = rng.normal(size=dim).astype(np.float32)
            cat_vecs[c] = v / np.linalg.norm(v)

        text_vectors = {"background": bg, "categories": cat_vecs, "dim": dim}

        results = run_protocol(
            usable_screenshots=usable,
            text_vectors=text_vectors,
            categories=categories,
            shots=[5],
            repeats=2,
            seed=42,
        )

        self.assertIn(5, results["shots"])
        shot_5 = results["shots"][5]
        self.assertIn("A@1.5", shot_5["methods"])
        self.assertIn("D+comp", shot_5["methods"])
        self.assertGreaterEqual(shot_5["methods"]["A@1.5"]["macro_f1"]["mean"], 0.0)


class TestExternalEmbeddings(unittest.TestCase):
    def test_load_npz_embeddings_and_prepare_dataset(self):
        rng = np.random.default_rng(1234)
        files = np.array(["shot1.png", "shot2.png", "shot3.png"])
        full_embs = rng.normal(size=(3, 256)).astype(np.float32)
        full_embs /= np.linalg.norm(full_embs, axis=1, keepdims=True)
        trunc_embs = full_embs[:, :128].copy()
        trunc_embs /= np.linalg.norm(trunc_embs, axis=1, keepdims=True)
        seconds = np.array([0.1, 0.2, 0.15], dtype=np.float32)

        with tempfile.TemporaryDirectory() as td:
            npz_path = Path(td) / "emb.npz"
            np.savez(npz_path, files=files, full=full_embs, trunc128=trunc_embs, seconds=seconds)

            # Test loading full
            loaded_full = load_npz_embeddings(npz_path, key="full")
            self.assertEqual(len(loaded_full), 3)
            self.assertIn("shot1.png", loaded_full)
            self.assertEqual(loaded_full["shot1.png"].shape, (256,))
            np.testing.assert_allclose(loaded_full["shot1.png"], full_embs[0], rtol=1e-5)

            # Test loading trunc128
            loaded_trunc = load_npz_embeddings(npz_path, key="trunc128")
            self.assertEqual(loaded_trunc["shot1.png"].shape, (128,))
            np.testing.assert_allclose(loaded_trunc["shot1.png"], trunc_embs[0], rtol=1e-5)

            # Test missing key raises KeyError
            with self.assertRaises(KeyError):
                load_npz_embeddings(npz_path, key="nonexistent")

            # Test prepare_dataset with external embeddings
            # Backup has shot1.png, shot2.png, shot_extra.png
            backup_data = {
                "collections": [{"key": "c1", "name": "Chats"}],
                "screenshots": [
                    {
                        "displayName": "shot1.png",
                        "embedding": np.zeros(128, dtype=np.float32),
                        "memberships": [{"collectionKey": "c1", "source": "MANUAL"}],
                    },
                    {
                        "displayName": "shot2.png",
                        "embedding": np.zeros(128, dtype=np.float32),
                        "memberships": [],
                    },
                    {
                        "displayName": "shot_extra.png",  # Not in npz -> should be dropped!
                        "embedding": np.zeros(128, dtype=np.float32),
                        "memberships": [],
                    },
                ],
            }
            labels_dict = {
                "shot1.png": ["chats"],
                "shot2.png": ["study"],
                "shot_extra.png": ["chats"],
            }
            mapped_categories = [("chats", "Chats")]

            usable, summary, swipe_agree = prepare_dataset(
                backup_data,
                labels_dict,
                mapped_categories,
                external_embeddings=loaded_full,
            )

            # shot_extra.png should be dropped because it is missing from external_embeddings
            self.assertEqual(len(usable), 2)
            usable_names = [s["displayName"] for s in usable]
            self.assertIn("shot1.png", usable_names)
            self.assertIn("shot2.png", usable_names)
            self.assertNotIn("shot_extra.png", usable_names)

            # Vectors must match loaded_full (256-d), not backup zeros
            shot1 = next(s for s in usable if s["displayName"] == "shot1.png")
            self.assertEqual(shot1["embedding"].shape, (256,))
            np.testing.assert_allclose(shot1["embedding"], loaded_full["shot1.png"], rtol=1e-5)
            self.assertTrue(shot1["swipe_pos"].get("chats", False))

    def test_validate_dimensions_mismatch(self):
        usable_256 = [{"displayName": "img.png", "embedding": np.zeros(256, dtype=np.float32)}]
        text_128 = {
            "dim": 128,
            "background": np.zeros((5, 128), dtype=np.float32),
            "categories": {"chats": np.zeros(128, dtype=np.float32)},
        }
        with self.assertRaises(ValueError) as ctx:
            validate_dimensions(usable_256, text_128)
        self.assertIn("Dimension mismatch", str(ctx.exception))

        # Matching dimension should not raise
        text_256 = {
            "dim": 256,
            "background": np.zeros((5, 256), dtype=np.float32),
            "categories": {"chats": np.zeros(256, dtype=np.float32)},
        }
        validate_dimensions(usable_256, text_256)


if __name__ == "__main__":
    unittest.main()

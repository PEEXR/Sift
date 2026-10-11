#!/usr/bin/env python3
"""Unit tests for compare_labels.py."""
from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

# Ensure tools/phase0 is on sys.path
sys.path.insert(0, str(Path(__file__).parent.resolve()))

import numpy as np

import compare_labels


class TestCompareLabels(unittest.TestCase):
    def test_perfect_agreement(self):
        """When ref and pred have identical labels, metrics must be 1.0 and confusion empty."""
        test_cats = ["chats", "study", "tweets", "memes", "animals", "notes"]
        ref = {
            "img1.jpg": {"file": "img1.jpg", "labels": ["chats", "memes"], "seconds": 1.0},
            "img2.jpg": {"file": "img2.jpg", "labels": ["study"], "seconds": 1.2},
            "img3.jpg": {"file": "img3.jpg", "labels": ["tweets", "animals", "notes"], "seconds": 0.8},
        }
        pred = {
            "img1.jpg": {"file": "img1.jpg", "labels": ["memes", "chats"], "seconds": 0.5},
            "img2.jpg": {"file": "img2.jpg", "labels": ["study"], "seconds": 0.6},
            "img3.jpg": {"file": "img3.jpg", "labels": ["animals", "notes", "tweets"], "seconds": 0.4},
        }

        res = compare_labels.compare_single(ref, pred, categories=test_cats)

        self.assertEqual(res["joined_count"], 3)
        self.assertEqual(res["match"]["exact_matches"], 3)
        self.assertEqual(res["match"]["exact_set_match_rate"], 1.0)
        self.assertEqual(res["macro_f1"], 1.0)
        self.assertEqual(res["confusion_pairs"], [])

        for cat in test_cats:
            m = res["categories"][cat]
            self.assertEqual(m["precision"], 1.0)
            self.assertEqual(m["recall"], 1.0)
            self.assertEqual(m["f1"], 1.0)
            self.assertEqual(m["fp"], 0)
            self.assertEqual(m["fn"], 0)

    def test_empty_label_rows(self):
        """Records with empty labels should match if both empty, and affect mean label counts."""
        ref = {
            "img1.jpg": {"file": "img1.jpg", "labels": []},
            "img2.jpg": {"file": "img2.jpg", "labels": ["chats"]},
            "img3.jpg": {"file": "img3.jpg", "labels": []},
        }
        pred = {
            "img1.jpg": {"file": "img1.jpg", "labels": []},
            "img2.jpg": {"file": "img2.jpg", "labels": ["chats"]},
            "img3.jpg": {"file": "img3.jpg", "labels": ["memes"]},  # mismatch
        }

        res = compare_labels.compare_single(ref, pred, categories=["chats", "memes"])

        self.assertEqual(res["joined_count"], 3)
        # img1 and img2 match; img3 does not
        self.assertEqual(res["match"]["exact_matches"], 2)
        self.assertAlmostEqual(res["match"]["exact_set_match_rate"], 2 / 3, places=3)
        # Ref has 1 total label across 3 images -> 0.33
        self.assertEqual(res["match"]["mean_labels_ref"], 0.33)
        # Pred has 2 total labels across 3 images -> 0.67
        self.assertEqual(res["match"]["mean_labels_pred"], 0.67)

        # For img3, ref has [], pred has ["memes"].
        # Since ref lacks any category 'a', no (a, memes) confusion pair is created.
        self.assertEqual(res["confusion_pairs"], [])

    def test_category_absent_from_ref(self):
        """A category completely absent from ref should have 0 recall/precision/F1 and not crash."""
        ref = {
            "img1.jpg": {"file": "img1.jpg", "labels": ["chats"]},
            "img2.jpg": {"file": "img2.jpg", "labels": ["tweets"]},
        }
        # Pred predicts 'study' (which is absent in ref) for img1
        pred = {
            "img1.jpg": {"file": "img1.jpg", "labels": ["chats", "study"]},
            "img2.jpg": {"file": "img2.jpg", "labels": ["tweets"]},
        }

        res = compare_labels.compare_single(ref, pred, categories=["chats", "tweets", "study", "animals"])

        study_m = res["categories"]["study"]
        self.assertEqual(study_m["ref_count"], 0)
        self.assertEqual(study_m["pred_count"], 1)
        self.assertEqual(study_m["tp"], 0)
        self.assertEqual(study_m["fp"], 1)
        self.assertEqual(study_m["fn"], 0)
        self.assertEqual(study_m["precision"], 0.0)
        self.assertEqual(study_m["recall"], 0.0)
        self.assertEqual(study_m["f1"], 0.0)

        # 'animals' is absent from both ref and pred
        anim_m = res["categories"]["animals"]
        self.assertEqual(anim_m["ref_count"], 0)
        self.assertEqual(anim_m["pred_count"], 0)
        self.assertEqual(anim_m["precision"], 0.0)
        self.assertEqual(anim_m["recall"], 0.0)
        self.assertEqual(anim_m["f1"], 0.0)

        # Confusion pair should show ref has 'chats', pred has 'study', ref lacks 'study'
        self.assertEqual(len(res["confusion_pairs"]), 1)
        self.assertEqual(res["confusion_pairs"][0], {"ref": "chats", "pred": "study", "count": 1})

    def test_confusion_pair_counting(self):
        """Tests counting and ranking of confusion pairs (ref has a, pred has b, ref lacks b)."""
        ref = {
            "img1.jpg": {"file": "img1.jpg", "labels": ["chats"]},
            "img2.jpg": {"file": "img2.jpg", "labels": ["chats"]},
            "img3.jpg": {"file": "img3.jpg", "labels": ["chats", "notes"]},
            "img4.jpg": {"file": "img4.jpg", "labels": ["study"]},
        }
        pred = {
            # img1: ref has 'chats', pred has 'memes' -> ('chats', 'memes')
            "img1.jpg": {"file": "img1.jpg", "labels": ["memes"]},
            # img2: ref has 'chats', pred has 'chats', 'memes' -> ('chats', 'memes')
            "img2.jpg": {"file": "img2.jpg", "labels": ["chats", "memes"]},
            # img3: ref has 'chats' and 'notes', pred has 'chats', 'tweets' -> ('chats', 'tweets') & ('notes', 'tweets')
            "img3.jpg": {"file": "img3.jpg", "labels": ["chats", "tweets"]},
            # img4: ref has 'study', pred has 'study' -> no confusion
            "img4.jpg": {"file": "img4.jpg", "labels": ["study"]},
        }

        pairs = compare_labels.compute_confusion_pairs(
            compare_labels.join_records(ref, pred),
            top_k=10,
        )

        expected = [
            {"ref": "chats", "pred": "memes", "count": 2},
            {"ref": "chats", "pred": "tweets", "count": 1},
            {"ref": "notes", "pred": "tweets", "count": 1},
        ]
        self.assertEqual(pairs, expected)

    def test_speed_summary(self):
        """Speed calculation should compute mean, median, and p90 correctly."""
        records = [
            {"file": "1.jpg", "seconds": 1.0},
            {"file": "2.jpg", "seconds": 2.0},
            {"file": "3.jpg", "seconds": 3.0},
            {"file": "4.jpg", "seconds": 4.0},
            {"file": "5.jpg", "seconds": 5.0},
        ]
        speed = compare_labels.compute_speed_metrics(records)
        self.assertIsNotNone(speed)
        self.assertEqual(speed["count"], 5)
        self.assertEqual(speed["mean"], 3.0)
        self.assertEqual(speed["median"], 3.0)
        # p90 for [1, 2, 3, 4, 5] is 4.6
        self.assertEqual(speed["p90"], 4.6)

        # Empty records returns None
        self.assertIsNone(compare_labels.compute_speed_metrics([]))

    def test_markdown_and_json(self):
        """Markdown output contains required sections and values."""
        ref = {"img1.jpg": {"file": "img1.jpg", "labels": ["chats"], "seconds": 1.5}}
        pred = {"img1.jpg": {"file": "img1.jpg", "labels": ["chats"], "seconds": 0.8}}
        res = compare_labels.compare_single(ref, pred, categories=["chats"])
        md = compare_labels.format_markdown(res, "ref.jsonl", "pred.jsonl")

        self.assertIn("# Label Comparison", md)
        self.assertIn("Exact Set Match Rate", md)
        self.assertIn("Per-Category Performance", md)
        self.assertIn("Speed Summary", md)
        self.assertIn("Top 10 Confusion Pairs", md)
        self.assertIn("0.80s", md)

        # Verify JSON serializability
        serialized = json.dumps(res)
        self.assertIsInstance(serialized, str)


if __name__ == "__main__":
    unittest.main()

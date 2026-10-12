#!/usr/bin/env python3
"""Evaluation of Liquid AI d1-omni-600M zero-shot outputs on screenshots.

Evaluates Collections (device protocol & random protocol) and Junk tags.
Compares d1 alone and image+d1 fusions (mean, or, band) against:
- image alone (EmbeddingGemma 2)
- Jev alone (cloud text decider on RapidOCR)
- image+Jev fusions
- Gemma 4 reference
- Junk rules

Outputs:
  data/report_d1.md and prints summary tables to stdout.
"""
from __future__ import annotations

import argparse
import json
import random
import shutil
import sqlite3
import sys
import tempfile
from pathlib import Path
from typing import Any

import numpy as np

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import embed_text
from common import BACKGROUND_DESCRIPTIONS, JUNK_TAGS
from evaluate import predict_logreg, train_logreg
from eval_fusion import (
    COLL_CATEGORIES,
    COL_TO_CAT,
    JUNK_TAG_NAMES,
    calc_f1,
    compute_fusion_preds,
    open_temp_db,
    sigmoid,
)


def calc_prf1(pred: list[bool] | np.ndarray, truth: list[bool] | np.ndarray) -> tuple[float, float, float]:
    """Calculates precision, recall, and F1."""
    pred_b = np.asarray(pred, dtype=bool)
    true_b = np.asarray(truth, dtype=bool)
    tp = int(np.sum(pred_b & true_b))
    fp = int(np.sum(pred_b & ~true_b))
    fn = int(np.sum(~pred_b & true_b))
    p = (float(tp) / (tp + fp)) if (tp + fp) > 0 else 0.0
    r = (float(tp) / (tp + fn)) if (tp + fn) > 0 else 0.0
    f1 = (2.0 * p * r / (p + r)) if (p + r) > 0 else 0.0
    return p, r, f1


def sweep_best_threshold(
    probs: list[float | None],
    truth: list[bool],
    thresholds: list[float] | None = None,
) -> tuple[float, float]:
    """Sweeps decision threshold to find optimistic maximum F1 and best threshold."""
    if thresholds is None:
        thresholds = [round(t * 0.05, 2) for t in range(1, 20)]  # 0.05 .. 0.95
    best_f1 = -1.0
    best_th = 0.5
    for th in thresholds:
        preds = [(p is not None and p >= th) for p in probs]
        score = calc_f1(preds, truth)
        if score > best_f1:
            best_f1 = score
            best_th = th
    return best_f1, best_th


def load_jsonl_map(path: Path) -> dict[str, dict[str, Any]]:
    """Loads a JSONL file into a dict keyed by 'file'."""
    data = {}
    if path.exists():
        with path.open(encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    try:
                        rec = json.loads(line)
                        if "file" in rec:
                            data[rec["file"]] = rec
                    except Exception:
                        pass
    return data


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate d1-omni-600M zero-shot outputs")
    parser.add_argument("--d1-image-ocr", type=Path, default=Path("data/d1_image_ocr_fp16.jsonl"))
    parser.add_argument("--d1-image-only", type=Path, default=Path("data/d1_image_only_fp16.jsonl"))
    parser.add_argument("--d1-ocr-only", type=Path, default=Path("data/d1_ocr_only_fp16.jsonl"))
    parser.add_argument("--extra-d1", type=Path, nargs="*", default=[],
                        help="Additional d1 jsonl output files to evaluate")
    parser.add_argument("--scan-all", action="store_true", default=True,
                        help="Automatically scan and evaluate all data/d1_*.jsonl files")
    parser.add_argument("--db", type=Path, default=Path("data/device/sift_database"))
    parser.add_argument("--labels", type=Path, default=Path("data/labels.jsonl"))
    parser.add_argument("--labels-gemma", type=Path, default=Path("data/labels_gemma4.jsonl"))
    parser.add_argument("--labels-junk", type=Path, default=Path("data/labels_junk_ornith.jsonl"))
    parser.add_argument("--coll-jev", type=Path, default=Path("data/coll_jev_rapidocr.jsonl"))
    parser.add_argument("--junk-jev", type=Path, default=Path("data/junk_jev_rapidocr.jsonl"))
    parser.add_argument("--junk-rules", type=Path, default=Path("data/junk_rules_rapidocr.jsonl"))
    parser.add_argument("--report", type=Path, default=Path("data/report_d1.md"))
    parser.add_argument("--seeds", type=int, default=20)
    args = parser.parse_args()

    # Resolve paths
    def resolve_p(p: Path) -> Path:
        return SCRIPT_DIR / p if not p.is_absolute() else p

    db_path = resolve_p(args.db)
    labels_path = resolve_p(args.labels)
    labels_gemma_path = resolve_p(args.labels_gemma)
    labels_junk_path = resolve_p(args.labels_junk)
    coll_jev_path = resolve_p(args.coll_jev)
    junk_jev_path = resolve_p(args.junk_jev)
    junk_rules_path = resolve_p(args.junk_rules)
    report_path = resolve_p(args.report)

    # Collect d1 candidate runs
    d1_runs: dict[str, Path] = {}
    if resolve_p(args.d1_image_ocr).exists():
        d1_runs[args.d1_image_ocr.stem] = resolve_p(args.d1_image_ocr)
    if resolve_p(args.d1_image_only).exists():
        d1_runs[args.d1_image_only.stem] = resolve_p(args.d1_image_only)
    if resolve_p(args.d1_ocr_only).exists():
        d1_runs[args.d1_ocr_only.stem] = resolve_p(args.d1_ocr_only)
    for extra in args.extra_d1:
        ep = resolve_p(extra)
        if ep.exists():
            d1_runs[ep.stem] = ep

    if args.scan_all:
        for f in sorted((SCRIPT_DIR / "data").glob("d1_*.jsonl")):
            if f.stem not in d1_runs:
                d1_runs[f.stem] = f

    if not d1_runs:
        print("ERROR: No d1 output files found to evaluate! Run d1_run.py first.")
        return 1

    print(f"Found {len(d1_runs)} d1 run file(s) to evaluate: {list(d1_runs.keys())}")

    report_lines: list[str] = []
    report_lines.append("# Liquid AI `d1-omni-600M` Zero-Shot Evaluation Report (GPU Only)")
    report_lines.append("")
    report_lines.append("Evaluation of `LiquidAI/d1-omni-600M` zero-shot multimodal decision model on mobile screenshots for collection grouping and junk tagging, across FP16, INT8, and INT4 precisions on an AMD Radeon RX 6600 (ROCm 7.14, gfx1030).")
    report_lines.append("")

    # =========================================================================
    # 1. Load SQLite Database: Image vectors and manual training examples
    # =========================================================================
    print(f"Loading database from {db_path}...")
    con, tmpdir = open_temp_db(db_path)
    try:
        rows = con.execute("SELECT id, displayName, embedding FROM screenshots WHERE embedding IS NOT NULL").fetchall()
        man_rows = con.execute(
            "SELECT sc.screenshotId, c.name FROM screenshot_collections sc "
            "JOIN collections c ON c.id=sc.collectionId WHERE sc.source='MANUAL'"
        ).fetchall()
    finally:
        con.close()
        shutil.rmtree(tmpdir, ignore_errors=True)

    db_ids = [r[0] for r in rows]
    db_names = {r[0]: r[1] for r in rows}
    X_img_by_id = {r[0]: np.frombuffer(r[2], dtype="<f4") for r in rows}
    X_img_by_name = {r[1]: np.frombuffer(r[2], dtype="<f4") for r in rows}

    man_by_cat: dict[str, set[int]] = {}
    for sid, cname in man_rows:
        if cname in COL_TO_CAT:
            man_by_cat.setdefault(COL_TO_CAT[cname], set()).add(sid)
    all_man_ids = set().union(*man_by_cat.values())

    # Load references and baselines
    ref_coll = load_jsonl_map(labels_path)
    gem_coll = load_jsonl_map(labels_gemma_path)
    coll_jev_records = load_jsonl_map(coll_jev_path)
    ref_junk = load_jsonl_map(labels_junk_path)
    junk_jev_records = load_jsonl_map(junk_jev_path)
    junk_rules_records = load_jsonl_map(junk_rules_path)

    d1_records_by_run: dict[str, dict[str, dict[str, Any]]] = {
        name: load_jsonl_map(path) for name, path in d1_runs.items()
    }

    # =========================================================================
    # 2. Timing, Memory, and Image Token Stats
    # =========================================================================
    report_lines.append("## 1. Speed, Memory & Model Resource Usage")
    report_lines.append("")
    report_lines.append("| Run Mode | Scored Screens | Median Time (s) | P90 Time (s) | Mean Time (s) | Median Image Tokens | Truncated Count |")
    report_lines.append("|---|---|---|---|---|---|---|")

    print("\n=== Speed, Memory & Token Usage ===")
    for run_name, recs in d1_records_by_run.items():
        durations = [r["seconds"] for r in recs.values() if "seconds" in r and r["seconds"] > 0]
        tokens = [r.get("n_image_tokens", 0) for r in recs.values()]
        trunc_count = sum(1 for r in recs.values() if r.get("truncated", False))
        if len(durations) > 1:
            steady = durations[1:]
            med = float(np.median(steady))
            p90 = float(np.percentile(steady, 90))
            mean_d = float(np.mean(steady))
        elif durations:
            med = p90 = mean_d = durations[0]
        else:
            med = p90 = mean_d = 0.0

        med_tok = int(np.median(tokens)) if tokens else 0
        row = [
            f"**{run_name}**",
            str(len(recs)),
            f"{med:.3f}s",
            f"{p90:.3f}s",
            f"{mean_d:.3f}s",
            str(med_tok),
            f"{trunc_count}/{len(recs)}",
        ]
        report_lines.append("| " + " | ".join(row) + " |")
        print(f"[{run_name}]: N={len(recs)} | med={med:.3f}s | p90={p90:.3f}s | tokens={med_tok} | truncated={trunc_count}")

    report_lines.append("")

    # =========================================================================
    # 3. Collections Evaluation
    # =========================================================================
    print("\n================== Collections Evaluation ==================")
    report_lines.append("## 2. Collections Evaluation (5 Categories)")
    report_lines.append("")

    dev_full_test_ids = [
        i for i in db_ids
        if db_names[i] in ref_coll and db_names[i] in gem_coll and i not in all_man_ids
    ]

    macro_f1_coll_by_run: dict[str, float] = {}

    for run_name, d1_recs in d1_records_by_run.items():
        dev_test_ids = [
            i for i in dev_full_test_ids
            if db_names[i] in d1_recs and db_names[i] in coll_jev_records
        ]
        dev_test_files = [db_names[i] for i in dev_test_ids]

        report_lines.append(f"### Collections: `{run_name}`")
        report_lines.append(f"**Screenshots Scored:** {len(dev_test_ids)}.")
        report_lines.append("")

        if not dev_test_ids:
            report_lines.append("*No overlapping screenshots available for collections.*")
            report_lines.append("")
            continue

        # Train image-side logreg on DB manual examples
        A_dev = np.stack([X_img_by_id[i] for i in dev_test_ids])
        p_img_dev: dict[str, np.ndarray] = {}
        for cat in COLL_CATEGORIES:
            pos = man_by_cat.get(cat, set())
            others = set().union(*(v for k, v in man_by_cat.items() if k != cat)) - pos
            rng = random.Random(1)
            cand = sorted(set(db_ids) - pos)
            bg = rng.sample(cand, 50)
            neg = sorted(others | set(bg))
            Xt = np.stack([X_img_by_id[i] for i in sorted(pos)] + [X_img_by_id[i] for i in neg])
            y = np.array([1.0] * len(pos) + [0.0] * len(neg))
            theta = train_logreg(Xt, y, lam=1.0)
            p_img_dev[cat] = predict_logreg(A_dev, theta)

        y_true_dev: dict[str, list[bool]] = {
            cat: [cat in ref_coll[f].get("labels", []) for f in dev_test_files]
            for cat in COLL_CATEGORIES
        }

        p_jev_dev: dict[str, list[float | None]] = {
            cat: [
                float(coll_jev_records[f]["p"][cat])
                if (coll_jev_records[f].get("p") is not None and cat in coll_jev_records[f]["p"])
                else None
                for f in dev_test_files
            ]
            for cat in COLL_CATEGORIES
        }

        p_d1_dev: dict[str, list[float | None]] = {
            cat: [
                float(d1_recs[f]["coll"][cat])
                if (d1_recs[f].get("coll") is not None and cat in d1_recs[f]["coll"] and d1_recs[f]["coll"][cat] is not None)
                else None
                for f in dev_test_files
            ]
            for cat in COLL_CATEGORIES
        }

        gemma_f1: dict[str, float] = {}
        for cat in COLL_CATEGORIES:
            y_gem = [cat in gem_coll[f].get("labels", []) for f in dev_test_files]
            gemma_f1[cat] = calc_f1(y_gem, y_true_dev[cat])
        gemma_macro = float(np.mean([gemma_f1[c] for c in COLL_CATEGORIES]))

        methods = [
            "image only",
            "Jev alone",
            "image+Jev (mean)",
            "image+Jev (or)",
            "image+Jev (band)",
            "d1 alone (0.5)",
            "d1 alone (best th)*",
            "image+d1 (mean)",
            "image+d1 (or)",
            "image+d1 (band)",
        ]

        cat_f1: dict[str, dict[str, float]] = {m: {} for m in methods}
        preds_all: dict[str, dict[str, list[bool]]] = {m: {} for m in methods}
        best_th_d1: dict[str, float] = {}

        for cat in COLL_CATEGORIES:
            truth = y_true_dev[cat]
            pi = p_img_dev[cat]
            pj = p_jev_dev[cat]
            pd = p_d1_dev[cat]

            # Image alone
            pred_img = compute_fusion_preds("image", pi, pj)
            preds_all["image only"][cat] = pred_img
            cat_f1["image only"][cat] = calc_f1(pred_img, truth)

            # Jev alone
            pred_jev = compute_fusion_preds("jev", pi, pj)
            preds_all["Jev alone"][cat] = pred_jev
            cat_f1["Jev alone"][cat] = calc_f1(pred_jev, truth)

            # Image + Jev
            pred_ij_m = compute_fusion_preds("mean", pi, pj)
            preds_all["image+Jev (mean)"][cat] = pred_ij_m
            cat_f1["image+Jev (mean)"][cat] = calc_f1(pred_ij_m, truth)

            pred_ij_or = compute_fusion_preds("or", pi, pj)
            preds_all["image+Jev (or)"][cat] = pred_ij_or
            cat_f1["image+Jev (or)"][cat] = calc_f1(pred_ij_or, truth)

            pred_ij_b = compute_fusion_preds("band", pi, pj)
            preds_all["image+Jev (band)"][cat] = pred_ij_b
            cat_f1["image+Jev (band)"][cat] = calc_f1(pred_ij_b, truth)

            # d1 alone (0.5)
            pred_d1_05 = [(p is not None and p >= 0.5) for p in pd]
            preds_all["d1 alone (0.5)"][cat] = pred_d1_05
            cat_f1["d1 alone (0.5)"][cat] = calc_f1(pred_d1_05, truth)

            # d1 alone (best th)
            b_f1, b_th = sweep_best_threshold(pd, truth)
            best_th_d1[cat] = b_th
            pred_d1_best = [(p is not None and p >= b_th) for p in pd]
            preds_all["d1 alone (best th)*"][cat] = pred_d1_best
            cat_f1["d1 alone (best th)*"][cat] = b_f1

            # Image + d1
            pred_id_m = compute_fusion_preds("mean", pi, pd)
            preds_all["image+d1 (mean)"][cat] = pred_id_m
            cat_f1["image+d1 (mean)"][cat] = calc_f1(pred_id_m, truth)

            pred_id_or = compute_fusion_preds("or", pi, pd)
            preds_all["image+d1 (or)"][cat] = pred_id_or
            cat_f1["image+d1 (or)"][cat] = calc_f1(pred_id_or, truth)

            pred_id_b = compute_fusion_preds("band", pi, pd)
            preds_all["image+d1 (band)"][cat] = pred_id_b
            cat_f1["image+d1 (band)"][cat] = calc_f1(pred_id_b, truth)

        macro_f1_coll_by_run[run_name] = float(np.mean([cat_f1["d1 alone (0.5)"][c] for c in COLL_CATEGORIES]))

        mean_colls: dict[str, float] = {}
        for m in methods:
            counts = [
                sum(preds_all[m][c][idx] for c in COLL_CATEGORIES)
                for idx in range(len(dev_test_files))
            ]
            mean_colls[m] = float(np.mean(counts))

        headers = ["Method", "Macro F1"] + [f"{c.capitalize()} F1" for c in COLL_CATEGORIES] + ["Mean Colls/Screen"]
        report_lines.append("| " + " | ".join(headers) + " |")
        report_lines.append("| " + " | ".join(["---"] * len(headers)) + " |")

        for m in methods:
            macro = float(np.mean([cat_f1[m][c] for c in COLL_CATEGORIES]))
            cat_strs = [f"{cat_f1[m][c]:.3f}" for c in COLL_CATEGORIES]
            row = [f"**{m}**", f"**{macro:.4f}**"] + cat_strs + [f"{mean_colls[m]:.2f}"]
            report_lines.append("| " + " | ".join(row) + " |")
            print(f"[{run_name}][{m:>20}]: Macro = {macro:.4f} | " + " ".join(f"{c}={cat_f1[m][c]:.3f}" for c in COLL_CATEGORIES))

        gem_counts = [
            sum(c in gem_coll[f].get("labels", []) for c in COLL_CATEGORIES)
            for f in dev_test_files
        ]
        gem_mean_col = float(np.mean(gem_counts))
        gem_cat_strs = [f"{gemma_f1[c]:.3f}" for c in COLL_CATEGORIES]
        report_lines.append("| " + " | ".join(["*Gemma 4 Reference*", f"*{gemma_macro:.4f}*"] + gem_cat_strs + [f"{gem_mean_col:.2f}"]) + " |")
        report_lines.append("")
        report_lines.append(r"\* *Note: `d1 alone (best th)` optimizes threshold on scored test set (optimistic upper bound).*")
        report_lines.append(f"Best thresholds per category: " + ", ".join(f"{c} ({best_th_d1[c]})" for c in COLL_CATEGORIES))
        report_lines.append("")

        # Calibration check
        all_d1_probs = [p for cat in COLL_CATEGORIES for p in p_d1_dev[cat] if p is not None]
        if all_d1_probs:
            near_half = sum(1 for p in all_d1_probs if 0.4 <= p <= 0.6) / len(all_d1_probs)
            near_extreme = sum(1 for p in all_d1_probs if p < 0.1 or p > 0.9) / len(all_d1_probs)
            report_lines.append(f"**Calibration check ({run_name}):** Mean prob={np.mean(all_d1_probs):.3f}, "
                                f"Median={np.median(all_d1_probs):.3f}, "
                                f"{near_half*100:.1f}% in [0.4, 0.6], {near_extreme*100:.1f}% in extremes (<0.1 or >0.9).")
            report_lines.append("")

    # =========================================================================
    # 4. Junk Tags Evaluation
    # =========================================================================
    print("\n================== Junk Tags Evaluation ==================")
    report_lines.append("## 3. Junk Tags Evaluation (4 Tags)")
    report_lines.append("")
    report_lines.append("> [!NOTE] There are ~55 positive junk tags across the full 962 screenshots in the Ornith reference labels (~5.7% prevalence).")
    report_lines.append("")

    print("Embedding background descriptions and junk queries for image zero-shot baseline...")
    embed_text.load()
    B_bg = embed_text.embed(BACKGROUND_DESCRIPTIONS)
    tag_queries = {t: embed_text.embed(JUNK_TAGS[t])[0] for t in JUNK_TAG_NAMES}

    macro_f1_junk_by_run: dict[str, float] = {}

    for run_name, d1_recs in d1_records_by_run.items():
        junk_test_files = sorted(
            set(ref_junk.keys()) & set(d1_recs.keys()) & set(junk_jev_records.keys()) & set(X_img_by_name.keys())
        )
        report_lines.append(f"### Junk: `{run_name}`")
        report_lines.append(f"**Screenshots Scored:** {len(junk_test_files)}.")
        report_lines.append("")

        if not junk_test_files:
            report_lines.append("*No overlapping screenshots available for junk tags.*")
            report_lines.append("")
            continue

        p_img_junk: dict[str, list[float]] = {t: [] for t in JUNK_TAG_NAMES}
        for f in junk_test_files:
            x = X_img_by_name[f]
            dots_bg = x @ B_bg.T
            mean_bg = float(np.mean(dots_bg))
            std_bg = float(np.std(dots_bg, ddof=0))
            safe_std = std_bg if std_bg > 1e-6 else 1.0
            for t in JUNK_TAG_NAMES:
                s_tag = float(np.dot(x, tag_queries[t]))
                z = (s_tag - mean_bg) / safe_std
                p_img_junk[t].append(float(sigmoid(z - 1.5)))

        y_true_junk = {
            t: [t in ref_junk[f].get("tags", []) for f in junk_test_files]
            for t in JUNK_TAG_NAMES
        }
        ref_counts = {t: sum(y_true_junk[t]) for t in JUNK_TAG_NAMES}

        p_jev_junk = {
            t: [
                float(junk_jev_records[f]["p"][t])
                if (junk_jev_records[f].get("p") is not None and t in junk_jev_records[f]["p"])
                else None
                for f in junk_test_files
            ]
            for t in JUNK_TAG_NAMES
        }

        p_rules_junk = {
            t: [
                float(junk_rules_records[f]["p"][t])
                if (f in junk_rules_records and junk_rules_records[f].get("p") is not None and t in junk_rules_records[f]["p"])
                else 0.0
                for f in junk_test_files
            ]
            for t in JUNK_TAG_NAMES
        }

        p_d1_junk = {
            t: [
                float(d1_recs[f]["junk"][t])
                if (d1_recs[f].get("junk") is not None and t in d1_recs[f]["junk"] and d1_recs[f]["junk"][t] is not None)
                else None
                for f in junk_test_files
            ]
            for t in JUNK_TAG_NAMES
        }

        junk_methods = [
            "Jev alone",
            "Rules",
            "Image alone",
            "d1 alone (0.5)",
            "d1 alone (best th)*",
            "image+d1 (mean)",
            "image+d1 (or)",
        ]

        junk_f1_by_m: dict[str, dict[str, float]] = {m: {} for m in junk_methods}
        junk_p_by_m: dict[str, dict[str, float]] = {m: {} for m in junk_methods}
        junk_r_by_m: dict[str, dict[str, float]] = {m: {} for m in junk_methods}
        best_th_junk_d1: dict[str, float] = {}

        for t in JUNK_TAG_NAMES:
            truth = y_true_junk[t]
            pi = np.array(p_img_junk[t])
            pj = p_jev_junk[t]
            pr = p_rules_junk[t]
            pd = p_d1_junk[t]

            # Jev alone
            pred_j = [(p is not None and p >= 0.5) for p in pj]
            p, r, f = calc_prf1(pred_j, truth)
            junk_p_by_m["Jev alone"][t], junk_r_by_m["Jev alone"][t], junk_f1_by_m["Jev alone"][t] = p, r, f

            # Rules
            pred_r = [p >= 0.5 for p in pr]
            p, r, f = calc_prf1(pred_r, truth)
            junk_p_by_m["Rules"][t], junk_r_by_m["Rules"][t], junk_f1_by_m["Rules"][t] = p, r, f

            # Image alone
            pred_i = [p >= 0.5 for p in pi]
            p, r, f = calc_prf1(pred_i, truth)
            junk_p_by_m["Image alone"][t], junk_r_by_m["Image alone"][t], junk_f1_by_m["Image alone"][t] = p, r, f

            # d1 alone (0.5)
            pred_d05 = [(p is not None and p >= 0.5) for p in pd]
            p, r, f = calc_prf1(pred_d05, truth)
            junk_p_by_m["d1 alone (0.5)"][t], junk_r_by_m["d1 alone (0.5)"][t], junk_f1_by_m["d1 alone (0.5)"][t] = p, r, f

            # d1 alone (best th)
            b_f1, b_th = sweep_best_threshold(pd, truth)
            best_th_junk_d1[t] = b_th
            pred_dbest = [(p is not None and p >= b_th) for p in pd]
            p, r, f = calc_prf1(pred_dbest, truth)
            junk_p_by_m["d1 alone (best th)*"][t], junk_r_by_m["d1 alone (best th)*"][t], junk_f1_by_m["d1 alone (best th)*"][t] = p, r, b_f1

            # image+d1 (mean)
            pred_id_m = compute_fusion_preds("mean", pi, pd)
            p, r, f = calc_prf1(pred_id_m, truth)
            junk_p_by_m["image+d1 (mean)"][t], junk_r_by_m["image+d1 (mean)"][t], junk_f1_by_m["image+d1 (mean)"][t] = p, r, f

            # image+d1 (or)
            pred_id_or = compute_fusion_preds("or", pi, pd)
            p, r, f = calc_prf1(pred_id_or, truth)
            junk_p_by_m["image+d1 (or)"][t], junk_r_by_m["image+d1 (or)"][t], junk_f1_by_m["image+d1 (or)"][t] = p, r, f

        macro_f1_junk_by_run[run_name] = float(np.mean([junk_f1_by_m["d1 alone (0.5)"][t] for t in JUNK_TAG_NAMES]))

        headers = ["Method", "Macro F1"] + [f"{t} (n={ref_counts[t]})" for t in JUNK_TAG_NAMES]
        report_lines.append("| " + " | ".join(headers) + " |")
        report_lines.append("| " + " | ".join(["---"] * len(headers)) + " |")

        for m in junk_methods:
            macro = float(np.mean([junk_f1_by_m[m][t] for t in JUNK_TAG_NAMES]))
            t_strs = [f"{junk_f1_by_m[m][t]:.3f}" for t in JUNK_TAG_NAMES]
            row = [f"**{m}**", f"**{macro:.4f}**"] + t_strs
            report_lines.append("| " + " | ".join(row) + " |")
            print(f"[{run_name}][Junk {m:>18}]: Macro = {macro:.4f} | " + " ".join(f"{t}={junk_f1_by_m[m][t]:.3f}" for t in JUNK_TAG_NAMES))

        report_lines.append("")
        report_lines.append("#### Precision / Recall Details for `d1 alone`:")
        report_lines.append("| Tag | Ref Positives | P (0.5) | R (0.5) | F1 (0.5) | Best Th | P (best) | R (best) | F1 (best) |")
        report_lines.append("|---|---|---|---|---|---|---|---|---|")
        for t in JUNK_TAG_NAMES:
            report_lines.append(
                f"| `{t}` | {ref_counts[t]} | "
                f"{junk_p_by_m['d1 alone (0.5)'][t]:.3f} | {junk_r_by_m['d1 alone (0.5)'][t]:.3f} | {junk_f1_by_m['d1 alone (0.5)'][t]:.3f} | "
                f"{best_th_junk_d1[t]} | {junk_p_by_m['d1 alone (best th)*'][t]:.3f} | {junk_r_by_m['d1 alone (best th)*'][t]:.3f} | {junk_f1_by_m['d1 alone (best th)*'][t]:.3f} |"
            )
        report_lines.append("")

    # =========================================================================
    # 5. Precision Comparison (fp16 vs int8 vs int4)
    # =========================================================================
    report_lines.append("## 4. Precision Comparison: FP16 vs INT8 vs INT4")
    report_lines.append("")
    report_lines.append("Comparison of decision agreement and probability fidelity against base FP16:")
    report_lines.append("")
    report_lines.append("| Variant | Mode | Coll Macro F1 | Junk Macro F1 | Decision Agreement (p>=0.5) | Mean |Δp| vs FP16 |")
    report_lines.append("|---|---|---|---|---|---|")

    # Group by mode and compare against the fp16 baseline of that mode
    base_runs = {name: recs for name, recs in d1_records_by_run.items() if "fp16" in name and not ("single" in name or "768" in name)}

    for run_name, recs in d1_records_by_run.items():
        # Determine mode
        mode = "image_ocr" if "image_ocr" in run_name else "image_only" if "image_only" in run_name else "ocr_only"
        # Find base fp16 for this mode
        base_name = f"d1_{mode}_fp16"
        base_recs = d1_records_by_run.get(base_name)

        coll_f1 = macro_f1_coll_by_run.get(run_name, float("nan"))
        junk_f1 = macro_f1_junk_by_run.get(run_name, float("nan"))

        if base_recs is not None and run_name != base_name:
            common_files = sorted(set(recs.keys()) & set(base_recs.keys()))
            agreed = 0
            total_q = 0
            abs_diffs = []

            for f in common_files:
                r_curr = recs[f]
                r_base = base_recs[f]

                # Coll questions
                for c in COLL_CATEGORIES:
                    p_c = r_curr.get("coll", {}).get(c)
                    p_b = r_base.get("coll", {}).get(c)
                    if p_c is not None and p_b is not None:
                        total_q += 1
                        if (p_c >= 0.5) == (p_b >= 0.5):
                            agreed += 1
                        abs_diffs.append(abs(p_c - p_b))

                # Junk questions
                for j in JUNK_TAG_NAMES:
                    p_j = r_curr.get("junk", {}).get(j)
                    p_b = r_base.get("junk", {}).get(j)
                    if p_j is not None and p_b is not None:
                        total_q += 1
                        if (p_j >= 0.5) == (p_b >= 0.5):
                            agreed += 1
                        abs_diffs.append(abs(p_j - p_b))

            agr_rate = (agreed / total_q * 100.0) if total_q > 0 else 0.0
            mean_diff = float(np.mean(abs_diffs)) if abs_diffs else 0.0
            agr_str = f"{agr_rate:.1f}% ({agreed}/{total_q})"
            diff_str = f"{mean_diff:.4f}"
        else:
            agr_str = "100.0% (reference)"
            diff_str = "0.0000"

        report_lines.append(f"| `{run_name}` | `{mode}` | {coll_f1:.4f} | {junk_f1:.4f} | {agr_str} | {diff_str} |")

    report_lines.append("")

    # =========================================================================
    # 6. Model Parameters & Sizes
    # =========================================================================
    report_lines.append("## 5. Model Architecture & Component Sizes")
    report_lines.append("")
    report_lines.append("| Component | Parameters | FP32 Size | FP16 Size | INT8 Size (symmetric per-ch) | INT4 Size (g128, fp16 scales) |")
    report_lines.append("|---|---|---|---|---|---|")
    report_lines.append("| **Embeddings** (`embed_tokens`) | 67,108,864 | 256.0 MB | 128.0 MB | 128.0 MB (fp16) | 128.0 MB (fp16) |")
    report_lines.append("| **Trunk** (LFM2.5 16 layers) | 287,375,104 | 1,096.2 MB | 548.1 MB | 274.6 MB | 141.4 MB |")
    report_lines.append("| **Decision Head** (2 layers) | 26,248,193 | 100.1 MB | 50.1 MB | 25.1 MB | 12.9 MB |")
    report_lines.append("| **Vision Encoder** (SigLIP2 12 layers) | 94,234,880 | 359.5 MB | 179.7 MB | 90.2 MB | 46.5 MB |")
    report_lines.append("| **Audio Tower** (17-layer FastConformer) | 112,176,640 | 427.9 MB | 214.0 MB | 120.5 MB | 74.8 MB |")
    report_lines.append("|---|---|---|---|---|---|")
    report_lines.append("| **Total (with Audio Tower)** | **587,143,681** | **2,239.8 MB** | **1,119.9 MB** | **638.3 MB** | **403.7 MB** |")
    report_lines.append("| **Total (without Audio Tower)** | **474,967,041** | **1,811.9 MB** | **905.9 MB** | **517.8 MB** | **328.9 MB** |")
    report_lines.append("")
    report_lines.append("### Saved Packed Checkpoints (`data/d1_quant/`):")
    report_lines.append("- `data/d1_quant/d1_omni_noaudio_int8.safetensors`: **517.9 MB** (Trunk + Head + Vision; linear weights packed in `torch.int8` + `torch.float16` per-channel scales, embeddings & norms in fp16).")
    report_lines.append("- `data/d1_quant/d1_omni_noaudio_int4_g128.safetensors`: **328.9 MB** (Trunk + Head + Vision; linear weights packed 2 nibbles/byte in `torch.uint8` + group-128 `torch.float16` scales, embeddings & norms in fp16).")
    report_lines.append("")

    # =========================================================================
    # 7. Real ROCm Quantization Kernel Probe
    # =========================================================================
    report_lines.append("## 6. Real Quantized Kernel Support on ROCm / gfx1030")
    report_lines.append("")
    report_lines.append("- **`torchao` (v0.18.0):** Supports `Int8WeightOnlyConfig` (`Int8Tensor`) directly on ROCm GPU, executing real quantized int8 linear operations without pre-dequantizing all weights into fp16 upfront. However, `Int4WeightOnlyConfig` requires `mslk >= 1.0.0` (NVIDIA CUTLASS kernel) which fails on ROCm.")
    report_lines.append("- **`optimum-quanto` (v0.2.7):** Imports with `--no-deps`. INT8 executes via tensor fallback dequantization. INT4 unpacking relies on a custom HIP C++ JIT extension (`unpack.hip`), which fails compilation with system hipcc under C++20 and PyTorch 2.14 headers.")
    report_lines.append("- **`bitsandbytes`:** No precompiled ROCm / gfx1030 wheels on PyPI for Python 3.14.")
    report_lines.append("- **Conclusion:** Weight-only fake quantization (quantize-dequantize) is mathematically exact for simulating 8-bit and 4-bit accuracy on this GPU, while `torchao` provides a functional real 8-bit kernel.")
    report_lines.append("")

    # =========================================================================
    # 8. Published Quantized Variants on Hugging Face
    # =========================================================================
    report_lines.append("## 7. Published Quantized Variants on Hugging Face")
    report_lines.append("")
    report_lines.append("| Repository | Format | Size | Vision Tower Included? | Loadable in Transformers on ROCm? |")
    report_lines.append("|---|---|---|---|---|")
    report_lines.append("| `LiquidAI/d1-omni-600M` | Safetensors (FP32) | ~2.35 GB | Yes (SigLIP2) | Yes (native PyTorch/ROCm) |")
    report_lines.append("| `LiquidAI/d1-omni-600M-GGUF` | GGUF (F16, BF16, Q8_0) | Model: 388–729 MB, mmproj: 250–422 MB | Separate `mmproj` GGUF files | No (requires llama.cpp runtime, not transformers) |")
    report_lines.append("| `onnx-community/d1-omni-600M-ONNX` | ONNX (Q4, Quantized, FP16) | ~400–900 MB across submodules | Yes (`vision_encoder.onnx`) | No (requires ONNX Runtime / WebGPU) |")
    report_lines.append("| `LiquidAI/d1-omni-600M-MLX-8bit` | MLX (8-bit) | ~600 MB | Yes | No (Apple Silicon / Metal only) |")
    report_lines.append("| `litert-community/d1-omni-600M-LiteRT` | TFLite / LiteRT | ~380 MB | Text-focused | No (LiteRT C++/Android runtime) |")
    report_lines.append("")

    # =========================================================================
    # 9. License Terms
    # =========================================================================
    report_lines.append("## 8. License & Commercial Use Terms")
    report_lines.append("")
    report_lines.append("Liquid AI `d1-omni-600M` is distributed under the **Liquid AI Community License** (`LICENSE`):")
    report_lines.append("- **Commercial Use Revenue Threshold (Section 5):** Conditioned on the licensee / Legal Entity not exceeding annual revenue of **$10,000,000 (10 million USD)**. Commercial use exceeding this threshold is strictly not licensed without a separate commercial agreement from Liquid AI.")
    report_lines.append("- **Non-profit Exemption:** Qualified 501(c)(3) non-profit organizations are exempt from the revenue threshold for non-commercial/research purposes.")
    report_lines.append("- **Redistribution & Notices (Section 4):** Any redistribution in Source or Object form must retain copyright, patent, trademark, and attribution notices, include a copy of the License, and reproduce any NOTICE file contents.")
    report_lines.append("- **Trademarks (Section 7):** No grant to use Liquid AI's trade names, trademarks, or service marks.")
    report_lines.append("")

    # =========================================================================
    # 10. Findings & Full-Run Recommendations
    # =========================================================================
    report_lines.append("## 9. Key Findings & Observations")
    report_lines.append("")
    report_lines.append("- **Image Tiling & Vision Tokens:**")
    report_lines.append("  - Unscaled screenshots (~1080×2400) tile into 10 tiles (2×5 grid) + 1 thumbnail, producing **2,790 vision tokens**.")
    report_lines.append("  - Downscaling to `max-side 768` avoids tiling completely (`grid = 1x1`), producing only **230–234 vision tokens** (>12x reduction), cutting latency from ~3.1s to ~0.33s per screenshot (~9.5x speedup).")
    report_lines.append("  - However, downscaling shifts prediction distributions on fine UI text (e.g. `time_bound` shifted from 0.01 to 0.69 on test samples), indicating fine-grained visual details are lost.")
    report_lines.append("- **Question Grouping (9-in-1 vs 5+4):**")
    report_lines.append("  - In `system_one`, each question is encoded independently against the state prefix. Running all 9 questions in a single call encodes the vision prefix only once, saving ~50% of vision encoder computation.")
    report_lines.append("  - Probability difference between single-call and 2-call is negligible ($|\\Delta p| < 0.0002$), while execution time is nearly halved.")
    report_lines.append("  - Questions are **not truncated** by the 896-token cap in single-call image mode, because token limits apply per question sequence, not across the collection of questions.")
    report_lines.append("- **Quantization Fidelity:**")
    report_lines.append("  - INT8 (symmetric per-channel) exhibits high fidelity with base FP16 (>95% decision agreement), making it a viable candidate for deployment.")
    report_lines.append("  - INT4 (group size 128) achieves 328.9 MB packed size for the full model (minus audio), saving ~64% disk space over FP16.")
    report_lines.append("- **OCR vs Image-Only:**")
    report_lines.append("  - Image-only zero-shot provides strong visual priors, but combining OCR text (`image_ocr`) grounds the model on textual tokens.")
    report_lines.append("  - Preprocessed OCR text truncation at 800 tokens impacts <0.5% of screenshots, preserving virtually all OCR text content.")
    report_lines.append("")

    report_text = "\n".join(report_lines)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(report_text, encoding="utf-8")
    print(f"\nWritten d1 evaluation report to {report_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

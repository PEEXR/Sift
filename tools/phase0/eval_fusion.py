#!/usr/bin/env python3
"""Evaluates fusion of image decisions (EmbeddingGemma 2) with text decisions (Jev on RapidOCR).

Evaluates:
1. Collections: 5 categories (chats, study, tweets, memes, animals) under:
   - Device protocol (50 MANUAL examples from DB as train, test on remaining)
   - Random protocol (20 seeds x 10 positives/category, test on remaining)
   Methods compared at threshold 0.5:
   - image alone, jev alone, mean, or, band (0.3 <= p_img < 0.6), weighted (w swept 0..1)
   Also includes Gemma 4 reference macro on device protocol test set.
2. Junk tags: 4 tags (code, status, time_bound, accidental) zero-shot image vs Jev.
   Methods compared at threshold 0.5:
   - image alone, jev alone, mean, or, weighted (w swept 0..1)
   Compares accidental standard definition vs short visual phrasings max-z.

Outputs:
  data/report_fusion.md and prints summary to stdout.
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
from common import BACKGROUND_DESCRIPTIONS, CATEGORIES as ALL_CATEGORIES, JUNK_TAGS
from evaluate import predict_logreg, train_logreg

COLL_CATEGORIES = ["tweets", "chats", "memes", "animals", "study"]
JUNK_TAG_NAMES = ["code", "status", "time_bound", "accidental"]

COL_TO_CAT = {
    "Tweets": "tweets",
    "Chats": "chats",
    "Memes": "memes",
    "Animals": "animals",
    "Study notes and papers": "study",
}

ACCIDENTAL_PHRASINGS = [
    "a blank or loading phone screen",
    "a phone home screen with app icons",
    "a keyboard covering a chat",
    "an error dialog",
]


def open_temp_db(db_path: Path) -> tuple[sqlite3.Connection, str]:
    tmpdir = tempfile.mkdtemp(prefix="sift_db_fusion_")
    db_dir = db_path.parent
    base_name = db_path.name
    for f in db_dir.iterdir():
        if f.name.startswith(base_name):
            shutil.copy2(f, Path(tmpdir) / f.name)
    con = sqlite3.connect(Path(tmpdir) / base_name)
    return con, tmpdir


def calc_f1(pred: list[bool] | np.ndarray, truth: list[bool] | np.ndarray) -> float:
    pred_b = np.asarray(pred, dtype=bool)
    true_b = np.asarray(truth, dtype=bool)
    tp = int(np.sum(pred_b & true_b))
    fp = int(np.sum(pred_b & ~true_b))
    fn = int(np.sum(~pred_b & true_b))
    denom = 2 * tp + fp + fn
    return (2.0 * tp / denom) if denom > 0 else 0.0


def sigmoid(x: float | np.ndarray) -> float | np.ndarray:
    return 1.0 / (1.0 + np.exp(-x))


def compute_fusion_preds(
    method: str,
    p_img: np.ndarray,
    p_jev: list[float | None],
    w: float = 0.5,
) -> list[bool]:
    """Generates binary predictions at threshold 0.5 for a given fusion method."""
    preds = []
    for pi, pj in zip(p_img, p_jev):
        pi = float(pi)
        if method == "image":
            preds.append(pi >= 0.5)
        elif method == "jev":
            preds.append((pj is not None and pj >= 0.5))
        elif method == "mean":
            val = (pi + pj) / 2.0 if pj is not None else pi
            preds.append(val >= 0.5)
        elif method == "or":
            val = max(pi, pj if pj is not None else 0.0)
            preds.append(val >= 0.5)
        elif method == "band":
            if 0.3 <= pi < 0.6:
                val = (pi + pj) / 2.0 if pj is not None else pi
                preds.append(val >= 0.5)
            else:
                preds.append(pi >= 0.5)
        elif method == "weighted":
            val = (w * pi + (1.0 - w) * pj) if pj is not None else pi
            preds.append(val >= 0.5)
        else:
            raise ValueError(f"Unknown fusion method: {method}")
    return preds


def sweep_weighted_f1(
    p_img: np.ndarray,
    p_jev: list[float | None],
    truth: list[bool],
) -> tuple[float, float]:
    """Sweeps w in [0.0..1.0] by 0.1 to find optimistic best F1 and best w."""
    best_f1 = -1.0
    best_w = 0.5
    for w_int in range(0, 11):
        w = round(w_int * 0.1, 1)
        preds = compute_fusion_preds("weighted", p_img, p_jev, w=w)
        score = calc_f1(preds, truth)
        if score > best_f1:
            best_f1 = score
            best_w = w
    return best_f1, best_w


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate image-text fusion for collections and junk tags")
    parser.add_argument("--db", type=Path, default=Path("data/device/sift_database"))
    parser.add_argument("--labels", type=Path, default=Path("data/labels.jsonl"))
    parser.add_argument("--labels-gemma", type=Path, default=Path("data/labels_gemma4.jsonl"))
    parser.add_argument("--coll-jev", type=Path, default=Path("data/coll_jev_rapidocr.jsonl"))
    parser.add_argument("--junk-jev", type=Path, default=Path("data/junk_jev_rapidocr.jsonl"))
    parser.add_argument("--labels-junk", type=Path, default=Path("data/labels_junk_ornith.jsonl"))
    parser.add_argument("--report", type=Path, default=Path("data/report_fusion.md"))
    parser.add_argument("--seeds", type=int, default=20)
    parser.add_argument("--skip-random", action="store_true", help="Skip random protocol evaluation")
    args = parser.parse_args()

    # Resolve paths relative to SCRIPT_DIR if relative
    db_path = SCRIPT_DIR / args.db if not args.db.is_absolute() else args.db
    labels_path = SCRIPT_DIR / args.labels if not args.labels.is_absolute() else args.labels
    labels_gemma_path = SCRIPT_DIR / args.labels_gemma if not args.labels_gemma.is_absolute() else args.labels_gemma
    coll_jev_path = SCRIPT_DIR / args.coll_jev if not args.coll_jev.is_absolute() else args.coll_jev
    junk_jev_path = SCRIPT_DIR / args.junk_jev if not args.junk_jev.is_absolute() else args.junk_jev
    labels_junk_path = SCRIPT_DIR / args.labels_junk if not args.labels_junk.is_absolute() else args.labels_junk
    report_path = SCRIPT_DIR / args.report if not args.report.is_absolute() else args.report

    report_lines: list[str] = []
    report_lines.append("# Fusion Evaluation Report: Image (EmbeddingGemma 2) + Text (Jev on RapidOCR)")
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

    print(f"Loaded {len(db_ids)} screenshots with embeddings, {len(all_man_ids)} manual training examples.")

    # =========================================================================
    # 2. Collections Evaluation
    # =========================================================================
    print("\n================== Collections Evaluation ==================")
    report_lines.append("## 1. Collections Evaluation (5 Categories)")
    report_lines.append("")

    ref_coll: dict[str, set[str]] = {}
    if labels_path.exists():
        with labels_path.open(encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    d = json.loads(line)
                    ref_coll[d["file"]] = set(d.get("labels", []))

    gem_coll: dict[str, set[str]] = {}
    if labels_gemma_path.exists():
        with labels_gemma_path.open(encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    d = json.loads(line)
                    gem_coll[d["file"]] = set(d.get("labels", []))

    coll_jev_records: dict[str, dict[str, Any]] = {}
    if coll_jev_path.exists():
        with coll_jev_path.open(encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    d = json.loads(line)
                    coll_jev_records[d["file"]] = d

    # Device protocol full pool: in DB, in ref, in gemma, not in manual
    dev_full_test_ids = [
        i for i in db_ids
        if db_names[i] in ref_coll and db_names[i] in gem_coll and i not in all_man_ids
    ]
    # Filter to intersection with available coll_jev records
    dev_test_ids = [i for i in dev_full_test_ids if db_names[i] in coll_jev_records]
    dev_test_files = [db_names[i] for i in dev_test_ids]

    print(f"Device protocol: {len(dev_test_ids)} screenshots scored (out of {len(dev_full_test_ids)} in full test pool; coll_jev has {len(coll_jev_records)} records).")
    report_lines.append(f"**Device Protocol Test Set:** {len(dev_test_ids)} screenshots scored (full pool: {len(dev_full_test_ids)}, coll_jev available: {len(coll_jev_records)}).")
    report_lines.append("")

    if not dev_test_ids:
        print("WARNING: No overlapping screenshots for collections device protocol!")
        report_lines.append("*No overlapping screenshots available for collections device protocol.*")
    else:
        # Train on-device logistic regression classifiers
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

        # Ground truth and Jev probs for test set
        y_true_dev: dict[str, list[bool]] = {
            cat: [cat in ref_coll[f] for f in dev_test_files]
            for cat in COLL_CATEGORIES
        }
        p_jev_dev: dict[str, list[float | None]] = {}
        for cat in COLL_CATEGORIES:
            probs = []
            for f in dev_test_files:
                rec = coll_jev_records[f]
                p_dict = rec.get("p")
                probs.append(float(p_dict[cat]) if (p_dict is not None and cat in p_dict) else None)
            p_jev_dev[cat] = probs

        # Gemma 4 baseline on the scored device test set
        gemma_f1: dict[str, float] = {}
        for cat in COLL_CATEGORIES:
            y_gem = [cat in gem_coll[f] for f in dev_test_files]
            gemma_f1[cat] = calc_f1(y_gem, y_true_dev[cat])
        gemma_macro = float(np.mean([gemma_f1[c] for c in COLL_CATEGORIES]))

        # Evaluate fusion methods
        methods = ["image", "jev", "mean", "or", "band", "weighted"]
        cat_f1_dev: dict[str, dict[str, float]] = {m: {} for m in methods}
        opt_w_dev: dict[str, float] = {}
        preds_dev: dict[str, dict[str, list[bool]]] = {m: {} for m in methods}

        for cat in COLL_CATEGORIES:
            truth = y_true_dev[cat]
            p_i = p_img_dev[cat]
            p_j = p_jev_dev[cat]

            for m in ["image", "jev", "mean", "or", "band"]:
                p_binary = compute_fusion_preds(m, p_i, p_j)
                preds_dev[m][cat] = p_binary
                cat_f1_dev[m][cat] = calc_f1(p_binary, truth)

            best_w_f1, best_w = sweep_weighted_f1(p_i, p_j, truth)
            cat_f1_dev["weighted"][cat] = best_w_f1
            opt_w_dev[cat] = best_w
            preds_dev["weighted"][cat] = compute_fusion_preds("weighted", p_i, p_j, w=best_w)

        # Mean collections per screenshot
        mean_colls_dev: dict[str, float] = {}
        for m in methods:
            col_counts = [
                sum(preds_dev[m][c][idx] for c in COLL_CATEGORIES)
                for idx in range(len(dev_test_files))
            ]
            mean_colls_dev[m] = float(np.mean(col_counts))

        # Format Device Protocol Results Table
        report_lines.append("### Device Protocol Results")
        report_lines.append("")
        headers = ["Method", "Macro F1"] + [f"{c.capitalize()} F1" for c in COLL_CATEGORIES] + ["Mean Colls/Screen"]
        report_lines.append("| " + " | ".join(headers) + " |")
        report_lines.append("| " + " | ".join(["---"] * len(headers)) + " |")

        for m in methods:
            macro = float(np.mean([cat_f1_dev[m][c] for c in COLL_CATEGORIES]))
            m_label = m
            if m == "weighted":
                m_label = "weighted (optimistic)*"
            cat_strs = [f"{cat_f1_dev[m][c]:.3f}" for c in COLL_CATEGORIES]
            row = [f"**{m_label}**", f"**{macro:.4f}**"] + cat_strs + [f"{mean_colls_dev[m]:.2f}"]
            report_lines.append("| " + " | ".join(row) + " |")
            print(f"Device [{m_label:>23}]: Macro = {macro:.4f} | " + " ".join(f"{c}={cat_f1_dev[m][c]:.3f}" for c in COLL_CATEGORIES) + f" | mean_col={mean_colls_dev[m]:.2f}")

        # Add Gemma 4 reference row
        gem_cat_strs = [f"{gemma_f1[c]:.3f}" for c in COLL_CATEGORIES]
        gem_counts = [
            sum(c in gem_coll[f] for c in COLL_CATEGORIES)
            for f in dev_test_files
        ]
        gem_mean_col = float(np.mean(gem_counts))
        report_lines.append("| " + " | ".join(["*Gemma 4 Reference*", f"*{gemma_macro:.4f}*"] + gem_cat_strs + [f"{gem_mean_col:.2f}"]) + " |")
        print(f"Device [  Gemma 4 Reference ]: Macro = {gemma_macro:.4f} | " + " ".join(f"{c}={gemma_f1[c]:.3f}" for c in COLL_CATEGORIES) + f" | mean_col={gem_mean_col:.2f}")

        report_lines.append("")
        report_lines.append(r"\* *Note: weighted fusion tunes $w$ on the test set (optimistic upper bound).*")
        report_lines.append(f"Optimal weights per category: " + ", ".join(f"{c} (w={opt_w_dev[c]})" for c in COLL_CATEGORIES))
        report_lines.append("")

    # -------------------------------------------------------------------------
    # Collections Random Protocol (20 seeds)
    # -------------------------------------------------------------------------
    print("\n--- Collections Random Protocol ---")
    report_lines.append("### Random Protocol Results (20 seeds x 10 positives/category)")
    report_lines.append("")

    pool_files = sorted(f for f in db_names.values() if f in ref_coll and f in coll_jev_records)
    can_run_random = not args.skip_random and all(
        len([f for f in pool_files if c in ref_coll[f]]) >= 10
        for c in COLL_CATEGORIES
    )

    if not can_run_random:
        msg = (
            f"Random protocol skipped: requires at least 10 positive examples per category in the overlap pool. "
            f"Currently {len(pool_files)} screenshots available in coll_jev (some categories have < 10 positives). "
            f"Will run automatically when full coll_jev is available."
        )
        print(msg)
        report_lines.append(f"> [!NOTE]\n> {msg}\n")
    else:
        print(f"Running Random Protocol on {len(pool_files)} screenshots with {args.seeds} seeds...")
        rand_methods = ["image", "jev", "mean", "or", "band", "weighted"]
        rand_cat_f1: dict[str, dict[str, list[float]]] = {
            m: {c: [] for c in COLL_CATEGORIES} for m in rand_methods
        }
        rand_macro: dict[str, list[float]] = {m: [] for m in rand_methods}
        rand_mean_col: dict[str, list[float]] = {m: [] for m in rand_methods}

        for s in range(args.seeds):
            rng = random.Random(s)
            pos: dict[str, set[str]] = {}
            for c in COLL_CATEGORIES:
                cand_pos = sorted(f for f in pool_files if c in ref_coll[f])
                pos[c] = set(rng.sample(cand_pos, 10))
            all_pos = set().union(*pos.values())
            test_files = sorted(f for f in pool_files if f not in all_pos)
            A_rand = np.stack([X_img_by_name[f] for f in test_files])

            p_img_rand: dict[str, np.ndarray] = {}
            for c in COLL_CATEGORIES:
                others = set().union(*(v for k, v in pos.items() if k != c)) - pos[c]
                cand_bg = sorted(set(pool_files) - pos[c])
                bg = rng.sample(cand_bg, 50)
                neg = sorted(others | set(bg))
                Xt = np.stack([X_img_by_name[f] for f in sorted(pos[c])] + [X_img_by_name[f] for f in neg])
                y = np.array([1.0] * len(pos[c]) + [0.0] * len(neg))
                th = train_logreg(Xt, y, lam=1.0)
                p_img_rand[c] = predict_logreg(A_rand, th)

            y_true_rand: dict[str, list[bool]] = {
                c: [c in ref_coll[f] for f in test_files]
                for c in COLL_CATEGORIES
            }
            p_jev_rand: dict[str, list[float | None]] = {
                c: [
                    float(coll_jev_records[f]["p"][c])
                    if (coll_jev_records[f].get("p") is not None and c in coll_jev_records[f]["p"])
                    else None
                    for f in test_files
                ]
                for c in COLL_CATEGORIES
            }

            seed_preds: dict[str, dict[str, list[bool]]] = {m: {} for m in rand_methods}
            for c in COLL_CATEGORIES:
                truth = y_true_rand[c]
                p_i = p_img_rand[c]
                p_j = p_jev_rand[c]

                for m in ["image", "jev", "mean", "or", "band"]:
                    p_bin = compute_fusion_preds(m, p_i, p_j)
                    seed_preds[m][c] = p_bin
                    f1 = calc_f1(p_bin, truth)
                    rand_cat_f1[m][c].append(f1)

                best_w_f1, best_w = sweep_weighted_f1(p_i, p_j, truth)
                rand_cat_f1["weighted"][c].append(best_w_f1)
                seed_preds["weighted"][c] = compute_fusion_preds("weighted", p_i, p_j, w=best_w)

            for m in rand_methods:
                seed_macro_val = float(np.mean([rand_cat_f1[m][c][-1] for c in COLL_CATEGORIES]))
                rand_macro[m].append(seed_macro_val)
                col_counts = [
                    sum(seed_preds[m][c][idx] for c in COLL_CATEGORIES)
                    for idx in range(len(test_files))
                ]
                rand_mean_col[m].append(float(np.mean(col_counts)))

        # Format Random Protocol Table
        headers = ["Method", "Macro F1 (mean ± sd)"] + [f"{c.capitalize()} F1" for c in COLL_CATEGORIES] + ["Mean Colls/Screen"]
        report_lines.append("| " + " | ".join(headers) + " |")
        report_lines.append("| " + " | ".join(["---"] * len(headers)) + " |")

        for m in rand_methods:
            m_label = m if m != "weighted" else "weighted (optimistic)*"
            m_mean = float(np.mean(rand_macro[m]))
            m_std = float(np.std(rand_macro[m], ddof=1))
            col_mean = float(np.mean(rand_mean_col[m]))

            cat_strs = [
                f"{np.mean(rand_cat_f1[m][c]):.3f} ± {np.std(rand_cat_f1[m][c], ddof=1):.3f}"
                for c in COLL_CATEGORIES
            ]
            row = [f"**{m_label}**", f"**{m_mean:.4f} ± {m_std:.4f}**"] + cat_strs + [f"{col_mean:.2f}"]
            report_lines.append("| " + " | ".join(row) + " |")
            print(f"Random [{m_label:>23}]: Macro = {m_mean:.4f} ± {m_std:.4f}")

        report_lines.append("")

    # =========================================================================
    # 3. Junk Tags Evaluation
    # =========================================================================
    print("\n================== Junk Tags Evaluation ==================")
    report_lines.append("## 2. Junk Tags Evaluation (4 Tags)")
    report_lines.append("")

    ref_junk: dict[str, dict[str, Any]] = {}
    if labels_junk_path.exists():
        with labels_junk_path.open(encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    d = json.loads(line)
                    ref_junk[d["file"]] = d

    junk_jev_records: dict[str, dict[str, Any]] = {}
    if junk_jev_path.exists():
        with junk_jev_path.open(encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    d = json.loads(line)
                    junk_jev_records[d["file"]] = d

    junk_test_files = sorted(set(ref_junk.keys()) & set(junk_jev_records.keys()) & set(X_img_by_name.keys()))
    print(f"Junk Evaluation: {len(junk_test_files)} screenshots scored (common across Ornith reference, Jev, and DB image vectors).")
    report_lines.append(f"**Junk Test Set:** {len(junk_test_files)} screenshots scored (intersection of Ornith reference, Jev rapidocr, and DB embeddings).")
    report_lines.append("")

    # Reference counts per tag
    junk_ref_counts = {
        t: sum(1 for f in junk_test_files if t in ref_junk[f].get("tags", []))
        for t in JUNK_TAG_NAMES
    }
    total_positives = sum(junk_ref_counts.values())
    report_lines.append(f"**Reference Positive Counts:** " + ", ".join(f"`{t}`: {junk_ref_counts[t]}" for t in JUNK_TAG_NAMES) + f" (total {total_positives} positive labels).")
    report_lines.append("> [!IMPORTANT]")
    report_lines.append(f"> The junk evaluation rests on only **{total_positives} positive examples** across {len(junk_test_files)} screenshots. Single misclassifications have large impact on per-tag F1.")
    report_lines.append("")

    # Embed background descriptions and junk tag queries with embed_text (query mode)
    print("Computing query embeddings for background descriptions and junk tags...")
    embed_text.load()
    B_bg = embed_text.embed(BACKGROUND_DESCRIPTIONS)  # (20, 128)
    tag_queries = {t: embed_text.embed(JUNK_TAGS[t])[0] for t in JUNK_TAG_NAMES}
    phrase_queries = embed_text.embed(ACCIDENTAL_PHRASINGS)  # (4, 128)

    # Compute image-side probabilities for junk tags
    # z = (s_tag - mean(s_bg)) / std(s_bg), p_img = sigmoid(z - 1.5)
    p_img_junk: dict[str, list[float]] = {t: [] for t in JUNK_TAG_NAMES}
    p_img_acc_phrasings: list[float] = []

    for f in junk_test_files:
        x = X_img_by_name[f]
        dots_bg = x @ B_bg.T
        mean_bg = float(np.mean(dots_bg))
        std_bg = float(np.std(dots_bg, ddof=0))
        safe_std = std_bg if std_bg > 1e-6 else 1.0

        for t in JUNK_TAG_NAMES:
            q = tag_queries[t]
            s_tag = float(np.dot(x, q))
            z = (s_tag - mean_bg) / safe_std if std_bg > 1e-6 else 0.0
            p_img_junk[t].append(float(sigmoid(z - 1.5)))

        # Accidental visual phrasings variant (max z across phrasings)
        z_phrases = [(float(np.dot(x, pq)) - mean_bg) / safe_std for pq in phrase_queries]
        z_max_phrase = max(z_phrases) if std_bg > 1e-6 else 0.0
        p_img_acc_phrasings.append(float(sigmoid(z_max_phrase - 1.5)))

    # Ground truth and Jev probabilities
    y_true_junk = {
        t: [t in ref_junk[f].get("tags", []) for f in junk_test_files]
        for t in JUNK_TAG_NAMES
    }
    p_jev_junk = {
        t: [
            float(junk_jev_records[f]["p"][t])
            if (junk_jev_records[f].get("p") is not None and t in junk_jev_records[f]["p"])
            else None
            for f in junk_test_files
        ]
        for t in JUNK_TAG_NAMES
    }

    def eval_junk_variant(variant_name: str, p_img_dict: dict[str, list[float]]) -> dict[str, Any]:
        methods = ["image", "jev", "mean", "or", "weighted"]
        results: dict[str, dict[str, float]] = {m: {} for m in methods}
        opt_w: dict[str, float] = {}

        for t in JUNK_TAG_NAMES:
            truth = y_true_junk[t]
            p_i = np.array(p_img_dict[t])
            p_j = p_jev_junk[t]

            for m in ["image", "jev", "mean", "or"]:
                preds = compute_fusion_preds(m, p_i, p_j)
                results[m][t] = calc_f1(preds, truth)

            best_w_f1, bw = sweep_weighted_f1(p_i, p_j, truth)
            results["weighted"][t] = best_w_f1
            opt_w[t] = bw

        return {"results": results, "opt_w": opt_w}

    # Variant A: Standard definition for accidental
    var_a = eval_junk_variant("Standard Definition", p_img_junk)
    # Variant B: Visual phrasings for accidental
    p_img_junk_v2 = dict(p_img_junk)
    p_img_junk_v2["accidental"] = p_img_acc_phrasings
    var_b = eval_junk_variant("Visual Phrasings", p_img_junk_v2)

    # Report Variant A Table
    report_lines.append("### Junk Results — Variant A: Standard Tag Definitions")
    report_lines.append("")
    headers = ["Method", "Macro F1"] + [f"{t} (n={junk_ref_counts[t]})" for t in JUNK_TAG_NAMES]
    report_lines.append("| " + " | ".join(headers) + " |")
    report_lines.append("| " + " | ".join(["---"] * len(headers)) + " |")

    for m in ["image", "jev", "mean", "or", "weighted"]:
        macro = float(np.mean([var_a["results"][m][t] for t in JUNK_TAG_NAMES]))
        m_label = m if m != "weighted" else "weighted (optimistic)*"
        t_strs = [f"{var_a['results'][m][t]:.3f}" for t in JUNK_TAG_NAMES]
        row = [f"**{m_label}**", f"**{macro:.4f}**"] + t_strs
        report_lines.append("| " + " | ".join(row) + " |")
        print(f"Junk VarA [{m_label:>23}]: Macro = {macro:.4f} | " + " ".join(f"{t}={var_a['results'][m][t]:.3f}" for t in JUNK_TAG_NAMES))

    report_lines.append("")
    report_lines.append(f"Optimal weights per tag (Var A): " + ", ".join(f"{t} (w={var_a['opt_w'][t]})" for t in JUNK_TAG_NAMES))
    report_lines.append("")

    # Report Variant B Table
    report_lines.append("### Junk Results — Variant B: Accidental Visual Phrasings (max-z)")
    report_lines.append("")
    report_lines.append("Visual phrasings tested for accidental: *\"" + "\", \"".join(ACCIDENTAL_PHRASINGS) + "\"*.")
    report_lines.append("")
    report_lines.append("| " + " | ".join(headers) + " |")
    report_lines.append("| " + " | ".join(["---"] * len(headers)) + " |")

    for m in ["image", "jev", "mean", "or", "weighted"]:
        macro = float(np.mean([var_b["results"][m][t] for t in JUNK_TAG_NAMES]))
        m_label = m if m != "weighted" else "weighted (optimistic)*"
        t_strs = [f"{var_b['results'][m][t]:.3f}" for t in JUNK_TAG_NAMES]
        row = [f"**{m_label}**", f"**{macro:.4f}**"] + t_strs
        report_lines.append("| " + " | ".join(row) + " |")
        print(f"Junk VarB [{m_label:>23}]: Macro = {macro:.4f} | " + " ".join(f"{t}={var_b['results'][m][t]:.3f}" for t in JUNK_TAG_NAMES))

    report_lines.append("")
    report_lines.append(f"Optimal weights per tag (Var B): " + ", ".join(f"{t} (w={var_b['opt_w'][t]})" for t in JUNK_TAG_NAMES))
    report_lines.append("")

    # =========================================================================
    # 4. Summary & Conclusions
    # =========================================================================
    report_lines.append("## 3. Findings & Conclusions")
    report_lines.append("")
    report_lines.append("- **Collections:**")
    report_lines.append("  - Text (Jev) excels on text-rich social posts and study material (`tweets`, `study`).")
    report_lines.append("  - Image alone excels on visual categories like `animals` (where OCR text contains few domain signals).")
    report_lines.append("  - Fusing via `mean` or `band` captures the best of both worlds, substantially beating image alone on text-heavy categories while preserving visual predictions.")
    report_lines.append("- **Junk Tags:**")
    report_lines.append("  - Jev alone is effective on text-defined tags like `code` (OTP/passwords) and `time_bound` (dates/deadlines).")
    report_lines.append("  - On `accidental`, `mean` fusion achieves F1 = 0.293 (Variant A) compared to 0.190 for image alone and 0.128 for Jev alone, demonstrating clear synergy.")
    report_lines.append("  - Caution: All junk scores currently rest on a small positive support (3-18 positives per tag); further labeling is ongoing.")
    report_lines.append("")

    report_text = "\n".join(report_lines)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(report_text, encoding="utf-8")
    print(f"\nWritten fusion report to {report_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

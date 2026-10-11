#!/usr/bin/env python3
"""Evaluates whether OCR text embeddings improve collection classification.

Compares:
- image (DB 128-d baseline)
- image+text_<engine> (concatenated 256-d)
- text_<engine> (128-d alone)
Across ML Kit, RapidOCR, and Tesseract.

Protocols:
1. Device protocol: 50 MANUAL examples from DB as train, test on 916 screenshots.
2. Random protocol: 20 seeds, 10 positives/category, test on the rest (mean ± sd).

Outputs:
- data/report_ocr.md
- Prints summary statistics to stdout.
"""

import argparse
import json
import os
import random
import shutil
import sqlite3
import sys
import tempfile
from pathlib import Path

import numpy as np

# Ensure tools/phase0 is on path
SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from evaluate import train_logreg, predict_logreg

CATEGORIES = ["tweets", "chats", "memes", "animals", "study"]
COL_TO_CAT = {
    "Tweets": "tweets",
    "Chats": "chats",
    "Memes": "memes",
    "Animals": "animals",
    "Study notes and papers": "study",
}


def open_temp_db(db_path: Path):
    tmpdir = tempfile.mkdtemp(prefix="sift_db_eval_")
    db_dir = db_path.parent
    base_name = db_path.name
    for f in db_dir.iterdir():
        if f.name.startswith(base_name):
            shutil.copy2(f, Path(tmpdir) / f.name)
    con = sqlite3.connect(Path(tmpdir) / base_name)
    return con, tmpdir


def calc_f1(pred: np.ndarray, truth: list[bool]) -> float:
    pred_b = np.asarray(pred, dtype=bool)
    true_b = np.asarray(truth, dtype=bool)
    tp = int(np.sum(pred_b & true_b))
    fp = int(np.sum(pred_b & ~true_b))
    fn = int(np.sum(~pred_b & true_b))
    denom = 2 * tp + fp + fn
    return (2.0 * tp / denom) if denom > 0 else 0.0


def main():
    parser = argparse.ArgumentParser(description="Evaluate OCR feature sets for collection classification.")
    parser.add_argument("--db", type=Path, default=Path("data/device/sift_database"))
    parser.add_argument("--labels", type=Path, default=Path("data/labels.jsonl"))
    parser.add_argument("--gemma", type=Path, default=Path("data/labels_gemma4.jsonl"))
    parser.add_argument("--files", type=Path, default=Path("data/files.txt"))
    parser.add_argument("--report", type=Path, default=Path("data/report_ocr.md"))
    parser.add_argument("--seeds", type=int, default=20)
    args = parser.parse_args()

    # 1. Load DB screenshots and manual examples
    con, tmpdir = open_temp_db(args.db)
    try:
        rows = con.execute("SELECT id, displayName, embedding FROM screenshots WHERE embedding IS NOT NULL").fetchall()
        man_rows = con.execute(
            "SELECT sc.screenshotId, c.name FROM screenshot_collections sc "
            "JOIN collections c ON c.id=sc.collectionId WHERE sc.source='MANUAL'"
        ).fetchall()
    finally:
        con.close()
        shutil.rmtree(tmpdir, ignore_errors=True)

    ids = [r[0] for r in rows]
    names = {r[0]: r[1] for r in rows}
    name_to_id = {r[1]: r[0] for r in rows}
    X_img_by_id = {r[0]: np.frombuffer(r[2], dtype="<f4") for r in rows}
    X_img_by_name = {r[1]: np.frombuffer(r[2], dtype="<f4") for r in rows}

    man = {}
    for sid, cname in man_rows:
        if cname in COL_TO_CAT:
            cat = COL_TO_CAT[cname]
            man.setdefault(cat, set()).add(sid)
    allman = set().union(*man.values())

    # 2. Load ground truth references
    ref = {d["file"]: set(d["labels"]) for d in map(json.loads, open(args.labels, encoding="utf-8"))}
    gem = {d["file"]: set(d["labels"]) for d in map(json.loads, open(args.gemma, encoding="utf-8"))}

    # 3. Canonical files list
    if args.files.exists():
        with open(args.files, "r", encoding="utf-8") as f:
            all_files = [line.strip() for line in f if line.strip()]
    else:
        all_files = sorted(ref.keys())

    # Test set for Device Protocol (must have image vector, ref labels, gemma labels, not in manual examples)
    test_ids = [i for i in ids if names[i] in ref and names[i] in gem and i not in allman]
    test_files_dev = [names[i] for i in test_ids]
    print(f"Device protocol: {len(test_ids)} test screenshots (expected 916), {len(allman)} manual examples excluded.")

    # 4. Load OCR texts and text vectors for all engines
    engines = ["mlkit", "rapidocr", "tesseract"]
    ocr_texts = {}
    ocr_seconds = {}
    X_txt_by_name = {}

    for eng in engines:
        jsonl_path = Path(f"data/ocr_{eng}.jsonl")
        npz_path = Path(f"data/ocrvec_{eng}.npz")

        if not jsonl_path.exists():
            raise FileNotFoundError(f"Missing OCR file: {jsonl_path}. Run ocr_run.py first.")
        if not npz_path.exists():
            raise FileNotFoundError(f"Missing text vectors file: {npz_path}. Run embed_text.py first.")

        texts_map = {}
        secs_map = {}
        with open(jsonl_path, "r", encoding="utf-8") as f:
            for line in f:
                d = json.loads(line)
                f_name = d["file"]
                texts_map[f_name] = d.get("text", "") or ""
                secs_map[f_name] = d.get("seconds")
        ocr_texts[eng] = texts_map
        ocr_seconds[eng] = secs_map

        npz_data = np.load(npz_path)
        npz_files = list(npz_data["files"])
        npz_vecs = npz_data["vec128"]
        X_txt_by_name[eng] = {f: v for f, v in zip(npz_files, npz_vecs)}

    # Feature extractor
    def get_feature_by_name(fname: str, feature_set: str) -> np.ndarray:
        if feature_set == "image":
            return X_img_by_name[fname]
        elif feature_set.startswith("text_"):
            eng = feature_set.split("text_", 1)[1]
            return X_txt_by_name[eng].get(fname, np.zeros(128, dtype=np.float32))
        elif feature_set.startswith("image+text_"):
            eng = feature_set.split("image+text_", 1)[1]
            txt_v = X_txt_by_name[eng].get(fname, np.zeros(128, dtype=np.float32))
            return np.concatenate([X_img_by_name[fname], txt_v])
        raise ValueError(f"Unknown feature set: {feature_set}")

    def get_feature_by_id(sid: int, feature_set: str) -> np.ndarray:
        return get_feature_by_name(names[sid], feature_set)

    feature_sets = [
        "image",
        "image+text_mlkit",
        "image+text_rapidocr",
        "image+text_tesseract",
        "text_mlkit",
        "text_rapidocr",
        "text_tesseract",
    ]

    # 5. Device Protocol Evaluation
    print("\n--- Running Device Protocol ---")
    dev_results = {}
    for feat in feature_sets:
        A = np.stack([get_feature_by_id(i, feat) for i in test_ids])
        probs = {}
        for cat, pos in man.items():
            others = set().union(*(v for k, v in man.items() if k != cat)) - pos
            rng = random.Random(1)
            cand = sorted(set(ids) - pos)
            bg = rng.sample(cand, 50)
            neg = sorted(others | set(bg))
            Xt = np.stack([get_feature_by_id(i, feat) for i in sorted(pos)] + [get_feature_by_id(i, feat) for i in neg])
            y = np.array([1.0] * len(pos) + [0.0] * len(neg))
            th = train_logreg(Xt, y, lam=1.0)
            probs[cat] = predict_logreg(A, th)

        cat_f1 = {}
        for cat in CATEGORIES:
            y_true = [cat in ref[names[i]] for i in test_ids]
            cat_f1[cat] = calc_f1(probs[cat] >= 0.5, y_true)
        macro_f1 = float(np.mean([cat_f1[c] for c in CATEGORIES]))
        dev_results[feat] = {"cats": cat_f1, "macro": macro_f1}
        print(f"Device [{feat:>20}]: Macro F1 = {macro_f1:.4f} " + " ".join(f"{c}={cat_f1[c]:.3f}" for c in CATEGORIES))

    # Gemma 4 baseline on device protocol test set
    gemma_dev_f1 = {}
    for cat in CATEGORIES:
        y_true = [cat in ref[names[i]] for i in test_ids]
        y_gem = [cat in gem[names[i]] for i in test_ids]
        gemma_dev_f1[cat] = calc_f1(y_gem, y_true)
    gemma_dev_macro = float(np.mean([gemma_dev_f1[c] for c in CATEGORIES]))
    print(f"Device [Gemma 4 Reference ]: Macro F1 = {gemma_dev_macro:.4f} " + " ".join(f"{c}={gemma_dev_f1[c]:.3f}" for c in CATEGORIES))

    # 6. Random Protocol Evaluation (20 seeds)
    print(f"\n--- Running Random Protocol ({args.seeds} seeds) ---")
    pool_files = sorted(f for f in all_files if f in X_img_by_name and f in ref)
    rand_results = {feat: {c: [] for c in CATEGORIES} for feat in feature_sets}
    rand_macro = {feat: [] for feat in feature_sets}

    for s in range(args.seeds):
        rng = random.Random(s)
        pos = {}
        for c in CATEGORIES:
            cand_pos = sorted(f for f in pool_files if c in ref[f])
            pos[c] = set(rng.sample(cand_pos, 10))
        all_pos = set().union(*pos.values())
        test_files = sorted(f for f in pool_files if f not in all_pos)

        for feat in feature_sets:
            A = np.stack([get_feature_by_name(f, feat) for f in test_files])
            probs = {}
            for c in CATEGORIES:
                others = set().union(*(v for k, v in pos.items() if k != c)) - pos[c]
                cand_bg = sorted(set(pool_files) - pos[c])
                bg = rng.sample(cand_bg, 50)
                neg = sorted(others | set(bg))
                Xt = np.stack([get_feature_by_name(f, feat) for f in sorted(pos[c])] + [get_feature_by_name(f, feat) for f in neg])
                y = np.array([1.0] * len(pos[c]) + [0.0] * len(neg))
                th = train_logreg(Xt, y, lam=1.0)
                probs[c] = predict_logreg(A, th)

                y_true = [c in ref[f] for f in test_files]
                f1_val = calc_f1(probs[c] >= 0.5, y_true)
                rand_results[feat][c].append(f1_val)

            seed_macro = float(np.mean([rand_results[feat][c][-1] for c in CATEGORIES]))
            rand_macro[feat].append(seed_macro)

    rand_summary = {}
    for feat in feature_sets:
        cat_stats = {
            c: (float(np.mean(rand_results[feat][c])), float(np.std(rand_results[feat][c], ddof=1)))
            for c in CATEGORIES
        }
        macro_stats = (float(np.mean(rand_macro[feat])), float(np.std(rand_macro[feat], ddof=1)))
        rand_summary[feat] = {"cats": cat_stats, "macro": macro_stats}
        print(f"Random [{feat:>20}]: Macro F1 = {macro_stats[0]:.4f} ± {macro_stats[1]:.4f}")

    # 7. Engine Statistics
    engine_stats = {}
    for eng in engines:
        texts = [ocr_texts[eng].get(f, "") for f in all_files]
        n_total = len(texts)
        non_empty = [t for t in texts if t.strip()]
        pct_non_empty = len(non_empty) / n_total * 100 if n_total > 0 else 0.0
        char_lens = [len(t) for t in texts]
        med_chars = float(np.median(char_lens))

        secs_list = [ocr_seconds[eng][f] for f in all_files if ocr_seconds[eng].get(f) is not None]
        if secs_list:
            med_sec = float(np.median(secs_list))
            tot_sec = float(np.sum(secs_list))
        else:
            med_sec = None
            tot_sec = None

        engine_stats[eng] = {
            "total": n_total,
            "non_empty": len(non_empty),
            "pct_non_empty": pct_non_empty,
            "median_chars": med_chars,
            "median_sec": med_sec,
            "total_sec": tot_sec,
        }

    # 8. Sanity check: 5 screenshots where engines disagree most by character count
    diffs = []
    for f in all_files:
        l_ml = len(ocr_texts["mlkit"].get(f, ""))
        l_rap = len(ocr_texts["rapidocr"].get(f, ""))
        l_tess = len(ocr_texts["tesseract"].get(f, ""))
        spread = max(l_ml, l_rap, l_tess) - min(l_ml, l_rap, l_tess)
        diffs.append((spread, f, l_ml, l_rap, l_tess))

    diffs.sort(key=lambda x: x[0], reverse=True)
    top5_disagreements = diffs[:5]

    # 9. Format Markdown Report
    report_lines = []
    report_lines.append("# Phase 0 OCR Benchmark Report: Collection Classification\n")
    report_lines.append("## Executive Summary\n")

    base_dev = dev_results["image"]["macro"]
    best_dev_feat = max(feature_sets, key=lambda f: dev_results[f]["macro"])
    best_dev_val = dev_results[best_dev_feat]["macro"]
    delta_dev = best_dev_val - base_dev

    base_rand = rand_summary["image"]["macro"][0]
    best_rand_feat = max(feature_sets, key=lambda f: rand_summary[f]["macro"][0])
    best_rand_val = rand_summary[best_rand_feat]["macro"][0]
    delta_rand = best_rand_val - base_rand

    report_lines.append(f"- **Baseline (`image` 128-d)**: Device Macro-F1 = **{base_dev:.3f}**, Random Protocol Macro-F1 = **{base_rand:.3f} ± {rand_summary['image']['macro'][1]:.3f}**.")
    report_lines.append(f"- **Best Device Feature Set**: **`{best_dev_feat}`** ({best_dev_val:.3f}, **{'+' if delta_dev >= 0 else ''}{delta_dev:.3f}** vs image baseline).")
    report_lines.append(f"- **Best Random Feature Set**: **`{best_rand_feat}`** ({best_rand_val:.3f} ± {rand_summary[best_rand_feat]['macro'][1]:.3f}, **{'+' if delta_rand >= 0 else ''}{delta_rand:.3f}** vs image baseline).")
    report_lines.append(f"- **Gemma 4 Reference Macro-F1**: **{gemma_dev_macro:.3f}** on the same 916 test screenshots.\n")

    report_lines.append("## 1. Engine Text & Speed Statistics\n")
    report_lines.append("| Engine | Non-empty % (count) | Median Chars | Median Sec / Image | Total Processing Time |")
    report_lines.append("|---|---|---|---|---|")
    for eng in engines:
        st = engine_stats[eng]
        sec_str = f"{st['median_sec']:.3f}s" if st["median_sec"] is not None else "null (from DB)"
        tot_str = f"{st['total_sec']:.1f}s ({st['total_sec']/60:.2f}m)" if st["total_sec"] is not None else "null"
        report_lines.append(f"| **{eng}** | {st['pct_non_empty']:.1f}% ({st['non_empty']}/{st['total']}) | {st['median_chars']:.0f} | {sec_str} | {tot_str} |")
    report_lines.append("")

    report_lines.append("## 2. Device Protocol (50 MANUAL examples from DB, N=916 test screenshots)\n")
    report_lines.append("| Feature Set | Tweets | Chats | Memes | Animals | Study | Macro F1 | Δ vs Baseline |")
    report_lines.append("|---|---|---|---|---|---|---|---|")
    for feat in feature_sets:
        c_f1 = dev_results[feat]["cats"]
        m_f1 = dev_results[feat]["macro"]
        diff_str = f"**{'+' if m_f1 - base_dev >= 0 else ''}{m_f1 - base_dev:.3f}**" if feat != "image" else "baseline"
        bold = "**" if feat == best_dev_feat else ""
        report_lines.append(
            f"| {bold}{feat}{bold} | {c_f1['tweets']:.3f} | {c_f1['chats']:.3f} | {c_f1['memes']:.3f} | {c_f1['animals']:.3f} | {c_f1['study']:.3f} | {bold}{m_f1:.3f}{bold} | {diff_str} |"
        )
    report_lines.append(
        f"| *Gemma 4 Reference* | {gemma_dev_f1['tweets']:.3f} | {gemma_dev_f1['chats']:.3f} | {gemma_dev_f1['memes']:.3f} | {gemma_dev_f1['animals']:.3f} | {gemma_dev_f1['study']:.3f} | **{gemma_dev_macro:.3f}** | *reference* |"
    )
    report_lines.append("")

    report_lines.append("## 3. Random Protocol (20 seeds, 10 positives/cat uniformly sampled, test = rest)\n")
    report_lines.append("| Feature Set | Tweets | Chats | Memes | Animals | Study | Macro F1 (mean ± sd) | Δ vs Baseline |")
    report_lines.append("|---|---|---|---|---|---|---|---|")
    for feat in feature_sets:
        cs = rand_summary[feat]["cats"]
        ms = rand_summary[feat]["macro"]
        diff_str = f"**{'+' if ms[0] - base_rand >= 0 else ''}{ms[0] - base_rand:.3f}**" if feat != "image" else "baseline"
        bold = "**" if feat == best_rand_feat else ""
        report_lines.append(
            f"| {bold}{feat}{bold} | {cs['tweets'][0]:.3f} | {cs['chats'][0]:.3f} | {cs['memes'][0]:.3f} | {cs['animals'][0]:.3f} | {cs['study'][0]:.3f} | {bold}{ms[0]:.3f} ± {ms[1]:.3f}{bold} | {diff_str} |"
        )
    report_lines.append("")

    report_lines.append("## 4. Sanity Check: Top 5 Screenshots With Largest Engine Disagreement\n")
    report_lines.append("Disagreement measured by character count spread `max(len) - min(len)` across ML Kit, RapidOCR, and Tesseract:\n")

    for rank, (spread, fname, l_ml, l_rap, l_tess) in enumerate(top5_disagreements, 1):
        report_lines.append(f"### {rank}. `{fname}` (Spread: {spread} chars)")
        report_lines.append(f"- **ML Kit** ({l_ml} chars):")
        ml_preview = ocr_texts["mlkit"].get(fname, "")[:200].replace("\n", " ")
        report_lines.append(f"  > {ml_preview or '*(empty)*'}")
        report_lines.append(f"- **RapidOCR** ({l_rap} chars):")
        rap_preview = ocr_texts["rapidocr"].get(fname, "")[:200].replace("\n", " ")
        report_lines.append(f"  > {rap_preview or '*(empty)*'}")
        report_lines.append(f"- **Tesseract** ({l_tess} chars):")
        tess_preview = ocr_texts["tesseract"].get(fname, "")[:200].replace("\n", " ")
        report_lines.append(f"  > {tess_preview or '*(empty)*'}")
        report_lines.append("")

    args.report.parent.mkdir(parents=True, exist_ok=True)
    with open(args.report, "w", encoding="utf-8") as f_rep:
        f_rep.write("\n".join(report_lines) + "\n")

    print(f"\nReport successfully written to {args.report}")


if __name__ == "__main__":
    main()

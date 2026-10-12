#!/usr/bin/env python3
"""Collection tie-breaker using Laya on RapidOCR text for unsure classifier screenshots.

Evaluates whether Laya reading OCR text improves decisions when on-device classifier
is unsure (0.3 <= p < 0.6).

Outputs:
  data/report_tiebreak.md
"""
from __future__ import annotations

import argparse
import json
import random
import shutil
import sqlite3
import sys
import tempfile
import time
from pathlib import Path

import numpy as np

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from common import CATEGORIES
from evaluate import predict_logreg, train_logreg
from junk_common import preprocess_ocr_text

COLLECTION_STATEMENTS = {
    "tweets": "This screenshot is a text post on X/Twitter or a similar social app, including comment sections under a post (avatar, @handle, like counts).",
    "chats": "This screenshot is a private messaging conversation (WhatsApp, Telegram, Instagram DMs, SMS) with message bubbles, not public comments.",
    "memes": "This screenshot is a meme or joke image meant to be funny.",
    "animals": "This screenshot's main subject is an animal (pets, wildlife), photo or drawing.",
    "study": "This screenshot is study material: notes, textbook pages, exam or practice questions, lecture slides, solutions.",
}

QUESTIONS = {
    cat: {
        "type": "noul",
        "instructions": stmt,
    }
    for cat, stmt in COLLECTION_STATEMENTS.items()
}


def open_temp_db(db_path: Path) -> tuple[sqlite3.Connection, str]:
    tmpdir = tempfile.mkdtemp(prefix="sift_db_tiebreak_")
    db_dir = db_path.parent
    base_name = db_path.name
    for f in db_dir.iterdir():
        if f.name.startswith(base_name):
            shutil.copy2(f, Path(tmpdir) / f.name)
    con = sqlite3.connect(Path(tmpdir) / base_name)
    return con, tmpdir


def get_torch_device() -> str:
    try:
        import torch
        if torch.cuda.is_available():
            return "cuda"
        if hasattr(torch.backends, "mps") and torch.backends.mps.is_available():
            return "mps"
    except Exception:
        pass
    return "cpu"


def calc_prf(pred: list[bool], true_labels: list[set[str]], cat: str) -> tuple[float, float, float]:
    tp = sum(1 for p, lbls in zip(pred, true_labels) if p and cat in lbls)
    fp = sum(1 for p, lbls in zip(pred, true_labels) if p and cat not in lbls)
    fn = sum(1 for p, lbls in zip(pred, true_labels) if not p and cat in lbls)
    p = tp / (tp + fp) if (tp + fp) > 0 else 0.0
    r = tp / (tp + fn) if (tp + fn) > 0 else 0.0
    f1 = 2 * p * r / (p + r) if (p + r) > 0 else 0.0
    return p, r, f1


def main() -> int:
    parser = argparse.ArgumentParser(description="Collection tie-breaker on unsure screenshots using Laya")
    parser.add_argument("--limit", type=int, default=None, help="Process up to N unsure screenshots with Laya")
    parser.add_argument("--db", default="data/device/sift_database", help="Path to device SQLite database")
    parser.add_argument("--ref", default="data/labels.jsonl", help="Ground truth collection labels")
    parser.add_argument("--gemma", default="data/labels_gemma4.jsonl", help="Gemma labels")
    parser.add_argument("--ocr", default="data/ocr_rapidocr.jsonl", help="RapidOCR text path")
    parser.add_argument("--out", default="data/report_tiebreak.md", help="Output Markdown report path")
    parser.add_argument("--cache", default="data/cache_tiebreak_laya.jsonl", help="Cache for Laya predictions")
    args = parser.parse_args()

    db_path = SCRIPT_DIR / args.db if not Path(args.db).is_absolute() else Path(args.db)
    ref_path = SCRIPT_DIR / args.ref if not Path(args.ref).is_absolute() else Path(args.ref)
    gem_path = SCRIPT_DIR / args.gemma if not Path(args.gemma).is_absolute() else Path(args.gemma)
    ocr_path = SCRIPT_DIR / args.ocr if not Path(args.ocr).is_absolute() else Path(args.ocr)
    out_path = SCRIPT_DIR / args.out if not Path(args.out).is_absolute() else Path(args.out)
    cache_path = SCRIPT_DIR / args.cache if not Path(args.cache).is_absolute() else Path(args.cache)

    con, tmpdir = open_temp_db(db_path)
    try:
        rows = con.execute("SELECT id, displayName, embedding FROM screenshots WHERE embedding IS NOT NULL").fetchall()
    finally:
        con.close()
        shutil.rmtree(tmpdir, ignore_errors=True)

    ids = [r[0] for r in rows]
    names = {r[0]: r[1] for r in rows}
    X = {r[0]: np.frombuffer(r[2], dtype="<f4") for r in rows}

    m = {
        "Tweets": "tweets",
        "Chats": "chats",
        "Memes": "memes",
        "Animals": "animals",
        "Study notes and papers": "study",
    }
    cats = list(m.values())

    con_manual, tmpdir2 = open_temp_db(db_path)
    try:
        man = {}
        for sid, cname in con_manual.execute(
            "SELECT sc.screenshotId, c.name FROM screenshot_collections sc JOIN collections c ON c.id=sc.collectionId WHERE sc.source='MANUAL'"
        ):
            if cname in m:
                man.setdefault(m[cname], set()).add(sid)
    finally:
        con_manual.close()
        shutil.rmtree(tmpdir2, ignore_errors=True)

    ref = {d["file"]: set(d["labels"]) for d in map(json.loads, open(ref_path)) if "file" in d}
    gem = {d["file"]: set(d["labels"]) for d in map(json.loads, open(gem_path)) if "file" in d}
    allman = set().union(*man.values())
    test = [i for i in ids if names[i] in ref and names[i] in gem and i not in allman]
    print(f"Test screenshots: {len(test)} (embedded on device, labeled by Ornith and Gemma, not an example)")

    A = np.stack([X[i] for i in test])
    probs_clf = {}
    for cat, pos in man.items():
        others = set().union(*(v for k, v in man.items() if k != cat)) - pos
        rng = random.Random(1)
        cand = sorted(set(ids) - pos)
        bg = rng.sample(cand, 50)
        neg = sorted(others | set(bg))
        Xt = np.stack([X[i] for i in sorted(pos)] + [X[i] for i in neg])
        y = [1] * len(pos) + [0] * len(neg)
        th = train_logreg(Xt, np.array(y, dtype=float), lam=1.0)
        probs_clf[cat] = predict_logreg(A, th)

    # Identify unsure screenshots: any collection with 0.3 <= p < 0.6
    unsure_indices = []
    for idx, sid in enumerate(test):
        is_unsure = any(0.3 <= probs_clf[c][idx] < 0.6 for c in cats)
        if is_unsure:
            unsure_indices.append(idx)

    print(f"Screenshots with at least one collection in unsure band [0.3, 0.6): {len(unsure_indices)} / {len(test)}")

    # Load OCR text
    ocr_map = {}
    with open(ocr_path) as f:
        for line in f:
            if line.strip():
                d = json.loads(line)
                ocr_map[d["file"]] = d.get("text") or ""

    # Check cache for Laya answers
    laya_cache = {}
    if cache_path.exists():
        with open(cache_path) as f:
            for line in f:
                if line.strip():
                    try:
                        rec = json.loads(line)
                        laya_cache[rec["file"]] = rec["p_laya"]
                    except Exception:
                        pass
        print(f"Loaded {len(laya_cache)} cached Laya responses from {cache_path}")

    # Determine which unsure screenshots need Laya
    target_unsure_indices = unsure_indices
    if args.limit is not None and args.limit > 0:
        target_unsure_indices = unsure_indices[:args.limit]
        print(f"Applying limit: running Laya on {len(target_unsure_indices)} unsure screenshots")

    needed_indices = [idx for idx in target_unsure_indices if names[test[idx]] not in laya_cache]
    if needed_indices:
        import laya
        device = get_torch_device()
        print(f"Loading Laya on {device} to evaluate {len(needed_indices)} unsure screenshots...")
        agent = laya.load("convaiinnovations/laya", device=device)
        tokenizer = getattr(agent, "tok", None) or getattr(agent, "tokenizer", None)

        cache_path.parent.mkdir(parents=True, exist_ok=True)
        with open(cache_path, "a") as f_out:
            for count, idx in enumerate(needed_indices, 1):
                sid = test[idx]
                fname = names[sid]
                raw_ocr = ocr_map.get(fname, "")
                clean_ocr = preprocess_ocr_text(raw_ocr, tokenizer=tokenizer)
                state = {"screenshot_text": clean_ocr}

                t0 = time.perf_counter()
                answers = agent.system_one(state, QUESTIONS)
                sec = time.perf_counter() - t0

                answers_dict = answers.get("answers", answers) if isinstance(answers, dict) else {}
                p_l = {}
                for cat in cats:
                    val = answers_dict.get(cat, {}).get("noul", 0.0)
                    p_l[cat] = float(val) if isinstance(val, (int, float)) else (1.0 if val else 0.0)

                laya_cache[fname] = p_l
                rec = {"file": fname, "p_laya": p_l, "seconds": round(sec, 4)}
                f_out.write(json.dumps(rec) + "\n")
                f_out.flush()
                if count <= 5 or count % 10 == 0 or count == len(needed_indices):
                    print(f"[{count}/{len(needed_indices)}] {fname}: {sec:.3f}s | p_laya={p_l}")

    # Evaluate decisions
    # Baseline: Classifier only (assigned if p_classifier >= 0.5)
    # Fused: in unsure band (0.3 <= p_classifier < 0.6), assign if 0.5*p_clf + 0.5*p_laya >= 0.5; outside band keep clf
    true_labels = [ref[names[i]] for i in test]

    report_lines = []
    report_lines.append("# Collection Tie-Breaker Report: Classifier vs Laya Fused\n")
    report_lines.append("Investigation into whether Laya reading RapidOCR text resolves ambiguous collection predictions.\n")
    report_lines.append(f"- **Evaluated Test Screenshots:** {len(test)}")
    report_lines.append(f"- **Total Unsure Screenshots (0.3 ≤ p_clf < 0.6):** {len(unsure_indices)}")
    report_lines.append(f"- **Laya Evaluated Screenshots:** {len(target_unsure_indices)}\n")

    report_lines.append("## Results\n")
    report_lines.append("| Category | Classifier P | Classifier R | Classifier F1 | Fused P | Fused R | Fused F1 | Δ F1 |")
    report_lines.append("|---|---|---|---|---|---|---|---|")

    clf_f1s = []
    fused_f1s = []

    for cat in cats:
        # Classifier-only decisions
        clf_pred = [bool(probs_clf[cat][idx] >= 0.5) for idx in range(len(test))]
        cp, cr, cf1 = calc_prf(clf_pred, true_labels, cat)
        clf_f1s.append(cf1)

        # Fused decisions
        fused_pred = []
        for idx in range(len(test)):
            p_c = float(probs_clf[cat][idx])
            fname = names[test[idx]]
            if 0.3 <= p_c < 0.6 and fname in laya_cache:
                p_l = laya_cache[fname].get(cat, p_c)
                fused_p = 0.5 * p_c + 0.5 * p_l
                fused_pred.append(bool(fused_p >= 0.5))
            else:
                fused_pred.append(bool(p_c >= 0.5))

        fp, fr, ff1 = calc_prf(fused_pred, true_labels, cat)
        fused_f1s.append(ff1)
        delta = ff1 - cf1
        delta_str = f"+{delta:.3f}" if delta > 0 else f"{delta:.3f}"
        report_lines.append(f"| `{cat}` | {cp:.3f} | {cr:.3f} | {cf1:.3f} | {fp:.3f} | {fr:.3f} | {ff1:.3f} | {delta_str} |")

    macro_clf = sum(clf_f1s) / len(cats)
    macro_fused = sum(fused_f1s) / len(cats)
    macro_delta = macro_fused - macro_clf
    m_delta_str = f"+{macro_delta:.3f}" if macro_delta > 0 else f"{macro_delta:.3f}"
    report_lines.append(f"| **macro** | | | **{macro_clf:.3f}** | | | **{macro_fused:.3f}** | **{m_delta_str}** |\n")

    if len(target_unsure_indices) < len(unsure_indices):
        report_lines.append(
            f"\n> **Note:** Smoke run evaluated {len(target_unsure_indices)} / {len(unsure_indices)} unsure screenshots.\n"
            f"> To run across all unsure screenshots, execute:\n"
            f"> `tools/phase0/.venv/bin/python tools/phase0/collection_tiebreak.py`\n"
        )

    report_text = "\n".join(report_lines)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(report_text)
    print(f"Report written to {out_path}")

    print("\n" + "=" * 60)
    print(f"TIE-BREAKER SUMMARY: Classifier Macro-F1: {macro_clf:.3f} -> Fused Macro-F1: {macro_fused:.3f} ({m_delta_str})")
    print("=" * 60 + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())

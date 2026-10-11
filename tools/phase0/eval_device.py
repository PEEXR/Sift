#!/usr/bin/env python3
"""Scores the app's on-device collection memberships against Ornith labels, next to Gemma 4 E2B.

Reads a copy of the app's Room database pulled from the phone. Each collection is mapped to a
Phase 0 category; its example screenshots (MANUAL rows) are left out of the test set for every
method, so no method is scored on the screenshots the classifier trained on.

Usage:
    .venv/bin/python eval_device.py DB_PATH --map Tweets=tweets --map Chats=chats ... \
        [--ref data/labels.jsonl] [--gemma data/labels_gemma4.jsonl]
"""
import argparse
import json
import sqlite3
import sys


def load_jsonl(path):
    return {d["file"]: set(d["labels"]) for d in map(json.loads, open(path)) if d.get("file")}


def prf(pred, ref, files, cat):
    tp = sum(1 for f in files if cat in pred.get(f, set()) and cat in ref[f])
    fp = sum(1 for f in files if cat in pred.get(f, set()) and cat not in ref[f])
    fn = sum(1 for f in files if cat not in pred.get(f, set()) and cat in ref[f])
    p = tp / (tp + fp) if tp + fp else 0.0
    r = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * p * r / (p + r) if p + r else 0.0
    return p, r, f1, tp + fp, tp + fn


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("db")
    ap.add_argument("--map", action="append", required=True, help="CollectionName=category")
    ap.add_argument("--ref", default="data/labels.jsonl")
    ap.add_argument("--gemma", default="data/labels_gemma4.jsonl")
    args = ap.parse_args()

    name_to_cat = dict(m.split("=", 1) for m in args.map)
    cats = list(name_to_cat.values())
    con = sqlite3.connect(args.db)
    rows = con.execute(
        "SELECT s.displayName, c.name, sc.source FROM screenshot_collections sc "
        "JOIN screenshots s ON s.id = sc.screenshotId JOIN collections c ON c.id = sc.collectionId"
    ).fetchall()
    embedded = {r[0] for r in con.execute("SELECT displayName FROM screenshots WHERE embedding IS NOT NULL")}

    app, examples = {}, set()
    for name, cname, source in rows:
        cat = name_to_cat.get(cname)
        if cat is None:
            continue
        if source == "MANUAL":
            examples.add(name)
        if source in ("AUTO", "MANUAL"):
            app.setdefault(name, set()).add(cat)

    ref = {f: {c for c in labels if c in cats} for f, labels in load_jsonl(args.ref).items()}
    gemma = {f: {c for c in labels if c in cats} for f, labels in load_jsonl(args.gemma).items()}
    files = sorted(f for f in ref if f in embedded and f in gemma and f not in examples)
    print(f"test screenshots: {len(files)} (embedded on device, labeled by Ornith and Gemma, not an example); "
          f"examples excluded: {len(examples)}")
    print(f"mean collections per screenshot: app {sum(len(app.get(f, ())) for f in files) / len(files):.2f}, "
          f"gemma {sum(len(gemma[f]) for f in files) / len(files):.2f}, ornith {sum(len(ref[f]) for f in files) / len(files):.2f}\n")

    print("| Category | App P | App R | App F1 | Gemma 4 F1 | App assigned | Gemma assigned | Ornith count |")
    print("|---|---|---|---|---|---|---|---|")
    app_f1s, gem_f1s = [], []
    for cat in cats:
        ap_, ar, af, an, rn = prf(app, ref, files, cat)
        _, _, gf, gn, _ = prf(gemma, ref, files, cat)
        app_f1s.append(af)
        gem_f1s.append(gf)
        print(f"| {cat} | {ap_:.2f} | {ar:.2f} | {af:.2f} | {gf:.2f} | {an} | {gn} | {rn} |")
    print(f"| **macro** | | | **{sum(app_f1s) / len(cats):.3f}** | **{sum(gem_f1s) / len(cats):.3f}** | | | |")
    return 0


if __name__ == "__main__":
    sys.exit(main())

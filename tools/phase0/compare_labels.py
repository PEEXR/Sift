#!/usr/bin/env python3
"""Compare predicted screenshot labels against reference ground-truth labels.

Usage:
    compare_labels.py --ref data/labels.jsonl --pred data/labels_gemma4.jsonl [--pred2 ...] [--out-json report.json]
"""
from __future__ import annotations

import argparse
import collections
import json
import sys
from pathlib import Path
from typing import Any, Sequence

import numpy as np

# Handle imports when run from repo root or tools/phase0
sys.path.insert(0, str(Path(__file__).parent.resolve()))
try:
    from common import CATEGORIES
except ImportError:
    CATEGORIES = {
        "chats": "a private messaging conversation...",
        "study": "study material...",
        "tweets": "a text post on X/Twitter...",
        "memes": "a meme or joke image...",
        "animals": "the main subject is an animal...",
        "notes": "personal notes or lists...",
    }


def load_jsonl(path: Path | str) -> dict[str, dict[str, Any]]:
    """Loads a JSONL file into a mapping of file name -> record."""
    records: dict[str, dict[str, Any]] = {}
    p = Path(path)
    if not p.exists():
        raise FileNotFoundError(f"File not found: {path}")

    with p.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            rec = json.loads(line)
            filename = rec.get("file")
            if filename:
                records[filename] = rec
    return records


def join_records(
    ref_records: dict[str, dict[str, Any]],
    pred_records: dict[str, dict[str, Any]],
) -> list[tuple[dict[str, Any], dict[str, Any]]]:
    """Joins ref and pred records on 'file', preserving sorted order of filenames."""
    common_files = sorted(set(ref_records.keys()) & set(pred_records.keys()))
    return [(ref_records[f], pred_records[f]) for f in common_files]


def compute_category_metrics(
    joined: Sequence[tuple[dict[str, Any], dict[str, Any]]],
    categories: Sequence[str] | dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Computes precision, recall, and F1 per category, plus macro-F1 over categories.

    Treats ref as ground truth. Defines 0/0 as 0.0.
    """
    if categories is None:
        cats = list(CATEGORIES.keys())
    elif isinstance(categories, dict):
        cats = list(categories.keys())
    else:
        cats = list(categories)

    per_category: dict[str, dict[str, Any]] = {}
    f1_list: list[float] = []

    for cat in cats:
        tp = 0
        fp = 0
        fn = 0
        tn = 0
        for ref_rec, pred_rec in joined:
            ref_labels = set(ref_rec.get("labels", []))
            pred_labels = set(pred_rec.get("labels", []))
            ref_has = cat in ref_labels
            pred_has = cat in pred_labels

            if ref_has and pred_has:
                tp += 1
            elif (not ref_has) and pred_has:
                fp += 1
            elif ref_has and (not pred_has):
                fn += 1
            else:
                tn += 1

        prec = float(tp) / (tp + fp) if (tp + fp) > 0 else 0.0
        rec = float(tp) / (tp + fn) if (tp + fn) > 0 else 0.0
        f1 = (2.0 * prec * rec) / (prec + rec) if (prec + rec) > 0 else 0.0

        per_category[cat] = {
            "precision": round(prec, 4),
            "recall": round(rec, 4),
            "f1": round(f1, 4),
            "tp": tp,
            "fp": fp,
            "fn": fn,
            "tn": tn,
            "ref_count": tp + fn,
            "pred_count": tp + fp,
        }
        f1_list.append(f1)

    macro_f1 = float(np.mean(f1_list)) if f1_list else 0.0
    return {
        "per_category": per_category,
        "macro_f1": round(macro_f1, 4),
    }


def compute_confusion_pairs(
    joined: Sequence[tuple[dict[str, Any], dict[str, Any]]],
    top_k: int = 10,
) -> list[dict[str, Any]]:
    """Finds top confusion pairs where ref has a, pred has b, and ref lacks b."""
    pair_counts: collections.Counter[tuple[str, str]] = collections.Counter()
    for ref_rec, pred_rec in joined:
        ref_set = set(ref_rec.get("labels", []))
        pred_set = set(pred_rec.get("labels", []))
        for a in ref_set:
            for b in pred_set:
                if b not in ref_set:
                    pair_counts[(a, b)] += 1

    # Sort by count desc, then alphabetically by pair
    sorted_pairs = sorted(pair_counts.items(), key=lambda item: (-item[1], item[0]))
    return [
        {"ref": a, "pred": b, "count": count}
        for (a, b), count in sorted_pairs[:top_k]
    ]


def compute_match_metrics(
    joined: Sequence[tuple[dict[str, Any], dict[str, Any]]],
) -> dict[str, Any]:
    """Computes exact-set match rate and mean labels per screenshot."""
    if not joined:
        return {
            "total_samples": 0,
            "exact_matches": 0,
            "exact_set_match_rate": 0.0,
            "mean_labels_ref": 0.0,
            "mean_labels_pred": 0.0,
        }

    exact_matches = 0
    ref_counts = []
    pred_counts = []

    for ref_rec, pred_rec in joined:
        ref_labels = set(ref_rec.get("labels", []))
        pred_labels = set(pred_rec.get("labels", []))
        if ref_labels == pred_labels:
            exact_matches += 1
        ref_counts.append(len(ref_labels))
        pred_counts.append(len(pred_labels))

    return {
        "total_samples": len(joined),
        "exact_matches": exact_matches,
        "exact_set_match_rate": round(float(exact_matches) / len(joined), 4),
        "mean_labels_ref": round(float(np.mean(ref_counts)), 2),
        "mean_labels_pred": round(float(np.mean(pred_counts)), 2),
    }


def compute_speed_metrics(records: Sequence[dict[str, Any]]) -> dict[str, Any] | None:
    """Computes speed summary (mean, median, p90) from 'seconds' fields."""
    secs = [
        float(r["seconds"])
        for r in records
        if "seconds" in r and r["seconds"] is not None and isinstance(r["seconds"], (int, float))
    ]
    if not secs:
        return None
    arr = np.array(secs, dtype=np.float64)
    return {
        "count": len(arr),
        "mean": round(float(np.mean(arr)), 2),
        "median": round(float(np.median(arr)), 2),
        "p90": round(float(np.percentile(arr, 90)), 2),
    }


def compare_single(
    ref_records: dict[str, dict[str, Any]],
    pred_records: dict[str, dict[str, Any]],
    categories: Sequence[str] | dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Runs a complete comparison between ref and pred records."""
    joined = join_records(ref_records, pred_records)
    match_metrics = compute_match_metrics(joined)
    cat_metrics = compute_category_metrics(joined, categories=categories)
    confusion = compute_confusion_pairs(joined, top_k=10)

    pred_joined_records = [p for _, p in joined]
    pred_speed = compute_speed_metrics(pred_joined_records)

    return {
        "joined_count": len(joined),
        "match": match_metrics,
        "categories": cat_metrics["per_category"],
        "macro_f1": cat_metrics["macro_f1"],
        "confusion_pairs": confusion,
        "speed": pred_speed,
    }


def format_markdown(
    comparison: dict[str, Any],
    ref_name: str,
    pred_name: str,
) -> str:
    """Formats comparison results as GitHub-flavored Markdown."""
    lines: list[str] = []
    lines.append(f"# Label Comparison: `{pred_name}` vs `{ref_name}`\n")
    lines.append(f"- **Joined Samples:** {comparison['joined_count']}")
    lines.append(f"- **Exact Set Match Rate:** {comparison['match']['exact_set_match_rate'] * 100:.1f}% ({comparison['match']['exact_matches']}/{comparison['joined_count']})")
    lines.append(f"- **Macro-F1:** {comparison['macro_f1']:.4f}")
    lines.append(f"- **Mean Labels/Screenshot (Ref):** {comparison['match']['mean_labels_ref']:.2f}")
    lines.append(f"- **Mean Labels/Screenshot (Pred):** {comparison['match']['mean_labels_pred']:.2f}\n")

    # Category performance table
    lines.append("## Per-Category Performance\n")
    lines.append("| Category | Precision | Recall | F1 | Ref Count | Pred Count |")
    lines.append("|---|---|---|---|---|---|")
    for cat, m in comparison["categories"].items():
        lines.append(
            f"| `{cat}` | {m['precision']:.4f} | {m['recall']:.4f} | {m['f1']:.4f} | {m['ref_count']} | {m['pred_count']} |"
        )
    lines.append("")

    # Speed table if available
    speed = comparison.get("speed")
    if speed:
        lines.append("## Speed Summary\n")
        lines.append("| Metric | Seconds |")
        lines.append("|---|---|")
        lines.append(f"| Mean | {speed['mean']:.2f}s |")
        lines.append(f"| Median | {speed['median']:.2f}s |")
        lines.append(f"| P90 | {speed['p90']:.2f}s |")
        lines.append("")

    # Confusion pairs table
    confusion = comparison.get("confusion_pairs", [])
    lines.append("## Top 10 Confusion Pairs (Ref has A, Pred has B, Ref lacks B)\n")
    if confusion:
        lines.append("| Ref Category (A) | Pred Category (B) | Count |")
        lines.append("|---|---|---|")
        for item in confusion:
            lines.append(f"| `{item['ref']}` | `{item['pred']}` | {item['count']} |")
    else:
        lines.append("*No confusion pairs observed.*")
    lines.append("")

    return "\n".join(lines)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Compare predicted screenshot labels against reference ground-truth."
    )
    parser.add_argument("--ref", type=Path, required=True, help="Reference JSONL file (ground truth)")
    parser.add_argument(
        "--pred",
        type=Path,
        required=True,
        nargs="+",
        help="One or more prediction JSONL files to compare",
    )
    parser.add_argument(
        "--pred2",
        type=Path,
        default=None,
        help="Optional second prediction JSONL file",
    )
    parser.add_argument(
        "--out-json",
        type=Path,
        default=None,
        help="Optional path to write full evaluation output as JSON",
    )
    args = parser.parse_args(argv)

    pred_paths = list(args.pred)
    if args.pred2 and args.pred2 not in pred_paths:
        pred_paths.append(args.pred2)

    ref_records = load_jsonl(args.ref)
    all_results: dict[str, Any] = {
        "ref": str(args.ref),
        "predictions": {},
    }

    markdown_outputs: list[str] = []

    for pred_path in pred_paths:
        pred_records = load_jsonl(pred_path)
        comp = compare_single(ref_records, pred_records)
        all_results["predictions"][str(pred_path)] = comp
        md = format_markdown(comp, ref_name=args.ref.name, pred_name=pred_path.name)
        markdown_outputs.append(md)

    # Print markdown to stdout
    print("\n\n---\n\n".join(markdown_outputs))

    if args.out_json:
        # If single prediction, also expose top-level keys for convenience
        if len(pred_paths) == 1:
            single_pred_path = str(pred_paths[0])
            out_data = {
                "ref": str(args.ref),
                "pred": single_pred_path,
                **all_results["predictions"][single_pred_path],
                "predictions": all_results["predictions"],
            }
        else:
            out_data = all_results

        args.out_json.parent.mkdir(parents=True, exist_ok=True)
        args.out_json.write_text(json.dumps(out_data, indent=2), encoding="utf-8")

    return 0


if __name__ == "__main__":
    sys.exit(main())

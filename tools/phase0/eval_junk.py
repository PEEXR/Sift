#!/usr/bin/env python3
"""Evaluates junk-tag deciders against ground truth labels.

Computes:
- Per-tag precision, recall, F1 at threshold 0.5
- Best-threshold F1 for probability deciders (optimistic sweep)
- Macro-F1 across the 4 tags (code, status, time_bound, accidental)
- Reference counts per tag
- Median runtime (seconds) per screenshot
- time_bound "date passed" agreement against OCR text

Outputs:
  data/report_junk.md
"""
from __future__ import annotations

import argparse
import json
import re
import statistics
import sys
from datetime import date, datetime
from pathlib import Path
from typing import Any

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from junk_common import JUNK_TAGS

try:
    import dateparser
except ImportError:
    dateparser = None

MONTH_NAMES = {
    "jan": 1, "january": 1,
    "feb": 2, "february": 2,
    "mar": 3, "march": 3,
    "apr": 4, "april": 4,
    "may": 5,
    "jun": 6, "june": 6,
    "jul": 7, "july": 7,
    "aug": 8, "august": 8,
    "sep": 9, "september": 9,
    "oct": 10, "october": 10,
    "nov": 11, "november": 11,
    "dec": 12, "december": 12,
}

DATE_REGEXES = [
    # YYYY-MM-DD or YYYY/MM/DD
    re.compile(r"\b(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})\b"),
    # DD-MM-YYYY or DD/MM/YYYY
    re.compile(r"\b(\d{1,2})[-/.](\d{1,2})[-/.](\d{4})\b"),
    # DD Mon YYYY e.g. 15 Aug 2026 or 15 August 2026
    re.compile(r"\b(\d{1,2})\s+([A-Za-z]{3,9})(?:,?\s+(\d{4}))?\b"),
    # Mon DD, YYYY e.g. Aug 15, 2026 or August 15 2026
    re.compile(r"\b([A-Za-z]{3,9})\s+(\d{1,2})(?:,?\s+(\d{4}))?\b"),
]


def parse_normalized_date(val: Any) -> tuple[int, int, int] | None:
    """Attempts to parse a date into (year, month, day). Returns None if unparseable."""
    if not val:
        return None
    s = str(val).strip()
    if dateparser is not None:
        try:
            dt = dateparser.parse(s)
            if dt:
                return (dt.year, dt.month, dt.day)
        except Exception:
            pass

    # Direct ISO match
    m = re.match(r"^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})", s)
    if m:
        return (int(m.group(1)), int(m.group(2)), int(m.group(3)))

    m = re.match(r"^(\d{1,2})[-/.](\d{1,2})[-/.](\d{4})", s)
    if m:
        return (int(m.group(3)), int(m.group(2)), int(m.group(1)))

    return None


def extract_dates_from_ocr(ocr_text: str) -> set[tuple[int, int, int]]:
    """Extracts candidate dates from OCR text."""
    found = set()
    if not ocr_text:
        return found

    for match in DATE_REGEXES[0].finditer(ocr_text):
        try:
            found.add((int(match.group(1)), int(match.group(2)), int(match.group(3))))
        except Exception:
            pass

    for match in DATE_REGEXES[1].finditer(ocr_text):
        try:
            found.add((int(match.group(3)), int(match.group(2)), int(match.group(1))))
        except Exception:
            pass

    for match in DATE_REGEXES[2].finditer(ocr_text):
        try:
            day = int(match.group(1))
            mon_str = match.group(2).lower()
            year = int(match.group(3)) if match.group(3) else 2026
            if mon_str in MONTH_NAMES:
                found.add((year, MONTH_NAMES[mon_str], day))
        except Exception:
            pass

    for match in DATE_REGEXES[3].finditer(ocr_text):
        try:
            mon_str = match.group(1).lower()
            day = int(match.group(2))
            year = int(match.group(3)) if match.group(3) else 2026
            if mon_str in MONTH_NAMES:
                found.add((year, MONTH_NAMES[mon_str], day))
        except Exception:
            pass

    return found


def date_matches_ocr(ref_date_val: Any, ocr_text: str) -> bool:
    """Checks if reference date appears in OCR text."""
    target = parse_normalized_date(ref_date_val)
    if not target:
        # If string appears verbatim in text
        s = str(ref_date_val).strip()
        return bool(s and s.lower() in ocr_text.lower())

    extracted = extract_dates_from_ocr(ocr_text)
    if target in extracted:
        return True

    # Check month and day match if year omitted or defaulted
    for y, m, d in extracted:
        if m == target[1] and d == target[2]:
            return True

    return False


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    records = []
    with path.open() as f:
        for line in f:
            line = line.strip()
            if line:
                try:
                    records.append(json.loads(line))
                except Exception:
                    pass
    return records


def compute_metrics(
    y_true: list[bool],
    y_prob: list[float],
    threshold: float = 0.5,
) -> tuple[float, float, float]:
    """Computes precision, recall, f1 for a binary indicator and probabilities."""
    tp = sum(1 for yt, yp in zip(y_true, y_prob) if yt and yp >= threshold)
    fp = sum(1 for yt, yp in zip(y_true, y_prob) if not yt and yp >= threshold)
    fn = sum(1 for yt, yp in zip(y_true, y_prob) if yt and yp < threshold)

    p = tp / (tp + fp) if (tp + fp) > 0 else 0.0
    r = tp / (tp + fn) if (tp + fn) > 0 else 0.0
    f1 = 2 * p * r / (p + r) if (p + r) > 0 else 0.0
    return p, r, f1


def sweep_best_f1(y_true: list[bool], y_prob: list[float]) -> tuple[float, float]:
    """Finds highest F1 across probability thresholds."""
    if not any(y_true):
        return 0.0, 0.5
    # Unique candidate thresholds
    thresholds = [round(t * 0.05, 2) for t in range(1, 20)]
    best_f1 = 0.0
    best_t = 0.5
    for t in thresholds:
        _, _, f1 = compute_metrics(y_true, y_prob, threshold=t)
        if f1 > best_f1:
            best_f1 = f1
            best_t = t
    return best_f1, best_t


def evaluate_decider(
    decider_name: str,
    pred_map: dict[str, dict[str, Any]],
    ref_map: dict[str, dict[str, Any]],
    tags: list[str],
) -> dict[str, Any]:
    common_files = sorted(set(pred_map.keys()) & set(ref_map.keys()))
    if not common_files:
        # No overlap or empty
        seconds_list = [v["seconds"] for v in pred_map.values() if "seconds" in v and v["seconds"] is not None]
        median_sec = statistics.median(seconds_list) if seconds_list else 0.0
        return {
            "decider": decider_name,
            "n_samples": 0,
            "median_sec": median_sec,
            "metrics": {t: {"p": 0.0, "r": 0.0, "f1_05": 0.0, "best_f1": 0.0, "best_thr": 0.5} for t in tags},
            "macro_f1_05": 0.0,
            "macro_best_f1": 0.0,
        }

    seconds_list = [pred_map[f]["seconds"] for f in common_files if "seconds" in pred_map[f] and pred_map[f]["seconds"] is not None]
    median_sec = statistics.median(seconds_list) if seconds_list else 0.0

    tag_metrics = {}
    f1s_05 = []
    best_f1s = []

    for tag in tags:
        y_true = [tag in ref_map[f].get("tags", []) for f in common_files]
        y_prob = [float(pred_map[f].get("p", {}).get(tag, 0.0)) for f in common_files]

        p, r, f1 = compute_metrics(y_true, y_prob, threshold=0.5)
        best_f1, best_thr = sweep_best_f1(y_true, y_prob)

        tag_metrics[tag] = {
            "p": p,
            "r": r,
            "f1_05": f1,
            "best_f1": best_f1,
            "best_thr": best_thr,
            "ref_pos": sum(y_true),
        }
        f1s_05.append(f1)
        best_f1s.append(best_f1)

    macro_05 = sum(f1s_05) / len(tags) if tags else 0.0
    macro_best = sum(best_f1s) / len(tags) if tags else 0.0

    return {
        "decider": decider_name,
        "n_samples": len(common_files),
        "median_sec": median_sec,
        "metrics": tag_metrics,
        "macro_f1_05": macro_05,
        "macro_best_f1": macro_best,
    }


def compute_date_agreement(
    ref_records: list[dict[str, Any]],
    ocr_mlkit: dict[str, str],
    ocr_rapidocr: dict[str, str],
) -> dict[str, Any]:
    time_bound_items = [r for r in ref_records if "time_bound" in r.get("tags", []) and r.get("date")]
    total = len(time_bound_items)
    if total == 0:
        return {"total": 0, "mlkit_matches": 0, "rapidocr_matches": 0, "mlkit_pct": 0.0, "rapidocr_pct": 0.0}

    mlkit_matches = 0
    rapidocr_matches = 0
    for it in time_bound_items:
        f = it["file"]
        d_val = it["date"]
        txt_m = ocr_mlkit.get(f, "")
        txt_r = ocr_rapidocr.get(f, "")
        if date_matches_ocr(d_val, txt_m):
            mlkit_matches += 1
        if date_matches_ocr(d_val, txt_r):
            rapidocr_matches += 1

    return {
        "total": total,
        "mlkit_matches": mlkit_matches,
        "rapidocr_matches": rapidocr_matches,
        "mlkit_pct": (mlkit_matches / total) * 100.0,
        "rapidocr_pct": (rapidocr_matches / total) * 100.0,
    }


def generate_markdown_report(
    eval_results: list[dict[str, Any]],
    date_aggr: dict[str, Any],
    ref_counts: dict[str, int],
    total_refs: int,
    tags: list[str],
) -> str:
    md = []
    md.append("# Junk-Tag Deciders Evaluation Report\n")
    md.append("Comparison of **rules**, **Laya** (local ModernBERT decision engine), and **Jev** (TypeSafe cloud API) across OCR engines.\n")

    # Reference labels overview
    md.append("## Ground Truth Overview\n")
    md.append(f"- **Total reference labeled screenshots:** {total_refs}")
    for tag in tags:
        cnt = ref_counts.get(tag, 0)
        pct = (cnt / total_refs * 100.0) if total_refs > 0 else 0.0
        md.append(f"- `{tag}`: {cnt} ({pct:.1f}%)")
    md.append("\n---\n")

    # Overall comparison table
    md.append("## Overall Performance Summary\n")
    md.append("| Decider | Evaluated Screenshots | Median Latency (s) | Macro-F1 @ 0.5 | Macro-F1 (Best Threshold*) |")
    md.append("|---|---|---|---|---|")
    for res in eval_results:
        md.append(
            f"| `{res['decider']}` | {res['n_samples']} | {res['median_sec']:.4f}s | "
            f"**{res['macro_f1_05']:.3f}** | {res['macro_best_f1']:.3f}* |"
        )
    md.append("\n*\\* Best-threshold F1 sweeps per-tag thresholds in [0.05, 0.95] and is optimistic.\n")

    # Per-tag detailed breakdown
    md.append("## Per-Tag Detailed Metrics\n")
    for tag in tags:
        md.append(f"### Tag: `{tag}` (Ref Positives: {ref_counts.get(tag, 0)})\n")
        md.append(f"> {JUNK_TAGS.get(tag, '')}\n")
        md.append("| Decider | Precision @ 0.5 | Recall @ 0.5 | F1 @ 0.5 | Best F1* | Best Threshold* |")
        md.append("|---|---|---|---|---|---|")
        for res in eval_results:
            m = res["metrics"].get(tag, {})
            md.append(
                f"| `{res['decider']}` | {m.get('p', 0.0):.3f} | {m.get('r', 0.0):.3f} | "
                f"**{m.get('f1_05', 0.0):.3f}** | {m.get('best_f1', 0.0):.3f}* | {m.get('best_thr', 0.5):.2f} |"
            )
        md.append("")

    # Date agreement section
    md.append("## `time_bound` \"Date Passed\" Agreement\n")
    md.append("Agreement between Ornith reference expiration/event date and OCR text date parser:\n")
    md.append(f"- **Screenshots tagged `time_bound` with date:** {date_aggr['total']}")
    md.append(f"- **ML Kit OCR matches:** {date_aggr['mlkit_matches']} / {date_aggr['total']} ({date_aggr['mlkit_pct']:.1f}%)")
    md.append(f"- **RapidOCR matches:** {date_aggr['rapidocr_matches']} / {date_aggr['total']} ({date_aggr['rapidocr_pct']:.1f}%)\n")

    return "\n".join(md)


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate junk-tag deciders")
    parser.add_argument("--labels", default="data/labels_junk_ornith.jsonl", help="Reference labels JSONL")
    parser.add_argument("--preds", nargs="*", default=None, help="List of prediction JSONL files to evaluate")
    parser.add_argument("--out", default="data/report_junk.md", help="Output Markdown report path")
    args = parser.parse_args()

    data_dir = SCRIPT_DIR / "data"
    labels_path = SCRIPT_DIR / args.labels if not Path(args.labels).is_absolute() else Path(args.labels)
    out_path = SCRIPT_DIR / args.out if not Path(args.out).is_absolute() else Path(args.out)

    # Load reference labels
    ref_records = load_jsonl(labels_path)
    ref_map = {r["file"]: r for r in ref_records if "file" in r}
    total_refs = len(ref_map)

    tags = list(JUNK_TAGS.keys())
    ref_counts = {
        t: sum(1 for r in ref_records if t in r.get("tags", []))
        for t in tags
    }

    # Discover prediction files if not explicitly provided
    if args.preds:
        pred_files = [Path(p) if Path(p).is_absolute() else SCRIPT_DIR / p for p in args.preds]
    else:
        pred_files = sorted(data_dir.glob("junk_*.jsonl"))

    print(f"Loaded {total_refs} reference labels from {labels_path}")
    print(f"Found {len(pred_files)} prediction file(s): {[p.name for p in pred_files]}")

    eval_results = []
    for pf in pred_files:
        decider_name = pf.stem.replace("junk_", "")
        records = load_jsonl(pf)
        pred_map = {r["file"]: r for r in records if "file" in r}
        res = evaluate_decider(decider_name, pred_map, ref_map, tags)
        eval_results.append(res)

    # Load OCR texts for date agreement
    ocr_mlkit_records = load_jsonl(data_dir / "ocr_mlkit.jsonl")
    ocr_rapidocr_records = load_jsonl(data_dir / "ocr_rapidocr.jsonl")
    ocr_mlkit = {r["file"]: r.get("text", "") for r in ocr_mlkit_records if "file" in r}
    ocr_rapidocr = {r["file"]: r.get("text", "") for r in ocr_rapidocr_records if "file" in r}

    date_aggr = compute_date_agreement(ref_records, ocr_mlkit, ocr_rapidocr)

    report_md = generate_markdown_report(eval_results, date_aggr, ref_counts, total_refs, tags)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(report_md)
    print(f"Wrote report to {out_path}")

    # Also print a concise summary to stdout
    print("\n" + "=" * 60)
    print("JUNK DECIDERS SUMMARY:")
    for res in eval_results:
        print(f"  {res['decider']:<22} | n={res['n_samples']:<4} | median {res['median_sec']:.4f}s | Macro-F1@0.5: {res['macro_f1_05']:.3f} | Best*: {res['macro_best_f1']:.3f}")
    print("=" * 60 + "\n")

    return 0


if __name__ == "__main__":
    sys.exit(main())

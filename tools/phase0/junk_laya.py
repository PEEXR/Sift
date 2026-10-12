#!/usr/bin/env python3
"""Local Laya decider on OCR text.

Uses convaiinnovations/laya decision model via agent.system_one(state, questions).
One call per screenshot with four noul questions whose instructions are the tag
definitions phrased as statements.

Outputs:
  data/junk_laya_<ocr>.jsonl with {"file", "p": {tag: probability}, "seconds"}
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from junk_common import JUNK_STATEMENTS, preprocess_ocr_text

QUESTIONS = {
    tag: {
        "type": "noul",
        "instructions": statement,
    }
    for tag, statement in JUNK_STATEMENTS.items()
}


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


def process_ocr_with_laya(
    agent: any,
    input_path: Path,
    output_path: Path,
    limit: int | None = None,
    resume: bool = True,
) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    done_files = set()
    if output_path.exists() and resume:
        with output_path.open() as f:
            for line in f:
                line = line.strip()
                if line:
                    try:
                        record = json.loads(line)
                        if "file" in record:
                            done_files.add(record["file"])
                    except Exception:
                        pass
        print(f"Resuming: found {len(done_files)} already processed items in {output_path.name}")

    items = []
    with input_path.open() as f:
        for line in f:
            line = line.strip()
            if line:
                items.append(json.loads(line))

    if limit is not None and limit > 0:
        items = items[:limit]

    to_process = [it for it in items if it["file"] not in done_files]
    print(f"Processing {len(to_process)} screenshots ({len(items)} total targets) from {input_path.name}")

    tokenizer = getattr(agent, "tok", None) or getattr(agent, "tokenizer", None)
    mode = "a" if (resume and output_path.exists()) else "w"
    with output_path.open(mode) as out_f:
        for idx, item in enumerate(to_process, 1):
            file_name = item["file"]
            raw_text = item.get("text") or ""
            clean_text = preprocess_ocr_text(raw_text, tokenizer=tokenizer)
            state = {"screenshot_text": clean_text}

            t0 = time.perf_counter()
            answers = agent.system_one(state, QUESTIONS)
            elapsed = time.perf_counter() - t0

            answers_dict = answers.get("answers", answers) if isinstance(answers, dict) else {}
            p = {}
            for tag in JUNK_STATEMENTS:
                ans = answers_dict.get(tag, {})
                val = ans.get("noul")
                if isinstance(val, (int, float)):
                    p[tag] = float(val)
                elif isinstance(val, bool):
                    p[tag] = 1.0 if val else 0.0
                else:
                    p[tag] = 0.0

            record = {
                "file": file_name,
                "p": p,
                "seconds": round(elapsed, 4),
            }
            out_f.write(json.dumps(record) + "\n")
            out_f.flush()
            if idx <= 5 or idx % 5 == 0 or idx == len(to_process):
                print(f"[{idx}/{len(to_process)}] {file_name}: {elapsed:.3f}s | p={p}")

    print(f"Finished writing {output_path}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Laya local decider for junk tags")
    parser.add_argument("--ocr", choices=["mlkit", "rapidocr", "both"], default="both")
    parser.add_argument("--limit", type=int, default=None, help="Process up to N screenshots")
    parser.add_argument("--device", type=str, default=None, help="Override torch device (cpu, cuda, mps)")
    parser.add_argument("--input", type=str, default=None, help="Override input jsonl path")
    parser.add_argument("--output", type=str, default=None, help="Override output jsonl path")
    parser.add_argument("--no-resume", action="store_true", help="Overwrite existing output")
    args = parser.parse_args()

    import laya

    device = args.device or get_torch_device()
    print(f"Loading Laya model convaiinnovations/laya on device '{device}'...")
    agent = laya.load("convaiinnovations/laya", device=device)
    print("Laya model loaded successfully.")

    data_dir = SCRIPT_DIR / "data"

    if args.input and args.output:
        process_ocr_with_laya(
            agent,
            Path(args.input),
            Path(args.output),
            limit=args.limit,
            resume=not args.no_resume,
        )
        return 0

    ocrs = ["mlkit", "rapidocr"] if args.ocr == "both" else [args.ocr]
    for ocr in ocrs:
        in_path = data_dir / f"ocr_{ocr}.jsonl"
        out_path = data_dir / f"junk_laya_{ocr}.jsonl"
        process_ocr_with_laya(
            agent,
            in_path,
            out_path,
            limit=args.limit,
            resume=not args.no_resume,
        )

    return 0


if __name__ == "__main__":
    sys.exit(main())

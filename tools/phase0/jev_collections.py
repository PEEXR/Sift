#!/usr/bin/env python3
"""Cloud Jev decider for collection categories on PaddleOCR (RapidOCR) text.

One API call per screenshot asking 5 noul questions for the 5 categories:
chats, study, tweets, memes, animals.
Screenshots with empty OCR text skip the API call and output p = null.

Outputs:
  data/coll_jev_rapidocr.jsonl with {"file", "p": {category: prob}, "seconds"}
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from junk_common import preprocess_ocr_text
from junk_jev import call_jev_api, load_api_key

COLLECTION_STATEMENTS = {
    "chats": "This screenshot is a private messaging conversation (WhatsApp, Telegram, Instagram DMs, SMS) with message bubbles, not public comments.",
    "study": "This screenshot is study material: notes, textbook pages, exam or practice questions, lecture slides, solutions.",
    "tweets": "This screenshot is a text post on X/Twitter or a similar social app, including comment sections under a post (avatar, @handle, like counts).",
    "memes": "This screenshot is a meme or joke image meant to be funny.",
    "animals": "This screenshot's main subject is an animal (pets, wildlife), photo or drawing.",
}

QUESTIONS = {
    cat: {
        "type": "noul",
        "instructions": stmt,
    }
    for cat, stmt in COLLECTION_STATEMENTS.items()
}


def main() -> int:
    parser = argparse.ArgumentParser(description="Jev cloud decider for collections (RapidOCR)")
    parser.add_argument("--limit", type=int, default=None, help="Process up to N screenshots")
    parser.add_argument("--input", default="data/ocr_rapidocr.jsonl", help="Input OCR jsonl path")
    parser.add_argument("--output", default="data/coll_jev_rapidocr.jsonl", help="Output jsonl path")
    parser.add_argument("--no-resume", action="store_true", help="Overwrite existing output instead of resuming")
    args = parser.parse_args()

    api_key = load_api_key()

    input_path = SCRIPT_DIR / args.input if not Path(args.input).is_absolute() else Path(args.input)
    output_path = SCRIPT_DIR / args.output if not Path(args.output).is_absolute() else Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    done_files = set()
    if output_path.exists() and not args.no_resume:
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
        print(f"Resuming: found {len(done_files)} already completed entries in {output_path}")

    items = []
    with input_path.open() as f:
        for line in f:
            line = line.strip()
            if line:
                items.append(json.loads(line))

    to_process = [it for it in items if it["file"] not in done_files]
    if args.limit is not None and args.limit > 0:
        to_process = to_process[:args.limit]

    print(f"Total in input: {len(items)}, already done: {len(done_files)}, to process: {len(to_process)}")

    mode = "w" if (args.no_resume or not output_path.exists()) else "a"
    with output_path.open(mode) as out_f:
        for idx, item in enumerate(to_process, 1):
            file_name = item["file"]
            raw_text = item.get("text") or ""
            clean_text = preprocess_ocr_text(raw_text)

            if not clean_text:
                record = {
                    "file": file_name,
                    "p": None,
                    "seconds": 0.0,
                }
                out_f.write(json.dumps(record) + "\n")
                out_f.flush()
                print(f"[{idx}/{len(to_process)}] {file_name}: skipped (empty OCR text), p=null")
                continue

            p, sec = call_jev_api(api_key, clean_text, questions=QUESTIONS)
            record = {
                "file": file_name,
                "p": p,
                "seconds": round(sec, 4),
            }
            out_f.write(json.dumps(record) + "\n")
            out_f.flush()
            print(f"[{idx}/{len(to_process)}] {file_name}: {sec:.2f}s, p={p}")

    print(f"Completed Jev collections processing. Output: {output_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

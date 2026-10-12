#!/usr/bin/env python3
"""Cloud Jev decider on OCR text using TypeSafe Jev systemone API.

Sequential calls with exponential backoff on 429/5xx, resumable.
Runs on RapidOCR text only.

Outputs:
  data/junk_jev_rapidocr.jsonl with {"file", "p": {tag: probability}, "seconds"}
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from junk_common import JUNK_STATEMENTS, preprocess_ocr_text

KEY_PATH = Path("/home/peexr/Work/Sift/.venv")
ENDPOINT_URL = "https://api.typesafe.ai/v1/systemone"

QUESTIONS = {
    tag: {
        "type": "noul",
        "instructions": statement,
    }
    for tag, statement in JUNK_STATEMENTS.items()
}


def load_api_key() -> str:
    if not KEY_PATH.exists():
        raise FileNotFoundError(f"API key file not found: {KEY_PATH}")
    key = KEY_PATH.read_text().strip()
    if not key:
        raise ValueError("API key file is empty")
    return key


def call_jev_api(
    api_key: str,
    state_text: str,
    questions: dict[str, Any] | None = None,
    max_retries: int = 5,
) -> tuple[dict[str, float], float]:
    if questions is None:
        questions = QUESTIONS
    payload = {
        "model": "jev-latest",
        "state": {"screenshot_text": state_text},
        "questions": questions,
    }
    data_bytes = json.dumps(payload).encode("utf-8")
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }

    backoff = 1.0
    for attempt in range(max_retries):
        t0 = time.perf_counter()
        req = urllib.request.Request(ENDPOINT_URL, data=data_bytes, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                elapsed = time.perf_counter() - t0
                body = json.loads(resp.read().decode("utf-8"))
                answers = body.get("answers", {})
                p = {}
                for tag in questions:
                    ans = answers.get(tag, {})
                    val = ans.get("noul")
                    if isinstance(val, (int, float)):
                        p[tag] = float(val)
                    elif isinstance(val, bool):
                        p[tag] = 1.0 if val else 0.0
                    else:
                        p[tag] = 0.0
                return p, elapsed
        except urllib.error.HTTPError as e:
            status = e.code
            error_body = ""
            try:
                error_body = e.read().decode("utf-8", errors="replace")
            except Exception:
                pass
            if status in (429, 500, 502, 503, 504) and attempt < max_retries - 1:
                time.sleep(backoff)
                backoff *= 2.0
                continue
            raise RuntimeError(f"HTTP {status} from Jev API: {error_body}") from e
        except (urllib.error.URLError, TimeoutError) as e:
            if attempt < max_retries - 1:
                time.sleep(backoff)
                backoff *= 2.0
                continue
            raise RuntimeError(f"Network error calling Jev API: {e}") from e

    raise RuntimeError("Exceeded maximum retries calling Jev API")


def main() -> int:
    parser = argparse.ArgumentParser(description="Jev cloud decider for junk tags (RapidOCR only)")
    parser.add_argument("--limit", type=int, default=None, help="Process up to N screenshots")
    parser.add_argument("--input", default="data/ocr_rapidocr.jsonl", help="Input OCR jsonl path")
    parser.add_argument("--output", default="data/junk_jev_rapidocr.jsonl", help="Output jsonl path")
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

    if args.limit is not None and args.limit > 0:
        items = items[:args.limit]

    to_process = [it for it in items if it["file"] not in done_files]
    print(f"Total targets: {len(items)}, to process: {len(to_process)}")

    mode = "w" if (args.no_resume or not output_path.exists()) else "a"
    with output_path.open(mode) as out_f:
        for idx, item in enumerate(to_process, 1):
            file_name = item["file"]
            raw_text = item.get("text") or ""
            clean_text = preprocess_ocr_text(raw_text)

            p, sec = call_jev_api(api_key, clean_text)
            record = {
                "file": file_name,
                "p": p,
                "seconds": round(sec, 4),
            }
            out_f.write(json.dumps(record) + "\n")
            out_f.flush()
            print(f"[{idx}/{len(to_process)}] {file_name}: {sec:.2f}s, p={p}")

    print(f"Completed Jev processing. Output: {output_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

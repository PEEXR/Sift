#!/usr/bin/env python3
"""Phase 0 labeler: asks Ornith 1.5 9B (via local Ollama) which candidate junk tags each screenshot fits.

Candidate junk tags:
- code: Shows a one-time password, verification code or login code (e.g. an SMS or app screen with a 4–8 digit code).
- status: A transactional status update: order placed/shipped/out for delivery/delivered, payment successful/failed, refund, booking or reservation confirmed, bill or recharge done.
- time_bound: Only useful until a specific date or time: a ticket, boarding pass, event, appointment, booking, coupon/offer with an end date, or deadline.
- accidental: Probably captured by accident or holds nothing worth keeping: mostly blank or loading screen, home screen or launcher, lock screen, notification shade or keyboard covering the content, an error dialog, a half-loaded page.

Multi-label: a screenshot can have several tags or none.
Resumable: screenshots already present in the output JSONL are skipped.

Usage:
    .venv/bin/python label_junk_ornith.py [screenshot_dir] [out.jsonl] [--limit N] [--max-side PX] [--file-list PATH] [--think]
"""
import argparse
import json
import re
import sys
import time
import urllib.request
from pathlib import Path

# Add tools/phase0 to sys.path so imports work regardless of cwd
TOOLS_DIR = Path(__file__).parent.resolve()
sys.path.insert(0, str(TOOLS_DIR))

from common import JUNK_TAGS
from label_ornith import IMAGE_EXTS, MODEL, OLLAMA_URL, encode_image

PROMPT = (
    "You are analyzing a phone screenshot to identify junk that the user may want to review and clean up.\n"
    "Decide which of these candidate tags apply. Pick every tag that clearly applies, or [] if none fit:\n\n"
    + "\n".join(f"- {k}: {v}" for k, v in JUNK_TAGS.items())
    + "\n\n"
    "Return a JSON object with:\n"
    "- \"tags\": subset of [\"code\", \"status\", \"time_bound\", \"accidental\"], or [] if nothing fits.\n"
    "- \"date\": if \"time_bound\", the main date the screenshot is tied to formatted as \"YYYY-MM-DD\" (or null if none is readable or not time_bound).\n"
    "  The screenshots were taken in 2026 (today is 2026-10-12), so resolve dates without a year where obvious.\n"
    "- \"reason\": one short sentence explaining your decision.\n\n"
    "Answer with JSON only."
)

SCHEMA = {
    "type": "object",
    "properties": {
        "tags": {
            "type": "array",
            "items": {"type": "string", "enum": list(JUNK_TAGS)},
        },
        "date": {"type": ["string", "null"]},
        "reason": {"type": "string"},
    },
    "required": ["tags", "date", "reason"],
}


def label_one(
    path: Path,
    think: bool = False,
    max_side: int = 1024,
    ollama_url: str = OLLAMA_URL,
    model: str = MODEL,
) -> dict:
    body = {
        "model": model,
        "stream": False,
        "think": think,
        "format": SCHEMA,
        "options": {"temperature": 0, "num_ctx": 8192},
        "messages": [
            {
                "role": "user",
                "content": PROMPT,
                "images": [encode_image(path, max_side)],
            }
        ],
    }
    req = urllib.request.Request(
        ollama_url,
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=600) as resp:
        out = json.load(resp)

    content = out["message"]["content"]
    parsed = json.loads(content)

    raw_tags = parsed.get("tags", [])
    if not isinstance(raw_tags, list):
        raw_tags = []
    tags = sorted({t for t in raw_tags if t in JUNK_TAGS})

    raw_date = parsed.get("date")
    if isinstance(raw_date, str):
        raw_date = raw_date.strip()
        if raw_date.lower() in ("null", "none", ""):
            date_val = None
        elif re.match(r"^\d{4}-\d{2}-\d{2}", raw_date):
            date_val = raw_date[:10]
        else:
            date_val = raw_date
    else:
        date_val = None

    reason = str(parsed.get("reason", "")).strip()
    return {"tags": tags, "date": date_val, "reason": reason}


def main() -> int:
    ap = argparse.ArgumentParser(description="Label screenshots with candidate junk tags using Ornith 1.5 9B")
    ap.add_argument(
        "screenshot_dir",
        type=Path,
        nargs="?",
        default=None,
        help="Directory containing screenshots (default: data/screenshots)",
    )
    ap.add_argument(
        "out",
        type=Path,
        nargs="?",
        default=None,
        help="Output JSONL file (default: data/labels_junk_ornith.jsonl)",
    )
    ap.add_argument(
        "--file-list",
        type=Path,
        default=None,
        help="Path to file listing specific screenshot filenames to process",
    )
    ap.add_argument(
        "--limit",
        type=int,
        default=0,
        help="Limit number of screenshots to process (0 = all)",
    )
    ap.add_argument(
        "--max-side",
        type=int,
        default=1024,
        help="Downscale longer side to this many px (0 = off, default: 1024)",
    )
    ap.add_argument(
        "--think",
        action="store_true",
        help="Let the model reason before answering (slower)",
    )
    ap.add_argument(
        "--ollama-url",
        type=str,
        default=OLLAMA_URL,
        help=f"Ollama chat endpoint (default: {OLLAMA_URL})",
    )
    ap.add_argument(
        "--model",
        type=str,
        default=MODEL,
        help=f"Model name (default: {MODEL})",
    )
    args = ap.parse_args()

    default_data_dir = TOOLS_DIR / "data"

    # Resolve screenshot directory
    if args.screenshot_dir:
        screenshot_dir = args.screenshot_dir
        if not screenshot_dir.is_dir() and (TOOLS_DIR / screenshot_dir).is_dir():
            screenshot_dir = TOOLS_DIR / screenshot_dir
    else:
        screenshot_dir = (
            Path("data/screenshots")
            if Path("data/screenshots").is_dir()
            else (default_data_dir / "screenshots")
        )

    if not screenshot_dir.is_dir():
        print(f"Error: Screenshot directory not found: {screenshot_dir}", file=sys.stderr)
        return 1

    # Resolve output path
    if args.out:
        out_path = args.out
        if not out_path.parent.is_dir() and (TOOLS_DIR / out_path.parent).is_dir():
            out_path = TOOLS_DIR / out_path
    else:
        out_path = (
            Path("data/labels_junk_ornith.jsonl")
            if Path("data").is_dir()
            else (default_data_dir / "labels_junk_ornith.jsonl")
        )

    # Read existing entries if resumable
    done = set()
    if out_path.exists():
        for line in out_path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line:
                try:
                    done.add(json.loads(line)["file"])
                except Exception:
                    pass

    # Collect files
    if args.file_list:
        fl = args.file_list
        if not fl.is_file() and (TOOLS_DIR / fl).is_file():
            fl = TOOLS_DIR / fl
        if not fl.is_file():
            print(f"Error: file list {args.file_list} not found.", file=sys.stderr)
            return 1
        listed = [
            line.strip()
            for line in fl.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.strip().startswith("#")
        ]
        files = [
            screenshot_dir / name
            for name in listed
            if (screenshot_dir / name).is_file() and (screenshot_dir / name).suffix.lower() in IMAGE_EXTS
        ]
    else:
        files = sorted(p for p in screenshot_dir.iterdir() if p.suffix.lower() in IMAGE_EXTS)

    todo = [p for p in files if p.name not in done]
    if args.limit > 0:
        todo = todo[:args.limit]

    print(
        f"{len(files)} images, {len(done)} already labeled, {len(todo)} to do",
        file=sys.stderr,
    )
    if not todo:
        print("Nothing to do.", file=sys.stderr)
        return 0

    out_path.parent.mkdir(parents=True, exist_ok=True)
    with out_path.open("a", encoding="utf-8") as f:
        for i, p in enumerate(todo, 1):
            t0 = time.monotonic()
            try:
                res = label_one(
                    p,
                    think=args.think,
                    max_side=args.max_side,
                    ollama_url=args.ollama_url,
                    model=args.model,
                )
            except Exception as e:
                print(f"[{i}/{len(todo)}] {p.name}: ERROR {e}", file=sys.stderr)
                continue
            duration = round(time.monotonic() - t0, 2)
            rec = {
                "file": p.name,
                "tags": res["tags"],
                "date": res["date"],
                "reason": res["reason"],
                "seconds": duration,
            }
            f.write(json.dumps(rec) + "\n")
            f.flush()
            print(
                f"[{i}/{len(todo)}] {p.name}: tags={rec['tags']} date={rec['date']} ({rec['seconds']}s)",
                file=sys.stderr,
            )
    return 0


if __name__ == "__main__":
    sys.exit(main())

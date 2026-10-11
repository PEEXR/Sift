#!/usr/bin/env python3
"""Phase 0 labeler: asks Ornith 1.5 9B (via local Ollama) which categories each screenshot fits.

Multi-label: a screenshot can fit several categories, or none. Labels exist only for
measurement; they are not product categories.

Resumable: screenshots already present in the output JSONL are skipped.

Usage:
    .venv/bin/python label_ornith.py <screenshot_dir> <out.jsonl> [--think] [--limit N] [--max-side PX]
"""
import argparse
import base64
import io
import json
import sys
import time
import urllib.request
from pathlib import Path

from PIL import Image

from common import CATEGORIES

OLLAMA_URL = "http://127.0.0.1:11434/api/chat"
MODEL = "ornith-1.5:9b"


PROMPT = (
    "You are labeling a phone screenshot for a dataset. Decide which of these categories it fits. "
    "Pick every category that clearly applies, or none if nothing fits.\n\n"
    + "\n".join(f"- {k}: {v}" for k, v in CATEGORIES.items())
    + "\n\nAlso report the app the screenshot was taken in if it is recognisable "
    "(e.g. x, whatsapp, instagram, reddit, telegram, youtube, upi_payment), else \"unknown\".\n"
    "Answer with JSON only."
)

SCHEMA = {
    "type": "object",
    "properties": {
        "labels": {"type": "array", "items": {"type": "string", "enum": list(CATEGORIES)}},
        "source": {"type": "string"},
    },
    "required": ["labels", "source"],
}

IMAGE_EXTS = {".png", ".jpg", ".jpeg", ".webp"}


def encode_image(path: Path, max_side: int) -> str:
    """Base64 image, downscaled so its longer side is at most max_side (0 = send as-is).

    Image cost grows with pixel count, and full-height screenshots are ~3x slower than needed.
    """
    if max_side <= 0:
        return base64.b64encode(path.read_bytes()).decode()
    img = Image.open(path).convert("RGB")
    img.thumbnail((max_side, max_side), Image.LANCZOS)
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=90)
    return base64.b64encode(buf.getvalue()).decode()


def label_one(path: Path, think: bool, max_side: int) -> dict:
    body = {
        "model": MODEL,
        "stream": False,
        "think": think,
        "format": SCHEMA,
        "options": {"temperature": 0, "num_ctx": 8192},
        "messages": [{
            "role": "user",
            "content": PROMPT,
            "images": [encode_image(path, max_side)],
        }],
    }
    req = urllib.request.Request(OLLAMA_URL, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=600) as resp:
        out = json.load(resp)
    parsed = json.loads(out["message"]["content"])
    labels = sorted({l for l in parsed.get("labels", []) if l in CATEGORIES})
    return {"labels": labels, "source": str(parsed.get("source", "unknown")).lower()}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("screenshot_dir", type=Path)
    ap.add_argument("out", type=Path)
    ap.add_argument("--think", action="store_true", help="let the model reason before answering (slower)")
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--max-side", type=int, default=1024, help="downscale longer side to this many px (0 = off)")
    args = ap.parse_args()

    done = set()
    if args.out.exists():
        for line in args.out.read_text().splitlines():
            if line.strip():
                done.add(json.loads(line)["file"])

    files = sorted(p for p in args.screenshot_dir.iterdir() if p.suffix.lower() in IMAGE_EXTS)
    todo = [p for p in files if p.name not in done]
    if args.limit:
        todo = todo[:args.limit]
    print(f"{len(files)} images, {len(done)} already labeled, {len(todo)} to do", file=sys.stderr)

    with args.out.open("a") as f:
        for i, p in enumerate(todo, 1):
            t0 = time.monotonic()
            try:
                rec = {"file": p.name, **label_one(p, args.think, args.max_side)}
            except Exception as e:  # keep going; failed files are retried on the next run
                print(f"[{i}/{len(todo)}] {p.name}: ERROR {e}", file=sys.stderr)
                continue
            rec["seconds"] = round(time.monotonic() - t0, 2)
            f.write(json.dumps(rec) + "\n")
            f.flush()
            print(f"[{i}/{len(todo)}] {p.name}: {rec['labels']} {rec['source']} ({rec['seconds']}s)",
                  file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())

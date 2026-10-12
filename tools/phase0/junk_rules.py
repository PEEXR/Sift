#!/usr/bin/env python3
"""Rules-based junk decider on OCR text.

Ports looksLikeCode from Android CleanupRules.kt for code (p = 1/0),
along with keyword-based baselines for status, time_bound, and accidental.

Outputs:
  data/junk_rules_<ocr>.jsonl with {"file", "p": {tag: probability}, "seconds"}
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from junk_common import (
    drop_status_bar,
    preprocess_ocr_text,
    count_non_status_bar_chars,
)

# --- Code detection (faithful port of CleanupRules.kt) ---
OTP_KEYWORDS = [
    "otp",
    "one-time",
    "one time",
    "verification code",
    "security code",
    "login code",
    "passcode",
    "is your code",
    "your code",
]

STANDALONE_CODE_REGEX = re.compile(r"(?<!\d)\d{4,8}(?!\d)")


def eval_code(clean_text: str) -> float:
    """Port of CleanupRules.kt looksLikeCode -> CodeKind.OTP."""
    if not clean_text:
        return 0.0
    lower = clean_text.lower()
    has_otp_keyword = any(kw in lower for kw in OTP_KEYWORDS)
    has_standalone_code = bool(STANDALONE_CODE_REGEX.search(clean_text))
    return 1.0 if (has_otp_keyword and has_standalone_code) else 0.0


# --- Status detection ---
STATUS_PATTERNS = [
    re.compile(r"\bdelivered\b", re.I),
    re.compile(r"\bout for delivery\b", re.I),
    re.compile(r"\border placed\b", re.I),
    re.compile(r"\border confirmed\b", re.I),
    re.compile(r"\border received\b", re.I),
    re.compile(r"\bshipped\b", re.I),
    re.compile(r"\bdispatched\b", re.I),
    re.compile(r"\bin transit\b", re.I),
    re.compile(r"\bpayment successful\b", re.I),
    re.compile(r"\bpayment succeeded\b", re.I),
    re.compile(r"\bpayment failed\b", re.I),
    re.compile(r"\bpaid successfully\b", re.I),
    re.compile(r"\brefund\b", re.I),
    re.compile(r"\brefunded\b", re.I),
    re.compile(r"\brefund initiated\b", re.I),
    re.compile(r"\brefund processed\b", re.I),
    re.compile(r"\bbooking confirmed\b", re.I),
    re.compile(r"\breservation confirmed\b", re.I),
    re.compile(r"\brecharge successful\b", re.I),
    re.compile(r"\brecharge done\b", re.I),
    re.compile(r"\bbill paid\b", re.I),
    re.compile(r"\btransaction successful\b", re.I),
    re.compile(r"\btransaction failed\b", re.I),
]


def eval_status(clean_text: str) -> float:
    if not clean_text:
        return 0.0
    for pat in STATUS_PATTERNS:
        if pat.search(clean_text):
            return 1.0
    return 0.0


# --- Time-bound detection ---
EXPIRING_PHRASES = [
    "expires in",
    "expires on",
    "expiring",
    "will expire",
    "expire in",
]

TIMED_EXPIRY_REGEX = re.compile(
    r"((valid for|valid until|valid till|use within|expires? (in|at|by))\s+(\d+|an?|one|two|three|five|ten|fifteen|thirty)\s*(sec|second|min|minute|hour|hr|day|am|pm))|(valid (until|till)\s+(midnight|tomorrow|today))",
    re.I,
)

TIME_BOUND_ADDITIONAL_PATTERNS = [
    re.compile(r"\bboarding pass\b", re.I),
    re.compile(r"\bticket\b", re.I),
    re.compile(r"\bpnr\b", re.I),
    re.compile(r"\bvalid (till|until|through|to)\b", re.I),
    re.compile(r"\b(expiry|expiration) date\b", re.I),
    re.compile(r"\bexpires\b", re.I),
    re.compile(r"\bevent date\b", re.I),
    re.compile(r"\b(departure|arrival|boarding) time\b", re.I),
    re.compile(r"\bcheck-in time\b", re.I),
    re.compile(r"\bshow time\b", re.I),
]


def eval_time_bound(clean_text: str) -> float:
    if not clean_text:
        return 0.0
    lower = clean_text.lower()
    if any(phrase in lower for phrase in EXPIRING_PHRASES):
        return 1.0
    if TIMED_EXPIRY_REGEX.search(clean_text):
        return 1.0
    for pat in TIME_BOUND_ADDITIONAL_PATTERNS:
        if pat.search(clean_text):
            return 1.0
    return 0.0


# --- Accidental detection (< 15 non-status-bar chars) ---
def eval_accidental(raw_ocr_text: str) -> float:
    count = count_non_status_bar_chars(raw_ocr_text)
    return 1.0 if count < 15 else 0.0


def classify_screenshot(raw_ocr_text: str) -> tuple[dict[str, float], float]:
    """Runs rules on OCR text, returning (p_dict, seconds)."""
    t0 = time.perf_counter()
    clean_text = preprocess_ocr_text(raw_ocr_text)
    p = {
        "code": eval_code(clean_text),
        "status": eval_status(clean_text),
        "time_bound": eval_time_bound(clean_text),
        "accidental": eval_accidental(raw_ocr_text),
    }
    elapsed = time.perf_counter() - t0
    return p, elapsed


def process_ocr_file(input_path: Path, output_path: Path, limit: int | None = None) -> None:
    print(f"Reading OCR from {input_path}")
    items = []
    with input_path.open() as f:
        for line in f:
            line = line.strip()
            if line:
                items.append(json.loads(line))

    if limit is not None and limit > 0:
        items = items[:limit]

    output_path.parent.mkdir(parents=True, exist_ok=True)
    results = []
    for item in items:
        file_name = item["file"]
        raw_text = item.get("text") or ""
        p, sec = classify_screenshot(raw_text)
        results.append({
            "file": file_name,
            "p": p,
            "seconds": round(sec, 6),
        })

    with output_path.open("w") as f:
        for r in results:
            f.write(json.dumps(r) + "\n")

    print(f"Wrote {len(results)} predictions to {output_path}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Rules decider for junk tags")
    parser.add_argument("--ocr", choices=["mlkit", "rapidocr", "both"], default="both")
    parser.add_argument("--limit", type=int, default=None, help="Process first N screenshots")
    parser.add_argument("--input", type=str, default=None, help="Override input jsonl path")
    parser.add_argument("--output", type=str, default=None, help="Override output jsonl path")
    args = parser.parse_args()

    data_dir = SCRIPT_DIR / "data"

    if args.input and args.output:
        process_ocr_file(Path(args.input), Path(args.output), limit=args.limit)
        return 0

    ocrs = ["mlkit", "rapidocr"] if args.ocr == "both" else [args.ocr]
    for ocr in ocrs:
        in_path = data_dir / f"ocr_{ocr}.jsonl"
        out_path = data_dir / f"junk_rules_{ocr}.jsonl"
        process_ocr_file(in_path, out_path, limit=args.limit)

    return 0


if __name__ == "__main__":
    sys.exit(main())

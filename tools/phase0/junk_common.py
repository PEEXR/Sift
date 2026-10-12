"""Shared definitions and preprocessing utilities for junk-tag deciders."""
from __future__ import annotations

import re
from typing import Any

from common import JUNK_TAGS

# Tag definitions phrased as statements for Laya and Jev noul questions
JUNK_STATEMENTS = {
    "code": "This screenshot shows a one-time password, verification code or login code (e.g. an SMS or app screen with a 4–8 digit code).",
    "status": "This screenshot shows a transactional status update: order placed/shipped/out for delivery/delivered, payment successful/failed, refund, booking or reservation confirmed, bill or recharge done.",
    "time_bound": "This screenshot is only useful until a specific date or time: a ticket, boarding pass, event, appointment, booking, coupon/offer with an end date, or deadline.",
    "accidental": "This screenshot was probably captured by accident or holds nothing worth keeping: mostly blank or loading screen, home screen or launcher, lock screen, notification shade or keyboard covering the content, an error dialog, a half-loaded page.",
}

STATUS_BAR_PATTERNS = [
    # Clock patterns: e.g. 12:24, 12:24 D, 0:40, 3:42PM, 2:45 PM
    re.compile(r"^\d{1,2}:\d{2}(\s*(am|pm|[a-df-z]))?$", re.I),
    # Network / signal / speed tokens: 5G, 5G+, 4G, NR, LTE, KB/s, KBe, Vo, VoLTE, WiFi
    re.compile(r"^(nr\s+)?(5g\+?|4g\+?|3g|lte|kb/s|kbe|mb/s|volte|vo|wifi)$", re.I),
    re.compile(r"^nr(\s+(kb/s|kbe|5g\+?))?$", re.I),
    # Speed like 0.20 KB/s or 24.5 KB/s
    re.compile(r"^\d+(\.\d+)?\s*(kb|mb|k|m)/s$", re.I),
    # Battery percentage e.g. 85%
    re.compile(r"^\d{1,3}%$"),
    # Lone numbers like 0.20, 66, 335, 27.9
    re.compile(r"^\d+(\.\d+)?$"),
]


def is_status_bar_line(line: str) -> bool:
    """Checks whether a single line matches status bar patterns."""
    s = line.strip()
    if not s:
        return False
    return any(p.match(s) for p in STATUS_BAR_PATTERNS)


def drop_status_bar(ocr_text: str, max_check_lines: int = 6) -> tuple[str, int]:
    """Drops status-bar lines within the first ~6 non-empty lines of OCR text."""
    if not ocr_text:
        return "", 0
    lines = ocr_text.split("\n")
    kept = []
    checked = 0
    dropped = 0
    for line in lines:
        stripped = line.strip()
        if not stripped:
            continue
        if checked < max_check_lines:
            checked += 1
            if is_status_bar_line(stripped):
                dropped += 1
                continue
        kept.append(line)
    return "\n".join(kept), dropped


def count_non_status_bar_chars(ocr_text: str) -> int:
    """Counts non-whitespace characters after dropping top status-bar lines."""
    text_no_status, _ = drop_status_bar(ocr_text)
    return len(re.sub(r"\s+", "", text_no_status))


def preprocess_ocr_text(ocr_text: str, tokenizer: Any = None, max_tokens: int = 360) -> str:
    """Preprocesses OCR text for deciders:
    1. Drop status-bar lines from top ~6 lines.
    2. Collapse whitespace.
    3. Truncate to fit budget (~360 tokens out of 512 total, leaving ~150 for questions).
    """
    clean_text, _ = drop_status_bar(ocr_text)
    collapsed = re.sub(r"\s+", " ", clean_text).strip()
    if not collapsed:
        return ""

    if tokenizer is not None:
        try:
            # Handles HF Tokenizers, AutoTokenizer, or Laya tokenizer
            if hasattr(tokenizer, "encode"):
                enc = tokenizer.encode(collapsed)
                if hasattr(enc, "ids"):
                    ids = enc.ids
                elif isinstance(enc, list):
                    ids = enc
                else:
                    ids = list(enc)
                if len(ids) > max_tokens:
                    ids = ids[:max_tokens]
                    collapsed = tokenizer.decode(ids, skip_special_tokens=True).strip()
        except Exception:
            words = collapsed.split(" ")
            if len(words) > 270:
                collapsed = " ".join(words[:270])
    else:
        words = collapsed.split(" ")
        if len(words) > 270:
            collapsed = " ".join(words[:270])

    return collapsed

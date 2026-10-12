# Task: Reference junk-tag labels from Ornith for the 962 screenshots

**EXECUTION RULE:** Work only under `tools/phase0/`. You MAY run Python (`tools/phase0/.venv/bin/python`) and call the local Ollama server. Do NOT run Gradle or adb, do NOT touch `app/`, do NOT commit. Do NOT start the full 962-image run yourself — write the script, run it on `--limit 20`, check the output, and finish with a summary. The orchestrator runs the full job.

## Context
Sift wants junk-review groups that hold for every user. The candidate tags (definitions are the contract — another worker gives the same definitions to text models, so copy them **verbatim** into a shared constant in `common.py` named `JUNK_TAGS`):

| Tag | Definition |
|---|---|
| `code` | Shows a one-time password, verification code or login code (e.g. an SMS or app screen with a 4–8 digit code). |
| `status` | A transactional status update: order placed/shipped/out for delivery/delivered, payment successful/failed, refund, booking or reservation confirmed, bill or recharge done. |
| `time_bound` | Only useful until a specific date or time: a ticket, boarding pass, event, appointment, booking, coupon/offer with an end date, or deadline. |
| `accidental` | Probably captured by accident or holds nothing worth keeping: mostly blank or loading screen, home screen or launcher, lock screen, notification shade or keyboard covering the content, an error dialog, a half-loaded page. |

A screenshot can have several tags or none. For `time_bound`, also return the main date the screenshot is tied to as `YYYY-MM-DD` (or null if none is readable), so "has the date passed" can be computed deterministically later.

## What to build — `label_junk_ornith.py`
- Model this on `label_ornith.py` (same Ollama call, model `ornith-1.5:9b`, temperature 0, JSON schema `format`, `--max-side 1024` downscale, resumable JSONL output, `--limit`, `--file-list`). Read it first and reuse its helpers (import from it rather than copy where practical).
- Prompt: give the four definitions (from `JUNK_TAGS`) and ask for `{"tags": [...subset of the four...], "date": "YYYY-MM-DD" | null, "reason": "<one short sentence>"}`. Also tell it the screenshots were taken in 2026 (today is 2026-10-12) so it can resolve dates without a year where obvious.
- Output: `data/labels_junk_ornith.jsonl` with `{"file", "tags", "date", "reason", "seconds"}`.
- Images: `data/screenshots/` (all 962 files).
- Ollama: the server is on localhost (default port) and `ornith-1.5:9b` is already pulled — check how `label_ornith.py` connects.

## Smoke test
Run `--limit 20`, print the tag counts and a few `reason`s, and sanity-check them against the images' OCR text in `data/ocr_rapidocr.jsonl` (e.g. any screenshot whose OCR mentions "OTP" or "delivered" should usually be tagged). Report seconds/image.

## Done when
Script exists, smoke test output looks sane, summary gives the exact command for the full run.

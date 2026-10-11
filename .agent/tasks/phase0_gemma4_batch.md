# Task: Phase 0b — Gemma 4 labeler variants: keys-only answers, multi-image batches, prefix reuse

**EXECUTION RULE (hard): Do NOT run Gradle, adb, pip, downloads, or any long-running/background command. Only read files and edit the files listed below. A full labeling run is using the GPU right now — keep your own test runs tiny (`--limit 2`, or `--batch 3 --limit 3`), each under ~2 minutes. Do NOT commit. Finish promptly with a summary.**

Only touch: `tools/phase0/label_gemma4.py`, `tools/phase0/test_label_gemma4.py` (new). Do not edit anything else.

## Why
Measured on the PC GPU: reading prompt+image (~504 tokens) takes ~0.15 s, but writing the ~30-token JSON answer takes ~0.8 s. On the phone, writing runs at ~6 tokens/s, so the JSON costs ~5 s per screenshot. We want to measure two ways to cut that, keeping accuracy vs Ornith in view:
1. **Keys-only answers** (1–3 output tokens instead of ~30).
2. **Several screenshots per prompt**, which shares the instruction text and gives speculative decoding a longer, predictable output.

## Changes to `label_gemma4.py` (keep all existing behaviour as the default)
1. `--file-list PATH`: only label the file names listed (one per line) from the screenshot dir. Works with resume and `--limit`.
2. `--keys-only`: prompt asks for *only* the matching category keys, comma-separated, or the single word `none`; no JSON, no source. Build the category list from `common.CATEGORIES` like the existing prompt. Parse by splitting on commas/whitespace and keeping exact category keys; `none` → `[]`. Record `"source": "unknown"`. Set a small `max_output_tokens` (e.g. 16 for single images). No constrained-JSON format in this mode.
3. `--batch N` (default 1): put N images in ONE user message (images first, each preceded by a text label `Image 1:`, `Image 2:` …, then the instruction text once). Ask for one answer line per image in the form `1: tweets, memes` / `2: none` (with `--keys-only`) or a JSON array of `{"i": 1, "labels": [...]}` objects (without it). Parse robustly; if an image's line is missing or unparsable, record `labels: []` and `"parse": "missing"` for that file. Scale `max_output_tokens` with N. Time is measured per batch; write one JSONL record per image with `seconds = batch_seconds / n_in_batch` and also `batch_seconds`, `batch_size`, `batch_index`. Attach the conversation's benchmark info (prefill/decode tokens and speeds, ttft) to every record of that batch.
4. `--max-num-tokens` passthrough to the Engine (KV cache size) — batches of 10 images need several thousand tokens; default: if `--batch` > 1, set it to `600 * batch + 512`, else leave unset.
5. **Prefix reuse (investigate, implement only if the API supports it):** check whether `litert_lm` lets you prefill the shared instruction text once and reuse/clone that state for each image (look at `session.py` `run_prefill`/`run_decode`, `conversation.py`, `engine.py`, and the CLI source in `litert_lm_cli`). If yes, add `--reuse-prefix` (single image per call; the instruction must then come *before* the image) and use it. If not supported, do not hack it — just state clearly in your summary what you found (which methods exist, why it isn't possible).
6. Fix the fallback parser: scanning raw text for app names currently matches `"x"` inside any word; only match whole words. Scanning for category keys must also match whole words only.
7. End-of-run summary line must include: images done, mean/median seconds per image, mean decode tokens per image, engine load time.

## Tests (`test_label_gemma4.py`, unittest, no model needed)
Pure parsing functions only: keys-only parsing (`tweets, memes`, `None`, `tweets.`, garbage), batch line parsing (`1: tweets` / `2:none` / missing line / extra lines / out-of-range index), whole-word fallback (`"next"` must not yield source `x`; `"study notes"` → `study` only if that is a category key match by whole word — note `notes` is also a key, so document the behaviour you choose).

## Done when
Tests pass; a tiny run of each mode works: default (`--limit 2`), `--keys-only --limit 2`, `--batch 3 --keys-only --limit 3`, `--batch 3 --limit 3`. Paste their output lines and timings in your summary, plus the prefix-reuse finding.

# Task: Junk-tag deciders on OCR text — rules vs Laya (local) vs Jev (cloud) — plus a collection tie-breaker test

**EXECUTION RULE:** Work only under `tools/phase0/`. You MAY run Python (`tools/phase0/.venv/bin/python`), `pip install` into that venv, and make the API calls described below. Do NOT run Gradle or adb, do NOT touch `app/`, do NOT commit. Do NOT start full runs over all 962 screenshots — write the scripts, run each on `--limit 20`, and finish with a summary giving the exact full-run commands. The orchestrator runs the full jobs. Never print, log, or write the API key anywhere.

Another worker is concurrently writing `label_junk_ornith.py` and adding `JUNK_TAGS` to `common.py` (the four tag definitions below, verbatim). If `JUNK_TAGS` isn't in `common.py` yet when you need it, add it yourself with exactly this content (keep it in sync — same keys, same text):

| Tag | Definition |
|---|---|
| `code` | Shows a one-time password, verification code or login code (e.g. an SMS or app screen with a 4–8 digit code). |
| `status` | A transactional status update: order placed/shipped/out for delivery/delivered, payment successful/failed, refund, booking or reservation confirmed, bill or recharge done. |
| `time_bound` | Only useful until a specific date or time: a ticket, boarding pass, event, appointment, booking, coupon/offer with an end date, or deadline. |
| `accidental` | Probably captured by accident or holds nothing worth keeping: mostly blank or loading screen, home screen or launcher, lock screen, notification shade or keyboard covering the content, an error dialog, a half-loaded page. |

## Inputs
- OCR text per screenshot: `data/ocr_mlkit.jsonl` (ML Kit, from the phone) and `data/ocr_rapidocr.jsonl` (PaddleOCR via RapidOCR). Format `{"file", "text", ...}`.
- Reference labels (written later by the other worker's full run): `data/labels_junk_ornith.jsonl` `{"file", "tags", "date", ...}`. Collection reference labels: `data/labels.jsonl` (categories chats, study, tweets, memes, animals).
- App rules to port: `app/src/main/java/com/peexr/sift/data/cleanup/CleanupRules.kt` (`OTP_KEYWORDS`, `EXPIRING_PHRASES`, `TIMED_EXPIRY_REGEX`, `STANDALONE_CODE_REGEX`, `looksLikeCode`). Read only.
- Text preprocessing (all deciders): drop the phone status-bar lines at the top of the OCR (clock like `12:24`, `5G+`, `NR`, `KB/s`, battery %, lone numbers like `0.20` within the first ~6 lines), collapse whitespace, and truncate to fit Laya's 512-token budget together with the questions (leave ~150 tokens for questions; use Laya's own tokenizer to measure).

## Deciders → `data/junk_<decider>_<ocr>.jsonl` with `{"file", "p": {tag: probability}, "seconds"}`
1. **`rules`** (`junk_rules.py`): Python port of `looksLikeCode` for `code` (p = 1/0), plus simple keyword patterns for `status` (delivered, out for delivery, order placed/confirmed, shipped, payment successful/failed, refund, booking confirmed, recharge successful…), `time_bound` (`TIMED_EXPIRY_REGEX`/`EXPIRING_PHRASES` + boarding pass/ticket/PNR/valid till/expires/event date patterns), `accidental` (OCR has < 15 non-status-bar characters). Keep the port faithful for `code`; the rest is a fair keyword baseline, not a tuned one.
2. **`laya`** (`junk_laya.py`): `pip install laya`; `agent = laya.load("convaiinnovations/laya", device=...)` (CPU is fine; use CUDA/ROCm only if torch already sees a GPU — don't fight drivers); `agent.system_one(state, questions)`. One call per screenshot with four `noul` questions whose `instructions` are the tag definitions phrased as statements ("This screenshot shows a one-time password, verification code or login code."). State = `{"screenshot_text": <preprocessed OCR>}`. Store `p[tag] = answers[tag]["noul"]` and the per-call seconds. Reference: https://github.com/vishalmysore/layaForWeb (README + `notebooks/laya_pytorch_check.ipynb`).
3. **`jev`** (`junk_jev.py`): TypeSafe Jev cloud API, same request shape the app uses (`app/src/main/java/com/peexr/sift/data/api/JevApiClient.kt` + `JevRequest`/`JevResponse` models in that package — read them for the endpoint, headers, auth and JSON). The key is the single line in `/home/peexr/Work/Sift/.venv` (a plain file, not a directory; it does not start with `sk-or-`, so use the TypeSafe endpoint). Same four `noul` questions and same state as Laya. Sequential calls, retry with backoff on 429/5xx, resumable. Run Jev on **RapidOCR text only**.

Run Laya on both `mlkit` and `rapidocr` text; rules on both.

## Collection tie-breaker (secondary)
`collection_tiebreak.py`: for screenshots where the device classifier is unsure, does Laya reading the OCR text fix the decision?
- Reuse `device_replica_sweep.py`'s logic to get per-collection probabilities from the device DB (`data/device/sift_database`, copy the db + `-wal` + `-shm` to a temp dir and open the copy).
- Unsure = any collection with 0.3 ≤ p < 0.6. For those screenshots only, ask Laya (RapidOCR text) one `noul` per collection, e.g. "This screenshot is a post or comment section on X/Twitter or a similar social app" — use the category definitions from `common.py` `CATEGORIES`.
- Fused decision: in the unsure band, assign if `0.5 * p_classifier + 0.5 * p_laya >= 0.5`; outside the band keep the classifier's decision. Report macro-F1 vs `data/labels.jsonl` for classifier-only vs fused, on the same 916 test screenshots `eval_device.py` uses (examples excluded). Write `data/report_tiebreak.md`. This one may run fully if it's under ~300 Laya calls; otherwise smoke-test it and give the command.

## Evaluation — `eval_junk.py`
Given `data/labels_junk_ornith.jsonl` and any set of `data/junk_*.jsonl`: per tag precision, recall, F1 at threshold 0.5 (and the best-threshold F1 for probability deciders, flagged as optimistic), macro-F1 across the four tags, reference counts per tag, and median seconds per screenshot. Also `time_bound` "date passed" agreement: for screenshots Ornith tags `time_bound` with a date, how often a Python date parser (`dateparser` or regexes) finds the same date in the OCR text, per OCR engine. Output `data/report_junk.md`. Must run on partial label files (only score files present in both).

## Done when
All scripts exist and pass a `--limit 20` smoke run each (Jev included — report whether the API answered and its latency), and the summary lists the exact full-run commands and expected durations.

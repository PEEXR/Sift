# Task: Fuse text decisions (Jev on PaddleOCR text) with the image side (EmbeddingGemma 2) — for collections AND junk tags

**EXECUTION RULE:** Work only under `tools/phase0/`. You MAY run Python (`tools/phase0/.venv/bin/python`) and make Jev API calls. Do NOT run Gradle or adb, do NOT touch `app/`, do NOT commit. Never print, log, or write the API key. Do NOT run the full 962-screenshot Jev pass yourself — write it, run `--limit 20`, and give the full-run command; the orchestrator runs it. Do NOT load Laya or any other large local model (the machine is short on RAM; another job is using the GPU).

## Context
- Image side today: per-collection logistic regression on the 128-d EmbeddingGemma 2 image vector, trained on 10 examples per collection. Macro-F1 vs Ornith ≈ 0.60 (device protocol) / 0.64 ± 0.02 (random protocol). Concatenating an OCR *text embedding* to the vector made things worse — that is NOT what this task does.
- Text side: a decision model reading the OCR text. On junk tags, Jev (TypeSafe cloud, `junk_jev.py`) on PaddleOCR text beat rules and Laya (macro-F1 0.45 vs 0.26 / 0.10 on 594 labeled screenshots). It has never been tried for collections, and the two sides have never been combined.
- Question: does combining the image-side probability with Jev's text-side probability beat either alone — for the 5 collections and for the 4 junk tags?

## Inputs (all in `tools/phase0/`)
- `common.py`: `CATEGORIES` (collection definitions), `JUNK_TAGS` (junk definitions), `BACKGROUND_DESCRIPTIONS` (20 background phrases the app's old matcher used), `QUERY_PREFIX`.
- `junk_jev.py` (Jev client: `load_api_key`, `call_jev_api`, retries, resumable output; reuse it — refactor a shared helper if needed, without changing its outputs), `data/junk_jev_rapidocr.jsonl` (existing junk answers for all 962).
- `data/ocr_rapidocr.jsonl` (PaddleOCR text) and the status-bar stripping/truncation helpers the junk scripts use.
- `device_replica_sweep.py`, `eval_ocr.py`: how to load the device DB (`data/device/sift_database` + `-wal`/`-shm`; copy all three to a temp dir and open the copy), the 50 MANUAL examples, the 916-screenshot test set, the random protocol (20 seeds × 10 positives/category), and `evaluate.py`'s `train_logreg`/`predict_logreg`.
- `embed_text.py`: `embed(texts)` → 128-d L2-normalised query vectors (same space as the image vectors).
- Reference labels: `data/labels.jsonl` (collections: chats, study, tweets, memes, animals; ignore notes), `data/labels_junk_ornith.jsonl` (junk tags; still being completed — score whatever is present).

## 1. Jev for collections — `jev_collections.py`
One call per screenshot, PaddleOCR text as state (same preprocessing as `junk_jev.py`), five `noul` questions, one per category, each phrased as a statement from `CATEGORIES` (e.g. "This screenshot is a text post on X/Twitter or a similar social app, including comment sections under a post."). Output `data/coll_jev_rapidocr.jsonl` `{"file", "p": {category: prob}, "seconds"}`. Resumable, retries, `--limit`. Screenshots with empty OCR text: skip the call and write `p = null` (fusion treats it as "no text opinion").

## 2. Image-side scores
- **Collections:** classifier probability `p_img` per category, trained exactly like the app (λ = 1, class-balanced, negatives = other categories' examples + seeded library sample of 50). Device protocol and random protocol.
- **Junk tags (zero-shot, no training — too few positives):** embed each tag's definition with `embed_text.embed` (query mode), cosine with each screenshot's image vector, z-score against the 20 `BACKGROUND_DESCRIPTIONS` exactly like the app's old matcher (z = (s_tag − mean(s_bg)) / std(s_bg)), then `p_img = sigmoid(z − 1.5)`. Also try short visual phrasings for `accidental` (e.g. "a blank or loading phone screen", "a phone home screen with app icons", "a keyboard covering a chat", "an error dialog") and use the max z across phrasings — report both variants.

## 3. Fusion — `eval_fusion.py` → `data/report_fusion.md`
For each target (5 categories, 4 junk tags), compare at threshold 0.5:
- `image` alone, `jev` alone,
- `mean`: (p_img + p_jev) / 2 (p_jev null → p_img),
- `or`: max(p_img, p_jev),
- `band`: keep the image decision unless 0.3 ≤ p_img < 0.6, then use `mean` (collections only),
- `weighted`: w·p_img + (1−w)·p_jev with w swept 0..1 in 0.1 steps — **flag as optimistic** (tuned on the test set).
Report per-target F1, macro-F1 for collections and for junk separately, mean collections per screenshot, and for collections both protocols (random: mean ± sd over 20 seeds). Include Gemma 4's collection macro on the device-protocol test set (`data/labels_gemma4.jsonl`) for reference. Junk: also give reference counts per tag and note how many positives the junk scores rest on.

## Smoke test
`jev_collections.py --limit 20` (report latency and whether answers look sane on a couple of obvious tweets/chats), and `eval_fusion.py` run on whatever is available (it must handle partial `coll_jev` and partial Ornith junk labels by scoring only screenshots present in all inputs, and say how many).

## Done when
Scripts exist, smoke tests pass, summary gives the exact full-run command for `jev_collections.py` and its expected duration.

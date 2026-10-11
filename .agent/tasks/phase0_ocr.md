# Task: Does OCR text improve collection classification? ML Kit vs PaddleOCR (RapidOCR) vs Tesseract

**EXECUTION RULE:** Work only under `tools/phase0/`. You MAY run Python (`tools/phase0/.venv/bin/python`) and `pip install` into that venv. Do NOT run Gradle or adb, do NOT touch `app/`, do NOT commit. Run commands in the foreground (no background jobs). OCR over ~960 images takes a while: make scripts **resumable** (append to JSONL, skip done files) and accept `--limit N`, so you can run them in chunks if a command times out. Finish with a summary.

Other workers are editing the Android app concurrently — ignore `app/`.

## Context
The app classifies screenshots into user collections with a per-collection L2 logistic regression on the image embedding (EmbeddingGemma 2, 128-d, image budget 140). On the vivo it scores macro-F1 0.62 against reference labels from a large VLM (Ornith). OCR text is currently not used for classification. Question: does adding an embedding of the screenshot's OCR text help, and which OCR engine gives the best text for this?

## Inputs (all in `tools/phase0/`)
- `data/device/sift_database` (+ `-wal`, `-shm`; open with sqlite3, it reads the WAL automatically — copy all three to a temp dir first and open the copy, never write to the originals). Table `screenshots`: `id`, `displayName`, `embedding` (float32 little-endian bytes, 128-d — the app's image vector), `ocrText` (ML Kit output, Latin). Tables `collections` (`id`, `name`) and `screenshot_collections` (`screenshotId`, `collectionId`, `source` = 'MANUAL'|'AUTO'|'EXCLUDED').
- `data/screenshots/` — the image files, matched by `displayName`.
- `data/labels.jsonl` — reference labels (`{"file":..., "labels":[...]}`), categories chats, study, tweets, memes, animals (ignore `notes`).
- `data/labels_gemma4.jsonl` — Gemma 4 labels, for the comparison column.
- Collection name → category: Tweets=tweets, Chats=chats, Memes=memes, Animals=animals, "Study notes and papers"=study.
- `embed_text.py` — ONNX EmbeddingGemma 2 text embedder (`embed(texts, full=False)` → L2-normalised 128-d; uses the *query* prefix). `evaluate.py` — `train_logreg(X, y, lam)` / `predict_logreg(X, theta)` (the exact recipe the app uses). `device_replica_sweep.py` — a replica of the app's training on the device examples (reproduces the app's 0.60–0.62); reuse its logic.

## Steps
1. **`ocr_run.py`** — `--engine rapidocr|tesseract`, writes `data/ocr_<engine>.jsonl` (`{"file", "text", "seconds"}`), resumable.
   - RapidOCR: `pip install rapidocr onnxruntime` (or `rapidocr_onnxruntime` if that's the current package name — check). Default PP-OCR mobile det+rec models, CPU. Join recognised lines top-to-bottom with newlines.
   - Tesseract: `/usr/bin/tesseract` is installed; call it via subprocess (`tesseract IMG stdout -l eng --psm 3`) or `pytesseract`. English only.
   - Record per-image seconds; report median and total.
   - ML Kit: no run needed — its text is `ocrText` in the DB. Export it to `data/ocr_mlkit.jsonl` in the same format (seconds = null).
2. **Text vectors.** Add a *document* mode to `embed_text.py` (don't change the existing query behaviour or its selftest): prefix `"title: none | text: "`, truncate the token ids to at most 512 tokens (keep BOS/EOS). Embed each engine's text → `data/ocrvec_<engine>.npz` (keys: `files`, `vec128`). Empty/whitespace text → zero vector + a flag array `empty`.
3. **`eval_ocr.py`** — compare, for each feature set:
   - `image` (DB 128-d vector only — baseline),
   - `image+text_<engine>` (concatenate image 128 + text 128 → 256-d; zero text vector when OCR empty),
   - `text_<engine>` alone (for information).
   Two protocols, same as the app (λ = 1, class-balanced, threshold 0.5, negatives = other categories' examples + a seeded random library sample of 50, multi-label, no competition):
   - **Device protocol:** the real 50 MANUAL examples from the DB as training positives (by collection), test = screenshots with an image vector, a reference label row and a Gemma row, minus all examples (should be 916). One run.
   - **Random protocol:** 20 seeds; per seed draw 10 positives per category uniformly from screenshots whose reference labels contain that category; test = the rest; report mean ± sd.
   Report per-category F1 and macro-F1, plus Gemma 4's macro on the same device-protocol test set, as a markdown table → `data/report_ocr.md`. Also print: fraction of screenshots with non-empty text per engine, median characters per screenshot, and OCR seconds/image.
4. **Sanity check** (include in report): 5 screenshots where engines disagree most (by character count), with the first 200 chars of each engine's text, so a human can eyeball quality.

## Done when
`data/report_ocr.md` exists with both protocols for all engines, scripts are in `tools/phase0/`, and the summary states which feature set wins and by how much, plus OCR speeds. If RapidOCR can't be installed, say exactly why and still deliver ML Kit + Tesseract.

# Task: Phase 0b — full-length EmbeddingGemma 2 image vectors on the PC (budgets 140 and 280)

**EXECUTION RULE (hard): Do NOT run Gradle, adb, pip, downloads, or any long-running/background command. Only read files and create/edit the files listed below. You MAY run short checks (each under ~2 minutes): `tools/phase0/.venv/bin/python tools/phase0/embed_image.py --selftest`, `tools/phase0/.venv/bin/python tools/phase0/embed_text.py --selftest`, and `tools/phase0/.venv/bin/python -m unittest discover -s tools/phase0 -p "test_*.py"`. Do NOT embed the whole screenshot folder — the orchestrator runs that. Do NOT commit. Finish promptly with a summary.**

Only touch: `tools/phase0/embed_image.py` (new), `tools/phase0/embed_text.py`, `tools/phase0/evaluate.py`, `tools/phase0/test_evaluate.py`. Do not edit the app module or `common.py`.

## Why
Phase 0 found that the app's stored 128-number vectors can't separate fine categories well. The model actually outputs a longer vector which the app truncates to 128 (Matryoshka). We want to measure whether the full-length vector, and/or image budget 280 instead of 140, separates categories better. Same model files as the app (q4 ONNX), same preprocessing, just without truncation.

## Part 1 — `tools/phase0/embed_image.py` (new)
Port the app's image embedding path exactly:
- Reference: `app/src/main/java/com/peexr/sift/data/embedding/EmbeddingEngine.kt` (`embedImage`, ≈ lines 303–440, and how `image_features` and the image soft tokens are fed to the text model), `ImagePatchifier.kt` (resize/patchify/positions for a given budget), `GemmaTokenizer.kt` (image token ids / prompt layout used for images), `IndexingQuality.kt` (budgets 70/140/280).
- Models (already downloaded): `tools/phase0/data/model/onnx/vision_encoder_q4.onnx` (+ `_data`), `tools/phase0/data/model/onnx/model_q4.onnx` (+ `_data`), `tools/phase0/data/model/tokenizer.json`. Reuse `embed_text.py`'s session loading where sensible (import from it; don't duplicate tokenizer logic).
- Decode images with Pillow (installed in the venv). Match the app's bitmap handling (RGB, any EXIF/rotation handling the app does, resize filter) as closely as the Kotlin code specifies.
- Output per image: the **full-length** L2-normalized `sentence_embedding` (do not truncate) plus the 128-truncated+renormalized version.
- Oracle: `.agent/tasks/phase2_golden/golden_vision.json` (keys `image`, `real_patches`, `soft_tokens`, `last_pos_xy`, `pv_first_patch_head`, `feat_head`, `feat_mean`) — the app's intermediate values for one image. Read the Kotlin tests that use it (`app/src/test/.../ImagePatchifierTest.kt`, `app/src/androidTest/.../EmbeddingEngineAndroidTest.kt`, `DeviceEmbeddingBenchTest.kt`) to learn which budget and image it was produced with and what each key means. Check what the image path refers to; if the image file isn't in the repo, skip that part of the check and say so.
- Second oracle: the app's own stored vectors. `tools/phase0/data/sift-backup-2026-10-11.json.gz` (FullBackup, see `data/backup/FullBackup.kt`) holds the 128-d vector the phone computed for each screenshot (`displayName`, `embeddingB64` = 128 little-endian float32, Base64); the files are in `tools/phase0/data/screenshots/`. The app indexed most of them at budget 140 (some may be 70 or 280). `--selftest` must embed 10 screenshots (sorted by name, first 10 that have an embedding in the backup) at budget 140 and print cosine(PC 128-truncated, phone vector) for each, plus the median. Pass = median ≥ 0.99. Also print the same at 70 and 280 for the first 3 so mismatched budgets are visible.
- CLI:
  - `embed_image.py --selftest`
  - `embed_image.py --budget 140 --out data/emb_full_140.npz [--limit N] DIR` — embeds every .png/.jpg/.jpeg in DIR (sorted), resumable: if OUT exists, load it and skip files already present; save every 25 images (write to a temp file then rename). npz arrays: `files` (str), `full` (float32 [n, D]), `trunc128` (float32 [n,128]), `seconds` (float32 [n], wall time per image). Print progress lines `[i/n] file 1.23s` to stderr.
  - `--threads N` for ONNX intra-op threads (default: all physical cores via `os.cpu_count()`).
- Print the full dimension D once at start.

## Part 2 — `embed_text.py`
Add `--full`: when given with `--phase0 OUT.json`, write full-length (untruncated, L2-normalized) text vectors instead of 128-d. Keep the default behaviour and `--selftest` unchanged.

## Part 3 — `evaluate.py` (+ tests)
- Make it dimension-agnostic: never hard-code 128/`EMBEDDING_DIM`; infer D from the data. `refine_query`, `z_scores`, kNN, logreg must work for any D.
- New optional arg `--embeddings PATH.npz` with `--embeddings-key full|trunc128` (default `full`): if given, take each screenshot's vector from the npz (`files` ↔ backup `displayName` ↔ labels `file`) instead of the backup's `embeddingB64`; screenshots missing from the npz are dropped. Swipe memberships still come from the backup.
- Text vectors must have the same D as the image vectors; fail with a clear error if not.
- Add a `--tag NAME` that is printed in the report title, so runs are distinguishable.
- Add/adjust unit tests: a protocol run with D=256 synthetic data; `--embeddings` loading from a temp npz.

## Done when
`embed_image.py --selftest` passes (median cosine ≥ 0.99 at budget 140), `embed_text.py --selftest` still passes, all unit tests pass. Report the full dimension D and the selftest numbers in your summary.

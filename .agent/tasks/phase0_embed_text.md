# Task: Phase 0 — PC text embedder matching the app (EmbeddingGemma 2, q4 ONNX)

**EXECUTION RULE (hard): Do NOT run Gradle, adb, pip, downloads, or any long-running/background command. Only read files and create/edit the files listed below. You MAY run `tools/phase0/.venv/bin/python tools/phase0/embed_text.py --selftest` once at the end if it finishes in under a minute. Do NOT commit. Finish promptly with a summary.**

Only touch: `tools/phase0/embed_text.py` (new). Do not edit anything else (in particular not `common.py`, not the app module).

## Goal
A Python script that produces the *same* 128-number text vectors the Android app produces in `EmbeddingEngine.embedText`, so the Phase 0 evaluation can score collections on the PC.

## Read first
- `app/src/main/java/com/peexr/sift/data/embedding/EmbeddingEngine.kt` — `embedText` (≈ lines 229–300) and `truncateAndNormalize` (≈ line 28). This is the reference behaviour.
- `app/src/main/java/com/peexr/sift/data/embedding/GemmaTokenizer.kt` — `QUERY_PREFIX`, `encode(addSpecial = true)` (BOS id 2 at start, EOS id 1 at end — confirm from the golden ids below).
- `tools/phase0/common.py` — import `QUERY_PREFIX`, `EMBEDDING_DIM`, `BACKGROUND_DESCRIPTIONS`, `CATEGORY_QUERIES` from here (run with `tools/phase0` on `sys.path`; the script lives in that directory so a plain `from common import ...` works).
- `.agent/tasks/phase2_golden/golden.json` → key `q4_text`: `{text: {"ids": [...], "emb128": [...]}}` for 5 texts. These are the app's real outputs with the q4 model. This is your correctness oracle.

## Environment (already set up by the orchestrator)
- Python venv: `tools/phase0/.venv` with `numpy`, `onnxruntime`, `tokenizers`. No other packages.
- Model files: `tools/phase0/data/model/onnx/model_q4.onnx` (+ `model_q4.onnx_data`), `tools/phase0/data/model/tokenizer.json`.

## Behaviour
1. Tokenize `QUERY_PREFIX + text` with `tokenizers.Tokenizer.from_file(tokenizer.json)`, add special tokens so ids match the golden `ids` exactly. If the HF tokenizer does not add EOS by itself, add it explicitly to match golden.
2. Run `model_q4.onnx` with inputs exactly as the app does: `input_ids` int64 [1,n], `attention_mask` int64 ones [1,n], `image_features` / `video_features` / `audio_features` float32 of shape [0,512]. Read output `sentence_embedding` (fall back to output 0).
3. `truncate_and_normalize`: take the first 128 values (zero-pad if shorter), L2-normalize (leave as zeros if norm is 0).
4. Create the ONNX session once and reuse it (CPU provider).

## CLI
- `embed_text.py --selftest` — for each golden text: assert ids equal golden ids; compute cosine vs golden `emb128`; print text, cosine. Exit 1 if any ids mismatch or any cosine < 0.999. Print "SELFTEST OK" on success.
- `embed_text.py --phase0 OUT.json` — write `{"model": "embeddinggemma-2 q4", "background": {phrase: [128 floats]}, "categories": {category: [128 floats]}}` using `BACKGROUND_DESCRIPTIONS` and `CATEGORY_QUERIES` (category key → embed its query text).
- `embed_text.py TEXT [TEXT ...]` — print one JSON line per text: `{"text": ..., "emb128": [...]}`.
- Paths: default model dir `tools/phase0/data/model`, resolved relative to the script file (not the CWD); allow `--model-dir` override.

## Style
Small, plain, stdlib + numpy + onnxruntime + tokenizers only. Module docstring with usage, like `tools/phase0/label_ornith.py`. Functions: `load(model_dir)`, `embed(texts) -> np.ndarray[n,128]`, `truncate_and_normalize(vec)`.

## Done when
`--selftest` prints SELFTEST OK (if you can run it), and the file is the only change.

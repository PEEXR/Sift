# Task: Zero-shot test of Liquid AI `d1-omni-600M` (text + image decision model) on the 962 screenshots — fp16 vs 8-bit vs 4-bit, GPU only

**EXECUTION RULE:** Work only under `tools/phase0/`. **GPU ONLY — never run the model on CPU, not even for smoke tests, not even for timing.** Use the ROCm PyTorch venv `tools/phase0/.venv-rocm` (the AMD RX 6600, gfx1030/1032, 8 GB VRAM). First verify `.venv-rocm/bin/python -c "import torch; print(torch.cuda.is_available(), torch.version.hip)"` prints `True` and a HIP version; if it prints False try `HSA_OVERRIDE_GFX_VERSION=10.3.0`; if still False, STOP and report exactly what you saw (don't fall back to CPU, don't reinstall torch). You MAY `pip install` extra packages into `.venv-rocm` (never into `.venv`), and download from Hugging Face (`LiquidAI/d1-omni-600M` is already partly cached under `~/.cache/huggingface`). Do NOT run Gradle or adb, do NOT touch `app/`, do NOT commit. **Do NOT start the full 962-screenshot runs yourself**: write the scripts, smoke-test with `--limit 20`, and finish with a summary giving exact full-run commands and the measured timings. The orchestrator runs the full jobs. Run one model process at a time. Before loading, check free VRAM (`rocm-smi --showmeminfo vram`); if < 3 GB is free, stop and report (another job may hold it — do not kill other processes). Keep host RAM under ~6 GB.

## Context
Sift groups screenshots into user collections and wants "junk" tags. Against Ornith-9B reference labels we have measured:
- image-only classifier (EmbeddingGemma 2 vectors, 10 examples/collection): collections macro-F1 0.64 (random protocol) / 0.60 (device protocol);
- + Jev (cloud text decision model on PaddleOCR text), "either confident" fusion: 0.68 / 0.65; Jev alone 0.57;
- junk tags: Jev alone macro-F1 ≈ 0.41–0.44, rules 0.26, image zero-shot 0.22–0.24 (few positives: ~40 total).
`d1-omni-600M` (https://huggingface.co/LiquidAI/d1-omni-600M) is a 587M-parameter *decision model* (381M shared trunk + decision head, 94M SigLIP2 vision encoder, 112M audio encoder): given a state (text/JSON and/or an image) plus typed questions, it returns probabilities in one forward pass, zero generated tokens. Published weights are fp32 (~2.3 GB). The model card says fp16 on GPU keeps the top answer on all checked rows and bf16 changes ~1–2% — so **use fp16, never bf16**. Questions: zero-shot, how accurate on our screenshots, how long per screenshot on the GPU, and **how much accuracy do we lose at 8-bit and 4-bit** (the phone needs a small file; the audio tower is not needed).

## Read first
- The model card and the repo's remote code (`modeling_*.py`): `system_one(state, questions, images=None, audio=None)`, `system_one_batch`, `probabilities(...)`; types `noul` (P(yes)), `choice`, `score`; 16,384-token context; **with images, state and question text is cut to 896 tokens**; images are tiled; images and audio can't be combined. Find exactly how images are tiled/resized (tile size, max tiles, tokens per tile) — screenshots are ~1080×2400 — report tile count and image-token count for a typical screenshot.
- Also fetch the repo's LICENSE and quote the commercial-use terms in the report.
- Check the model card's "quantizations" list: are there published GGUF/ONNX/quantized variants, what formats and sizes, and do they include the vision path? Report; use them only if they are loadable in transformers on this GPU.
- `common.py` (`CATEGORIES`, `JUNK_TAGS`), `jev_collections.py`, `junk_jev.py` (the exact phrasing of the five collection and four junk statements, and the OCR preprocessing), `eval_fusion.py` (image-side probabilities, fusion rules, protocols, junk scoring — **import and reuse**), `eval_ocr.py`.
- Existing files from an earlier attempt: `d1_run.py` and `eval_d1.py` (written for CPU; adapt them: device `cuda`, fp16, remove the CPU/thread logic).
- Data: screenshots `data/screenshots/`; PaddleOCR text `data/ocr_rapidocr.jsonl`; references `data/labels.jsonl` (collections), `data/labels_junk_ornith.jsonl` (junk, now complete: 962), `data/labels_gemma4.jsonl`; existing Jev outputs `data/coll_jev_rapidocr.jsonl`, `data/junk_jev_rapidocr.jsonl`; device DB `data/device/sift_database`.

## Build — `d1_run.py`
`--mode image_ocr|image_only|ocr_only` `--precision fp16|int8|int4|fp32` `--limit N` `--file-list F` `--output data/d1_<mode>_<precision>.jsonl` (resumable) `--max-side N` (downscale before the model sees the image; default the model processor's behaviour; in the smoke test compare the default with 768 px long side — report time and output agreement).
- Per screenshot two calls: (1) the five collection `noul` questions, (2) the four junk `noul` questions, using exactly the Jev statement phrasings. On the 20-screenshot smoke test also try all nine questions in one call; report whether answers differ materially and whether the 896-token cap truncates the questions in image mode. If nine fit and agree, say so (it halves the time).
- `image_ocr`: state = preprocessed PaddleOCR text (truncate so state + questions fit under the 896-token image-mode limit, measured with the model's tokenizer; report the truncation length chosen and how many screenshots get truncated) + the image. `image_only`: no text state, image only. `ocr_only`: text only (control; implement and smoke-test; optional in the full run). Empty-OCR screenshots in `image_ocr`: run with image and empty text.
- Output per screenshot: `{"file","coll":{category:p_yes},"junk":{tag:p_yes},"seconds","n_image_tokens","truncated"}`.
- Timing: `torch.cuda.synchronize()` around the calls; exclude the first (warm-up) call; report median and p90 seconds per screenshot (both calls, incl. image preprocessing) and `torch.cuda.max_memory_allocated()` per precision/mode.

## Quantization — `d1_quant.py`
Goal: accuracy and size of 8-bit and 4-bit weights.
1. **Weight-only quantize–dequantize ("fake quant") in PyTorch**, then run in fp16 on the GPU. This reproduces the accuracy of real weight-only quantization exactly (compute in fp16 with the dequantized weights). Implement:
   - `int8`: symmetric per-output-channel for every `nn.Linear` in the trunk, decision head and vision encoder (skip the audio tower — remove/ignore it; it is not needed);
   - `int4`: group-wise (group size 128, and also run 64 on the smoke set), asymmetric or symmetric with fp16 scales (state which), same layer coverage;
   - variant `--keep-vision-fp16`: quantize only trunk+head, keep the vision encoder fp16 (so we can see which part is sensitive);
   - embeddings: report results with embeddings kept fp16 and (for int8) quantized per-row.
2. **Real quantized kernels, if they work on ROCm/gfx1030**: try `bitsandbytes` (8-bit/4-bit), `optimum-quanto`, or `torchao`. Timebox to ~30 minutes in total; report which work, and if one does, VRAM and seconds/screenshot with real kernels. If none work, say so — fake quant is enough for accuracy.
3. **Sizes** — one table: parameter count per component (embeddings / trunk / head / vision encoder / audio tower / other); bytes for fp32, fp16, int8, int4 (g128, with fp16 scales) — each with and without the audio tower; and the **actual saved file size** of a packed int8 and a packed int4 safetensors (int4 packed two per byte + scales) for trunk+head+vision without the audio tower.
Save quantized state dicts under `data/d1_quant/` only if small enough (< 2 GB total); otherwise just report sizes.

## Evaluate — `eval_d1.py` → `data/report_d1.md`
Reuse `eval_fusion.py`. For every (mode, precision) output present (partial outputs allowed; score only screenshots present in all inputs and say how many):
- **Collections** (device protocol, the same 916 test screenshots as before; plus the 20-seed random protocol for fusions): d1 alone at 0.5 and best-threshold (flag optimistic), and fusions with the image-side classifier: `mean`, `or`, `band` exactly as in `eval_fusion.py`. One table: image only, Jev alone, image+Jev (`or`/`band`/`mean`), d1 alone, image+d1 (`or`/`band`/`mean`) per precision, Gemma 4 reference. Per-category F1, macro-F1, mean collections per screenshot.
- **Junk** (962 Ornith labels; ~40 positives — say so): per tag P/R/F1 at 0.5 and best-threshold for d1 alone and `mean` fusion with the zero-shot image side, next to Jev alone and rules.
- **Precision comparison**: how much macro-F1 (collections, junk) is lost going fp16 → int8 → int4, and the top-answer agreement rate between precisions (fraction of (screenshot, question) pairs where p≥0.5 agrees with fp16, and mean |Δp|).
- **Speed/memory/size table** per mode and precision; image tokens; truncation stats.
- Findings: which combination wins, whether image-only d1 is competitive, whether OCR helps d1, whether 8-bit/4-bit are acceptable, any sign of miscalibration (answers all near 0.5 or 1). Don't over-interpret junk numbers.

## Smoke test
GPU only, `--limit 20`: `d1_run.py` in `image_ocr` and `image_only` for fp16, then int8 and int4 via `d1_quant.py` (same 20), plus `ocr_only` fp16; `eval_d1.py` on those 20; a few sample outputs next to the Ornith labels (use `--file-list` with screenshots from `labels_junk_ornith.jsonl` that have tags so junk is exercised).

## Done when
Scripts run on the GPU; summary lists: tile/image-token counts, per-mode/precision seconds per screenshot and peak VRAM, the 9-vs-5+4 question finding, whether real quantized kernels work, the size table, license terms, published quantized variants (if any), and the exact full-run commands with expected durations.

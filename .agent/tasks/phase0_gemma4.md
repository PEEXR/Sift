# Task: Phase 0b — label the screenshots with Gemma 4 E2B (LiteRT-LM, GPU, speculative decoding) and compare with Ornith

**EXECUTION RULE (hard): Do NOT run Gradle, adb, pip, downloads, or any long-running/background command. Only read files and create/edit the files listed below. You MAY run short checks (each under ~2 minutes), e.g. `tools/phase0/.venv/bin/python tools/phase0/label_gemma4.py tools/phase0/data/sizetest /tmp/g4_test.jsonl --limit 3`. Do NOT label the whole folder — the orchestrator runs that. The GPU may be busy with another job; that's fine. Do NOT commit. Finish promptly with a summary.**

Only touch (all new): `tools/phase0/label_gemma4.py`, `tools/phase0/compare_labels.py`, `tools/phase0/test_compare_labels.py`. Do not edit anything else.

## Context
`tools/phase0/label_ornith.py` labels screenshots with Ornith via Ollama; its JSONL output (`tools/phase0/data/labels.jsonl`) is our reference. We now want Gemma 4 E2B — the model that would run on the phone — to label the same screenshots with the same prompt, then measure agreement with Ornith and speed.

Environment (already set up): venv `tools/phase0/.venv` has `litert-lm` 0.18 (Python API package `litert_lm`, CLI source in `litert_lm_cli/commands/run.py` — read it to see how an Engine/Conversation is created and how `--attachment` images, `--backend`, `--vision-backend`, `--speculative-decoding`, `--temperature`, `--thinking` are passed), plus numpy and Pillow. Model file: `tools/phase0/data/litert/gemma-4-E2B-it.litertlm` (the exact file the phone runs). Verified by the orchestrator: `litert-lm run <model> --backend gpu --vision-backend gpu --speculative-decoding true --temperature 0 --attachment img.jpg --prompt "..."` works on this PC (OpenCL GPU).

## `label_gemma4.py`
- Same CLI shape as `label_ornith.py`: `label_gemma4.py <screenshot_dir> <out.jsonl> [--limit N] [--think] [--max-side PX] [--backend gpu|cpu] [--no-speculative]`. Defaults: GPU for both main and vision backend, speculative decoding ON, thinking OFF, temperature 0, `--max-side 0` (send the original image; LiteRT-LM resizes internally — but keep the option, reusing `encode_image` logic from `label_ornith.py` by importing it).
- **Load the engine once** and reuse it for all images. Use a fresh conversation (or reset) per image so images don't accumulate in context.
- Prompt: build it from `common.CATEGORIES` exactly like `label_ornith.py`'s `PROMPT` (import it), but ask for: `Answer with only a JSON object: {"labels": [...], "source": "..."}`. If LiteRT-LM supports constrained/JSON response formats (see `conversation.py` `_resolve_response_format`), use it with the same schema as `label_ornith.SCHEMA`; otherwise parse leniently (first `{...}` block; on failure, fall back to scanning the text for category keys) and record `"parse": "fallback"`.
- Output JSONL records with the same fields as Ornith's (`file`, `labels` sorted and filtered to `CATEGORIES`, `source`, `seconds`) plus `raw` (the model's text, truncated to 300 chars) and, if available from `get_benchmark_info()`, `prefill_tokens`, `prefill_tps`, `decode_tokens`, `decode_tps`, `ttft_s`.
- Resumable like `label_ornith.py`. Print one progress line per image.
- At the end print: images done, mean/median seconds per image, and engine load time (measure it separately).

## `compare_labels.py`
`compare_labels.py --ref data/labels.jsonl --pred data/labels_gemma4.jsonl [--pred2 ...]`
- Join on `file` (only files present in both). Treat ref as ground truth.
- Per category (from `common.CATEGORIES`): precision, recall, F1 of pred vs ref; macro-F1 over categories; exact-set match rate; mean labels per screenshot (ref vs pred); confusion pairs (ref has a, pred has b, ref lacks b) top 10.
- Speed summary from `seconds` (mean, median, p90) if present.
- Markdown to stdout; `--out-json` optional. Pure functions + `main()`, stdlib + numpy only.

## `test_compare_labels.py`
unittest with tiny hand-made label sets: perfect agreement, empty-label rows, a category absent from ref, confusion pair counting.

## Done when
`label_gemma4.py` labels 3 sample screenshots end-to-end (report their output lines and timings in your summary), tests pass.

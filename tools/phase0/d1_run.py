#!/usr/bin/env python3
"""Run Liquid AI d1-omni-600M zero-shot inference on screenshots (GPU only).

Modes:
- image_ocr: Preprocessed PaddleOCR text + screenshot image
- image_only: Screenshot image only (empty text state)
- ocr_only: Preprocessed PaddleOCR text only (no image)

Precisions:
- fp16: Native fp16 on CUDA (default, recommended)
- int8: Weight-only symmetric per-channel quantized (via d1_quant)
- int4: Weight-only group-wise quantized (group_size 128 / 64, via d1_quant)
- fp32: Full precision on CUDA

Outputs:
  data/d1_<mode>_<precision>.jsonl with:
  {"file", "coll": {category: p_yes}, "junk": {tag: p_yes}, "seconds": float, "n_image_tokens": int, "truncated": bool}
"""
from __future__ import annotations

import argparse
import json
import resource
import sys
import time
from pathlib import Path
from typing import Any

import numpy as np
import torch
from PIL import Image

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import d1_quant
from junk_common import preprocess_ocr_text
from jev_collections import COLLECTION_STATEMENTS
from junk_jev import JUNK_STATEMENTS

COLL_CATEGORIES = ["chats", "study", "tweets", "memes", "animals"]
JUNK_TAG_NAMES = ["code", "status", "time_bound", "accidental"]

COLL_QUESTIONS = {
    cat: {
        "type": "noul",
        "instructions": COLLECTION_STATEMENTS[cat],
    }
    for cat in COLL_CATEGORIES
}

JUNK_QUESTIONS = {
    tag: {
        "type": "noul",
        "instructions": JUNK_STATEMENTS[tag],
    }
    for tag in JUNK_TAG_NAMES
}

ALL_QUESTIONS = {**COLL_QUESTIONS, **JUNK_QUESTIONS}

# Maximum tokens for preprocessed OCR text in image_ocr mode.
# Context for image mode is 896 tokens. Delimiters + question text take ~50-65 tokens.
# 800 tokens leaves plenty of headroom, avoiding question truncation.
MAX_OCR_IMAGE_TOKENS = 800


def compute_image_tokens(image: Image.Image) -> int:
    """Computes exact prefix length (tokens) produced by SigLIP2 vision tower for given PIL image."""
    try:
        from vision import preprocess, prefix_length
        inp = preprocess(image)
        return prefix_length(inp)
    except Exception:
        # Fallback calculation matching vision.py layout:
        w, h = image.size
        factor, maximum, minimum = 32, 256 * 1024, 64 * 1024
        th_h = max(factor, round(h / factor) * factor)
        th_w = max(factor, round(w / factor) * factor)
        if th_h * th_w > maximum:
            beta = (h * w / maximum) ** 0.5
            th_h = max(factor, int(h / beta / factor) * factor)
            th_w = max(factor, int(w / beta / factor) * factor)
        elif th_h * th_w < minimum:
            beta = (minimum / (h * w)) ** 0.5
            th_h = max(factor, int(h * beta / factor) * factor)
            th_w = max(factor, int(w * beta / factor) * factor)

        large = max(16, round(h / factor) * factor) * max(16, round(w / factor) * factor) > maximum * 2
        thumb_tokens = (th_h // 16) * (th_w // 16) // 4
        if not large:
            return thumb_tokens
        ratios = sorted(
            {(x, y) for n in range(2, 11) for x in range(1, n + 1) for y in range(1, n + 1) if 2 <= x * y <= 10},
            key=lambda r: r[0] * r[1]
        )
        best_diff = float("inf")
        best_grid = (1, 1)
        for rx, ry in ratios:
            diff = abs(w / h - rx / ry)
            if diff < best_diff or (diff == best_diff and w * h > 0.5 * 512 * 512 * rx * ry):
                best_grid = (rx, ry)
                best_diff = diff
        tiles = best_grid[0] * best_grid[1]
        return tiles * 256 + thumb_tokens


def resize_image_max_side(img: Image.Image, max_side: int | None) -> Image.Image:
    """Downscales image so max(width, height) <= max_side, preserving aspect ratio."""
    if max_side is None or max_side <= 0:
        return img
    w, h = img.size
    current_max = max(w, h)
    if current_max <= max_side:
        return img
    scale = float(max_side) / float(current_max)
    new_w = max(1, int(round(w * scale)))
    new_h = max(1, int(round(h * scale)))
    return img.resize((new_w, new_h), Image.Resampling.LANCZOS)


def load_target_files(
    input_ocr_path: Path,
    screenshots_dir: Path,
    file_list_arg: str | None,
) -> list[str]:
    """Determines list of screenshot filenames to process."""
    if file_list_arg:
        fl_path = Path(file_list_arg)
        if fl_path.exists():
            files = [line.strip() for line in fl_path.read_text().splitlines() if line.strip()]
        else:
            files = [f.strip() for f in file_list_arg.split(",") if f.strip()]
        return files

    # Default: read files from OCR jsonl in order
    files = []
    if input_ocr_path.exists():
        with input_ocr_path.open() as f:
            for line in f:
                if line.strip():
                    try:
                        rec = json.loads(line)
                        if "file" in rec:
                            files.append(rec["file"])
                    except Exception:
                        pass
    if not files and screenshots_dir.exists():
        files = sorted(f.name for f in screenshots_dir.glob("*.jpg"))
    return files


def main() -> int:
    parser = argparse.ArgumentParser(description="Liquid AI d1-omni-600M zero-shot decider (GPU only)")
    parser.add_argument("--mode", required=True, choices=["image_ocr", "image_only", "ocr_only"],
                        help="Operating mode")
    parser.add_argument("--precision", choices=["fp16", "int8", "int4", "fp32"], default="fp16",
                        help="Model precision (default: fp16)")
    parser.add_argument("--limit", type=int, default=None, help="Process up to N screenshots")
    parser.add_argument("--output", default=None, help="Output jsonl path (defaults to data/d1_<mode>_<precision>.jsonl)")
    parser.add_argument("--max-side", type=int, default=None, help="Downscale long side of image to N pixels")
    parser.add_argument("--single-call", action="store_true",
                        help="Run all 9 questions in 1 call instead of 2 calls (5 coll + 4 junk)")
    parser.add_argument("--group-size", type=int, default=128, help="INT4 group size (128 or 64, default: 128)")
    parser.add_argument("--keep-vision-fp16", action="store_true",
                        help="Quantize only trunk + head, keep vision encoder in fp16")
    parser.add_argument("--quantize-embeddings", action="store_true",
                        help="Quantize embedding weights (per-row symmetric int8)")
    parser.add_argument("--real-quant", action="store_true",
                        help="Use real quantized kernels (torchao Int8WeightOnlyConfig) for int8 if available")
    parser.add_argument("--file-list", default=None,
                        help="Path to file listing screenshot filenames, or comma-separated list")
    parser.add_argument("--no-resume", action="store_true", help="Overwrite output instead of resuming")
    parser.add_argument("--input-ocr", default="data/ocr_rapidocr.jsonl", help="Input OCR jsonl path")
    parser.add_argument("--screenshots-dir", default="data/screenshots", help="Directory of screenshots")
    parser.add_argument("--model-id", default="LiquidAI/d1-omni-600M", help="Hugging Face model ID or path")
    args = parser.parse_args()

    # GPU ONLY check
    if not torch.cuda.is_available():
        print("ERROR: CUDA/ROCm is not available! Execution must be GPU ONLY.", file=sys.stderr)
        return 1

    device = "cuda"

    # Resolve paths relative to SCRIPT_DIR
    input_ocr_path = SCRIPT_DIR / args.input_ocr if not Path(args.input_ocr).is_absolute() else Path(args.input_ocr)
    screenshots_dir = SCRIPT_DIR / args.screenshots_dir if not Path(args.screenshots_dir).is_absolute() else Path(args.screenshots_dir)

    if args.output is None:
        parts = [f"d1_{args.mode}_{args.precision}"]
        if args.max_side:
            parts.append(str(args.max_side))
        if args.single_call:
            parts.append("single")
        if args.precision == "int4" and args.group_size != 128:
            parts.append(f"g{args.group_size}")
        if args.keep_vision_fp16:
            parts.append("keepvis")
        if args.quantize_embeddings:
            parts.append("qemb")
        if args.real_quant:
            parts.append("real")
        out_filename = f"{'_'.join(parts)}.jsonl"
        output_path = SCRIPT_DIR / "data" / out_filename
    else:
        output_path = SCRIPT_DIR / args.output if not Path(args.output).is_absolute() else Path(args.output)

    output_path.parent.mkdir(parents=True, exist_ok=True)

    print(f"=== Liquid AI d1-omni-600M Decider (GPU Only) ===")
    print(f"Device: {device} ({torch.cuda.get_device_name(0)})")
    print(f"Mode: {args.mode}")
    print(f"Precision: {args.precision}")
    print(f"Max-side: {args.max_side}")
    print(f"Single-call (9 questions): {args.single_call}")
    print(f"Output: {output_path}")

    # Load OCR text map
    ocr_map: dict[str, str] = {}
    if input_ocr_path.exists():
        with input_ocr_path.open() as f:
            for line in f:
                if line.strip():
                    try:
                        rec = json.loads(line)
                        if "file" in rec:
                            ocr_map[rec["file"]] = rec.get("text") or ""
                    except Exception:
                        pass
        print(f"Loaded OCR map for {len(ocr_map)} screenshots from {input_ocr_path}")

    # Check already completed files if resuming
    done_files: set[str] = set()
    if output_path.exists() and not args.no_resume:
        with output_path.open() as f:
            for line in f:
                if line.strip():
                    try:
                        rec = json.loads(line)
                        if "file" in rec:
                            done_files.add(rec["file"])
                    except Exception:
                        pass
        print(f"Resuming: found {len(done_files)} completed records in {output_path}")

    # Determine files to process
    all_files = load_target_files(input_ocr_path, screenshots_dir, args.file_list)
    to_process = [f for f in all_files if f not in done_files]
    if args.limit is not None and args.limit > 0:
        to_process = to_process[:args.limit]

    print(f"Total candidate files: {len(all_files)}, to process: {len(to_process)}")
    if not to_process:
        print("No files to process. Done.")
        return 0

    # Load model and tokenizer on GPU
    torch_dtype = torch.float32 if args.precision == "fp32" else torch.float16
    print(f"Loading model {args.model_id} on {device} ({torch_dtype})...")
    from transformers import AutoModel, AutoTokenizer
    t0_load = time.perf_counter()
    model = AutoModel.from_pretrained(
        args.model_id,
        trust_remote_code=True,
        dtype=torch_dtype,
    ).to(device)

    # Audio tower is not needed for screenshots — delete and free VRAM
    if hasattr(model, "audio"):
        del model.audio
        model.audio = None
        torch.cuda.empty_cache()

    # Apply quantization if specified
    if args.precision in ("int8", "int4"):
        if args.real_quant and args.precision == "int8":
            print("Applying real ROCm int8 quantization via torchao Int8WeightOnlyConfig...")
            from torchao.quantization import Int8WeightOnlyConfig, quantize_
            # Quantize linear layers in trunk and head
            quantize_(model.encoder, Int8WeightOnlyConfig())
            quantize_(model.head, Int8WeightOnlyConfig())
            if not args.keep_vision_fp16 and hasattr(model, "vision"):
                quantize_(model.vision, Int8WeightOnlyConfig())
            print("torchao real int8 quantization applied.")
        else:
            print(f"Applying fake quantization ({args.precision}, group_size={args.group_size}, "
                  f"keep_vision_fp16={args.keep_vision_fp16}, quantize_emb={args.quantize_embeddings})...")
            qstats = d1_quant.apply_fake_quantization(
                model,
                precision=args.precision,
                group_size=args.group_size,
                keep_vision_fp16=args.keep_vision_fp16,
                quantize_embeddings=args.quantize_embeddings,
            )
            print(f"Fake quantization applied: {qstats}")

    model.eval()
    tokenizer = AutoTokenizer.from_pretrained(args.model_id, trust_remote_code=True)
    t_load = time.perf_counter() - t0_load
    print(f"Model ready on {device} in {t_load:.2f}s.")

    # Warmup pass
    torch.cuda.synchronize()
    torch.cuda.reset_peak_memory_stats()
    warmup_q = {"w": {"type": "noul", "instructions": "Is this a test?"}}
    _ = model.system_one("test state", warmup_q)
    torch.cuda.synchronize()

    # Processing loop
    durations: list[float] = []
    tokens_list: list[int] = []
    truncated_count = 0

    mode_write = "w" if (args.no_resume or not output_path.exists()) else "a"
    with output_path.open(mode_write) as out_f:
        for idx, file_name in enumerate(to_process, 1):
            torch.cuda.synchronize()
            t0 = time.perf_counter()

            # 1. Image loading & preprocessing
            pil_image: Image.Image | None = None
            n_tokens = 0
            if args.mode in ("image_ocr", "image_only"):
                img_path = screenshots_dir / file_name
                if not img_path.exists():
                    print(f"Warning: Screenshot {img_path} not found, skipping.")
                    continue
                raw_img = Image.open(img_path).convert("RGB")
                pil_image = resize_image_max_side(raw_img, args.max_side)
                n_tokens = compute_image_tokens(pil_image)

            # 2. Text preprocessing & truncation
            state: str | None = None
            truncated = False
            if args.mode in ("image_ocr", "ocr_only"):
                raw_text = ocr_map.get(file_name, "")
                clean_text = preprocess_ocr_text(raw_text, tokenizer=None)
                if not clean_text:
                    state = ""
                else:
                    if args.mode == "image_ocr":
                        # Tokenize and enforce budget of MAX_OCR_IMAGE_TOKENS
                        enc = tokenizer.encode(clean_text, add_special_tokens=False)
                        if len(enc) > MAX_OCR_IMAGE_TOKENS:
                            truncated = True
                            clean_text = tokenizer.decode(enc[:MAX_OCR_IMAGE_TOKENS], skip_special_tokens=True).strip()
                    state = clean_text

            if truncated:
                truncated_count += 1

            # 3. Model inference
            if args.mode == "ocr_only" and not state:
                # Empty OCR in ocr_only: null probabilities, 0.0 seconds
                coll_probs = {c: None for c in COLL_CATEGORIES}
                junk_probs = {t: None for t in JUNK_TAG_NAMES}
                elapsed = 0.0
            else:
                image_arg = [pil_image] if pil_image is not None else None
                with torch.no_grad():
                    if args.single_call:
                        res = model.system_one(state, ALL_QUESTIONS, images=image_arg)
                        answers = res.get("answers", {})
                        coll_probs = {
                            cat: float(answers[cat]["noul"]) if cat in answers and "noul" in answers[cat] else 0.0
                            for cat in COLL_CATEGORIES
                        }
                        junk_probs = {
                            tag: float(answers[tag]["noul"]) if tag in answers and "noul" in answers[tag] else 0.0
                            for tag in JUNK_TAG_NAMES
                        }
                    else:
                        # 2 calls: collections then junk
                        res_coll = model.system_one(state, COLL_QUESTIONS, images=image_arg)
                        ans_coll = res_coll.get("answers", {})
                        coll_probs = {
                            cat: float(ans_coll[cat]["noul"]) if cat in ans_coll and "noul" in ans_coll[cat] else 0.0
                            for cat in COLL_CATEGORIES
                        }

                        res_junk = model.system_one(state, JUNK_QUESTIONS, images=image_arg)
                        ans_junk = res_junk.get("answers", {})
                        junk_probs = {
                            tag: float(ans_junk[tag]["noul"]) if tag in ans_junk and "noul" in ans_junk[tag] else 0.0
                            for tag in JUNK_TAG_NAMES
                        }
                torch.cuda.synchronize()
                elapsed = time.perf_counter() - t0

            record = {
                "file": file_name,
                "coll": coll_probs,
                "junk": junk_probs,
                "seconds": round(elapsed, 4),
                "n_image_tokens": n_tokens,
                "truncated": truncated,
            }
            out_f.write(json.dumps(record) + "\n")
            out_f.flush()

            durations.append(elapsed)
            tokens_list.append(n_tokens)

            # Clear cache between items to prevent VRAM fragmentation on ROCm
            torch.cuda.empty_cache()

            # Brief logging per item
            coll_top = [f"{c}:{v:.2f}" for c, v in coll_probs.items() if v is not None and v >= 0.5]
            junk_top = [f"{t}:{v:.2f}" for t, v in junk_probs.items() if v is not None and v >= 0.5]
            colls_str = ",".join(coll_top) if coll_top else "none"
            junk_str = ",".join(junk_top) if junk_top else "none"
            print(f"[{idx}/{len(to_process)}] {file_name}: {elapsed:.2f}s | tokens={n_tokens} | coll=[{colls_str}] | junk=[{junk_str}]")

    # Final summary statistics
    # Exclude warm-up (first call) from median / percentile calculation if > 1 call
    if len(durations) > 1:
        steady_durations = durations[1:]
        med_s = float(np.median(steady_durations))
        p90_s = float(np.percentile(steady_durations, 90))
        mean_s = float(np.mean(steady_durations))
        warmup_s = durations[0]
    elif durations:
        med_s = p90_s = mean_s = warmup_s = durations[0]
    else:
        med_s = p90_s = mean_s = warmup_s = 0.0

    peak_vram_mb = torch.cuda.max_memory_allocated() / (1024.0 * 1024.0)
    peak_rss_mb = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024.0
    med_tokens = float(np.median(tokens_list)) if tokens_list else 0

    print("\n" + "=" * 60)
    print(f"Run Summary ({args.mode}, precision={args.precision}, max_side={args.max_side}, single_call={args.single_call}):")
    print(f"Processed: {len(durations)} screenshots")
    print(f"Warm-up call (item 1): {warmup_s:.2f}s")
    print(f"Steady median: {med_s:.2f}s/screenshot")
    print(f"Steady P90:    {p90_s:.2f}s/screenshot")
    print(f"Steady Mean:   {mean_s:.2f}s/screenshot")
    print(f"Image tokens (median): {med_tokens:.0f}")
    print(f"Truncated items: {truncated_count}/{len(durations)}")
    print(f"Peak VRAM: {peak_vram_mb:.1f} MB")
    print(f"Peak Host RSS: {peak_rss_mb:.1f} MB")
    print("=" * 60)

    return 0


if __name__ == "__main__":
    sys.exit(main())

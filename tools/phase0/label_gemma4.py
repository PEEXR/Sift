#!/usr/bin/env python3
"""Phase 0 labeler: labels phone screenshots with Gemma 4 E2B using LiteRT-LM.

CLI shape matches label_ornith.py:
    label_gemma4.py <screenshot_dir> <out.jsonl> [--limit N] [--think] [--max-side PX]
                    [--backend gpu|cpu] [--no-speculative] [--model PATH]
                    [--file-list PATH] [--keys-only] [--batch N] [--max-num-tokens N]
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path

import numpy as np

# Add tools/phase0 to sys.path so imports work regardless of cwd
TOOLS_DIR = Path(__file__).parent.resolve()
sys.path.insert(0, str(TOOLS_DIR))

import litert_lm
from common import CATEGORIES
from label_ornith import IMAGE_EXTS, SCHEMA, encode_image

DEFAULT_MODEL_PATH = TOOLS_DIR / "data" / "litert" / "gemma-4-E2B-it.litertlm"

PROMPT = (
    "You are labeling a phone screenshot for a dataset. Decide which of these categories it fits. "
    "Pick every category that clearly applies, or none if nothing fits.\n\n"
    + "\n".join(f"- {k}: {v}" for k, v in CATEGORIES.items())
    + "\n\nAlso report the app the screenshot was taken in if it is recognisable "
    '(e.g. x, whatsapp, instagram, reddit, telegram, youtube, upi_payment), else "unknown".\n'
    'Answer with only a JSON object: {"labels": [...], "source": "..."}'
)


def build_prompt(keys_only: bool = False, batch_size: int = 1) -> str:
    """Builds the instruction prompt according to keys_only and batch_size."""
    cat_lines = "\n".join(f"- {k}: {v}" for k, v in CATEGORIES.items())
    if batch_size > 1:
        if keys_only:
            return (
                "You are labeling phone screenshots for a dataset. Decide which of these categories each image fits. "
                "Pick every category that clearly applies, or none if nothing fits.\n\n"
                f"{cat_lines}\n\n"
                f"For each image numbered 1 to {batch_size}, provide one answer line in the form:\n"
                "1: tweets, memes\n"
                "2: none\n"
                f"Answer with only these {batch_size} lines, one per image."
            )
        else:
            return (
                "You are labeling phone screenshots for a dataset. Decide which of these categories each image fits. "
                "Pick every category that clearly applies, or none if nothing fits.\n\n"
                f"{cat_lines}\n\n"
                "Also report the app each screenshot was taken in if it is recognisable "
                '(e.g. x, whatsapp, instagram, reddit, telegram, youtube, upi_payment), else "unknown".\n'
                f'Answer with only a JSON array of objects for images 1 to {batch_size}: '
                '[{"i": 1, "labels": [...], "source": "..."}, {"i": 2, ...}]'
            )
    else:
        if keys_only:
            return (
                "You are labeling a phone screenshot for a dataset. Decide which of these categories it fits. "
                "Pick every category that clearly applies, or none if nothing fits.\n\n"
                f"{cat_lines}\n\n"
                "Answer with only the matching category keys, comma-separated, or the single word none."
            )
        else:
            return PROMPT


def parse_keys_only(raw_text: str) -> list[str]:
    """Parses category keys from keys-only model output.

    Splits on commas and whitespace, strips trailing/leading punctuation,
    and matches exact category keys. Returns sorted unique category keys; 'none' -> [].
    """
    cleaned = raw_text.strip().lower()
    if cleaned in ("none", "none."):
        return []
    tokens = re.split(r"[\s,]+", cleaned)
    labels = set()
    for tok in tokens:
        tok = tok.strip(".,;:\"'!?()[]{}")
        if tok in CATEGORIES:
            labels.add(tok)
    return sorted(labels)


def parse_batch_lines(raw_text: str, batch_size: int) -> dict[int, dict[str, any]]:
    """Parses lines for batch keys-only output.

    Expects one line per image like:
        1: tweets, memes
        2: none

    Returns a dict mapping 1-based index (1..batch_size) to:
        {"labels": list[str], "is_missing": bool}
    If a line for an index is missing or unparsable, is_missing is True and labels is [].
    """
    results: dict[int, dict[str, any]] = {
        i: {"labels": [], "is_missing": True} for i in range(1, batch_size + 1)
    }

    for line in raw_text.splitlines():
        line = line.strip()
        if not line:
            continue
        m = re.match(r"^(?:image\s*)?(\d+)[:.]\s*(.*)$", line, re.IGNORECASE)
        if not m:
            continue
        try:
            idx = int(m.group(1))
        except ValueError:
            continue
        if idx < 1 or idx > batch_size:
            continue

        content = m.group(2).strip()
        cleaned = content.lower().strip(".,;: ")
        if cleaned == "none":
            results[idx] = {"labels": [], "is_missing": False}
        else:
            labels = parse_keys_only(content)
            if labels:
                results[idx] = {"labels": labels, "is_missing": False}
            else:
                results[idx] = {"labels": [], "is_missing": True}

    return results


def parse_batch_json(raw_text: str, batch_size: int) -> dict[int, dict[str, any]]:
    """Parses JSON array or list of objects for batch mode without keys-only.

    Expects a JSON array of objects:
        [{"i": 1, "labels": [...], "source": "..."}, ...]

    Returns a dict mapping 1-based index (1..batch_size) to:
        {"labels": list[str], "source": str, "is_missing": bool}
    If an index is missing or unparsable, is_missing is True, labels is [], source is 'unknown'.
    """
    results: dict[int, dict[str, any]] = {
        i: {"labels": [], "source": "unknown", "is_missing": True}
        for i in range(1, batch_size + 1)
    }

    # 1. Try finding a JSON array [...]
    start = raw_text.find("[")
    end = raw_text.rfind("]")
    if start != -1 and end != -1 and end > start:
        candidate = raw_text[start : end + 1]
        try:
            data = json.loads(candidate)
            if isinstance(data, list):
                for item in data:
                    if isinstance(item, dict) and "i" in item:
                        try:
                            idx = int(item["i"])
                        except (ValueError, TypeError):
                            continue
                        if 1 <= idx <= batch_size:
                            raw_labels = item.get("labels", [])
                            if isinstance(raw_labels, list):
                                labels = sorted({str(l) for l in raw_labels if str(l) in CATEGORIES})
                            else:
                                labels = []
                            source = str(item.get("source", "unknown")).lower()
                            results[idx] = {
                                "labels": labels,
                                "source": source,
                                "is_missing": False,
                            }
        except Exception:
            pass

    # 2. Fallback: extract individual balanced {...} blocks with "i"
    missing_indices = [i for i, v in results.items() if v["is_missing"]]
    if missing_indices:
        idx_pos = 0
        while idx_pos < len(raw_text):
            obj_start = raw_text.find("{", idx_pos)
            if obj_start == -1:
                break
            depth = 0
            obj_end = -1
            for k in range(obj_start, len(raw_text)):
                if raw_text[k] == "{":
                    depth += 1
                elif raw_text[k] == "}":
                    depth -= 1
                    if depth == 0:
                        obj_end = k + 1
                        break
            if obj_end != -1:
                chunk = raw_text[obj_start:obj_end]
                try:
                    item = json.loads(chunk)
                    if isinstance(item, dict) and "i" in item:
                        idx = int(item["i"])
                        if 1 <= idx <= batch_size and results[idx]["is_missing"]:
                            raw_labels = item.get("labels", [])
                            if isinstance(raw_labels, list):
                                labels = sorted({str(l) for l in raw_labels if str(l) in CATEGORIES})
                            else:
                                labels = []
                            source = str(item.get("source", "unknown")).lower()
                            results[idx] = {
                                "labels": labels,
                                "source": source,
                                "is_missing": False,
                            }
                except Exception:
                    pass
                idx_pos = obj_end
            else:
                break

    return results


def parse_output(raw_text: str) -> tuple[list[str], str, bool]:
    """Parses labels and source from model raw output.

    Extracts the first balanced {...} block. On failure, falls back to scanning
    the text for category keys using whole-word matching and records fallback.
    Returns:
        (labels, source, is_fallback)
    """
    # 1. Try finding the first {...} block
    start = raw_text.find("{")
    if start != -1:
        depth = 0
        end = -1
        for idx in range(start, len(raw_text)):
            if raw_text[idx] == "{":
                depth += 1
            elif raw_text[idx] == "}":
                depth -= 1
                if depth == 0:
                    end = idx + 1
                    break
        if end != -1:
            candidate = raw_text[start:end]
            try:
                data = json.loads(candidate)
                if isinstance(data, dict):
                    raw_labels = data.get("labels", [])
                    if isinstance(raw_labels, list):
                        labels = sorted({str(l) for l in raw_labels if str(l) in CATEGORIES})
                    else:
                        labels = []
                    source = str(data.get("source", "unknown")).lower()
                    return labels, source, False
            except Exception:
                pass

    # 2. Fallback: scan text for category keys using whole-word matching only
    low = raw_text.lower()
    matched_labels = sorted({
        cat for cat in CATEGORIES
        if re.search(r"\b" + re.escape(cat) + r"\b", low)
    })
    source = "unknown"
    for app in ["x", "whatsapp", "instagram", "reddit", "telegram", "youtube", "upi_payment"]:
        if re.search(r"\b" + re.escape(app) + r"\b", low):
            source = app
            break
    return matched_labels, source, True


def label_screenshot(
    engine: litert_lm.Engine,
    path: Path,
    prompt: str,
    max_side: int,
    think: bool,
    keys_only: bool = False,
) -> tuple[dict, str, any]:
    """Runs inference on a single screenshot with a fresh conversation."""
    if max_side <= 0:
        image_content = {"type": "image", "path": str(path.resolve())}
    else:
        b64 = encode_image(path, max_side)
        image_content = {"type": "image", "blob": b64}

    message = {
        "role": "user",
        "content": [
            image_content,
            {"type": "text", "text": prompt},
        ],
    }

    sampler_cfg = litert_lm.SamplerConfig(temperature=0.0)
    thinking_cfg = (
        litert_lm.ThinkingConfig(enable_thinking=True, thinking_token_budget=-1)
        if think
        else None
    )
    if keys_only:
        max_tokens = 1024 if think else 16
    else:
        max_tokens = 1024 if think else 256

    with engine.create_conversation(
        sampler_config=sampler_cfg,
        thinking_config=thinking_cfg,
        max_output_tokens=max_tokens,
    ) as conv:
        resp = conv.send_message(message)
        bench = None
        try:
            bench = conv.get_benchmark_info()
        except Exception:
            pass
        raw_text = str(resp).strip()
        if keys_only:
            labels = parse_keys_only(raw_text)
            source = "unknown"
            result = {
                "labels": labels,
                "source": source,
                "is_fallback": False,
            }
        else:
            labels, source, is_fallback = parse_output(raw_text)
            result = {
                "labels": labels,
                "source": source,
                "is_fallback": is_fallback,
            }
        return result, raw_text, bench


def label_batch(
    engine: litert_lm.Engine,
    paths: list[Path],
    prompt: str,
    max_side: int,
    think: bool,
    keys_only: bool,
) -> tuple[dict[int, dict[str, any]], str, any]:
    """Runs inference on a batch of screenshots in ONE user message."""
    contents: list[dict[str, any]] = []
    for k, p in enumerate(paths, 1):
        contents.append({"type": "text", "text": f"Image {k}:"})
        if max_side <= 0:
            contents.append({"type": "image", "path": str(p.resolve())})
        else:
            b64 = encode_image(p, max_side)
            contents.append({"type": "image", "blob": b64})
    contents.append({"type": "text", "text": prompt})

    message = {
        "role": "user",
        "content": contents,
    }

    sampler_cfg = litert_lm.SamplerConfig(temperature=0.0)
    thinking_cfg = (
        litert_lm.ThinkingConfig(enable_thinking=True, thinking_token_budget=-1)
        if think
        else None
    )
    n = len(paths)
    if keys_only:
        max_tokens = (1024 if think else 16) * n
    else:
        max_tokens = (1024 if think else 256) * n

    with engine.create_conversation(
        sampler_config=sampler_cfg,
        thinking_config=thinking_cfg,
        max_output_tokens=max_tokens,
    ) as conv:
        resp = conv.send_message(message)
        bench = None
        try:
            bench = conv.get_benchmark_info()
        except Exception:
            pass
        raw_text = str(resp).strip()
        if keys_only:
            parsed = parse_batch_lines(raw_text, n)
        else:
            parsed = parse_batch_json(raw_text, n)
        return parsed, raw_text, bench


def main() -> int:
    ap = argparse.ArgumentParser(description="Label screenshots with Gemma 4 E2B via LiteRT-LM")
    ap.add_argument("screenshot_dir", type=Path, help="Directory containing screenshot images")
    ap.add_argument("out", type=Path, help="Output JSONL file")
    ap.add_argument("--limit", type=int, default=0, help="Maximum number of images to label")
    ap.add_argument("--think", action="store_true", help="Enable thinking/reasoning generation")
    ap.add_argument(
        "--max-side",
        type=int,
        default=0,
        help="Downscale longer side to PX (0 = send original, LiteRT-LM handles resizing)",
    )
    ap.add_argument(
        "--backend",
        choices=["gpu", "cpu"],
        default="gpu",
        help="Hardware backend (default: gpu)",
    )
    ap.add_argument(
        "--no-speculative",
        action="store_true",
        help="Disable speculative decoding (default is enabled)",
    )
    ap.add_argument(
        "--model",
        type=Path,
        default=DEFAULT_MODEL_PATH,
        help="Path to .litertlm model file",
    )
    ap.add_argument(
        "--file-list",
        type=Path,
        default=None,
        help="Only label file names listed (one per line) from screenshot_dir",
    )
    ap.add_argument(
        "--keys-only",
        action="store_true",
        help="Ask for only category keys comma-separated or 'none' (no JSON, source: unknown)",
    )
    ap.add_argument(
        "--batch",
        type=int,
        default=1,
        help="Number of images per batch in one message (default: 1)",
    )
    ap.add_argument(
        "--max-num-tokens",
        type=int,
        default=None,
        help="KV cache size passed to Engine (default: 600 * batch + 512 if batch > 1, else unset)",
    )
    args = ap.parse_args()

    # Resumable: find already processed files
    done = set()
    if args.out.exists():
        for line in args.out.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line:
                try:
                    done.add(json.loads(line)["file"])
                except Exception:
                    pass

    if args.file_list:
        if not args.file_list.exists():
            print(f"Error: file list {args.file_list} not found.", file=sys.stderr)
            return 1
        listed = [
            line.strip()
            for line in args.file_list.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.strip().startswith("#")
        ]
        files = [
            args.screenshot_dir / name
            for name in listed
            if (args.screenshot_dir / name).is_file() and (args.screenshot_dir / name).suffix.lower() in IMAGE_EXTS
        ]
    else:
        files = sorted(p for p in args.screenshot_dir.iterdir() if p.suffix.lower() in IMAGE_EXTS)

    todo = [p for p in files if p.name not in done]
    if args.limit > 0:
        todo = todo[:args.limit]

    print(
        f"{len(files)} images, {len(done)} already labeled, {len(todo)} to do",
        file=sys.stderr,
    )
    if not todo:
        print("Nothing to do.", file=sys.stderr)
        return 0

    # Measure engine load time separately
    t_load_start = time.monotonic()
    backend_obj = litert_lm.Backend.GPU() if args.backend == "gpu" else litert_lm.Backend.CPU()
    vision_backend_obj = litert_lm.Backend.GPU() if args.backend == "gpu" else litert_lm.Backend.CPU()
    enable_speculative = not args.no_speculative

    if args.max_num_tokens is not None:
        max_tokens_val = args.max_num_tokens
    elif args.batch > 1:
        max_tokens_val = 600 * args.batch + 512
    else:
        max_tokens_val = None

    engine_kwargs: dict[str, any] = {
        "model_path": str(args.model.resolve()),
        "backend": backend_obj,
        "vision_backend": vision_backend_obj,
        "enable_speculative_decoding": enable_speculative,
        "enable_benchmark": True,
    }
    if max_tokens_val is not None:
        engine_kwargs["max_num_tokens"] = max_tokens_val
    if args.batch > 1:
        engine_kwargs["max_num_images"] = args.batch

    print(
        f"Loading LiteRT-LM engine (model={args.model.name}, backend={args.backend}, speculative={enable_speculative})...",
        file=sys.stderr,
    )
    engine = litert_lm.Engine(**engine_kwargs)
    engine_load_time = round(time.monotonic() - t_load_start, 2)
    print(f"Engine loaded in {engine_load_time}s", file=sys.stderr)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    seconds_list: list[float] = []
    decode_tokens_list: list[float] = []

    try:
        with args.out.open("a", encoding="utf-8") as f:
            if args.batch <= 1:
                prompt_text = build_prompt(keys_only=args.keys_only, batch_size=1)
                for i, p in enumerate(todo, 1):
                    t0 = time.monotonic()
                    try:
                        parsed_res, raw_text, bench = label_screenshot(
                            engine=engine,
                            path=p,
                            prompt=prompt_text,
                            max_side=args.max_side,
                            think=args.think,
                            keys_only=args.keys_only,
                        )
                    except Exception as e:
                        print(f"[{i}/{len(todo)}] {p.name}: ERROR {e}", file=sys.stderr)
                        continue

                    duration = round(time.monotonic() - t0, 2)
                    seconds_list.append(duration)

                    rec: dict[str, any] = {
                        "file": p.name,
                        "labels": parsed_res["labels"],
                        "source": parsed_res["source"],
                        "seconds": duration,
                        "raw": raw_text[:300],
                    }
                    if parsed_res.get("is_fallback"):
                        rec["parse"] = "fallback"

                    if bench is not None:
                        rec["prefill_tokens"] = bench.last_prefill_token_count
                        rec["prefill_tps"] = round(bench.last_prefill_tokens_per_second, 2)
                        rec["decode_tokens"] = bench.last_decode_token_count
                        rec["decode_tps"] = round(bench.last_decode_tokens_per_second, 2)
                        rec["ttft_s"] = round(bench.time_to_first_token_in_second, 3)
                        decode_tokens_list.append(bench.last_decode_token_count)

                    f.write(json.dumps(rec) + "\n")
                    f.flush()

                    print(
                        f"[{i}/{len(todo)}] {rec['file']}: {rec['labels']} {rec['source']} ({rec['seconds']}s)",
                        file=sys.stderr,
                    )
            else:
                batches = [todo[k : k + args.batch] for k in range(0, len(todo), args.batch)]
                img_counter = 0
                for b_idx, batch_files in enumerate(batches, 1):
                    n_in_batch = len(batch_files)
                    prompt_text = build_prompt(keys_only=args.keys_only, batch_size=n_in_batch)
                    t0 = time.monotonic()
                    try:
                        parsed_batch, raw_text, bench = label_batch(
                            engine=engine,
                            paths=batch_files,
                            prompt=prompt_text,
                            max_side=args.max_side,
                            think=args.think,
                            keys_only=args.keys_only,
                        )
                    except Exception as e:
                        print(f"[Batch {b_idx}/{len(batches)}] ERROR {e}", file=sys.stderr)
                        continue

                    batch_duration = round(time.monotonic() - t0, 2)
                    sec_per_img = round(batch_duration / n_in_batch, 2)

                    for k, p in enumerate(batch_files, 1):
                        img_counter += 1
                        seconds_list.append(sec_per_img)
                        item = parsed_batch[k]

                        rec: dict[str, any] = {
                            "file": p.name,
                            "labels": item["labels"],
                            "source": item.get("source", "unknown"),
                            "seconds": sec_per_img,
                            "batch_seconds": batch_duration,
                            "batch_size": n_in_batch,
                            "batch_index": k,
                            "raw": raw_text[:300],
                        }
                        if item["is_missing"]:
                            rec["parse"] = "missing"

                        if bench is not None:
                            rec["prefill_tokens"] = bench.last_prefill_token_count
                            rec["prefill_tps"] = round(bench.last_prefill_tokens_per_second, 2)
                            rec["decode_tokens"] = bench.last_decode_token_count
                            rec["decode_tps"] = round(bench.last_decode_tokens_per_second, 2)
                            rec["ttft_s"] = round(bench.time_to_first_token_in_second, 3)
                            decode_tokens_list.append(bench.last_decode_token_count / n_in_batch)

                        f.write(json.dumps(rec) + "\n")
                        f.flush()

                        status_str = " [parse: missing]" if item["is_missing"] else ""
                        print(
                            f"[{img_counter}/{len(todo)}] {rec['file']}: {rec['labels']} {rec['source']} "
                            f"({rec['seconds']}s/img, batch {batch_duration}s){status_str}",
                            file=sys.stderr,
                        )
    finally:
        engine.close()

    # Print summary at the end
    if seconds_list:
        mean_sec = round(float(np.mean(seconds_list)), 2)
        median_sec = round(float(np.median(seconds_list)), 2)
    else:
        mean_sec = median_sec = 0.0

    mean_decode = round(float(np.mean(decode_tokens_list)), 1) if decode_tokens_list else 0.0

    print(
        f"\nDone: {len(seconds_list)} images labeled. "
        f"Mean: {mean_sec}s, Median: {median_sec}s per image. "
        f"Mean decode tokens: {mean_decode}. "
        f"Engine load time: {engine_load_time}s",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())

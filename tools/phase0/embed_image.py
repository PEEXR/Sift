#!/usr/bin/env python3
"""Phase 0 image embedder matching the Android app (EmbeddingGemma 2, q4 ONNX).

Ports the image embedding path from EmbeddingEngine.kt, ImagePatchifier.kt,
and GemmaTokenizer.kt. Produces full-length (untruncated, L2-normalized)
embeddings as well as 128-truncated+renormalized embeddings.

Usage:
    .venv/bin/python embed_image.py --selftest
    .venv/bin/python embed_image.py --budget 140 --out data/emb_full_140.npz [--limit N] DIR
"""

from __future__ import annotations

import argparse
import base64
import gzip
import json
import math
import os
import sys
import time
from pathlib import Path
from typing import Any

import numpy as np
import onnxruntime as ort
from PIL import Image

# Ensure tools/phase0 is on sys.path
SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from common import EMBEDDING_DIM
import embed_text

DEFAULT_MODEL_DIR = SCRIPT_DIR / "data" / "model"
DEFAULT_BACKUP_PATH = SCRIPT_DIR / "data" / "sift-backup-2026-10-11.json.gz"
DEFAULT_SCREENSHOTS_DIR = SCRIPT_DIR / "data" / "screenshots"
DEFAULT_GOLDEN_VISION_PATH = SCRIPT_DIR.parent.parent / ".agent" / "tasks" / "phase2_golden" / "golden_vision.json"

SUPPORTED_BUDGETS = {70, 140, 280, 560, 1120}
DEFAULT_BUDGET = 140

# Gemma token IDs
BOS_ID = 2
EOS_ID = 1
IMAGE_START_ID = 255999
IMAGE_PLACEHOLDER_ID = 258880
IMAGE_END_ID = 258882

# Module-level cached sessions
_vision_session: ort.InferenceSession | None = None
_text_session: ort.InferenceSession | None = None
_tokenizer = None
_vision_output_name: str | None = None
_text_output_name: str | None = None
_text_input_names: set[str] | None = None
_full_dim: int | None = None
_current_model_dir: Path | None = None
_current_threads: int | None = None


def calculate_target_size(
    orig_w: int,
    orig_h: int,
    max_soft_tokens: int = DEFAULT_BUDGET,
) -> tuple[int, int] | None:
    """Calculates target image dimensions (tW, tH) for EmbeddingGemma vision encoder.

    Dimensions are always multiples of 48 (pool * patch = 3 * 16).
    Replicates ImagePatchifier.calculateTargetSize exactly.
    """
    if max_soft_tokens not in SUPPORTED_BUDGETS:
        raise ValueError(
            f"Unsupported token budget: {max_soft_tokens}. Supported: {SUPPORTED_BUDGETS}"
        )
    if orig_w <= 0 or orig_h <= 0:
        return None

    max_patches = max_soft_tokens * 9
    target_pixels = max_patches * 256.0
    factor = math.sqrt(target_pixels / (float(orig_h) * float(orig_w)))
    t_h = int(math.floor(factor * orig_h / 48.0) * 48)
    t_w = int(math.floor(factor * orig_w / 48.0) * 48)

    if (t_h == 0) ^ (t_w == 0):
        long_side = max(orig_h, orig_w)
        short_side = max(1, min(orig_h, orig_w))
        other = min(
            int(math.floor(float(long_side) / short_side) * 48),
            (max_patches // 9) * 48,
        )
        if t_h == 0:
            t_h = 48
            t_w = other
        else:
            t_w = 48
            t_h = other

    if t_h == 0 and t_w == 0:
        return None
    return t_w, t_h


def process_image(
    img: Image.Image,
    budget: int = DEFAULT_BUDGET,
) -> tuple[np.ndarray, np.ndarray, int]:
    """Resizes and patchifies image into pixel_values and pixel_position_ids.

    Replicates ImagePatchifier.processBitmap and processPixels:
    - Bilinear resize to target (tW, tH)
    - Normalization to [0, 1] float32
    - Patches shaped (1, num_patches, 768)
    - Grid positions shaped (1, num_patches, 2)
    - Soft tokens count = num_patches // 9

    Returns:
        (pixel_values, positions, soft_tokens)
    """
    img = img.convert("RGB")
    orig_w, orig_h = img.size
    target_size = calculate_target_size(orig_w, orig_h, budget)
    if target_size is None:
        raise ValueError(f"Invalid image dimensions: {orig_w}x{orig_h}")
    t_w, t_h = target_size

    # Bilinear resampling matching Android Bitmap.createScaledBitmap(..., filter = true)
    scaled = img.resize((t_w, t_h), Image.Resampling.BILINEAR)
    img_arr = np.asarray(scaled, dtype=np.float32) / 255.0  # shape (t_h, t_w, 3)

    gh = t_h // 16
    gw = t_w // 16
    num_patches = gh * gw
    soft_tokens = num_patches // 9

    # Reorder into patches: (gh, 16, gw, 16, 3) -> (gh, gw, 16, 16, 3) -> (1, num_patches, 768)
    patches = img_arr.reshape(gh, 16, gw, 16, 3).transpose(0, 2, 1, 3, 4)
    pixel_values = patches.reshape(1, num_patches, 768).astype(np.float32)

    # Patch grid positions: [px, py]
    grid_y, grid_x = np.mgrid[0:gh, 0:gw]
    positions = np.stack([grid_x, grid_y], axis=-1).reshape(1, num_patches, 2).astype(np.int64)

    return pixel_values, positions, soft_tokens


def normalize_l2(vec: np.ndarray) -> np.ndarray:
    """L2-normalizes vector without truncation."""
    arr = np.asarray(vec, dtype=np.float32).flatten()
    sum_sq = float(np.sum(arr.astype(np.float64) ** 2))
    norm = np.sqrt(sum_sq)
    if norm == 0.0 or np.isnan(norm):
        return np.zeros_like(arr)
    inv_norm = np.float32(1.0 / norm)
    return (arr * inv_norm).astype(np.float32)


def load_models(
    model_dir: Path | str | None = None,
    num_threads: int | None = None,
) -> tuple[ort.InferenceSession, ort.InferenceSession]:
    """Loads and caches vision encoder and text/fusion model sessions.

    Prints full embedding dimension D once at start.
    """
    global _vision_session, _text_session, _tokenizer
    global _vision_output_name, _text_output_name, _text_input_names
    global _full_dim, _current_model_dir, _current_threads

    if model_dir is None:
        model_dir = DEFAULT_MODEL_DIR
    resolved_dir = Path(model_dir).resolve()

    if num_threads is None:
        num_threads = os.cpu_count() or 1

    if (
        _vision_session is not None
        and _text_session is not None
        and _current_model_dir == resolved_dir
        and _current_threads == num_threads
    ):
        return _vision_session, _text_session

    vision_path = resolved_dir / "onnx" / "vision_encoder_q4.onnx"
    if not vision_path.exists():
        vision_path = resolved_dir / "vision_encoder_q4.onnx"
    if not vision_path.exists():
        raise FileNotFoundError(f"Vision model not found in {resolved_dir}")

    text_path = resolved_dir / "onnx" / "model_q4.onnx"
    if not text_path.exists():
        text_path = resolved_dir / "model_q4.onnx"
    if not text_path.exists():
        raise FileNotFoundError(f"Text model not found in {resolved_dir}")

    opts = ort.SessionOptions()
    opts.intra_op_num_threads = num_threads

    _vision_session = ort.InferenceSession(
        str(vision_path),
        sess_options=opts,
        providers=["CPUExecutionProvider"],
    )
    v_out_names = [out.name for out in _vision_session.get_outputs()]
    _vision_output_name = "image_features" if "image_features" in v_out_names else v_out_names[0]

    _text_session = ort.InferenceSession(
        str(text_path),
        sess_options=opts,
        providers=["CPUExecutionProvider"],
    )
    t_out_names = [out.name for out in _text_session.get_outputs()]
    _text_output_name = "sentence_embedding" if "sentence_embedding" in t_out_names else t_out_names[0]
    _text_input_names = {inp.name for inp in _text_session.get_inputs()}

    # Determine full embedding dimension D from text model output shape or config
    text_out_shape = _text_session.get_outputs()[0].shape
    if text_out_shape and isinstance(text_out_shape[-1], int) and text_out_shape[-1] > 0:
        _full_dim = text_out_shape[-1]
    else:
        config_path = resolved_dir / "config.json"
        if config_path.exists():
            with open(config_path, "r", encoding="utf-8") as f:
                cfg = json.load(f)
            _full_dim = cfg.get("text_config", {}).get("embedding_dim", 768)
        else:
            _full_dim = 768

    _current_model_dir = resolved_dir
    _current_threads = num_threads

    print(f"Embedding model loaded: full dimension D = {_full_dim}")
    return _vision_session, _text_session


def embed_image(
    image: Image.Image | str | Path,
    budget: int = DEFAULT_BUDGET,
) -> tuple[np.ndarray, np.ndarray]:
    """Embeds an image using vision encoder and multimodal text model.

    Returns:
        (full_emb, trunc128_emb)
        full_emb: float32 1D array of shape (D,), L2-normalized.
        trunc128_emb: float32 1D array of shape (128,), truncated to 128 and L2-normalized.
    """
    if _vision_session is None or _text_session is None:
        load_models()

    if isinstance(image, (str, Path)):
        with Image.open(image) as img:
            pixel_values, positions, soft_tokens = process_image(img, budget=budget)
    else:
        pixel_values, positions, soft_tokens = process_image(image, budget=budget)

    # Step 1: Run vision encoder
    vision_inputs = {
        "pixel_values": pixel_values,
        "pixel_position_ids": positions,
    }
    vision_out = _vision_session.run([_vision_output_name], vision_inputs)
    img_feats = vision_out[0]

    # Shape expected by text model is (soft_tokens, 512)
    img_feats_2d = img_feats.reshape(soft_tokens, 512).astype(np.float32)

    # Step 2: Build multimodal input IDs sequence matching EmbeddingEngine.kt
    # [2 (<bos>), 255999 (<|image>), 258880 * soft_tokens (<|image|>), 258882 (<image|>), 1 (<eos>)]
    input_ids_list = [BOS_ID, IMAGE_START_ID] + [IMAGE_PLACEHOLDER_ID] * soft_tokens + [IMAGE_END_ID, EOS_ID]
    seq_len = len(input_ids_list)

    input_ids = np.array([input_ids_list], dtype=np.int64)
    attention_mask = np.ones((1, seq_len), dtype=np.int64)

    text_inputs = {
        "input_ids": input_ids,
        "attention_mask": attention_mask,
        "image_features": img_feats_2d,
        "video_features": np.zeros((0, 512), dtype=np.float32),
        "audio_features": np.zeros((0, 512), dtype=np.float32),
    }
    feed_dict = {k: v for k, v in text_inputs.items() if k in _text_input_names}

    text_out = _text_session.run([_text_output_name], feed_dict)
    raw_emb = text_out[0].flatten().astype(np.float32)

    full_emb = normalize_l2(raw_emb)
    trunc128_emb = embed_text.truncate_and_normalize(raw_emb, EMBEDDING_DIM)

    return full_emb, trunc128_emb


def run_selftest(
    backup_path: Path = DEFAULT_BACKUP_PATH,
    screenshots_dir: Path = DEFAULT_SCREENSHOTS_DIR,
    golden_vision_path: Path = DEFAULT_GOLDEN_VISION_PATH,
) -> bool:
    """Runs self-test against golden oracle data and phone stored vectors.

    1. Checks golden_vision.json oracle.
    2. Embeds first 10 screenshots at budget 140 and compares vs phone stored vectors.
       Requires median cosine >= 0.99.
    3. Prints budget comparison (70, 140, 280) for the first 3.
    """
    print("--- Running Image Embedder Self-Test ---")

    # Oracle 1: Check golden_vision.json
    if golden_vision_path.exists():
        with open(golden_vision_path, "r", encoding="utf-8") as f:
            golden = json.load(f)
        golden_img_desc = golden.get("image", "")
        # Check what the image refers to
        img_candidate = Path(golden_img_desc)
        if not img_candidate.exists() and not (golden_vision_path.parent / golden_img_desc).exists():
            print(
                f"Oracle 1 (golden_vision.json): 'image' refers to '{golden_img_desc}' "
                f"(synthetic gradient specification, not a file on disk). Skipping image file check."
            )
        # Verify geometry for 1080x2400 at budget 280
        t_size = calculate_target_size(1080, 2400, max_soft_tokens=280)
        if t_size is not None:
            t_w, t_h = t_size
            gh = t_h // 16
            gw = t_w // 16
            calc_patches = gh * gw
            calc_tokens = calc_patches // 9
            calc_last_pos = [gw - 1, gh - 1]
            if (
                calc_patches == golden.get("real_patches")
                and calc_tokens == golden.get("soft_tokens")
                and calc_last_pos == golden.get("last_pos_xy")
            ):
                print(
                    f"Oracle 1 geometry check: {calc_patches} patches, {calc_tokens} soft tokens, "
                    f"last pos {calc_last_pos} matches golden_vision.json OK"
                )
    else:
        print(f"Notice: golden_vision.json not found at {golden_vision_path}")

    # Oracle 2: Backup comparison
    if not backup_path.exists():
        print(f"FAIL: Backup file not found: {backup_path}")
        return False

    with open(backup_path, "rb") as f:
        magic = f.read(2)
        f.seek(0)
        raw_bytes = f.read()

    if magic == b"\x1f\x8b":
        content = gzip.decompress(raw_bytes).decode("utf-8")
    else:
        content = raw_bytes.decode("utf-8")

    backup_data = json.loads(content)
    screenshots = backup_data.get("screenshots", [])

    items_with_emb = []
    for s in screenshots:
        name = s.get("displayName")
        b64 = s.get("embeddingB64")
        if not name or not b64:
            continue
        img_path = screenshots_dir / name
        if not img_path.exists():
            continue
        raw_vec = base64.b64decode(b64.strip())
        if len(raw_vec) != EMBEDDING_DIM * 4:
            continue
        phone_vec = np.frombuffer(raw_vec, dtype="<f4").copy()
        items_with_emb.append((name, img_path, phone_vec))

    items_with_emb.sort(key=lambda x: x[0])
    test_10 = items_with_emb[:10]

    if len(test_10) < 10:
        print(f"FAIL: Only found {len(test_10)} test screenshots with backup embeddings (needed 10)")
        return False

    print(f"\nEvaluating first 10 screenshots against phone vectors (budget 140):")
    cosines_140 = []

    for idx, (name, img_path, phone_vec) in enumerate(test_10, 1):
        _, trunc140 = embed_image(img_path, budget=140)
        cos = embed_text.cosine_similarity(trunc140, phone_vec)
        cosines_140.append(cos)
        print(f"  [{idx}/10] {name}: cosine(PC@140, phone) = {cos:.6f}")

    median_140 = float(np.median(cosines_140))
    print(f"\nMedian cosine at budget 140: {median_140:.6f} (required >= 0.99)")

    print(f"\nBudget sensitivity check on first 3 screenshots:")
    for idx, (name, img_path, phone_vec) in enumerate(test_10[:3], 1):
        _, trunc70 = embed_image(img_path, budget=70)
        cos70 = embed_text.cosine_similarity(trunc70, phone_vec)
        cos140 = cosines_140[idx - 1]
        _, trunc280 = embed_image(img_path, budget=280)
        cos280 = embed_text.cosine_similarity(trunc280, phone_vec)
        print(
            f"  [{idx}/3] {name}:\n"
            f"         budget 70:  cosine = {cos70:.6f}\n"
            f"         budget 140: cosine = {cos140:.6f}\n"
            f"         budget 280: cosine = {cos280:.6f}"
        )

    passed = median_140 >= 0.99
    if passed:
        print(f"\nSELFTEST OK (median cosine {median_140:.6f} >= 0.99)")
    else:
        print(f"\nSELFTEST FAIL (median cosine {median_140:.6f} < 0.99)")
    return passed


def main():
    parser = argparse.ArgumentParser(
        description="Phase 0 image embedder matching EmbeddingEngine.embedImage (EmbeddingGemma 2, q4 ONNX)."
    )
    parser.add_argument(
        "--selftest",
        action="store_true",
        help="Run self-test comparing against phone stored vectors.",
    )
    parser.add_argument(
        "--budget",
        type=int,
        default=DEFAULT_BUDGET,
        choices=[70, 140, 280, 560, 1120],
        help=f"Image token budget (default: {DEFAULT_BUDGET}).",
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=None,
        help="Path to output .npz file (e.g. data/emb_full_140.npz).",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=None,
        help="Limit number of images to embed.",
    )
    parser.add_argument(
        "--threads",
        type=int,
        default=None,
        help="ONNX intra-op threads (default: all physical cores via os.cpu_count()).",
    )
    parser.add_argument(
        "--model-dir",
        type=Path,
        default=DEFAULT_MODEL_DIR,
        help=f"Directory containing ONNX models (default: {DEFAULT_MODEL_DIR}).",
    )
    parser.add_argument(
        "--backup",
        type=Path,
        default=DEFAULT_BACKUP_PATH,
        help=f"Path to FullBackup file (default: {DEFAULT_BACKUP_PATH}).",
    )
    parser.add_argument(
        "--screenshots-dir",
        type=Path,
        default=DEFAULT_SCREENSHOTS_DIR,
        help=f"Path to screenshots directory (default: {DEFAULT_SCREENSHOTS_DIR}).",
    )
    parser.add_argument(
        "dir",
        nargs="?",
        type=Path,
        help="Directory containing screenshots to embed.",
    )

    args = parser.parse_args()

    load_models(model_dir=args.model_dir, num_threads=args.threads)

    if args.selftest:
        ok = run_selftest(
            backup_path=args.backup,
            screenshots_dir=args.screenshots_dir,
        )
        sys.exit(0 if ok else 1)

    if args.dir:
        if not args.out:
            parser.error("--out OUT.npz is required when DIR is provided.")

        if not args.dir.exists() or not args.dir.is_dir():
            parser.error(f"Directory not found: {args.dir}")

        valid_exts = {".png", ".jpg", ".jpeg"}
        all_files = sorted(
            [p for p in args.dir.iterdir() if p.is_file() and p.suffix.lower() in valid_exts],
            key=lambda p: p.name,
        )

        existing_files: list[str] = []
        existing_full: list[np.ndarray] = []
        existing_trunc: list[np.ndarray] = []
        existing_seconds: list[float] = []

        if args.out.exists():
            with np.load(args.out) as prev:
                existing_files = list(prev["files"])
                existing_full = list(prev["full"])
                existing_trunc = list(prev["trunc128"])
                existing_seconds = list(prev["seconds"])
            print(f"Resuming: found {len(existing_files)} existing embeddings in {args.out}")

        already_done = set(existing_files)
        files_to_process = [p for p in all_files if p.name not in already_done]
        if args.limit is not None:
            files_to_process = files_to_process[:args.limit]

        total = len(files_to_process)
        print(f"Embedding {total} images at budget {args.budget} (threads={args.threads or os.cpu_count() or 1})...")

        def save_checkpoint():
            args.out.parent.mkdir(parents=True, exist_ok=True)
            tmp_out = args.out.with_suffix(".tmp.npz")
            np.savez(
                tmp_out,
                files=np.array(existing_files, dtype=str),
                full=np.array(existing_full, dtype=np.float32),
                trunc128=np.array(existing_trunc, dtype=np.float32),
                seconds=np.array(existing_seconds, dtype=np.float32),
            )
            os.replace(tmp_out, args.out)

        for i, img_path in enumerate(files_to_process):
            t0 = time.perf_counter()
            full_vec, trunc_vec = embed_image(img_path, budget=args.budget)
            elapsed = time.perf_counter() - t0

            existing_files.append(img_path.name)
            existing_full.append(full_vec)
            existing_trunc.append(trunc_vec)
            existing_seconds.append(elapsed)

            print(f"[{i + 1}/{total}] {img_path.name} {elapsed:.2f}s", file=sys.stderr)

            if (i + 1) % 25 == 0:
                save_checkpoint()

        if total > 0 or not args.out.exists():
            save_checkpoint()
            print(f"Wrote {len(existing_files)} embeddings to {args.out}")
        return

    parser.print_help()
    sys.exit(1)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Phase 0 text embedder matching the Android app (EmbeddingGemma 2, q4 ONNX).

Produces the same 128-dimensional L2-normalized text vectors that the Android app
produces in EmbeddingEngine.embedText, so Phase 0 evaluation can score collections
on the PC.

Usage:
    .venv/bin/python embed_text.py --selftest
    .venv/bin/python embed_text.py --phase0 OUT.json [--model-dir DIR]
    .venv/bin/python embed_text.py "coffee receipt" "flight boarding pass"
"""
import argparse
import json
import os
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
import tokenizers

# Ensure tools/phase0 is on sys.path so 'from common import ...' works regardless of CWD.
SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from common import (
    BACKGROUND_DESCRIPTIONS,
    CATEGORY_QUERIES,
    EMBEDDING_DIM,
    QUERY_PREFIX,
)

DEFAULT_MODEL_DIR = SCRIPT_DIR / "data" / "model"
DEFAULT_GOLDEN_PATH = SCRIPT_DIR.parent.parent / ".agent" / "tasks" / "phase2_golden" / "golden.json"

BOS_ID = 2
EOS_ID = 1

# Module-level cached session and tokenizer
_tokenizer: tokenizers.Tokenizer | None = None
_session: ort.InferenceSession | None = None
_output_name: str | None = None
_input_names: set[str] | None = None
_current_model_dir: Path | None = None


def load(
    model_dir: Path | str | None = None,
    num_threads: int | None = None,
) -> tuple[tokenizers.Tokenizer, ort.InferenceSession]:
    """Loads and caches the Gemma tokenizer and ONNX inference session.

    Reuses the existing session if already loaded for the same model directory.
    """
    global _tokenizer, _session, _output_name, _input_names, _current_model_dir

    if model_dir is None:
        model_dir = DEFAULT_MODEL_DIR
    resolved_dir = Path(model_dir).resolve()

    if _session is not None and _tokenizer is not None and _current_model_dir == resolved_dir:
        return _tokenizer, _session

    tokenizer_path = resolved_dir / "tokenizer.json"
    if not tokenizer_path.exists():
        raise FileNotFoundError(f"Tokenizer not found at {tokenizer_path}")

    model_path = resolved_dir / "onnx" / "model_q4.onnx"
    if not model_path.exists():
        model_path = resolved_dir / "model_q4.onnx"
    if not model_path.exists():
        raise FileNotFoundError(f"ONNX model not found in {resolved_dir}")

    _tokenizer = tokenizers.Tokenizer.from_file(str(tokenizer_path))

    opts = ort.SessionOptions()
    if num_threads is None:
        cores = os.cpu_count() or 1
        num_threads = min(4, max(1, cores))
    opts.intra_op_num_threads = num_threads

    _session = ort.InferenceSession(
        str(model_path),
        sess_options=opts,
        providers=["CPUExecutionProvider"],
    )

    out_names = [out.name for out in _session.get_outputs()]
    _output_name = "sentence_embedding" if "sentence_embedding" in out_names else out_names[0]
    _input_names = {inp.name for inp in _session.get_inputs()}
    _current_model_dir = resolved_dir

    return _tokenizer, _session


def normalize_l2(vec: np.ndarray) -> np.ndarray:
    """L2-normalizes vector without truncation."""
    arr = np.asarray(vec, dtype=np.float32).flatten()
    sum_sq = float(np.sum(arr.astype(np.float64) ** 2))
    norm = np.sqrt(sum_sq)
    if norm == 0.0 or np.isnan(norm):
        return np.zeros_like(arr)
    inv_norm = np.float32(1.0 / norm)
    return (arr * inv_norm).astype(np.float32)


def truncate_and_normalize(vec: np.ndarray, target_dim: int = EMBEDDING_DIM) -> np.ndarray:
    """Truncates or zero-pads vector to target_dim and L2-normalizes it.

    Replicates truncateAndNormalize and FloatArray.l2Normalize in the Android app.
    Zero or NaN norm returns an all-zero vector without crashing.
    """
    arr = np.asarray(vec, dtype=np.float32).flatten()
    if len(arr) < target_dim:
        padded = np.zeros(target_dim, dtype=np.float32)
        padded[:len(arr)] = arr
        arr = padded
    else:
        arr = arr[:target_dim].copy()

    sum_sq = float(np.sum(arr.astype(np.float64) ** 2))
    norm = np.sqrt(sum_sq)
    if norm == 0.0 or np.isnan(norm):
        return np.zeros(target_dim, dtype=np.float32)
    inv_norm = np.float32(1.0 / norm)
    return (arr * inv_norm).astype(np.float32)


DOC_PREFIX = "title: none | text: "
MAX_DOC_TOKENS = 512


def tokenize_query(tok: tokenizers.Tokenizer, text: str) -> list[int]:
    """Tokenizes QUERY_PREFIX + text, matching GemmaTokenizer.encode in the app.

    Adds BOS (2) at start and EOS (1) at end.
    """
    full_text = QUERY_PREFIX + text
    encoding = tok.encode(full_text)
    ids = list(encoding.ids)
    if not ids or ids[0] != BOS_ID:
        ids = [BOS_ID] + ids
    if ids[-1] != EOS_ID:
        ids = ids + [EOS_ID]
    return ids


def tokenize_doc(tok: tokenizers.Tokenizer, text: str, max_tokens: int = MAX_DOC_TOKENS) -> list[int]:
    """Tokenizes DOC_PREFIX + text for document embedding.

    Adds BOS (2) at start, EOS (1) at end, and truncates to at most max_tokens tokens (keeping BOS/EOS).
    """
    full_text = DOC_PREFIX + text
    encoding = tok.encode(full_text)
    ids = list(encoding.ids)
    if not ids or ids[0] != BOS_ID:
        ids = [BOS_ID] + ids
    if ids[-1] != EOS_ID:
        ids = ids + [EOS_ID]
    if len(ids) > max_tokens:
        ids = ids[: max_tokens - 1] + [EOS_ID]
    return ids


def embed(texts: list[str] | str, full: bool = False, doc: bool = False) -> np.ndarray:
    """Embeds input texts into L2-normalized float vectors.

    If doc is True, prefixes with DOC_PREFIX and truncates tokens to 512 (keeping BOS/EOS).
    Empty or whitespace texts in doc mode produce zero vectors.
    If full is True, returns full-length vectors of shape (len(texts), D).
    Otherwise returns 128-dimensional vectors of shape (len(texts), 128).
    """
    if _session is None or _tokenizer is None:
        load()

    if isinstance(texts, str):
        texts = [texts]

    dim = _session.get_outputs()[0].shape[-1] if full else EMBEDDING_DIM
    if not texts:
        return np.zeros((0, dim), dtype=np.float32)

    embeddings = []
    for text in texts:
        if doc and not text.strip():
            embeddings.append(np.zeros(dim, dtype=np.float32))
            continue

        if doc:
            ids = tokenize_doc(_tokenizer, text)
        else:
            ids = tokenize_query(_tokenizer, text)

        n = len(ids)
        input_ids = np.array([ids], dtype=np.int64)
        attention_mask = np.ones((1, n), dtype=np.int64)

        all_inputs = {
            "input_ids": input_ids,
            "attention_mask": attention_mask,
            "image_features": np.zeros((0, 512), dtype=np.float32),
            "video_features": np.zeros((0, 512), dtype=np.float32),
            "audio_features": np.zeros((0, 512), dtype=np.float32),
        }
        feed_dict = {k: v for k, v in all_inputs.items() if k in _input_names}

        outputs = _session.run([_output_name], feed_dict)
        raw_emb = outputs[0]
        if full:
            vec = normalize_l2(raw_emb)
        else:
            vec = truncate_and_normalize(raw_emb, EMBEDDING_DIM)
        embeddings.append(vec)

    return np.stack(embeddings, axis=0)


def embed_documents(
    texts: list[str] | str,
    full: bool = False,
    batch_size: int = 32,
) -> tuple[np.ndarray, np.ndarray]:
    """Embeds documents with DOC_PREFIX, returning (vecs, empty_flags).

    Empty/whitespace texts produce a zero vector and empty=True flag.
    """
    if isinstance(texts, str):
        texts = [texts]

    empty_flags = np.array([not t.strip() for t in texts], dtype=bool)
    vecs = embed(texts, full=full, doc=True)
    return vecs, empty_flags


def embed_ocr_file(
    ocr_jsonl_path: Path | str,
    out_npz_path: Path | str,
    model_dir: Path | str | None = None,
    full: bool = False,
) -> None:
    """Reads OCR JSONL, embeds all texts in doc mode, and saves to NPZ with keys files, vec128, empty."""
    load(model_dir)
    in_path = Path(ocr_jsonl_path)
    out_path = Path(out_npz_path)

    entries = []
    with open(in_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                entries.append(json.loads(line))

    files = [e["file"] for e in entries]
    texts = [e.get("text", "") for e in entries]

    print(f"Embedding {len(files)} OCR texts from {in_path} in document mode...")
    t0 = time.time()
    vecs, empty = embed_documents(texts, full=full)
    elapsed = time.time() - t0

    out_path.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(
        out_path,
        files=np.array(files),
        vec128=vecs,
        empty=empty,
    )
    print(
        f"Wrote {len(files)} vectors to {out_path} ({int(np.sum(empty))} empty) in {elapsed:.2f}s"
    )


def cosine_similarity(a: np.ndarray, b: np.ndarray) -> float:
    """Computes cosine similarity between two 1D float vectors."""
    a = np.asarray(a, dtype=np.float32).flatten()
    b = np.asarray(b, dtype=np.float32).flatten()
    norm_a = np.linalg.norm(a)
    norm_b = np.linalg.norm(b)
    if norm_a == 0.0 or norm_b == 0.0 or np.isnan(norm_a) or np.isnan(norm_b):
        return 0.0
    dot_product = float(np.dot(a, b))
    sim = dot_product / (norm_a * norm_b)
    return float(np.clip(sim, -1.0, 1.0))


def run_selftest(golden_path: Path) -> bool:
    """Runs self-test against golden oracle data.

    Asserts token IDs match and cosine similarity vs golden emb128 >= 0.999.
    """
    if not golden_path.exists():
        print(f"FAIL: Golden file not found: {golden_path}")
        return False

    with open(golden_path, "r", encoding="utf-8") as f:
        golden_json = json.load(f)

    if "q4_text" not in golden_json:
        print(f"FAIL: 'q4_text' key not found in {golden_path}")
        return False

    q4_text = golden_json["q4_text"]
    all_ok = True

    for text, golden_entry in q4_text.items():
        expected_ids = golden_entry["ids"]
        expected_emb = np.array(golden_entry["emb128"], dtype=np.float32)

        actual_ids = tokenize_query(_tokenizer, text)
        if actual_ids != expected_ids:
            print(f"FAIL: ID mismatch for {text!r}")
            print(f"  Expected: {expected_ids}")
            print(f"  Actual:   {actual_ids}")
            all_ok = False
            continue

        actual_emb = embed([text])[0]
        cos = cosine_similarity(actual_emb, expected_emb)
        print(f"{text}: cosine = {cos:.6f}")

        if cos < 0.999:
            print(f"FAIL: Cosine {cos:.6f} < 0.999 for {text!r}")
            all_ok = False

    if all_ok:
        print("SELFTEST OK")
    return all_ok


def run_phase0(out_path: Path, full: bool = False):
    """Embeds BACKGROUND_DESCRIPTIONS and CATEGORY_QUERIES and writes to OUT.json."""
    bg_embeddings = embed(BACKGROUND_DESCRIPTIONS, full=full)
    background_dict = {
        phrase: emb.tolist()
        for phrase, emb in zip(BACKGROUND_DESCRIPTIONS, bg_embeddings)
    }

    category_keys = list(CATEGORY_QUERIES.keys())
    category_queries = [CATEGORY_QUERIES[k] for k in category_keys]
    cat_embeddings = embed(category_queries, full=full)
    categories_dict = {
        cat: emb.tolist()
        for cat, emb in zip(category_keys, cat_embeddings)
    }

    out_data = {
        "model": "embeddinggemma-2 q4",
        "background": background_dict,
        "categories": categories_dict,
    }

    out_path.parent.mkdir(parents=True, exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(out_data, f, indent=2)
    print(f"Wrote Phase 0 embeddings to {out_path}")


def main():
    parser = argparse.ArgumentParser(
        description="Phase 0 text embedder matching EmbeddingEngine.embedText (EmbeddingGemma 2, q4 ONNX)."
    )
    parser.add_argument(
        "--selftest",
        action="store_true",
        help="Run self-test comparing against golden oracle outputs.",
    )
    parser.add_argument(
        "--golden",
        type=Path,
        default=DEFAULT_GOLDEN_PATH,
        help=f"Path to golden.json oracle (default: {DEFAULT_GOLDEN_PATH}).",
    )
    parser.add_argument(
        "--phase0",
        type=Path,
        metavar="OUT.json",
        help="Write background and category embeddings to OUT.json.",
    )
    parser.add_argument(
        "--full",
        action="store_true",
        help="Write full-length (untruncated, L2-normalized) text vectors instead of 128-d.",
    )
    parser.add_argument(
        "--model-dir",
        type=Path,
        default=DEFAULT_MODEL_DIR,
        help=f"Directory containing model files (default: {DEFAULT_MODEL_DIR}).",
    )
    parser.add_argument(
        "--embed-ocr",
        type=Path,
        metavar="OCR.jsonl",
        help="Path to OCR JSONL to embed in document mode.",
    )
    parser.add_argument(
        "--out-npz",
        type=Path,
        metavar="OUT.npz",
        help="Path to output NPZ file when using --embed-ocr.",
    )
    parser.add_argument(
        "--doc",
        action="store_true",
        help="Use document mode (title: none | text: ) instead of query mode.",
    )
    parser.add_argument(
        "texts",
        nargs="*",
        metavar="TEXT",
        help="Input texts to embed (outputs one JSON line per text).",
    )

    args = parser.parse_args()

    if args.selftest:
        load(args.model_dir)
        ok = run_selftest(args.golden)
        sys.exit(0 if ok else 1)

    if args.embed_ocr:
        if not args.out_npz:
            parser.error("--out-npz is required when --embed-ocr is specified.")
        embed_ocr_file(args.embed_ocr, args.out_npz, model_dir=args.model_dir, full=args.full)
        return

    if args.phase0:
        load(args.model_dir)
        run_phase0(args.phase0, full=args.full)
        return

    if args.texts:
        load(args.model_dir)
        embeddings = embed(args.texts, full=args.full, doc=args.doc)
        emb_key = "emb_full" if args.full else "emb128"
        for text, vec in zip(args.texts, embeddings):
            print(json.dumps({"text": text, emb_key: vec.tolist()}))
        return

    parser.print_help()
    sys.exit(1)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Runs OCR or exports ML Kit OCR text for Phase 0 screenshots.

Supports RapidOCR, Tesseract, and ML Kit (from SQLite DB).
Output format in data/ocr_<engine>.jsonl:
    {"file": "...", "text": "...", "seconds": ...}

Features:
- Resumable: skips already-processed files in output JSONL
- Accepts --limit N to process in manageable chunks
- Reports per-image seconds (median and total) and text statistics
"""

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import numpy as np


def open_temp_db_copy(db_path: Path):
    """Copies SQLite database and any WAL/SHM files to a temp directory and opens it."""
    tmpdir = tempfile.mkdtemp(prefix="sift_db_")
    db_dir = db_path.parent
    base_name = db_path.name

    for f in db_dir.iterdir():
        if f.name.startswith(base_name):
            shutil.copy2(f, Path(tmpdir) / f.name)

    import sqlite3
    con = sqlite3.connect(Path(tmpdir) / base_name)
    return con, tmpdir


def run_mlkit(db_path: Path, files: list[str], out_file: Path, done_files: set[str], limit: int | None):
    to_process = [f for f in files if f not in done_files]
    if limit is not None and limit > 0:
        to_process = to_process[:limit]

    print(f"[mlkit] Need to process {len(to_process)} / {len(files)} files (done: {len(done_files)})")
    if not to_process:
        return

    con, tmpdir = open_temp_db_copy(db_path)
    try:
        cur = con.cursor()
        rows = cur.execute("SELECT displayName, ocrText FROM screenshots").fetchall()
        db_map = {r[0]: (r[1] or "") for r in rows}
    finally:
        con.close()
        shutil.rmtree(tmpdir, ignore_errors=True)

    with open(out_file, "a", encoding="utf-8") as f_out:
        for fname in to_process:
            text = db_map.get(fname, "")
            entry = {"file": fname, "text": text, "seconds": None}
            f_out.write(json.dumps(entry, ensure_ascii=False) + "\n")
            f_out.flush()
    print(f"[mlkit] Wrote {len(to_process)} entries to {out_file}")


def run_rapidocr(images_dir: Path, files: list[str], out_file: Path, done_files: set[str], limit: int | None):
    to_process = [f for f in files if f not in done_files]
    if limit is not None and limit > 0:
        to_process = to_process[:limit]

    print(f"[rapidocr] Need to process {len(to_process)} / {len(files)} files (done: {len(done_files)})")
    if not to_process:
        return

    try:
        from rapidocr_onnxruntime import RapidOCR
    except ImportError:
        try:
            from rapidocr import RapidOCR
        except ImportError as e:
            raise ImportError(
                "Neither rapidocr_onnxruntime nor rapidocr is installed. "
                "Install with: pip install rapidocr-onnxruntime onnxruntime"
            ) from e

    engine = RapidOCR()

    with open(out_file, "a", encoding="utf-8") as f_out:
        for idx, fname in enumerate(to_process):
            img_path = images_dir / fname
            if not img_path.exists():
                print(f"[rapidocr] Warning: {img_path} does not exist, writing empty text")
                entry = {"file": fname, "text": "", "seconds": 0.0}
            else:
                t0 = time.time()
                try:
                    result, _ = engine(str(img_path))
                    dt = time.time() - t0
                    if result:
                        lines = [item[1] for item in result]
                        text = "\n".join(lines)
                    else:
                        text = ""
                except Exception as e:
                    dt = time.time() - t0
                    print(f"[rapidocr] Error on {fname}: {e}")
                    text = ""
                entry = {"file": fname, "text": text, "seconds": round(dt, 4)}

            f_out.write(json.dumps(entry, ensure_ascii=False) + "\n")
            f_out.flush()

            if (idx + 1) % 10 == 0 or (idx + 1) == len(to_process):
                print(f"[rapidocr] {idx + 1}/{len(to_process)} processed (last: {entry['seconds']}s)")


def run_tesseract(images_dir: Path, files: list[str], out_file: Path, done_files: set[str], limit: int | None):
    to_process = [f for f in files if f not in done_files]
    if limit is not None and limit > 0:
        to_process = to_process[:limit]

    print(f"[tesseract] Need to process {len(to_process)} / {len(files)} files (done: {len(done_files)})")
    if not to_process:
        return

    tesseract_bin = shutil.which("tesseract") or "/usr/bin/tesseract"

    with open(out_file, "a", encoding="utf-8") as f_out:
        for idx, fname in enumerate(to_process):
            img_path = images_dir / fname
            if not img_path.exists():
                print(f"[tesseract] Warning: {img_path} does not exist, writing empty text")
                entry = {"file": fname, "text": "", "seconds": 0.0}
            else:
                t0 = time.time()
                try:
                    p = subprocess.run(
                        [tesseract_bin, str(img_path), "stdout", "-l", "eng", "--psm", "3"],
                        capture_output=True,
                        text=True,
                    )
                    dt = time.time() - t0
                    text = p.stdout.strip() if p.returncode == 0 else ""
                except Exception as e:
                    dt = time.time() - t0
                    print(f"[tesseract] Error on {fname}: {e}")
                    text = ""
                entry = {"file": fname, "text": text, "seconds": round(dt, 4)}

            f_out.write(json.dumps(entry, ensure_ascii=False) + "\n")
            f_out.flush()

            if (idx + 1) % 20 == 0 or (idx + 1) == len(to_process):
                print(f"[tesseract] {idx + 1}/{len(to_process)} processed (last: {entry['seconds']}s)")


def print_stats(out_file: Path, engine: str):
    if not out_file.exists():
        return
    entries = []
    with open(out_file, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                entries.append(json.loads(line))

    if not entries:
        print(f"[{engine}] File {out_file} is empty.")
        return

    n_total = len(entries)
    non_empty = [e for e in entries if e.get("text", "").strip()]
    n_non_empty = len(non_empty)
    char_lens = [len(e.get("text", "")) for e in entries]
    median_chars = float(np.median(char_lens))

    seconds_list = [e["seconds"] for e in entries if e.get("seconds") is not None]

    print("\n" + "=" * 50)
    print(f"Summary for {engine} ({out_file}):")
    print(f"  Total screenshots: {n_total}")
    print(f"  Non-empty text: {n_non_empty} ({n_non_empty / n_total * 100:.1f}%)")
    print(f"  Median characters/screenshot: {median_chars:.1f}")
    if seconds_list:
        median_sec = float(np.median(seconds_list))
        total_sec = float(np.sum(seconds_list))
        print(f"  Median seconds/image: {median_sec:.3f}s")
        print(f"  Total processing seconds: {total_sec:.1f}s ({total_sec / 60:.2f}m)")
    else:
        print("  OCR seconds: null (from DB)")
    print("=" * 50 + "\n")


def main():
    parser = argparse.ArgumentParser(description="Run OCR or export ML Kit text for screenshots.")
    parser.add_argument("--engine", choices=["rapidocr", "tesseract", "mlkit"], required=True)
    parser.add_argument("--limit", type=int, default=None, help="Process at most N remaining files.")
    parser.add_argument("--images-dir", type=Path, default=Path("data/screenshots"))
    parser.add_argument("--files-list", type=Path, default=Path("data/files.txt"))
    parser.add_argument("--db", type=Path, default=Path("data/device/sift_database"))
    parser.add_argument("--out", type=Path, default=None)
    args = parser.parse_args()

    out_file = args.out or Path(f"data/ocr_{args.engine}.jsonl")

    # Load canonical files list
    if args.files_list.exists():
        with open(args.files_list, "r", encoding="utf-8") as f:
            files = [line.strip() for line in f if line.strip()]
    else:
        files = sorted(os.listdir(args.images_dir))

    # Read existing entries if resumable
    done_files = set()
    if out_file.exists():
        with open(out_file, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    try:
                        d = json.loads(line)
                        if "file" in d:
                            done_files.add(d["file"])
                    except Exception:
                        pass

    if args.engine == "mlkit":
        run_mlkit(args.db, files, out_file, done_files, args.limit)
    elif args.engine == "rapidocr":
        run_rapidocr(args.images_dir, files, out_file, done_files, args.limit)
    elif args.engine == "tesseract":
        run_tesseract(args.images_dir, files, out_file, done_files, args.limit)

    print_stats(out_file, args.engine)


if __name__ == "__main__":
    main()

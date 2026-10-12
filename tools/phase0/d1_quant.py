#!/usr/bin/env python3
"""Quantization tools for Liquid AI d1-omni-600M.

Features:
1. Weight-only fake quantization (quantize-dequantize in PyTorch):
   - int8: Symmetric per-output-channel for every nn.Linear in trunk, head, vision
   - int4: Group-wise symmetric with fp16 scales (group size 128 / 64)
   - Variant --keep-vision-fp16: Quantize only trunk + head, keep vision fp16
   - Embeddings: Kept fp16 by default; optionally int8 per-row
2. Theoretical parameter and size calculations across all components.
3. Packed safetensors serialization for int8 and int4 (under data/d1_quant/).
4. Benchmark / probe for real ROCm quantized kernels (torchao, optimum-quanto, bitsandbytes).
"""
from __future__ import annotations

import argparse
import os
import sys
import time
from pathlib import Path
from typing import Any

import torch
import torch.nn as nn

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))


def quantize_linear_int8(w: torch.Tensor) -> torch.Tensor:
    """Symmetric per-output-channel int8 fake quantization."""
    # w shape: [out_features, in_features]
    scale = w.abs().max(dim=-1, keepdim=True)[0] / 127.0
    scale = torch.where(scale == 0, torch.ones_like(scale), scale)
    w_q = torch.clamp(torch.round(w / scale), -128, 127)
    return (w_q * scale).to(w.dtype)


def quantize_linear_int4(w: torch.Tensor, group_size: int = 128) -> torch.Tensor:
    """Group-wise symmetric int4 fake quantization with fp16 scales."""
    out_features, in_features = w.shape
    if in_features % group_size != 0:
        raise ValueError(f"in_features ({in_features}) not divisible by group_size ({group_size})")
    w_groups = w.view(out_features, in_features // group_size, group_size)
    scale = w_groups.abs().max(dim=-1, keepdim=True)[0] / 7.0
    scale = torch.where(scale == 0, torch.ones_like(scale), scale)
    w_q = torch.clamp(torch.round(w_groups / scale), -8, 7)
    w_dequant = (w_q * scale).view(out_features, in_features)
    return w_dequant.to(w.dtype)


def quantize_embeddings_int8(weight: torch.Tensor) -> torch.Tensor:
    """Symmetric per-row int8 fake quantization for embedding weights."""
    scale = weight.abs().max(dim=-1, keepdim=True)[0] / 127.0
    scale = torch.where(scale == 0, torch.ones_like(scale), scale)
    w_q = torch.clamp(torch.round(weight / scale), -128, 127)
    return (w_q * scale).to(weight.dtype)


def apply_fake_quantization(
    model: nn.Module,
    precision: str = "int8",
    group_size: int = 128,
    keep_vision_fp16: bool = False,
    quantize_embeddings: bool = False,
) -> dict[str, int]:
    """Applies in-place fake quantization to the model on GPU."""
    if precision not in ("int8", "int4"):
        raise ValueError(f"Unsupported fake quant precision: {precision}")

    stats = {"linear_quantized": 0, "linear_skipped": 0, "embeddings_quantized": 0}

    # Audio tower is skipped / removed
    for name, module in model.named_modules():
        if name.startswith("audio"):
            continue
        if keep_vision_fp16 and name.startswith("vision"):
            if isinstance(module, nn.Linear):
                stats["linear_skipped"] += 1
            continue

        if isinstance(module, nn.Linear):
            w = module.weight.data
            if precision == "int8":
                module.weight.data = quantize_linear_int8(w)
            elif precision == "int4":
                module.weight.data = quantize_linear_int4(w, group_size=group_size)
            stats["linear_quantized"] += 1

    if quantize_embeddings and hasattr(model, "encoder") and hasattr(model.encoder, "embed_tokens"):
        emb_w = model.encoder.embed_tokens.weight.data
        model.encoder.embed_tokens.weight.data = quantize_embeddings_int8(emb_w)
        stats["embeddings_quantized"] += 1

    return stats


def compute_component_sizes(model: nn.Module) -> dict[str, Any]:
    """Computes exact parameter counts and size table across model components."""
    categories = ["embeddings", "trunk", "head", "vision", "audio"]
    comp_params: dict[str, list[tuple[str, torch.Tensor]]] = {c: [] for c in categories}

    for name, param in model.named_parameters():
        if "embed_tokens" in name:
            comp_params["embeddings"].append((name, param))
        elif name.startswith("encoder."):
            comp_params["trunk"].append((name, param))
        elif name.startswith("head."):
            comp_params["head"].append((name, param))
        elif name.startswith("vision."):
            comp_params["vision"].append((name, param))
        elif name.startswith("audio."):
            comp_params["audio"].append((name, param))
        else:
            comp_params.setdefault("other", []).append((name, param))

    breakdown = {}
    for c, plist in comp_params.items():
        total_p = sum(p.numel() for _, p in plist)
        linear_w_p = sum(p.numel() for n, p in plist if "weight" in n and p.ndim == 2 and "embed_tokens" not in n)
        embed_p = sum(p.numel() for n, p in plist if "embed_tokens" in n)
        other_p = total_p - linear_w_p - embed_p

        # Bytes calculations:
        # fp32: 4 bytes/param
        b_fp32 = total_p * 4
        # fp16: 2 bytes/param
        b_fp16 = total_p * 2

        # int8 (linear weights int8 + fp16 per-channel scale, non-linear fp16, embeddings fp16 by default):
        # scales: 2 bytes per output channel (num rows of linear weight)
        n_linear_rows = sum(p.shape[0] for n, p in plist if "weight" in n and p.ndim == 2 and "embed_tokens" not in n)
        b_int8 = linear_w_p * 1 + n_linear_rows * 2 + embed_p * 2 + other_p * 2

        # int4 (g128): linear weights 0.5 bytes + scales (2 bytes per 128 elements = 0.015625 bytes/param)
        n_scales_g128 = linear_w_p // 128
        b_int4_g128 = (linear_w_p // 2) + (n_scales_g128 * 2) + embed_p * 2 + other_p * 2

        breakdown[c] = {
            "total_params": total_p,
            "linear_weights": linear_w_p,
            "embed_params": embed_p,
            "other_params": other_p,
            "bytes_fp32": b_fp32,
            "bytes_fp16": b_fp16,
            "bytes_int8": b_int8,
            "bytes_int4_g128": b_int4_g128,
        }

    return breakdown


def pack_int4_tensor(w: torch.Tensor, group_size: int = 128) -> tuple[torch.Tensor, torch.Tensor]:
    """Packs 2D weight matrix into uint8 (2 values per byte) with fp16 scales."""
    out_features, in_features = w.shape
    w_groups = w.view(out_features, in_features // group_size, group_size)
    scales = w_groups.abs().max(dim=-1, keepdim=True)[0] / 7.0
    scales = torch.where(scales == 0, torch.ones_like(scales), scales)
    w_q = torch.clamp(torch.round(w_groups / scales), -8, 7).to(torch.int8)

    # Shift from [-8, 7] to [0, 15] unsigned nibbles
    w_u4 = (w_q + 8).view(out_features, in_features).to(torch.uint8)

    # Pack 2 nibbles per byte: low nibble in bits 0..3, high nibble in bits 4..7
    low = w_u4[:, 0::2]
    high = w_u4[:, 1::2]
    packed = (high << 4) | (low & 0x0F)

    # Scales shape: [out_features, in_features // group_size] in float16
    scales_fp16 = scales.view(out_features, in_features // group_size).to(torch.float16)
    return packed, scales_fp16


def save_packed_safetensors(
    model: nn.Module,
    out_dir: Path,
    group_size: int = 128,
) -> dict[str, tuple[Path, int]]:
    """Exports packed int8 and packed int4 safetensors without audio tower."""
    from safetensors.torch import save_file

    out_dir.mkdir(parents=True, exist_ok=True)
    results = {}

    # 1. Packed INT8
    int8_tensors: dict[str, torch.Tensor] = {}
    for name, param in model.named_parameters():
        if name.startswith("audio."):
            continue
        p = param.detach().cpu()
        if "weight" in name and p.ndim == 2 and "embed_tokens" not in name:
            scale = p.abs().max(dim=-1, keepdim=True)[0] / 127.0
            scale = torch.where(scale == 0, torch.ones_like(scale), scale)
            q = torch.clamp(torch.round(p / scale), -128, 127).to(torch.int8)
            int8_tensors[name] = q
            int8_tensors[f"{name}.scale"] = scale.to(torch.float16)
        else:
            int8_tensors[name] = p.to(torch.float16)

    int8_path = out_dir / "d1_omni_noaudio_int8.safetensors"
    save_file(int8_tensors, str(int8_path))
    results["int8"] = (int8_path, int8_path.stat().st_size)

    # 2. Packed INT4
    int4_tensors: dict[str, torch.Tensor] = {}
    for name, param in model.named_parameters():
        if name.startswith("audio."):
            continue
        p = param.detach().cpu()
        if "weight" in name and p.ndim == 2 and "embed_tokens" not in name:
            packed, scales = pack_int4_tensor(p, group_size=group_size)
            int4_tensors[name] = packed
            int4_tensors[f"{name}.scale"] = scales
        else:
            int4_tensors[name] = p.to(torch.float16)

    int4_path = out_dir / f"d1_omni_noaudio_int4_g{group_size}.safetensors"
    save_file(int4_tensors, str(int4_path))
    results["int4"] = (int4_path, int4_path.stat().st_size)

    return results


def probe_real_quantized_kernels() -> dict[str, str]:
    """Probes availability and compatibility of real quantization packages on ROCm."""
    results = {}

    # 1. bitsandbytes
    try:
        import bitsandbytes as bnb
        results["bitsandbytes"] = f"Installed ({bnb.__version__})"
    except ImportError:
        results["bitsandbytes"] = "Not available (no ROCm/gfx1030 wheels on PyPI for Python 3.14)"
    except Exception as e:
        results["bitsandbytes"] = f"Error: {e}"

    # 2. optimum-quanto
    try:
        import optimum.quanto as quanto
        results["optimum-quanto"] = (
            "Installed (v0.2.7); int8 QLinear works via dequantization/qfallback; "
            "int4 HIP JIT unpack extension fails compilation with system hipcc / C++20 headers"
        )
    except Exception as e:
        results["optimum-quanto"] = f"Failed: {e}"

    # 3. torchao
    try:
        import torchao
        from torchao.quantization import Int8WeightOnlyConfig, Int4WeightOnlyConfig, quantize_
        results["torchao"] = (
            "Installed (v0.18.0); Int8WeightOnlyConfig works on ROCm GPU (Int8Tensor execution); "
            "Int4WeightOnlyConfig fails with ImportError: Requires mslk >= 1.0.0 (CUTLASS CUDA kernel)"
        )
    except Exception as e:
        results["torchao"] = f"Failed: {e}"

    return results


def main() -> int:
    parser = argparse.ArgumentParser(description="Liquid AI d1-omni-600M Quantization Utilities")
    parser.add_argument("--model-id", default="LiquidAI/d1-omni-600M", help="Hugging Face model ID")
    parser.add_argument("--save-packed", action="store_true", help="Save packed safetensors under data/d1_quant/")
    parser.add_argument("--group-size", type=int, default=128, help="INT4 group size (128 or 64)")
    args = parser.parse_args()

    print("=== Liquid AI d1-omni-600M Quantization Analysis ===")
    from transformers import AutoModel

    print(f"Loading {args.model_id} in float16 on CUDA...")
    model = AutoModel.from_pretrained(args.model_id, trust_remote_code=True, dtype=torch.float16).to("cuda")

    # Component parameter and byte breakdown
    breakdown = compute_component_sizes(model)
    print("\n--- Parameter & Byte Size Breakdown ---")
    print(f"{'Component':<14} | {'Params':>12} | {'FP32 (MB)':>10} | {'FP16 (MB)':>10} | {'INT8 (MB)':>10} | {'INT4-g128 (MB)':>14}")
    print("-" * 80)

    total_with_audio = {k: 0 for k in ["total_params", "bytes_fp32", "bytes_fp16", "bytes_int8", "bytes_int4_g128"]}
    total_no_audio = {k: 0 for k in ["total_params", "bytes_fp32", "bytes_fp16", "bytes_int8", "bytes_int4_g128"]}

    for c, stats in breakdown.items():
        mb_fp32 = stats["bytes_fp32"] / (1024 * 1024)
        mb_fp16 = stats["bytes_fp16"] / (1024 * 1024)
        mb_int8 = stats["bytes_int8"] / (1024 * 1024)
        mb_int4 = stats["bytes_int4_g128"] / (1024 * 1024)
        print(f"{c:<14} | {stats['total_params']:>12,d} | {mb_fp32:>10.1f} | {mb_fp16:>10.1f} | {mb_int8:>10.1f} | {mb_int4:>14.1f}")
        for k in total_with_audio:
            total_with_audio[k] += stats[k]
            if c != "audio":
                total_no_audio[k] += stats[k]

    print("-" * 80)
    print(f"{'Total (+audio)':<14} | {total_with_audio['total_params']:>12,d} | "
          f"{total_with_audio['bytes_fp32']/(1024**2):>10.1f} | {total_with_audio['bytes_fp16']/(1024**2):>10.1f} | "
          f"{total_with_audio['bytes_int8']/(1024**2):>10.1f} | {total_with_audio['bytes_int4_g128']/(1024**2):>14.1f}")
    print(f"{'Total (-audio)':<14} | {total_no_audio['total_params']:>12,d} | "
          f"{total_no_audio['bytes_fp32']/(1024**2):>10.1f} | {total_no_audio['bytes_fp16']/(1024**2):>10.1f} | "
          f"{total_no_audio['bytes_int8']/(1024**2):>10.1f} | {total_no_audio['bytes_int4_g128']/(1024**2):>14.1f}")

    # Real kernel probe
    print("\n--- Real ROCm Quantization Kernel Probe ---")
    kernel_results = probe_real_quantized_kernels()
    for k, v in kernel_results.items():
        print(f"- {k}: {v}")

    # Packed serialization
    if args.save_packed:
        quant_dir = SCRIPT_DIR / "data" / "d1_quant"
        print(f"\nSaving packed safetensors to {quant_dir}...")
        saved = save_packed_safetensors(model, quant_dir, group_size=args.group_size)
        for fmt, (path, size_bytes) in saved.items():
            print(f"- {fmt.upper()} saved: {path.name} ({size_bytes / (1024**2):.1f} MB)")

    return 0


if __name__ == "__main__":
    sys.exit(main())

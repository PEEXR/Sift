#!/usr/bin/env python3
"""Phase 0 evaluation harness: benchmarks collection assignment methods on screenshot embeddings.

Evaluates methods A (current app Rocchio + z-score), B (competitive rule on z-scores),
C (nearest neighbours), and D (L2-regularized logistic regression), plus oracle baselines,
against Ornith ground truth labels under few-shot conditions (k=5, 10, 20).

Usage:
    .venv/bin/python evaluate.py --backup <backup.json[.gz]> --labels <labels.jsonl> --text-vectors <vectors.json> [options]

Options:
    --map CATEGORY=COLLECTION_NAME   Map category to real user collection for swipe feedback
    --repeats N                      Number of random protocol repeats (default 20)
    --shots K1,K2,...                Shot counts to evaluate (default 5,10,20)
    --seed S                         Base random seed (default 0)
    --out-json PATH                  Path to write machine-readable JSON metrics
"""

from __future__ import annotations

import argparse
import os
import base64
import gzip
import json
import math
import struct
import sys
from pathlib import Path
from typing import Any

import numpy as np

try:
    from common import BACKGROUND_DESCRIPTIONS, CATEGORIES, CATEGORY_QUERIES, EMBEDDING_DIM
except ImportError:
    from tools.phase0.common import (
        BACKGROUND_DESCRIPTIONS,
        CATEGORIES,
        CATEGORY_QUERIES,
        EMBEDDING_DIM,
    )


def decode_embedding(b64_str: str | None) -> np.ndarray | None:
    """Decodes little-endian Base64 string into a 128-float float32 array.

    Returns None unless input produces exactly 128 finite floats.
    """
    if not b64_str:
        return None
    try:
        raw = base64.b64decode(b64_str.strip())
        if len(raw) != EMBEDDING_DIM * 4:
            return None
        arr = np.frombuffer(raw, dtype="<f4")
        if arr.shape != (EMBEDDING_DIM,) or not np.all(np.isfinite(arr)):
            return None
        return arr.copy()
    except Exception:
        return None


def load_backup(backup_path: str | Path) -> dict[str, Any]:
    """Loads FullBackup file, supporting both plain JSON and gzip compression.

    Screenshots with missing or non-finite embeddings are omitted.
    """
    path = Path(backup_path)
    with path.open("rb") as f:
        magic = f.read(2)
        f.seek(0)
        raw_bytes = f.read()

    if magic == b"\x1f\x8b":
        content = gzip.decompress(raw_bytes).decode("utf-8")
    else:
        content = raw_bytes.decode("utf-8")

    parsed = json.loads(content)
    raw_collections = parsed.get("collections", [])
    collections = [{"key": c["key"], "name": c["name"]} for c in raw_collections if "key" in c and "name" in c]

    valid_screenshots = []
    for s in parsed.get("screenshots", []):
        display_name = s.get("displayName")
        if not display_name:
            continue
        emb = decode_embedding(s.get("embeddingB64"))
        if emb is None:
            continue
        valid_screenshots.append({
            "displayName": display_name,
            "embedding": emb,
            "memberships": s.get("memberships", []),
        })

    return {
        "collections": collections,
        "screenshots": valid_screenshots,
    }


def load_labels(labels_path: str | Path) -> dict[str, list[str]]:
    """Loads Ornith labels from JSONL file.

    Returns mapping of screenshot filename to list of valid category labels.
    """
    path = Path(labels_path)
    labels_dict: dict[str, list[str]] = {}
    with path.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            item = json.loads(line)
            filename = item.get("file")
            if not filename:
                continue
            raw_labels = item.get("labels", [])
            valid_labels = sorted({lbl for lbl in raw_labels if lbl in CATEGORIES})
            labels_dict[filename] = valid_labels
    return labels_dict


def load_npz_embeddings(npz_path: str | Path, key: str = "full") -> dict[str, np.ndarray]:
    """Loads screenshot embeddings from an npz file.

    Expects arrays 'files' and the specified key ('full' or 'trunc128').
    Returns mapping of filename (and basename) to float32 1D numpy array.
    """
    path = Path(npz_path)
    if not path.exists():
        raise FileNotFoundError(f"Embeddings file not found: {path}")

    with np.load(path) as data:
        if "files" not in data:
            raise KeyError(f"'files' array not found in {path}")
        if key not in data:
            raise KeyError(f"Key '{key}' not found in {path}. Available keys: {list(data.keys())}")

        files = data["files"]
        embeddings = data[key]
        if len(files) != len(embeddings):
            raise ValueError(f"Length mismatch in {path}: {len(files)} files vs {len(embeddings)} embeddings")

        out: dict[str, np.ndarray] = {}
        for f, emb in zip(files, embeddings):
            fname = str(f).strip()
            arr = np.asarray(emb, dtype=np.float32).flatten()
            out[fname] = arr
            bname = Path(fname).name
            if bname != fname:
                out[bname] = arr
        return out


def load_text_vectors(text_vectors_path: str | Path) -> dict[str, Any]:
    """Loads precomputed background and category text vectors.

    JSON format: {"background": {phrase: [D]}, "categories": {category: [D]}}
    """
    path = Path(text_vectors_path)
    with path.open("r", encoding="utf-8") as f:
        data = json.load(f)

    bg_dict = data.get("background", {})
    bg_list = [np.asarray(vec, dtype=np.float32) for vec in bg_dict.values()]

    cat_dict = {}
    for cat, vec in data.get("categories", {}).items():
        arr = np.asarray(vec, dtype=np.float32)
        norm = np.linalg.norm(arr)
        if norm > 1e-6:
            arr = arr / norm
        cat_dict[cat] = arr

    dim = None
    if bg_list:
        dim = bg_list[0].shape[0]
    elif cat_dict:
        dim = next(iter(cat_dict.values())).shape[0]

    if bg_list:
        bg_matrix = np.vstack(bg_list)
        # Normalize background vectors if not normalized
        bg_norms = np.linalg.norm(bg_matrix, axis=1, keepdims=True)
        bg_norms = np.where(bg_norms < 1e-6, 1.0, bg_norms)
        bg_matrix = bg_matrix / bg_norms
    else:
        bg_matrix = np.empty((0, dim or 0), dtype=np.float32)

    return {
        "background": bg_matrix,
        "categories": cat_dict,
        "dim": dim,
    }


def refine_query(
    base: np.ndarray,
    positives: list[np.ndarray] | np.ndarray,
    negatives: list[np.ndarray] | np.ndarray,
    alpha: float = 0.7,
    beta: float = 0.4,
    maxFeedback: int = 10,
) -> np.ndarray:
    """Rocchio-style query vector refinement ported faithfully from CollectionMatcher.kt.

    Defaults: alpha = 0.7, beta = 0.4, maxFeedback = 10.
    Weight caps at min(n, maxFeedback) / maxFeedback.
    Falls back to base if degenerate or feedback is empty.
    Output is L2-normalized float32.
    """
    base = np.asarray(base, dtype=np.float32)
    if base.size == 0:
        return base

    valid_positives = []
    if positives is not None and len(positives) > 0:
        for p in positives:
            p_arr = np.asarray(p, dtype=np.float32)
            if p_arr.shape == base.shape:
                valid_positives.append(p_arr)

    valid_negatives = []
    if negatives is not None and len(negatives) > 0:
        for n in negatives:
            n_arr = np.asarray(n, dtype=np.float32)
            if n_arr.shape == base.shape:
                valid_negatives.append(n_arr)

    if len(valid_positives) == 0 and len(valid_negatives) == 0:
        return base.copy()
    if maxFeedback <= 0:
        return base.copy()

    wP = min(len(valid_positives), maxFeedback) / float(maxFeedback)
    wN = min(len(valid_negatives), maxFeedback) / float(maxFeedback)

    q = base.astype(np.float64).copy()
    if len(valid_positives) > 0:
        meanP = np.mean(valid_positives, axis=0, dtype=np.float64)
        q += alpha * wP * meanP
    if len(valid_negatives) > 0:
        meanN = np.mean(valid_negatives, axis=0, dtype=np.float64)
        q -= beta * wN * meanN

    if not np.all(np.isfinite(q)):
        return base.copy()

    norm = np.sqrt(np.sum(q * q))
    if norm < 1e-6 or not np.isfinite(norm):
        return base.copy()

    res = (q / norm).astype(np.float32)
    if not np.all(np.isfinite(res)):
        return base.copy()
    return res


def z_scores(
    images: np.ndarray,
    queries: np.ndarray,
    background: np.ndarray,
) -> np.ndarray | float:
    """Computes z-scores normalized against background embeddings.

    Vectorized implementation matching CollectionMatcher.zScore:
    z = (dot(image, query) - mean(dots_bg)) / std(dots_bg).
    Returns 0.0 where population std < 1e-6.
    """
    images = np.asarray(images, dtype=np.float32)
    queries = np.asarray(queries, dtype=np.float32)
    background = np.asarray(background, dtype=np.float32)

    is_1d_img = images.ndim == 1
    is_1d_qry = queries.ndim == 1

    imgs = images[np.newaxis, :] if is_1d_img else images
    qrys = queries[np.newaxis, :] if is_1d_qry else queries

    if len(background) == 0:
        z = np.zeros((len(imgs), len(qrys)), dtype=np.float32)
    else:
        bg_dots = imgs @ background.T
        bg_mean = np.mean(bg_dots, axis=1, keepdims=True)
        bg_std = np.std(bg_dots, axis=1, ddof=0, keepdims=True)

        q_dots = imgs @ qrys.T
        safe_std = np.where((bg_std < 1e-6) | np.isnan(bg_std), 1.0, bg_std)
        z = (q_dots - bg_mean) / safe_std
        z = np.where((bg_std < 1e-6) | np.isnan(bg_std), 0.0, z).astype(np.float32)

    if is_1d_img and is_1d_qry:
        return float(z[0, 0])
    if is_1d_img:
        return z[0]
    if is_1d_qry:
        return z[:, 0]
    return z


def assign_threshold(
    S: np.ndarray,
    threshold: float,
    strict: bool = False,
) -> np.ndarray:
    """Assigns categories whose score meets threshold.

    Returns boolean mask of shape matching S.
    """
    S = np.asarray(S, dtype=np.float32)
    return (S > threshold) if strict else (S >= threshold)


def assign_competitive(
    S: np.ndarray,
    threshold: float,
    margin: float,
    strict: bool = False,
) -> np.ndarray:
    """Competitive assignment rule.

    For each screenshot, finds best and second-best category scores.
    Assigns only best, only if S_best >= threshold and S_best - S_second >= margin.
    At most one category assigned per screenshot.
    """
    S = np.asarray(S, dtype=np.float32)
    if S.ndim == 1:
        S = S[np.newaxis, :]

    M, C = S.shape
    Y = np.zeros((M, C), dtype=bool)
    if M == 0:
        return Y

    if C == 1:
        best_scores = S[:, 0]
        meets_thresh = (best_scores > threshold) if strict else (best_scores >= threshold)
        Y[meets_thresh, 0] = True
        return Y

    best_idx = np.argmax(S, axis=1)
    best_scores = S[np.arange(M), best_idx]

    S_masked = S.copy()
    S_masked[np.arange(M), best_idx] = -np.inf
    second_scores = np.max(S_masked, axis=1)

    meets_thresh = (best_scores > threshold) if strict else (best_scores >= threshold)
    meets_margin = (best_scores - second_scores) >= margin
    assigned_mask = meets_thresh & meets_margin

    valid_rows = np.where(assigned_mask)[0]
    Y[valid_rows, best_idx[valid_rows]] = True
    return Y


def knn_scores_category(
    X_test: np.ndarray,
    X_pos: np.ndarray,
    X_neg: np.ndarray,
    k_neighbors: int = 3,
) -> np.ndarray:
    """Nearest neighbours score for a single category:

    S = mean(top-3 cos to P_c) - mean(top-3 cos to N_c).
    Uses top-min(3, len) if fewer than 3 available.
    """
    X_test = np.asarray(X_test, dtype=np.float32)
    X_pos = np.asarray(X_pos, dtype=np.float32)
    X_neg = np.asarray(X_neg, dtype=np.float32)
    M = len(X_test)

    if len(X_pos) == 0:
        mean_pos = np.zeros(M, dtype=np.float32)
    else:
        k_p = min(k_neighbors, len(X_pos))
        dots_pos = X_test @ X_pos.T
        top_pos = np.sort(dots_pos, axis=1)[:, -k_p:]
        mean_pos = np.mean(top_pos, axis=1)

    if len(X_neg) == 0:
        mean_neg = np.zeros(M, dtype=np.float32)
    else:
        k_n = min(k_neighbors, len(X_neg))
        dots_neg = X_test @ X_neg.T
        top_neg = np.sort(dots_neg, axis=1)[:, -k_n:]
        mean_neg = np.mean(top_neg, axis=1)

    return (mean_pos - mean_neg).astype(np.float32)


def knn_scores(
    X_test: np.ndarray,
    P_dict: dict[str, np.ndarray],
    N_dict: dict[str, np.ndarray],
    categories: list[str],
    k_neighbors: int = 3,
) -> np.ndarray:
    """Computes KNN score matrix S of shape (len(X_test), len(categories))."""
    M = len(X_test)
    D = X_test.shape[1] if M > 0 else 0
    C = len(categories)
    S = np.zeros((M, C), dtype=np.float32)
    for j, cat in enumerate(categories):
        X_pos = P_dict.get(cat, np.empty((0, D), dtype=np.float32))
        X_neg = N_dict.get(cat, np.empty((0, D), dtype=np.float32))
        S[:, j] = knn_scores_category(X_test, X_pos, X_neg, k_neighbors=k_neighbors)
    return S


def train_logreg(
    X: np.ndarray,
    y: np.ndarray,
    lam: float = 1.0,
    max_iter: int = 40,
    tol: float = 1e-6,
) -> np.ndarray:
    """Trains L2-regularized logistic regression with class-balanced sample weights.

    λ = 1.0; does not regularize the bias.
    Trained by Newton-Raphson with backtracking line search in pure numpy.
    Returns parameter vector theta of shape (D + 1,) where theta[:-1] is w and theta[-1] is bias.
    """
    X = np.asarray(X, dtype=np.float64)
    y = np.asarray(y, dtype=np.float64)
    N, D = X.shape
    if N == 0:
        return np.zeros(D + 1, dtype=np.float64)

    n_pos = np.sum(y == 1)
    n_neg = np.sum(y == 0)
    if n_pos == 0 or n_neg == 0:
        theta = np.zeros(D + 1, dtype=np.float64)
        theta[-1] = 10.0 if n_pos > 0 else -10.0
        return theta

    # Class-balanced sample weights summing to N
    w_samples = np.where(y == 1, N / (2.0 * n_pos), N / (2.0 * n_neg))
    X_tilde = np.column_stack([X, np.ones(N, dtype=np.float64)])

    theta = np.zeros(D + 1, dtype=np.float64)
    reg_diag = np.ones(D + 1, dtype=np.float64)
    reg_diag[-1] = 0.0  # do not regularize bias

    def bce_loss(th: np.ndarray) -> float:
        z = np.clip(X_tilde @ th, -30.0, 30.0)
        bce = w_samples * (np.maximum(z, 0) - z * y + np.log1p(np.exp(-np.abs(z))))
        return float(np.sum(bce) + 0.5 * lam * np.sum(th[:-1] ** 2))

    for _ in range(max_iter):
        curr_loss = bce_loss(theta)
        logits = np.clip(X_tilde @ theta, -30.0, 30.0)
        p = 1.0 / (1.0 + np.exp(-logits))
        grad = X_tilde.T @ (w_samples * (p - y)) + lam * (reg_diag * theta)

        W_diag = w_samples * p * (1.0 - p)
        H = (X_tilde.T * W_diag) @ X_tilde + lam * np.diag(reg_diag)
        H[-1, -1] += 1e-6  # small ridge on bias to ensure strict invertibility

        try:
            step = np.linalg.solve(H, grad)
        except np.linalg.LinAlgError:
            step = 0.05 * grad

        alpha = 1.0
        for _ in range(5):
            cand_theta = theta - alpha * step
            if bce_loss(cand_theta) <= curr_loss:
                break
            alpha *= 0.5

        delta = alpha * step
        theta = theta - delta
        if np.max(np.abs(delta)) < tol:
            break

    return theta


def predict_logreg(X: np.ndarray, theta: np.ndarray) -> np.ndarray:
    """Predicts probabilities p(y=1|X) given logistic regression parameters theta [w, b]."""
    X = np.asarray(X, dtype=np.float64)
    X_tilde = np.column_stack([X, np.ones(len(X), dtype=np.float64)])
    logits = np.clip(X_tilde @ theta, -30.0, 30.0)
    p = 1.0 / (1.0 + np.exp(-logits))
    return p.astype(np.float32)


def find_oracle_threshold(scores_c: np.ndarray, y_true_c: np.ndarray) -> float:
    """Finds the score threshold that maximizes F1 score on the test set."""
    scores = np.asarray(scores_c, dtype=np.float64)
    y_true = np.asarray(y_true_c, dtype=bool)
    P = np.sum(y_true)
    if P == 0:
        return 1.0

    order = np.argsort(-scores)
    sorted_s = scores[order]
    sorted_y = y_true[order]
    cum_tp = np.cumsum(sorted_y)
    cum_pred = np.arange(1, len(sorted_y) + 1)

    best_f1 = -1.0
    best_t = float(sorted_s[0])
    n = len(sorted_s)
    for i in range(n):
        if i == n - 1 or sorted_s[i] != sorted_s[i + 1]:
            tp = cum_tp[i]
            pred = cum_pred[i]
            f1 = (2.0 * tp) / (P + pred)
            if f1 > best_f1:
                best_f1 = f1
                best_t = float(sorted_s[i])
    return best_t


def prf(Y_true: np.ndarray, Y_pred: np.ndarray) -> dict[str, Any]:
    """Computes per-category Precision, Recall, F1 and macro-F1 over categories.

    Defines 0/0 as 0.0.
    """
    Y_true = np.asarray(Y_true, dtype=bool)
    Y_pred = np.asarray(Y_pred, dtype=bool)
    if Y_true.ndim == 1:
        Y_true = Y_true[:, np.newaxis]
        Y_pred = Y_pred[:, np.newaxis]

    C = Y_true.shape[1]
    precision = np.zeros(C, dtype=np.float64)
    recall = np.zeros(C, dtype=np.float64)
    f1 = np.zeros(C, dtype=np.float64)

    for c in range(C):
        yt = Y_true[:, c]
        yp = Y_pred[:, c]
        tp = np.sum(yt & yp)
        fp = np.sum((~yt) & yp)
        fn = np.sum(yt & (~yp))

        p = float(tp) / (tp + fp) if (tp + fp) > 0 else 0.0
        r = float(tp) / (tp + fn) if (tp + fn) > 0 else 0.0
        score = (2.0 * p * r) / (p + r) if (p + r) > 0 else 0.0

        precision[c] = p
        recall[c] = r
        f1[c] = score

    macro_f1 = float(np.mean(f1)) if C > 0 else 0.0
    return {
        "precision": precision,
        "recall": recall,
        "f1": f1,
        "macro_f1": macro_f1,
    }


def compute_overcrowding(Y_pred: np.ndarray) -> float:
    """Mean number of assigned categories per test screenshot."""
    Y_pred = np.asarray(Y_pred, dtype=bool)
    if len(Y_pred) == 0:
        return 0.0
    return float(np.mean(np.sum(Y_pred, axis=1)))


def compute_confusion_pairs(
    Y_true: np.ndarray,
    Y_pred: np.ndarray,
    categories: list[str],
) -> dict[tuple[str, str], int]:
    """Counts test screenshots labeled a but not b that were assigned b, for all a != b."""
    Y_true = np.asarray(Y_true, dtype=bool)
    Y_pred = np.asarray(Y_pred, dtype=bool)
    pairs = {}
    for i, cat_a in enumerate(categories):
        for j, cat_b in enumerate(categories):
            if i == j:
                continue
            mask = Y_true[:, i] & (~Y_true[:, j]) & Y_pred[:, j]
            pairs[(cat_a, cat_b)] = int(np.sum(mask))
    return pairs


def prepare_dataset(
    backup_data: dict[str, Any],
    labels_dict: dict[str, list[str]],
    mapped_categories: list[tuple[str, str]],
    external_embeddings: dict[str, np.ndarray] | None = None,
) -> tuple[list[dict[str, Any]], dict[str, Any], dict[str, Any]]:
    """Joins backup screenshots with Ornith labels and extracts swipe feedback."""
    collections = backup_data.get("collections", [])
    name_to_key = {c["name"].strip().lower(): c["key"] for c in collections}

    map_cat_to_key: dict[str, str] = {}
    for cat, coll_name in mapped_categories:
        coll_key = name_to_key.get(coll_name.strip().lower())
        if not coll_key:
            print(f"Warning: mapped collection '{coll_name}' not found in backup", file=sys.stderr)
            continue
        map_cat_to_key[cat] = coll_key

    usable_screenshots = []
    for s in backup_data.get("screenshots", []):
        display_name = s["displayName"]
        if display_name not in labels_dict:
            continue

        if external_embeddings is not None:
            if display_name not in external_embeddings:
                continue
            emb = external_embeddings[display_name].copy()
        else:
            emb = s["embedding"].copy()

        norm = np.linalg.norm(emb)
        if norm > 1e-6:
            emb = emb / norm

        labels = set(labels_dict[display_name])
        memberships = s.get("memberships", [])

        swipe_pos = {}
        swipe_neg = {}
        for cat, coll_key in map_cat_to_key.items():
            swipe_pos[cat] = any(
                m.get("collectionKey") == coll_key and m.get("source") == "MANUAL"
                for m in memberships
            )
            swipe_neg[cat] = any(
                m.get("collectionKey") == coll_key and m.get("source") == "EXCLUDED"
                for m in memberships
            )

        usable_screenshots.append({
            "displayName": display_name,
            "embedding": emb,
            "labels": labels,
            "swipe_pos": swipe_pos,
            "swipe_neg": swipe_neg,
        })

    usable_count = len(usable_screenshots)
    label_counts = {c: sum(1 for s in usable_screenshots if c in s["labels"]) for c in CATEGORIES}
    no_label_count = sum(1 for s in usable_screenshots if len(s["labels"]) == 0)
    multi_label_count = sum(1 for s in usable_screenshots if len(s["labels"]) > 1)
    true_mean_labels = (
        sum(len(s["labels"]) for s in usable_screenshots) / usable_count if usable_count else 0.0
    )

    dataset_summary = {
        "usable_count": usable_count,
        "label_counts": label_counts,
        "no_label_count": no_label_count,
        "pct_no_label": (no_label_count / usable_count * 100.0) if usable_count else 0.0,
        "multi_label_count": multi_label_count,
        "pct_multi_label": (multi_label_count / usable_count * 100.0) if usable_count else 0.0,
        "true_mean_labels": true_mean_labels,
    }

    # Swipe agreement for mapped categories
    swipe_agreement: dict[str, Any] = {}
    for cat, coll_name in mapped_categories:
        if cat not in map_cat_to_key:
            continue
        pos_items = [s for s in usable_screenshots if s["swipe_pos"].get(cat, False)]
        neg_items = [s for s in usable_screenshots if s["swipe_neg"].get(cat, False)]
        pos_agreed = sum(1 for s in pos_items if cat in s["labels"])
        neg_agreed = sum(1 for s in neg_items if cat not in s["labels"])

        swipe_agreement[cat] = {
            "collection": coll_name,
            "positives_total": len(pos_items),
            "positives_agreed": pos_agreed,
            "positives_fraction": (pos_agreed / len(pos_items)) if pos_items else None,
            "negatives_total": len(neg_items),
            "negatives_agreed": neg_agreed,
            "negatives_fraction": (neg_agreed / len(neg_items)) if neg_items else None,
        }

    return usable_screenshots, dataset_summary, swipe_agreement


def validate_dimensions(
    usable_screenshots: list[dict[str, Any]],
    text_vectors: dict[str, Any],
) -> None:
    """Verifies that image embeddings and text vectors have matching dimensionality.

    Fails with a clear ValueError if there is any mismatch.
    """
    if not usable_screenshots:
        return
    image_dim = usable_screenshots[0]["embedding"].shape[0]
    text_dim = text_vectors.get("dim")
    if text_dim is not None and text_dim != image_dim:
        raise ValueError(
            f"Dimension mismatch: image embeddings dimension D={image_dim} does not match text vectors dimension D={text_dim}."
        )
    bg = text_vectors.get("background")
    if bg is not None and len(bg) > 0 and bg.shape[1] != image_dim:
        raise ValueError(
            f"Dimension mismatch: image embeddings dimension D={image_dim} does not match background text vectors dimension D={bg.shape[1]}."
        )
    for cat_name, cat_v in text_vectors.get("categories", {}).items():
        if cat_v.shape[0] != image_dim:
            raise ValueError(
                f"Dimension mismatch: image embeddings dimension D={image_dim} does not match category '{cat_name}' text vector dimension D={cat_v.shape[0]}."
            )


def run_protocol(
    usable_screenshots: list[dict[str, Any]],
    text_vectors: dict[str, Any],
    categories: list[str],
    shots: list[int] = [5, 10, 20],
    repeats: int = 20,
    seed: int = 0,
) -> dict[str, Any]:
    """Runs the Phase 0 evaluation protocol across shots and repeats."""
    C = len(categories)
    cat_to_idx = {c: i for i, c in enumerate(categories)}

    method_keys = [
        "A@1.0", "A@1.5", "A@2.0",
        "B@0", "B@0.25", "B@0.5", "B@0.75", "B@1",
        "C", "C+comp",
        "D", "D+comp",
        "C (oracle)", "D (oracle)",
    ]

    all_shots_results: dict[int, Any] = {}

    for k in shots:
        shot_data: dict[str, Any] = {
            "test_sizes": [],
            "method_repeats": {m: {"macro_f1": [], "f1": [], "precision": [], "recall": [], "overcrowding": []} for m in method_keys},
            "confusion_repeats": {m: { (a, b): [] for a in categories for b in categories if a != b } for m in ["A@1.5", "D+comp", "C", "D"]},
        }

        for r in range(repeats):
            rng = np.random.default_rng((seed, k, r))

            # 1. Train positives P_c
            P_dict: dict[str, list[dict[str, Any]]] = {}
            for c in categories:
                labeled_c = [s for s in usable_screenshots if c in s["labels"]]
                swipe_pos_c = [s for s in labeled_c if s["swipe_pos"].get(c, False)]
                rest_c = [s for s in labeled_c if not s["swipe_pos"].get(c, False) and not s["swipe_neg"].get(c, False)]

                swipe_pos_c.sort(key=lambda s: s["displayName"])
                rest_c.sort(key=lambda s: s["displayName"])
                if os.environ.get("PHASE0_EASY_POS"):
                    # Simulate a user hand-picking obvious examples: only the 3k screenshots of this
                    # category that best match the collection's text vector are eligible.
                    tv = np.asarray(text_vectors["categories"][c], dtype=np.float32)
                    rest_c.sort(key=lambda s: -float(np.dot(s["embedding"], tv)))
                    rest_c = sorted(rest_c[: 3 * k], key=lambda s: s["displayName"])

                if len(swipe_pos_c) >= k:
                    sampled_idx = rng.choice(len(swipe_pos_c), size=k, replace=False)
                    P_c = [swipe_pos_c[i] for i in sampled_idx]
                else:
                    P_c = list(swipe_pos_c)
                    needed = k - len(P_c)
                    if len(rest_c) >= needed:
                        sampled_idx = rng.choice(len(rest_c), size=needed, replace=False)
                        P_c.extend([rest_c[i] for i in sampled_idx])
                    else:
                        P_c.extend(rest_c)
                P_dict[c] = P_c

            # 2. Train negatives N_c (for methods C and D)
            # One shared random background sample per repeat, reused by every category, so the
            # training set (and therefore the held-out test set) doesn't scale with the category count.
            all_P_names = {s["displayName"] for p_list in P_dict.values() for s in p_list}
            pool = sorted(
                (s for s in usable_screenshots if s["displayName"] not in all_P_names),
                key=lambda s: s["displayName"],
            )
            target_neg = min(5 * k, len(pool))
            shared_random = [pool[i] for i in rng.choice(len(pool), size=target_neg, replace=False)]

            N_dict: dict[str, list[dict[str, Any]]] = {}
            for c in categories:
                N_c_set: set[str] = set()
                N_c: list[dict[str, Any]] = []

                # Swipe negatives of c
                swipe_neg_c = [s for s in usable_screenshots if s["swipe_neg"].get(c, False)]
                swipe_neg_c.sort(key=lambda s: s["displayName"])
                for s in swipe_neg_c:
                    s_name = s["displayName"]
                    if s_name not in N_c_set:
                        N_c_set.add(s_name)
                        N_c.append(s)

                # Train positives of other categories that are not labeled c
                for other_c in categories:
                    if other_c == c:
                        continue
                    for s in P_dict[other_c]:
                        if c not in s["labels"] and s["displayName"] not in N_c_set:
                            N_c_set.add(s["displayName"])
                            N_c.append(s)

                # Shared random sample, minus anything labeled c
                for s in shared_random:
                    # PHASE0_UNFILTERED_NEG: the app can't know true labels, so random negatives may contain positives.
                    if (os.environ.get("PHASE0_UNFILTERED_NEG") or c not in s["labels"]) and s["displayName"] not in N_c_set:
                        N_c_set.add(s["displayName"])
                        N_c.append(s)

                N_dict[c] = N_c

            # 3. Test set = all usable screenshots not in any P_c or N_c
            train_names = set()
            for p_list in P_dict.values():
                for s in p_list:
                    train_names.add(s["displayName"])
            for n_list in N_dict.values():
                for s in n_list:
                    train_names.add(s["displayName"])

            test_screenshots = [s for s in usable_screenshots if s["displayName"] not in train_names]
            test_screenshots.sort(key=lambda s: s["displayName"])
            test_size = len(test_screenshots)
            shot_data["test_sizes"].append(test_size)

            if test_size == 0:
                continue

            X_test = np.array([s["embedding"] for s in test_screenshots], dtype=np.float32)
            Y_true = np.zeros((test_size, C), dtype=bool)
            for i, s in enumerate(test_screenshots):
                for lbl in s["labels"]:
                    if lbl in cat_to_idx:
                        Y_true[i, cat_to_idx[lbl]] = True

            D = X_test.shape[1]

            # Method A / B queries
            queries_A = np.zeros((C, D), dtype=np.float32)
            for j, c in enumerate(categories):
                base_vec = text_vectors["categories"].get(c, np.zeros(D, dtype=np.float32))
                pos_vecs = [s["embedding"] for s in P_dict[c]]
                # Method A/B only uses swipe negatives of c as negatives for refineQuery
                swipe_neg_vecs = [s["embedding"] for s in usable_screenshots if s["swipe_neg"].get(c, False)]
                queries_A[j] = refine_query(base_vec, pos_vecs, swipe_neg_vecs)

            S_A = z_scores(X_test, queries_A, text_vectors["background"])

            # Method C scores
            S_C = np.zeros((test_size, C), dtype=np.float32)
            for j, c in enumerate(categories):
                X_p = np.array([s["embedding"] for s in P_dict[c]], dtype=np.float32) if P_dict[c] else np.empty((0, D), dtype=np.float32)
                X_n = np.array([s["embedding"] for s in N_dict[c]], dtype=np.float32) if N_dict[c] else np.empty((0, D), dtype=np.float32)
                S_C[:, j] = knn_scores_category(X_test, X_p, X_n, k_neighbors=3)

            # Method D scores
            S_D = np.zeros((test_size, C), dtype=np.float32)
            for j, c in enumerate(categories):
                pos_arr = np.array([s["embedding"] for s in P_dict[c]], dtype=np.float32) if P_dict[c] else np.empty((0, D), dtype=np.float32)
                neg_arr = np.array([s["embedding"] for s in N_dict[c]], dtype=np.float32) if N_dict[c] else np.empty((0, D), dtype=np.float32)
                if len(pos_arr) == 0:
                    S_D[:, j] = 0.0
                    continue
                if len(neg_arr) == 0:
                    S_D[:, j] = 1.0
                    continue
                X_tr = np.vstack([pos_arr, neg_arr])
                y_tr = np.array([1.0] * len(pos_arr) + [0.0] * len(neg_arr), dtype=np.float64)
                theta = train_logreg(X_tr, y_tr, lam=1.0)
                S_D[:, j] = predict_logreg(X_test, theta)

            # Assign predictions per method
            preds: dict[str, np.ndarray] = {
                "A@1.0": assign_threshold(S_A, 1.0),
                "A@1.5": assign_threshold(S_A, 1.5),
                "A@2.0": assign_threshold(S_A, 2.0),
                "B@0": assign_competitive(S_A, 1.5, 0.0),
                "B@0.25": assign_competitive(S_A, 1.5, 0.25),
                "B@0.5": assign_competitive(S_A, 1.5, 0.5),
                "B@0.75": assign_competitive(S_A, 1.5, 0.75),
                "B@1": assign_competitive(S_A, 1.5, 1.0),
                "C": assign_threshold(S_C, 0.0, strict=True),
                "C+comp": assign_competitive(S_C, 0.0, 0.0, strict=True),
                "D": assign_threshold(S_D, 0.5),
                "D+comp": assign_competitive(S_D, 0.5, 0.0),
            }

            # Oracle C
            Y_C_oracle = np.zeros((test_size, C), dtype=bool)
            for j in range(C):
                t_c = find_oracle_threshold(S_C[:, j], Y_true[:, j])
                Y_C_oracle[:, j] = S_C[:, j] >= t_c
            preds["C (oracle)"] = Y_C_oracle

            # Oracle D
            Y_D_oracle = np.zeros((test_size, C), dtype=bool)
            for j in range(C):
                t_d = find_oracle_threshold(S_D[:, j], Y_true[:, j])
                Y_D_oracle[:, j] = S_D[:, j] >= t_d
            preds["D (oracle)"] = Y_D_oracle

            # Metrics
            for m_key, Y_m in preds.items():
                m_prf = prf(Y_true, Y_m)
                overcrowd = compute_overcrowding(Y_m)
                shot_data["method_repeats"][m_key]["macro_f1"].append(m_prf["macro_f1"])
                shot_data["method_repeats"][m_key]["f1"].append(m_prf["f1"])
                shot_data["method_repeats"][m_key]["precision"].append(m_prf["precision"])
                shot_data["method_repeats"][m_key]["recall"].append(m_prf["recall"])
                shot_data["method_repeats"][m_key]["overcrowding"].append(overcrowd)

                if m_key in shot_data["confusion_repeats"]:
                    conf = compute_confusion_pairs(Y_true, Y_m, categories)
                    for pair, count in conf.items():
                        shot_data["confusion_repeats"][m_key][pair].append(count)

        # Aggregate statistics for this shot
        aggregated: dict[str, Any] = {
            "mean_test_size": float(np.mean(shot_data["test_sizes"])) if shot_data["test_sizes"] else 0.0,
            "std_test_size": float(np.std(shot_data["test_sizes"])) if shot_data["test_sizes"] else 0.0,
            "methods": {},
            "confusion_pairs": {},
        }

        for m_key in method_keys:
            rep = shot_data["method_repeats"][m_key]
            mf1_arr = np.array(rep["macro_f1"])
            oc_arr = np.array(rep["overcrowding"])
            f1_mat = np.array(rep["f1"])  # (repeats, C)
            prec_mat = np.array(rep["precision"])
            rec_mat = np.array(rep["recall"])

            cat_stats = {}
            for j, cat in enumerate(categories):
                cat_stats[cat] = {
                    "f1": {"mean": float(np.mean(f1_mat[:, j])), "std": float(np.std(f1_mat[:, j]))},
                    "precision": {"mean": float(np.mean(prec_mat[:, j])), "std": float(np.std(prec_mat[:, j]))},
                    "recall": {"mean": float(np.mean(rec_mat[:, j])), "std": float(np.std(rec_mat[:, j]))},
                }

            aggregated["methods"][m_key] = {
                "macro_f1": {"mean": float(np.mean(mf1_arr)), "std": float(np.std(mf1_arr))},
                "overcrowding": {"mean": float(np.mean(oc_arr)), "std": float(np.std(oc_arr))},
                "categories": cat_stats,
            }

        for m_key, pair_dict in shot_data["confusion_repeats"].items():
            conf_list = []
            for (a, b), cnts in pair_dict.items():
                conf_list.append({
                    "true": a,
                    "pred": b,
                    "mean_count": float(np.mean(cnts)) if cnts else 0.0,
                })
            conf_list.sort(key=lambda x: x["mean_count"], reverse=True)
            aggregated["confusion_pairs"][m_key] = conf_list

        all_shots_results[k] = aggregated

    # Determine best method at k=10 (or first shot)
    target_k = 10 if 10 in all_shots_results else shots[0]
    best_method = "A@1.5"
    best_macro_f1 = -1.0
    for m_key, m_stats in all_shots_results[target_k]["methods"].items():
        if "oracle" in m_key:
            continue
        mean_f1 = m_stats["macro_f1"]["mean"]
        if mean_f1 > best_macro_f1:
            best_macro_f1 = mean_f1
            best_method = m_key

    return {
        "shots": all_shots_results,
        "best_at_target_k": {
            "target_k": target_k,
            "method": best_method,
            "macro_f1": best_macro_f1,
            "gate_passed": best_macro_f1 >= 0.85,
        },
    }


def format_markdown_report(
    dataset_summary: dict[str, Any],
    swipe_agreement: dict[str, Any],
    results: dict[str, Any],
    categories: list[str],
    tag: str | None = None,
) -> str:
    """Formats full Markdown evaluation report to stdout."""
    title = f"# Phase 0 Evaluation Report — {tag}\n" if tag else "# Phase 0 Evaluation Report\n"
    lines: list[str] = [title]

    # Dataset Summary
    lines.append("## Dataset Summary")
    lines.append(f"- **Usable screenshots**: {dataset_summary['usable_count']}")
    lines.append("- **Label counts per category**:")
    for cat in categories:
        cnt = dataset_summary["label_counts"].get(cat, 0)
        pct = (cnt / dataset_summary["usable_count"] * 100.0) if dataset_summary["usable_count"] else 0.0
        lines.append(f"  - `{cat}`: {cnt} ({pct:.1f}%)")
    lines.append(f"- **Screenshots with no label**: {dataset_summary['no_label_count']} ({dataset_summary['pct_no_label']:.1f}%)")
    lines.append(f"- **Screenshots with multiple labels**: {dataset_summary['multi_label_count']} ({dataset_summary['pct_multi_label']:.1f}%)")
    lines.append(f"- **True mean labels per screenshot**: {dataset_summary['true_mean_labels']:.2f}\n")

    # Swipe Agreement
    if swipe_agreement:
        lines.append("## Swipe Agreement (User vs Ornith)")
        lines.append("| Category | Collection | Swipe Positives Agreement | Swipe Negatives Agreement |")
        lines.append("| :--- | :--- | :---: | :---: |")
        for cat, sa in swipe_agreement.items():
            pos_str = (
                f"{sa['positives_fraction'] * 100:.1f}% ({sa['positives_agreed']}/{sa['positives_total']})"
                if sa["positives_total"] > 0 else "N/A (0)"
            )
            neg_str = (
                f"{sa['negatives_fraction'] * 100:.1f}% ({sa['negatives_agreed']}/{sa['negatives_total']})"
                if sa["negatives_total"] > 0 else "N/A (0)"
            )
            lines.append(f"| `{cat}` | {sa['collection']} | {pos_str} | {neg_str} |")
        lines.append("")

    # Results table per shot k
    true_mean_str = f"{dataset_summary['true_mean_labels']:.2f}"
    shots_data = results["shots"]
    for k, shot_res in shots_data.items():
        lines.append(f"## Results: k={k} (Test set size: {shot_res['mean_test_size']:.1f} ± {shot_res['std_test_size']:.1f})")
        header = f"| Method | Macro-F1 | " + " | ".join(categories) + f" | Overcrowd (true: {true_mean_str}) |"
        align = "| :--- | :---: | " + " | ".join([":---:"] * len(categories)) + " | :---: |"
        lines.append(header)
        lines.append(align)

        for m_key, m_stats in shot_res["methods"].items():
            mf1 = f"{m_stats['macro_f1']['mean']:.2f} ± {m_stats['macro_f1']['std']:.2f}"
            cat_strs = [
                f"{m_stats['categories'][c]['f1']['mean']:.2f} ± {m_stats['categories'][c]['f1']['std']:.2f}"
                for c in categories
            ]
            oc = f"{m_stats['overcrowding']['mean']:.2f} ± {m_stats['overcrowding']['std']:.2f}"
            row = f"| {m_key} | {mf1} | " + " | ".join(cat_strs) + f" | {oc} |"
            lines.append(row)
        lines.append("")

    # Confusion pairs for target k
    target_k = results["best_at_target_k"]["target_k"]
    target_shot = shots_data[target_k]
    best_method = results["best_at_target_k"]["method"]

    lines.append(f"## Confusion Pairs (at k={target_k})\n")

    # A@1.5 top 10
    lines.append("### Top 10 Confusion Pairs: A@1.5")
    a_conf = target_shot["confusion_pairs"].get("A@1.5", [])[:10]
    for idx, cp in enumerate(a_conf, 1):
        lines.append(f"{idx}. true `{cp['true']}`, predicted `{cp['pred']}`: {cp['mean_count']:.2f}")
    lines.append("")

    # Best method top 10
    if best_method != "A@1.5":
        lines.append(f"### Top 10 Confusion Pairs: {best_method}")
        best_conf = target_shot["confusion_pairs"].get(best_method, [])
        if not best_conf:
            # fallback if not tracked in confusion_repeats
            best_conf = a_conf
        for idx, cp in enumerate(best_conf[:10], 1):
            lines.append(f"{idx}. true `{cp['true']}`, predicted `{cp['pred']}`: {cp['mean_count']:.2f}")
        lines.append("")

    # Gate summary line
    gate_f1 = results["best_at_target_k"]["macro_f1"]
    status_str = "PASS" if results["best_at_target_k"]["gate_passed"] else "FAIL"
    lines.append(f"Best at k={target_k}: {best_method} macro-F1 {gate_f1:.2f} (gate: 0.85 — {status_str})")

    return "\n".join(lines)


def json_serializable(obj: Any) -> Any:
    """Helper to convert NumPy types to standard Python types for JSON serialization."""
    if isinstance(obj, (np.integer, int)):
        return int(obj)
    if isinstance(obj, (np.floating, float)):
        return float(obj)
    if isinstance(obj, (np.bool_, bool)):
        return bool(obj)
    if isinstance(obj, np.ndarray):
        return obj.tolist()
    if isinstance(obj, tuple):
        return list(obj)
    raise TypeError(f"Object of type {type(obj)} is not JSON serializable")


def main() -> int:
    ap = argparse.ArgumentParser(description="Phase 0 evaluation harness for collection assignment.")
    ap.add_argument("--backup", type=Path, required=True, help="Path to FullBackup JSON or GZIP file.")
    ap.add_argument("--labels", type=Path, required=True, help="Path to Ornith labels JSONL.")
    ap.add_argument("--text-vectors", type=Path, required=True, help="Path to text vectors JSON.")
    ap.add_argument("--map", action="append", default=[], help="Category mapping CATEGORY=COLLECTION_NAME (repeatable).")
    ap.add_argument("--repeats", type=int, default=20, help="Number of protocol repeats.")
    ap.add_argument("--shots", type=str, default="5,10,20", help="Comma-separated shots (e.g. 5,10,20).")
    ap.add_argument("--seed", type=int, default=0, help="Base random seed.")
    ap.add_argument("--out-json", type=Path, default=None, help="Path to save output JSON metrics.")
    ap.add_argument("--tag", type=str, default=None, help="Tag name printed in the report title.")
    ap.add_argument("--embeddings", type=Path, default=None, help="Path to precomputed embeddings .npz file.")
    ap.add_argument("--embeddings-key", type=str, default="full", choices=["full", "trunc128"], help="Key in npz to use (default: full).")
    args = ap.parse_args()

    shots = [int(x.strip()) for x in args.shots.split(",") if x.strip()]

    # Parse --map CATEGORY=COLLECTION_NAME
    mapped_categories = []
    for item in args.map:
        if "=" in item:
            cat, coll_name = item.split("=", 1)
            mapped_categories.append((cat.strip().lower(), coll_name.strip()))
        else:
            print(f"Warning: ignoring malformed --map argument '{item}' (expected CATEGORY=COLLECTION_NAME)", file=sys.stderr)

    backup_data = load_backup(args.backup)
    labels_dict = load_labels(args.labels)
    text_vectors = load_text_vectors(args.text_vectors)
    categories = list(CATEGORIES.keys())

    external_embeddings = None
    if args.embeddings:
        external_embeddings = load_npz_embeddings(args.embeddings, key=args.embeddings_key)

    usable_screenshots, dataset_summary, swipe_agreement = prepare_dataset(
        backup_data, labels_dict, mapped_categories, external_embeddings=external_embeddings
    )

    validate_dimensions(usable_screenshots, text_vectors)

    results = run_protocol(
        usable_screenshots=usable_screenshots,
        text_vectors=text_vectors,
        categories=categories,
        shots=shots,
        repeats=args.repeats,
        seed=args.seed,
    )

    report = format_markdown_report(dataset_summary, swipe_agreement, results, categories, tag=args.tag)
    print(report)

    if args.out_json:
        out_payload = {
            "dataset_summary": dataset_summary,
            "swipe_agreement": swipe_agreement,
            "results": results,
        }
        if args.tag:
            out_payload["tag"] = args.tag
        with args.out_json.open("w", encoding="utf-8") as f:
            json.dump(out_payload, f, indent=2, default=json_serializable)

    return 0


if __name__ == "__main__":
    sys.exit(main())

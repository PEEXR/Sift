# Task: Phase 0 — evaluation harness for collection assignment methods

**EXECUTION RULE (hard): Do NOT run Gradle, adb, pip, downloads, or any long-running/background command. Only read files and create/edit the files listed below. You MAY run `tools/phase0/.venv/bin/python -m unittest discover -s tools/phase0 -p "test_*.py"` (it must finish in seconds). Do NOT commit. Finish promptly with a summary.**

Only touch (both new): `tools/phase0/evaluate.py`, `tools/phase0/test_evaluate.py`. Do not edit anything else.

## Context
Sift is an Android screenshot organizer. Each screenshot has a 128-number L2-normalized embedding. Users create collections; the app auto-fills them. We are measuring whether those 128 numbers are enough to tell fine-grained kinds of content apart with few examples. Read for reference (do not modify):
- `app/src/main/java/com/peexr/sift/data/collections/CollectionMatcher.kt` — `zScore`, `match`, `refineQuery` (method A must port these exactly, including `refineQuery`'s defaults alpha 0.7, beta 0.4, maxFeedback 10, weights `min(n, maxFeedback)/maxFeedback`, and fall-backs to `base`).
- `app/src/main/java/com/peexr/sift/data/backup/FullBackup.kt` — backup JSON format.
- `tools/phase0/common.py` — `CATEGORIES` (the category keys — iterate over it, never hard-code the count), `EMBEDDING_DIM`.
- `tools/phase0/label_ornith.py` — produces the labels file.

Environment: `tools/phase0/.venv` has numpy only (plus onnxruntime/tokenizers, unused here). **Use only stdlib + numpy.** No sklearn, no pandas.

## Inputs (CLI args)
- `--backup PATH` — FullBackup file, plain JSON or gzip (detect gzip by first two bytes 0x1F 0x8B). Use `screenshots[].displayName`, `screenshots[].embeddingB64` (Base64 of 128 little-endian float32; skip screenshots with null/invalid embedding), `screenshots[].memberships[] {collectionKey, source: AUTO|MANUAL|EXCLUDED}`, `collections[] {key, name}`.
- `--labels PATH` — JSONL, one `{"file": displayName, "labels": [category,...], "source": str}` per screenshot. `labels` may be empty (= belongs to no category). Join on `displayName == file`. Only screenshots with both an embedding and a label line are used.
- `--text-vectors PATH` — JSON `{"background": {phrase: [128]}, "categories": {category: [128]}}`.
- `--map CATEGORY=COLLECTION_NAME` (repeatable, optional) — links a category to one of the user's real collections (by name, case-insensitive). For a mapped category, that collection's MANUAL members are "swipe positives" and EXCLUDED members are "swipe negatives".
- `--repeats` (default 20), `--shots` (default `5,10,20`), `--seed` (default 0), `--out-json PATH` (optional).

## Ground truth
Ornith labels are ground truth: screenshot `s` is in category `c` iff `c in labels[s]`. Multi-label: a screenshot may be in several categories or none. Screenshots with no labels stay in the test set (any assignment to them is a false positive).

## Protocol (per k in shots, per repeat r with RNG seeded by (seed, k, r))
For each category c:
- **Train positives P_c:** k screenshots sampled from those labeled c. Prefer swipe positives of c first (if mapped and they are labeled c), fill the rest randomly. If fewer than k exist, use all and record a warning.
- **Train negatives N_c** (for methods C and D): swipe negatives of c (if mapped) + train positives of other categories that are not labeled c + a random sample of 5·k screenshots not labeled c.
- Method A/B only uses swipe negatives of c as `negatives` for `refineQuery` (that is what the app does); everything else is the app's behaviour.
Test set = all usable screenshots not in any category's P_c or N_c for this repeat. Report its size.

## Methods (each outputs a score matrix S[test, category] and an assignment matrix Y[test, category] of bools)
- **A — current app:** `q_c = refine_query(text_c, P_c, swipe_neg_c)`; `S = z_score(image, q_c, background)`; assign if `S >= t` for t in {1.0, 1.5, 2.0} (report each as A@t; A@1.5 is the baseline).
- **B — competitive:** same S as A. Per screenshot: let best and second-best categories; assign only best, only if `S_best >= 1.5` and `S_best - S_second >= m`, for m in {0, 0.25, 0.5, 0.75, 1.0} (report B@m).
- **C — nearest neighbours:** cosine = dot (vectors are normalized). `S = mean(top-3 cos to P_c) - mean(top-3 cos to N_c)` (use top-min(3, len) if fewer). Assign if `S > 0`. Also report **C+comp**: competitive rule on C's scores with margin 0.
- **D — linear classifier:** per category, L2-regularized logistic regression on 128 features + bias, trained by full-batch gradient descent (or Newton/IRLS) in numpy on P_c (label 1) vs N_c (label 0), class-balanced sample weights, λ = 1.0 (do not regularize the bias). `S = predicted probability`; assign if `S >= 0.5`. Also report **D+comp**: competitive rule on D's probabilities with margin 0.
- Also an **oracle** row for C and D: for each category the threshold that maximizes F1 on the test set (clearly labeled "oracle — not achievable in product").

## Metrics (per method, per k; mean ± std over repeats)
- Per-category precision, recall, F1 (define 0/0 as 0). Macro-F1 over all categories.
- **Overcrowding:** mean number of assigned categories per test screenshot, next to the true mean number of labels.
- **Confusion pairs:** for each ordered pair (true a, predicted b), a ≠ b: count of test screenshots labeled a but not b that were assigned b, averaged over repeats. Print the top 10 pairs for A@1.5 and for the best method.
- **Swipe agreement (only if `--map` given):** for each mapped category, fraction of swipe positives that Ornith labels c, and fraction of swipe negatives Ornith does *not* label c. This checks Ornith against the user.

## Output
- Markdown report to stdout: dataset summary (usable screenshots, label counts per category, % with no label, % multi-label), swipe agreement, then one table per k: rows = methods, columns = macro-F1, per-category F1, overcrowding. Then confusion pairs. Then a one-line "Best at k=10: <method> macro-F1 x.xx" against the 0.85 gate.
- `--out-json`: all numbers, machine-readable.

## Structure
Pure, testable functions, e.g.: `load_backup`, `load_labels`, `decode_embedding`, `z_scores(images, queries, background)` (vectorized), `refine_query`, `assign_threshold`, `assign_competitive(S, threshold, margin)`, `knn_scores`, `train_logreg`/`predict_logreg`, `prf(Y_true, Y_pred)`, `run_protocol(...)`, `main()`. Module docstring with usage like `tools/phase0/label_ornith.py`.

## Tests (`test_evaluate.py`, unittest, synthetic data only, fast)
- `refine_query`: empty feedback returns base; matches a hand-computed example; weight cap at maxFeedback; output normalized.
- `z_scores`: equals the formula on a tiny hand example; std < 1e-6 → 0.
- `assign_competitive`: clear winner assigned alone; near-tie under margin → nothing; below threshold → nothing.
- `knn_scores` and logistic regression: separate two well-separated Gaussian blobs in 128-d (accuracy > 0.95).
- `prf`: hand example including a category with no positives.
- `decode_embedding`: round-trips `struct.pack('<128f', ...)` Base64.
- `load_backup`: reads both plain and gzip temp files.

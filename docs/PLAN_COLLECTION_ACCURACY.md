# Plan: Collection Accuracy & Junk Review

Status: Phases 0, 2 and 5 done; Phase 1 dropped; Phases 3–4 not started. Updated 2026-10-12.

## Problems

1. **Collections were too big.** The old matcher scored every collection on its own (z ≥ 1.5 against 20 background phrases), so a screenshot landed in ~5.7 of 6 collections.
2. **Fine differences get lost** (tweets vs. text on black, memes vs. animals).
3. **Swipe feedback was averaged into one vector** (`refineQuery`), which lands between the kinds of thing a collection holds.
4. **OCR struggles with stylized fonts** and reads Latin script only. It affects search and cleanup, not grouping.
5. **Junk review needs better buckets.** The user decides what is junk; the app offers sensible groups.

## Principles

- Collections stay open-ended. Users name their own; nothing assumes a fixed list.
- The user decides what is junk. Every deletion goes through swipe review and the system delete dialog.
- Measure before changing the model.
- Collections learn from the user's own examples and swipes.

---

## Phase 0: Measurement — done

**Setup** (scripts in `tools/phase0/`, data in `tools/phase0/data/`, gitignored)
- 962 screenshots from the vivo. Reference labels from Ornith 1.5 9B (Ollama, images downscaled to 1024 px, ~6.6 s/img on an RX 6600). Categories: chats, study, tweets (incl. comment sections), memes, animals, notes. Multi-label.
- Ornith's self-agreement (fast vs. reasoning mode) ≈ 0.86 on the five main categories — the practical ceiling for any method scored against it.
- Methods at k = 10 examples per category: A (old matcher), B (competitive), C (nearest neighbours), D (per-collection L2 logistic regression).

**Results** (macro-F1 vs. Ornith, k = 10, 6 categories)

| Method | Phone vectors (128-d @140) | Full 768-d @140 |
|---|---|---|
| A — old matcher | 0.24 (5.7 collections / screenshot) | — |
| B — competitive | 0.55 | — |
| C — nearest neighbours | 0.48 | — |
| **D — logistic regression** | **0.58–0.60** | 0.64 |
| D with the best possible threshold (oracle) | 0.69 | ~0.70 |

- Gemma 4 E2B (LiteRT-LM, as a labeler): 0.65 on 6 categories / ~0.74 on the five main ones. Reasoning mode: no gain, 12× slower. Batching 10 images per prompt broke accuracy (0.30–0.38). Speculative decoding: no effect for short answers.
- The 0.85 gate was not reached by any method, including Gemma 4. The owner judged D's quality acceptable.

**Decision:** D (logistic regression per collection) on the existing EmbeddingGemma 2 vectors. No vision-language model in the app.

## Phase 1: Competitive assignment — dropped

Competition hurts collections that overlap: a cat meme belongs in Memes *and* Animals (D+comp is 0.05 below D). Screenshots may stay in several collections (answers open question 2).

## Phase 2: Collections that learn from examples — done

- **Every collection starts with ~10 examples.** Creating a collection = name + pick 10 screenshots (`ui/collections/ExamplePicker.kt`). Onboarding makes the first collection right after the model download starts, which also gives the download time to finish.
- **Classifier** (`data/collections/CollectionClassifier.kt`): L2 logistic regression (λ = 1, class-balanced, Newton/IRLS), trained at refresh time, nothing stored. Positives = MANUAL members; negatives = EXCLUDED members + other collections' MANUAL members + a seeded library sample (5× positives, 50–200). Threshold from sensitivity: Strict 0.65, Balanced 0.5, Loose 0.35. Fewer than 5 examples with vectors → old text matcher.
- **Indexing:** example screenshots are embedded first (also ones added mid-run); collections refresh as soon as the examples are in, every 50 screenshots, and at the end.
- **Learning from adds:** viewer toggles refresh the collection; "Find more" review shows near-misses (0.15 ≤ p < threshold) to add or reject.

**Device result** (vivo, 1,022 screenshots, 5 collections × 10 examples, Balanced; 916 test screenshots, examples excluded)

| Collection | App P | App R | App F1 | Gemma 4 F1 |
|---|---|---|---|---|
| Tweets | 0.93 | 0.54 | 0.68 | 0.84 |
| Chats | 0.73 | 0.56 | 0.63 | 0.70 |
| Memes | 0.55 | 0.39 | 0.46 | 0.58 |
| Animals | 0.65 | 0.54 | 0.59 | 0.82 |
| Study notes and papers | 0.77 | 0.67 | 0.72 | 0.74 |
| **Macro** | | | **0.62** | **0.74** |

- Training: ~22 ms per collection on the vivo. A Python replica on the same vectors gives 0.60, so the app matches the harness. Lowering the threshold does not help (0.4 → 0.56).
- The app is precise but conservative (0.59 collections per screenshot vs. Ornith's 0.84) — hence "Find more".
- Memory: ~1.24 GB PSS after indexing with the model loaded (~1 GB native). The engine now releases the vision session after indexing and everything after 60 s idle.
- Further gain if needed: store full 768-d vectors (+~0.04 on the PC; needs a DB migration).

## Phase 3: Source hints from OCR — not started

A `SourceDetector` turning OCR text (and file name) into `x`, `whatsapp`, `instagram`, `reddit`, `telegram`, `youtube`, `upi_payment`, `unknown`; stored in a new `source` column (migration + backfill), used as a review/library filter and optionally as classifier input.
- The vivo's file names carry only date and time (`Screenshot_20260730_002437.jpg`, `Screenshot_2026_0730_104500.png`), so detection must rely on on-screen text. The Nothing's names are unchecked.
- Lower priority now that the classifier quality is accepted.

## Phase 4: Junk review — not started (next)

Tags that hold for every user, from OCR text, vectors and file data. Some exist in `CleanupRules.kt`.

| Tag | Source | Status |
|---|---|---|
| `code` (OTP / verification code) | `looksLikeCode` | exists |
| `status` (delivery, order confirmed, payment done) | OCR patterns | new |
| `dated` (ticket, event or offer whose date has passed) | extends `TIMED_EXPIRY_REGEX` with date parsing | new |
| `accidental` (half-open keyboard, notification shade, blank or loading screen, error dialog) | OCR patterns plus very little text | new |
| `duplicate` | `findBursts` plus near-identical vectors (cosine ≥ ~0.97) | partly exists |

Review presets combine **what** (collection, source or tag), **when** (age) and **which** (best of each duplicate group), e.g. "Chats older than 6 months", "Codes & delivery updates older than a week", "Duplicates". Safety rules unchanged: swipe review → summary card → `createDeleteRequest`.

## Phase 5: Vision-language model spike — done, not adopted

Gemma 4 E2B on the vivo: ~7 s/img (13 s with reasoning), 2.6 GB download, an estimated 2–3 GB RAM while loaded. EmbeddingGemma 2 on the vivo: ~5.8 s/img at budget 140, and classification adds no extra time. Accuracy gain (0.74 vs. 0.62) judged not worth the cost.

## Execution notes

- Workers: Gemini 3.8 Flash via `agy`, one stage per dispatch, specs in `.agent/tasks/`. Workers don't run Gradle; Claude runs the gate.
- Build on Linux: JDK 17, Android SDK 36, `bash ./gradlew testDebugUnitTest assembleDebug`.
- Device testing: debug builds include `DebugCommandReceiver` (`adb shell am broadcast -n com.peexr.sift/.debug.DebugCommandReceiver -a com.peexr.sift.DEBUG --es cmd create|refresh|status`). Keep Sift in the foreground on the vivo (background freezer).
- Scoring a device run: pull `databases/sift_database*` via `run-as`, then `tools/phase0/eval_device.py DB --map Name=category ...`.

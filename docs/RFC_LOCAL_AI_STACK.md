# [Design Record / RFC] On-Device AI Stack for Sift: Hybrid Search, Semantic Collections & Safe Cleanup

## 1. Document Status & Overview

This document was originally written as an architectural RFC prior to implementation. It has since been updated as the **canonical design record** reflecting the actual production implementation in `sift_android`.

Sift operates as a 100% private, on-device screenshot intelligence application. The shipped architecture combines fast lexical retrieval, multimodal neural embeddings, adaptive indexing calibration, and a safe review-before-deletion workflow.

### Decisions vs. Original Plan

| Architectural Area | Original RFC Proposal | Final Implementation | Rationale / Difference |
| :--- | :--- | :--- | :--- |
| **Embedding Model** | EmbeddingGemma 2 via Google LiteRT / MediaPipe Tasks | **EmbeddingGemma 2 ONNX Runtime** (`1.30.0`) | LiteRT lacked stable Android bindings for Gemma multimodal vision graphs; ONNX Runtime provides deterministic CPU execution. |
| **Model Distribution** | Bundled / unspecified delivery | **On-demand download from Hugging Face** (~316 MB) | Keeps initial APK lightweight (~38 MB); downloads via chunked 16 MB HTTP range requests with SHA-256 validation. |
| **Modalities** | Text + Image + Audio | **Text + Image only** (Audio encoder excluded) | Audio encoder dropped to conserve ~110 MB memory and storage without affecting screenshot retrieval. |
| **Decision Engine** | Laya (ModernBERT ONNX) + Jev Cloud API | **Heuristic rules & Rocchio relevance feedback**; No Laya | Laya and tiny LLMs were omitted to prevent thermal and battery drain. Jev API client is preserved in `data/api` and repository for future use, but is not connected to UI. |
| **Collections** | Two-stage classifier (Gemma + Laya) | **Z-score thresholding against 20 background priors** + Rocchio feedback | Single unified collection model: zero separate categories, user prompt auto-fills via z-score and refines through swipe feedback. |
| **Cleanup Workflow** | Ascending-confidence safety ramp ($60\% \to 95\%$) with "Purge All Remaining" unlock | **Deterministic rules + 2-mode swipe review + final system confirmation card** | Eliminated opaque LLM confidence scoring. Deletions require user review, summary card verification, and a system `createDeleteRequest` dialog. |
| **Database** | Android system Room + SQLite FTS5 | **Room 2.8.5 + bundled `requery:sqlite-android` (3.49.0)** | System SQLite on certain OEM builds lacks the FTS5 extension module; bundling sqlite-android guarantees FTS5 availability. |

---

## 2. System Architecture & Model Pipeline

```
                                ┌───────────────────────────────────────┐
                                │          Screenshot Ingestion         │
                                └───────────────────┬───────────────────┘
                                                    │
                      ┌─────────────────────────────┴─────────────────────────────┐
                      ▼                                                           ▼
         [ Google ML Kit OCR ]                                      [ EmbeddingGemma 2 (ONNX) ]
         • On-device text recognition                               • Gemma4 image patchifier (16 px patches)
         • Words, codes, and tokens                                  • Vision encoder + fusion graph
                      │                                                           │
                      ▼                                                           ▼
         [ SQLite FTS5 Virtual Table ]                              [ 128-d MRL Embedding BLOB ]
         • Prefix query token matching                               • Cosine similarity search
                      │                                                           │
                      └─────────────────────────────┬─────────────────────────────┘
                                                    ▼
                                    [ Reciprocal Rank Fusion (RRF) ]
                                    • k = 60 rank blending
                                                    │
                                                    ▼
                                        [ Instant Hybrid Search ]
```

### Component Stack

| Layer | Technology | Role | Execution Details |
| :--- | :--- | :--- | :--- |
| **Lexical Engine** | Google ML Kit Text Recognition + SQLite FTS5 | Exact keywords, serial numbers, OTPs, URLs, codes | Runs per screenshot during indexing; indexed in FTS5 via bundled `requery` engine. |
| **Semantic Engine** | EmbeddingGemma 2 (`q4` ONNX) | Cross-modal text $\leftrightarrow$ image retrieval | 128-d Matryoshka truncation, L2-normalized; CPU inference with intra-op threading (max 4 cores). |
| **Vector Index** | Pure Kotlin Vector Math + SQLite BLOB | In-memory SIMD dot products over 128-d floats | Top-$k$ cosine similarity scan across 512-byte byte arrays (<2 ms for 5,000 items). |
| **Fusion Layer** | Reciprocal Rank Fusion (`RankFusion.kt`) | Merge lexical and dense rankings | $RRF(d) = \sum \frac{w_i}{k + r_i(d)}$ with $k=60$; preserves deterministic ordering. |
| **Classification** | Background z-score + Rocchio Feedback | Collection matching and refinement | Normalized similarity against 20 reference concepts; refined via swipe history. |
| **Storage Engine** | Room 2.8.5 + `requery:sqlite-android` 3.49.0 | Local relational & full-text persistence | Bundled SQLite guarantees FTS5 across all Android OEM variants; recursive triggers enabled. |

---

## 3. Embedding Runtime & Model Delivery

### Model Artifacts & On-Demand Downloader
The embedding pipeline relies on the Hugging Face ONNX Community release of `embeddinggemma-2-ONNX`:
* `onnx/model_q4.onnx` (~491 KB) & `onnx/model_q4.onnx_data` (~174 MB)
* `onnx/vision_encoder_q4.onnx` (~159 KB) & `onnx/vision_encoder_q4.onnx_data` (~109 MB)
* `tokenizer.json` (~32 MB, streaming BPE tokenizer)
* **Total Download Size:** ~316 MB (315,807,148 bytes). Audio encoder was explicitly excluded.

**Resilient Downloader (`ModelDownloader.kt`):**
* Requests data using 16 MB fixed HTTP range chunks (`Range: bytes=start-end`).
* Writes to temporary `.part` files; recovers interrupted downloads without losing progress.
* Implements exponential/linear backoff with up to 6 retry attempts per chunk.
* Validates SHA-256 checksums before atomically renaming to final filenames.

### Gemma Vision Processing & Tokenization
* **Gemma4 Patchifier (`ImagePatchifier.kt`):** Images are resized such that width and height are multiples of 48 (pool factor $3 \times$ patch size $16$). Patches are decomposed into $16 \times 16$ pixel grids with values normalized to $[0.0, 1.0]$. The number of patches equals `softTokens * 9`.
* **Token Budgets (`maxSoftTokens`):** Supported budgets are $70, 140, 280, 560, 1120$.
* **Fusion Input IDs:** Formatted as `[2 (<bos>), 255999 (<|image>), 258880 * T (<|image|>), 258882 (<image|>), 1 (<eos>)]` where $T = \text{softTokens}$.
* **Text Tokenization (`GemmaTokenizer.kt`):** Implements BPE tokenization with byte-fallback UTF-8 parsing, automatic `<bos>`/`<eos>` wrapping, and query prefix: `task: search result | query: `.
* **Matryoshka Truncation:** Embeddings are truncated to 128 dimensions and L2-normalized (`truncateAndNormalize`).

---

## 4. Indexing Engine & Hardware Calibration

### Quality Presets & Automatic Calibration (`IndexingQuality.kt`)
Inference execution is CPU-bound across 4 worker threads. Compute requirements scale linearly with the soft-token budget:

| Preset | Budget (`maxSoftTokens`) | Target Profile | Characteristics |
| :--- | :--- | :--- | :--- |
| **FAST** | 70 | Low-power / budget devices | Fastest throughput; slightly lower accuracy on tiny text. |
| **BALANCED** | 140 | Mid-tier hardware | Strong recall; balanced thermal profile (~half of Best). |
| **BEST** | 280 | Flagship devices / overnight runs | Maximum semantic precision. |
| **AUTO** | Dynamic (70, 140, or 280) | Device-calibrated | Calibrates on device to pick the best budget completing within 8 hours. |

### On-Device Measurement Benchmarks
Values marked *est.* are linear extrapolations from the measured 280-budget figure (cost scales with the budget); they were not timed on that device.

During AUTO calibration, the worker benchmarks the first 3 images at budget 140, normalizes the observation to budget 280, and maintains an Exponential Moving Average (EMA, $\alpha=0.3$):

| Device Platform | Processor Class | Sec / Image @ 70 | Sec / Image @ 140 | Sec / Image @ 280 |
| :--- | :--- | :--- | :--- | :--- |
| **Snapdragon 7+ Gen 3** | High-tier ARMv9 | ~2.8 s (est.) | ~5.5 s (est.) | **~11.0 s** (measured) |
| **MediaTek MT6886 (Dimensity 7200)** | Mid-tier ARMv8 | **~5.2 s** (measured) | **~10.6 s** (measured) | **~19.5 s** (measured) |

### Indexing Scheduling & OEM Freezer Handling
* **WorkManager Foreground Service (`EmbeddingWorker.kt`):** Runs as a foreground service with notification channel `sift_indexing` (`FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING` on Android 14+ / `DATA_SYNC` on Android 10–13).
* **Triggers:**
  1. Manual user trigger ("Index now" button in Library header card).
  2. App open check (`MainActivity.onStart()` $\to$ `autoIndexIfNeeded()`).
  3. MediaStore ContentObserver: Registered on `EXTERNAL_CONTENT_URI` with 3-second debounce.
  4. Periodic catch-up (`AutoIndexWorker`): Scheduled every 12 hours (flex 4 hours) while device is charging and battery not low.
* **Keep-Screen-On Design:** Aggressive OEM process management (e.g., vivo freezer policies) halts background CPU threads when the screen turns off. To guarantee indexing completion for large libraries, `LibraryScreen` asserts `view.keepScreenOn = true` while indexing is actively running.

---

## 5. Collections Architecture & Semantic Auto-Fill

Collections represent dynamic semantic groups without pre-defined factory defaults. Users create collections by specifying a name and natural-language description.

### Z-Score Normalization (`CollectionMatcher.kt`)
Cosine similarity scores vary across different screenshot distributions. To ensure consistent thresholding, image similarities are normalized against 20 hidden background descriptions (e.g., `"meme or joke image"`, `"chat or messaging conversation"`, `"source code"`, `"receipt or payment"`):

$$z = \frac{\cos(\mathbf{v}_{\text{image}}, \mathbf{v}_{\text{collection}}) - \mu_{\text{background}}}{\sigma_{\text{background}}}$$

* **Sensitivity Settings:**
  * **Strict:** $z \ge 2.0$ (High precision, minimal noise).
  * **Balanced:** $z \ge 1.5$ (Default; captures true matches plus near-misses for review).
  * **Loose:** $z \ge 1.0$ (High recall).
* **Membership States:**
  * `AUTO`: Assigned automatically by the embedding matcher.
  * `MANUAL`: Explicitly kept by the user via review swipe right or collection sheet.
  * `EXCLUDED`: Explicitly dismissed by the user via swipe left (remembered to prevent reappearance).

### Rocchio Relevance Feedback
Following a collection review session, the collection's query representation is adapted based on user decisions (`refineQuery`):

$$\mathbf{q}_{\text{refined}} = \mathbf{q}_{\text{base}} + \alpha \cdot w_P \cdot \boldsymbol{\mu}_{\text{kept}} - \beta \cdot w_N \cdot \boldsymbol{\mu}_{\text{removed}}$$

Where $\alpha = 0.7$, $\beta = 0.4$, $w = \min(N, 10)/10$, and the resulting vector is L2-normalized.

---

## 6. Cleanup Engine & Safe Review UX

The preliminary RFC's ascending-confidence ramp ($60\% \to 95\%$) and one-tap mass purge were replaced by deterministic heuristic filters and a two-mode card review pipeline.

### Dual-Mode Swipe Review (`ReviewScreen.kt`)
1. **Collection Review Mode:**
   * Stack contains unreviewed `AUTO` candidates sorted by lowest confidence first.
   * **Swipe Right / Lime:** Marks item as `MANUAL` (Kept).
   * **Swipe Left / Coral:** Marks item as `EXCLUDED` (Removed from collection).
   * **Delete File:** Dedicated secondary button invoking system file deletion.
2. **Search & Cleanup Mode:**
   * Stack populated from hybrid search or cleanup presets.
   * **Swipe Left / Coral:** Marks screenshot for deletion.
   * **Swipe Right / Lime:** Keeps screenshot (skips deletion).
   * **Mark Remaining:** Unlocks when the stack reaches strong matches (top 40% or verified criteria).
   * **Summary & Confirmation:** No files are deleted during swiping. A summary card details marked items and total reclaimable megabytes. Tapping "Delete files" issues a single system `MediaStore.createDeleteRequest` prompt.

### Deterministic Cleanup Presets (`CleanupRules.kt`)
* **Age Filter Chips:** 30, 90, 180, and 365 days.
* **Codes & Expiring:** Regex analysis over ML Kit OCR text:
  * OTP detection: Matches OTP keywords combined with standalone 4–8 digit tokens (`(?<!\d)\d{4,8}(?!\d)`).
  * Expiring tokens: Matches temporal phrases (`"expires in"`, `"valid until"`).
  * Items older than 7 days are categorized as strong cleanup candidates.
* **Near-Duplicates (Burst Detection):**
  * Evaluates chronological runs where consecutive screenshots occur within $\le 10$ minutes ($600\text{ s}$) with embedding cosine similarity $\ge 0.92$.
  * Retains the newest screenshot of each burst and marks older captures for deletion.
* **Visual Card Presentation:** Displays the complete screenshot centered over a blurred, dimmed backdrop ($28\text{ dp}$ blur, $35\%$ scrim) alongside an extracted OCR text snippet.

---

## 7. UI System & Application Features

* **Material 3 Expressive:** Built against `androidx.compose.material3:material3:1.5.0-alpha18` (pinned; newer alpha/beta releases require AGP 9.1 and compileSdk 37).
* **Styling & Fonts:** Brand violet seed (`#6B3FD4`), Bricolage Grotesque display typography, and DM Sans body typography.
* **Navigation:** Floating pill navigation host (`Library` · `Collections` · `Review`).
* **In-App Viewer (`ViewerScreen.kt`):** Gesture pan/zoom, collection membership management sheet, selectable OCR text copy sheet, and single-file deletion.
* **Onboarding & Settings:**
  * 4-step first-run wizard (Welcome, Permissions, Model download, Ready).
  * Settings provides model download management, indexing quality configuration, auto-fill sensitivity tuning, license disclosures, and full JSON collection backup/restore (matching screenshots by display name, timestamp, and byte size).
* **Window & Display:** Edge-to-edge windowing with automatic scrimming behind 3-button navigation, alongside explicit high-refresh-rate requests for 120 Hz panels.

---

## 8. Release Configuration & Privacy Model

* **Packaging:** Single arm64-only APK (`arm64-v8a`) reduced to ~38 MB via R8 code minification and resource shrinking.
* **Security & Build Setup:**
  * Android backup disabled (`android:allowBackup="false"`).
  * Cleartext HTTP disabled (`android:usesCleartextTraffic="false"`).
  * Signing keys externalized from repository (`.sift-signing/keystore.properties`).
  * Dedicated `benchmark` build variant matching release optimization but debug-signed for local profiling.
* **Zero-Leakage Privacy:** All inference, indexing, and search operations execute entirely on device. Internet access is restricted solely to the on-demand download of the open-weights model from Hugging Face.

---

## 9. Implementation Roadmap & Status Record

```
[Phase 1: DB & FTS5] ──► [Phase 2: EmbeddingGemma] ──► [Phase 3: Decision Engine] ──► [Phase 4: Cleanup & UI]
      COMPLETED                   COMPLETED                    SUPERSEDED                    COMPLETED
 (Room + SQLite FTS5)        (ONNX Runtime 1.30.0)      (Replaced by Heuristics)       (M3 Expressive + Review)
```

| Phase / Stage | Scope | Implementation Status | Notes |
| :--- | :--- | :--- | :--- |
| **Phase 1** | Database & Hybrid Search Foundation | **Completed** | Bundled `requery:sqlite-android` with FTS5, 128-d BLOB column, in-memory vector math. |
| **Phase 2** | EmbeddingGemma 2 Multimodal Integration | **Completed** | ONNX Runtime Android 1.30.0, Gemma4 patchifier, chunked model downloader. |
| **Phase 3** | Laya Local Decision Engine | **Superseded / Dropped** | ModernBERT/Laya omitted to avoid thermal overhead; Jev API client retained in `data/api` but unused. |
| **Phase 4** | Safety-First Cleanup UX | **Replaced / Completed** | Ascending-confidence LLM meter replaced by dual-mode review, summary card, and batch delete request. |
| **Stage 5** | Material 3 Expressive UI Redesign | **Completed** | Floating pill nav, Bricolage Grotesque + DM Sans, violet seed theme. |
| **Stage 6** | Cleanup Rules & Near-Duplicate Bursts | **Completed** | OTP/expiring OCR patterns (7-day threshold), burst grouping ($\le 10$ min, cosine $\ge 0.92$). |
| **Stage 7** | Auto-Indexing & MediaStore Sync | **Completed** | ContentObserver debounce (3 s), periodic 12 h catch-up worker, keep-screen-on safeguard. |
| **Stage 8** | Indexing Quality Calibration | **Completed** | AUTO/FAST/BALANCED/BEST presets, 3-image calibration, EMA device tracking. |
| **Stage 9** | Onboarding & Settings Management | **Completed** | First-run setup, sensitivity options, model manager, licenses. |
| **Stage 10** | Collections Backup & Restore | **Completed** | JSON export/import matched by name, timestamp, and size. |
| **Stage 11** | Fullscreen Viewer & Text Sheet | **Completed** | Zoom/pan, OCR text extraction sheet, collection assignment sheet. |
| **Stage 12** | Production Release & Profiling | **Completed** | R8 shrinking, arm64-only APK (~38 MB), benchmark build variant. |

---

## 10. Known Limitations & Future Work

1. **Auto-Fill Precision vs. Recall:** Z-score thresholding provides strong baseline filtering but occasionally surfaces near-misses on visually ambiguous shots; the swipe review workflow remains essential for verification.
2. **Android 14+ Photo Picker Permissions:** The app requires full `READ_MEDIA_IMAGES` access to synchronize gallery contents; Android 14 partial access ("Select photos and videos") is not yet handled gracefully.
3. **Toolchain Upgrades:** Android Gradle Plugin 9.x and Compose Material 3 beta migration are deferred until compileSdk 37 tooling stabilizes.
4. **Offline Cold Start:** First launch requires network connectivity to download the 316 MB embedding model from Hugging Face before semantic capabilities become active.
5. **Real-World Metrics Logging:** Retrieval precision and indexing latency are calibrated locally, without automated telemetry or analytics pipelines.
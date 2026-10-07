
# [Feature Request / RFC] Next-Gen Local AI Stack for Sift: Hybrid Search, Smart Collections & Safety-First Cleanup

## 1. Executive Summary
Transform Sift into a fully private, on-device intelligence platform by upgrading from keyword/ML-Kit heuristics to a multi-model architecture:
1. **EmbeddingGemma 2 (LiteRT):** Native cross-modal visual & semantic vector search.
2. **Google ML Kit OCR + SQLite FTS5:** Exact lexical, alphanumeric, and code retrieval.
3. **Laya (Local ONNX) / Jev (API Fallback):** Non-autoregressive System 1 decision engine for collection triage and cleanup scoring.
4. **Safety-First "Ascending" Cleanup UX:** A Google One–style swipe-to-delete interface that reviews ambiguous items first to safely unlock one-tap mass deletion for obvious trash.

---

## 2. Current Pain Points
* **Lookup Limitations:** Current search relies on substring matching against OCR text and generic ML Kit labels (`"font"`, `"magenta"`), failing on conceptual queries (*"coffee receipt"*, *"flight pass"*, *"funny cat meme"*).
* **Cloud & API Key Dependency:** Smart triage and custom purge directives currently require an external TypeSafe / OpenRouter API key via [JevApiClient.kt](file:///c:/Users/systems/Downloads/sift_android/app/src/main/java/com/peexr/sift/data/api/JevApiClient.kt), preventing offline operation.
* **Cleanup False-Positive Risk:** Standard descending cleanup (obvious trash first) creates fatigue, causing users to mass-delete the remaining ambiguous items at the bottom where false positives hide.

---

## 3. Proposed Architecture & Model Stack

```
                          ┌─────────────────────────────────────┐
                          │         Screenshot Ingestion        │
                          └──────────────────┬──────────────────┘
                                             │
                     ┌───────────────────────┴───────────────────────┐
                     ▼                                               ▼
         [ Google ML Kit OCR ]                             [ EmbeddingGemma 2 ]
         • Extracts text & codes                           • Ingests raw bitmap pixels
         • Indexed in SQLite FTS5                          • Output: 128d MRL Vector
                     │                                               │
                     ▼                                               ▼
     ┌───────────────────────────────┐               ┌───────────────────────────────┐
     │      Exact Token Search       │               │     Semantic Vector Search    │
     └───────────────┬───────────────┘               └───────────────┬───────────────┘
                     └───────────────────────┬───────────────────────┘
                                             ▼
                             [ Reciprocal Rank Fusion (RRF) ]
                                             │
                                             ▼
                                 [ Instant Search Results ]
```

| Component | Technology | Role | Execution |
| :--- | :--- | :--- | :--- |
| **Visual Perception** | **EmbeddingGemma 2** (LiteRT) | Cross-modal text $\leftrightarrow$ image embeddings (MRL truncated to 128d) | On-Device (NPU/GPU) |
| **Lexical Engine** | **ML Kit OCR + Room FTS5** | Exact keywords, serial numbers, OTPs, Wi-Fi keys, URLs | On-Device |
| **Decision Engine** | **Laya** (ModernBERT ONNX) / **Jev** (API) | Structured `noul` (yes/no) & `score` evaluations | On-Device (Laya) / Cloud (Jev) |
| **Local Storage** | **Android Room + SQLite** | BLOB column for 128d vectors; FTS virtual table for OCR | On-Device |

---

## 4. Key Features & Specifications

### Feature A: Instant Hybrid Search (Lookups)
* **Dense Vector Search:** Fast cosine similarity of query text against pre-indexed 128d image vectors (<2ms across 5,000 items in Kotlin).
* **Sparse Lexical Search:** SQLite `FTS5` BM25 search over OCR text for exact strings and numbers.
* **Fusion:** Reciprocal Rank Fusion (RRF) combines sparse and dense rankings into a single unified result list as the user types.

### Feature B: Two-Stage Smart Collections
* **Visual / Topical Collections** (*"Sneakers"*, *"Cooking"*, *"Memes"*):
  * Pure **EmbeddingGemma 2** vector distance against collection description (instant, zero cost).
* **Rule-Based Collections** (*"Work Expenses only (exclude personal receipts)"*):
  * **Stage 1:** EmbeddingGemma 2 generates top candidate matches.
  * **Stage 2:** **Laya / Jev** evaluates discrete `noul` criteria to enforce strict inclusion/exclusion logic.

### Feature C: Safety-First Cleanup (Swipe-to-Delete)
Inspired by Google One storage manager, but with an **ascending confidence safety ramp**:

```
[ Low Confidence (60%) ] ──► [ Medium Confidence (75%) ] ──► [ High Confidence (90%+) ]
     User Audits Gray Area        User Confirms Pattern         Dynamic Unlock:
     (Rescues false positives)    (Builds confidence)           [ PURGE ALL REMAINING (34) ]
```

1. **Heuristic Pre-Filtering (Stage 0):** Immediate regex identification of ephemeral content (6-digit OTP codes, `"expires in 10 minutes"`) and age filters (>6 months).
2. **Scoring Pipeline (Stage 1 & 2):** EmbeddingGemma 2 retrieves candidate junk $\rightarrow$ Laya/Jev assigns calibrated `0–100%` confidence and generates a rationale.
3. **Ascending Triage Queue:**
   * Items are sorted in **ascending order of junk confidence** ($60\% \rightarrow 95\%$).
   * The user reviews the *least certain* items first, rescuing borderline memories or important receipts.
   * As confidence crosses the **$\ge 85\%$ threshold**, the UI reveals a prominent banner:  
     > **"All ambiguous items audited! The remaining 34 screenshots are high-confidence junk. [Purge All Remaining]"**
   * Eliminates the risk of accidental mass deletion.

---

## 5. Technical Implementation Roadmap

### Phase 1: Database & Hybrid Search Foundation
* Upgrade [AppDatabase.kt](file:///c:/Users/systems/Downloads/sift_android/app/src/main/java/com/peexr/sift/data/local/AppDatabase.kt) to include:
  * `FTS5` virtual table for `ocr_text`.
  * `embedding: ByteArray` column in `ScreenshotEntity` (128 floats $\times$ 4 bytes = 512 bytes/item).
* Implement in-memory SIMD/FloatArray cosine similarity in Kotlin.

### Phase 2: EmbeddingGemma 2 Integration
* Integrate Google LiteRT / MediaPipe Tasks Android SDK (`tasks-vision` & `tasks-text`).
* Set up a background `WorkManager` worker with battery and charging constraints (`setRequiresCharging(true)`, `setRequiresDeviceIdle(true)`) for initial camera roll indexing.

### Phase 3: Laya Local Decision Engine
* Add `onnxruntime-android` dependency to [build.gradle.kts](file:///c:/Users/systems/Downloads/sift_android/app/build.gradle.kts).
* Package quantized `laya-modernbert-int8.onnx` as an on-demand asset.
* Update [JevApiClient.kt](file:///c:/Users/systems/Downloads/sift_android/app/src/main/java/com/peexr/sift/data/api/JevApiClient.kt) to allow toggling between **Local Laya**, **OpenJev LAN Server**, or **TypeSafe Cloud API**.

### Phase 4: Expressive Swipe-to-Delete UI
* Build an expressive swipeable card stack in Compose M3 Expressive ([CleanupScreen.kt](file:///c:/Users/systems/Downloads/sift_android/app/src/main/java/com/peexr/sift/ui/cleanup/CleanupScreen.kt)).
* Implement the ascending confidence meter and dynamic "Purge Remaining" trigger.

---

## 6. Success Metrics
* **Search Accuracy:** >90% retrieval success on non-text visual searches (memes, objects, charts).
* **Search Latency:** Under 25ms end-to-end as-you-type search latency on mid-tier hardware.
* **Privacy & Reliability:** 100% of search, collection sorting, and cleanup functions work without internet connectivity or API keys.
* **User Safety:** Near-zero accidental deletions reported during gallery cleanup.
# Sift Android Architecture & Code Map

This document serves as a companion code map for `docs/RFC_LOCAL_AI_STACK.md`, outlining the layer responsibilities, core abstractions, and primary source files across `sift_android`.

---

## 1. Package Organization

```
com.peexr.sift/
├── MainActivity.kt                  # Activity entry point, window insets, refresh rate, permissions
├── SiftApplication.kt              # App lifecycle, dependency graph, background scopes
├── data/
│   ├── api/                        # Remote API integration
│   │   └── JevApiClient.kt         # TypeSafe/OpenRouter client (retained; unused by UI)
│   ├── backup/                     # Portability & serialization
│   │   └── CollectionsBackup.kt    # JSON export/import matched by name, timestamp, and size
│   ├── cleanup/                    # Deletion heuristics
│   │   └── CleanupRules.kt         # Age filters, OTP regex, burst near-duplicate grouping
│   ├── collections/                # Collection auto-population
│   │   └── CollectionMatcher.kt    # Background z-score calculation & Rocchio feedback
│   ├── embedding/                  # On-device neural inference
│   │   ├── AutoIndexPolicy.kt      # Pure auto-indexing trigger preconditions
│   │   ├── AutoIndexWorker.kt      # Periodic 12-hour background catch-up worker
│   │   ├── EmbeddingEngine.kt      # ONNX Runtime sessions, Matryoshka truncation, L2 normalization
│   │   ├── EmbeddingWorker.kt      # WorkManager foreground indexing service & scheduler
│   │   ├── FileHashUtils.kt       # Streaming SHA-256 validation
│   │   ├── GemmaTokenizer.kt       # BPE tokenizer with byte fallback and query prefix
│   │   ├── ImagePatchifier.kt      # 16 px patchifier & Gemma4 target dimension scaling
│   │   ├── IndexingQuality.kt      # Token budgets (70/140/280), EMA tracking, ETA estimation
│   │   ├── ModelDownloader.kt      # Chunked 16 MB range downloader with retry and resume
│   │   └── ModelStore.kt           # On-disk model manifest (~316 MB) and installation state
│   ├── local/                      # Persistence layer
│   │   ├── AppDatabase.kt          # Room 2.8.5 database using requery sqlite-android
│   │   ├── Entities.kt             # ScreenshotEntity, CollectionEntity, CrossRef
│   │   ├── Migrations.kt           # Schema migrations and FTS5 virtual table setup
│   │   ├── ScreenshotDao.kt        # Room DAO with FTS5 MATCH and BLOB queries
│   │   └── SettingsManager.kt      # Encrypted/standard SharedPreferences configuration
│   ├── model/                      # Domain & data transfer models
│   │   ├── CollectionModels.kt     # Summaries, member models
│   │   └── JevModels.kt            # Jev question & request schemas
│   ├── perception/                 # Image preprocessing
│   │   └── PerceptionEngine.kt     # Google ML Kit Text Recognition & Image Labeling
│   ├── repository/                 # Repository layer
│   │   └── ScreenshotRepository.kt # Central coordinator for search, collections, indexing
│   └── search/                     # Vector and lexical retrieval math
│       ├── RankFusion.kt           # Reciprocal Rank Fusion (RRF, k=60) & FTS query sanitization
│       └── VectorMath.kt           # 128-d byte/float conversions, dot product, top-k cosine
└── ui/
    ├── collections/                # Collections browser & detail screens
    │   ├── CollectionDetailScreen.kt
    │   ├── CollectionDetailViewModel.kt
    │   ├── CollectionsListScreen.kt
    │   ├── CollectionsScreen.kt
    │   └── CollectionsViewModel.kt
    ├── components/                 # Shared UI elements
    │   └── ScreenshotTile.kt       # Standard thumbnail tile with rounded corners
    ├── library/                    # Primary gallery & search screen
    │   ├── LibraryScreen.kt        # Grid, date sections, keep-screen-on during indexing
    │   └── LibraryViewModel.kt     # MediaStore ContentObserver, debounced sync, search
    ├── navigation/                 # Navigation framework
    │   ├── FloatingPillNav.kt      # Material 3 Expressive floating pill bottom bar
    │   ├── SiftDestination.kt      # Screen route definitions
    │   └── SiftNavHost.kt          # Root navigation graph
    ├── onboarding/                 # First-run experience
    │   ├── OnboardingLogic.kt      # Step progression and skip rules
    │   └── OnboardingScreen.kt     # 4-step wizard (welcome, permissions, model, ready)
    ├── review/                     # Swipe triage & cleanup screen
    │   ├── CardImage.kt            # Foreground image display modes (Fit / Tall)
    │   ├── ReviewPicker.kt         # Entry selector for collection vs search/cleanup review
    │   ├── ReviewScreen.kt         # Review route, deletion dialogs, summary card
    │   ├── ReviewSession.kt        # Pure review state machine, actions, and history
    │   ├── ReviewViewModel.kt      # Review queue loading, Rocchio trigger, deletion request
    │   └── SwipeCardStack.kt       # Spring physics swipe gestures, blurred backdrop, OCR snippet
    ├── settings/                   # Configuration & diagnostics
    │   ├── SettingsScreen.kt       # Quality preset picker, model status, export/import, licenses
    │   └── SettingsViewModel.kt    # Calibration stats, backup operations
    ├── theme/                      # Styling & typography
    │   ├── Color.kt                # Material 3 Expressive palette (violet seed #6B3FD4)
    │   ├── Shape.kt                # Rounded corner shapes
    │   ├── Theme.kt                # MaterialExpressiveTheme wrapper
    │   └── Type.kt                 # Bricolage Grotesque & DM Sans typography definitions
    └── viewer/                     # Fullscreen screenshot inspector
        ├── ViewerScreen.kt         # Gesture pan/zoom, collection assign sheet, OCR sheet, delete
        └── ViewerViewModel.kt      # Detail loading and collection toggle logic
```

---

## 2. Core Data Flow & Key Subsystems

### Hybrid Search Execution
1. **User Input:** `LibraryViewModel` debounces query text by 250 ms.
2. **Lexical Retrieval:** `RankFusion.sanitizeFtsQuery()` sanitizes tokens for SQLite FTS5 prefix match; `ScreenshotDao.searchOcrIds()` returns exact token hits.
3. **Semantic Retrieval:** `EmbeddingEngine.embedText()` prepends `task: search result | query: `, generates a 128-d vector; `VectorMath.topKBySimilarity()` performs brute-force cosine scans across SQLite vector BLOBs.
4. **Rank Blending:** `RankFusion.reciprocalRankFusion()` blends lexical and dense lists with $k=60$.
5. **Presentation:** `ScreenshotRepository.getScreenshotsByIds()` maps fused IDs back to UI models preserving exact rank order.

### Collection Matching & Relevance Feedback
1. **Auto-Fill:** `ScreenshotRepository.refreshAutoCollections()` evaluates candidate embeddings against collection descriptions.
2. **Prior Normalization:** `CollectionMatcher.match()` calculates z-scores relative to 20 hidden reference concepts. Scores meeting the user threshold (Strict 2.0 / Balanced 1.5 / Loose 1.0) become `AUTO` members.
3. **Review Session:** Swiping right marks items `MANUAL` (kept); swiping left marks items `EXCLUDED` (removed).
4. **Refinement:** `CollectionMatcher.refineQuery()` adapts the collection embedding using Rocchio feedback ($\alpha=0.7$, $\beta=0.4$), persisting improved representations for future auto-fills.

### Safe Cleanup Lifecycle
1. **Queue Generation:** `ReviewViewModel.startCleanup()` aggregates candidates via `CleanupRules.kt`:
   * Codes & Expiring: OCR regex matching OTP numbers or expiration phrasing (items older than 7 days prioritized).
   * Near-Duplicates: Temporal runs within $\le 10$ minutes with cosine similarity $\ge 0.92$, keeping only the newest item.
2. **Review Triage:** Users swipe through candidates. Swipe left marks for deletion; swipe right keeps.
3. **Summary Audit:** Once the stack is cleared, the Summary card outlines total files and reclaimable storage.
4. **System Deletion:** A single user confirmation invokes `MediaStore.createDeleteRequest()` to trigger the official system-managed deletion dialog.

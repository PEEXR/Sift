# Active Task Specification — Phase 2: EmbeddingGemma 2 (ONNX) Integration

Source RFC: `docs/RFC_LOCAL_AI_STACK.md` (Phase 2, adapted: **ONNX Runtime instead of LiteRT**, **model downloaded on demand**, **audio encoder excluded**). Do NOT touch Phase 3/4 (no Laya, no Jev changes, no CleanupScreen/UI changes). Do NOT commit; the orchestrator reviews and commits.

**STATUS: Phase 2 and UI stages 1-2 are committed. Ignore the BACKGROUND REFERENCE sections at the bottom of this file.**

**EXECUTION RULE (hard): Do NOT run Gradle, adb, tests, or ANY long-running/background command. Only read files and edit/create files. The orchestrator builds and tests. Finish your turn promptly with a summary.**

## THIS DISPATCH = Stage 13: update `docs/RFC_LOCAL_AI_STACK.md` to match what was actually built

The RFC was written before implementation and now disagrees with the code in several places. Rewrite it as an accurate **design record** (keep the document's existing tone and structure where still valid; remove or mark superseded anything that no longer applies). This is a documentation-only task: edit ONLY `docs/RFC_LOCAL_AI_STACK.md` (you may also add one new file `docs/ARCHITECTURE_SIFT_ANDROID.md` if you think a short code map helps). Do NOT touch any source file. Verify every factual claim against the code before writing it (read the files listed below); do not invent behaviour.

### Decisions to reflect (all already implemented; verify in code)
- **Stack**: ML Kit OCR + Room FTS5 (lexical) and **EmbeddingGemma 2** multimodal embeddings (semantic), fused with RRF. **No Laya, no learned classifier, no tiny model.** The Jev/TypeSafe API client still exists in `data/api` and the repository but is NOT used by any UI (kept intentionally for possible future use) — say so.
- **Runtime**: ONNX Runtime Android (1.30.0), not LiteRT. Model files: `model_q4.onnx(+_data)`, `vision_encoder_q4.onnx(+_data)`, `tokenizer.json` (~316 MB) downloaded on demand from Hugging Face (`ModelStore`, `ModelDownloader`: chunked 16 MB range requests, resume via `.part` files, retries, SHA-256 verification). Audio encoder excluded. Output: 128-d Matryoshka truncation, L2-normalised; image processing per Gemma4 processor (16 px patches, sides multiples of 48, budget `maxSoftTokens` ∈ {70,140,280,560,1120}, max patches = budget × 9); query prefix `task: search result | query: `.
- **Indexing quality**: `IndexingQuality` AUTO/FAST/BALANCED/BEST (70/140/280); AUTO calibrates seconds/image on the device (3 images at 140 → normalised to 280) and picks the largest budget that fits an 8 h target; EMA measurement stored per device; the worker publishes the budget it uses for the ETA. Measured: Snapdragon 7+ Gen 3 ≈ 11 s/img at 280; MediaTek MT6886 ≈ 19-20 s/img at 280, ≈ 5.2 s/img at 70, ≈ 10.6 s/img at 140. Indexing is CPU-bound (~4 cores).
- **Indexing triggers** (`EmbeddingWorker`, `EmbeddingScheduler`, `AutoIndexPolicy`, `AutoIndexWorker`): foreground-service WorkManager worker (notification channel `sift_indexing`), manual "Index now", automatic on app open / MediaStore change (ContentObserver, 3 s debounce) / periodic 12 h catch-up while charging; OEM freezers (vivo) pause background work, so the app is designed to index while open (keep-screen-on while running).
- **Hybrid search**: FTS5 via the bundled requery sqlite-android (system SQLite may lack FTS5) + dense cosine over 128-d BLOBs fused with RRF.
- **Collections = categories** (one concept, no defaults; user-created with a natural-language description). Auto-fill: z-score of image-vs-collection similarity against 20 hidden background descriptions, threshold from the user's sensitivity (Strict 2.0 / Balanced 1.5 / Loose 1.0). Memberships: AUTO / MANUAL (kept) / EXCLUDED (removed, remembered). A screenshot can be in many collections. **Relevance feedback**: after a collection review session the query vector is refined Rocchio-style from kept/removed examples (`refineQuery`, α 0.7, β 0.4, up to 10 examples each).
- **Cleanup (replaces the RFC's "Safety-First Cleanup" ascending-confidence design)**: swipe review with two modes — collection mode (keep/remove from collection, separate "Delete file") and search/cleanup mode (swipe left marks for deletion, one final system `createDeleteRequest`, nothing is deleted without the summary card). Cleanup rules: age filter chips (30/90/180/365 days), "Codes & expiring" preset (regex over OCR: OTP keywords + 4-8 digit code, "expires in…" phrases; stale after 7 days), "Near-duplicates" preset (consecutive shots ≤ 10 min apart with cosine ≥ 0.92; the newest of each burst is kept). Cards show the whole screenshot over a blurred backdrop plus an OCR snippet. No LLM scoring and no "Purge all remaining" ascending-confidence ramp (note this as a deliberate departure).
- **UI**: Material 3 Expressive (material3 1.5.0-alpha18, pinned; the beta needs AGP 9.1/compileSdk 37 and brings little), violet seed, Bricolage Grotesque + DM Sans, floating pill nav (Library · Collections · Review), in-app viewer (zoom, collections sheet, OCR text sheet, delete), onboarding, Settings (model, indexing, quality, sensitivity, export/import of collections as JSON matched by name+date+size, licenses). Edge-to-edge with a scrim behind 3-button nav; display refresh-rate request for 120 Hz panels.
- **Release**: signed R8-shrunk arm64-only APK (~38 MB), key outside the repo, backup disabled, cleartext off; `benchmark` build type for profiling.
- **Privacy**: everything on device; the only network use is the one-time model download.
- **Known limitations / future work** (be honest): auto-fill precision needs review (near misses are expected; that is what review is for); Android 14+ "limited photo access" is not handled gracefully (app needs full access); R8 shrinking verified on a fresh install on one device only; AGP 9 / material3 beta migration deferred; no instrumentation of real-world accuracy; model download depends on Hugging Face availability.

### Files to read to verify
`data/embedding/*`, `data/collections/CollectionMatcher.kt`, `data/cleanup/CleanupRules.kt`, `data/search/*`, `data/repository/ScreenshotRepository.kt`, `data/local/*`, `ui/review/*`, `ui/viewer/*`, `ui/settings/*`, `ui/onboarding/*`, `app/build.gradle.kts`, `docs/design/SIFT_UI_SPEC.md`.

### Output
- Update the roadmap/phase sections to a "what was built / status" form (Phases 1-4 + later stages: UI redesign, cleanup rules, auto-index, onboarding, settings, quality calibration, release). Keep the document readable (< ~400 lines), no marketing language, use tables for measurements and for the decisions-vs-original-plan comparison.
- Do NOT run any command. Do not commit. Finish promptly with a summary of what changed.

---


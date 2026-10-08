# Active Task Specification — Phase 2: EmbeddingGemma 2 (ONNX) Integration

Source RFC: `docs/RFC_LOCAL_AI_STACK.md` (Phase 2, adapted: **ONNX Runtime instead of LiteRT**, **model downloaded on demand**, **audio encoder excluded**). Do NOT touch Phase 3/4 (no Laya, no Jev changes, no CleanupScreen/UI changes). Do NOT commit; the orchestrator reviews and commits.

**STATUS: Phase 2 and UI stages 1-2 are committed. Ignore the BACKGROUND REFERENCE sections at the bottom of this file.**

**EXECUTION RULE (hard): Do NOT run Gradle, adb, tests, or ANY long-running/background command. Only read files and edit/create files. The orchestrator builds and tests. Finish your turn promptly with a summary.**

## THIS DISPATCH = Stage 9: auto-fill learns from the user's keep/remove swipes

Goal: a collection's matching improves as the user reviews. Kept (MANUAL) screenshots pull the collection's query vector toward what the user considers a match; removed (EXCLUDED) screenshots push it away (Rocchio-style relevance feedback). No training, no new model, deterministic and cheap (128-d vectors).

Read FIRST: `data/collections/CollectionMatcher.kt` (`match`, `zScore`, `BACKGROUND_DESCRIPTIONS`), `data/repository/ScreenshotRepository.kt` (`refreshAutoCollections`, `getOrEmbedText`, `keepInCollection`, `removeFromCollection`), `data/local/ScreenshotDao.kt` (`getAllEmbeddings`, `replaceAutoMembers`), `data/local/Entities.kt` (`EmbeddingRow`, `MembershipSource`), `data/search/VectorMath.kt` (dot/normalise helpers; embeddings are L2-normalised 128-d floats), `ui/review/ReviewViewModel.kt` (collection-mode session end), existing tests `app/src/test/java/com/peexr/sift/data/collections/CollectionMatcherTest.kt`. Do NOT change build files/dependencies, DB version/schema, any composable, or Jev code.

### 1. Pure logic (CollectionMatcher.kt, JVM-tested)
`fun refineQuery(base: FloatArray, positives: List<FloatArray>, negatives: List<FloatArray>, alpha: Float = 0.7f, beta: Float = 0.4f, maxFeedback: Int = 10): FloatArray`:
- If both lists are empty return `base` unchanged.
- `wP = min(positives.size, maxFeedback) / maxFeedback.toFloat()`, `wN` likewise for negatives (so a handful of swipes nudges, ten or more fully applies).
- `meanP` / `meanN` = component-wise means (skip a list if empty; ignore vectors whose size differs from `base.size`).
- `q = base + alpha * wP * meanP - beta * wN * meanN`, then L2-normalise. If the result has norm < 1e-6 or any non-finite component, return `base`.
Tests (append to `CollectionMatcherTest.kt` or a new `RefineQueryTest.kt`): no feedback -> same array; positives move the vector toward the positives (cosine(result, meanP) > cosine(base, meanP)); negatives move it away (cosine(result, meanN) < cosine(base, meanN)); weight scales with count (1 positive moves less than 10); output is unit length; degenerate cancelling input falls back to base; mismatched-size vectors ignored.

### 2. Data
- DAO: `@Query("SELECT s.id AS id, s.embedding AS embedding FROM screenshots s INNER JOIN screenshot_collections sc ON sc.screenshotId = s.id WHERE sc.collectionId = :collectionId AND sc.source = :source AND s.embedding IS NOT NULL") suspend fun getFeedbackEmbeddings(collectionId: String, source: String): List<EmbeddingRow>` (reuse `EmbeddingRow`).
- `ScreenshotRepository.refreshAutoCollections`: for each target collection, load kept (`MembershipSource.MANUAL`) and removed (`MembershipSource.EXCLUDED`) embeddings, decode with the existing BLOB->FloatArray helper (size must be 128), compute `refineQuery(textVector, kept, removed)` and use THAT vector in `CollectionMatcher.match`. Everything else (threshold from `zThresholdProvider`, background vectors, `replaceAutoMembers` semantics: AUTO rows replaced, MANUAL/EXCLUDED preserved) is unchanged. Do the DB reads on IO and the maths on Default.
- Keep MANUAL members even if they would no longer match (they are the user's explicit decision) — verify `replaceAutoMembers` already guarantees this and say so in your summary.

### 3. When to refresh
- `ReviewViewModel`: when a collection-mode session ENDS (the Done phase is reached, or the user leaves collection mode / presses back with at least one keep or remove performed this session), call `repository.refreshAutoCollections(onlyCollectionId = id)` once, launched in `SiftApplication.appScope` (not `viewModelScope`, so it survives leaving the screen; the ViewModel can take `app.appScope` or a `CoroutineScope` parameter with a sensible default — keep the existing constructor/Factory call sites compiling). Never refresh per swipe (it would reshuffle AUTO members while the user reviews). Do not refresh in search/cleanup modes.
- Expose the pure decision as a small top-level function in `ui/review/` e.g. `fun shouldRefreshAfterSession(keptCount: Int, removedCount: Int): Boolean` (= either > 0) and unit-test it.

### 4. Constraints
- Disk on IO, CPU on Default, never swallow `CancellationException`, no UI changes, no new dependencies, existing tests must keep compiling and passing (mock/stub the new DAO method where repository tests need it).
- Do NOT run Gradle or any command. Do not commit. Finish promptly with a summary listing every file touched and any deviation.

---


# Active Task Specification — Phase 2: EmbeddingGemma 2 (ONNX) Integration

Source RFC: `docs/RFC_LOCAL_AI_STACK.md` (Phase 2, adapted: **ONNX Runtime instead of LiteRT**, **model downloaded on demand**, **audio encoder excluded**). Do NOT touch Phase 3/4 (no Laya, no Jev changes, no CleanupScreen/UI changes). Do NOT commit; the orchestrator reviews and commits.

**STATUS: Phase 2 is committed. Ignore the BACKGROUND REFERENCE sections at the bottom of this file.**

**EXECUTION RULE (hard): Do NOT run Gradle, adb, tests, or ANY long-running/background command. Only read files and edit/create files. The orchestrator builds and tests. Finish your turn promptly with a summary.**

## THIS DISPATCH = Phase 3 / Stage 3b: auto-filling collections (collections ARE the categories)

Product rules (decided by the user):
- There is ONE concept: **collection**. Never introduce a "category"/"group" type, table, or the word category in code or docs. No default collections: the user creates every collection (name + description).
- A collection auto-fills from its description using EmbeddingGemma 2 embeddings. A screenshot may belong to MANY collections.
- The user reviews a collection by swiping keep/remove. "Remove from collection" must be REMEMBERED so auto-fill never re-adds it. Users can also add screenshots manually. Deleting the file from storage is a separate existing flow (`createDeletePendingIntent` / `removeDeletedScreenshots`): do not change it.
- Stack: ML Kit OCR + EmbeddingGemma 2 only. No Laya, no trained models. No UI work in this stage (keep existing UI compiling).

### 1. Schema (Room v2 -> v3): `data/local/Entities.kt`, `AppDatabase.kt`, `Migrations.kt`
- `CollectionEntity`: add `@ColumnInfo(defaultValue = "1") val autoFill: Boolean = true`.
- `ScreenshotCollectionCrossRef`: add `@ColumnInfo(defaultValue = "MANUAL") val source: String = MembershipSource.MANUAL` and `val score: Float? = null` (z-score for AUTO rows). Add `object MembershipSource { const val AUTO = "AUTO"; const val MANUAL = "MANUAL"; const val EXCLUDED = "EXCLUDED" }`.
- `AppDatabase` version = 3; add `MIGRATION_2_3` in `Migrations.kt` following that file's style (SQL constants + Migration object):
  - `ALTER TABLE collections ADD COLUMN autoFill INTEGER NOT NULL DEFAULT 1`
  - `ALTER TABLE screenshot_collections ADD COLUMN source TEXT NOT NULL DEFAULT 'MANUAL'`
  - `ALTER TABLE screenshot_collections ADD COLUMN score REAL`
  Existing membership rows become MANUAL. The `@ColumnInfo(defaultValue=...)` values MUST match the SQL defaults or Room's schema validation fails when the database opens. Register with `.addMigrations(MIGRATION_1_2, MIGRATION_2_3)`. Do not touch MIGRATION_1_2.

### 2. DAO: `data/local/ScreenshotDao.kt`
- Queries that list members of a collection, or collections of a screenshot, must ignore EXCLUDED rows: add `AND sc.source != 'EXCLUDED'` to `getCollectionsForScreenshot` and `getScreenshotsForCollectionFlow`.
- Add:
  - `@Query("DELETE FROM screenshot_collections WHERE collectionId = :collectionId AND source = 'AUTO'") suspend fun clearAutoMembers(collectionId: String)`
  - `@Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMemberships(rows: List<ScreenshotCollectionCrossRef>)` (IGNORE so MANUAL/EXCLUDED rows are never overwritten by AUTO)
  - `@Transaction suspend fun replaceAutoMembers(collectionId: String, rows: List<ScreenshotCollectionCrossRef>)` = clearAutoMembers + insertMemberships
  - `@Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertMembership(row: ScreenshotCollectionCrossRef)`
  - `@Query("SELECT * FROM collections WHERE autoFill = 1") suspend fun getAutoFillCollections(): List<CollectionEntity>`
  - `@Query("SELECT * FROM collections WHERE id = :id") suspend fun getCollectionById(id: String): CollectionEntity?`
  - `getReviewQueue(collectionId: String): List<CollectionMemberRow>`: members of one collection (not EXCLUDED), least-confident first:
    `SELECT s.*, sc.source AS membershipSource, sc.score AS membershipScore FROM screenshots s INNER JOIN screenshot_collections sc ON s.id = sc.screenshotId WHERE sc.collectionId = :collectionId AND sc.source != 'EXCLUDED' ORDER BY (sc.score IS NULL), sc.score ASC, s.dateAdded DESC`
    with `data class CollectionMemberRow(@Embedded val screenshot: ScreenshotEntity, val membershipSource: String, val membershipScore: Float?)` in Entities.kt.

### 3. Pure matching logic: NEW `app/src/main/java/com/peexr/sift/data/collections/CollectionMatcher.kt`
- `object CollectionMatcher` with `const val DEFAULT_Z_THRESHOLD = 1.5f` and an internal (never user-visible) list `BACKGROUND_DESCRIPTIONS` with exactly these 20 strings:
  "meme or joke image", "chat or messaging conversation", "social media post", "study notes or textbook page", "exam question paper", "document or form", "receipt or payment", "ticket or booking", "source code", "video game screen", "map", "shopping product page", "phone settings screen", "news article", "video app screen", "photo of a person", "music player", "calendar or schedule", "email inbox", "home screen with app icons".
  KDoc must say: these only normalise each image's similarity scale; they are NOT collections and are never shown to the user.
- `fun zScore(image: FloatArray, collection: FloatArray, background: List<FloatArray>): Float` = (dot(image, collection) − mean over b of dot(image, b)) / (population std over b of dot(image, b)); return 0f if std < 1e-6. Use `dot` from `data/search/VectorMath.kt`.
- `fun match(collection: FloatArray, screenshots: List<Pair<Long, FloatArray>>, background: List<FloatArray>, threshold: Float = DEFAULT_Z_THRESHOLD): List<Pair<Long, Float>>` returning (screenshotId, z) for every screenshot with z >= threshold, sorted by z descending. Precompute each image's background mean/std once per call. Pure Kotlin, no Android types.
- KDoc calibration note: measured on the user's real screenshots, z >= 1.5 includes true matches plus a few near misses (the swipe review handles those); z >= 2.0 is high precision.

### 4. Repository: `data/repository/ScreenshotRepository.kt`
- `suspend fun refreshAutoCollections(onlyCollectionId: String? = null)`: no-op if the model is not Ready/installed. Embed the background descriptions and each auto-fill collection's `description` (fall back to `name` if blank) with `embeddingEngine.embedText(...)`, caching vectors in an in-memory map keyed by the text and guarded by a `Mutex`. Load `dao.getAllEmbeddings()` once; skip vectors that are not 128 floats. For each collection (or only `onlyCollectionId`): `CollectionMatcher.match(...)`, then `dao.replaceAutoMembers(id, rows with source = AUTO and score = z)`. Math on `Dispatchers.Default`, DB on `Dispatchers.IO`. Rethrow `CancellationException`.
- `createCollection(...)`: keep the signature; after inserting, call `refreshAutoCollections(onlyCollectionId = id)` but never fail creation if that throws (log it; rethrow CancellationException).
- `suspend fun removeFromCollection(screenshotId: Long, collectionId: String)` → `upsertMembership(source = EXCLUDED, score = null)` (remembered). Update existing callers of the DAO's old `removeFromCollection` (grep) so the UI keeps compiling.
- `suspend fun keepInCollection(screenshotId: Long, collectionId: String)` → upsert MANUAL, score null (a confirmed keep is no longer auto).
- `suspend fun addToCollection(screenshotId: Long, collectionId: String)` → upsert MANUAL (also un-excludes).
- `suspend fun getReviewQueue(collectionId: String): List<CollectionMemberRow>` exposing the DAO query.
- Existing `classifyScreenshotWithJev` uses `assignToCollection`; leave it working (it inserts MANUAL by default).

### 5. Indexing worker
- At the end of `EmbeddingWorker.doWork()`, only if it embedded at least one screenshot, call `app.repository.refreshAutoCollections()` inside try/catch (rethrow CancellationException, log others).

### 6. Tests (JVM unit tests)
- NEW `app/src/test/java/com/peexr/sift/data/collections/CollectionMatcherTest.kt`: zScore math with hand-made normalized vectors; threshold filtering; sort order; std≈0 returns 0; empty inputs; the same screenshot matching two different collections (call match twice).
- Update `ScreenshotRepositoryTest` mocks if constructor/DAO signatures changed; keep all existing tests passing.

Do not touch the embedding engine, tokenizer, patchifier, docs, or add dependencies.

---
BACKGROUND REFERENCE (previous stages, do not act on it):

Work through stages 2c → 2e **in order**. After each stage run `.\gradlew.bat testDebugUnitTest` (from `sift_android/`) and fix failures before moving on. Keep all new code under `app/src/main/java/com/peexr/sift/data/embedding/` unless stated.

## Verified facts about the model (from a Python spike — trust these, do not guess)
Model repo: `onnx-community/embeddinggemma-2-ONNX` (Apache-2.0). We use the **q4** variants, 128-d truncation of the 768-d output. Files and SHA-256:

| File (under `https://huggingface.co/onnx-community/embeddinggemma-2-ONNX/resolve/main/`) | Bytes | SHA-256 |
|---|---|---|
| `onnx/model_q4.onnx` | 490742 | F9EEBA97ACDDF139B8EE2DDF04BC30DCEAFA88DE93FADF74D7644E0D61A477A9 |
| `onnx/model_q4.onnx_data` | 174028800 | C3975F2D1AB7A1878AE31A7D7A9B7804A827AFF3800B60DFCEAFCE21CAC3DF49 |
| `onnx/vision_encoder_q4.onnx` | 159400 | 7EA284226D4938F0AD921AB091F1D80A9CA699AA802984EF5CD5EEC4F4761D96 |
| `onnx/vision_encoder_q4.onnx_data` | 108957696 | 0A9D6C927334F152A33DD90874F65D6EA5228999ABE6A450D3F7813677FA704C |
| `tokenizer.json` | 32170510 | 4D777EF5BDC1AA36227ABDFB77C3E49E7B9C892D16E1B6BDA41C393504828BE4 |

Each `.onnx` and its `.onnx_data` MUST sit in the same directory with those exact names (external data). ~316 MB total. Never download audio files.

### Graph I/O
- **Text/fusion model `model_q4.onnx`** inputs: `input_ids` int64 [B,S]; `attention_mask` int64 [B,S]; `image_features` float32 [N,512]; `video_features` float32 [N,512]; `audio_features` float32 [N,512]. **All five inputs are required** — for unused modalities pass an empty tensor of shape [0,512]. Output we use: `sentence_embedding` float32 [B,768] (mean-pooled by the graph). Ignore `last_hidden_state`.
- **Vision model `vision_encoder_q4.onnx`** inputs: `pixel_values` float32 [1,P,768]; `pixel_position_ids` int64 [1,P,2]. Output `image_features` float32 [T,512] where T = P/9.
- Final embedding = `sentence_embedding[0]` → take first **128** floats → **L2-normalize** (mandatory after truncation) → this is what gets stored (use `FloatArray.toByteArray()` from `data/search/VectorMath.kt`).

### Text path
- Tokenizer = HF `tokenizer.json`: **BPE**, vocab 262144, 514906 merges, `byte_fallback=true`, `fuse_unk=true`, `ignore_merges=false`. Normalizer: replace `" "` with `"▁"` (U+2581). Pre-tokenizer: **Split on `" "` with behavior `MergedWithPrevious`** (applied after the normalizer's replacement, check tokenizer.json `pre_tokenizer` literally and mirror it). Post-processor: `<bos>`(id 2) + tokens + `<eos>`(id 1). Special/added tokens in `added_tokens` must be matched atomically (e.g. `<|image|>`).
- Query prefix (exact string, trailing space included): `"task: search result | query: "` + user query.
- Image/document side uses no text prefix (see image path).
- Golden token ids and expected 128-d embeddings for 5 queries are in `.agent/tasks/phase2_golden/golden.json` (keys `q4_text` → query → `{ids, emb128}`; ids already include bos/eos, built from prefix+query). Your tokenizer MUST reproduce these ids exactly; your engine MUST match `emb128` with cosine ≥ 0.999.

### Image path (Gemma4 image processor, 280-token budget, max_patches = 280*9 = 2520, patch 16, pool 3)
1. Decode bitmap to RGB (alpha dropped). Let H,W be its size.
2. Target size: `factor = sqrt(2520*256 / (H*W))`; `tH = floor(factor*H / 48) * 48`; `tW = floor(factor*W / 48) * 48` (side multiple = pool*patch = 48). If tH==0 xor tW==0 apply the edge-case rule: set the zero side to 48 and the other to `min(floor(long/short)*48, (2520/9)*48)`. If both 0, skip the image (return null).
3. Resize to (tH, tW) with **bicubic** (PIL BICUBIC semantics; on Android use `Bitmap.createScaledBitmap(..., filter=true)` on a software bitmap — exact bit-parity with PIL is NOT required, but dimensions must match).
4. Scale to [0,1] by `/255`. **No mean/std normalization.**
5. Patchify: grid of `gh=tH/16` by `gw=tW/16`, row-major (y outer, x inner). Each patch vector has 768 floats ordered **(row-in-patch, col-in-patch, channel)** i.e. index = `(py*16 + px)*3 + c` (matches numpy `transpose(1,3,2,4,0)`).
6. `pixel_position_ids[i] = (x, y)` with x = column index, y = row index of patch i (x first!).
7. **Do NOT pad** to 2520 — unpadded input was verified to give bit-identical features (P = gh*gw ≤ 2520, a multiple of 9).
8. Run vision model → `image_features` [T,512]; T must equal P/9.
9. Fusion input ids: `[2 (bos), 255999 (<|image>), 258880 × T (<|image|>), 258882 (<image|>), 1 (eos)]`, attention_mask all ones, `image_features` = step 8 output. **The placeholder MUST be expanded to T copies** — a single placeholder token breaks retrieval quality.
10. Embedding = same 128-d truncate + L2 normalize.
- Golden for a deterministic synthetic image is in `.agent/tasks/phase2_golden/golden_vision.json` (image: 2400×1080 gradient defined in the file; expect `real_patches=2376`, grid 33 wide × 72 tall, `soft_tokens=264`, last position `[32,71]`, first patch head values). Use it to unit-test the preprocessor (pure Kotlin math on a synthetic IntArray/FloatArray image — keep the resize/patchify core free of `android.graphics` so it runs in plain JUnit; the Bitmap→pixels step is a thin wrapper). Note: because the resize filter differs from PIL, test patchify/positions/dims exactly, and pixel values only for an image that needs no resize or via tolerance.

## Stage 2b — Model download manager
Files: `data/embedding/ModelStore.kt`, `data/embedding/ModelDownloader.kt` (+ tests).
- `ModelStore`: knows the 5-file manifest above (name, url, size, sha256), the target dir `context.filesDir/models/embeddinggemma2-q4/` (create `onnx/` subdir for the 4 onnx files), and exposes `isInstalled(): Boolean` (all files exist, sizes match) and `Flow`/StateFlow of `ModelState` = `NotInstalled | Downloading(bytesDone, bytesTotal, currentFile) | Ready | Failed(message)`.
- `ModelDownloader`: uses existing OkHttp. Runs on `Dispatchers.IO`. Download to `*.part`, **resume** via `Range` header if a `.part` exists, verify SHA-256 (streaming `MessageDigest`, do not load the file in memory), then atomically rename. Skip files already verified. Delete the file and fail on checksum mismatch. Must be cancellable; rethrow `CancellationException`, never swallow it. Allow download on any network but expose a `wifiOnly: Boolean` parameter (default true) honored by the caller.
- Unit-test the manifest (5 entries, no audio), hash verification helper, and the resume/Range logic using a fake `Call.Factory`/MockWebServer-free approach (okhttp3 `Interceptor` returning canned responses is fine). Do not require network in tests.

## Stage 2c — Tokenizer + image preprocessor
Files: `data/embedding/GemmaTokenizer.kt`, `data/embedding/ImagePatchifier.kt` (+ tests).
- `GemmaTokenizer`: loads `tokenizer.json` (32 MB) **streaming** (use `kotlinx.serialization.json` streaming or `android.util.JsonReader`-style; do NOT build a full `JsonElement` tree — memory) and builds vocab map + merge-rank map (`Map<Pair or packed Long, Int>`). Implement BPE with byte fallback (`<0xNN>` tokens), `<unk>` fuse, added-token atomic matching, bos/eos. API: `fun encode(text: String, addSpecial: Boolean = true): IntArray`. Loading is expensive: expose it as a lazily-created singleton held by the engine, loaded on `Dispatchers.Default`/IO.
- Add `src/test/resources/golden/` copies of `golden.json` and `golden_vision.json`. For the tokenizer test, if `tokenizer.json` is not available in unit-test resources (it is 32 MB — do NOT commit it), make the test `Assume`-skip when the file is missing, locating it via the env var `SIFT_TOKENIZER_JSON` (the orchestrator will set it to `<scratchpad>\eg2\tokenizer.json`). Also write a tiny synthetic tokenizer.json fixture test covering normalizer, merges, and byte fallback so there is always a runnable test.
- `ImagePatchifier`: pure functions for steps 2, 4–7 above plus the Bitmap wrapper. Output data class `PatchBatch(pixelValues: FloatArray /*P*768*/, positions: LongArray /*P*2*/, numPatches: Int, softTokens: Int)`.

## Stage 2d — EmbeddingEngine (ONNX Runtime)
Files: `data/embedding/EmbeddingEngine.kt`, modify `app/build.gradle.kts`.
- Add dependency `com.microsoft.onnxruntime:onnxruntime-android:1.22.0` (or newest 1.2x on Maven Central that resolves). If packaging complains about duplicate `libc++_shared.so` or similar, add the needed `packaging { jniLibs { pickFirsts += ... } }`.
- `class EmbeddingEngine(context, modelStore, tokenizerProvider)` with `suspend fun embedText(query: String): FloatArray` (applies the query prefix) and `suspend fun embedImage(uri: Uri | Bitmap): FloatArray?`; both return a 128-d L2-normalized FloatArray. All inference on `Dispatchers.Default` (CPU-bound) and file/model loading on `Dispatchers.IO`. Create `OrtEnvironment`/`OrtSession`s lazily once, keep them, and expose `close()`. Use `SessionOptions` with `setIntraOpNumThreads` = min(4, cores). Try NNAPI/XNNPACK execution provider only behind a try/catch falling back to CPU. Close `OnnxTensor`/`Result` objects in `use {}` blocks. Throw a clear `IllegalStateException` if the model is not `Ready`.
- Serialize inference with a `Mutex` (one session, not thread-safe for concurrent heavy use).
- Instrumented-only code paths cannot run in JUnit; keep the pure helpers (truncate+normalize, id-sequence building for the image path) separately testable and unit-test them. Add an `androidTest` (not run by the orchestrator) that loads the real model and checks the 5 golden query embeddings (cosine ≥ 0.999) — optional but preferred.
- Wire an `embeddingEngine` and `modelStore` into `SiftApplication.kt` as lazy properties (match existing style). Do not trigger any download automatically.

## Stage 2e — Background indexer + search integration
Files: `data/embedding/EmbeddingWorker.kt`, modify `data/repository/ScreenshotRepository.kt`, `SiftApplication.kt`, `app/build.gradle.kts` (add `androidx.work:work-runtime-ktx:2.9.1`), `data/local/ScreenshotDao.kt` (only if needed).
- `EmbeddingWorker : CoroutineWorker` embedding all screenshots with `embedding IS NULL` (add a DAO query for ids+uris needing embeddings, using the existing columns — check `Entities.kt` for the URI/path field), in batches, writing via the existing `updateEmbedding(id, bytes)`. Skips cleanly (`Result.success()`, no work) if the model is not `Ready`. Checks `isStopped`/coroutine cancellation between items; one bad image must not fail the whole run (log and continue). Updates progress via `setProgress`.
- Expose `EmbeddingScheduler` helper: `enqueueIndexing(context)` using unique work `ExistingWorkPolicy.KEEP` with constraints `setRequiresCharging(true)` and `setRequiresDeviceIdle(true)` (per RFC), plus `enqueueIndexingNow(context)` with no idle/charging constraints for a user-triggered run.
- In `ScreenshotRepository` add `suspend fun hybridSearch(query: String, limit: Int = 50): List<ScreenshotEntity>` on `Dispatchers.IO`: (1) FTS ids via `searchOcrIds` — sanitize the query for FTS5 MATCH (quote each token, e.g. `"foo" "bar"*`; empty/blank query → empty list; catch `SQLiteException` → treat as no lexical hits), (2) if the model is `Ready`, `embedText` + `getAllEmbeddings()` + `topKBySimilarity` from `VectorMath.kt` (dense hits), else dense list is empty, (3) fuse with the existing RRF in `RankFusion.kt` (read its API first), (4) map ids back to entities preserving fused order. Do not add any UI; just the repository method (+ unit test of the FTS query sanitizer as a pure function).

## Constraints
- Kotlin, coroutines: network & disk I/O on `Dispatchers.IO`, CPU inference on `Dispatchers.Default`. Never swallow `CancellationException`.
- UDF / no UI changes in this task. No hardcoded secrets. Keep existing code style, KDoc on public classes, comments only where non-obvious.
- Do not modify `docs/`, `.agent/tasks/phase2_golden/`, `scripts/`. Do not touch unrelated files. Do not commit.
- Never put the 316 MB model files or `tokenizer.json` in the repo or APK assets.

## Acceptance Criteria
- [ ] `.\gradlew.bat testDebugUnitTest` passes (new tests included; the tokenizer golden test may Assume-skip without the env var).
- [ ] `.\gradlew.bat assembleDebug` succeeds with onnxruntime + WorkManager added.
- [ ] Manifest contains exactly the 5 files above with the given sizes/SHA-256; no audio.
- [ ] Downloader resumes via Range, verifies SHA-256 streaming, renames atomically, is cancellable.
- [ ] Tokenizer reproduces golden ids; image patchifier reproduces golden dims/positions (33×72 grid, 2376 patches, 264 soft tokens for 2400×1080).
- [ ] Image id-sequence is `[2, 255999, 258880×T, 258882, 1]`; all 5 graph inputs always supplied; output truncated to 128 and re-normalized.
- [ ] Worker uses charging+idle constraints; `hybridSearch` fuses FTS + dense via RRF and degrades gracefully when the model is absent.
- [ ] No Phase 3/4 changes; no UI changes; nothing committed.
- [ ] End with a summary listing files created/modified and anything you could not verify.

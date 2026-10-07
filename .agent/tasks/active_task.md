# Active Task Specification — Phase 2: EmbeddingGemma 2 (ONNX) Integration

Source RFC: `docs/RFC_LOCAL_AI_STACK.md` (Phase 2, adapted: **ONNX Runtime instead of LiteRT**, **model downloaded on demand**, **audio encoder excluded**). Do NOT touch Phase 3/4 (no Laya, no Jev changes, no CleanupScreen/UI changes). Do NOT commit; the orchestrator reviews and commits.

**STATUS: Phase 2 is committed. Ignore the BACKGROUND REFERENCE sections at the bottom of this file.**

**EXECUTION RULE (hard): Do NOT run Gradle, adb, tests, or ANY long-running/background command. Only read files and edit/create files. The orchestrator builds and tests. Finish your turn promptly with a summary.**

## THIS DISPATCH = UI Stage 1: Expressive theme foundation + floating pill navigation

Read FIRST: `docs/design/SIFT_UI_SPEC.md` (source of truth) and the mock markup in `docs/design/mock/` (`Main.dc.html` shows the nav pill, header, search pill and colors). Library versions are already upgraded (material3 1.5.0-alpha18, Compose BOM 2026.06.01): do NOT change any dependency or build file.

Scope of THIS stage: theme + navigation shell only. Screens get rebuilt in later stages; here they must just compile, use the new theme, and lose obsolete bits.

### 1. Theme — `app/src/main/java/com/peexr/sift/ui/theme/`
- `Color.kt`: replace the palette with the spec's tokens (light + a matching dark scheme as described in the spec). Name them by role (e.g. `SiftViolet`, `SiftInk`, `SiftDisplayInk`, `KeepLime`, `OnKeepLime`, `RemoveCoral`, `OnRemoveCoral`, `InverseSurface`...). Also add the six collection tonal pairs as a `val CollectionPalette: List<Pair<Color, Color>>` (container, ink) in the spec's order.
- Extra tokens the M3 scheme has no slot for (display ink, keep/remove accents, collection palette) go in an `@Immutable data class SiftExtendedColors` provided via `staticCompositionLocalOf` + accessor `SiftTheme.extended` (an `object SiftTheme { val extended: SiftExtendedColors @Composable get() = ... }`).
- `Type.kt`: `val BricolageGrotesque = FontFamily(Font(R.font.bricolage_grotesque, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800))), Font(R.font.bricolage_grotesque, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))))` and `val DmSans = FontFamily(` 400/500/700 the same way `)` (use `@OptIn(ExperimentalTextApi::class)` if the compiler requires it). Typography: display*/headline* = BricolageGrotesque ExtraBold with negative letterSpacing (displayLarge 64.sp/line 61.sp/−2.5.sp, displayMedium 56.sp, displaySmall 44.sp, headlineLarge 34.sp/−1.sp, headlineMedium 28.sp/−0.8.sp, headlineSmall 22.sp); title*/body*/label* = DmSans (titleLarge 20.sp Bold, titleMedium 16.sp Bold, bodyLarge 16.sp, bodyMedium 15.sp, bodySmall 13.sp, labelLarge 15.sp Bold, labelMedium 14.sp Bold, labelSmall 12.sp Medium). No `FontFamily.Default` anywhere.
- `Shape.kt`: extraSmall 8.dp, small 14.dp, medium 18.dp, large 28.dp, extraLarge 36.dp (RoundedCornerShape).
- `Theme.kt`: `SiftTheme(darkTheme, content)` uses `MaterialExpressiveTheme(colorScheme, motionScheme = MotionScheme.expressive(), shapes, typography)`. Dynamic color OFF (remove the dynamicColor path). Edge-to-edge friendly (no hard-coded status bar color writes; keep what MainActivity needs to compile).

### 2. Navigation — `ui/navigation/SiftDestination.kt`, `SiftNavHost.kt`
- Destinations: `Library` (route "library", existing `CollectionsScreen` content for now), `Collections` (route "collections", NEW placeholder screen `ui/collections/CollectionsListScreen.kt` showing the 58sp "Collections" title + "Coming next" body text, themed), `Review` (route "review", existing `CleanupScreen` for now). Start destination = Library. Icons: rounded Material icons (GridView / CollectionsBookmark or FolderSpecial / Style or SwipeLeft — pick close equivalents from material-icons-extended).
- Replace the full-width `NavigationBar` with a custom **floating pill nav** composable `ui/navigation/FloatingPillNav.kt`: Row in a `Surface(shape = CircleShape, color = surfaceContainerHighest, shadowElevation = 6.dp)`, 6.dp padding, 4.dp spacing, centered, 22.dp above the bottom + navigation bar insets (`WindowInsets.navigationBars`). Selected item: primary pill (52.dp tall, 20.dp horizontal padding) with icon + label (labelLarge, onPrimary); unselected: 52.dp circle icon button (onSurfaceVariant) with contentDescription. Animate width/colors with `animateContentSize()` + `animateColorAsState` using `MaterialTheme.motionScheme` specs where available. The pill overlays content (screens must add bottom padding ≈ 100.dp so content isn't hidden — expose `val FloatingNavClearance = 100.dp`).
- Remove the old `Scaffold` bottomBar usage accordingly (keep a Scaffold if useful for snackbars).

### 3. Remove obsolete UI (the user dropped Jev/Laya)
- In `CollectionsScreen.kt`: delete the "Jev Auto-Tag" extended FAB and any UI text mentioning Jev; the create-collection dialog text becomes: title "New collection", fields "Name" and "Description" with supporting text "Screenshots matching this description are added automatically." Keep the sync FAB for now.
- Do NOT delete Jev data/API classes or ViewModel methods in this stage (only the UI entry points). If a ViewModel method becomes unused, leave it.

### Constraints
- Use `MaterialTheme.colorScheme/typography/shapes` and `SiftTheme.extended` — no raw `Color(0x...)` in screen files (only in Color.kt). No hard-coded font families outside Type.kt.
- Keep all existing features compiling and working (search, sync, collections dialog, cleanup).
- Do NOT run Gradle or any command (orchestrator builds). Do not touch data/, build files, docs/, or tests unless a test breaks from a rename.

### Acceptance
- App compiles; launches on Library with the floating pill nav; switching tabs works; fonts are Bricolage Grotesque/DM Sans; palette is violet/lilac per spec; no Jev button.

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

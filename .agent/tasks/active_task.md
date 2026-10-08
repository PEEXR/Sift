# Active Task Specification — Phase 2: EmbeddingGemma 2 (ONNX) Integration

Source RFC: `docs/RFC_LOCAL_AI_STACK.md` (Phase 2, adapted: **ONNX Runtime instead of LiteRT**, **model downloaded on demand**, **audio encoder excluded**). Do NOT touch Phase 3/4 (no Laya, no Jev changes, no CleanupScreen/UI changes). Do NOT commit; the orchestrator reviews and commits.

**STATUS: Phase 2 and UI stages 1-2 are committed. Ignore the BACKGROUND REFERENCE sections at the bottom of this file.**

**EXECUTION RULE (hard): Do NOT run Gradle, adb, tests, or ANY long-running/background command. Only read files and edit/create files. The orchestrator builds and tests. Finish your turn promptly with a summary.**

## THIS DISPATCH = Stage 12: "Indexing quality" (image token budget) with automatic speed calibration

Problem (measured on the user's second phone, a MediaTek MT6886): indexing takes ~20 s per screenshot at the current fixed image budget (280 soft tokens = up to 2,520 patches), i.e. ~13 h for 2,354 screenshots, while the user's Snapdragon phone takes ~11 s. Indexing is CPU-bound (ONNX vision encoder) and compute scales at least linearly with the budget (`maxPatches = maxSoftTokens * 9`; supported budgets 70/140/280/560/1120). Let the user choose, and let the app pick a sensible default from a measured speed. The library may contain embeddings produced with different budgets: that is acceptable (same model/space; search quality differs slightly).

Read FIRST: `data/embedding/EmbeddingEngine.kt` (`maxSoftTokens`, `embedImage(bitmap)` ~line 309, `embedImage(uri)` ~line 426, the `ImagePatchifier.processBitmap(bitmap, maxSoftTokens)` call), `data/embedding/ImagePatchifier.kt` (`SUPPORTED_BUDGETS`), `data/embedding/EmbeddingWorker.kt` (per-item loop around lines 120-220, `embeddedCount`), `data/local/SettingsManager.kt`, `ui/settings/SettingsScreen.kt` + `SettingsViewModel.kt` (the "Indexing" section), `ui/library/LibraryViewModel.kt` (`computeIndexingUi`, `formatEta`) and `ui/library/LibraryScreen.kt` (lines ~403 and ~416 use `indexing.remaining * 11L`), `SiftApplication.kt`. Do NOT change build files/dependencies, DB schema, or Jev code.

### 1. Pure logic (new file `data/embedding/IndexingQuality.kt`, no Android deps; JVM-tested)
- `enum class IndexingQuality(val budget: Int?, val label: String, val hint: String) { AUTO(null, "Auto", "Picks a speed that finishes overnight"), FAST(70, "Fast", "Quickest, slightly less accurate on small text"), BALANCED(140, "Balanced", "Good accuracy, about half the time of Best"), BEST(280, "Best", "Most accurate, slowest") }`.
- Cost model: seconds per image scales linearly with budget. `fun secondsPerImageAt(budget: Int, secPerImageAt280: Double): Double = secPerImageAt280 * budget / 280.0`.
- `fun chooseAutoBudget(secPerImageAt280: Double?, pendingCount: Int, targetSeconds: Double = 6 * 3600.0): Int`: returns 140 when `secPerImageAt280` is null (no measurement yet — calibration will run); otherwise the LARGEST of 280, 140, 70 whose estimated total (`secondsPerImageAt(budget, s) * pendingCount`) is <= `targetSeconds`; if none fits return 70.
- `fun resolveBudget(quality: IndexingQuality, secPerImageAt280: Double?, pendingCount: Int): Int` = `quality.budget ?: chooseAutoBudget(...)`.
- `fun updateMeasurement(previous: Double?, observedSeconds: Double, budgetUsed: Int): Double`: normalise the observation to the 280 budget (`observedSeconds * 280.0 / budgetUsed`) and blend with an exponential moving average (`alpha = 0.3`; first observation is taken as is); ignore non-finite or <= 0 observations (return previous or the normalised observation if previous is null — never return NaN).
- `fun estimateTotalSeconds(budget: Int, secPerImageAt280: Double?, pendingCount: Int, fallbackSecAt280: Double = 11.0): Long` (rounded; uses the fallback when there is no measurement).
- Tests `app/src/test/java/com/peexr/sift/data/embedding/IndexingQualityTest.kt`: `chooseAutoBudget` (null -> 140; 3 s/img x 1000 -> 280; 11 s x 2354 (7.2 h at 280) -> 140 (3.6 h <= 6 h); 20 s x 2354 -> 70; tiny pending always 280; boundary exactly at target), `updateMeasurement` (first value, EMA blend, normalisation e.g. 70-budget observation of 5 s -> 20 s at 280, bad inputs), `resolveBudget` for fixed choices ignoring measurements, `estimateTotalSeconds`.

### 2. Settings storage (`SettingsManager`)
- `var indexingQuality: IndexingQuality` (default AUTO; persist the enum NAME, fall back to AUTO on unknown/missing).
- `var measuredSecPerImageAt280: Double?` (persist as a Float/String; null when never measured; provide `clearMeasurement()`; it is per device, so nothing calls it automatically).

### 3. Engine + worker
- `EmbeddingEngine`: add an optional `maxSoftTokens: Int = this.maxSoftTokens` parameter to BOTH `embedImage(bitmap, ...)` and `embedImage(uri, ...)` and use it for the `ImagePatchifier.processBitmap(...)` call (keep the constructor property as the default; validate against `SUPPORTED_BUDGETS` with `require`). No engine rebuild is needed to change budgets.
- `EmbeddingWorker`: at the start of each run read `settings.indexingQuality`, `measuredSecPerImageAt280` and the pending count and compute the budget with `resolveBudget`. If AUTO and no measurement exists, run a CALIBRATION: the first 3 images that need embedding are embedded at budget 140 and timed (wall-clock of the `embedImage` call only, `SystemClock.elapsedRealtime()`; skip the very first call as warm-up if you have at least 3 samples — use the median of the remaining/all timings), store the result via `updateMeasurement(...)`, then `resolveBudget` again for the rest of the run. For every image embedded (any mode), call `updateMeasurement` with its timing and persist the updated value (write it back to SharedPreferences at most once every 10 images and at the end of the run, not on every image). Log the chosen budget and sec/image once per run (`Log.i`).
- The per-image embedding time must exclude OCR so the measurement reflects the vision encoder.

### 4. UI
- `SettingsScreen` Indexing section: replace nothing existing; add a card "Indexing quality" with four selectable rows (radio semantics, `selectable(role = Role.RadioButton)`): Auto / Fast / Balanced / Best, each with its `hint`, and for the selected option an estimate line when a measurement exists: "About X h Y min for N waiting screenshots at this setting" (use `estimateTotalSeconds` + the library's `formatEta`; for AUTO use `chooseAutoBudget`'s result; when no measurement exists say "Sift will time the first few screenshots to choose a speed"). Changing it persists immediately; it applies from the next indexing run (say so in one line of helper text: "Applies to screenshots indexed from now on."). "Re-index everything" keeps the measurement.
- `LibraryViewModel`/`LibraryScreen`: stop using the hard-coded `* 11L`. Add a `secondsPerItem: Long` (or the data needed) to `IndexingUi.Pending/Running` computed as `secondsPerImageAt(resolveBudget(...), measured ?: 11.0)` plus ~1 s for OCR/overhead; ETA = `remaining * secondsPerItem`. Keep `formatEta` unchanged. `computeIndexingUi` must stay pure/unit-testable (pass the extra number in as a parameter with a default of 11) and existing tests must keep passing.

### 5. Constraints
- Theme tokens only, stateless composables, immutable StateFlow, IO/Default dispatchers as appropriate, never swallow `CancellationException`, no new dependencies, no behaviour change for users who never open the setting except the automatic budget choice. Existing tests (JVM and androidTest compile) must keep compiling; update `LibraryLogicTest`/`SettingsScreenTest` only if their signatures change.
- Do NOT run Gradle or any command. Do not commit. Finish promptly with a summary listing every file touched and any deviation.

---


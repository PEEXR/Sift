# Active Task Specification — Phase 2: EmbeddingGemma 2 (ONNX) Integration

Source RFC: `docs/RFC_LOCAL_AI_STACK.md` (Phase 2, adapted: **ONNX Runtime instead of LiteRT**, **model downloaded on demand**, **audio encoder excluded**). Do NOT touch Phase 3/4 (no Laya, no Jev changes, no CleanupScreen/UI changes). Do NOT commit; the orchestrator reviews and commits.

**STATUS: Phase 2 and UI stages 1-2 are committed. Ignore the BACKGROUND REFERENCE sections at the bottom of this file.**

**EXECUTION RULE (hard): Do NOT run Gradle, adb, tests, or ANY long-running/background command. Only read files and edit/create files. The orchestrator builds and tests. Finish your turn promptly with a summary.**

## THIS DISPATCH = Stage 6: index new screenshots automatically

Problem: today new screenshots are only embedded when the user opens Sift and taps "Index now". The user takes new screenshots daily. Goal: new screenshots are picked up and indexed with no manual step, without wasting battery, and without ever starting a second worker while one is running (the OEM freezer on the user's phone pauses background work, so best-effort catch-up must be cheap and idempotent).

Read FIRST: `data/embedding/EmbeddingWorker.kt` (worker + `EmbeddingScheduler`: `enqueueIndexing` = KEEP + charging/idle, `enqueueIndexingNow` = REPLACE, no constraints), `SiftApplication.kt`, `MainActivity.kt`, `ui/library/LibraryViewModel.kt` (`syncScreenshots`, `computeIndexingUi`, WorkManager info flow), `data/local/SettingsManager.kt`, `data/repository/ScreenshotRepository.kt` (`syncMediaStoreScreenshots`, `countNeedingIndexingFlow`), `AndroidManifest.xml`. Do NOT change build files/dependencies, DB schema, the embedding maths, or any screen layout except where stated.

### 1. Setting
- `SettingsManager`: add `var autoIndexNewScreenshots: Boolean` (default true, key "auto_index_new"). (The settings UI comes in a later stage; do not add UI now.)

### 2. Pure decision logic (unit-tested)
- NEW `data/embedding/AutoIndexPolicy.kt`, no Android deps: `fun shouldAutoIndex(enabled: Boolean, modelInstalled: Boolean, pendingCount: Int, workRunning: Boolean, lastAutoStartMillis: Long, nowMillis: Long, minGapMillis: Long = 60_000): Boolean` — true only when enabled && modelInstalled && pendingCount > 0 && !workRunning && (now - lastAutoStart) >= minGap. Also `fun isWorkRunning(states: List<androidx.work.WorkInfo.State>): Boolean` (RUNNING or ENQUEUED-with-no-constraints is NOT needed: only RUNNING counts; keep it a pure list-of-enum function so it needs no WorkManager instance).
- Tests `app/src/test/java/com/peexr/sift/data/embedding/AutoIndexPolicyTest.kt` covering every false branch + the true case + the min-gap boundary.

### 3. Triggers
a. **On app start/foreground** (`MainActivity.onStart`/after the silent launch sync completes): call a new `LibraryViewModel.autoIndexIfNeeded()` which reads the current pending count (`repository.countNeedingIndexingFlow.first()`), model-installed state (`app.modelStore.isInstalled()`), WorkManager state of `UNIQUE_WORK_NAME`, applies `shouldAutoIndex`, and if true calls a NEW `EmbeddingScheduler.enqueueIndexingNow(app, replace = false)` variant using `ExistingWorkPolicy.KEEP` (never REPLACE an in-flight run). Keep the existing `enqueueIndexingNow(context)` behaviour for the manual "Index now" button unchanged. Record `lastAutoStartMillis` in the ViewModel (in-memory is fine).
b. **While the app is open**: register a `ContentObserver` on `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` (notifyForDescendants = true) from `LibraryViewModel` (or the Application when the activity is started) so a screenshot taken while Sift is open is detected. Debounce ~3 s (use a `MutableSharedFlow`/`callbackFlow` + `debounce`), then `syncScreenshots(announce = false)` followed by `autoIndexIfNeeded()`. Unregister in `onCleared`/when the collector is cancelled; no leaks; never register twice (guard with a flag).
c. **Background catch-up**: a periodic `PeriodicWorkRequest` (every 12 hours, flex 4 h) of a small new `AutoIndexWorker : CoroutineWorker` with constraints `setRequiresCharging(true)` + `setRequiresBatteryNotLow(true)`, enqueued uniquely (`ExistingPeriodicWorkPolicy.KEEP`, unique name "com.peexr.sift.auto_index_periodic") from `SiftApplication.onCreate` (only when `autoIndexNewScreenshots` is true; cancel it when false — expose `EmbeddingScheduler.syncAutoIndexSchedule(context, enabled)` and call it from onCreate; the future settings toggle will call it too). `AutoIndexWorker.doWork`: sync MediaStore via `SiftApplication.repository.syncMediaStoreScreenshots()` (it needs READ_MEDIA_IMAGES; if the permission is missing return `Result.success()` quietly), then if pending > 0 and model installed and the unique embedding work is not RUNNING, enqueue the embedding worker via the KEEP variant and return success. It must NOT embed anything itself. Wrap non-cancellation errors, log, return `Result.success()` (never retry storms); rethrow `CancellationException`.

### 4. No UI changes in this stage
The existing indexing card already shows progress for automatic runs. Do not touch any composable.

### 5. Permissions/manifest
- Nothing new is required (foreground service permissions exist). Verify `POST_NOTIFICATIONS` is already requested at runtime somewhere; if it is NOT requested at runtime on Android 13+, say so in your summary but do not add UI in this stage.

### Constraints
- Disk/network on IO, never swallow `CancellationException`, no new dependencies, no new screens. Comments only where non-obvious. Do not touch Jev code.
- Do NOT run Gradle or any command. Do not commit. Finish promptly with a summary listing every file touched and any deviation.

---


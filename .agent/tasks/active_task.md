# Active Task Specification — Phase 2: EmbeddingGemma 2 (ONNX) Integration

Source RFC: `docs/RFC_LOCAL_AI_STACK.md` (Phase 2, adapted: **ONNX Runtime instead of LiteRT**, **model downloaded on demand**, **audio encoder excluded**). Do NOT touch Phase 3/4 (no Laya, no Jev changes, no CleanupScreen/UI changes). Do NOT commit; the orchestrator reviews and commits.

**STATUS: Phase 2 and UI stages 1-2 are committed. Ignore the BACKGROUND REFERENCE sections at the bottom of this file.**

**EXECUTION RULE (hard): Do NOT run Gradle, adb, tests, or ANY long-running/background command. Only read files and edit/create files. The orchestrator builds and tests. Finish your turn promptly with a summary.**

## THIS DISPATCH = Stage 8: full Settings screen (replaces the Settings dialog)

**RESUME NOTE: a previous run of this stage was interrupted (the laptop hibernated). Uncommitted partial work already exists: `data/backup/*` (+ tests), `SettingsViewModel.kt`, edits in `SiftApplication.kt`, `CollectionMatcher.kt` (+ test), `ModelStore.kt`, `Entities.kt`, `ScreenshotDao.kt`, `SettingsManager.kt`, `ScreenshotRepository.kt`. Run `git status`/`git diff` FIRST, read what exists, keep what is correct, and finish ONLY the remainder (notably `SettingsScreen.kt`, wiring into `SiftNavHost.kt`/`LibraryRoute`, removing `SettingsDialog.kt`, any missing repository/DAO/ModelStore pieces and tests). Do not redo finished work.**

Read FIRST: `docs/design/SIFT_UI_SPEC.md`, `ui/settings/SettingsDialog.kt` (current dialog; model-status pattern), `ui/navigation/SiftNavHost.kt` (how the settings dialog is opened from `LibraryRoute`; overlay patterns used by the viewer and collection page), `data/local/SettingsManager.kt`, `data/embedding/ModelStore.kt` + `ModelDownloader.kt` (state, install dir `filesDir/models/embeddinggemma2-q4/`, any delete helper), `data/embedding/EmbeddingScheduler` (`syncAutoIndexSchedule`, `enqueueIndexingNow`), `data/collections/CollectionMatcher.kt` (`DEFAULT_Z_THRESHOLD`, `match`), `data/repository/ScreenshotRepository.kt` (`refreshAutoCollections`, constructor providers), `data/local/Entities.kt`, `data/local/ScreenshotDao.kt`, `ui/onboarding/OnboardingScreen.kt` (model section UI to reuse), `ui/theme/*`. Do NOT change build files/dependencies (kotlinx-serialization-json is already available), DB version/schema, or any Jev code.

### 1. Settings (SettingsManager)
Add (SharedPreferences, defaults in parentheses): `autoFillSensitivity: AutoFillSensitivity` (BALANCED) — `enum class AutoFillSensitivity(val zThreshold: Float, val label: String) { STRICT(2.0f, "Strict"), BALANCED(1.5f, "Balanced"), LOOSE(1.0f, "Loose") }` defined next to `CollectionMatcher`; `autoIndexNewScreenshots` already exists. Keep `typeSafeApiKey` in the manager (data layer for Jev stays) but the Settings screen does NOT show it.

### 2. Wiring sensitivity
`ScreenshotRepository` gets a new constructor parameter `zThresholdProvider: () -> Float = { CollectionMatcher.DEFAULT_Z_THRESHOLD }` (default keeps existing tests compiling) used by `refreshAutoCollections` when calling `CollectionMatcher.match` (read the signature; add a threshold parameter there if it does not exist, keeping the default). `SiftApplication` passes `{ settingsManager.autoFillSensitivity.zThreshold }`. Changing sensitivity in Settings calls `repository.refreshAutoCollections()` (all auto-fill collections; MANUAL and EXCLUDED rows are untouched by it) in `appScope`, and shows a snackbar "Updating collections…".

### 3. Pure logic + tests (JVM)
- NEW `data/backup/CollectionsBackup.kt` (no Android deps, `@Serializable` via kotlinx.serialization): `data class BackupFile(version: Int = 1, collections: List<BackupCollection>)`, `BackupCollection(name, description, colorHex, autoFill, kept: List<ScreenshotRef>, excluded: List<ScreenshotRef>)`, `ScreenshotRef(displayName: String, dateAdded: Long, sizeBytes: Long)` (screenshot ids are MediaStore ids and are NOT portable between phones, so identify screenshots by this triple). Functions: `fun encodeBackup(file: BackupFile): String`, `fun decodeBackup(json: String): Result<BackupFile>` (failure for malformed JSON or `version` > 1; ignore unknown keys), and `fun matchRefs(refs: List<ScreenshotRef>, candidates: List<Candidate /* id, displayName, dateAdded, sizeBytes */>): List<Long>` (exact triple match; ids returned in ref order, unmatched refs skipped).
- Tests `app/src/test/java/com/peexr/sift/data/backup/CollectionsBackupTest.kt`: round-trip, malformed input, newer version, unknown keys ignored, `matchRefs` (match, no match, duplicates).
- Tests for `AutoFillSensitivity` thresholds ordering (STRICT > BALANCED > LOOSE) in an existing/new JVM test.

### 4. Repository operations (IO)
- `suspend fun exportCollections(): String` — builds the `BackupFile` from collections + MANUAL (`kept`) and EXCLUDED rows joined to screenshots (add DAO queries as needed: a single query per membership source, no per-row queries) and returns JSON.
- `suspend fun importCollections(json: String): Result<ImportSummary>` — `ImportSummary(collectionsCreated: Int, collectionsSkipped: Int, membershipsApplied: Int)`. A collection whose name already exists (case-insensitive) is skipped (counted); otherwise create it (new id, same colour/description/autoFill), apply kept -> `keepInCollection`, excluded -> `removeFromCollection` for matched screenshots, then `refreshAutoCollections(onlyCollectionId = newId)` when `autoFill` is true.
- `suspend fun reindexEverything()` — DAO `@Query("UPDATE screenshots SET isProcessed = 0, ocrText = '', visualLabels = '', embedding = NULL")` (check exact column names/types in `Entities.kt`; also remove stale FTS rows if triggers do not already handle updates — verify and say in your summary) and clear AUTO memberships (`DELETE FROM screenshot_collections WHERE source = 'AUTO'`); MANUAL/EXCLUDED stay. Then the caller starts indexing with `EmbeddingScheduler.enqueueIndexingNow(app)`.
- `suspend fun libraryStats(): LibraryStats(total: Int, indexed: Int)` (counts via DAO).
- Model removal: `ModelStore.deleteModel()` if not present (delete the install dir, reset state to NotInstalled). Deleting the model must not delete the database.

### 5. Settings UI
- NEW `ui/settings/SettingsScreen.kt` (`SettingsRoute` + stateless `SettingsScreen`) + `SettingsViewModel.kt`. Full-screen overlay opened from the Library header settings button (same slide/fade overlay pattern as the collection page; back arrow + `BackHandler`; hide the floating pill while open). Remove `SettingsDialog.kt` and its usage in `SiftNavHost` (and the `settingsManager` plumbing that only fed the dialog's API key).
- Layout: back circle + title "Settings" (display style like other screens); then tonal section cards (24 dp corners, 16 dp gutters, 12 dp gaps) each with a title and rows:
  1. **Smart search** — model status row (Installed · 316 MB / Not installed / Downloading n% / Failed) with a trailing button "Download" / "Remove model" (confirm dialog "Remove the smart search model? Search and auto-fill stop working until you download it again. Your collections stay."). 
  2. **Indexing** — "Index new screenshots automatically" `Switch` (calls `EmbeddingScheduler.syncAutoIndexSchedule`), a stats line "N of M screenshots indexed", button "Index now" (disabled when nothing pending or model missing), button "Re-index everything" (confirm dialog; then `reindexEverything()` + `enqueueIndexingNow`).
  3. **Collections** — "Auto-fill sensitivity" with a 3-option connected button group Strict / Balanced / Loose and helper text "Strict = fewer, surer matches. Loose = more matches to review."; "Export collections" (`ActivityResultContracts.CreateDocument("application/json")`, default name `sift-collections.json`, write the string on IO via `contentResolver.openOutputStream`) and "Import collections" (`OpenDocument` for `application/json` + `text/plain`, read on IO, call `importCollections`, report "Created 3, skipped 1, restored 42 entries" in a snackbar; failures show "That file isn't a Sift backup").
  4. **About** — "Sift" + version (PackageManager `versionName`), line "Everything runs on your phone. Your screenshots never leave it.", and "Open-source licenses" opening a scrollable dialog listing: Bricolage Grotesque (SIL OFL 1.1), DM Sans (SIL OFL 1.1), ONNX Runtime (MIT), EmbeddingGemma 2 (Gemma Terms of Use), Google ML Kit, Coil (Apache 2.0), Jetpack Compose / AndroidX (Apache 2.0), Room (Apache 2.0), kotlinx libraries (Apache 2.0), OkHttp (Apache 2.0), requery sqlite-android (Apache 2.0). Plain text list, name + license; no network.
- Theme tokens only (no raw colours or font families outside `ui/theme`); text contrast must work in light AND dark (avoid hardcoded white on inverse surfaces); every icon-only control has a content description; touch targets >= 48 dp; `@OptIn` as required. Stateless leaves, immutable StateFlow, IO for disk/IO, never swallow `CancellationException`.

### Constraints
- Do not touch Jev files or remove Jev data code. No new dependencies. Existing tests must keep compiling and passing.
- Do NOT run Gradle or any command. Do not commit. Finish promptly with a summary listing every file touched and any deviation.

---


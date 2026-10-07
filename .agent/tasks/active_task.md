# Active Task Specification — Phase 2: EmbeddingGemma 2 (ONNX) Integration

Source RFC: `docs/RFC_LOCAL_AI_STACK.md` (Phase 2, adapted: **ONNX Runtime instead of LiteRT**, **model downloaded on demand**, **audio encoder excluded**). Do NOT touch Phase 3/4 (no Laya, no Jev changes, no CleanupScreen/UI changes). Do NOT commit; the orchestrator reviews and commits.

**STATUS: Phase 2 and UI stages 1-2 are committed. Ignore the BACKGROUND REFERENCE sections at the bottom of this file.**

**EXECUTION RULE (hard): Do NOT run Gradle, adb, tests, or ANY long-running/background command. Only read files and edit/create files. The orchestrator builds and tests. Finish your turn promptly with a summary.**

## THIS DISPATCH = UI Stage 3a: collections data layer, view-models, library perf (NO composables in this dispatch)

Read FIRST: `docs/design/SIFT_UI_SPEC.md` (sections "Collections", "Collection page"), `data/local/Entities.kt`, `data/local/ScreenshotDao.kt`, `data/repository/ScreenshotRepository.kt`, `ui/library/LibraryViewModel.kt`, `ui/collections/CollectionsViewModel.kt`. "Category" and "collection" are the same thing everywhere: use the word "collection". Do NOT change build files, dependencies, the DB schema/version, or any composable.

### 1. Library perf (the app feels laggy while indexing)
Every embedding the worker writes invalidates `screenshots`, which re-runs `allScreenshotsFlow` (SELECT * incl. embedding BLOB + OCR text, then one query per row for collection names) and regroups the whole library on the main thread.
- DAO: add `data class LibraryRow(val id: Long, val uri: String, val displayName: String, val dateAdded: Long, val sizeBytes: Long)` (query-result class, not an entity) and `@Query("SELECT id, uri, displayName, dateAdded, sizeBytes FROM screenshots ORDER BY dateAdded DESC") fun getLibraryRowsFlow(): Flow<List<LibraryRow>>`.
- Repository: add `val libraryItemsFlow: Flow<List<ScreenshotItem>>` = `dao.getLibraryRowsFlow().map { rows -> rows.map { ScreenshotItem(id, uri, displayName, dateAdded, sizeBytes) } }.distinctUntilChanged().flowOn(Dispatchers.Default)` (no collection lookups, no OCR).
- Fix the N+1 in the old paths too: add DAO `@Query("SELECT sc.screenshotId AS screenshotId, c.name AS name FROM screenshot_collections sc INNER JOIN collections c ON c.id = sc.collectionId WHERE sc.source != 'EXCLUDED'") fun getAllMembershipNamesFlow(): Flow<List<MembershipNameRow>>` and have `allScreenshotsFlow` combine entities with that flow and group names in memory (one pass). `mapToDomainModel(entities)` (used for search results) must also stop doing one query per row: add DAO `suspend fun getMembershipNamesForScreenshots(ids: List<Long>): List<MembershipNameRow>` (single `IN (:ids)` query; chunk ids by 500 to stay under SQLite's variable limit) and use it.
- `LibraryViewModel`: use `repository.libraryItemsFlow`; compute sections in a separate flow `libraryItemsFlow.map { groupByDate(it, ZonedDateTime.now()) }.flowOn(Dispatchers.Default).distinctUntilChanged()` so indexing-progress and keystroke emissions do NOT regroup the library. Behaviour otherwise unchanged; keep `groupByDate`/`formatEta` signatures (existing tests must keep passing).

### 2. Collection summaries (Collections list)
- DAO row `data class CollectionSummaryRow(val id: String, val name: String, val description: String, val colorHex: String, val autoFill: Boolean, val memberCount: Int, val reviewCount: Int, val totalBytes: Long)` and `getCollectionSummariesFlow(): Flow<List<CollectionSummaryRow>>`: one row per collection ordered by name; `memberCount` = rows with source != 'EXCLUDED'; `reviewCount` = rows with source = 'AUTO' (unreviewed); `totalBytes` = SUM(sizeBytes) of non-excluded members (COALESCE to 0). Use correlated subqueries or LEFT JOIN + GROUP BY.
- DAO row `data class CollectionPreviewRow(val collectionId: String, val uri: String)` and `getCollectionPreviewsFlow(): Flow<List<CollectionPreviewRow>>`: up to 3 members per collection (non-excluded; MANUAL first, then newest). The app bundles requery sqlite-android 3.49 so window functions (`ROW_NUMBER() OVER (PARTITION BY ...)`) work at runtime, but Room's compile-time check must also accept the query; if Room rejects it, use a correlated-subquery `LIMIT 3` alternative.
- Domain: new file `data/model/CollectionModels.kt` with `data class CollectionSummary(val id: String, val name: String, val description: String, val colorHex: String, val memberCount: Int, val reviewCount: Int, val totalBytes: Long, val previewUris: List<String>)`. Repository `val collectionSummariesFlow: Flow<List<CollectionSummary>>` = combine of the two DAO flows, `flowOn(Dispatchers.Default)`.

### 3. Collection members (Collection page)
- DAO row `data class CollectionMemberItemRow` with the lightweight columns of `LibraryRow` plus `source: String` (from `screenshot_collections.source`); `@Query getCollectionMembersFlow(collectionId: String): Flow<List<CollectionMemberItemRow>>` for non-excluded members ordered by dateAdded DESC (no OCR/embedding columns).
- Domain (in `CollectionModels.kt`): `data class CollectionMemberItem(val item: ScreenshotItem, val kept: Boolean)` where `kept = source == MembershipSource.MANUAL`. Repository `fun collectionMembersFlow(collectionId: String): Flow<List<CollectionMemberItem>>` and `fun collectionFlow(collectionId: String): Flow<CollectionSummary?>` (from the summaries flow).
- Repository mutation (`withContext(Dispatchers.IO)`): `updateCollectionDescription(id: String, description: String)` = new DAO `@Query("UPDATE collections SET description = :description WHERE id = :id")`, then `refreshAutoCollections(onlyCollectionId = id)`. MANUAL and EXCLUDED rows must survive the refill: check that `replaceAutoMembers`/`refreshAutoCollections` already guarantees this and say so in your summary. `deleteCollection` already exists.

### 4. View-models
- Replace `CollectionsViewModel` (same file): `CollectionsUiState(collections: List<CollectionSummary>, statusMessage: String?)`; actions `createCollection(name: String, description: String)` (trim name; ignore blank; description defaults to the name when blank; picks the next colour round-robin from `CollectionPaletteHex[currentCount % 6]`; calls `repository.createCollection`, which already auto-fills), `deleteCollection(id)`, `clearStatusMessage()`. Keep `CollectionsViewModel.Factory(repository)` compiling because `MainActivity.kt` uses it. Remove the Jev classification plumbing from the view-model (leave the repository method).
- In `ui/theme/Color.kt`, next to `CollectionPalette`, add `val CollectionPaletteHex: List<String>` = the six CONTAINER colours as "#RRGGBB" strings in the same order, and `fun collectionColors(colorHex: String): Pair<Color, Color>` returning the (container, ink) pair whose container matches `colorHex` case-insensitively, falling back to the first pair.
- NEW `ui/collections/CollectionDetailViewModel.kt`: `CollectionDetailViewModel(repository, collectionId)` + `Factory(repository, collectionId)`; `enum class MemberFilter { ALL, AUTO, KEPT }` (KEPT = `kept`, AUTO = not kept); `CollectionDetailUiState(collection: CollectionSummary?, filter: MemberFilter, members: List<CollectionMemberItem> /* already filtered */, allCount: Int, autoCount: Int, keptCount: Int, isRefilling: Boolean)`; actions `setFilter(f)`, `updateDescription(text)` (sets `isRefilling` while `repository.updateCollectionDescription` runs; rethrow CancellationException, log other errors), `delete()`. Pure top-level `fun filterMembers(members: List<CollectionMemberItem>, filter: MemberFilter): List<CollectionMemberItem>` for unit tests.
- The old `ui/collections/CollectionsScreen.kt` (377 lines) is dead UI that depends on the old view-model: delete it and remove its import from `ui/navigation/SiftNavHost.kt`. Do not otherwise edit `CollectionsListScreen` (replaced next dispatch). Other screens that referenced removed view-model members (grep!) must keep compiling.

### 5. Tests (JVM, no Android)
- `app/src/test/java/com/peexr/sift/ui/collections/CollectionLogicTest.kt`: `filterMembers` for ALL/AUTO/KEPT, and `collectionColors` (case-insensitive match, unknown hex falls back to the first pair; compare `Color` values, which is plain Kotlin).
- Fix any existing tests under `app/src/test/` that stop compiling after your DAO/repository changes (mock the new DAO methods as needed).

### Constraints
- Network/disk on Dispatchers.IO, CPU on Default, never swallow CancellationException, immutable StateFlow, no raw `Color(0x...)` outside `ui/theme`.
- DB version stays 3 and the schema is unchanged (new classes are query-result holders only).
- Do NOT run Gradle or any command; the orchestrator builds and tests. Do not commit. Finish promptly with a summary listing every file touched.

---


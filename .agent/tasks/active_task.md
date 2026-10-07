# Active Task Specification — Phase 2: EmbeddingGemma 2 (ONNX) Integration

Source RFC: `docs/RFC_LOCAL_AI_STACK.md` (Phase 2, adapted: **ONNX Runtime instead of LiteRT**, **model downloaded on demand**, **audio encoder excluded**). Do NOT touch Phase 3/4 (no Laya, no Jev changes, no CleanupScreen/UI changes). Do NOT commit; the orchestrator reviews and commits.

**STATUS: Phase 2 and UI stages 1-2 are committed. Ignore the BACKGROUND REFERENCE sections at the bottom of this file.**

**EXECUTION RULE (hard): Do NOT run Gradle, adb, tests, or ANY long-running/background command. Only read files and edit/create files. The orchestrator builds and tests. Finish your turn promptly with a summary.**

## THIS DISPATCH = UI Stage 3c: in-app image viewer (full-screen) with collections sheet and OCR text sheet

Goal: tapping a screenshot anywhere (Library grid, Library search results, a collection page) opens OUR OWN full-screen viewer instead of the system "open with" chooser. The viewer lets the user (a) assign the screenshot to any collections manually, (b) read/copy its OCR text, (c) see basic info, (d) delete the file. "Category" == "collection"; the user creates all collections (no defaults).

Read FIRST: `docs/design/SIFT_UI_SPEC.md` (Color, Type, Shape, Motion, Accessibility), `data/local/Entities.kt` (ScreenshotEntity has `ocrText`, `isProcessed`; `ScreenshotCollectionCrossRef`, `MembershipSource`), `data/local/ScreenshotDao.kt`, `data/repository/ScreenshotRepository.kt` (`keepInCollection`, `removeFromCollection`, `addToCollection`, `createCollection`, `createDeletePendingIntent`, `removeDeletedScreenshots`), `ui/library/LibraryScreen.kt`, `ui/collections/CollectionDetailScreen.kt`, `ui/collections/CollectionsListScreen.kt` (create-sheet pattern), `ui/navigation/SiftNavHost.kt`, `ui/components/ScreenshotTile.kt`. Do NOT change build files, dependencies, DB version/schema.

### 1. Data layer
- DAO (query-result classes, not entities): `data class ScreenshotDetailRow(val id: Long, val uri: String, val displayName: String, val dateAdded: Long, val sizeBytes: Long, val ocrText: String, val isProcessed: Boolean, val hasEmbedding: Boolean)` and `@Query("SELECT id, uri, displayName, dateAdded, sizeBytes, ocrText, isProcessed, (embedding IS NOT NULL) AS hasEmbedding FROM screenshots WHERE id = :id") fun getScreenshotDetailFlow(id: Long): Flow<ScreenshotDetailRow?>`; and `data class MembershipRow(val collectionId: String, val source: String)` with `@Query("SELECT collectionId, source FROM screenshot_collections WHERE screenshotId = :id") fun getMembershipsForScreenshotFlow(id: Long): Flow<List<MembershipRow>>`.
- Repository: `fun screenshotDetailFlow(id: Long): Flow<ScreenshotDetail?>` where (new, in `data/model/CollectionModels.kt` or a new `ScreenshotDetail.kt`) `data class ScreenshotDetail(val item: ScreenshotItem /* with ocrText */, val hasEmbedding: Boolean)`; and `fun collectionMembershipFlow(id: Long): Flow<Map<String, String>>` (collectionId -> source). Both `flowOn(Dispatchers.Default)`.
- Repository mutation `suspend fun setMembership(screenshotId: Long, collectionId: String, member: Boolean)`: `member = true` -> `keepInCollection` (MANUAL, survives auto-fill); `member = false` -> `removeFromCollection` (EXCLUDED, remembered so auto-fill will not re-add it). Reuse the existing methods, `withContext(Dispatchers.IO)`.

### 2. ViewModel
- NEW `ui/viewer/ViewerViewModel.kt`: `ViewerViewModel(repository, screenshotId)` + `Factory`. `ViewerUiState(detail: ScreenshotDetail?, collections: List<ViewerCollectionRow>, isMissing: Boolean)` where `ViewerCollectionRow(val summary: CollectionSummary, val isMember: Boolean)`; `isMember` = membership source is AUTO or MANUAL (EXCLUDED or absent -> false). Combine `repository.screenshotDetailFlow`, `repository.collectionSummariesFlow`, `repository.collectionMembershipFlow`. Pure top-level `fun buildViewerCollections(summaries: List<CollectionSummary>, memberships: Map<String, String>): List<ViewerCollectionRow>` (members first, then by name) for unit tests. Actions: `toggleCollection(collectionId: String, member: Boolean)`, `createCollectionAndAdd(name: String, description: String)` (create via the same colour round-robin logic as `CollectionsViewModel.createCollection` — factor the colour pick into a small pure top-level function `nextCollectionColorHex(existingCount: Int): String` in `ui/collections` shared by both, with a unit test — then add THIS screenshot as MANUAL; the id of the new collection must be returned by `repository.createCollection`: change it to return the new id String, callers ignoring it keep compiling).
- Deleting from storage: the screen asks `repository.createDeletePendingIntent(listOf(Uri.parse(uri)))` and launches it with `rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult())`; on RESULT_OK call `repository.removeDeletedScreenshots(listOf(id))` then close the viewer. (API < 30 returns a null PendingIntent: hide the delete action there or show a snackbar "Delete isn't supported on this Android version" — the repository helper already returns null below R.)

### 3. Screen (match the app's M3 Expressive look; theme tokens only)
- NEW `ui/viewer/ViewerScreen.kt`: `ViewerRoute(viewModel, onClose)` + stateless `ViewerScreen(state, onClose, onToggleCollection, onCreateCollection, onDelete, ...)`.
- Full-screen near-black background (use `MaterialTheme.colorScheme.scrim`/`surface`-derived tokens, not raw hex; the viewer is dark in both themes). The image: Coil `AsyncImage` with `ContentScale.Fit`; pinch-to-zoom (1x–5x), pan when zoomed, double-tap toggles 1x/2.5x, using `Modifier.pointerInput` with `detectTransformGestures`/`detectTapGestures` and `graphicsLayer` (hoist transform state with `remember`). When at 1x, vertical swipe down by > 120 dp closes (translate + fade the image while dragging). Back press and the close button call `onClose`.
- Top bar overlay: circular close button (44 dp+, `surfaceContainerHigh` at ~70% alpha) top-start; the date ("MMMM d, yyyy") + size as a caption centered/after it.
- Bottom action bar (floating pill like the app nav, inset by navigation bars): three actions with icon + label: **Collections** (opens the collections sheet; show a count badge of memberships), **Text** (opens the OCR sheet; icon `TextSnippet`/`Notes`), **Delete** (coral `removeAccent`). Each has a content description.
- Collections sheet: `ModalBottomSheet`, title "Add to collections", a list: for each `ViewerCollectionRow` a row with a coloured dot (container colour from `collectionColors`), name, and a `Checkbox`/`Switch` bound to `isMember` (`toggleable`, role Checkbox). Support multiple. Final row "New collection" (+ icon) expanding inline Name + Description fields and a "Create & add" button (same copy as the create sheet in `CollectionsListScreen`). Empty state when there are no collections: "No collections yet — create one below".
- OCR sheet: `ModalBottomSheet` titled "Text in this screenshot"; if `isProcessed` and `ocrText` is non-blank: scrollable `SelectionContainer` text (bodyLarge) + a "Copy all" button (`LocalClipboardManager` or `ClipboardManager` -> snackbar-style confirmation "Copied"); if processed but blank: "No text found in this screenshot"; if not processed: "Not indexed yet — run indexing from the Library to read this screenshot." Below, small line "Smart search: ready / not indexed" from `hasEmbedding`.
- Delete: confirmation `AlertDialog` "Delete this screenshot?" "It is removed from your phone." -> Delete (coral) / Cancel, then the system delete request above.
- If the screenshot disappears (flow emits null after it existed) close the viewer.

### 4. Navigation wiring
- `ui/navigation/SiftNavHost.kt`: add `var viewerScreenshotId by remember { mutableStateOf<Long?>(null) }`; when non-null render `ViewerRoute` full-screen on top of everything (including a collection page) with a fade/scale-in transition, hide the floating pill, and `BackHandler` closes the viewer FIRST (before the collection-page BackHandler — place it later in composition order or use enabled flags so the viewer wins). Use `viewModel(key = "viewer-$id", factory = ViewerViewModel.Factory(repository, id))`.
- `LibraryScreen.kt` / `LibraryRoute`: replace the `ACTION_VIEW` tile click with a callback `onOpenScreenshot: (ScreenshotItem) -> Unit` threaded from `SiftMainApp` (both grid and search results). Collection page's `onOpenScreenshot` in `SiftNavHost` likewise sets `viewerScreenshotId` (remove the old `ACTION_VIEW` code and now-unused imports).
- The viewer needs system bars hidden or transparent: draw edge-to-edge; do not change `MainActivity`.

### 5. Tests (JVM)
- `app/src/test/java/com/peexr/sift/ui/viewer/ViewerLogicTest.kt`: `buildViewerCollections` (members first then alphabetical; EXCLUDED/absent -> not member; AUTO and MANUAL -> member). `nextCollectionColorHex` wraps after 6 and equals `CollectionPaletteHex[n % 6]`.
- Fix any existing tests broken by the changed signatures.

### Constraints
- Theme tokens only: no raw `Color(0x...)` or font families outside `ui/theme`; icons from `Icons.Rounded`/`Icons.Filled` already available via material-icons-extended.
- Stateless leaves, ViewModel via thin Route, immutable StateFlow, disk on IO, never swallow CancellationException, touch targets >= 48 dp, content descriptions on all icon-only controls, `@OptIn` for experimental APIs.
- Do NOT run Gradle or any command; the orchestrator builds and tests. Do not commit. Finish promptly with a summary listing every file touched.

---


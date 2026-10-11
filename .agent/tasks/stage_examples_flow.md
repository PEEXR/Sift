# Task: "Create collection = name + pick 10 examples" everywhere, and the first collection is created during onboarding

**EXECUTION RULE (hard): Do NOT run Gradle, adb, tests, or ANY long-running/background command. Only read files and edit/create files. The orchestrator builds and tests. Do NOT commit. Finish promptly with a summary.**

Another worker is concurrently editing `data/repository/ScreenshotRepository.kt`, `data/local/ScreenshotDao.kt`, `data/collections/*`, and `app/src/debug/`. **Do not edit those files.** Use only the repository's existing public API (e.g. `libraryItemsFlow`, `createCollection`, `addToCollection`, `refreshAutoCollections`, `hybridSearch`). Everything you need is under `ui/`, plus `MainActivity.kt` if strictly necessary. No new dependencies, no build-file changes, no DB changes.

## Product decision (from the owner)
Collections now learn from examples. Whenever the user creates a collection — including during onboarding — they name it and then **pick about 10 example screenshots** that belong in it. The classifier (being built by the other worker) trains on those examples (stored as MANUAL memberships). Onboarding makes the user create their first collection right away; this also gives the ~316 MB model download time to finish in the background.

## Read first
`ui/onboarding/OnboardingLogic.kt`, `ui/onboarding/OnboardingScreen.kt` (steps WELCOME → PERMISSIONS → MODEL → READY; `app.startModelDownload()` runs in application scope so it survives navigation; `onPhotosGranted` triggers the MediaStore sync that fills the library), `MainActivity.kt` (how onboarding finishes; `onFinish(openCollectionsCreateSheet)`), `ui/collections/CollectionsListScreen.kt` (current create sheet: name + description) and `CollectionsViewModel.kt` (`createCollection`, `nextCollectionColorHex`), `ui/navigation/SiftNavHost.kt`, `ui/components/ScreenshotTile.kt`, `ui/library/LibraryScreen.kt`/`LibraryViewModel.kt` (grid + thumbnails), theme files. Match existing style (Material 3, the app's typography/colours/shapes, comment density, naming).

## 1. Example picker (new, reusable): `ui/collections/ExamplePicker.kt` (+ a small ViewModel if useful)
- Full-screen composable `ExamplePickerScreen(collectionName: String, onDone: (selectedIds: List<Long>) -> Unit, onSkip: () -> Unit, ...)`.
- Header: "Pick 10 screenshots that belong in “{name}”" and a line explaining "Sift learns what this collection means from your examples." Counter chip `n / 10`.
- Grid of the library (newest first) from `repository.libraryItemsFlow`, using the existing thumbnail tile; tap toggles selection (clear check overlay + border); long-press opens nothing (keep simple).
- Search field at the top: when non-empty, show `repository.hybridSearch(query)` results instead of the full grid (debounce ~300 ms; it may return nothing before OCR/indexing has run — show "No matches yet — scroll the library instead" in that case). Selection persists across search/grid.
- Primary button "Done" enabled once ≥ 10 selected (more is fine). A secondary text button "Skip for now" is always available (collection still gets created; it just falls back to text matching until it has examples).
- If the library is still empty (sync in progress), show a spinner with "Loading your screenshots…".
- Back = cancel (no collection created) in the normal flow; in onboarding, back returns to the name step.

## 2. Creating a collection anywhere in the app
Change the existing create flow so that after the user enters name (+ optional description) they go to the Example picker; on Done: `createCollection(...)`, then `addToCollection(id, collectionId)` for each selected screenshot, then `refreshAutoCollections(onlyCollectionId = id)`; then open that collection's detail screen (or return to the list if detail navigation isn't straightforward). On Skip: create the collection without examples. Do the DB work in the ViewModel (`viewModelScope`), not in composables. Expose any new ViewModel functions you need in `CollectionsViewModel`.
Note: `createCollection` currently refreshes immediately after insert; that's fine — calling refresh again after the examples are added is what makes the classifier run.

## 3. Onboarding
New order: WELCOME → PERMISSIONS → MODEL → **COLLECTION** → **EXAMPLES** → READY.
- MODEL: unchanged screen, but make "Continue" the obvious action once the download has *started* (it keeps running in the background). If the model is already installed, it should say so and continue.
- COLLECTION: "Make your first collection" — name field (required) + optional description, with 3–4 tappable suggestion chips (e.g. "Tweets", "Chats", "Memes", "Study notes") that fill the name. Continue requires a non-blank name.
- EXAMPLES: the Example picker for that name. Done/Skip both advance to READY. Persist the collection only here (create + add examples + refresh) via a ViewModel or `app.repository` in a coroutine scope that survives leaving onboarding (e.g. `app.applicationScope` if one exists, otherwise a ViewModel scoped to the activity) — check what's available; don't lose the write when onboarding finishes.
- READY: mention the collection that was created and that Sift will fill it once screenshots are indexed (indexing needs the model; show download progress if still downloading). Remove READY's old "create a collection" call-to-action if it duplicates this; adjust `onFinish(...)` usage accordingly.
- Update `OnboardingLogic.kt`: steps, `nextStep`, `previousStep`, `canAdvanceFrom` (COLLECTION requires a non-blank name → add a parameter), and the step indicator.
- Update `app/src/test/.../OnboardingLogicTest.kt` (find it) for the new steps.

## Done when
All edits made, unit test for onboarding logic updated, summary lists every file changed and anything you couldn't verify without compiling.

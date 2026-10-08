# Active Task Specification — Phase 2: EmbeddingGemma 2 (ONNX) Integration

Source RFC: `docs/RFC_LOCAL_AI_STACK.md` (Phase 2, adapted: **ONNX Runtime instead of LiteRT**, **model downloaded on demand**, **audio encoder excluded**). Do NOT touch Phase 3/4 (no Laya, no Jev changes, no CleanupScreen/UI changes). Do NOT commit; the orchestrator reviews and commits.

**STATUS: Phase 2 and UI stages 1-2 are committed. Ignore the BACKGROUND REFERENCE sections at the bottom of this file.**

**EXECUTION RULE (hard): Do NOT run Gradle, adb, tests, or ANY long-running/background command. Only read files and edit/create files. The orchestrator builds and tests. Finish your turn promptly with a summary.**

## THIS DISPATCH = Stage 11: instrumented Compose UI tests for the new screens

Goal: automated device/emulator tests that exercise the NEW stateless screens with fake in-memory state — no database, no model, no real screenshots, no network — so they are deterministic and run on any Android 8+ device. They are compiled by `assembleDebugAndroidTest` and run later with `connectedDebugAndroidTest` (the orchestrator runs them; you must NOT run Gradle or any command).

Read FIRST: the stateless composables and their state classes: `ui/collections/CollectionsListScreen.kt` (`CollectionsListScreen`), `ui/collections/CollectionDetailScreen.kt` (`CollectionDetailScreen`), `ui/review/ReviewPicker.kt` (`ReviewPicker`, `AgeFilterRow`), `ui/review/ReviewScreen.kt` (`ReviewScreen`), `ui/review/SwipeCardStack.kt`, `ui/onboarding/OnboardingScreen.kt` (step composables), `ui/settings/SettingsScreen.kt` (`SettingsScreen`), `ui/viewer/ViewerScreen.kt` (`ViewerScreen`), `ui/navigation/FloatingPillNav.kt`, `ui/theme/Theme.kt` (`SiftTheme`), their UI-state data classes (`CollectionSummary`, `CollectionDetailUiState`, `ReviewUiState`/`ReviewSession`, `ViewerUiState`, `SettingsUiState` etc.) and the existing androidTest folder `app/src/androidTest/java/com/peexr/sift/`. Dependencies `androidx.compose.ui:ui-test-junit4` and `ui-test-manifest` are already declared; do NOT change build files.

### What to write (new files under `app/src/androidTest/java/com/peexr/sift/ui/...`)
Use `createComposeRule()`; wrap every screen in `SiftTheme`; use `ScreenshotItem`s with a bogus uri (`"content://invalid/1"`) — Coil will fail to load and show the placeholder, which is fine. Prefer semantics (`onNodeWithText`, `onNodeWithContentDescription`, roles) over tags; where a node cannot be targeted reliably, add a minimal `Modifier.testTag("...")` in the production composable (allowed, keep tags few and stable, e.g. `"swipe_top_card"`, `"keep_button"`, `"remove_button"` ONLY if the content descriptions are not sufficient).

1. `CollectionsListScreenTest`: empty state shows "No collections yet" + create button; with 2 `CollectionSummary` items both names, descriptions in quotes and "N to review" badges render; tapping a card invokes `onOpenCollection(id)`; opening the create sheet via the FAB, "Create" is disabled while the name is blank and enabled after typing; tapping it invokes `onCreate(name, description)` with the typed values.
2. `CollectionDetailScreenTest`: title and "Fills with …" chip render; All/Auto/Kept filter buttons show counts and tapping one calls the filter callback; "Review N unsure" button visible only when `reviewCount > 0` and invokes the callback; the overflow menu shows "Edit description" and "Delete collection"; delete shows the confirmation dialog.
3. `ReviewPickerTest`: collections list with "N to review"; age filter chips selectable and invoke the callback; quick-cleanup cards ("Codes & expiring", "Near-duplicates") invoke their callbacks; empty-collections state shows the call to action.
4. `ReviewScreenTest`: with a 3-card collection-mode session, the keep and remove buttons (content descriptions) invoke their callbacks; the progress label "1 of 3" shows; the "Delete file" button is present in collection mode and absent in search mode; in search mode the trash/keep buttons exist and "Mark remaining N strong match(es)" appears only when only strong cards remain; the summary phase shows "Delete N files · X MB" and its button invokes the delete callback; a swipe gesture (`performTouchInput { swipeLeft() }` on the top card) results in the remove/mark callback being invoked.
5. `OnboardingStepsTest`: each step composable renders its headline; the Permissions step's "Continue" is disabled until `hasMediaPermission` is true (use whatever parameter the composable exposes); the Model step shows download / progress / ready states.
6. `SettingsScreenTest`: sections render; toggling the auto-index switch invokes its callback; the sensitivity Strict/Balanced/Loose options invoke the callback with the right value; "Re-index everything" shows a confirmation dialog before invoking; the licenses dialog lists "ONNX Runtime" and "Bricolage Grotesque".
7. `ViewerScreenTest`: tapping "Collections" opens the sheet listing collection names with checkboxes that invoke the toggle callback; tapping "Text" opens the OCR sheet showing the text, the "Not indexed yet" message when not processed, and "Copy all" when text exists; Delete shows the confirmation dialog.
8. `FloatingPillNavTest`: three destinations render; the selected one shows its label; tapping another invokes the callback with that destination.
9. Add a tiny shared helper file `ui/TestFixtures.kt` for fake data builders (`fakeScreenshot(id)`, `fakeCollectionSummary(...)` etc.) so tests stay short.

### Rules
- Tests must not depend on animations timing: use `composeTestRule.waitForIdle()`; set `mainClock.autoAdvance` defaults; avoid `Thread.sleep`.
- Do not use the real `MainActivity`; use `createComposeRule()` (the `ui-test-manifest` supplies the host activity).
- If a screen's current signature makes a case impossible (e.g. state is created inside the composable), prefer a SMALL production refactor (hoist state into a parameter with a default) over skipping the test; list every production file you touched in your summary. Do not change behaviour.
- No new dependencies; no network; no reading from the device's real gallery.
- Do NOT run Gradle or any command. Do not commit. Finish promptly with a summary listing every file touched and any test you had to skip with the reason.

---


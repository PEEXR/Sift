# Active Task Specification — Phase 2: EmbeddingGemma 2 (ONNX) Integration

Source RFC: `docs/RFC_LOCAL_AI_STACK.md` (Phase 2, adapted: **ONNX Runtime instead of LiteRT**, **model downloaded on demand**, **audio encoder excluded**). Do NOT touch Phase 3/4 (no Laya, no Jev changes, no CleanupScreen/UI changes). Do NOT commit; the orchestrator reviews and commits.

**STATUS: Phase 2 and UI stages 1-2 are committed. Ignore the BACKGROUND REFERENCE sections at the bottom of this file.**

**EXECUTION RULE (hard): Do NOT run Gradle, adb, tests, or ANY long-running/background command. Only read files and edit/create files. The orchestrator builds and tests. Finish your turn promptly with a summary.**

## THIS DISPATCH = Stage 14: fix 8 failing instrumented Compose UI tests (test code only)

The Compose UI tests under `app/src/androidTest/java/com/peexr/sift/ui/` were run on a real device (24 of 32 pass). The 8 failures below are TEST bugs, not app bugs. Fix ONLY the test files (and `ui/TestFixtures.kt` if useful). Do NOT change any production code under `app/src/main`, build files or dependencies. Do NOT run Gradle or any command; the orchestrator re-runs them on the phone.

### Failures and required fixes
1. **"ComponentActivity has already set content"** — `composeTestRule.setContent {}` was called more than once in one test (to show several states). Compose allows ONE `setContent` per test rule/test. Affected tests:
   - `CollectionDetailScreenTest.reviewUnsureButton_visibleOnlyWhenReviewCountGreaterThanZero_andInvokesCallback`
   - `OnboardingStepsTest.permissionsStep_rendersHeadline_andContinueDisabledUntilPermissionGranted`
   - `OnboardingStepsTest.modelStep_rendersHeadline_andShowsDownloadProgressReadyStates`
   - `ReviewScreenTest.searchMode_deleteFileAbsent_trashAndKeepButtonsExist_andMarkRemainingAppearsWhenStrong`
   Fix pattern: call `setContent` once and drive the different states through `var state by mutableStateOf(...)` (declared in the test and read inside the composable lambda), then change `state` and call `composeTestRule.waitForIdle()` between assertions. Alternatively split into several `@Test` methods, each with one `setContent`. Keep every assertion that exists today.
2. **`ReviewScreenTest.collectionMode_threeCards_keepAndRemoveAndProgressAndFileDelete`** fails at `assertTrue(keepCalled)` (line ~78): the keep/remove buttons play a fly-off animation (~hundreds of ms) before invoking the callback, so the assertion runs too early. Use `composeTestRule.waitUntil(timeoutMillis = 5_000) { keepCalled }` (and likewise for `removeCalled`; for `deleteFileCalled` if it also animates) after each click, then assert.
3. **`SettingsScreenTest.indexingQualityOptions_invokeCallbackWithCorrectValue`** and **`sensitivityOptions_invokeCallbackWithCorrectValue`**: `onNodeWithText("Balanced")` matches TWO nodes (the Indexing-quality row "Balanced" and the Auto-fill sensitivity button "Balanced"), so `performScrollTo()` fails. Disambiguate with a parent/semantic matcher: e.g. quality rows are `selectable` radio items — use `hasText("Balanced") and hasClickAction()`/`hasAnyAncestor(...)` or `onAllNodesWithText("Balanced")[index]` with a comment explaining the order, or find the node via its description text (`"Good accuracy, about half the time of Best"` belongs to the quality row; the sensitivity group has no such hint text). Pick the most robust option and apply to Strict/Loose/Fast/Best too where ambiguity can occur.
4. **`SettingsScreenTest.licensesDialog_listsOnnxRuntimeAndBricolageGrotesque`**: `onNodeWithText("Open-source licenses")` matches both the button and the dialog title once the dialog is open. Click the first (button) before opening, and after opening assert on the dialog contents ("ONNX Runtime", "Bricolage Grotesque") using `onAllNodesWithText("Open-source licenses")` with an index/`assertCountEquals(2)` as appropriate, or `onNode(hasText("ONNX Runtime"))` which is unique.

### Constraints
- Keep tests deterministic: no `Thread.sleep`; use `waitUntil`/`waitForIdle`.
- Each test must still verify what its name says.
- Finish promptly with a summary listing every file touched.

---


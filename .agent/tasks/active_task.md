# Active Task Specification — Phase 2: EmbeddingGemma 2 (ONNX) Integration

Source RFC: `docs/RFC_LOCAL_AI_STACK.md` (Phase 2, adapted: **ONNX Runtime instead of LiteRT**, **model downloaded on demand**, **audio encoder excluded**). Do NOT touch Phase 3/4 (no Laya, no Jev changes, no CleanupScreen/UI changes). Do NOT commit; the orchestrator reviews and commits.

**STATUS: Phase 2 and UI stages 1-2 are committed. Ignore the BACKGROUND REFERENCE sections at the bottom of this file.**

**EXECUTION RULE (hard): Do NOT run Gradle, adb, tests, or ANY long-running/background command. Only read files and edit/create files. The orchestrator builds and tests. Finish your turn promptly with a summary.**

## THIS DISPATCH = Stage 7: first-run onboarding (permissions, model download, first collection hint)

Today a fresh install immediately fires the gallery permission dialog, never asks for the notification permission (Android 13+; the indexing foreground notification is silently suppressed without it), and only offers the 316 MB model download as a card on the Library. Build a short, friendly onboarding in the app's M3 Expressive style. The user's existing install (permission granted + model installed + library populated) must NOT see it.

Read FIRST: `docs/design/SIFT_UI_SPEC.md` (Color, Type, Shape, Motion), `MainActivity.kt` (current `checkAndRequestPermissions`, `permissionLauncher`, `requestHighestRefreshRate`), `ui/navigation/SiftNavHost.kt`, `ui/library/LibraryViewModel.kt` (`IndexingUi`, `downloadModel()`, model state flow), `data/embedding/ModelStore.kt` (`state`, `isInstalled()`), `data/local/SettingsManager.kt`, `ui/settings/SettingsDialog.kt` (model section pattern), `ui/collections/CollectionsListScreen.kt` (create sheet), `ui/theme/*`. Do NOT change build files/dependencies/DB.

### 1. Pure logic (unit-tested)
- NEW `ui/onboarding/OnboardingLogic.kt`: `enum class OnboardingStep { WELCOME, PERMISSIONS, MODEL, READY }`; `fun shouldShowOnboarding(onboardingDone: Boolean, hasMediaPermission: Boolean, modelInstalled: Boolean): Boolean` = `!onboardingDone && !(hasMediaPermission && modelInstalled)`; `fun nextStep(current: OnboardingStep): OnboardingStep?` (null after READY); `fun canAdvanceFrom(step: OnboardingStep, hasMediaPermission: Boolean): Boolean` (PERMISSIONS requires the media permission; every other step is always advanceable; MODEL is skippable).
- Tests `app/src/test/java/com/peexr/sift/ui/onboarding/OnboardingLogicTest.kt`.
- `SettingsManager`: add `var onboardingDone: Boolean` (key "onboarding_done", default false).

### 2. Screens (`ui/onboarding/OnboardingScreen.kt` + `OnboardingViewModel.kt` if you need state; keep leaves stateless)
Full-screen, edge-to-edge, background `colorScheme.background`, big display-font headlines (use `MaterialTheme.typography.displayMedium`-style like the other screens), a step indicator (4 dots; the active dot is a wide primary pill) and one primary button per step at the bottom (above the navigation bar). Slide/fade transitions between steps (`AnimatedContent`). Back press goes to the previous step.
1. **WELCOME** — headline "Find any screenshot", body "Sift reads and understands your screenshots on your phone. Nothing ever leaves it." Three short feature rows with tonal icon tiles: "Search by what's in it" (Search icon), "Collections that fill themselves" (FolderSpecial), "Swipe to clean up" (Style). Button "Get started".
2. **PERMISSIONS** — headline "Two quick permissions". Card 1 "Photos" (required): "Sift needs to see your screenshots." button "Allow photos" -> request `READ_MEDIA_IMAGES` (API 33+) / `READ_EXTERNAL_STORAGE` (<33) with `rememberLauncherForActivityResult(RequestMultiplePermissions)`; after grant show a check and call `libraryViewModel.syncScreenshots()` (pass a callback; the screen itself stays UI-only). If permanently denied (rationale false after a denial) show "Open settings" which launches `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`. Card 2 "Notifications" (optional, only on API 33+): "So you can see indexing progress while Sift works." button "Allow notifications" -> `POST_NOTIFICATIONS`; on API < 33 the card is hidden. The primary button "Continue" is disabled until photos are granted.
3. **MODEL** — headline "Smart search model", body "A one-time 316 MB download lets Sift understand what's in your screenshots. Wi-Fi recommended." Show state from the model store: NotInstalled -> button "Download 316 MB" (`downloadModel()`) and a text button "Not now"; Downloading -> a thick 12 dp rounded progress bar + "Downloading… N%"; Failed -> message + "Retry"; Ready -> check + "Model ready", primary "Continue". Skipping is allowed (the Library card still offers the download later).
4. **READY** — headline "You're set", body "Next, make your first collection — describe it in words and Sift fills it." Primary "Create a collection" (finishes onboarding, switches to the Collections tab, and opens its create sheet — expose a one-shot `openCreateSheet` flag through `SiftMainApp` state) and text button "Maybe later" (finishes onboarding, stays on Library). Both set `settingsManager.onboardingDone = true`. Indexing starts automatically via the existing auto-index logic when the model is ready (nothing to do here).

### 3. Wiring
- `MainActivity.kt`: compute `showOnboarding = shouldShowOnboarding(settings.onboardingDone, hasMediaPermission(), modelStore.isInstalled())`. When true, do NOT call `checkAndRequestPermissions()` at start (the onboarding does it) and render the onboarding instead of `SiftMainApp`; when it finishes, switch to `SiftMainApp` without recreating the activity. When false, behave exactly as today (existing users: if both conditions are satisfied also set `onboardingDone = true`). The silent sync on launch, `requestHighestRefreshRate`, auto-index hooks stay as they are. Keep ViewModels created via the existing `viewModels` delegates.
- `SiftMainApp`: add optional parameter `startOnCollectionsWithCreateSheet: Boolean = false` (or equivalent) so the "Create a collection" button lands on the Collections tab with the create sheet open (add a `LaunchedEffect`/initial-state in `CollectionsRoute`/`CollectionsListScreen` to consume the flag once).
- Do not duplicate strings that already exist in `SettingsDialog`; if sharing the model-status UI is easy, extract a small shared composable in `ui/components/`, otherwise keep them separate.

### 4. Quality
- Theme tokens only (no raw `Color(0x...)`, no font families outside `ui/theme`); stateless leaves; every icon-only control has a content description; touch targets >= 48 dp; text contrast works in light AND dark theme (the app follows the system theme — do not use `inverseOnSurface`/`inverseSurface` pairings with hardcoded white). `@OptIn` as required (`ExperimentalMaterial3ExpressiveApi` for `LoadingIndicator`).
- Never swallow `CancellationException`; no network code in composables (the download goes through `SiftApplication.startModelDownload()` as today).
- Do NOT run Gradle or any command. Do not commit. Finish promptly with a summary listing every file touched and any deviation.

---


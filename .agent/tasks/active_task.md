# Active Task Specification — Phase 2: EmbeddingGemma 2 (ONNX) Integration

Source RFC: `docs/RFC_LOCAL_AI_STACK.md` (Phase 2, adapted: **ONNX Runtime instead of LiteRT**, **model downloaded on demand**, **audio encoder excluded**). Do NOT touch Phase 3/4 (no Laya, no Jev changes, no CleanupScreen/UI changes). Do NOT commit; the orchestrator reviews and commits.

**STATUS: Phase 2 and UI stages 1-2 are committed. Ignore the BACKGROUND REFERENCE sections at the bottom of this file.**

**EXECUTION RULE (hard): Do NOT run Gradle, adb, tests, or ANY long-running/background command. Only read files and edit/create files. The orchestrator builds and tests. Finish your turn promptly with a summary.**

## THIS DISPATCH = Stage 5a: review cards show the whole screenshot (no crop) + OCR snippet

Read FIRST: `ui/review/SwipeCardStack.kt` (the card content composable around "Screenshot image (Coil, ContentScale.Crop ...)" and the bottom info row), `ui/review/ReviewSession.kt` (`ReviewCard`), `ui/review/ReviewScreen.kt` for how cards are passed. Do NOT change swipe physics, stack layout, stamps, build files or the data layer. Only the contents of the card frame change.

### Behaviour
The card frame keeps its fixed size. Inside the image area (the rounded `Box` that currently crops):
1. **Backdrop**: the same image drawn with `ContentScale.Crop`, blurred (`Modifier.blur(28.dp)`; it is a no-op below Android 12, which is fine) and dimmed (overlay `MaterialTheme.colorScheme.scrim` at ~35% alpha) so letterbox space never looks like empty black bars.
2. **Foreground**: the same image with `ContentScale.Fit`, centred, clipped to `RoundedCornerShape(14.dp)` with a small inset (8 dp) so the whole screenshot is visible at its own aspect ratio.
3. **Very tall captures** (width/height < 0.35, e.g. scrolling screenshots): draw the foreground with `ContentScale.FillWidth` aligned to the top (`Alignment.TopCenter`), clipped to the frame, with a 56 dp bottom fade (a vertical gradient from transparent to the backdrop colour) so it is clear the image continues. Tapping the card still opens the in-app viewer (already wired via `onOpenScreenshot`) for pinch-zoom.
4. Get the image's aspect ratio from Coil without a second request: use `AsyncImage(... onSuccess = { state -> aspect = state.painter.intrinsicSize.run { if (height > 0f) width / height else null } })` (or `SubcomposeAsyncImage`); hold it in `remember(card.item.uri) { mutableStateOf<Float?>(null) }`. Until known, use the normal Fit mode. Load at a size good enough to read large text: `Size(1080, 1440)` for the foreground; the backdrop may use `Size(160, 240)` (it is blurred anyway) — two lightweight Coil requests for the same uri are fine (memory cache dedups).
5. Pure functions (top-level in `ui/review/`, public so tests can use them): `enum class CardImageMode { FIT, TALL_TOP }` and `fun cardImageMode(aspect: Float?): CardImageMode` (null or >= 0.35 -> FIT, else TALL_TOP); `fun ocrSnippet(text: String, maxChars: Int = 80): String` — collapse all whitespace runs/newlines to single spaces, trim, return "" for blank, truncate to `maxChars` at a word boundary when possible and append "…" when truncated.
6. **OCR snippet line** under the date row, single line, `bodyMedium`, `onSurfaceVariant`, `maxLines = 1`, ellipsis; only shown when `ocrSnippet(card.item.ocrText)` is not blank; content description not needed (decorative text is read normally). Keep the bottom row's date + confidence pill unchanged. The card must not grow: the image area uses the remaining height (`weight(1f)`), as today.
7. Only the TOP card loads the full-size foreground image; the two peeking cards behind it keep a cheap load (they are mostly hidden). Do not allocate per drag frame: the image composables must not read the drag offset.

### Tests (JVM)
- `app/src/test/java/com/peexr/sift/ui/review/CardImageTest.kt`: `cardImageMode` (null, 0.46, exactly 0.35, 0.2, 1.0, 2.0) and `ocrSnippet` (blank/whitespace -> "", newlines collapsed, short text unchanged, long text truncated at a word boundary with "…", a single very long word with no spaces is hard-truncated with "…", result length <= maxChars + 1).

### Constraints
- Theme tokens only (no raw `Color(0x...)`; `Color.Transparent`/`Color.Black` constants are fine for gradients/scrim). Touch targets and content descriptions unchanged. `@OptIn` where required.
- Do NOT run Gradle or any command. Do not commit. Finish promptly with a summary listing every file touched.

---


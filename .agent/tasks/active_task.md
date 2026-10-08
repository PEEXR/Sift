# Active Task Specification — Phase 2: EmbeddingGemma 2 (ONNX) Integration

Source RFC: `docs/RFC_LOCAL_AI_STACK.md` (Phase 2, adapted: **ONNX Runtime instead of LiteRT**, **model downloaded on demand**, **audio encoder excluded**). Do NOT touch Phase 3/4 (no Laya, no Jev changes, no CleanupScreen/UI changes). Do NOT commit; the orchestrator reviews and commits.

**STATUS: Phase 2 and UI stages 1-2 are committed. Ignore the BACKGROUND REFERENCE sections at the bottom of this file.**

**EXECUTION RULE (hard): Do NOT run Gradle, adb, tests, or ANY long-running/background command. Only read files and edit/create files. The orchestrator builds and tests. Finish your turn promptly with a summary.**

## THIS DISPATCH = Stage 5b: cleanup filters — age filter, "Codes & expiring" preset, "Near-duplicates" preset

Product decisions (user): cleanup = search + age filter; also detect OTP/expiring-code screenshots and near-duplicate bursts. NO learned classifier, NO Jev/Laya (leave `data/api/*`, `JevModels.kt` and the repository's Jev methods untouched — do not remove or call them). Everything is deterministic (regex + embedding cosine) and reuses the existing Review "search mode" swipe stack: swipe left = mark for deletion, one final system delete request. The user is always in the loop; nothing is deleted without the existing summary card.

Read FIRST: `ui/review/ReviewViewModel.kt`, `ReviewSession.kt` (`ReviewCard.isStrong`, `splitSearchResults`, modes), `ReviewScreen.kt`, `ReviewPicker.kt`, `data/repository/ScreenshotRepository.kt` (`hybridSearch`, `mapToDomainModel`), `data/search/VectorMath.kt` (cosine), `data/local/ScreenshotDao.kt` and `Entities.kt` (embedding is a 128-float BLOB, ocrText, dateAdded in epoch SECONDS). Do NOT change build files, DB version/schema, swipe physics, or card visuals.

### 1. Pure logic (new file `app/src/main/java/com/peexr/sift/data/cleanup/CleanupRules.kt`, no Android deps, fully unit-tested)
- `enum class AgeFilter(val days: Int?, val label: String) { ANY(null,"Any age"), MONTH(30,"Older than 1 month"), QUARTER(90,"Older than 3 months"), HALF_YEAR(180,"Older than 6 months"), YEAR(365,"Older than 1 year") }`.
- `fun isOlderThan(dateAddedSeconds: Long, nowSeconds: Long, days: Int?): Boolean` (null days -> true; strictly older than `days * 86400`).
- `fun looksLikeCode(ocrText: String): CodeKind?` where `enum class CodeKind { OTP, EXPIRING }`. OTP: the text contains (case-insensitive) a keyword from {otp, one-time, one time, verification code, security code, login code, passcode, "is your code", "your code"} AND a standalone 4–8 digit number (`(?<!\d)\d{4,8}(?!\d)`, ignoring numbers that look like years 19xx/20xx or are part of a longer digit/decimal/phone pattern is NOT required; keep it simple). EXPIRING: contains a phrase like "expires in", "expires on", "valid for", "valid until", "expiring", "will expire", "expire in", "use within" (case-insensitive) — OTP takes priority when both match. Blank text -> null.
- `data class EmbeddedRef(val id: Long, val dateAdded: Long, val embedding: FloatArray)` and `fun findBursts(items: List<EmbeddedRef>, windowSeconds: Long = 600, threshold: Float = 0.92f): List<List<Long>>`: sort by dateAdded; a burst is a maximal run where each item is within `windowSeconds` of the previous one AND has cosine >= `threshold` to the burst's first item (embeddings are L2-normalised 128-d; use the existing cosine/dot helper from `VectorMath`); return only bursts of size >= 2, each list ordered oldest -> newest. `fun burstDeletionCandidates(bursts: List<List<Long>>): List<Long>` = every id except the NEWEST of each burst (the newest is kept).
- Unit tests `app/src/test/java/com/peexr/sift/data/cleanup/CleanupRulesTest.kt`: `isOlderThan` boundaries; `looksLikeCode` (positive: "Your OTP is 482913", "482913 is your verification code", "Code expires in 10 minutes"; negative: "Total ₹482913 paid", "Meeting at 4:30", blank, a 9-digit number with "otp"); `findBursts` (3 near-identical shots within 2 minutes -> one burst of 3; same vectors 20 minutes apart -> two singletons, none returned; different vectors close in time -> none; unsorted input); `burstDeletionCandidates` keeps newest.

### 2. Data access
- DAO: `@Query("SELECT id, dateAdded, embedding FROM screenshots WHERE embedding IS NOT NULL ORDER BY dateAdded ASC") suspend fun getEmbeddedRefs(): List<EmbeddedRow>` (query-result class `EmbeddedRow(id, dateAdded, embedding: ByteArray)`); `@Query("SELECT id, ocrText FROM screenshots WHERE ocrText != ''") suspend fun getOcrTexts(): List<OcrRow>`; `@Query("SELECT * FROM screenshots WHERE id IN (:ids)")` already exists as `getScreenshotsByIds` — reuse (chunk ids by 500).
- Repository (IO): `suspend fun findCodeScreenshots(): List<CodeMatch>` (`CodeMatch(entity: ScreenshotEntity, kind: CodeKind)`) = run `looksLikeCode` over `getOcrTexts()` then fetch entities; `suspend fun findDuplicateScreenshots(): List<ScreenshotEntity>` = decode the BLOBs with the existing embedding decoding helper (find it in `data/search` or the repository), `findBursts`, `burstDeletionCandidates`, fetch entities (order: by dateAdded ASC). Both CPU work on `Dispatchers.Default`. `hybridSearch` stays as is.

### 3. Review integration
- New `ReviewMode.Cleanup(preset: CleanupPreset)` or reuse `Search` with a label — choose the smallest change that keeps `ReviewSession` pure/testable. `enum class CleanupPreset { CODES, DUPLICATES }`. Header label: "Codes & expiring · N" / "Near-duplicates · N" instead of `N matches for "q"`.
- Age filter state `ageFilter: AgeFilter` lives in the ViewModel/state (default ANY) and applies to: free-text searches (filter the hybrid results by `isOlderThan` BEFORE `splitSearchResults`), and the CODES preset. It does NOT apply to DUPLICATES (burst logic stays pure). Changing the filter re-runs the current search/preset.
- Stack ordering/strength: CODES: card `isStrong = true` when the screenshot is older than 7 days (a code that old is stale) else `false`; confidence label "Old code" / "Recent code" / "Expires soon text" per `CodeKind` — pick clear short labels (≤ 14 chars): OTP -> "One-time code", EXPIRING -> "Expiring". Unsure (recent) first, then strong. DUPLICATES: `isStrong = true` (the newest of each burst is kept automatically and never shown), label "Near-duplicate". Reuse the existing "Mark remaining N strong matches" flow, summary card and single delete request unchanged. Empty results: "Nothing to clean here" (+ "Try an older age filter" when `ageFilter == ANY` is false... keep it simple: just the message).
- UI in `ReviewPicker.kt`: under the search pill add an **age filter chip row** (`FilterChip`/connected chips, horizontally scrollable, selected chip uses `primaryContainer`), and a section "Quick cleanups" with 2 compact tonal cards: "Codes & expiring — one-time codes and 'expires in…' screenshots" and "Near-duplicates — shots you took twice in a row"; each shows a loading indicator while computing, then enters the swipe stack. The age chips are also visible (compact) in the header while swiping a search so the user can change them; keep it to one row that does not shift the card area (hide it while a card is being dragged is NOT needed — place it in the header block under the progress bar only when mode is Search/Cleanup(CODES) and phase is Swiping; the card frame must keep its size, so reserve its height only in those modes).
- All text via existing styles/tokens; every chip/card has content descriptions and 48 dp touch targets; no new colours.

### 4. Tests
- Extend `ReviewSessionTest.kt` only if you changed `ReviewSession`; keep all existing tests passing. Repository tests: do NOT require Android; skip repository tests for the new DAO calls (covered by pure-logic tests).

### Constraints
- Disk on IO, CPU on Default, never swallow `CancellationException`, immutable StateFlow, stateless composable leaves, `@OptIn` as required. Do not remove or call Jev code. No new dependencies.
- Do NOT run Gradle or any command. Do not commit. Finish promptly with a summary listing every file touched and any deviation.

---


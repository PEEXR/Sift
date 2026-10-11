# Task: Collections learn from screenshots the user adds — refresh after adds, and a "Find more" review of near-misses

**EXECUTION RULE (hard): Do NOT run Gradle, adb, tests, or ANY long-running/background command. Only read files and edit/create files. The orchestrator builds and tests. Do NOT commit. Finish promptly with a summary.**

Another worker is concurrently editing `data/embedding/*` and `SiftApplication.kt`. **Do not edit those.**

## Context (measured on the vivo)
Each collection runs a per-collection logistic-regression classifier (`data/collections/CollectionClassifier.kt`, wired in `ScreenshotRepository.refreshAutoCollections`): positives = the collection's MANUAL members, negatives = its EXCLUDED members + other collections' MANUAL members + a seeded library sample. On the phone it is precise (~0.75) but misses about half of what belongs (recall ~0.55). Screenshots the user adds (viewer toggle → `setMembership` → MANUAL) already become positives, but:
1. Nothing refreshes after a viewer toggle, so the collection doesn't change until some later refresh.
2. The user has no way to find the screenshots the collection missed, so they rarely add any.

## Read first
`ScreenshotRepository.kt` (`refreshAutoCollections`, `setMembership`, `keepInCollection`, `removeFromCollection`, `getReviewQueue`), `ScreenshotDao.kt`, `CollectionClassifier.kt`, `CollectionMatcher.kt` (sensitivity → probability threshold), `ui/viewer/ViewerViewModel.kt`, `ui/review/ReviewSession.kt` + `ReviewViewModel.kt` + `ReviewScreen.kt` (modes; in `ReviewMode.Collection` right = keep → MANUAL, left = remove → EXCLUDED; `triggerCollectionRefreshIfNeeded`), `ui/collections/CollectionDetailScreen.kt` + `CollectionDetailViewModel.kt`, `ui/navigation/SiftNavHost.kt`. Match style and comment density.

Files you may touch: those above, `ScreenshotDao.kt` (new queries only, no schema change), and tests under `app/src/test/`. No build files, no new dependencies.

## 1. Refresh after viewer changes
In `ViewerViewModel.toggleCollection` (and `createCollectionAndAdd`), after the membership write, refresh that collection: `repository.refreshAutoCollections(onlyCollectionId = collectionId)`. Debounce per collection (~1.5 s; a user toggling several in a row → one refresh), in a scope that survives leaving the viewer (the app scope that `ReviewViewModel` uses — `appScope`). Same try/catch pattern (rethrow `CancellationException`).

## 2. Repository: near-miss suggestions
`suspend fun getSuggestions(collectionId: String, limit: Int = 30): List<CollectionMemberRow-like item>` (reuse whatever row/item type `getReviewQueue` returns so the review UI can show it unchanged):
- Factor the classifier-training part of `refreshAutoCollections` (positives/negatives gathering + `train`) into a private helper both use — **don't change refresh's behaviour or its log line**.
- If the collection has < `MIN_EXAMPLES` MANUAL members with vectors → return empty.
- Score every screenshot with a vector that has **no membership row of any kind** in this collection; keep those with `SUGGEST_MIN_P = 0.15 <= p < threshold` (threshold from the current sensitivity, same mapping refresh uses); sort by p descending; take `limit`.
- Runs on `Dispatchers.Default`.

## 3. Review mode "Find more"
- Add a review mode for suggestions (e.g. `ReviewMode.Suggestions(id, name)` or a flag on `Collection` — pick whichever fits the code better). Queue = `getSuggestions(id)`. Swipe right = **add** (`keepInCollection` → MANUAL), swipe left = **not this one** (`removeFromCollection` → EXCLUDED; a no-row screenshot getting an EXCLUDED row is fine — it becomes a negative). Labels/toasts: "Added to {name}" / "Not in {name}". Never offers deletion in this mode.
- After the session (same rule as `triggerCollectionRefreshIfNeeded`) refresh the collection.
- Empty queue: friendly message ("No more suggestions for {name} right now").
- Entry point: a "Find more" button/action on the collection detail screen, shown when the collection has ≥ 5 examples (or always, letting the empty state handle it — your call; say which).

## Tests
- `ReviewSession` reducer tests for the new mode (start, keep/remove counting, done phase).
- A repository-level test for `getSuggestions` if `ScreenshotRepositoryTest` has a fake-DAO setup that makes it reasonable: returns only non-members in `[0.15, threshold)`, sorted, ≤ limit; empty when < 5 examples.
- Existing tests must still pass (don't change refresh semantics).

## Done when
Edits made; summary lists every file changed, the mode design chosen, and anything unverifiable without compiling.

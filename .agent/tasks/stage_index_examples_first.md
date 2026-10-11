# Task: Index collection examples first, and fill collections during indexing (not only at the end)

**EXECUTION RULE (hard): Do NOT run Gradle, adb, tests, or ANY long-running/background command. Only read files and edit/create files. The orchestrator builds and tests. Do NOT commit. Finish promptly with a summary.**

## Problem (observed on a real phone)
First install: ~1,000 screenshots, ~6 s each to embed → indexing takes ~1.5 h. The user creates collections and picks ~10 examples each (stored as `MembershipSource.MANUAL` rows). Two issues:
1. `EmbeddingWorker.doWork` takes a fixed list from `dao.getScreenshotsNeedingIndexing()` (newest first) at the start. Example screenshots that happen to be older are embedded late, so the collection classifier (`refreshAutoCollections` needs ≥ `MIN_EXAMPLES` = 5 MANUAL members **with embeddings**) stays on the weak text fallback for most of the run. Examples added *after* the worker started are never moved forward.
2. `refreshAutoCollections()` runs only once, after the whole run finishes (`EmbeddingWorker.kt` ~line 278). For the whole first run the collections look nearly empty.

## Read first
`data/embedding/EmbeddingWorker.kt` (whole file), `data/local/ScreenshotDao.kt` (`getScreenshotsNeedingIndexing`, `IndexingRow`, membership tables/`MembershipSource`), `data/repository/ScreenshotRepository.kt` (`refreshAutoCollections`, `addToCollection`, `createCollection`), `data/collections/CollectionClassifier.kt` (`MIN_EXAMPLES`). Match the surrounding style and comment density.

## Changes
Files you may touch: `EmbeddingWorker.kt`, `ScreenshotDao.kt` (new/changed queries only — **no schema or DB version change**), and `ScreenshotRepository.kt` only if strictly needed. Nothing under `ui/`, no build files, no new dependencies.

1. **Examples first at start.** Change the ordering of `getScreenshotsNeedingIndexing()` so screenshots that are a MANUAL member of any collection come first, then the rest newest-first (e.g. `ORDER BY (id IN (SELECT screenshotId FROM screenshot_collections WHERE source = 'MANUAL')) DESC, dateAdded DESC` — check how `source` is actually stored by the type converter and use the right literal).
2. **Examples added mid-run jump the queue.** Before each item (or at least before each batch — per item is preferred and the query must be cheap), check for MANUAL-member screenshots that still need an embedding and have not already been attempted in this run (keep a `HashSet<Long>` of attempted ids so a failing image can't loop forever); process those first, then continue the original list, skipping ids already attempted. Add a small DAO query, e.g. `getManualScreenshotsNeedingEmbedding(): List<IndexingRow>` (only rows with `embedding IS NULL`). Progress counts (`processed`/`total`) must stay sensible: increase `total` if a queued example wasn't in the original list; never let `processed` exceed `total`.
3. **Refresh collections during the run.** Call `app.repository.refreshAutoCollections()` (same try/catch as the end-of-run call):
   - right after an example (MANUAL-member) screenshot gets its embedding **and** no further pending examples remain in the queue (so all collections' classifiers switch on as soon as their examples are in), and
   - every `REFRESH_EVERY = 50` newly embedded screenshots,
   - and keep the existing end-of-run refresh.
   Don't refresh more often than that; refresh is cheap (~10 ms per collection) but writes the DB.
4. Keep everything else unchanged: calibration, budgets, `isStopped` handling, perception step, foreground notification, `Result.retry()` semantics.

## Done when
Edits made; summary lists every file changed, the exact SQL used, how `source` is stored, and anything you couldn't verify without compiling.

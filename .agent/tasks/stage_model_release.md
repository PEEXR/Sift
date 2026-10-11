# Task: Release the embedding model's memory when it isn't needed

**EXECUTION RULE (hard): Do NOT run Gradle, adb, tests, or ANY long-running/background command. Only read files and edit/create files. The orchestrator builds and tests. Do NOT commit. Finish promptly with a summary.**

Another worker is concurrently editing `data/repository/ScreenshotRepository.kt`, `data/local/ScreenshotDao.kt` and `ui/review/*`, `ui/viewer/*`, `ui/collections/*`. **Do not edit those files.**

## Problem (measured on the vivo)
After indexing 1,022 screenshots, Sift sat at ~1.24 GB PSS with ~1 GB of native heap allocated — the ONNX text + vision sessions (`EmbeddingEngine`) plus the 32 MB tokenizer stay loaded forever once created. `getSessions()` loads both sessions together, even for a text-only search.

## Read first
`data/embedding/EmbeddingEngine.kt` (whole), `SiftApplication.kt` (lazy `embeddingEngine`), `data/embedding/EmbeddingWorker.kt` (end of `doWork`), callers of `embedText` (search) and `embedImage`. Match style and comment density.

Files you may touch: `EmbeddingEngine.kt`, `SiftApplication.kt` (only if needed), `EmbeddingWorker.kt` (only the end-of-run/stop paths — another change just landed in its loop; don't restructure it), and `app/src/test/.../EmbeddingEngineTest.kt`.

## Changes
1. **Load sessions separately.** Text search should load only the text session (+ tokenizer); `embedImage` loads the vision session (and whatever else it needs). Keep the existing thread-safety (`inferenceMutex`, the `synchronized` double-check) — no race where a session is closed while an inference uses it: closing must take `inferenceMutex`.
2. **`suspend fun releaseVisionSession()`**: closes and nulls the vision session (under `inferenceMutex`). Next `embedImage` reloads it transparently.
3. **Idle release**: after `IDLE_RELEASE_MS = 60_000` with no inference of any kind, close all sessions and drop the tokenizer (keep the `OrtEnvironment` — it's a cheap singleton). Next call reloads. Use a coroutine job on an engine-owned `CoroutineScope(SupervisorJob() + Dispatchers.IO)` that's restarted on every inference (cancel + relaunch `delay`); `close()` cancels the scope. Must not fire while an inference is running.
4. **EmbeddingWorker**: at the end of a run (success, and the stop/retry paths) call `releaseVisionSession()` in a try/catch (rethrow `CancellationException`). Only if `embeddingEngine` was used.
5. `close()` and `isClosed` semantics unchanged (closed engine stays closed; release ≠ close).
6. Log at `Log.i(TAG, ...)` when sessions are loaded and released (with which ones), wrapped so JVM unit tests don't crash (`try { Log.i(...) } catch (_: Throwable) {}` — this codebase's tests don't mock `Log`).

## Tests
Add to `EmbeddingEngineTest.kt` only what is testable without native ONNX (e.g. if you extract the idle-timer logic into a small pure/injectable helper, test that it fires after the timeout, is reset by activity, and never fires during an active inference). Don't break existing tests.

## Done when
Edits made; summary lists files changed, the locking design, and anything unverifiable without compiling.

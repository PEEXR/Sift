# Active Task Specification — Phase 1: Database & Hybrid Search Foundation

Source RFC: `docs/RFC_LOCAL_AI_STACK.md` (Phase 1 ONLY. Do NOT touch Phase 2-4: no LiteRT/MediaPipe, no WorkManager, no ONNX, no UI changes.)

## Objective
1. Room migration v1 -> v2 adding a 128-d embedding BLOB to `ScreenshotEntity` and an FTS5 index over `ocrText`.
2. A pure-Kotlin FloatArray cosine-similarity utility plus Reciprocal Rank Fusion (RRF), with unit tests.

## Key Files (all under `sift_android/app/src/main/java/com/peexr/sift/`)
- Modify: `data/local/Entities.kt` (ScreenshotEntity)
- Modify: `data/local/AppDatabase.kt`
- Modify: `data/local/ScreenshotDao.kt`
- Create: `data/local/Migrations.kt` (SQL constants + `MIGRATION_1_2`)
- Create: `data/search/VectorMath.kt`
- Create: `data/search/RankFusion.kt`
- Create tests in `sift_android/app/src/test/java/com/peexr/sift/data/search/`: `VectorMathTest.kt`, `RankFusionTest.kt` (JUnit4, plain JVM; see existing `JevSerializationTest.kt` for style)

## Part 1: Room migration

### ScreenshotEntity
- Add `val embedding: ByteArray? = null` as the LAST constructor parameter (nullable: not yet indexed). 128 floats x 4 bytes = 512 bytes, little-endian.
- Because it is a `data class` with a ByteArray, override `equals`/`hashCode` using `contentEquals`/`contentHashCode` (nullable-safe).
- Keep `ocrText` as is.

### AppDatabase
- Bump `version = 2`. Keep `exportSchema = false`.
- Add `.addMigrations(MIGRATION_1_2)` and `.addCallback(...)` to the builder.
- NOTE: Room 2.6.1 has NO FTS5 entity support (only `@Fts4`). Do NOT declare an FTS entity. Create the FTS5 table by raw SQL, so Room's schema validation ignores it.

### Migrations.kt
Define in one place the SQL used by BOTH the migration and the `onCreate` callback (fresh installs never run migrations, so the callback must create the same objects):
- `ALTER TABLE screenshots ADD COLUMN embedding BLOB` (migration only; on fresh install Room creates the column from the entity).
- FTS5 external-content table:
  `CREATE VIRTUAL TABLE IF NOT EXISTS screenshots_fts USING fts5(ocrText, content='screenshots', content_rowid='id', tokenize='unicode61 remove_diacritics 2')`
- Three sync triggers (AFTER INSERT, AFTER DELETE, AFTER UPDATE OF ocrText ON screenshots) using the standard external-content pattern (`INSERT INTO screenshots_fts(screenshots_fts, rowid, ocrText) VALUES('delete', old.id, old.ocrText)` for delete/update-old, plain insert for new). All `IF NOT EXISTS`.
- Backfill in migration: `INSERT INTO screenshots_fts(screenshots_fts) VALUES('rebuild')`.
- Expose `fun createFtsObjects(db: SupportSQLiteDatabase)` used by the callback's `onCreate`, and have `MIGRATION_1_2` call the ALTER, then `createFtsObjects`, then the rebuild.
- IMPORTANT: `insertScreenshots` uses `OnConflictStrategy.REPLACE`. SQLite fires DELETE triggers for REPLACE-deleted rows ONLY when `PRAGMA recursive_triggers = ON`; otherwise the old FTS entry goes stale and the index corrupts. So the callback must also set `PRAGMA recursive_triggers = ON` in `onOpen` (via `db.query("PRAGMA recursive_triggers = ON").close()` to be safe with result-returning pragmas), with a comment explaining why.

### ScreenshotDao
- Leave `insertScreenshots` unchanged (preserving existing `ocrText`/`embedding` on re-insert is OUT OF SCOPE), but add a brief comment noting it relies on `recursive_triggers` for FTS sync.
- Add:
  - `@Query("UPDATE screenshots SET embedding = :embedding WHERE id = :id") suspend fun updateEmbedding(id: Long, embedding: ByteArray)`
  - `@Query("SELECT id, embedding FROM screenshots WHERE embedding IS NOT NULL") suspend fun getAllEmbeddings(): List<EmbeddingRow>` with a small `data class EmbeddingRow(val id: Long, val embedding: ByteArray)` (needs contentEquals-based equals/hashCode too; put it in Entities.kt or the DAO file).
  - `@Query("SELECT rowid FROM screenshots_fts WHERE screenshots_fts MATCH :query ORDER BY rank LIMIT :limit") suspend fun searchOcrIds(query: String, limit: Int = 100): List<Long>`. Room cannot validate the FTS table at compile time without an entity; if KSP/Room fails verifying this query, use `@RawQuery` with a `SupportSQLiteQuery` wrapper instead (`searchOcrIds` as a non-abstract interface/default or a helper), and keep the same public semantics.
- Add a helper `fun sanitizeFtsQuery(raw: String): String` (in `data/search/`, top-level, pure): split on whitespace, strip FTS5 special characters (`"*():^-+` etc.), drop empties, wrap each term in double quotes, append `*` to the last term for as-you-type prefix match, join with spaces. Return empty string if nothing remains (caller must skip the query then). Add tests.

## Part 2: Vector math + RRF (pure Kotlin, no Android imports so they run as JVM unit tests)

### VectorMath.kt (`package com.peexr.sift.data.search`)
- `const val EMBEDDING_DIM = 128`
- `fun ByteArray.toFloatArray(): FloatArray` / `fun FloatArray.toByteArray(): ByteArray` using `ByteBuffer.order(ByteOrder.LITTLE_ENDIAN)`; require size == EMBEDDING_DIM*4 (throw `IllegalArgumentException` otherwise).
- `fun FloatArray.l2Normalize(): FloatArray` (returns a new array; zero vector returns zeros, no NaN).
- `fun dot(a: FloatArray, b: FloatArray): Float`: single tight loop, unrolled by 4 with 4 independent accumulators (lets the JIT/ART auto-vectorize), handles remainder. Require equal sizes.
- `fun cosineSimilarity(a, b): Float` (handles zero norms -> 0f).
- `fun topKBySimilarity(query: FloatArray, candidates: List<Pair<Long, FloatArray>>, k: Int): List<Pair<Long, Float>>`: assumes candidates are pre-normalized; compute dot with a normalized query, keep top-k using a size-k min-heap (`PriorityQueue`), return sorted descending. No full sort of all candidates. `k <= 0` -> empty.
- No allocations inside the per-candidate hot loop.

### RankFusion.kt
- `fun reciprocalRankFusion(rankings: List<List<Long>>, k: Int = 60, limit: Int = Int.MAX_VALUE): List<Long>`: score(id) = sum over rankings of 1/(k + rank), rank 1-based, an id appearing multiple times within one list counts only at its first position. Output sorted by score desc, ties broken by best (lowest) rank in any list, then by id asc for determinism.
- Optional `weights: List<Float>? = null` param (same size as rankings) multiplying each list's contribution.

## Constraints
- Kotlin idiom and comment density matching surrounding code. No hardcoded UI stuff (no UI in this phase).
- DB/IO methods stay `suspend` in the DAO (Room dispatches off main). Never swallow `CancellationException` (not expected to appear here).
- Do not modify unrelated files. Do not add dependencies.
- Do not run Gradle; the orchestrator runs it.

## Acceptance Criteria
- [ ] `ScreenshotEntity.embedding: ByteArray?` added with content-based equals/hashCode
- [ ] DB version 2, `MIGRATION_1_2` registered, FTS5 table + 3 triggers created both by migration and by `onCreate` callback; no FTS entity declared
- [ ] DAO has `updateEmbedding`, `getAllEmbeddings`, FTS search query
- [ ] `sanitizeFtsQuery` implemented and tested (special chars, empty, prefix on last term)
- [ ] `VectorMath.kt` and `RankFusion.kt` implemented per above
- [ ] Unit tests: byte round-trip, normalization (incl. zero vector), dot/cosine on known values (incl. size not multiple of 4), topK ordering and k > n, RRF ordering/ties/duplicates/weights/limit
- [ ] `.\gradlew.bat testDebugUnitTest` passes (kspDebugKotlin must succeed with the new queries)

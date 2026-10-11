# Task: Collections learn from examples — per-collection classifier (+ debug test hook)

**EXECUTION RULE (hard): Do NOT run Gradle, adb, tests, or ANY long-running/background command. Only read files and edit/create files. The orchestrator builds and tests. Do NOT commit. Finish promptly with a summary.**

Another worker is concurrently editing the UI (onboarding, collections screens, navigation). **Do not touch anything under `ui/`**, `MainActivity.kt`, or `SiftApplication.kt`.

Files you may touch: `data/collections/CollectionClassifier.kt` (new), `data/collections/CollectionMatcher.kt` (only if needed), `data/repository/ScreenshotRepository.kt`, `data/local/ScreenshotDao.kt` (new queries only, no schema change), `app/src/test/java/com/peexr/sift/data/collections/CollectionClassifierTest.kt` (new), and a new debug-only receiver under `app/src/debug/` (see Part 3). No DB schema/version change, no new dependencies, no build-file changes except what Part 3 strictly needs (a debug-source-set manifest is fine; prefer not to touch `build.gradle.kts`).

## Why (measured)
Phase 0 (PC, 962 real screenshots, labels from a large VLM) showed: the current auto-fill (text vector + averaged swipes + z ≥ threshold) scores macro-F1 ≈ 0.25 and puts each screenshot in ~5.5 of 6 collections. An L2-regularised logistic regression per collection, trained on ~10 user-kept examples, scores ≈ 0.6–0.7 and assigns ~0.8 collections per screenshot. The product now asks the user for ~10 examples whenever they create a collection (another worker builds that UI; examples are stored as `MembershipSource.MANUAL` rows via the existing `addToCollection`).

## Part 1 — `data/collections/CollectionClassifier.kt` (pure Kotlin, JVM-testable, no Android imports)
Port this exact recipe (it is what was measured):
- **Model:** logistic regression on the stored embedding vector (any dimension D; today 128) + bias. Loss = class-weighted binary cross-entropy + `0.5 * lambda * ||w||^2` with `lambda = 1.0`, **bias not regularised**. Class weights: positives `N/(2*nPos)`, negatives `N/(2*nNeg)`.
- **Training:** Newton–Raphson (IRLS) with backtracking line search (halve step up to 5 times until loss doesn't increase), max 40 iterations, stop when max |Δθ| < 1e-6. Add 1e-6 to the bias diagonal of the Hessian. Solve the (D+1)×(D+1) system with a small dense Cholesky or Gaussian elimination with partial pivoting written in the file (D ≤ 1024; no libraries). Clamp logits to [-30, 30]. If the solve fails, take a gradient step of 0.05·grad.
- **Degenerate cases:** no positives → model that predicts 0 for everything; no negatives → predicts 1 (but see refresh rules below — we never call it without negatives); identical vectors must not produce NaN.
- **API (suggested):** `data class ClassifierModel(val weights: FloatArray, val bias: Float)`; `fun train(positives: List<FloatArray>, negatives: List<FloatArray>, lambda: Float = 1f): ClassifierModel`; `fun ClassifierModel.probability(x: FloatArray): Float`. Validate that all vectors share one dimension; skip mismatched ones.
- **Negative sampling helper:** `fun sampleBackground(candidateIds: List<Long>, exclude: Set<Long>, count: Int, seed: Long): List<Long>` — deterministic (seeded `java.util.Random`, sort candidates first) so refreshes are stable.

## Part 2 — wire it into `ScreenshotRepository.refreshAutoCollections`
Keep the function signature and its write path (`dao.replaceAutoMembers`, which only replaces AUTO rows — MANUAL and EXCLUDED are never touched). Change only how matches are computed, per auto-fill collection `c`:
- `positives` = vectors of `c`'s MANUAL members.
- If `positives.size >= MIN_EXAMPLES` (const `= 5`): **classifier path.**
  - `negatives` = vectors of `c`'s EXCLUDED members + MANUAL members of **every other** collection (load them even when `onlyCollectionId` is set; skip screenshots that are also MANUAL in `c`) + a seeded random sample of `BACKGROUND_SAMPLE = 5 * positives.size` (min 50, max 200) library screenshots that are not MANUAL/EXCLUDED in `c`. Seed = `c.id.hashCode().toLong()`.
  - Train, then score every screenshot with a valid vector; assign AUTO membership when `p >= threshold`, where threshold comes from the sensitivity setting mapped as STRICT → 0.65, BALANCED → 0.5, LOOSE → 0.35 (add this mapping next to `AutoFillSensitivity`; keep the z thresholds for the fallback path). Store `score = p`.
  - Do not create AUTO rows for screenshots that are MANUAL or EXCLUDED in `c` (check what `replaceAutoMembers`/upsert does today; preserve existing semantics).
- Else: **fallback path** = today's behaviour exactly (text vector + `refineQuery` + z-score).
- Multi-membership stays allowed (no competition between collections) — Phase 0 showed competition hurts overlapping collections like memes+animals.
- Training runs on `Dispatchers.Default`; for ~3,000 screenshots × 10 collections the whole refresh must stay well under a second on a phone — avoid allocations in the scoring loop.
- Add a `Log.i("Sift.Classifier", …)` line per collection: path used (classifier/fallback), #pos, #neg, #assigned, train ms.

## Part 3 — debug-only test hook (for automated device testing; must not ship in release)
Create `app/src/debug/java/com/peexr/sift/debug/DebugCommandReceiver.kt` and `app/src/debug/AndroidManifest.xml` registering it (exported, since it is debug-only; action `com.peexr.sift.DEBUG`). Commands via `--es cmd ...`:
- `create` with `--es name N --es desc D --es files "a.jpg,b.png,..."` → create the collection (existing `createCollection` + `nextCollectionColorHex`-equivalent colour; you may hard-code a palette colour if that helper is in `ui/`), then `addToCollection` for each screenshot whose `displayName` matches, then `refreshAutoCollections()`. Log how many names matched.
- `refresh` → `refreshAutoCollections()`.
- `status` → log counts: screenshots, screenshots with embeddings, collections with #MANUAL/#AUTO/#EXCLUDED each.
Use `goAsync()` + a coroutine; get the repository the same way the rest of the app does (look at `SiftApplication`/ViewModel factories — read-only use, don't modify them). If a DAO query by display name is needed, add it.

## Tests — `CollectionClassifierTest.kt`
- Two separable Gaussian blobs in D=128 (seeded): train on 10 pos / 50 neg, accuracy on 200 held-out > 0.95.
- Matches a reference: for a tiny hand-made 2-D dataset, the trained weights/bias match values you compute in the test with an independent simple gradient-descent implementation to 1e-3 (or check the gradient at the optimum is ~0).
- No positives / identical vectors / mismatched dimensions: no crash, no NaN.
- `sampleBackground` is deterministic for a given seed and never returns excluded ids.

## Done when
Code compiles in your head against existing types (you can't run Gradle), tests written, summary lists every file changed and any assumption you made about `replaceAutoMembers`.

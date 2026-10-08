# Active Task Specification — Phase 2: EmbeddingGemma 2 (ONNX) Integration

Source RFC: `docs/RFC_LOCAL_AI_STACK.md` (Phase 2, adapted: **ONNX Runtime instead of LiteRT**, **model downloaded on demand**, **audio encoder excluded**). Do NOT touch Phase 3/4 (no Laya, no Jev changes, no CleanupScreen/UI changes). Do NOT commit; the orchestrator reviews and commits.

**STATUS: Phase 2 and UI stages 1-2 are committed. Ignore the BACKGROUND REFERENCE sections at the bottom of this file.**

**EXECUTION RULE (hard): Do NOT run Gradle, adb, tests, or ANY long-running/background command. Only read files and edit/create files. The orchestrator builds and tests. Finish your turn promptly with a summary.**

## THIS DISPATCH = Stage 10: release build configuration (signing, R8 shrinking, manifest hardening)

Goal: `./gradlew assembleRelease` produces a signed, shrunk, optimised APK (and `bundleRelease` an AAB). The orchestrator has ALREADY created the signing key OUTSIDE the repo: `C:/Users/systems/.sift-signing/sift-release.jks` with `C:/Users/systems/.sift-signing/keystore.properties` (keys: `storeFile`, `storePassword`, `keyAlias`, `keyPassword`). NEVER copy secrets into the repo; never print passwords.

Read FIRST: `app/build.gradle.kts` (existing `release` and `benchmark` build types, dependencies list), `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/*` if present. Do NOT change dependency versions, compileSdk/targetSdk, or any Kotlin source.

### 1. Signing
In `app/build.gradle.kts` add a `signingConfigs { create("release") { ... } }` that loads the properties file from, in order: env var `SIFT_KEYSTORE_PROPERTIES` (a path), else `${System.getProperty("user.home")}/.sift-signing/keystore.properties`. Use `java.util.Properties` + `FileInputStream`; resolve `storeFile` with `file(...)`. If the properties file does not exist, DO NOT fail the build: leave the release build type unsigned (print a Gradle `logger.warn` once) so other contributors and CI without the key can still configure the project. The `release` build type uses this signing config when it is available. Keep `benchmark` (debug-signed, `initWith(release)`) working: it must NOT inherit the release signing config (re-set its `signingConfig` to debug as it does today) and, since it inherits minify from release, set `isMinifyEnabled = false` and `isShrinkResources = false` explicitly for `benchmark` so profiling builds stay fast to build and readable.

### 2. Shrinking
`release`: `isMinifyEnabled = true`, `isShrinkResources = true`, `proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")`, `isDebuggable = false`. Also `ndk { debugSymbolLevel = "SYMBOL_TABLE" }` is NOT needed; skip. Keep `packaging` as is.

### 3. `app/proguard-rules.pro` (replace the current minimal content; comment each block with why)
Write keep rules for everything that is accessed reflectively/JNI so R8 cannot break the app, based on the actual dependencies in `build.gradle.kts`:
- ONNX Runtime (`ai.onnxruntime.**` — JNI looks up Java classes/fields/methods by name): `-keep class ai.onnxruntime.** { *; }` and `-dontwarn ai.onnxruntime.**` (check if the AAR ships consumer rules; keeping is still safest).
- requery sqlite-android (JNI): `-keep class io.requery.android.database.** { *; }`, `-keep class io.requery.android.sqlite.** { *; }`, `-dontwarn io.requery.**`.
- Room: entities/DAOs/`@Database` classes and generated `_Impl` classes (Room ships consumer rules; add belt-and-braces `-keep class * extends androidx.room.RoomDatabase`, `-keep @androidx.room.Entity class *`, `-keepclassmembers` for `@androidx.room.*`), plus all Room query-result holder data classes in `com.peexr.sift.data.local` (they are populated by generated code by name/constructor): `-keep class com.peexr.sift.data.local.** { *; }`.
- kotlinx.serialization: `-keepattributes *Annotation*, InnerClasses`, `-keep,includedescriptorclasses class com.peexr.sift.**$$serializer { *; }`, `-keepclassmembers class com.peexr.sift.** { *** Companion; }`, `-keepclasseswithmembers class com.peexr.sift.** { kotlinx.serialization.KSerializer serializer(...); }`, and keep `@kotlinx.serialization.Serializable` classes in `com.peexr.sift.data.**` (embedding tokenizer/manifest models, backup file models, Jev models).
- WorkManager workers instantiated by class name: `-keep class * extends androidx.work.ListenableWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }`.
- ML Kit / Play services text recognition and image labeling: `-dontwarn com.google.mlkit.**`, `-keep class com.google.mlkit.** { *; }` only if the libs lack consumer rules (do not over-keep Play services; keep it narrow, `-dontwarn` others).
- Coil 2.x, OkHttp 4.x, Okio, Compose, coroutines: rely on their consumer rules; add only `-dontwarn okhttp3.**`, `-dontwarn okio.**`, `-dontwarn org.conscrypt.**`, `-dontwarn org.bouncycastle.**`, `-dontwarn org.openjsse.**`.
- Keep line numbers for crash reports: `-keepattributes SourceFile,LineNumberTable` and `-renamesourcefileattribute SourceFile`.
- The app's own code that is called only from JNI or reflection (look at `data/embedding/*` for anything touching ONNX tensors by reflection — probably nothing): keep if found.

### 4. Manifest hardening (`AndroidManifest.xml`)
- `android:allowBackup="false"` (screenshot text/embeddings are private and the DB is not meant to be auto-restored to a new device; collections can be exported from Settings). If a `dataExtractionRules`/`fullBackupContent` attribute exists, keep it consistent (exclude everything).
- Ensure `android:usesCleartextTraffic="false"` (the model download is HTTPS) — check the model manifest URLs in `data/embedding/ModelStore.kt` use https; report if any is http.
- Do not add or remove permissions; do not touch exported flags unless something is exported without need — report, don't change, anything suspicious.

### 5. Constraints
- Keep `testDebugUnitTest` / existing tests unaffected (rules apply to release only).
- Do NOT run Gradle or any command; the orchestrator builds, inspects R8's `missing_rules.txt`/`usage.txt` and tests. Do not commit. Finish promptly with a summary listing every file touched and any deviation or anything you could not decide.

---


# ==============================================================================
# Proguard / R8 Rules for Sift
# ==============================================================================

# ------------------------------------------------------------------------------
# 1. Crash Reporting & Stack Traces
# Preserve source file names and line numbers in stack traces for readable crash reports.
# ------------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ------------------------------------------------------------------------------
# 2. General Reflection & Serialization Attributes
# Preserve runtime annotations and inner class relationships required by
# Kotlin reflection, Room, and kotlinx.serialization.
# ------------------------------------------------------------------------------
-keepattributes *Annotation*,InnerClasses,EnclosingMethod

# ------------------------------------------------------------------------------
# 3. ONNX Runtime (ai.onnxruntime.**)
# JNI native code looks up Java classes, constructors, methods, and field offsets
# by exact name and descriptor. Also OrtSession.SessionOptions.addXnnpack is
# invoked reflectively if present. Suppress missing optional native provider warnings.
# ------------------------------------------------------------------------------
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ------------------------------------------------------------------------------
# 4. Requery sqlite-android (io.requery.android.**)
# Bundled SQLite engine and custom SQLiteOpenHelper accessed via native JNI calls.
# ------------------------------------------------------------------------------
-keep class io.requery.android.database.** { *; }
-keep class io.requery.android.sqlite.** { *; }
-dontwarn io.requery.**

# ------------------------------------------------------------------------------
# 5. Room Database
# Room entities, DAOs, Database classes, and generated implementations (_Impl).
# DAOs and query holder data classes in com.peexr.sift.data.local are constructed
# and populated by generated Room code by name and constructor parameters.
# ------------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers class * {
    @androidx.room.* <methods>;
    @androidx.room.* <fields>;
}
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}
-keep class **_Impl { *; }
-keep class com.peexr.sift.data.local.** { *; }

# ------------------------------------------------------------------------------
# 6. kotlinx.serialization
# Keep synthetic serializers, companion objects, and serializer() provider methods
# so R8 does not strip or rename serialization hooks used by JSON decoding/encoding.
# Also keep all @Serializable model classes in com.peexr.sift.data.** (Jev models,
# backup file structures, and manifest/tokenizer data transfer objects).
# ------------------------------------------------------------------------------
-keep,includedescriptorclasses class com.peexr.sift.**$$serializer { *; }
-keepclassmembers class com.peexr.sift.** {
    *** Companion;
}
-keepclasseswithmembers class com.peexr.sift.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class com.peexr.sift.data.** { *; }

# ------------------------------------------------------------------------------
# 7. WorkManager
# Workers are instantiated reflectively by class name using standard 2-arg constructor
# (Context, WorkerParameters) by WorkManager's WorkerFactory.
# ------------------------------------------------------------------------------
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ------------------------------------------------------------------------------
# 8. ML Kit & Google Play Services
# ML Kit libraries include consumer rules for core components. Suppress warnings
# for optional Google Play services dependencies and keep vision API entry points.
# ------------------------------------------------------------------------------
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# ------------------------------------------------------------------------------
# 9. Networking, OkHttp, Okio, & Security Providers
# Coil, Compose, and coroutines rely on their bundled consumer rules.
# Suppress warnings for optional platform security engines (Conscrypt, BouncyCastle,
# OpenJSSE) dynamically probed by OkHttp.
# ------------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

package com.peexr.sift.data.local

import android.content.Context
import android.content.SharedPreferences
import com.peexr.sift.data.collections.AutoFillSensitivity
import com.peexr.sift.data.embedding.IndexingQuality

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("sift_preferences", Context.MODE_PRIVATE)

    var typeSafeApiKey: String?
        get() = prefs.getString("typesafe_api_key", "")
        set(value) = prefs.edit().putString("typesafe_api_key", value).apply()

    var isAutoSyncEnabled: Boolean
        get() = prefs.getBoolean("auto_sync", true)
        set(value) = prefs.edit().putBoolean("auto_sync", value).apply()

    var autoIndexNewScreenshots: Boolean
        get() = prefs.getBoolean("auto_index_new", true)
        set(value) = prefs.edit().putBoolean("auto_index_new", value).apply()

    var autoFillSensitivity: AutoFillSensitivity
        get() {
            val raw = prefs.getString("auto_fill_sensitivity", AutoFillSensitivity.BALANCED.name)
            return try {
                if (raw != null) AutoFillSensitivity.valueOf(raw) else AutoFillSensitivity.BALANCED
            } catch (_: IllegalArgumentException) {
                AutoFillSensitivity.BALANCED
            }
        }
        set(value) = prefs.edit().putString("auto_fill_sensitivity", value.name).apply()

    var indexingQuality: IndexingQuality
        get() {
            val raw = prefs.getString("indexing_quality", IndexingQuality.AUTO.name)
            return try {
                if (raw != null) IndexingQuality.valueOf(raw) else IndexingQuality.AUTO
            } catch (_: IllegalArgumentException) {
                IndexingQuality.AUTO
            }
        }
        set(value) = prefs.edit().putString("indexing_quality", value.name).apply()

    var measuredSecPerImageAt280: Double?
        get() {
            if (!prefs.contains("measured_sec_per_image_280")) return null
            val raw = prefs.getFloat("measured_sec_per_image_280", -1f)
            return if (raw > 0f) raw.toDouble() else null
        }
        set(value) {
            if (value != null && value > 0.0 && value.isFinite()) {
                prefs.edit().putFloat("measured_sec_per_image_280", value.toFloat()).apply()
            } else {
                prefs.edit().remove("measured_sec_per_image_280").apply()
            }
        }

    fun clearMeasurement() {
        prefs.edit().remove("measured_sec_per_image_280").apply()
    }

    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboarding_done", false)
        set(value) = prefs.edit().putBoolean("onboarding_done", value).apply()

    var lastBackupEpochSec: Long?
        get() {
            if (!prefs.contains("last_backup_epoch_sec")) return null
            val value = prefs.getLong("last_backup_epoch_sec", -1L)
            return if (value >= 0L) value else null
        }
        set(value) {
            if (value != null && value >= 0L) {
                prefs.edit().putLong("last_backup_epoch_sec", value).apply()
            } else {
                prefs.edit().remove("last_backup_epoch_sec").apply()
            }
        }
}

package com.peexr.sift.data.local

import android.content.Context
import android.content.SharedPreferences
import com.peexr.sift.data.collections.AutoFillSensitivity

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

    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboarding_done", false)
        set(value) = prefs.edit().putBoolean("onboarding_done", value).apply()
}

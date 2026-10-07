package com.peexr.sift.data.local

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("sift_preferences", Context.MODE_PRIVATE)

    var typeSafeApiKey: String?
        get() = prefs.getString("typesafe_api_key", "")
        set(value) = prefs.edit().putString("typesafe_api_key", value).apply()

    var isAutoSyncEnabled: Boolean
        get() = prefs.getBoolean("auto_sync", true)
        set(value) = prefs.edit().putBoolean("auto_sync", value).apply()
}

package com.peexr.sift

import android.app.Application
import com.peexr.sift.data.api.JevApiClient
import com.peexr.sift.data.local.AppDatabase
import com.peexr.sift.data.local.SettingsManager
import com.peexr.sift.data.perception.PerceptionEngine
import com.peexr.sift.data.repository.ScreenshotRepository

class SiftApplication : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var settingsManager: SettingsManager
        private set

    lateinit var perceptionEngine: PerceptionEngine
        private set

    lateinit var jevApiClient: JevApiClient
        private set

    lateinit var repository: ScreenshotRepository
        private set

    override fun onCreate() {
        super.onCreate()

        database = AppDatabase.getDatabase(this)
        settingsManager = SettingsManager(this)
        perceptionEngine = PerceptionEngine(this)

        jevApiClient = JevApiClient(
            apiKeyProvider = { settingsManager.typeSafeApiKey }
        )

        repository = ScreenshotRepository(
            context = this,
            database = database,
            perceptionEngine = perceptionEngine,
            jevApiClient = jevApiClient
        )
    }

    override fun onTerminate() {
        super.onTerminate()
        perceptionEngine.close()
    }
}

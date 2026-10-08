package com.peexr.sift.ui.settings

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.peexr.sift.SiftApplication
import com.peexr.sift.data.collections.AutoFillSensitivity
import com.peexr.sift.data.embedding.EmbeddingScheduler
import com.peexr.sift.data.embedding.IndexingQuality
import com.peexr.sift.data.embedding.ModelState
import com.peexr.sift.data.repository.LibraryStats
import com.peexr.sift.data.repository.ScreenshotRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.content.pm.PackageManager
import android.os.Build
import com.peexr.sift.data.backup.BackupSettings
import com.peexr.sift.data.backup.FullBackup
import com.peexr.sift.data.backup.backupReminderText
import com.peexr.sift.data.backup.decodeFullBackup
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.withContext

data class RestoreConfirmDialogState(
    val screenshotCount: Int,
    val collectionCount: Int,
    val dateText: String
)

data class SettingsUiState(
    val modelState: ModelState = ModelState.NotInstalled,
    val isModelInstalled: Boolean = false,
    val autoIndexNewScreenshots: Boolean = true,
    val libraryStats: LibraryStats = LibraryStats(total = 0, indexed = 0),
    val autoFillSensitivity: AutoFillSensitivity = AutoFillSensitivity.BALANCED,
    val indexingQuality: IndexingQuality = IndexingQuality.AUTO,
    val measuredSecPerImageAt280: Double? = null,
    val isOperating: Boolean = false,
    val lastBackupEpochSec: Long? = null,
    val backupReminder: String? = null,
    val restoreConfirm: RestoreConfirmDialogState? = null
)

class SettingsViewModel(
    private val repository: ScreenshotRepository,
    private val app: SiftApplication
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            modelState = app.modelStore.state.value,
            isModelInstalled = app.modelStore.isInstalled(),
            autoIndexNewScreenshots = app.settingsManager.autoIndexNewScreenshots,
            autoFillSensitivity = app.settingsManager.autoFillSensitivity,
            indexingQuality = app.settingsManager.indexingQuality,
            measuredSecPerImageAt280 = app.settingsManager.measuredSecPerImageAt280,
            lastBackupEpochSec = app.settingsManager.lastBackupEpochSec,
            backupReminder = backupReminderText(
                lastBackupEpochSec = app.settingsManager.lastBackupEpochSec,
                nowEpochSec = System.currentTimeMillis() / 1000L,
                indexedCount = 0
            )
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _snackbarEvent = MutableSharedFlow<String>()
    val snackbarEvent: SharedFlow<String> = _snackbarEvent.asSharedFlow()

    init {
        viewModelScope.launch {
            app.modelStore.state.collect { state ->
                val installed = app.modelStore.isInstalled()
                _uiState.update { it.copy(modelState = state, isModelInstalled = installed) }
            }
        }
        refreshStats()
    }

    fun refreshStats() {
        viewModelScope.launch {
            try {
                val stats = repository.libraryStats()
                val measured = app.settingsManager.measuredSecPerImageAt280
                val lastBackup = app.settingsManager.lastBackupEpochSec
                val now = System.currentTimeMillis() / 1000L
                val reminder = backupReminderText(lastBackup, now, stats.indexed)
                _uiState.update {
                    it.copy(
                        libraryStats = stats,
                        measuredSecPerImageAt280 = measured,
                        lastBackupEpochSec = lastBackup,
                        backupReminder = reminder
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e("SettingsViewModel", "Failed to load library stats", t)
            }
        }
    }

    fun startModelDownload() {
        app.startModelDownload()
    }

    fun removeModel() {
        app.modelStore.deleteModel()
        _uiState.update {
            it.copy(
                modelState = ModelState.NotInstalled,
                isModelInstalled = false
            )
        }
    }

    fun setAutoIndexEnabled(enabled: Boolean) {
        app.settingsManager.autoIndexNewScreenshots = enabled
        EmbeddingScheduler.syncAutoIndexSchedule(app, enabled)
        _uiState.update { it.copy(autoIndexNewScreenshots = enabled) }
    }

    fun setIndexingQuality(quality: IndexingQuality) {
        app.settingsManager.indexingQuality = quality
        _uiState.update { it.copy(indexingQuality = quality) }
    }

    fun indexNow() {
        EmbeddingScheduler.enqueueIndexingNow(app)
        refreshStats()
    }

    fun reindexEverything() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isOperating = true) }
                repository.reindexEverything()
                EmbeddingScheduler.enqueueIndexingNow(app)
                refreshStats()
                _snackbarEvent.emit("Re-indexing started")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e("SettingsViewModel", "Failed to reindex everything", t)
                _snackbarEvent.emit("Failed to re-index")
            } finally {
                _uiState.update { it.copy(isOperating = false) }
            }
        }
    }

    fun setAutoFillSensitivity(sensitivity: AutoFillSensitivity) {
        app.settingsManager.autoFillSensitivity = sensitivity
        _uiState.update { it.copy(autoFillSensitivity = sensitivity) }
        viewModelScope.launch {
            _snackbarEvent.emit("Updating collections…")
        }
        app.appScope.launch {
            try {
                repository.refreshAutoCollections()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e("SettingsViewModel", "Failed to refresh auto collections on sensitivity change", t)
            }
        }
    }

    fun exportCollectionsToUri(uri: Uri) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isOperating = true) }
                val json = repository.exportCollections()
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(json.toByteArray(Charsets.UTF_8))
                        os.flush()
                    } ?: error("Failed to open output stream for export")
                }
                _snackbarEvent.emit("Collections exported")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e("SettingsViewModel", "Export failed", t)
                _snackbarEvent.emit("Export failed")
            } finally {
                _uiState.update { it.copy(isOperating = false) }
            }
        }
    }

    fun importCollectionsFromUri(uri: Uri) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isOperating = true) }
                val json = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { inputStream ->
                        inputStream.bufferedReader(Charsets.UTF_8).readText()
                    } ?: error("Failed to open input stream for import")
                }
                val result = repository.importCollections(json)
                result.fold(
                    onSuccess = { summary ->
                        val msg = "Created ${summary.collectionsCreated}, skipped ${summary.collectionsSkipped}, restored ${summary.membershipsApplied} entries"
                        _snackbarEvent.emit(msg)
                        refreshStats()
                    },
                    onFailure = {
                        _snackbarEvent.emit("That file isn't a Sift backup")
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e("SettingsViewModel", "Import failed", t)
                _snackbarEvent.emit("That file isn't a Sift backup")
            } finally {
                _uiState.update { it.copy(isOperating = false) }
            }
        }
    }

    private fun getAppVersion(): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                app.packageManager.getPackageInfo(app.packageName, 0)
            }
            packageInfo.versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    private var pendingRestoreBytes: ByteArray? = null
    private var pendingRestoreBackup: FullBackup? = null

    fun backupToUri(uri: Uri) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isOperating = true) }
                val nowEpochSec = System.currentTimeMillis() / 1000L
                val settings = BackupSettings(
                    autoFillSensitivity = app.settingsManager.autoFillSensitivity.name,
                    indexingQuality = app.settingsManager.indexingQuality.name,
                    autoIndexNewScreenshots = app.settingsManager.autoIndexNewScreenshots,
                    measuredSecPerImageAt280 = app.settingsManager.measuredSecPerImageAt280
                )
                val bytes = repository.createFullBackup(
                    appVersion = getAppVersion(),
                    settings = settings
                )
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(bytes)
                        os.flush()
                    } ?: error("Failed to open output stream for backup")
                }
                app.settingsManager.lastBackupEpochSec = nowEpochSec
                val stats = repository.libraryStats()
                val reminder = backupReminderText(nowEpochSec, nowEpochSec, stats.indexed)
                _uiState.update {
                    it.copy(
                        lastBackupEpochSec = nowEpochSec,
                        backupReminder = reminder
                    )
                }
                _snackbarEvent.emit("Backup saved")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e("SettingsViewModel", "Backup failed", t)
                _snackbarEvent.emit("Backup failed")
            } finally {
                _uiState.update { it.copy(isOperating = false) }
            }
        }
    }

    fun prepareRestoreFromUri(uri: Uri) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isOperating = true) }
                val bytes = withContext(Dispatchers.IO) {
                    try {
                        app.contentResolver.openInputStream(uri)?.use { inputStream ->
                            inputStream.readBytes()
                        } ?: error("Couldn't read that file")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        throw IllegalArgumentException("Couldn't read that file", e)
                    }
                }
                val decodeResult = decodeFullBackup(bytes)
                if (decodeResult.isFailure) {
                    _snackbarEvent.emit("That file isn't a Sift backup")
                    return@launch
                }
                val backup = decodeResult.getOrThrow()
                pendingRestoreBytes = bytes
                pendingRestoreBackup = backup

                val dateStr = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                    .format(Date(backup.createdAtEpochSec * 1000L))
                _uiState.update {
                    it.copy(
                        restoreConfirm = RestoreConfirmDialogState(
                            screenshotCount = backup.screenshots.size,
                            collectionCount = backup.collections.size,
                            dateText = dateStr
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e("SettingsViewModel", "Prepare restore failed", t)
                val msg = if (t.message == "Couldn't read that file") {
                    "Couldn't read that file"
                } else {
                    "That file isn't a Sift backup"
                }
                _snackbarEvent.emit(msg)
            } finally {
                _uiState.update { it.copy(isOperating = false) }
            }
        }
    }

    fun dismissRestoreConfirm() {
        pendingRestoreBytes = null
        pendingRestoreBackup = null
        _uiState.update { it.copy(restoreConfirm = null) }
    }

    fun confirmRestore() {
        val bytes = pendingRestoreBytes ?: return
        val backup = pendingRestoreBackup ?: return
        pendingRestoreBytes = null
        pendingRestoreBackup = null

        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isOperating = true, restoreConfirm = null) }
                val result = repository.restoreFullBackup(bytes)
                result.fold(
                    onSuccess = { summary ->
                        val backupSettings = backup.settings
                        val restoredSensitivity = try {
                            AutoFillSensitivity.valueOf(backupSettings.autoFillSensitivity)
                        } catch (_: Exception) {
                            AutoFillSensitivity.BALANCED
                        }
                        app.settingsManager.autoFillSensitivity = restoredSensitivity

                        val restoredQuality = try {
                            IndexingQuality.valueOf(backupSettings.indexingQuality)
                        } catch (_: Exception) {
                            IndexingQuality.AUTO
                        }
                        app.settingsManager.indexingQuality = restoredQuality

                        app.settingsManager.autoIndexNewScreenshots = backupSettings.autoIndexNewScreenshots
                        EmbeddingScheduler.syncAutoIndexSchedule(app, backupSettings.autoIndexNewScreenshots)

                        if (app.settingsManager.measuredSecPerImageAt280 == null && backupSettings.measuredSecPerImageAt280 != null) {
                            app.settingsManager.measuredSecPerImageAt280 = backupSettings.measuredSecPerImageAt280
                        }

                        // The screen shows a copy of the settings: refresh it so it matches what was just restored.
                        _uiState.update {
                            it.copy(
                                autoFillSensitivity = app.settingsManager.autoFillSensitivity,
                                indexingQuality = app.settingsManager.indexingQuality,
                                autoIndexNewScreenshots = app.settingsManager.autoIndexNewScreenshots,
                                measuredSecPerImageAt280 = app.settingsManager.measuredSecPerImageAt280
                            )
                        }

                        val stats = repository.libraryStats()
                        val pending = stats.total - stats.indexed
                        if (pending > 0 && app.modelStore.isInstalled()) {
                            EmbeddingScheduler.enqueueIndexingNow(app, replace = false)
                        }

                        val totalCollections = summary.collectionsCreated + summary.collectionsReused
                        val formattedMatched = NumberFormat.getIntegerInstance().format(summary.screenshotsMatched)
                        val unmatchedMsg = if (summary.screenshotsUnmatched > 0) {
                            " ${NumberFormat.getIntegerInstance().format(summary.screenshotsUnmatched)} screenshots in the backup weren't found on this phone."
                        } else {
                            ""
                        }
                        val msg = "Restored $formattedMatched screenshots, $totalCollections ${if (totalCollections == 1) "collection" else "collections"}.$unmatchedMsg"
                        _snackbarEvent.emit(msg)
                        refreshStats()
                    },
                    onFailure = { t ->
                        Log.e("SettingsViewModel", "Restore failed", t)
                        _snackbarEvent.emit("That file isn't a Sift backup")
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e("SettingsViewModel", "Restore failed", t)
                _snackbarEvent.emit("That file isn't a Sift backup")
            } finally {
                _uiState.update { it.copy(isOperating = false) }
            }
        }
    }

    class Factory(
        private val repository: ScreenshotRepository,
        private val app: SiftApplication
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(repository, app) as T
        }
    }
}

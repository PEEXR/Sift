package com.peexr.sift.ui.settings

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.peexr.sift.SiftApplication
import com.peexr.sift.data.collections.AutoFillSensitivity
import com.peexr.sift.data.embedding.EmbeddingScheduler
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
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val modelState: ModelState = ModelState.NotInstalled,
    val isModelInstalled: Boolean = false,
    val autoIndexNewScreenshots: Boolean = true,
    val libraryStats: LibraryStats = LibraryStats(total = 0, indexed = 0),
    val autoFillSensitivity: AutoFillSensitivity = AutoFillSensitivity.BALANCED,
    val isOperating: Boolean = false
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
            autoFillSensitivity = app.settingsManager.autoFillSensitivity
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
                _uiState.update { it.copy(libraryStats = stats) }
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

package com.peexr.sift.ui.cleanup

import android.app.PendingIntent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.peexr.sift.data.model.ScreenshotItem
import com.peexr.sift.data.repository.ScreenshotRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CleanupUiState(
    val junkScreenshots: List<ScreenshotItem> = emptyList(),
    val junkCount: Int = 0,
    val recoverableBytes: Long = 0L,
    val isScanning: Boolean = false,
    val scanProgress: Float = 0f,
    val promptDirective: String = "",
    val selectedIds: Set<Long> = emptySet(),
    val deletePendingIntent: PendingIntent? = null,
    val statusMessage: String? = null
)

private data class ActionState(
    val directive: String,
    val selectedIds: Set<Long>,
    val deleteIntent: PendingIntent?,
    val statusMessage: String?
)

class CleanupViewModel(
    private val repository: ScreenshotRepository
) : ViewModel() {

    private val _isScanning = MutableStateFlow(false)
    private val _scanProgress = MutableStateFlow(0f)
    private val _promptDirective = MutableStateFlow("")
    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _deletePendingIntent = MutableStateFlow<PendingIntent?>(null)
    private val _statusMessage = MutableStateFlow<String?>(null)

    private val scanStateFlow = combine(_isScanning, _scanProgress) { scanning, progress ->
        Pair(scanning, progress)
    }

    private val actionStateFlow = combine(
        _promptDirective,
        _selectedIds,
        _deletePendingIntent,
        _statusMessage
    ) { directive, selected, intent, msg ->
        ActionState(directive, selected, intent, msg)
    }

    private val dataStateFlow = combine(
        repository.junkScreenshotsFlow,
        repository.junkCountFlow,
        repository.recoverableBytesFlow
    ) { junkList, count, bytes ->
        Triple(junkList, count, bytes)
    }

    val uiState: StateFlow<CleanupUiState> = combine(
        dataStateFlow,
        scanStateFlow,
        actionStateFlow
    ) { (junkList, count, bytes), (scanning, progress), action ->
        CleanupUiState(
            junkScreenshots = junkList,
            junkCount = count,
            recoverableBytes = bytes,
            isScanning = scanning,
            scanProgress = progress,
            promptDirective = action.directive,
            selectedIds = if (action.selectedIds.isEmpty()) junkList.map { it.id }.toSet() else action.selectedIds,
            deletePendingIntent = action.deleteIntent,
            statusMessage = action.statusMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CleanupUiState()
    )

    fun updateDirective(directive: String) {
        _promptDirective.value = directive
    }

    fun executePurgeScan(directive: String? = null) {
        val query = directive ?: _promptDirective.value
        if (query.isBlank()) {
            _statusMessage.value = "Please enter a directive to search for junk (e.g. 'delete all motivational quotes')"
            return
        }

        viewModelScope.launch {
            _isScanning.value = true
            _scanProgress.value = 0f
            _statusMessage.value = "Jev evaluating screenshots against: '$query'..."

            val result = repository.evaluateCustomPurgeDirective(query) { current, total ->
                _scanProgress.value = if (total > 0) current.toFloat() / total else 0f
            }

            if (result.isSuccess) {
                val matches = result.getOrNull() ?: 0
                _statusMessage.value = "Scan complete: found $matches junk screenshots"
            } else {
                _statusMessage.value = "Jev error: ${result.exceptionOrNull()?.message}"
            }

            _isScanning.value = false
        }
    }

    fun toggleSelection(id: Long) {
        val current = _selectedIds.value.toMutableSet()
        if (current.contains(id)) {
            current.remove(id)
        } else {
            current.add(id)
        }
        _selectedIds.value = current
    }

    fun selectAll() {
        _selectedIds.value = uiState.value.junkScreenshots.map { it.id }.toSet()
    }

    fun deselectAll() {
        _selectedIds.value = emptySet()
    }

    fun requestDeleteSelected() {
        val itemsToDelete = uiState.value.junkScreenshots.filter { uiState.value.selectedIds.contains(it.id) }
        if (itemsToDelete.isEmpty()) return

        val uris = itemsToDelete.map { Uri.parse(it.uri) }
        val intent = repository.createDeletePendingIntent(uris)
        if (intent != null) {
            _deletePendingIntent.value = intent
        } else {
            // Android 10 or lower direct delete
            viewModelScope.launch {
                repository.removeDeletedScreenshots(itemsToDelete.map { it.id })
                _statusMessage.value = "Purged ${itemsToDelete.size} screenshots"
            }
        }
    }

    fun onDeleteConfirmed() {
        val selected = _selectedIds.value.toList()
        viewModelScope.launch {
            repository.removeDeletedScreenshots(selected)
            _selectedIds.value = emptySet()
            _deletePendingIntent.value = null
            _statusMessage.value = "Successfully purged ${selected.size} screenshots"
        }
    }

    fun clearDeleteIntent() {
        _deletePendingIntent.value = null
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    class Factory(private val repository: ScreenshotRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CleanupViewModel(repository) as T
        }
    }
}

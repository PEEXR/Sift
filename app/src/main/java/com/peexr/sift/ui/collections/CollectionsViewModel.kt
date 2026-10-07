package com.peexr.sift.ui.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.peexr.sift.data.local.CollectionEntity
import com.peexr.sift.data.model.ScreenshotItem
import com.peexr.sift.data.repository.ScreenshotRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CollectionsUiState(
    val screenshots: List<ScreenshotItem> = emptyList(),
    val collections: List<CollectionEntity> = emptyList(),
    val selectedCollectionId: String? = null,
    val searchQuery: String = "",
    val isSyncing: Boolean = false,
    val isClassifying: Boolean = false,
    val statusMessage: String? = null
)

class CollectionsViewModel(
    private val repository: ScreenshotRepository
) : ViewModel() {

    private val _selectedCollectionId = MutableStateFlow<String?>(null)
    private val _searchQuery = MutableStateFlow("")
    private val _isSyncing = MutableStateFlow(false)
    private val _isClassifying = MutableStateFlow(false)
    private val _statusMessage = MutableStateFlow<String?>(null)

    private val filterFlow = combine(_selectedCollectionId, _searchQuery) { col, query ->
        Pair(col, query)
    }

    private val statusFlow = combine(_isSyncing, _isClassifying, _statusMessage) { syncing, classifying, msg ->
        Triple(syncing, classifying, msg)
    }

    val uiState: StateFlow<CollectionsUiState> = combine(
        repository.allScreenshotsFlow,
        repository.allCollectionsFlow,
        filterFlow,
        statusFlow
    ) { screenshots, collections, filter, status ->
        val (selectedCol, query) = filter
        val (syncing, classifying, msg) = status

        val filtered = screenshots.filter { item ->
            val matchesCollection = if (selectedCol == null) true else {
                val colName = collections.find { it.id == selectedCol }?.name
                colName != null && item.collections.contains(colName)
            }
            val matchesQuery = if (query.isBlank()) true else {
                val q = query.lowercase()
                item.displayName.lowercase().contains(q) ||
                item.ocrText.lowercase().contains(q) ||
                item.visualLabels.any { it.contains(q) } ||
                item.collections.any { it.lowercase().contains(q) }
            }
            matchesCollection && matchesQuery
        }

        CollectionsUiState(
            screenshots = filtered,
            collections = collections,
            selectedCollectionId = selectedCol,
            searchQuery = query,
            isSyncing = syncing,
            isClassifying = classifying,
            statusMessage = msg
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CollectionsUiState()
    )

    fun syncScreenshots() {
        viewModelScope.launch {
            _isSyncing.value = true
            _statusMessage.value = "Scanning gallery..."
            val count = repository.syncMediaStoreScreenshots()
            _statusMessage.value = "Found $count screenshots"
            _isSyncing.value = false
        }
    }

    fun selectCollection(collectionId: String?) {
        _selectedCollectionId.value = collectionId
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun createCollection(name: String, description: String, colorHex: String = "#6750A4") {
        viewModelScope.launch {
            repository.createCollection(name, description, colorHex)
            _statusMessage.value = "Created collection '$name'"
        }
    }

    fun classifyAllWithJev() {
        viewModelScope.launch {
            _isClassifying.value = true
            _statusMessage.value = "Classifying screenshots with Jev..."
            val screenshots = uiState.value.screenshots
            var successCount = 0

            screenshots.forEach { item ->
                val result = repository.classifyScreenshotWithJev(item.id)
                if (result.isSuccess) successCount++
            }

            _statusMessage.value = "Classified $successCount screenshots with Jev"
            _isClassifying.value = false
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    class Factory(private val repository: ScreenshotRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CollectionsViewModel(repository) as T
        }
    }
}

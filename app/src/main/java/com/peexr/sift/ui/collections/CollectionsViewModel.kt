package com.peexr.sift.ui.collections

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.peexr.sift.data.model.CollectionSummary
import com.peexr.sift.data.repository.ScreenshotRepository
import com.peexr.sift.ui.theme.CollectionPaletteHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Determines the next round-robin collection palette color hex based on existing collection count.
 */
fun nextCollectionColorHex(existingCount: Int): String {
    val size = CollectionPaletteHex.size
    val index = ((existingCount % size) + size) % size
    return CollectionPaletteHex[index]
}

data class CollectionsUiState(
    val collections: List<CollectionSummary> = emptyList(),
    val statusMessage: String? = null
)


class CollectionsViewModel(
    private val repository: ScreenshotRepository
) : ViewModel() {

    private val _statusMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<CollectionsUiState> = combine(
        repository.collectionSummariesFlow,
        _statusMessage
    ) { collections, statusMessage ->
        CollectionsUiState(
            collections = collections,
            statusMessage = statusMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CollectionsUiState()
    )

    fun createCollection(name: String, description: String) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) return

        val finalDescription = description.trim().ifBlank { trimmedName }
        val currentCount = uiState.value.collections.size
        val colorHex = nextCollectionColorHex(currentCount)

        viewModelScope.launch {
            try {
                repository.createCollection(
                    name = trimmedName,
                    description = finalDescription,
                    colorHex = colorHex
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e("CollectionsViewModel", "Failed to create collection", t)
                _statusMessage.value = "Failed to create collection"
            }
        }
    }

    fun deleteCollection(id: String) {
        viewModelScope.launch {
            try {
                repository.deleteCollection(id)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e("CollectionsViewModel", "Failed to delete collection", t)
                _statusMessage.value = "Failed to delete collection"
            }
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

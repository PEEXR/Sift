package com.peexr.sift.ui.review

import android.app.PendingIntent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.peexr.sift.data.model.CollectionSummary
import com.peexr.sift.data.model.ScreenshotItem
import com.peexr.sift.data.repository.ScreenshotRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI State for the Review (swipe) screen.
 */
data class ReviewUiState(
    val mode: ReviewMode = ReviewMode.Picker,
    val stack: List<ReviewCard> = emptyList(),
    val index: Int = 0,
    val kept: Int = 0,
    val removed: Int = 0,
    val deleted: Int = 0,
    val marked: Set<Long> = emptySet(),
    val phase: ReviewPhase = ReviewPhase.Swiping,
    val canUndo: Boolean = false,
    val collections: List<CollectionSummary> = emptyList(),
    val query: String = "",
    val statusToast: String? = null,
    val deletePendingIntent: PendingIntent? = null,
    val markedTotalBytes: Long = 0L,
    val showMarkRemaining: Boolean = false
)

class ReviewViewModel(
    private val repository: ScreenshotRepository
) : ViewModel() {

    private val _session = MutableStateFlow(ReviewSession())
    private val _statusToast = MutableStateFlow<String?>(null)
    private val _deletePendingIntent = MutableStateFlow<PendingIntent?>(null)
    private var pendingSingleDeleteId: Long? = null
    private var searchJob: Job? = null
    private var toastJob: Job? = null

    init {
        viewModelScope.launch {
            repository.collectionSummariesFlow.collect { collections ->
                _session.value = _session.value.apply(ReviewAction.SetCollections(collections))
            }
        }
    }

    val uiState: StateFlow<ReviewUiState> = combine(
        _session,
        _statusToast,
        _deletePendingIntent
    ) { session, toast, pendingIntent ->
        val markedBytes = session.stack
            .filter { session.marked.contains(it.item.id) }
            .sumOf { it.item.sizeBytes }

        ReviewUiState(
            mode = session.mode,
            stack = session.stack,
            index = session.index,
            kept = session.kept,
            removed = session.removed,
            deleted = session.deleted,
            marked = session.marked,
            phase = session.phase,
            canUndo = session.canUndo,
            collections = session.collections,
            query = session.query,
            statusToast = toast,
            deletePendingIntent = pendingIntent,
            markedTotalBytes = markedBytes,
            showMarkRemaining = session.showMarkRemaining
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ReviewUiState()
    )

    private fun dispatch(action: ReviewAction) {
        _session.value = _session.value.apply(action)
    }

    private fun showToast(message: String) {
        toastJob?.cancel()
        _statusToast.value = message
        toastJob = viewModelScope.launch {
            delay(2400)
            _statusToast.value = null
        }
    }

    fun openCollection(collectionId: String) {
        viewModelScope.launch {
            try {
                val queue = repository.getReviewQueue(collectionId)
                val cards = queue.map { row ->
                    ReviewCard(
                        item = ScreenshotItem(
                            id = row.screenshot.id,
                            uri = row.screenshot.uri,
                            displayName = row.screenshot.displayName,
                            dateAdded = row.screenshot.dateAdded,
                            sizeBytes = row.screenshot.sizeBytes,
                            ocrText = row.screenshot.ocrText,
                            visualLabels = row.screenshot.visualLabels.split(",").filter { it.isNotBlank() },
                            isProcessed = row.screenshot.isProcessed,
                            isJunk = row.screenshot.isJunk,
                            junkConfidence = row.screenshot.junkConfidence,
                            junkRationale = row.screenshot.junkRationale
                        ),
                        confidenceLabel = "Unsure",
                        originalScore = row.membershipScore,
                        isStrong = false
                    )
                }
                val colName = _session.value.collections.firstOrNull { it.id == collectionId }?.name ?: "Collection"
                dispatch(ReviewAction.StartCollection(id = collectionId, name = colName, cards = cards))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e("ReviewViewModel", "Failed to load review queue for $collectionId", t)
            }
        }
    }

    fun submitSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return

        searchJob?.cancel()
        dispatch(ReviewAction.SetQuery(trimmed))
        dispatch(ReviewAction.SetLoading)

        searchJob = viewModelScope.launch {
            try {
                val entities = repository.hybridSearch(trimmed, limit = 60)
                val items = repository.mapToDomainModel(entities)
                val searchStack = splitSearchResults(items)
                dispatch(ReviewAction.StartSearch(query = trimmed, searchStack = searchStack))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e("ReviewViewModel", "Search failed for '$trimmed'", t)
                dispatch(ReviewAction.StartSearch(query = trimmed, searchStack = SearchStack(emptyList())))
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        dispatch(ReviewAction.ClearSearch)
    }

    fun keep() {
        val session = _session.value
        val current = session.currentCard ?: return
        val mode = session.mode

        if (mode is ReviewMode.Collection) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    repository.keepInCollection(current.item.id, mode.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e("ReviewViewModel", "Failed to keep in collection", t)
                }
            }
            showToast("Kept")
        } else {
            showToast("Kept")
        }
        dispatch(ReviewAction.Keep)
    }

    fun removeOrMark() {
        val session = _session.value
        val current = session.currentCard ?: return
        val mode = session.mode

        if (mode is ReviewMode.Collection) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    repository.removeFromCollection(current.item.id, mode.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e("ReviewViewModel", "Failed to remove from collection", t)
                }
            }
            showToast("Removed from ${mode.name}")
        } else {
            showToast("Marked for deletion")
        }
        dispatch(ReviewAction.RemoveOrMark)
    }

    fun markRemaining() {
        dispatch(ReviewAction.MarkRemaining)
        showToast("Marked remaining for deletion")
    }

    fun undo() {
        val session = _session.value
        val lastStep = session.history.lastOrNull() ?: return
        val mode = session.mode

        if (mode is ReviewMode.Collection) {
            val card = lastStep.card
            if (card != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        repository.restoreAutoMembership(card.item.id, mode.id, card.originalScore)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.e("ReviewViewModel", "Failed to restore auto membership", t)
                    }
                }
            }
        }
        dispatch(ReviewAction.Undo)
        _statusToast.value = null
    }

    fun deleteCurrentFile() {
        val session = _session.value
        val current = session.currentCard ?: return

        pendingSingleDeleteId = current.item.id
        val uri = Uri.parse(current.item.uri)
        val pendingIntent = repository.createDeletePendingIntent(listOf(uri))
        if (pendingIntent != null) {
            _deletePendingIntent.value = pendingIntent
        } else {
            // Android 10 or lower direct delete
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    repository.removeDeletedScreenshots(listOf(current.item.id))
                    withContext(Dispatchers.Main) {
                        dispatch(ReviewAction.FileDeleted(current.item.id, current.item.sizeBytes))
                        showToast("File deleted")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e("ReviewViewModel", "Failed to delete file directly", t)
                }
            }
        }
    }

    fun requestDeleteMarked() {
        val session = _session.value
        val markedIds = session.marked
        if (markedIds.isEmpty()) return

        val markedCards = session.stack.filter { markedIds.contains(it.item.id) }
        val uris = markedCards.map { Uri.parse(it.item.uri) }
        val pendingIntent = repository.createDeletePendingIntent(uris)
        if (pendingIntent != null) {
            _deletePendingIntent.value = pendingIntent
        } else {
            onFilesDeleted(markedIds)
        }
    }

    fun onFilesDeleted(ids: Set<Long>) {
        _deletePendingIntent.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.removeDeletedScreenshots(ids.toList())
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e("ReviewViewModel", "Failed to delete screenshots from repository", t)
            }
            withContext(Dispatchers.Main) {
                val session = _session.value
                if (session.mode is ReviewMode.Collection) {
                    val id = ids.firstOrNull() ?: pendingSingleDeleteId ?: 0L
                    val card = session.stack.firstOrNull { it.item.id == id }
                    dispatch(ReviewAction.FileDeleted(id, card?.item?.sizeBytes ?: 0L))
                    pendingSingleDeleteId = null
                    showToast("File deleted")
                } else {
                    val totalBytes = session.stack
                        .filter { ids.contains(it.item.id) }
                        .sumOf { it.item.sizeBytes }
                    dispatch(ReviewAction.FilesDeleted(ids, totalBytes))
                }
            }
        }
    }

    fun onDeleteCancelled() {
        _deletePendingIntent.value = null
        pendingSingleDeleteId = null
        dispatch(ReviewAction.DeleteCancelled)
    }

    fun clearMarks() {
        dispatch(ReviewAction.ClearMarks)
    }

    fun restart() {
        dispatch(ReviewAction.Restart)
    }

    fun back() {
        dispatch(ReviewAction.Back)
    }

    fun clearStatusToast() {
        _statusToast.value = null
    }

    class Factory(
        private val repository: ScreenshotRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ReviewViewModel(repository) as T
        }
    }
}

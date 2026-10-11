package com.peexr.sift.ui.review

import android.app.PendingIntent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.peexr.sift.data.cleanup.AgeFilter
import com.peexr.sift.data.cleanup.CodeKind
import com.peexr.sift.data.cleanup.isOlderThan
import com.peexr.sift.data.model.CollectionSummary
import com.peexr.sift.data.model.ScreenshotItem
import com.peexr.sift.data.repository.ScreenshotRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    val showMarkRemaining: Boolean = false,
    val ageFilter: AgeFilter = AgeFilter.ANY,
    val loadingCleanupPreset: CleanupPreset? = null
)

class ReviewViewModel(
    private val repository: ScreenshotRepository,
    private val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : ViewModel() {

    private val _session = MutableStateFlow(ReviewSession())
    private val _statusToast = MutableStateFlow<String?>(null)
    private val _deletePendingIntent = MutableStateFlow<PendingIntent?>(null)
    private val _ageFilter = MutableStateFlow(AgeFilter.ANY)
    private val _loadingCleanupPreset = MutableStateFlow<CleanupPreset?>(null)
    private var pendingSingleDeleteId: Long? = null
    private var searchJob: Job? = null
    private var cleanupJob: Job? = null
    private var toastJob: Job? = null
    private var collectionRefreshedForCurrentSession = false

    private fun triggerCollectionRefreshIfNeeded() {
        val session = _session.value
        val mode = session.mode
        val collectionId = when (mode) {
            is ReviewMode.Collection -> mode.id
            is ReviewMode.Suggestions -> mode.id
            else -> null
        }
        if (collectionId != null && !collectionRefreshedForCurrentSession) {
            if (shouldRefreshAfterSession(session.kept, session.removed)) {
                collectionRefreshedForCurrentSession = true
                appScope.launch {
                    try {
                        repository.refreshAutoCollections(onlyCollectionId = collectionId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.e("ReviewViewModel", "Failed to refresh auto collection $collectionId after session", t)
                    }
                }
            }
        }
    }

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
        _deletePendingIntent,
        _ageFilter,
        _loadingCleanupPreset
    ) { session, toast, pendingIntent, ageFilter, loadingPreset ->
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
            showMarkRemaining = session.showMarkRemaining,
            ageFilter = ageFilter,
            loadingCleanupPreset = loadingPreset
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
        triggerCollectionRefreshIfNeeded()
        collectionRefreshedForCurrentSession = false
        searchJob?.cancel()
        cleanupJob?.cancel()
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

    fun openSuggestions(collectionId: String) {
        triggerCollectionRefreshIfNeeded()
        collectionRefreshedForCurrentSession = false
        searchJob?.cancel()
        cleanupJob?.cancel()
        viewModelScope.launch {
            try {
                val queue = repository.getSuggestions(collectionId)
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
                        confidenceLabel = "Near miss",
                        originalScore = row.membershipScore,
                        isStrong = false
                    )
                }
                val colName = _session.value.collections.firstOrNull { it.id == collectionId }?.name ?: "Collection"
                dispatch(ReviewAction.StartSuggestions(id = collectionId, name = colName, cards = cards))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e("ReviewViewModel", "Failed to load suggestions for $collectionId", t)
            }
        }
    }

    fun submitSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return

        triggerCollectionRefreshIfNeeded()
        searchJob?.cancel()
        cleanupJob?.cancel()
        dispatch(ReviewAction.SetQuery(trimmed))
        dispatch(ReviewAction.SetLoading)

        searchJob = viewModelScope.launch {
            try {
                val entities = repository.hybridSearch(trimmed, limit = 60)
                val nowSeconds = System.currentTimeMillis() / 1000L
                val currentAge = _ageFilter.value
                val filteredEntities = entities.filter {
                    isOlderThan(it.dateAdded, nowSeconds, currentAge.days)
                }
                val items = repository.mapToDomainModel(filteredEntities)
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

    fun setAgeFilter(filter: AgeFilter) {
        _ageFilter.value = filter
        val currentMode = _session.value.mode
        when (currentMode) {
            is ReviewMode.Search -> {
                submitSearch(currentMode.query)
            }
            is ReviewMode.Cleanup -> {
                if (currentMode.preset == CleanupPreset.CODES) {
                    startCleanup(CleanupPreset.CODES, showLoadingPhase = true)
                }
            }
            else -> {}
        }
    }

    fun startCleanup(preset: CleanupPreset, showLoadingPhase: Boolean = false) {
        triggerCollectionRefreshIfNeeded()
        searchJob?.cancel()
        cleanupJob?.cancel()

        if (showLoadingPhase) {
            dispatch(ReviewAction.SetLoading)
        } else {
            _loadingCleanupPreset.value = preset
        }

        cleanupJob = viewModelScope.launch {
            try {
                when (preset) {
                    CleanupPreset.CODES -> {
                        val matches = repository.findCodeScreenshots()
                        val nowSeconds = System.currentTimeMillis() / 1000L
                        val currentAge = _ageFilter.value
                        val filteredMatches = matches.filter {
                            isOlderThan(it.entity.dateAdded, nowSeconds, currentAge.days)
                        }
                        val entities = filteredMatches.map { it.entity }
                        val domainItems = repository.mapToDomainModel(entities)
                        val kindById = filteredMatches.associate { it.entity.id to it.kind }

                        val cards = domainItems.map { item ->
                            val kind = kindById[item.id] ?: CodeKind.OTP
                            val isOlderThan7Days = isOlderThan(item.dateAdded, nowSeconds, 7)
                            val label = when (kind) {
                                CodeKind.OTP -> "One-time code"
                                CodeKind.EXPIRING -> "Expiring"
                            }
                            ReviewCard(
                                item = item,
                                confidenceLabel = label,
                                isStrong = isOlderThan7Days
                            )
                        }
                        val unsureCards = cards.filter { !it.isStrong }
                        val strongCards = cards.filter { it.isStrong }
                        val orderedStack = unsureCards + strongCards
                        dispatch(ReviewAction.StartCleanup(CleanupPreset.CODES, orderedStack))
                    }

                    CleanupPreset.DUPLICATES -> {
                        val entities = repository.findDuplicateScreenshots()
                        val domainItems = repository.mapToDomainModel(entities)
                        val cards = domainItems.map { item ->
                            ReviewCard(
                                item = item,
                                confidenceLabel = "Near-duplicate",
                                isStrong = true
                            )
                        }
                        dispatch(ReviewAction.StartCleanup(CleanupPreset.DUPLICATES, cards))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e("ReviewViewModel", "Cleanup failed for preset $preset", t)
                dispatch(ReviewAction.StartCleanup(preset, emptyList()))
            } finally {
                _loadingCleanupPreset.value = null
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        cleanupJob?.cancel()
        dispatch(ReviewAction.ClearSearch)
        if (_session.value.mode is ReviewMode.Collection || _session.value.mode is ReviewMode.Suggestions) {
            collectionRefreshedForCurrentSession = false
        }
    }

    fun keep() {
        val session = _session.value
        val current = session.currentCard ?: return
        val mode = session.mode

        if (mode is ReviewMode.Suggestions) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    repository.keepInCollection(current.item.id, mode.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e("ReviewViewModel", "Failed to keep in collection", t)
                }
            }
            showToast("Added to ${mode.name}")
        } else if (mode is ReviewMode.Collection) {
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
        if (_session.value.phase is ReviewPhase.Done) {
            triggerCollectionRefreshIfNeeded()
        }
    }

    fun removeOrMark() {
        val session = _session.value
        val current = session.currentCard ?: return
        val mode = session.mode

        if (mode is ReviewMode.Suggestions) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    repository.removeFromCollection(current.item.id, mode.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e("ReviewViewModel", "Failed to remove from collection", t)
                }
            }
            showToast("Not in ${mode.name}")
        } else if (mode is ReviewMode.Collection) {
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
        if (_session.value.phase is ReviewPhase.Done) {
            triggerCollectionRefreshIfNeeded()
        }
    }

    fun markRemaining() {
        dispatch(ReviewAction.MarkRemaining)
        showToast("Marked remaining for deletion")
    }

    fun undo() {
        val session = _session.value
        val lastStep = session.history.lastOrNull() ?: return
        val mode = session.mode

        if (mode is ReviewMode.Suggestions) {
            val card = lastStep.card
            if (card != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        repository.deleteMembership(card.item.id, mode.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.e("ReviewViewModel", "Failed to undo suggestion membership", t)
                    }
                }
            }
        } else if (mode is ReviewMode.Collection) {
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
                        if (_session.value.phase is ReviewPhase.Done) {
                            triggerCollectionRefreshIfNeeded()
                        }
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
                    if (_session.value.phase is ReviewPhase.Done) {
                        triggerCollectionRefreshIfNeeded()
                    }
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
        collectionRefreshedForCurrentSession = false
        dispatch(ReviewAction.Restart)
    }

    fun back() {
        triggerCollectionRefreshIfNeeded()
        dispatch(ReviewAction.Back)
        if (_session.value.mode is ReviewMode.Collection || _session.value.mode is ReviewMode.Suggestions) {
            collectionRefreshedForCurrentSession = false
        }
    }

    fun clearStatusToast() {
        _statusToast.value = null
    }

    override fun onCleared() {
        super.onCleared()
        triggerCollectionRefreshIfNeeded()
    }

    class Factory(
        private val repository: ScreenshotRepository,
        private val appScope: CoroutineScope? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val scope = appScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
            return ReviewViewModel(repository, scope) as T
        }
    }
}

/**
 * Pure decision function determining whether auto-collections should be refreshed
 * after a review session. Refreshes if either kept or removed count is greater than zero.
 */
fun shouldRefreshAfterSession(keptCount: Int, removedCount: Int): Boolean =
    keptCount > 0 || removedCount > 0

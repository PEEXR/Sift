package com.peexr.sift.ui.library

import android.database.ContentObserver
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.peexr.sift.SiftApplication
import com.peexr.sift.data.embedding.EmbeddingScheduler
import com.peexr.sift.data.embedding.EmbeddingWorker
import com.peexr.sift.data.embedding.ModelState
import com.peexr.sift.data.embedding.isWorkRunning
import com.peexr.sift.data.embedding.shouldAutoIndex
import com.peexr.sift.data.model.ScreenshotItem
import com.peexr.sift.data.repository.ScreenshotRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * UI state for the Library screen.
 */
data class LibraryUiState(
    val totalCount: Int = 0,
    val sections: List<LibrarySection> = emptyList(),
    val query: String = "",
    val searchResults: List<ScreenshotItem>? = null,
    val isSearching: Boolean = false,
    val indexing: IndexingUi = IndexingUi.Hidden,
    val isRefreshing: Boolean = false,
    val statusMessage: String? = null
)

/**
 * A date-grouped section of screenshots in the library.
 */
data class LibrarySection(
    val title: String,
    val items: List<ScreenshotItem>
)

/**
 * State representing on-device indexing progress and model availability.
 */
sealed interface IndexingUi {
    data object Hidden : IndexingUi
    data class NeedsModel(val sizeMb: Int = 316) : IndexingUi
    data class Downloading(val fraction: Float) : IndexingUi
    data class ModelFailed(val message: String) : IndexingUi
    data class Pending(val remaining: Int) : IndexingUi
    data class Running(val done: Int, val total: Int, val remaining: Int) : IndexingUi
}

/**
 * Formats an ETA in seconds into a friendly human-readable string.
 *
 * Examples:
 * - 45s -> "less than a minute"
 * - 720s -> "about 12 min"
 * - 6017s -> "about 1 h 40 min"
 */
fun formatEta(seconds: Long): String {
    if (seconds < 60) {
        return "less than a minute"
    }
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 && minutes > 0 -> "about $hours h $minutes min"
        hours > 0 -> "about $hours h"
        else -> "about $minutes min"
    }
}

/**
 * Pure function grouping screenshots by dateAdded (seconds since epoch) in the specified time zone.
 *
 * Buckets:
 * - "Today": daysDiff == 0
 * - "Yesterday": daysDiff == 1
 * - "This week": daysDiff in 2..7 (rest of the last 7 days)
 * - "Last week": daysDiff in 8..14
 * - One per month (e.g. "August 2026", newest first) for items older than 14 days.
 */
fun groupByDate(
    items: List<ScreenshotItem>,
    now: ZonedDateTime
): List<LibrarySection> {
    if (items.isEmpty()) return emptyList()

    val nowDate = now.toLocalDate()
    val zone = now.zone

    val todayItems = mutableListOf<ScreenshotItem>()
    val yesterdayItems = mutableListOf<ScreenshotItem>()
    val thisWeekItems = mutableListOf<ScreenshotItem>()
    val lastWeekItems = mutableListOf<ScreenshotItem>()
    val monthBuckets = linkedMapOf<YearMonth, MutableList<ScreenshotItem>>()

    for (item in items) {
        val itemDate = Instant.ofEpochSecond(item.dateAdded).atZone(zone).toLocalDate()
        val daysDiff = ChronoUnit.DAYS.between(itemDate, nowDate)

        when {
            daysDiff <= 0L -> todayItems.add(item)
            daysDiff == 1L -> yesterdayItems.add(item)
            daysDiff in 2L..7L -> thisWeekItems.add(item)
            daysDiff in 8L..14L -> lastWeekItems.add(item)
            else -> {
                val yearMonth = YearMonth.from(itemDate)
                monthBuckets.getOrPut(yearMonth) { mutableListOf() }.add(item)
            }
        }
    }

    val result = mutableListOf<LibrarySection>()
    if (todayItems.isNotEmpty()) {
        result.add(LibrarySection("Today", todayItems))
    }
    if (yesterdayItems.isNotEmpty()) {
        result.add(LibrarySection("Yesterday", yesterdayItems))
    }
    if (thisWeekItems.isNotEmpty()) {
        result.add(LibrarySection("This week", thisWeekItems))
    }
    if (lastWeekItems.isNotEmpty()) {
        result.add(LibrarySection("Last week", lastWeekItems))
    }

    val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
    monthBuckets.entries
        .sortedByDescending { it.key }
        .forEach { (yearMonth, monthList) ->
            if (monthList.isNotEmpty()) {
                val title = yearMonth.format(monthFormatter)
                result.add(LibrarySection(title, monthList))
            }
        }

    return result
}

/**
 * Computes the [IndexingUi] state from model state, pending count, and WorkManager work infos.
 */
fun computeIndexingUi(
    modelState: ModelState,
    countNeeding: Int,
    workInfos: List<WorkInfo>,
    isModelInstalled: Boolean
): IndexingUi {
    val runningWork = workInfos.firstOrNull { it.state == WorkInfo.State.RUNNING }
    if (runningWork != null) {
        val done = runningWork.progress.getInt(EmbeddingWorker.KEY_CURRENT, 0)
        val progressTotal = runningWork.progress.getInt(EmbeddingWorker.KEY_TOTAL, 0)
        val total = if (progressTotal > 0) progressTotal else (done + countNeeding).coerceAtLeast(1)
        val remaining = (total - done).coerceAtLeast(0)
        return IndexingUi.Running(done = done, total = total, remaining = remaining)
    }

    return when (modelState) {
        is ModelState.Downloading -> {
            val totalBytes = modelState.bytesTotal.coerceAtLeast(1L)
            val fraction = (modelState.bytesDone.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            IndexingUi.Downloading(fraction = fraction)
        }
        is ModelState.Failed -> {
            IndexingUi.ModelFailed(message = modelState.message)
        }
        is ModelState.NotInstalled -> {
            if (isModelInstalled) {
                if (countNeeding > 0) IndexingUi.Pending(remaining = countNeeding) else IndexingUi.Hidden
            } else {
                IndexingUi.NeedsModel(sizeMb = 316)
            }
        }
        is ModelState.Ready -> {
            if (countNeeding > 0) {
                IndexingUi.Pending(remaining = countNeeding)
            } else {
                IndexingUi.Hidden
            }
        }
    }
}

private data class SearchState(
    val isSearching: Boolean = false,
    val results: List<ScreenshotItem>? = null
)

class LibraryViewModel(
    private val repository: ScreenshotRepository,
    private val app: SiftApplication
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    private val _statusMessage = MutableStateFlow<String?>(null)

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private val searchStateFlow: Flow<SearchState> = _query
        .debounce { q -> if (q.isBlank()) 0L else 250L }
        .flatMapLatest { q ->
            flow {
                if (q.isBlank()) {
                    emit(SearchState(isSearching = false, results = null))
                } else {
                    emit(SearchState(isSearching = true, results = null))
                    try {
                        val entities = repository.hybridSearch(q, limit = 100)
                        val items = repository.mapToDomainModel(entities)
                        emit(SearchState(isSearching = false, results = items))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.e("LibraryViewModel", "Search error", t)
                        emit(SearchState(isSearching = false, results = emptyList()))
                    }
                }
            }
        }

    private val indexingUiFlow: Flow<IndexingUi> = combine(
        app.modelStore.state,
        repository.countNeedingIndexingFlow,
        WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(EmbeddingScheduler.UNIQUE_WORK_NAME)
    ) { modelState, countNeeding, workInfos ->
        computeIndexingUi(
            modelState = modelState,
            countNeeding = countNeeding,
            workInfos = workInfos,
            isModelInstalled = app.modelStore.isInstalled()
        )
    }

    private val sectionsFlow: Flow<List<LibrarySection>> = repository.libraryItemsFlow
        .map { groupByDate(it, ZonedDateTime.now()) }
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()

    private val libraryDataFlow = combine(
        sectionsFlow,
        repository.countAllFlow
    ) { sections, totalCount ->
        Pair(sections, totalCount)
    }

    private val searchCombined = combine(_query, searchStateFlow) { q, searchState ->
        Pair(q, searchState)
    }

    private val refreshAndStatusFlow = combine(_isRefreshing, _statusMessage) { refreshing, status ->
        Pair(refreshing, status)
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        libraryDataFlow,
        searchCombined,
        indexingUiFlow,
        refreshAndStatusFlow
    ) { (sections, totalCount), (queryText, searchState), indexingUi, (isRefreshing, statusMsg) ->
        LibraryUiState(
            totalCount = totalCount,
            sections = sections,
            query = queryText,
            searchResults = searchState.results,
            isSearching = searchState.isSearching,
            indexing = indexingUi,
            isRefreshing = isRefreshing,
            statusMessage = statusMsg
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState()
    )

    private var lastAutoStartMillis: Long = 0L

    private var isContentObserverRegistered = false
    private var contentObserver: ContentObserver? = null
    private val mediaChangeFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var mediaObserverJob: Job? = null

    init {
        registerMediaObserver()
    }

    @OptIn(FlowPreview::class)
    fun registerMediaObserver() {
        if (isContentObserverRegistered) return
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                mediaChangeFlow.tryEmit(Unit)
            }
        }
        try {
            app.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
            contentObserver = observer
            isContentObserverRegistered = true
        } catch (e: Exception) {
            Log.w("LibraryViewModel", "Could not register MediaStore ContentObserver", e)
        }

        mediaObserverJob = viewModelScope.launch {
            mediaChangeFlow
                .debounce(3000L)
                .collect {
                    syncScreenshots(announce = false).join()
                    autoIndexIfNeeded()
                }
        }
    }

    private fun unregisterMediaObserver() {
        mediaObserverJob?.cancel()
        mediaObserverJob = null
        if (isContentObserverRegistered) {
            contentObserver?.let {
                try {
                    app.contentResolver.unregisterContentObserver(it)
                } catch (e: Exception) {
                    Log.w("LibraryViewModel", "Error unregistering ContentObserver", e)
                }
            }
            contentObserver = null
            isContentObserverRegistered = false
        }
    }

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
    }

    fun startIndexing() {
        EmbeddingScheduler.enqueueIndexingNow(app)
    }

    fun downloadModel() {
        app.startModelDownload()
    }

    fun refresh() {
        syncScreenshots(announce = true)
    }

    /**
     * Checks whether automatic indexing should run and, if so, enqueues the worker
     * with KEEP policy (never replacing an in-flight run).
     */
    fun autoIndexIfNeeded(): Job = viewModelScope.launch(Dispatchers.IO) {
        try {
            val enabled = app.settingsManager.autoIndexNewScreenshots
            val modelInstalled = app.modelStore.isInstalled()
            val pendingCount = repository.countNeedingIndexingFlow.first()
            val workInfos = WorkManager.getInstance(app)
                .getWorkInfosForUniqueWork(EmbeddingScheduler.UNIQUE_WORK_NAME)
                .get()
            val workRunning = isWorkRunning(workInfos.map { it.state })
            val now = System.currentTimeMillis()

            val start = shouldAutoIndex(
                enabled = enabled,
                modelInstalled = modelInstalled,
                pendingCount = pendingCount,
                workRunning = workRunning,
                lastAutoStartMillis = lastAutoStartMillis,
                nowMillis = now
            )
            Log.i(
                "LibraryViewModel",
                "autoIndex enabled=$enabled model=$modelInstalled pending=$pendingCount running=$workRunning -> start=$start"
            )
            if (start) {
                lastAutoStartMillis = now
                // Nothing is RUNNING here, so replacing is safe and also clears a stale queued request
                // (KEEP would silently keep it and indexing would never begin).
                EmbeddingScheduler.enqueueIndexingNow(app, replace = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e("LibraryViewModel", "autoIndexIfNeeded failed", t)
        }
    }

    /** Re-scans MediaStore. Only user-initiated refreshes report the result; the launch-time sync stays silent. */
    fun syncScreenshots(announce: Boolean = false): Job = viewModelScope.launch {
        _isRefreshing.value = announce
        try {
            val count = repository.syncMediaStoreScreenshots()
            if (announce) _statusMessage.value = "Found $count screenshots"
            autoIndexIfNeeded()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e("LibraryViewModel", "Sync failed", t)
            _statusMessage.value = "Couldn't scan the gallery"
        } finally {
            _isRefreshing.value = false
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        unregisterMediaObserver()
    }

    class Factory(
        private val repository: ScreenshotRepository,
        private val app: SiftApplication
    ) : ViewModelProvider.Factory {
        constructor(app: SiftApplication) : this(app.repository, app)

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LibraryViewModel(repository, app) as T
        }
    }
}

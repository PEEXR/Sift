package com.peexr.sift.ui.viewer

import android.app.PendingIntent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.peexr.sift.data.local.MembershipSource
import com.peexr.sift.data.model.CollectionSummary
import com.peexr.sift.data.model.ScreenshotDetail
import com.peexr.sift.data.repository.ScreenshotRepository
import com.peexr.sift.ui.collections.nextCollectionColorHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Representation of a collection row in the viewer's collections sheet.
 */
data class ViewerCollectionRow(
    val summary: CollectionSummary,
    val isMember: Boolean
)

/**
 * UI state for the full-screen screenshot viewer.
 */
data class ViewerUiState(
    val detail: ScreenshotDetail? = null,
    val collections: List<ViewerCollectionRow> = emptyList(),
    val isMissing: Boolean = false
)

/**
 * Pure function combining collection summaries and screenshot membership sources.
 * Members are placed first (sorted alphabetically), followed by non-members (sorted alphabetically).
 * Memberships with AUTO or MANUAL are considered members; EXCLUDED or absent are not members.
 */
fun buildViewerCollections(
    summaries: List<CollectionSummary>,
    memberships: Map<String, String>
): List<ViewerCollectionRow> {
    return summaries.map { summary ->
        val source = memberships[summary.id]
        val isMember = source == MembershipSource.AUTO || source == MembershipSource.MANUAL
        ViewerCollectionRow(summary = summary, isMember = isMember)
    }.sortedWith(
        compareByDescending<ViewerCollectionRow> { it.isMember }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.summary.name }
    )
}

class ViewerViewModel(
    private val repository: ScreenshotRepository,
    val screenshotId: Long,
    private val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : ViewModel() {

    companion object {
        private val refreshJobs = ConcurrentHashMap<String, Job>()
    }

    private var hadDetail = false
    private var hasEmitted = false

    val uiState: StateFlow<ViewerUiState> = combine(
        repository.screenshotDetailFlow(screenshotId),
        repository.collectionSummariesFlow,
        repository.collectionMembershipFlow(screenshotId)
    ) { detail, summaries, memberships ->
        hasEmitted = true
        if (detail != null) {
            hadDetail = true
        }
        val isMissing = (hadDetail && detail == null) || (hasEmitted && !hadDetail)
        ViewerUiState(
            detail = detail,
            collections = buildViewerCollections(summaries, memberships),
            isMissing = isMissing
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ViewerUiState()
    )

    private fun scheduleRefresh(collectionId: String) {
        refreshJobs[collectionId]?.cancel()
        refreshJobs[collectionId] = appScope.launch {
            try {
                delay(1500)
                repository.refreshAutoCollections(onlyCollectionId = collectionId)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e("ViewerViewModel", "Failed to refresh auto collection $collectionId after membership change", t)
            } finally {
                refreshJobs.remove(collectionId, coroutineContext[Job])
            }
        }
    }

    fun toggleCollection(collectionId: String, member: Boolean) {
        viewModelScope.launch {
            try {
                repository.setMembership(screenshotId, collectionId, member)
                scheduleRefresh(collectionId)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e("ViewerViewModel", "Failed to toggle collection membership", t)
            }
        }
    }

    fun createCollectionAndAdd(name: String, description: String) {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) return

        val finalDescription = description.trim().ifBlank { trimmedName }
        val existingCount = uiState.value.collections.size
        val colorHex = nextCollectionColorHex(existingCount)

        viewModelScope.launch {
            try {
                val newId = repository.createCollection(
                    name = trimmedName,
                    description = finalDescription,
                    colorHex = colorHex
                )
                repository.setMembership(screenshotId, newId, member = true)
                scheduleRefresh(newId)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e("ViewerViewModel", "Failed to create collection and add screenshot", t)
            }
        }
    }

    fun createDeletePendingIntent(uri: String): PendingIntent? {
        return repository.createDeletePendingIntent(listOf(Uri.parse(uri)))
    }

    fun removeDeletedScreenshot(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                repository.removeDeletedScreenshots(listOf(screenshotId))
                onComplete()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e("ViewerViewModel", "Failed to remove deleted screenshot", t)
            }
        }
    }

    class Factory(
        private val repository: ScreenshotRepository,
        private val screenshotId: Long,
        private val appScope: CoroutineScope? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val scope = appScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
            return ViewerViewModel(repository, screenshotId, scope) as T
        }
    }
}

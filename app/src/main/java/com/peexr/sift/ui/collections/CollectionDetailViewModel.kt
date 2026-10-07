package com.peexr.sift.ui.collections

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.peexr.sift.data.model.CollectionMemberItem
import com.peexr.sift.data.model.CollectionSummary
import com.peexr.sift.data.repository.ScreenshotRepository
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class MemberFilter {
    ALL,
    AUTO,
    KEPT
}

/**
 * Pure filter function for collection member items according to [MemberFilter].
 * - ALL: includes all members
 * - AUTO: includes unreviewed members (kept = false)
 * - KEPT: includes manually confirmed members (kept = true)
 */
fun filterMembers(
    members: List<CollectionMemberItem>,
    filter: MemberFilter
): List<CollectionMemberItem> {
    return when (filter) {
        MemberFilter.ALL -> members
        MemberFilter.AUTO -> members.filter { !it.kept }
        MemberFilter.KEPT -> members.filter { it.kept }
    }
}

/**
 * Formats a byte count into a human-readable string:
 * - MB with one decimal place up to 1024 MB
 * - GB with one decimal place when > 1024 MB
 */
fun formatBytes(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0L)
    val mb = safeBytes.toDouble() / (1024.0 * 1024.0)
    return if (mb > 1024.0) {
        val gb = safeBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        String.format(Locale.US, "%.1f GB", gb)
    } else {
        String.format(Locale.US, "%.1f MB", mb)
    }
}

data class CollectionDetailUiState(
    val collection: CollectionSummary? = null,
    val filter: MemberFilter = MemberFilter.ALL,
    val members: List<CollectionMemberItem> = emptyList(),
    val allCount: Int = 0,
    val autoCount: Int = 0,
    val keptCount: Int = 0,
    val isRefilling: Boolean = false
)

class CollectionDetailViewModel(
    private val repository: ScreenshotRepository,
    private val collectionId: String
) : ViewModel() {

    private val _filter = MutableStateFlow(MemberFilter.ALL)
    private val _isRefilling = MutableStateFlow(false)

    val uiState: StateFlow<CollectionDetailUiState> = combine(
        repository.collectionFlow(collectionId),
        repository.collectionMembersFlow(collectionId),
        _filter,
        _isRefilling
    ) { collection, rawMembers, filter, isRefilling ->
        val keptCount = rawMembers.count { it.kept }
        val autoCount = rawMembers.size - keptCount
        val filteredMembers = filterMembers(rawMembers, filter)

        CollectionDetailUiState(
            collection = collection,
            filter = filter,
            members = filteredMembers,
            allCount = rawMembers.size,
            autoCount = autoCount,
            keptCount = keptCount,
            isRefilling = isRefilling
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CollectionDetailUiState()
    )

    fun setFilter(filter: MemberFilter) {
        _filter.value = filter
    }

    fun updateDescription(text: String) {
        viewModelScope.launch {
            _isRefilling.value = true
            try {
                repository.updateCollectionDescription(collectionId, text)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e("CollectionDetailVM", "Failed to update description for collection $collectionId", t)
            } finally {
                _isRefilling.value = false
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            try {
                repository.deleteCollection(collectionId)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e("CollectionDetailVM", "Failed to delete collection $collectionId", t)
            }
        }
    }

    class Factory(
        private val repository: ScreenshotRepository,
        private val collectionId: String
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CollectionDetailViewModel(repository, collectionId) as T
        }
    }
}

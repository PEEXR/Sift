package com.peexr.sift.ui.review

import com.peexr.sift.data.model.CollectionSummary
import com.peexr.sift.data.model.ScreenshotItem
import kotlin.math.ceil

/**
 * An item in the swipe review stack.
 *
 * @param item Domain screenshot model.
 * @param confidenceLabel Human-readable badge text (e.g. "Unsure", "Weak match", "Strong match").
 * @param originalScore The original similarity or z-score from auto-categorisation (preserved for undo).
 * @param isStrong True if this card belongs to the top 40% strong matches in search mode.
 */
data class ReviewCard(
    val item: ScreenshotItem,
    val confidenceLabel: String,
    val originalScore: Float? = null,
    val isStrong: Boolean = false
)

/**
 * Result of splitting search results into unsure matches and strong matches.
 */
data class SearchStack(
    val cards: List<ReviewCard>,
    val strongCount: Int = 0,
    val unsureCount: Int = 0
)

/**
 * Pure function partitioning hybrid search results (ordered best match first) into a swipe stack.
 *
 * Rules:
 * - The best 40% (rounded up, min 1 when non-empty) are designated as "strong matches".
 * - The remaining items are designated as "unsure" (weak matches).
 * - The swipe stack presents the unsure matches WEAKEST-FIRST, followed by the strong matches WEAKEST-FIRST.
 * - If results is empty, returns an empty [SearchStack].
 */
fun splitSearchResults(results: List<ScreenshotItem>): SearchStack {
    if (results.isEmpty()) {
        return SearchStack(cards = emptyList(), strongCount = 0, unsureCount = 0)
    }

    val n = results.size
    val strongCount = ceil(n * 0.40).toInt().coerceAtLeast(1).coerceAtMost(n)
    val unsureCount = n - strongCount

    val strongList = results.take(strongCount).map {
        ReviewCard(
            item = it,
            confidenceLabel = "Strong match",
            isStrong = true
        )
    }

    val unsureList = results.drop(strongCount).map {
        ReviewCard(
            item = it,
            confidenceLabel = "Weak match",
            isStrong = false
        )
    }

    // Stack order: unsure weakest-first, then strong weakest-first
    val cards = unsureList.reversed() + strongList.reversed()

    return SearchStack(
        cards = cards,
        strongCount = strongCount,
        unsureCount = unsureCount
    )
}

/**
 * Review mode: Picker (default), Collection triage, or Search triage.
 */
sealed interface ReviewMode {
    data object Picker : ReviewMode
    data class Collection(val id: String, val name: String) : ReviewMode
    data class Search(val query: String) : ReviewMode
}

/**
 * Current visual phase of the review session.
 */
sealed interface ReviewPhase {
    data object Swiping : ReviewPhase
    data object Summary : ReviewPhase
    data class Freed(val bytes: Long) : ReviewPhase
    data object Done : ReviewPhase
    data object Loading : ReviewPhase
    data object Empty : ReviewPhase
}

enum class StepKind {
    KEEP,
    REMOVE,
    MARK,
    MARK_REMAINING
}

/**
 * Recorded historical step enabling undo.
 */
data class ReviewStep(
    val kind: StepKind,
    val card: ReviewCard? = null,
    val cards: List<ReviewCard> = emptyList(),
    val previousIndex: Int = 0
)

/**
 * Actions that can be applied to a [ReviewSession].
 */
sealed interface ReviewAction {
    data class SetCollections(val collections: List<CollectionSummary>) : ReviewAction
    data class StartCollection(val id: String, val name: String, val cards: List<ReviewCard>) : ReviewAction
    data class StartSearch(val query: String, val searchStack: SearchStack) : ReviewAction
    data class SetQuery(val query: String) : ReviewAction
    data object SetLoading : ReviewAction
    data object Keep : ReviewAction
    data object RemoveOrMark : ReviewAction
    data object MarkRemaining : ReviewAction
    data object Undo : ReviewAction
    data class FileDeleted(val screenshotId: Long, val sizeBytes: Long) : ReviewAction
    data class FilesDeleted(val ids: Set<Long>, val totalBytes: Long) : ReviewAction
    data object DeleteCancelled : ReviewAction
    data object ClearMarks : ReviewAction
    data object Restart : ReviewAction
    data object Back : ReviewAction
    data object ClearSearch : ReviewAction
}

/**
 * Pure, immutable state of a Review session.
 */
data class ReviewSession(
    val mode: ReviewMode = ReviewMode.Picker,
    val stack: List<ReviewCard> = emptyList(),
    val index: Int = 0,
    val kept: Int = 0,
    val removed: Int = 0,
    val deleted: Int = 0,
    val marked: Set<Long> = emptySet(),
    val phase: ReviewPhase = ReviewPhase.Swiping,
    val collections: List<CollectionSummary> = emptyList(),
    val query: String = "",
    val history: List<ReviewStep> = emptyList(),
    val previousCollection: ReviewMode.Collection? = null
) {
    val canUndo: Boolean
        get() = history.isNotEmpty()

    val currentCard: ReviewCard?
        get() = stack.getOrNull(index)

    val remainingCount: Int
        get() = (stack.size - index).coerceAtLeast(0)

    val showMarkRemaining: Boolean
        get() = mode is ReviewMode.Search &&
                phase == ReviewPhase.Swiping &&
                stack.isNotEmpty() &&
                index < stack.size &&
                stack.subList(index, stack.size).all { it.isStrong }
}

/**
 * Pure reducer function applying a [ReviewAction] to a [ReviewSession].
 */
fun ReviewSession.apply(action: ReviewAction): ReviewSession {
    return when (action) {
        is ReviewAction.SetCollections -> {
            copy(collections = action.collections)
        }

        is ReviewAction.StartCollection -> {
            copy(
                mode = ReviewMode.Collection(id = action.id, name = action.name),
                stack = action.cards,
                index = 0,
                kept = 0,
                removed = 0,
                deleted = 0,
                marked = emptySet(),
                history = emptyList(),
                previousCollection = null,
                phase = if (action.cards.isEmpty()) ReviewPhase.Empty else ReviewPhase.Swiping
            )
        }

        is ReviewAction.StartSearch -> {
            val prev = if (mode is ReviewMode.Collection) mode else previousCollection
            copy(
                mode = ReviewMode.Search(action.query),
                query = action.query,
                stack = action.searchStack.cards,
                index = 0,
                kept = 0,
                removed = 0,
                deleted = 0,
                marked = emptySet(),
                history = emptyList(),
                previousCollection = prev,
                phase = if (action.searchStack.cards.isEmpty()) ReviewPhase.Empty else ReviewPhase.Swiping
            )
        }

        is ReviewAction.SetQuery -> {
            copy(query = action.query)
        }

        is ReviewAction.SetLoading -> {
            copy(phase = ReviewPhase.Loading)
        }

        is ReviewAction.Keep -> {
            if (index >= stack.size || phase != ReviewPhase.Swiping) return this
            val current = stack[index]
            val nextIndex = index + 1
            val nextKept = kept + 1
            val step = ReviewStep(kind = StepKind.KEEP, card = current, previousIndex = index)
            val nextPhase = if (nextIndex >= stack.size) {
                if (mode is ReviewMode.Search) ReviewPhase.Summary else ReviewPhase.Done
            } else {
                ReviewPhase.Swiping
            }
            copy(
                index = nextIndex,
                kept = nextKept,
                history = history + step,
                phase = nextPhase
            )
        }

        is ReviewAction.RemoveOrMark -> {
            if (index >= stack.size || phase != ReviewPhase.Swiping) return this
            val current = stack[index]
            val nextIndex = index + 1
            if (mode is ReviewMode.Search) {
                val nextMarked = marked + current.item.id
                val step = ReviewStep(kind = StepKind.MARK, card = current, previousIndex = index)
                val nextPhase = if (nextIndex >= stack.size) ReviewPhase.Summary else ReviewPhase.Swiping
                copy(
                    index = nextIndex,
                    marked = nextMarked,
                    history = history + step,
                    phase = nextPhase
                )
            } else {
                val nextRemoved = removed + 1
                val step = ReviewStep(kind = StepKind.REMOVE, card = current, previousIndex = index)
                val nextPhase = if (nextIndex >= stack.size) ReviewPhase.Done else ReviewPhase.Swiping
                copy(
                    index = nextIndex,
                    removed = nextRemoved,
                    history = history + step,
                    phase = nextPhase
                )
            }
        }

        is ReviewAction.MarkRemaining -> {
            if (mode !is ReviewMode.Search || phase != ReviewPhase.Swiping || index >= stack.size) return this
            val remaining = stack.subList(index, stack.size)
            val remainingIds = remaining.map { it.item.id }.toSet()
            val step = ReviewStep(kind = StepKind.MARK_REMAINING, cards = remaining, previousIndex = index)
            copy(
                index = stack.size,
                marked = marked + remainingIds,
                history = history + step,
                phase = ReviewPhase.Summary
            )
        }

        is ReviewAction.Undo -> {
            if (history.isEmpty()) return this
            val lastStep = history.last()
            val remainingHistory = history.dropLast(1)
            when (lastStep.kind) {
                StepKind.KEEP -> {
                    copy(
                        index = lastStep.previousIndex,
                        kept = (kept - 1).coerceAtLeast(0),
                        history = remainingHistory,
                        phase = ReviewPhase.Swiping
                    )
                }
                StepKind.REMOVE -> {
                    copy(
                        index = lastStep.previousIndex,
                        removed = (removed - 1).coerceAtLeast(0),
                        history = remainingHistory,
                        phase = ReviewPhase.Swiping
                    )
                }
                StepKind.MARK -> {
                    val cardId = lastStep.card?.item?.id
                    val nextMarked = if (cardId != null) marked - cardId else marked
                    copy(
                        index = lastStep.previousIndex,
                        marked = nextMarked,
                        history = remainingHistory,
                        phase = ReviewPhase.Swiping
                    )
                }
                StepKind.MARK_REMAINING -> {
                    val ids = lastStep.cards.map { it.item.id }.toSet()
                    copy(
                        index = lastStep.previousIndex,
                        marked = marked - ids,
                        history = remainingHistory,
                        phase = ReviewPhase.Swiping
                    )
                }
            }
        }

        is ReviewAction.FileDeleted -> {
            val nextIndex = index + 1
            val nextDeleted = deleted + 1
            val nextPhase = if (nextIndex >= stack.size) ReviewPhase.Done else ReviewPhase.Swiping
            // File delete is irreversible; undo is disabled after a file delete
            copy(
                index = nextIndex,
                deleted = nextDeleted,
                history = emptyList(),
                phase = nextPhase
            )
        }

        is ReviewAction.FilesDeleted -> {
            copy(
                marked = emptySet(),
                history = emptyList(),
                phase = ReviewPhase.Freed(action.totalBytes)
            )
        }

        is ReviewAction.DeleteCancelled -> {
            copy(phase = ReviewPhase.Summary)
        }

        is ReviewAction.ClearMarks -> {
            copy(marked = emptySet())
        }

        is ReviewAction.Restart -> {
            copy(
                index = 0,
                kept = 0,
                removed = 0,
                deleted = 0,
                marked = emptySet(),
                history = emptyList(),
                phase = if (stack.isEmpty()) ReviewPhase.Empty else ReviewPhase.Swiping
            )
        }

        is ReviewAction.Back, is ReviewAction.ClearSearch -> {
            if (mode is ReviewMode.Search) {
                if (previousCollection != null) {
                    copy(
                        mode = previousCollection,
                        previousCollection = null,
                        query = ""
                    )
                } else {
                    copy(
                        mode = ReviewMode.Picker,
                        query = "",
                        stack = emptyList(),
                        index = 0,
                        kept = 0,
                        removed = 0,
                        deleted = 0,
                        marked = emptySet(),
                        history = emptyList(),
                        phase = ReviewPhase.Swiping
                    )
                }
            } else if (mode is ReviewMode.Collection) {
                copy(
                    mode = ReviewMode.Picker,
                    stack = emptyList(),
                    index = 0,
                    kept = 0,
                    removed = 0,
                    deleted = 0,
                    marked = emptySet(),
                    history = emptyList(),
                    phase = ReviewPhase.Swiping
                )
            } else {
                this
            }
        }
    }
}

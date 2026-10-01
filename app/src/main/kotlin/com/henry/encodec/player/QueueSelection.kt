package com.henry.encodec.player

internal data class QueueReplacement<T>(
    val items: List<T>,
    val currentIndex: Int,
    val replacedItem: T?,
)

/** Replaces the current queue slot, or creates the first slot when the queue is empty. */
internal fun <T> replaceCurrentQueueItem(
    items: List<T>,
    currentIndex: Int,
    item: T,
): QueueReplacement<T> {
    val index = currentIndex.takeIf { it in items.indices } ?: 0
    val updated = items.toMutableList()
    val replaced = updated.getOrNull(index)
    if (replaced == null) updated.add(item) else updated[index] = item
    return QueueReplacement(updated, index, replaced)
}

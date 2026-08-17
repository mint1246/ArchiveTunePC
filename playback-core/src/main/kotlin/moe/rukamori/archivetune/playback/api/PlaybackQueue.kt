/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.api

import kotlin.random.Random

/**
 * Platform-neutral queue/timeline implementation. Logical queue indexes remain stable for the UI,
 * while shuffle order is tracked by private entry tokens so duplicate media ids remain safe.
 * The owner is expected to serialize calls (desktop playback does so with a coroutine mutex).
 */
class PlaybackQueue(
    private val random: Random = Random.Default,
) {
    private data class Entry(val token: Long, val item: PlaybackItem)

    data class Selection(
        val item: PlaybackItem,
        val index: Int,
        val repeated: Boolean = false,
    )

    data class Mutation(
        val currentChanged: Boolean,
        val current: Selection?,
    )

    private val entries = mutableListOf<Entry>()
    private val playOrder = mutableListOf<Long>()
    private var cursor = -1
    private var nextToken = 1L

    var repeatMode: RepeatMode = RepeatMode.OFF
        private set

    var shuffleEnabled: Boolean = false
        private set

    val items: List<PlaybackItem>
        get() = entries.map(Entry::item)

    val currentIndex: Int
        get() {
            val token = currentToken ?: return -1
            return entries.indexOfFirst { it.token == token }
        }

    val current: PlaybackItem?
        get() = currentEntry?.item

    val canAdvance: Boolean
        get() = entries.isNotEmpty() && (cursor < playOrder.lastIndex || repeatMode == RepeatMode.ALL)

    val canRetreat: Boolean
        get() = entries.isNotEmpty() && (cursor > 0 || repeatMode == RepeatMode.ALL)

    internal val currentTokenForOwner: Long?
        get() = currentToken

    private val currentToken: Long?
        get() = playOrder.getOrNull(cursor)

    private val currentEntry: Entry?
        get() = currentToken?.let { token -> entries.firstOrNull { it.token == token } }

    fun replace(items: List<PlaybackItem>, startIndex: Int = 0): Selection? {
        entries.clear()
        playOrder.clear()
        entries += items.map { Entry(nextToken++, it) }
        if (entries.isEmpty()) {
            cursor = -1
            return null
        }

        val safeIndex = startIndex.coerceIn(entries.indices)
        rebuildPlayOrder(entries[safeIndex].token)
        return selection()
    }

    fun append(items: List<PlaybackItem>): Mutation {
        if (items.isEmpty()) return Mutation(false, selection())
        if (entries.isEmpty()) {
            return Mutation(currentChanged = true, current = replace(items))
        }

        val newEntries = items.map { Entry(nextToken++, it) }
        entries += newEntries
        if (shuffleEnabled) {
            insertIntoShuffleOrderLikeMedia3(newEntries.map(Entry::token))
        } else {
            syncLinearOrder()
        }
        return Mutation(false, selection())
    }

    fun insertNext(items: List<PlaybackItem>): Mutation {
        if (items.isEmpty()) return Mutation(false, selection())
        if (entries.isEmpty()) {
            return Mutation(currentChanged = true, current = replace(items))
        }

        val insertionIndex = currentIndex + 1
        val newEntries = items.map { Entry(nextToken++, it) }
        entries.addAll(insertionIndex, newEntries)
        if (shuffleEnabled) {
            playOrder.addAll(cursor + 1, newEntries.map(Entry::token))
        } else {
            syncLinearOrder()
        }
        return Mutation(false, selection())
    }

    fun removeAt(index: Int): Mutation {
        require(index in entries.indices) { "Queue index $index is out of bounds" }
        val oldCurrentToken = currentToken
        val removed = entries.removeAt(index)
        val removedOrderIndex = playOrder.indexOf(removed.token)
        if (removedOrderIndex >= 0) {
            playOrder.removeAt(removedOrderIndex)
            if (removedOrderIndex < cursor) cursor--
        }

        if (entries.isEmpty()) {
            cursor = -1
        } else if (removed.token == oldCurrentToken) {
            cursor = cursor.coerceIn(playOrder.indices)
        } else if (!shuffleEnabled) {
            syncLinearOrder(oldCurrentToken)
        }

        return Mutation(
            currentChanged = removed.token == oldCurrentToken,
            current = selection(),
        )
    }

    fun move(fromIndex: Int, toIndex: Int): Mutation {
        require(fromIndex in entries.indices) { "Queue index $fromIndex is out of bounds" }
        require(toIndex in entries.indices) { "Queue index $toIndex is out of bounds" }
        if (fromIndex == toIndex) return Mutation(false, selection())

        val oldCurrentToken = currentToken
        val entry = entries.removeAt(fromIndex)
        entries.add(toIndex, entry)
        if (!shuffleEnabled) syncLinearOrder(oldCurrentToken)
        return Mutation(false, selection())
    }

    /**
     * Reorders queue rows the way the Android queue sheet does: logical order when shuffle is off,
     * shuffle traversal order when shuffle is on.
     */
    fun moveVisual(fromVisual: Int, toVisual: Int): Mutation =
        if (shuffleEnabled) {
            moveInPlayOrder(fromVisual, toVisual)
        } else {
            move(fromVisual, toVisual)
        }

    /**
     * Reorders the traversal (shuffle) order only, keeping the logical list untouched. [fromVisual]
     * and [toVisual] are positions in [visualTokens], matching Android's `getQueueWindows()` order.
     * Used when a shuffled queue needs an existing item moved without changing album/playlist order.
     */
    fun moveInPlayOrder(fromVisual: Int, toVisual: Int): Mutation {
        val size = playOrder.size
        if (size < 2 || fromVisual == toVisual || fromVisual !in 0 until size || toVisual !in 0 until size) {
            return Mutation(false, selection())
        }
        val currentToken = currentToken ?: return Mutation(false, selection())

        val visual = visualTokens().toMutableList()
        val token = visual.removeAt(fromVisual)
        visual.add(toVisual, token)

        playOrder.clear()
        playOrder += visual
        cursor = playOrder.indexOf(currentToken)
        return Mutation(false, selection())
    }

    /**
     * Moves the item at logical [index] so it plays immediately after the current item, matching
     * Android's `MusicService.moveQueueItemToNext`. In shuffle mode this repositions the item in
     * the traversal order right after the current item.
     */
    fun moveToNext(index: Int): Mutation {
        require(index in entries.indices) { "Queue index $index is out of bounds" }
        if (index == currentIndex) return Mutation(false, selection())

        if (shuffleEnabled) {
            val token = entries[index].token
            val sourceVisual = visualTokens().indexOf(token)
            if (sourceVisual < 0 || sourceVisual == cursor) return Mutation(false, selection())
            val destinationVisual = (cursor + 1).coerceAtMost(playOrder.lastIndex)
            if (sourceVisual == destinationVisual) return Mutation(false, selection())
            return moveInPlayOrder(sourceVisual, destinationVisual)
        }

        val destination = (currentIndex + 1).coerceAtMost(entries.lastIndex)
        if (index == destination) return Mutation(false, selection())
        return move(index, destination)
    }

    /** Removes everything except the current item, matching Android's clear-queue action. */
    fun clear(): Mutation {
        val currentToken = currentToken
        if (currentToken == null) {
            entries.clear()
            playOrder.clear()
            cursor = -1
            return Mutation(false, null)
        }
        val currentEntry = entries.first { it.token == currentToken }
        entries.clear()
        entries += currentEntry
        playOrder.clear()
        playOrder += currentToken
        cursor = 0
        return Mutation(false, selection())
    }

    /**
     * The queue's tokens in visual order, equivalent to `Player.getQueueWindows()` on Android:
     * already-played traversal entries first, then current, then upcoming traversal entries.
     */
    fun visualTokens(): List<Long> = playOrder.toList()

    /**
     * Queue rows in the same order Android exposes through `Player.getQueueWindows()`. With shuffle
     * disabled this is logical album/playlist order. With shuffle enabled this is traversal order.
     * [PlaybackQueueEntry.logicalIndex] remains the Media3-style logical media item index used by
     * seek/remove/play-next operations.
     */
    fun entries(): List<PlaybackQueueEntry> {
        val currentToken = currentToken
        return visualTokens().mapNotNull { token ->
            val index = entries.indexOfFirst { it.token == token }
            entries.getOrNull(index)?.let { entry ->
                PlaybackQueueEntry(entry.token, entry.item, index, entry.token == currentToken)
            }
        }
    }

    fun select(index: Int): Selection {
        require(index in entries.indices) { "Queue index $index is out of bounds" }
        val token = entries[index].token
        val playOrderIndex = playOrder.indexOf(token)
        if (playOrderIndex >= 0) {
            cursor = playOrderIndex
        } else {
            rebuildPlayOrder(token)
        }
        return requireNotNull(selection())
    }

    /** User skips ignore repeat-one; natural completion honors it. */
    fun advance(automatic: Boolean): Selection? {
        if (entries.isEmpty()) return null
        if (automatic && repeatMode == RepeatMode.ONE) {
            return selection()?.copy(repeated = true)
        }

        return when {
            cursor < playOrder.lastIndex -> {
                cursor++
                selection()
            }

            repeatMode == RepeatMode.ALL -> {
                cursor = 0
                selection()?.copy(repeated = true)
            }

            else -> null
        }
    }

    fun retreat(): Selection? {
        if (entries.isEmpty()) return null
        return when {
            cursor > 0 -> {
                cursor--
                selection()
            }

            repeatMode == RepeatMode.ALL -> {
                cursor = playOrder.lastIndex
                selection()?.copy(repeated = true)
            }

            else -> null
        }
    }

    fun setRepeatMode(mode: RepeatMode) {
        repeatMode = mode
    }

    fun setShuffleEnabled(enabled: Boolean) {
        if (shuffleEnabled == enabled) return
        val token = currentToken
        shuffleEnabled = enabled
        if (token == null) return
        if (enabled) {
            playOrder.clear()
            playOrder += entries.map(Entry::token).shuffled(random)
            cursor = playOrder.indexOf(token)
        } else {
            syncLinearOrder(token)
        }
    }

    private fun rebuildPlayOrder(currentToken: Long) {
        playOrder.clear()
        if (shuffleEnabled) {
            playOrder += currentToken
            playOrder += entries.asSequence().map(Entry::token).filterNot { it == currentToken }.shuffled(random)
            cursor = 0
        } else {
            playOrder += entries.map(Entry::token)
            cursor = playOrder.indexOf(currentToken)
        }
    }

    private fun syncLinearOrder(preservedCurrentToken: Long? = currentToken) {
        playOrder.clear()
        playOrder += entries.map(Entry::token)
        cursor = preservedCurrentToken?.let(playOrder::indexOf) ?: -1
        if (cursor < 0 && playOrder.isNotEmpty()) cursor = 0
    }

    /** Mirrors Media3 `DefaultShuffleOrder.cloneAndInsert` for token identities. */
    private fun insertIntoShuffleOrderLikeMedia3(newTokens: List<Long>) {
        if (newTokens.isEmpty()) return
        val oldCurrentToken = currentToken
        val insertionPoints = List(newTokens.size) { random.nextInt(playOrder.size + 1) }.sorted()
        val randomizedNewTokens = newTokens.shuffled(random)
        val oldOrder = playOrder.toList()

        playOrder.clear()
        var oldIndex = 0
        var newIndex = 0
        while (oldIndex < oldOrder.size || newIndex < randomizedNewTokens.size) {
            if (newIndex < randomizedNewTokens.size && insertionPoints[newIndex] == oldIndex) {
                playOrder += randomizedNewTokens[newIndex++]
            } else if (oldIndex < oldOrder.size) {
                playOrder += oldOrder[oldIndex++]
            }
        }
        cursor = oldCurrentToken?.let(playOrder::indexOf) ?: -1
        if (cursor < 0 && playOrder.isNotEmpty()) cursor = 0
    }

    private fun selection(): Selection? {
        val entry = currentEntry ?: return null
        return Selection(entry.item, entries.indexOf(entry))
    }
}

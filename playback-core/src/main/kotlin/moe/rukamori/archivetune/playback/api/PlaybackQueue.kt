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
            // Media3 appends newly added items at the end of the shuffle order rather than
            // interleaving them, so appended tracks play after everything already queued.
            playOrder += newEntries.map(Entry::token)
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
     * Reorders the visible "up next" order, mirroring how the Android queue sheet reorders its
     * timeline windows. [fromVisual] and [toVisual] are positions in [visualTokens].
     *
     * When shuffle is disabled this rewrites the logical order (like Media3's `moveMediaItem`);
     * when shuffle is enabled only the traversal order changes and the logical list is untouched.
     */
    fun moveVisual(fromVisual: Int, toVisual: Int): Mutation {
        val size = playOrder.size
        if (size < 2 || fromVisual == toVisual || fromVisual !in 0 until size || toVisual !in 0 until size) {
            return Mutation(false, selection())
        }
        val currentToken = currentToken ?: return Mutation(false, selection())

        val visual = visualTokens().toMutableList()
        val token = visual.removeAt(fromVisual)
        visual.add(toVisual, token)

        if (shuffleEnabled) {
            playOrder.clear()
            playOrder += visual
            cursor = playOrder.indexOf(currentToken)
        } else {
            val byToken = entries.associateBy(Entry::token)
            val newEntries = visual.mapNotNull(byToken::get)
            entries.clear()
            entries += newEntries
            syncLinearOrder(currentToken)
        }
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
            if (sourceVisual <= 0) return Mutation(false, selection())
            return moveVisual(sourceVisual, 1)
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
     * The queue's tokens in visual order: current first, then upcoming in play order, then
     * already-played items. Equivalent to `Player.getQueueWindows()` on Android.
     */
    fun visualTokens(): List<Long> {
        if (playOrder.isEmpty()) return emptyList()
        return buildList(playOrder.size) {
            for (i in cursor until playOrder.size) add(playOrder[i])
            for (i in 0 until cursor) add(playOrder[i])
        }
    }

    /** The queue's rows in visual order, exposing stable ids and logical indices for the UI. */
    fun visualEntries(): List<PlaybackQueueEntry> {
        val indexByToken = HashMap<Long, Int>(entries.size)
        entries.forEachIndexed { index, entry -> indexByToken[entry.token] = index }
        if (playOrder.isEmpty()) {
            return entries.map { entry ->
                PlaybackQueueEntry(entry.token, entry.item, indexByToken.getValue(entry.token), isCurrent = false)
            }
        }
        val currentToken = currentToken
        val result = ArrayList<PlaybackQueueEntry>(entries.size)
        fun addToken(token: Long) {
            val index = indexByToken[token] ?: return
            result += PlaybackQueueEntry(token, entries[index].item, index, token == currentToken)
        }
        for (i in cursor until playOrder.size) addToken(playOrder[i])
        for (i in 0 until cursor) addToken(playOrder[i])
        return result
    }

    fun select(index: Int): Selection {
        require(index in entries.indices) { "Queue index $index is out of bounds" }
        rebuildPlayOrder(entries[index].token)
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
        if (token != null) rebuildPlayOrder(token)
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

    private fun selection(): Selection? {
        val entry = currentEntry ?: return null
        return Selection(entry.item, entries.indexOf(entry))
    }
}

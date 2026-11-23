/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.session

import kotlin.concurrent.Volatile
import moe.rukamori.archivetune.playback.internal.PlaybackAtomicLong

/**
 * Generation gates for persisted playback state.
 *
 * Save, restore and restored-queue hydration overlap in a real Media3 service: a queued save must
 * not overwrite freshly restored state, and a late hydration backfill must not clobber a newer
 * queue. This coordinator owns those races with monotonically increasing tokens so the platform
 * adapter only has to perform the actual file writes and player mutations.
 *
 * Calls from one service should be serialized on its main/thread-confined scope, matching the
 * surrounding playback state contract. File locking and serialization formats stay with the
 * caller; no file or player is owned here.
 */
class PlaybackPersistenceCoordinator {
    private val saveGeneration = PlaybackAtomicLong(0L)
    private val hydrationGeneration = PlaybackAtomicLong(0L)

    @Volatile
    private var restoring = false

    @Volatile
    private var hydrating = false

    val isRestoring: Boolean
        get() = restoring

    val isHydrating: Boolean
        get() = hydrating

    /** Token identifying the current save epoch. */
    fun saveToken(): Long = saveGeneration.get()

    /** Invalidate every in-flight save, e.g. when persisted files are cleared. */
    fun invalidateSaves() {
        saveGeneration.incrementAndGet()
    }

    /** Whether [token] still identifies the current save epoch. */
    fun isSaveTokenCurrent(token: Long): Boolean = token == saveGeneration.get()

    /**
     * Whether a snapshot taken under [token] may start. Restore and hydration additionally block
     * a fresh snapshot so it cannot capture half-rebuilt player state.
     */
    fun canSave(token: Long): Boolean =
        isSaveTokenCurrent(token) && !restoring && !hydrating

    fun beginRestore() {
        restoring = true
    }

    fun endRestore() {
        restoring = false
    }

    /** Invalidate any pending hydration backfill. */
    fun cancelHydration() {
        hydrationGeneration.incrementAndGet()
        hydrating = false
    }

    /** Start a new hydration epoch and return its token. */
    fun beginHydration(): Long {
        val token = hydrationGeneration.incrementAndGet()
        hydrating = true
        return token
    }

    fun isHydrationCurrent(token: Long): Boolean = token == hydrationGeneration.get()

    /** Clear the hydration flag only if [token] still owns the current epoch. */
    fun finishHydration(token: Long): Boolean {
        if (token != hydrationGeneration.get()) return false
        hydrating = false
        return true
    }
}

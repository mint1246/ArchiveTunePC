/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlaybackPersistenceCoordinatorTest {
    @Test
    fun pendingSaveIsAllowed_untilExplicitlyInvalidated() {
        val coordinator = PlaybackPersistenceCoordinator()
        val token = coordinator.saveToken()

        assertTrue(coordinator.canSave(token))
        coordinator.invalidateSaves()
        assertFalse(coordinator.canSave(token))
        assertTrue(coordinator.canSave(coordinator.saveToken()))
    }

    @Test
    fun saveIsBlockedWhileRestoringOrHydrating() {
        val coordinator = PlaybackPersistenceCoordinator()
        val token = coordinator.saveToken()

        coordinator.beginRestore()
        assertTrue(coordinator.isRestoring)
        assertFalse(coordinator.canSave(token))
        coordinator.endRestore()
        assertFalse(coordinator.isRestoring)
        assertTrue(coordinator.canSave(token))

        val hydrationToken = coordinator.beginHydration()
        assertTrue(coordinator.isHydrating)
        assertFalse(coordinator.canSave(token))
        assertTrue(coordinator.finishHydration(hydrationToken))
        // Finishing a hydration epoch must not accidentally clear a save marker.
        assertTrue(coordinator.canSave(token))
    }

    @Test
    fun restoreIsIdempotentAndClearedOnEnd() {
        val coordinator = PlaybackPersistenceCoordinator()

        coordinator.beginRestore()
        coordinator.beginRestore()
        assertTrue(coordinator.isRestoring)
        coordinator.endRestore()
        assertFalse(coordinator.isRestoring)
    }

    @Test
    fun startingHydrationInvalidatesThePreviousEpoch() {
        val coordinator = PlaybackPersistenceCoordinator()

        val first = coordinator.beginHydration()
        assertTrue(coordinator.isHydrationCurrent(first))
        val second = coordinator.beginHydration()

        assertNotEquals(first, second)
        assertFalse(coordinator.isHydrationCurrent(first))
        assertTrue(coordinator.isHydrationCurrent(second))
        assertTrue(coordinator.isHydrating)
    }

    @Test
    fun staleHydrationCannotClearTheCurrentFlag() {
        val coordinator = PlaybackPersistenceCoordinator()

        val stale = coordinator.beginHydration()
        val current = coordinator.beginHydration()

        assertFalse(coordinator.finishHydration(stale))
        assertTrue(coordinator.isHydrating)
        assertTrue(coordinator.finishHydration(current))
        assertFalse(coordinator.isHydrating)
    }

    @Test
    fun cancelHydrationInvalidatesTokensAndClearsFlag() {
        val coordinator = PlaybackPersistenceCoordinator()
        val token = coordinator.beginHydration()

        coordinator.cancelHydration()

        assertFalse(coordinator.isHydrating)
        assertFalse(coordinator.isHydrationCurrent(token))
        assertTrue(coordinator.canSave(coordinator.saveToken()))
    }

    @Test
    fun repeatedCancelDoesNotCollideWithLaterEpochs() {
        val coordinator = PlaybackPersistenceCoordinator()

        coordinator.beginHydration()
        coordinator.cancelHydration()
        coordinator.cancelHydration()
        val current = coordinator.beginHydration()

        assertTrue(coordinator.finishHydration(current))
        assertFalse(coordinator.isHydrating)
    }

    @Test
    fun tokensAreStrictlyIncreasingWithinTheirEpoch() {
        val coordinator = PlaybackPersistenceCoordinator()
        val initialSave = coordinator.saveToken()
        val first = coordinator.beginHydration()
        coordinator.cancelHydration()
        val second = coordinator.beginHydration()
        coordinator.invalidateSaves()

        assertTrue(first < second)
        assertEquals(initialSave + 1, coordinator.saveToken())
    }
}

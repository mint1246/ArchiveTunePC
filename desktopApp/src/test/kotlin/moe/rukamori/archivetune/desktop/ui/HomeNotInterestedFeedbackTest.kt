/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.desktop.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeNotInterestedFeedbackTest {
    @Test
    fun `remote success hides item only after backend success`() = runBlocking {
        val hidden = mutableListOf<SongItem>()
        val item = song("remote-success")
        val feedback = HomeNotInterestedFeedback(
            sendRemoteFeedback = { Result.success(Unit) },
            hideRemoteItem = { hidden += it as SongItem },
            dismissLocalItem = {},
            restoreLocalItem = {},
        )

        val result = feedback.dismissRemote(item)

        assertTrue(result.isSuccess)
        assertEquals(listOf(item), hidden)
        assertNull(feedback.notice.value)
    }

    @Test
    fun `remote failure keeps item visible and exposes retry that can recover`() = runBlocking {
        val hidden = mutableListOf<SongItem>()
        var attempts = 0
        val item = song("remote-failure")
        val feedback = HomeNotInterestedFeedback(
            sendRemoteFeedback = {
                attempts += 1
                if (attempts == 1) Result.failure(IllegalStateException("offline")) else Result.success(Unit)
            },
            hideRemoteItem = { hidden += it as SongItem },
            dismissLocalItem = {},
            restoreLocalItem = {},
        )

        val failure = feedback.dismissRemote(item)

        assertTrue(failure.isFailure)
        assertTrue(hidden.isEmpty())
        val notice = feedback.notice.value as HomeFeedbackNotice.RemoteFailure
        assertSame(item, notice.item)
        assertTrue(notice.message.contains("offline"))

        val retry = feedback.retryRemoteFailure()
        assertTrue(retry?.isSuccess == true)
        assertEquals(listOf(item), hidden)
        assertNull(feedback.notice.value)
    }

    @Test
    fun `local dismissal exposes undo and restores most recent item`() {
        val dismissed = mutableListOf<SongItem>()
        val restored = mutableListOf<SongItem>()
        val item = song("local")
        val feedback = HomeNotInterestedFeedback(
            sendRemoteFeedback = { Result.success(Unit) },
            hideRemoteItem = {},
            dismissLocalItem = { dismissed += it as SongItem },
            restoreLocalItem = { restored += it as SongItem },
        )

        feedback.dismissLocal(item)

        assertEquals(listOf(item), dismissed)
        assertSame(item, (feedback.notice.value as HomeFeedbackNotice.LocalDismissed).item)
        assertTrue(feedback.undoLocalDismissal())
        assertEquals(listOf(item), restored)
        assertNull(feedback.notice.value)
        assertFalse(feedback.undoLocalDismissal())
    }

    @Test
    fun `stale overlapping remote completion cannot replace newer failure notice`() = runBlocking {
        val first = song("first")
        val second = song("second")
        val firstResult = CompletableDeferred<Result<Unit>>()
        val secondResult = CompletableDeferred<Result<Unit>>()
        val hidden = mutableListOf<SongItem>()
        val feedback = HomeNotInterestedFeedback(
            sendRemoteFeedback = { item ->
                when (item.id) {
                    first.id -> firstResult.await()
                    second.id -> secondResult.await()
                    else -> error("unexpected item")
                }
            },
            hideRemoteItem = { hidden += it as SongItem },
            dismissLocalItem = {},
            restoreLocalItem = {},
        )

        val firstRequest = async { feedback.dismissRemote(first) }
        val secondRequest = async { feedback.dismissRemote(second) }

        secondResult.complete(Result.failure(IllegalStateException("new failure")))
        secondRequest.await()
        val current = feedback.notice.value as HomeFeedbackNotice.RemoteFailure
        assertSame(second, current.item)

        firstResult.complete(Result.success(Unit))
        firstRequest.await()

        assertEquals(listOf(first), hidden)
        val afterStaleCompletion = feedback.notice.value as HomeFeedbackNotice.RemoteFailure
        assertSame(second, afterStaleCompletion.item)
        assertTrue(afterStaleCompletion.message.contains("new failure"))
    }

    private fun song(id: String) = SongItem(
        id = id,
        title = "Song $id",
        artists = emptyList(),
        thumbnail = "t",
        feedbackToken = "feedback-$id",
    )
}

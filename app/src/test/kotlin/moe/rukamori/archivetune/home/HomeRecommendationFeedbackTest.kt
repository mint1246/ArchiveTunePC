package moe.rukamori.archivetune.home

import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class HomeRecommendationFeedbackTest {
    @Test
    fun `remote recommendation hides only after feedback succeeds`() = runBlocking {
        val item = song("a")
        val hidden = mutableListOf<SongItem>()
        val feedback =
            HomeRecommendationFeedback(
                sendRemoteFeedback = { Result.success(Unit) },
                hideRemoteItem = { hidden += it as SongItem },
            )

        feedback.dismissRemote(item).getOrThrow()

        assertEquals(listOf(item), hidden)
        assertNull(feedback.notice.value)
    }

    @Test
    fun `failed remote feedback remains visible and retryable`() = runBlocking {
        val item = song("b")
        val hidden = mutableListOf<SongItem>()
        var attempts = 0
        val feedback =
            HomeRecommendationFeedback(
                sendRemoteFeedback = {
                    attempts += 1
                    if (attempts == 1) Result.failure(IllegalStateException("offline")) else Result.success(Unit)
                },
                hideRemoteItem = { hidden += it as SongItem },
            )

        feedback.dismissRemote(item)
        assertEquals(emptyList<SongItem>(), hidden)
        assertSame(item, (feedback.notice.value as HomeRecommendationFeedbackNotice.RemoteFailure).item)

        feedback.retryRemoteFailure()?.getOrThrow()
        assertEquals(listOf(item), hidden)
        assertNull(feedback.notice.value)
    }

    private fun song(id: String) =
        SongItem(
            id = id,
            title = "Song $id",
            artists = emptyList(),
            thumbnail = "",
            feedbackToken = "feedback-$id",
        )
}

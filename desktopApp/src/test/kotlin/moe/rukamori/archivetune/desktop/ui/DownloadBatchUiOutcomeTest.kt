package moe.rukamori.archivetune.desktop.ui

import moe.rukamori.archivetune.desktop.platform.DownloadBatchResult
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadBatchUiOutcomeTest {
    @Test
    fun `partial batch navigates to downloads and reports success and failure counts`() {
        val outcome = DownloadBatchResult(
            requested = 10,
            queuedIds = List(8) { "queued-$it" },
            failures = listOf("Song A: offline", "Song B: unavailable"),
        ).toUiOutcome()

        assertTrue(outcome.navigateToDownloads)
        assertTrue(outcome.message.orEmpty().contains("Queued 8/10 songs"))
        assertTrue(outcome.message.orEmpty().contains("2 failed"))
        assertTrue(outcome.message.orEmpty().contains("Song A: offline"))
    }

    @Test
    fun `total batch failure stays on current surface with clear error counts`() {
        val outcome = DownloadBatchResult(
            requested = 3,
            queuedIds = emptyList(),
            failures = listOf("A failed", "B failed", "C failed"),
        ).toUiOutcome()

        assertFalse(outcome.navigateToDownloads)
        assertTrue(outcome.message.orEmpty().contains("Could not queue any of 3 songs"))
        assertTrue(outcome.message.orEmpty().contains("3 failed"))
    }

    @Test
    fun `total success keeps existing navigate-to-downloads behavior without warning`() {
        val outcome = DownloadBatchResult(
            requested = 3,
            queuedIds = listOf("a", "b", "c"),
            failures = emptyList(),
        ).toUiOutcome()

        assertTrue(outcome.navigateToDownloads)
        assertNull(outcome.message)
    }
}

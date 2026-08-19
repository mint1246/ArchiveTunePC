package moe.rukamori.archivetune.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalLibraryScanFeedbackTest {
    @Test
    fun `idle without folders asks for a folder and cannot rescan`() {
        val feedback = localLibraryScanFeedback(
            folderCount = 0,
            trackCount = 0,
            scanning = false,
            error = null,
        )

        assertEquals("Add a folder to index local music.", feedback.status)
        assertFalse(feedback.showProgress)
        assertFalse(feedback.showRetry)
        assertFalse(feedback.rescanEnabled)
        assertTrue(feedback.folderActionsEnabled)
    }

    @Test
    fun `scanning takes precedence over stale errors and prevents overlapping scan actions`() {
        val feedback = localLibraryScanFeedback(
            folderCount = 2,
            trackCount = 37,
            scanning = true,
            error = "old failure",
        )

        assertEquals("Scanning local library…", feedback.status)
        assertTrue(feedback.showProgress)
        assertFalse(feedback.showRetry)
        assertFalse(feedback.rescanEnabled)
        assertFalse(feedback.folderActionsEnabled)
    }

    @Test
    fun `failure preserves error and exposes retry when a folder exists`() {
        val feedback = localLibraryScanFeedback(
            folderCount = 1,
            trackCount = 12,
            scanning = false,
            error = "Could not read music folder",
        )

        assertEquals("Could not read music folder", feedback.status)
        assertFalse(feedback.showProgress)
        assertTrue(feedback.showRetry)
        assertTrue(feedback.rescanEnabled)
        assertTrue(feedback.folderActionsEnabled)
    }

    @Test
    fun `successful scan reports updated track and folder counts`() {
        val feedback = localLibraryScanFeedback(
            folderCount = 2,
            trackCount = 37,
            scanning = false,
            error = null,
        )

        assertEquals("37 tracks indexed from 2 folders.", feedback.status)
        assertFalse(feedback.showProgress)
        assertFalse(feedback.showRetry)
        assertTrue(feedback.rescanEnabled)
        assertTrue(feedback.folderActionsEnabled)
    }

    @Test
    fun `singular successful count is grammatical`() {
        val feedback = localLibraryScanFeedback(
            folderCount = 1,
            trackCount = 1,
            scanning = false,
            error = null,
        )

        assertEquals("1 track indexed from 1 folder.", feedback.status)
    }
}

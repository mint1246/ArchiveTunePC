/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmedia

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupportedLocalAudioTest {
    @Test
    fun `supported extension is accepted even without a usable mime type`() {
        assertTrue(SupportedLocalAudio.isSupported("Song.FLAC", null))
        assertTrue(SupportedLocalAudio.isSupported("recording.3ga", "audio/*"))
    }

    @Test
    fun `blocked extension wins over an otherwise supported mime type`() {
        assertFalse(SupportedLocalAudio.isSupported("mix.wv", "audio/x-flac"))
        assertFalse(SupportedLocalAudio.isSupported("album.cue", "audio/mpeg"))
    }

    @Test
    fun `mime type with parameters and casing is normalized`() {
        assertTrue(SupportedLocalAudio.isSupported("no-extension", " Audio/OGG ; charset=utf-8"))
        assertFalse(SupportedLocalAudio.isSupported("no-extension", "application/pdf"))
    }

    @Test
    fun `mime-only check accepts wildcard audio type`() {
        assertTrue(SupportedLocalAudio.isSupportedMimeType(" AUDIO/* "))
        assertTrue(SupportedLocalAudio.isSupportedMimeType("video/mp4; codecs=avc1"))
        assertFalse(SupportedLocalAudio.isSupportedMimeType("audio/wildcard"))
    }
}

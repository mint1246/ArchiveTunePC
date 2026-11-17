/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmedia

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbeddedLyricsParserTest {
    private val parser = EmbeddedLyricsParser()

    @Test
    fun `ID3v2_3 USLT unsynchronized lyrics are returned`() {
        val payload = concat(byteArrayOf(0), "eng".toByteArray(StandardCharsets.ISO_8859_1), byteArrayOf(0), "hello lyrics".toByteArray(StandardCharsets.ISO_8859_1))
        val tag = id3Tag(majorVersion = 3, frames = id3FrameV3("USLT", payload))

        assertEquals("hello lyrics", parser.parse(bytes(tag), "song.mp3", "audio/mpeg"))
    }

    @Test
    fun `ID3v2_2 ULT short frame id is still parsed`() {
        val payload = concat(byteArrayOf(0), "eng".toByteArray(StandardCharsets.ISO_8859_1), byteArrayOf(0), "old tag".toByteArray(StandardCharsets.ISO_8859_1))
        val tag = id3Tag(majorVersion = 2, frames = id3FrameV2("ULT", payload))

        assertEquals("old tag", parser.parse(bytes(tag), "song.mp3", "audio/mpeg"))
    }

    @Test
    fun `ID3v2_4 SYLT produces LRC lines sorted by timestamp`() {
        val payload =
            concat(
                byteArrayOf(0),
                "eng".toByteArray(StandardCharsets.ISO_8859_1),
                byteArrayOf(2, 1),
                byteArrayOf(0),
                "second".toByteArray(StandardCharsets.ISO_8859_1),
                byteArrayOf(0),
                be32(2_000),
                "first".toByteArray(StandardCharsets.ISO_8859_1),
                byteArrayOf(0),
                be32(1_500),
            )
        val tag = id3Tag(majorVersion = 4, frames = id3FrameV4("SYLT", payload))

        assertEquals("[00:01.50]first\n[00:02.00]second", parser.parse(bytes(tag), "song.mp3", "audio/mpeg"))
    }

    @Test
    fun `ID3 unsynchronization is removed before frame parsing`() {
        // Raw on-disk payload stores 0xFF as 0xFF 0x00; frame/tag sizes describe the de-unsynchronized bytes.
        val rawPayload =
            concat(
                byteArrayOf(0),
                "eng".toByteArray(StandardCharsets.ISO_8859_1),
                byteArrayOf(0),
                byteArrayOf('a'.code.toByte(), 0xFF.toByte(), 0x00.toByte(), 'b'.code.toByte()),
            )
        val rawFrame =
            concat(
                "USLT".toByteArray(StandardCharsets.ISO_8859_1),
                be32(8),
                byteArrayOf(0, 0),
                rawPayload,
            )
        val tag =
            concat(
                byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0x80.toByte()),
                synchsafe(rawFrame.size),
                rawFrame,
            )

        assertEquals("a\u00FFb", parser.parse(bytes(tag), "song.mp3", "audio/mpeg"))
    }

    @Test
    fun `FLAC vorbis comment lyrics are returned`() {
        val comment = vorbisComment("LYRICS=flac line")
        val block = concat(byteArrayOf((0x80 or 4).toByte()), uint24(comment.size), comment)
        val flac = concat("fLaC".toByteArray(StandardCharsets.US_ASCII), block)

        assertEquals("flac line", parser.parse(bytes(flac), "song.flac", "audio/flac"))
    }

    @Test
    fun `Opus tags synced lyrics take priority over unsynced`() {
        val comment = vorbisComment("LYRICS=unsynced", "SYNCEDLYRICS=[00:01.00]synced")
        val opus = concat("OpusTags".toByteArray(StandardCharsets.US_ASCII), comment)

        assertEquals("[00:01.00]synced", parser.parse(bytes(opus), "song.opus", "audio/opus"))
    }

    @Test
    fun `MP4 lyrics box is found through container boxes`() {
        val dataPayload = concat(ByteArray(8), "mp4 lyric".toByteArray(StandardCharsets.UTF_8))
        val dataBox = mp4Box("data", dataPayload)
        val lyricsBox = mp4Box(byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte()), dataBox)
        val mp4 = mp4Box("moov", mp4Box("udta", mp4Box("ilst", lyricsBox)))

        assertEquals("mp4 lyric", parser.parse(bytes(mp4), "song.m4a", "audio/mp4"))
    }

    @Test
    fun `unknown extension falls back to raw vorbis comment scan`() {
        val comment = vorbisComment("UNSYNCEDLYRICS=fallback")
        val header = byteArrayOf(0x03) + "vorbis".toByteArray(StandardCharsets.US_ASCII)

        assertEquals("fallback", parser.parse(bytes(concat(header, comment)), "song.bin", "application/octet-stream"))
    }

    @Test
    fun `missing stream yields null without reporting an error`() {
        val errors = mutableListOf<Throwable>()
        val failingParser = EmbeddedLyricsParser(onError = errors::add)

        assertNull(failingParser.parse({ null }, "song.mp3", "audio/mpeg"))
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `read failures are reported and converted to null`() {
        val errors = mutableListOf<Throwable>()
        val failingParser = EmbeddedLyricsParser(onError = errors::add)

        assertNull(
            failingParser.parse({ throw IllegalStateException("provider closed") }, "song.mp3", "audio/mpeg"),
        )
        assertEquals(1, errors.size)
        assertEquals("provider closed", errors.single().message)
    }

    @Test
    fun `cancellation is never swallowed as an ordinary parse failure`() {
        val errors = mutableListOf<Throwable>()
        val failingParser = EmbeddedLyricsParser(onError = errors::add)

        assertFailsWith<CancellationException> {
            failingParser.parse({ throw CancellationException() }, "song.mp3", "audio/mpeg")
        }
        assertTrue(errors.isEmpty())
    }
}

private fun bytes(data: ByteArray): () -> java.io.InputStream = { ByteArrayInputStream(data) }

private fun concat(vararg parts: ByteArray): ByteArray {
    val result = ByteArray(parts.sumOf { it.size })
    var offset = 0
    parts.forEach { part ->
        part.copyInto(result, offset)
        offset += part.size
    }
    return result
}

private fun be32(value: Int): ByteArray =
    byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

private fun le32(value: Int): ByteArray =
    byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte(),
    )

private fun uint24(value: Int): ByteArray =
    byteArrayOf(
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

private fun synchsafe(value: Int): ByteArray =
    byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )

private fun id3Tag(
    majorVersion: Int,
    frames: ByteArray,
    flags: Int = 0,
): ByteArray =
    concat(
        byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), majorVersion.toByte(), 0, flags.toByte()),
        synchsafe(frames.size),
        frames,
    )

private fun id3FrameV2(
    id: String,
    payload: ByteArray,
): ByteArray =
    concat(id.toByteArray(StandardCharsets.ISO_8859_1), uint24(payload.size), payload)

private fun id3FrameV3(
    id: String,
    payload: ByteArray,
): ByteArray =
    concat(id.toByteArray(StandardCharsets.ISO_8859_1), be32(payload.size), byteArrayOf(0, 0), payload)

private fun id3FrameV4(
    id: String,
    payload: ByteArray,
): ByteArray =
    concat(id.toByteArray(StandardCharsets.ISO_8859_1), synchsafe(payload.size), byteArrayOf(0, 0), payload)

private fun vorbisComment(vararg comments: String): ByteArray {
    val vendor = "test".toByteArray(StandardCharsets.UTF_8)
    val parts = mutableListOf<ByteArray>(le32(vendor.size), vendor, le32(comments.size))
    comments.forEach { comment ->
        val bytes = comment.toByteArray(StandardCharsets.UTF_8)
        parts += le32(bytes.size)
        parts += bytes
    }
    return concat(*parts.toTypedArray())
}

private fun mp4Box(
    type: String,
    payload: ByteArray,
): ByteArray = mp4Box(type.toByteArray(StandardCharsets.ISO_8859_1), payload)

private fun mp4Box(
    type: ByteArray,
    payload: ByteArray,
): ByteArray = concat(be32(8 + payload.size), type, payload)

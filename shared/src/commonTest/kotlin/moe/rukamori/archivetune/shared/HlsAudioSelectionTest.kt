package moe.rukamori.archivetune.shared

import kotlinx.serialization.json.Json
import kotlin.test.*

class HlsAudioSelectionTest {
    @Test fun nativePreferenceKeepsClickedIdentityAndUsesHlsTimeline() {
        val response = Json.parseToJsonElement(CatalogFixtures.player(hls = CatalogFixtures.HLS))
        val result = CatalogParsing.resolve(response, CatalogFixtures.CLICKED, preferHls = true)
        assertEquals(CatalogFixtures.CLICKED, result.trackId)
        assertEquals(CatalogFixtures.HLS, result.url)
        assertEquals("application/vnd.apple.mpegurl", result.mimeType)
        assertEquals(CatalogFixtures.AUDIO_HIGH, CatalogParsing.resolve(response, CatalogFixtures.CLICKED).url)
        assertFailsWith<CatalogException> { CatalogParsing.resolve(response, CatalogFixtures.WRONG, preferHls = true) }
    }

    @Test fun audioRenditionUsesAacLcWithoutFetchingVideoAndRejectsForeignUris() {
        val manifest = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="233",NAME="Low, stereo",DEFAULT=YES,URI="https://manifest.googlevideo.com/low.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="234",NAME="High, stereo",DEFAULT=YES,URI="https://manifest.googlevideo.com/high.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="bad",DEFAULT=YES,URI="https://example.invalid/foreign.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=70000,CODECS="avc1.4D400C,mp4a.40.5",AUDIO="233"
            https://manifest.googlevideo.com/video-low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=170000,CODECS="avc1.4D400C,mp4a.40.2",AUDIO="234"
            https://manifest.googlevideo.com/video-high.m3u8
        """.trimIndent()
        assertEquals("https://manifest.googlevideo.com/high.m3u8", CatalogParsing.hlsAudioRendition(manifest))
        assertNull(CatalogParsing.hlsAudioRendition("#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,URI=\"https://example.invalid/foreign.m3u8\""))
        assertNull(CatalogParsing.hlsAudioRendition("#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,URI=\"https://manifest.googlevideo.com/audio.m3u8?s=encrypted\""))
    }
}

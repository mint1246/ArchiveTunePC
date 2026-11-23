package moe.rukamori.archivetune.shared

import kotlin.test.*

class AndroidArtworkUrlTest {
    @Test fun playerResizePreservesSourceFamilyAndDoesNotRewriteUnrelatedHosts() {
        assertEquals("https://lh3.googleusercontent.com/cover=w1080-h1080-l90-rj", "https://lh3.googleusercontent.com/cover=w120-h120-l90-rj".androidHighResArtwork())
        assertEquals("https://yt3.ggpht.com/cover=s1080-c-k", "https://yt3.ggpht.com/cover=s48-c-k-s96".androidHighResArtwork())
        val video = "https://i.ytimg.com/vi/aa11bb22cc3/hqdefault.jpg"
        assertEquals(video, video.androidHighResArtwork())
        val unrelated = "https://example.invalid/googleusercontent.com/w120-h120"
        assertEquals(unrelated, unrelated.androidHighResArtwork())
    }
}

package moe.rukamori.archivetune.sdk.playlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiContentFilterMatcherTest {
    private data class Item(
        val id: String,
        val creators: List<String?>,
    )

    @Test
    fun `normalizeChannelKey strips handle punctuation and lowercases`() {
        assertEquals("aiartist123", AiContentFilterMatcher.normalizeChannelKey("  @AI_Artist-123! "))
        assertNull(AiContentFilterMatcher.normalizeChannelKey("   @---  "))
    }

    @Test
    fun `parseChannelList ignores comments and blank lines`() {
        val raw =
            """
            ! Title: Blocklist
            ! Another comment
              @SynthWave_AI
            UC12345-xyz

            ! trailing comment
            @SynthWave_AI
            """.trimIndent()

        assertEquals(
            setOf("synthwaveai", "uc12345xyz"),
            AiContentFilterMatcher.parseChannelList(raw),
        )
    }

    @Test
    fun `effectiveBlockedKeys includes warnlist only when moderate confidence is enabled`() {
        val blocklist = setOf("blocked")
        val warnlist = setOf("warned")

        assertEquals(
            setOf("blocked"),
            AiContentFilterMatcher.effectiveBlockedKeys(blocklist, warnlist, includeModerateConfidence = false),
        )
        assertEquals(
            setOf("blocked", "warned"),
            AiContentFilterMatcher.effectiveBlockedKeys(blocklist, warnlist, includeModerateConfidence = true),
        )
    }

    @Test
    fun `isBlocked and filterItems match normalized creator keys when enabled`() {
        val blocked = setOf("synthwaveai", "uc999")
        val items = listOf(
            Item("keep", listOf("Real Band", "UC111")),
            Item("drop-name", listOf("@SynthWave_AI")),
            Item("drop-id", listOf("Other", "uc-999")),
        )

        assertTrue(AiContentFilterMatcher.isBlocked(listOf(null, "@SynthWave_AI"), blocked))
        assertFalse(AiContentFilterMatcher.isBlocked(listOf("Real Band"), blocked))

        assertEquals(
            listOf("keep"),
            AiContentFilterMatcher.filterItems(
                items = items,
                enabled = true,
                blockedChannelKeys = blocked,
                creatorCandidates = { it.creators.asSequence() },
            ).map(Item::id),
        )
        assertEquals(
            items,
            AiContentFilterMatcher.filterItems(
                items = items,
                enabled = false,
                blockedChannelKeys = blocked,
                creatorCandidates = { it.creators.asSequence() },
            ),
        )
    }
}

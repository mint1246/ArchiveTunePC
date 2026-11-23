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
    fun normalizeChannelKey_strips_handle_punctuation_and_lowercases() {
        assertEquals("aiartist123", AiContentFilterMatcher.normalizeChannelKey("  @AI_Artist-123! "))
        assertNull(AiContentFilterMatcher.normalizeChannelKey("   @---  "))
    }

    @Test
    fun parseChannelList_ignores_comments_and_blank_lines() {
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
    fun effectiveBlockedKeys_includes_warnlist_only_when_moderate_confidence_is_enabled() {
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
    fun isBlocked_and_filterItems_match_normalized_creator_keys_when_enabled() {
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

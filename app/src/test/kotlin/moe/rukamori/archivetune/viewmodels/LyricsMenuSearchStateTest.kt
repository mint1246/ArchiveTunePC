/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.viewmodels

import com.google.common.collect.ImmutableList
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsMenuSearchStateTest {
    @Test
    fun providerCompletion_keepsConfiguredOrder_whilePublishingProgressively() {
        val initial =
            LyricsSearchScreenState.Success(
                providers =
                    ImmutableList.of(
                        pendingProvider("first"),
                        pendingProvider("second"),
                        pendingProvider("third"),
                    ),
                results = ImmutableList.of(),
                isSearching = true,
            )

        val thirdFinished =
            initial.withProviderOutcome(
                providerIndex = 2,
                status = LyricsSearchProviderStatus.FOUND,
                results = ImmutableList.of(result("third", "third lyrics")),
            )

        assertEquals(listOf("first", "second", "third"), thirdFinished.providers.map { it.providerName })
        assertEquals(listOf("third lyrics"), thirdFinished.results.map { it.lyrics })
        assertEquals(LyricsSearchProviderStatus.SEARCHING, thirdFinished.providers[0].status)

        val firstFinished =
            thirdFinished.withProviderOutcome(
                providerIndex = 0,
                status = LyricsSearchProviderStatus.FOUND,
                results = ImmutableList.of(result("first", "first lyrics")),
            )

        assertEquals(listOf("first", "second", "third"), firstFinished.providers.map { it.providerName })
        assertEquals(listOf("first lyrics", "third lyrics"), firstFinished.results.map { it.lyrics })
        assertEquals(LyricsSearchProviderStatus.SEARCHING, firstFinished.providers[1].status)
        assertEquals(LyricsSearchProviderStatus.FOUND, firstFinished.providers[2].status)
    }

    private fun pendingProvider(name: String) =
        LyricsSearchProviderUiModel(
            id = name,
            providerName = name,
            status = LyricsSearchProviderStatus.SEARCHING,
        )

    private fun result(provider: String, lyrics: String) =
        LyricsSearchResultUiModel(
            id = "$provider:$lyrics",
            providerName = provider,
            lyrics = lyrics,
            preview = lyrics,
            lineCount = 1,
            characterCount = lyrics.length,
            isLineSynced = false,
            isWordSynced = false,
        )
}

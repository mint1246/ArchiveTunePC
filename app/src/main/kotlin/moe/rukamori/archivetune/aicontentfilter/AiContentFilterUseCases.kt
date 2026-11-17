/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.aicontentfilter

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.EpisodeItem
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.PodcastItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.YTItem
import moe.rukamori.archivetune.sdk.playlist.AiContentFilterMatcher
import javax.inject.Inject

class ObserveAiContentFilterUseCase
    @Inject
    constructor(
        private val repository: AiContentFilterRepository,
    ) {
        operator fun invoke(): Flow<Pair<AiContentFilterSettings, AiContentFilterStatus>> =
            combine(repository.observeSettings(), repository.observeStatus(), ::Pair)
    }

class UpdateAiContentFilterSettingsUseCase
    @Inject
    constructor(
        private val repository: AiContentFilterRepository,
    ) {
        suspend fun setEnabled(enabled: Boolean) {
            repository.setEnabled(enabled)
        }

        suspend fun setIncludeModerateConfidence(enabled: Boolean) {
            repository.setIncludeModerateConfidence(enabled)
        }
    }

class RefreshAiContentFilterUseCase
    @Inject
    constructor(
        private val repository: AiContentFilterRepository,
    ) {
        suspend operator fun invoke(force: Boolean): AiContentFilterRefreshResult = repository.refreshIfStale(force)
    }

class LoadAiContentFilterPolicyUseCase
    @Inject
    constructor(
        private val repository: AiContentFilterRepository,
    ) {
        suspend operator fun invoke(): AiContentFilterPolicy {
            val settings = repository.getSettings()
            if (!settings.enabled) return AiContentFilterPolicy.Disabled

            repository.refreshIfStale()
            val lists = repository.loadLists()
            val blockedKeys =
                AiContentFilterMatcher.effectiveBlockedKeys(
                    blocklist = lists.blocklist,
                    warnlist = lists.warnlist,
                    includeModerateConfidence = settings.includeModerateConfidence,
                )
            return AiContentFilterPolicy(
                enabled = true,
                blockedChannelKeys = blockedKeys,
            )
        }
    }

class FilterAiContentUseCase
    @Inject
    constructor() {
        operator fun <T : YTItem> invoke(
            items: List<T>,
            policy: AiContentFilterPolicy,
        ): List<T> =
            AiContentFilterMatcher.filterItems(
                items = items,
                enabled = policy.enabled,
                blockedChannelKeys = policy.blockedChannelKeys,
                creatorCandidates = { item -> item.creatorCandidates() },
            )

        private fun YTItem.creatorCandidates(): Sequence<String?> =
            when (this) {
                is SongItem -> {
                    artists.asSequence().flatMap { artist -> artist.candidates() }
                }

                is AlbumItem -> {
                    artists.orEmpty().asSequence().flatMap { artist -> artist.candidates() }
                }

                is PlaylistItem -> {
                    author?.candidates().orEmpty()
                }

                is ArtistItem -> {
                    sequenceOf(title, id, channelId)
                }

                is PodcastItem -> author?.candidates().orEmpty()

                is EpisodeItem -> podcast?.candidates().orEmpty()
            }

        private fun Artist.candidates(): Sequence<String?> = sequenceOf(name, id)
    }

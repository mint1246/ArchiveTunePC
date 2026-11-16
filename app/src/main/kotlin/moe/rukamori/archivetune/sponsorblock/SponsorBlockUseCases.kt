/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sponsorblock

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import moe.rukamori.archivetune.sponsorblock.core.RawSponsorBlockSegment
import moe.rukamori.archivetune.sponsorblock.core.normalizeSponsorBlockSegments

class ObserveSponsorBlockSettingsUseCase
    @Inject
    constructor(
        private val repository: SponsorBlockRepository,
    ) {
        operator fun invoke(): Flow<SponsorBlockSettings> = repository.observeSettings()
    }

class SetSponsorBlockEnabledUseCase
    @Inject
    constructor(
        private val repository: SponsorBlockRepository,
    ) {
        suspend operator fun invoke(enabled: Boolean) {
            repository.setEnabled(enabled)
        }
    }

class SetSponsorBlockCategoriesUseCase
    @Inject
    constructor(
        private val repository: SponsorBlockRepository,
    ) {
        suspend operator fun invoke(categories: Set<SponsorBlockCategory>) {
            repository.setCategories(categories)
        }
    }

class SetSponsorBlockApiUrlUseCase
    @Inject
    constructor(
        private val repository: SponsorBlockRepository,
    ) {
        suspend operator fun invoke(apiUrl: String) {
            val normalizedUrl = requireNotNull(normalizeSponsorBlockApiUrl(apiUrl))
            repository.setApiUrl(normalizedUrl)
        }
    }

class ValidateSponsorBlockApiUrlUseCase
    @Inject
    constructor() {
        operator fun invoke(apiUrl: String): Boolean = normalizeSponsorBlockApiUrl(apiUrl) != null
    }

class GetSponsorBlockSegmentsUseCase
    @Inject
    constructor(
        private val repository: SponsorBlockRepository,
    ) {
        suspend operator fun invoke(
            videoId: String,
            settings: SponsorBlockSettings,
        ): List<SponsorBlockSegment> {
            if (
                !YOUTUBE_VIDEO_ID.matches(videoId) ||
                !settings.enabled ||
                settings.categories.isEmpty()
            ) {
                return emptyList()
            }

            val orderedCategories = SponsorBlockCategory.entries.filter(settings.categories::contains)
            val segments = repository.fetchSegments(
                videoId = videoId,
                categories = orderedCategories,
                apiUrl = settings.apiUrl,
            ).mapNotNull { raw ->
                if (raw.segment.size < 2) null else RawSponsorBlockSegment(
                    startSeconds = raw.segment[0],
                    endSeconds = raw.segment[1],
                    category = raw.category,
                    actionType = raw.actionType,
                )
            }
            return normalizeSponsorBlockSegments(segments, orderedCategories.mapTo(mutableSetOf()) { it.apiValue })
                .map { SponsorBlockSegment(it.startMs, it.endMs) }
        }

        private companion object {
            val YOUTUBE_VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
        }
    }

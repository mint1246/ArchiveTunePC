/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.stream

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.constants.AudioQuality
import moe.rukamori.archivetune.innertube.NetworkGatekeeper
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.utils.hasCompleteYouTubeLoginCookies
import moe.rukamori.archivetune.morideobfuscator.youtubei.YoutubeiAudioQuality
import moe.rukamori.archivetune.morideobfuscator.youtubei.YoutubeiException
import moe.rukamori.archivetune.morideobfuscator.youtubei.YoutubeiFailureKind
import moe.rukamori.archivetune.morideobfuscator.youtubei.YoutubeiNetworkConfiguration
import moe.rukamori.archivetune.morideobfuscator.youtubei.YoutubeiResolutionPriority
import moe.rukamori.archivetune.morideobfuscator.youtubei.YoutubeiResolver
import moe.rukamori.archivetune.morideobfuscator.youtubei.YoutubeiStreamRequest
import moe.rukamori.archivetune.utils.YTPlayerUtils
import timber.log.Timber
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YoutubeiStreamRepository
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : AudioStreamRepository {
        private val resolver =
            YoutubeiResolver(
                context = context,
                diagnostics = { message -> Timber.tag("YoutubeiResolver").d(message) },
            ) {
                YoutubeiNetworkConfiguration(
                    proxy = YouTube.proxy,
                    proxyUsername = YouTube.proxyUsername,
                    proxyPassword = YouTube.proxyPassword,
                    dns = YouTube.dns,
                    interceptors = listOf(NetworkGatekeeper),
                )
            }

        override suspend fun resolve(request: AudioStreamRequest): ResolvedAudioStream =
            resolve(
                request = request,
                priority =
                    when (request.purpose) {
                        StreamPurpose.PLAYBACK -> StreamResolutionPriority.FOREGROUND
                        StreamPurpose.DOWNLOAD -> StreamResolutionPriority.BACKGROUND
                    },
            )

        internal suspend fun resolve(
            request: AudioStreamRequest,
            priority: StreamResolutionPriority,
        ): ResolvedAudioStream {
            val authState = request.authState
            if (authState.hasLoginCookie && !hasCompleteYouTubeLoginCookies(authState.cookie)) {
                throw YTPlayerUtils.InvalidPlaybackLoginContextException(
                    videoId = request.mediaId,
                    targetUrl = request.mediaUrl,
                    cause = IllegalStateException("YouTube login cookies are incomplete"),
                )
            }

            val locale = YouTube.locale
            val resolvedVisitorData =
                authState.visitorData?.takeIf(String::isNotBlank)
                    ?: if (!authState.hasLoginCookie) {
                        YouTube.visitorData().getOrNull()?.takeIf(String::isNotBlank)
                    } else {
                        null
                    }
            val primaryRequest =
                YoutubeiStreamRequest(
                    mediaId = request.mediaId,
                    quality = request.quality.toYoutubeiQuality(),
                    networkMetered = request.networkMetered,
                    authFingerprint = authState.streamCacheFingerprint,
                    pinnedItag = request.pinnedFormatId,
                    requiresSongMetadata = request.requiresSongMetadata,
                    cookie = authState.cookie.takeIf { authState.hasLoginCookie },
                    visitorData = resolvedVisitorData,
                    dataSyncId = authState.dataSyncId.takeIf { authState.hasLoginCookie },
                    sessionPoToken = authState.poTokenGvsSession.takeIf { authState.hasLoginCookie },
                    videoPoToken =
                        authState.poTokenGvs?.takeIf {
                            authState.hasLoginCookie && authState.poTokenGvsVideoId == request.mediaId
                        },
                    language = locale.hl,
                    location = locale.gl,
                    timezone = TimeZone.getDefault().id,
                )
            val resolved =
                try {
                    resolver.resolve(
                        request = primaryRequest,
                        priority = priority.toYoutubeiPriority(),
                        videoPoTokenProvider = { replacementMediaId ->
                            val replacementAuthState =
                                YTPlayerUtils.ensureYoutubeiPoTokensForPlayback(
                                    videoId = replacementMediaId,
                                    authState = authState,
                                )
                            replacementAuthState.poTokenGvs?.takeIf {
                                replacementAuthState.poTokenGvsVideoId == replacementMediaId
                            }
                        },
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: YoutubeiException) {
                    val fallbackResolved =
                        if (shouldFallbackToAnonymousVisionOs(authState.hasLoginCookie, failure)) {
                            Timber.tag("YoutubeiResolver").w(
                                "Authenticated WEB_CREATOR failed for %s (%s); retrying with anonymous VISIONOS",
                                request.mediaId,
                                failure.kind,
                            )
                            val fallbackVisitorData =
                                resolvedVisitorData
                                    ?: YouTube.visitorData().getOrNull()?.takeIf(String::isNotBlank)
                            try {
                                resolver.resolve(
                                    request =
                                        primaryRequest.copy(
                                            authFingerprint = "anon:${fallbackVisitorData.orEmpty()}",
                                            cookie = null,
                                            visitorData = fallbackVisitorData,
                                            dataSyncId = null,
                                            sessionPoToken = null,
                                            videoPoToken = null,
                                        ),
                                    priority = priority.toYoutubeiPriority(),
                                )
                            } catch (fallbackCancellation: CancellationException) {
                                throw fallbackCancellation
                            } catch (_: Throwable) {
                                null
                            }
                        } else {
                            null
                        }
                    if (fallbackResolved != null) {
                        fallbackResolved
                    } else {
                        if (failure.kind == YoutubeiFailureKind.PO_TOKEN ||
                            failure.kind == YoutubeiFailureKind.LOGIN_REQUIRED &&
                            YTPlayerUtils.isBotDetectionError(failure.message.orEmpty())
                        ) {
                            throw YTPlayerUtils.BotDetectionPlaybackException(
                                videoId = request.mediaId,
                                clients = setOf(if (authState.hasLoginCookie) "WEB_CREATOR" else "VISIONOS"),
                                cause = failure,
                            )
                        }
                        if (failure.kind == YoutubeiFailureKind.LOGIN_REQUIRED ||
                            failure.kind == YoutubeiFailureKind.HTTP && failure.httpStatus == 401
                        ) {
                            if (!failure.requiresContentConfirmation()) {
                                if (!authState.hasLoginCookie) {
                                    throw YTPlayerUtils.BadStreamPlayerResponseException(
                                        videoId = request.mediaId,
                                        failedClients = setOf("VISIONOS"),
                                        cause = failure,
                                    )
                                }
                                throw YTPlayerUtils.InvalidPlaybackLoginContextException(
                                    videoId = request.mediaId,
                                    targetUrl = request.mediaUrl,
                                    cause = failure,
                                )
                            }
                            throw YTPlayerUtils.LoginRequiredForPlaybackException(
                                videoId = request.mediaId,
                                targetUrl = request.mediaUrl,
                                reason = failure.message,
                                cause = failure,
                            )
                        }
                        throw failure
                    }
                }

            return ResolvedAudioStream(
                url = resolved.url,
                requestHeaders = resolved.requestHeaders,
                formatId = resolved.formatId,
                mimeType = resolved.mimeType,
                codecs = resolved.codecs,
                bitrate = resolved.bitrate,
                sampleRate = resolved.sampleRate,
                contentLength = resolved.contentLength,
                expiresAtMs = resolved.expiresAtMs,
                authFingerprint = authState.streamCacheFingerprint,
                source = StreamSource.YOUTUBEI,
                runtimeVersion = resolved.runtimeVersion,
                title = resolved.title,
                durationSeconds = resolved.durationSeconds,
                thumbnailUrl = resolved.thumbnailUrl,
                loudnessDb = resolved.loudnessDb,
                perceptualLoudnessDb = resolved.perceptualLoudnessDb,
                playbackTrackingUrl = resolved.playbackTrackingUrl,
            )
        }

        suspend fun preWarm() {
            resolver.preWarm()
        }

        suspend fun invalidateSessions() {
            resolver.invalidateSessions()
        }

        fun trimMemory(level: Int) {
            resolver.trimMemory(level)
        }

        private fun AudioQuality.toYoutubeiQuality(): YoutubeiAudioQuality =
            when (this) {
                AudioQuality.LOW -> YoutubeiAudioQuality.LOW
                AudioQuality.HIGH -> YoutubeiAudioQuality.HIGH
                AudioQuality.HIGHEST -> YoutubeiAudioQuality.HIGHEST
                AudioQuality.AUTO -> YoutubeiAudioQuality.AUTO
            }

        private fun StreamResolutionPriority.toYoutubeiPriority(): YoutubeiResolutionPriority =
            when (this) {
                StreamResolutionPriority.FOREGROUND -> YoutubeiResolutionPriority.FOREGROUND
                StreamResolutionPriority.BACKGROUND -> YoutubeiResolutionPriority.BACKGROUND
            }

        private val AudioStreamRequest.mediaUrl: String
            get() = "https://music.youtube.com/watch?v=$mediaId"

        private fun YoutubeiException.requiresContentConfirmation(): Boolean =
            isContentConfirmationFailure(this)

        internal companion object {
            val CONTENT_CONFIRMATION_REASONS =
                listOf(
                    "confirm your age",
                    "verify your age",
                    "age-restricted",
                    "age restricted",
                    "age verification",
                    "AGE_CHECK_REQUIRED",
                    "AGE_VERIFICATION_REQUIRED",
                    "CONTENT_CHECK_REQUIRED",
                    "inappropriate for some users",
                    "mature audiences",
                    "private video",
                    "members-only",
                    "members only",
                )

            internal fun isContentConfirmationFailure(failure: YoutubeiException): Boolean {
                if (failure.httpStatus == 401) return false
                val reason = failure.message.orEmpty()
                return CONTENT_CONFIRMATION_REASONS.any { reason.contains(it, ignoreCase = true) }
            }

            /**
             * Returns true when an authenticated WEB_CREATOR failure should retry with anonymous
             * VISIONOS (for example `Release - Topic` auto-generated tracks rejected by WEB_CREATOR
             * or slow-network PoToken/decipher failures), while keeping 401 and age/private/members
             * restrictions on the login-recovery path.
             */
            internal fun shouldFallbackToAnonymousVisionOs(
                hasLoginCookie: Boolean,
                failure: YoutubeiException,
            ): Boolean {
                if (!hasLoginCookie) return false
                if (failure.httpStatus == 401 || isContentConfirmationFailure(failure)) return false
                return when (failure.kind) {
                    YoutubeiFailureKind.UNAVAILABLE,
                    YoutubeiFailureKind.NO_FORMAT,
                    YoutubeiFailureKind.PO_TOKEN,
                    YoutubeiFailureKind.DECIPHER,
                    YoutubeiFailureKind.HTTP,
                    -> true
                    else -> false
                }
            }
        }
    }

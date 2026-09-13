/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.component

import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.LyricsClickKey
import moe.rukamori.archivetune.constants.LyricsLineBlurKey
import moe.rukamori.archivetune.constants.LyricsLineSpacingKey
import moe.rukamori.archivetune.constants.LyricsRomanizeChineseKey
import moe.rukamori.archivetune.constants.LyricsRomanizeHindiKey
import moe.rukamori.archivetune.constants.LyricsRomanizeJapaneseKey
import moe.rukamori.archivetune.constants.LyricsRomanizeKoreanKey
import moe.rukamori.archivetune.constants.LyricsRomanizeOtherLanguagesKey
import moe.rukamori.archivetune.constants.LyricsScrollKey
import moe.rukamori.archivetune.constants.LyricsTextSizeKey
import moe.rukamori.archivetune.constants.PlayerBackgroundStyle
import moe.rukamori.archivetune.constants.PlayerBackgroundStyleKey
import moe.rukamori.archivetune.db.entities.LyricsEntity
import moe.rukamori.archivetune.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import moe.rukamori.archivetune.lyrics.LyricsEntry
import moe.rukamori.archivetune.lyrics.LyricsGraphemeSpringSnapshot
import moe.rukamori.archivetune.lyrics.LyricsGraphemeSpringState
import moe.rukamori.archivetune.lyrics.LyricsGraphemeTiming
import moe.rukamori.archivetune.lyrics.LyricsInlineSpringState
import moe.rukamori.archivetune.lyrics.LyricsInterludeMotion
import moe.rukamori.archivetune.lyrics.LyricsInterludeSpringState
import moe.rukamori.archivetune.lyrics.LyricsMotionPhysics
import moe.rukamori.archivetune.lyrics.LyricsTextTimeline
import moe.rukamori.archivetune.lyrics.LyricsTextTimelineBuilder
import moe.rukamori.archivetune.lyrics.LyricsVisualClock
import moe.rukamori.archivetune.lyrics.PreparedLyrics
import moe.rukamori.archivetune.lyrics.LyricsRomanizationPreferences
import moe.rukamori.archivetune.lyrics.LyricsScrollAnchorSpring
import moe.rukamori.archivetune.lyrics.LyricsUtils.effectiveLyricsEntryEndMs
import moe.rukamori.archivetune.lyrics.LyricsUtils.findCurrentLineIndex
import moe.rukamori.archivetune.lyrics.LyricsUtils.hasWordSyncedLyrics
import moe.rukamori.archivetune.lyrics.LyricsUtils.insertInstrumentalBreaks
import moe.rukamori.archivetune.lyrics.LyricsUtils.isLineSyncedLrc
import moe.rukamori.archivetune.lyrics.LyricsUtils.isTtml
import moe.rukamori.archivetune.lyrics.LyricsUtils.parseLyrics
import moe.rukamori.archivetune.lyrics.LyricsUtils.parseTtml
import moe.rukamori.archivetune.lyrics.LyricsUtils.providedRomanizedTextForEntry
import moe.rukamori.archivetune.lyrics.LyricsUtils.providedTranslationTextForEntry
import moe.rukamori.archivetune.lyrics.LyricsUtils.romanizeLyricsLine
import moe.rukamori.archivetune.lyrics.LyricsUtils.shouldRomanizeLyricsLine
import moe.rukamori.archivetune.lyrics.WordTimestamp
import moe.rukamori.archivetune.lyrics.toLyricsEntries
import moe.rukamori.archivetune.ui.component.shimmer.ShimmerHost
import moe.rukamori.archivetune.ui.component.shimmer.TextPlaceholder
import moe.rukamori.archivetune.ui.theme.rememberArchiveTuneLyricsFontFamily
import moe.rukamori.archivetune.ui.utils.smoothFadingEdge
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.utils.reportException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

// ──────────────────────────────────────────────────────────────────────
// Constants
// ──────────────────────────────────────────────────────────────────────

/** Seconds to wait before auto-scroll resumes after manual scroll. */
private const val MANUAL_SCROLL_TIMEOUT_MS = 3000L

/** Desktop SpicyPane focal region: upper-middle, leaving more upcoming lyrics visible. */
private const val LYRIC_FOCAL_RATIO = 0.45f

/** Sentinel entry prepended so auto-scroll has headroom above the first line. */
private val HEAD_LYRICS_ENTRY = LyricsEntry(time = 0L, text = "")

private fun isRtlText(text: String): Boolean = LyricsMotionPhysics.firstStrongRtl(text) ?: false

// ──────────────────────────────────────────────────────────────────────
// Main Composable
// ──────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LyricsV2(
    sliderPositionProvider: () -> Long?,
    lyricsSyncOffset: Int,
    preparedLyrics: PreparedLyrics? = null,
    modifier: Modifier = Modifier,
    textColorOverride: Color? = null,
    lyricsLineBlurOverride: Boolean? = null,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val player = playerConnection.player
    val context = LocalContext.current

    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    // ── Preferences ──
    val (lyricsClick) = rememberPreference(LyricsClickKey, defaultValue = true)
    val (lyricsScroll) = rememberPreference(LyricsScrollKey, defaultValue = true)
    val (lyricsTextSize) = rememberPreference(LyricsTextSizeKey, defaultValue = 26f)
    val (lyricsLineSpacing) = rememberPreference(LyricsLineSpacingKey, defaultValue = 1.3f)
    val (lyricsLineBlurPreference) = rememberPreference(LyricsLineBlurKey, defaultValue = true)
    val (romanizeChinese) = rememberPreference(LyricsRomanizeChineseKey, defaultValue = true)
    val (romanizeHindi) = rememberPreference(LyricsRomanizeHindiKey, defaultValue = true)
    val (romanizeJapanese) = rememberPreference(LyricsRomanizeJapaneseKey, defaultValue = true)
    val (romanizeKorean) = rememberPreference(LyricsRomanizeKoreanKey, defaultValue = true)
    val (romanizeOtherLanguages) = rememberPreference(LyricsRomanizeOtherLanguagesKey, defaultValue = true)
    val romanizationPreferences =
        remember(
            romanizeJapanese,
            romanizeKorean,
            romanizeChinese,
            romanizeHindi,
            romanizeOtherLanguages,
        ) {
            LyricsRomanizationPreferences(
                romanizeJapanese = romanizeJapanese,
                romanizeKorean = romanizeKorean,
                romanizeChinese = romanizeChinese,
                romanizeHindi = romanizeHindi,
                romanizeOther = romanizeOtherLanguages,
            )
        }
    val lyricsFontFamily = rememberArchiveTuneLyricsFontFamily()
    val playerBackground by rememberEnumPreference(PlayerBackgroundStyleKey, PlayerBackgroundStyle.DEFAULT)

    // ── Text colour derived from background style ──
    val textColor =
        textColorOverride ?: if (playerBackground == PlayerBackgroundStyle.DEFAULT) {
            MaterialTheme.colorScheme.onBackground
        } else {
            Color.White
        }
    val lyricsLineBlur = lyricsLineBlurOverride ?: lyricsLineBlurPreference

    // ── Selection mode state ──
    var isSelectionModeActive by rememberSaveable { mutableStateOf(false) }
    val selectedIndices = remember { mutableStateListOf<Int>() }
    var showMaxSelectionToast by remember { mutableStateOf(false) }
    val maxSelectionLimit = 5
    var showShareDialog by remember { mutableStateOf(false) }
    var shareDialogData by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var showShareImageDialog by remember { mutableStateOf(false) }

    // ── Lyrics data ──
    val currentLyrics by playerConnection.currentLyrics.collectAsState(initial = null)
    val lyrics = currentLyrics?.lyrics
    val showTranslations =
        remember(currentLyrics?.source) {
            currentLyrics?.hasTranslationSource() == true
        }

    // ── Parse lyrics into entries ──
    val isSynced =
        remember(lyrics, preparedLyrics) {
            preparedLyrics?.syncType?.let { it != moe.rukamori.archivetune.lyrics.LyricsSyncType.PLAIN }
                ?: (lyrics != null && (isLineSyncedLrc(lyrics!!) || isTtml(lyrics!!)))
        }
    val isWordSyncedFormat =
        remember(lyrics, preparedLyrics) {
            preparedLyrics?.syncType == moe.rukamori.archivetune.lyrics.LyricsSyncType.WORD ||
                (preparedLyrics == null && lyrics != null && hasWordSyncedLyrics(lyrics!!))
        }
    val lyricsDurationMs = player.duration.takeIf { it > 0L } ?: 0L

    val lyricsEntries: List<LyricsEntry> =
        remember(lyrics, lyricsDurationMs, preparedLyrics) {
            if (lyrics == null || lyrics == LYRICS_NOT_FOUND) return@remember emptyList()
            val parsed =
                preparedLyrics?.toLyricsEntries()
                    ?: when {
                        isTtml(lyrics!!) -> {
                            insertInstrumentalBreaks(parseTtml(lyrics!!))
                        }

                        isLineSyncedLrc(lyrics!!) -> {
                            val parsedLines = parseLyrics(lyrics!!)
                            val desktopRenderable =
                                parsedLines.mapIndexedNotNull { index, entry ->
                                    val hasProviderEnd =
                                        entry.durationMs > 0L ||
                                            !entry.words.isNullOrEmpty() ||
                                            parsedLines.getOrNull(index + 1)?.time != null
                                    entry.takeIf { hasProviderEnd }
                                }
                            insertInstrumentalBreaks(desktopRenderable)
                        }

                        else -> {
                            lyrics!!
                                .lines()
                                .filter { it.isNotBlank() }
                                .mapIndexed { index, line ->
                                    LyricsEntry(time = -1L, text = line.trim())
                                }
                        }
                    }
            if (parsed.isNotEmpty() && parsed.first().time >= 0) {
                listOf(HEAD_LYRICS_ENTRY) + parsed
            } else {
                parsed
            }
        }

    val entriesWithWords: List<LyricsEntry> = lyricsEntries
    val entryEndTimes =
        remember(entriesWithWords, lyricsDurationMs) {
            entriesWithWords.indices.map { index ->
                effectiveLyricsEntryEndMs(entriesWithWords, index, lyricsDurationMs)
            }
        }
    val vocalEntryIndices =
        remember(entriesWithWords) {
            entriesWithWords.indices.filter { index ->
                val entry = entriesWithWords[index]
                entry != HEAD_LYRICS_ENTRY && !entry.isInstrumental && entry.time >= 0L
            }
        }
    val vocalOrdinalByEntryIndex =
        remember(vocalEntryIndices) {
            vocalEntryIndices.withIndex().associate { (ordinal, entryIndex) -> entryIndex to ordinal }
        }

    // ── Romanization ──
    LaunchedEffect(entriesWithWords, romanizationPreferences) {
        if (!romanizationPreferences.isEnabled) {
            entriesWithWords.forEach { entry ->
                if (entry.romanizedTextFlow.value != null) {
                    entry.romanizedTextFlow.value = null
                }
            }
            return@LaunchedEffect
        }

        entriesWithWords.forEach { entry ->
            val providerRomanized = providedRomanizedTextForEntry(entry, romanizationPreferences)
            if (providerRomanized != null) {
                if (entry.romanizedTextFlow.value != providerRomanized) {
                    entry.romanizedTextFlow.value = providerRomanized
                }
                return@forEach
            }

            if (!shouldRomanizeLyricsLine(entry.text, romanizationPreferences)) {
                if (entry.romanizedTextFlow.value != null) {
                    entry.romanizedTextFlow.value = null
                }
                return@forEach
            }

            launch {
                val romanized =
                    try {
                        romanizeLyricsLine(entry.text, romanizationPreferences)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        reportException(e)
                        null
                    }
                entry.romanizedTextFlow.value = romanized
            }
        }
    }

    // ── Playback position tracking ──
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var playbackPositionMs by remember { mutableLongStateOf(0L) }
    var currentLineIndex by remember { mutableIntStateOf(0) }
    val latestSliderPositionProvider = rememberUpdatedState(sliderPositionProvider)
    val activeVocalIndices =
        remember(entriesWithWords, entryEndTimes, currentPositionMs, isSynced) {
            if (!isSynced) {
                emptyList()
            } else {
                vocalEntryIndices.filter { index ->
                    val entry = entriesWithWords[index]
                    currentPositionMs >= entry.time && currentPositionMs < entryEndTimes[index]
                }
            }
        }

    LaunchedEffect(entriesWithWords, isSynced, lyricsSyncOffset) {
        if (!isSynced || entriesWithWords.isEmpty()) return@LaunchedEffect
        val visualClock = LyricsVisualClock()
        var lastSamplePosition = Long.MIN_VALUE
        while (isActive) {
            withFrameNanos { frameNanos ->
                val sliderPos = latestSliderPositionProvider.value()
                val sampledPosition = ((sliderPos ?: player.currentPosition) + lyricsSyncOffset.toLong()).coerceAtLeast(0L)
                val sampleNow = System.nanoTime()
                if (sampledPosition != lastSamplePosition) {
                    lastSamplePosition = sampledPosition
                    visualClock.updateSample(sampledPosition, sampleNow)
                }

                val visualPosition =
                    visualClock.frame(
                        frameNanos = frameNanos,
                        nowNanos = System.nanoTime(),
                        isPlaying = player.isPlaying && sliderPos == null,
                    )
                playbackPositionMs = visualPosition
                currentPositionMs = visualPosition
                currentLineIndex = findCurrentLineIndex(entriesWithWords, visualPosition, 0L)
            }
        }
    }

    // ── Scroll State ──
    val listState = rememberLazyListState()
    var isManualScrolling by remember { mutableStateOf(false) }
    var lastManualScrollTime by remember { mutableLongStateOf(0L) }
    var scrollAnchorIndex by remember(entriesWithWords) { mutableIntStateOf(-1) }
    var scrollAnchorWasInterlude by remember(entriesWithWords) { mutableStateOf(false) }

    // Detect manual scrolling
    val nestedScrollConnection =
        remember {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (!isSelectionModeActive && source == NestedScrollSource.UserInput) {
                        isManualScrolling = true
                        lastManualScrollTime = System.currentTimeMillis()
                    }
                    return Offset.Zero
                }
            }
        }

    // Resume auto-scroll after timeout
    LaunchedEffect(isManualScrolling, lastManualScrollTime) {
        if (isManualScrolling) {
            delay(MANUAL_SCROLL_TIMEOUT_MS)
            isManualScrolling = false
        }
    }

    // Keep active-line focus independent from page movement, matching desktop SpicyPane.
    // One newly-current vocal may stay below the pinned anchor; N+2 advances the page.
    // LazyList cannot translate the whole scene by a fractional scene index, so spring the
    // target row's measured pixel error toward the 45% focal point every frame instead.
    LaunchedEffect(currentLineIndex, isManualScrolling, lyricsScroll, entriesWithWords, vocalEntryIndices) {
        if (!lyricsScroll || isManualScrolling || !isSynced) return@LaunchedEffect
        if (currentLineIndex < 0 || currentLineIndex >= entriesWithWords.size) return@LaunchedEffect

        val currentEntry = entriesWithWords[currentLineIndex]
        val targetIndex =
            if (currentEntry.isInstrumental) {
                scrollAnchorWasInterlude = true
                currentLineIndex
            } else {
                val anchor =
                    LyricsMotionPhysics.pinnedScrollAnchor(
                        previousAnchorIndex = scrollAnchorIndex,
                        activeLineIndex = currentLineIndex,
                        vocalEntryIndices = vocalEntryIndices,
                        forceReset = scrollAnchorWasInterlude,
                    )
                scrollAnchorIndex = anchor
                scrollAnchorWasInterlude = false
                anchor
            }

        var anchorSpring: LyricsScrollAnchorSpring? = null
        var lastFrameNanos = 0L
        var settledFrames = 0

        while (isActive) {
            val scrollDelta =
                withFrameNanos { frameNanos ->
                    val layoutInfo = listState.layoutInfo
                    val visibleItems = layoutInfo.visibleItemsInfo
                    if (visibleItems.isEmpty() || layoutInfo.viewportSize.height <= 0) {
                        return@withFrameNanos 0f
                    }

                    val focalOffsetPx = layoutInfo.viewportSize.height * LYRIC_FOCAL_RATIO
                    val targetItem = visibleItems.firstOrNull { it.index == targetIndex }
                    val measuredErrorPx =
                        if (targetItem != null) {
                            targetItem.offset.toDouble() + targetItem.size / 2.0 - focalOffsetPx.toDouble()
                        } else {
                            // Keep large seeks continuous too. Estimate the off-screen target from
                            // current row extents; the measurement self-corrects as rows enter view.
                            val spacing = layoutInfo.mainAxisItemSpacing.toDouble()
                            val averageExtent =
                                visibleItems
                                    .map { it.size.toDouble() + spacing }
                                    .average()
                                    .coerceAtLeast(1.0)
                            val reference = visibleItems.minBy { abs(it.index - targetIndex) }
                            reference.offset.toDouble() + reference.size / 2.0 +
                                (targetIndex - reference.index) * averageExtent -
                                focalOffsetPx.toDouble()
                        }

                    val dt =
                        if (lastFrameNanos == 0L) {
                            1.0 / 60.0
                        } else {
                            ((frameNanos - lastFrameNanos) / 1e9).coerceIn(0.0, 0.1)
                        }
                    lastFrameNanos = frameNanos

                    val follower =
                        anchorSpring ?: LyricsScrollAnchorSpring(measuredErrorPx).also { anchorSpring = it }
                    val delta = follower.scrollDeltaForFrame(measuredErrorPx, dt)
                    settledFrames =
                        if (follower.canSleep() && abs(measuredErrorPx) < 0.5) settledFrames + 1 else 0
                    delta.toFloat()
                }

            if (abs(scrollDelta) > 0.01f) {
                listState.scrollBy(scrollDelta)
            }
            if (settledFrames >= 2) break
        }
    }

    BackHandler(enabled = isSelectionModeActive) {
        isSelectionModeActive = false
        selectedIndices.clear()
    }

    LaunchedEffect(showMaxSelectionToast) {
        if (showMaxSelectionToast) {
            Toast
                .makeText(
                    context,
                    context.getString(R.string.max_selection_limit, maxSelectionLimit),
                    Toast.LENGTH_SHORT,
                ).show()
            showMaxSelectionToast = false
        }
    }

    // ── Keep screen alive ──
    val activity = context as? android.app.Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ── Render ──
    BoxWithConstraints(
        contentAlignment = Alignment.TopCenter,
        modifier =
            modifier
                .fillMaxSize()
                .padding(bottom = 12.dp),
    ) {
        if (lyrics == LYRICS_NOT_FOUND) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.lyrics_not_found),
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                )
            }
            return@BoxWithConstraints
        }

        if (lyrics == null) {
            ShimmerHost {
                repeat(6) {
                    TextPlaceholder()
                }
            }
            return@BoxWithConstraints
        }

        if (entriesWithWords.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.lyrics_not_found),
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                )
            }
            return@BoxWithConstraints
        }

        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .nestedScroll(nestedScrollConnection)
                    .smoothFadingEdge(vertical = 80.dp)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            itemsIndexed(
                items = entriesWithWords,
                key = { index, entry -> "${index}_${entry.time}" },
                contentType = { _, entry ->
                    when {
                        entry == HEAD_LYRICS_ENTRY -> "head"
                        entry.isInstrumental -> "instrumental"
                        entry.words != null && isSynced -> "wordSynced"
                        else -> "lineSynced"
                    }
                },
            ) { index, item ->
                if (item == HEAD_LYRICS_ENTRY) {
                    Spacer(modifier = Modifier.height(120.dp))
                    return@itemsIndexed
                }

                // ── Instrumental break icon ──
                if (item.isInstrumental && isSynced) {
                    val startTimeMs = item.time
                    val endTimeMs = item.time + item.durationMs
                    val isActive = playbackPositionMs in startTimeMs until endTimeMs
                    if (!isActive) {
                        Spacer(modifier = Modifier.height(0.dp))
                        return@itemsIndexed
                    }
                    val distanceFromActive =
                        if (isActive) {
                            0
                        } else {
                            activeVocalIndices.minOfOrNull { activeIndex -> abs(index - activeIndex) }
                                ?: abs(index - currentLineIndex)
                        }
                    val instrAlpha = LyricsMotionPhysics.lineAlpha(distanceFromActive)
                    val animatedInstrAlpha by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = instrAlpha,
                        animationSpec =
                            spring(
                                dampingRatio = 0.92f,
                                stiffness = 180f,
                            ),
                        label = "v2InstrumentalAlpha",
                    )
                    val animatedInstrScale by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = LyricsMotionPhysics.lineScale(distanceFromActive),
                        animationSpec =
                            spring(
                                dampingRatio = 0.88f,
                                stiffness = 180f,
                            ),
                        label = "v2InstrumentalScale",
                    )
                    val targetInstrBlur =
                        if (!isSynced || isActive || isManualScrolling) {
                            0f
                        } else {
                            LyricsMotionPhysics.lineBlurDp(distanceFromActive)
                        }
                    val animatedInstrBlur by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = targetInstrBlur,
                        animationSpec =
                            spring(
                                dampingRatio = 1f,
                                stiffness = 220f,
                            ),
                        label = "v2InstrumentalBlur",
                    )
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = 12.dp,
                                    end = 12.dp,
                                    top =
                                        if (index == 0 || (index == 1 && entriesWithWords[0] == HEAD_LYRICS_ENTRY)) {
                                            0.dp
                                        } else {
                                            (lyricsLineSpacing * 8).dp
                                        },
                                    bottom = (lyricsLineSpacing * 8).dp,
                                ).then(
                                    if (lyricsLineBlur) {
                                        Modifier.blur(
                                            radiusX = animatedInstrBlur.dp,
                                            radiusY = animatedInstrBlur.dp,
                                            edgeTreatment = BlurredEdgeTreatment.Rectangle,
                                        )
                                    } else {
                                        Modifier
                                    },
                                ).graphicsLayer {
                                    scaleX = animatedInstrScale
                                    scaleY = animatedInstrScale
                                    alpha = animatedInstrAlpha
                                }.then(
                                    if (lyricsClick && item.time > 0) {
                                        Modifier.clickable { player.seekTo(item.time) }
                                    } else {
                                        Modifier
                                    },
                                ),
                    ) {
                        InstrumentalBreakItem(
                            durationMs = item.durationMs,
                            currentPositionMs = playbackPositionMs,
                            startTimeMs = startTimeMs,
                            textColor = textColor,
                            isPlaying = player.isPlaying,
                        )
                    }
                    return@itemsIndexed
                }

                // Desktop SpicyPane centers every vocal line. BiDi remains content-driven inside
                // the shaped paragraph rather than changing the row's horizontal anchor.
                val textAlign = TextAlign.Center
                val horizontalAlignment = Alignment.CenterHorizontally

                val entryEndMs = entryEndTimes.getOrElse(index) { item.time }
                val isActive =
                    isSynced &&
                        !item.isInstrumental &&
                        item.time >= 0L &&
                        currentPositionMs >= item.time &&
                        currentPositionMs < entryEndMs
                val isSelected = selectedIndices.contains(index)

                // Overlapping vocals can be active together; focus depth follows the nearest active line.
                val distanceFromActive =
                    if (!isSynced) {
                        0
                    } else if (activeVocalIndices.isNotEmpty()) {
                        val ordinal = vocalOrdinalByEntryIndex[index]
                        if (ordinal == null) {
                            abs(index - currentLineIndex)
                        } else {
                            activeVocalIndices.minOf { activeIndex ->
                                abs(ordinal - vocalOrdinalByEntryIndex.getValue(activeIndex))
                            }
                        }
                    } else {
                        abs(index - currentLineIndex)
                    }
                val lineAlpha =
                    when {
                        !isSynced -> {
                            0.92f
                        }

                        else -> LyricsMotionPhysics.lineAlpha(distanceFromActive)
                    }
                val targetBlur =
                    if (!isSynced || isActive || (isSelectionModeActive && isSelected) || isManualScrolling) {
                        0f
                    } else {
                        LyricsMotionPhysics.lineBlurDp(distanceFromActive)
                    }
                val animatedBlur by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = targetBlur,
                    animationSpec =
                        spring(
                            dampingRatio = 1f,
                            stiffness = 220f,
                        ),
                    label = "v2LyricBlur",
                )
                val animatedLineScale by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = LyricsMotionPhysics.lineScale(distanceFromActive),
                    animationSpec =
                        spring(
                            dampingRatio = 0.88f,
                            stiffness = 180f,
                        ),
                    label = "v2LineScale",
                )
                val animatedLineAlpha by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = lineAlpha,
                    animationSpec =
                        spring(
                            dampingRatio = 0.92f,
                            stiffness = 180f,
                        ),
                    label = "v2LineAlpha",
                )
                Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    color =
                                        if (isSelected && isSelectionModeActive) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                        } else {
                                            Color.Transparent
                                        },
                                    shape = RoundedCornerShape(8.dp),
                                ).padding(
                                    start = 12.dp,
                                    end = 12.dp,
                                    top =
                                        if (index == 0 ||
                                            (index == 1 && entriesWithWords[0] == HEAD_LYRICS_ENTRY)
                                        ) {
                                            0.dp
                                        } else {
                                            (lyricsLineSpacing * 8).dp
                                        },
                                    bottom = (lyricsLineSpacing * 8).dp,
                                ).then(
                                    if (lyricsLineBlur) {
                                        Modifier.blur(
                                            radiusX = animatedBlur.dp,
                                            radiusY = animatedBlur.dp,
                                            edgeTreatment = BlurredEdgeTreatment.Rectangle,
                                        )
                                    } else {
                                        Modifier
                                    },
                                ).graphicsLayer {
                                    scaleX = animatedLineScale
                                    scaleY = animatedLineScale
                                    alpha = animatedLineAlpha
                                }.combinedClickable(
                                    enabled = true,
                                    onClick = {
                                        if (isSelectionModeActive) {
                                            if (isSelected) {
                                                selectedIndices.remove(index)
                                                if (selectedIndices.isEmpty()) {
                                                    isSelectionModeActive = false
                                                }
                                            } else {
                                                if (selectedIndices.size < maxSelectionLimit) {
                                                    selectedIndices.add(index)
                                                } else {
                                                    showMaxSelectionToast = true
                                                }
                                            }
                                        } else if (lyricsClick && isSynced && item.time > 0) {
                                            player.seekTo(item.time)
                                        }
                                    },
                                    onLongClick = {
                                        if (!isSelectionModeActive) {
                                            isSelectionModeActive = true
                                            selectedIndices.add(index)
                                        } else if (!isSelected && selectedIndices.size < maxSelectionLimit) {
                                            selectedIndices.add(index)
                                        } else if (!isSelected) {
                                            showMaxSelectionToast = true
                                        }
                                    },
                                ),
                        horizontalAlignment = horizontalAlignment,
                    ) {
                        val romanizedText =
                            if (romanizationPreferences.isEnabled) {
                                val value by item.romanizedTextFlow.collectAsState()
                                value
                            } else {
                                null
                            }
                        val translationText =
                            remember(showTranslations, item.providerTranslationText, item.text) {
                                if (showTranslations) providedTranslationTextForEntry(item) else null
                            }
                        val supplementaryBaseTextStyle = MaterialTheme.typography.bodyMedium
                        val supplementaryTextStyle =
                            remember(supplementaryBaseTextStyle, lyricsTextSize, lyricsFontFamily) {
                                supplementaryBaseTextStyle.copy(
                                    fontSize = (lyricsTextSize * 0.55f).sp,
                                    lineHeight = (lyricsTextSize * 0.75f).sp,
                                    fontWeight = FontWeight.Normal,
                                    fontStyle = FontStyle.Normal,
                                    fontFamily = lyricsFontFamily ?: supplementaryBaseTextStyle.fontFamily,
                                )
                            }

                        if (romanizedText != null) {
                            Text(
                                text = romanizedText,
                                style = supplementaryTextStyle,
                                color = textColor.copy(alpha = 0.65f),
                                textAlign = textAlign,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = (lyricsTextSize * 0.18f).dp),
                            )
                        }

                        if (isSynced) {
                            val mainTimeline =
                                remember(item, entryEndMs) {
                                    LyricsTextTimelineBuilder.fromEntry(item, entryEndMs)
                                }
                            val releaseEndMs =
                                if (entryEndMs >= Long.MAX_VALUE - 700L) Long.MAX_VALUE else entryEndMs + 700L
                            val useAnimatedRenderer =
                                item.time >= 0L && currentPositionMs >= item.time && currentPositionMs < releaseEndMs
                            if (useAnimatedRenderer) {
                                LyricsLineV2(
                                    mainTimeline = mainTimeline,
                                    currentPositionMs = currentPositionMs,
                                    textColor = textColor,
                                    baseFontSize = lyricsTextSize,
                                    textAlign = textAlign,
                                    lyricsFontFamily = lyricsFontFamily,
                                    isPlaying = player.isPlaying,
                                    // Desktop SpicyPane has fixed spring/glow tuning. Do not let
                                    // old Android-only tuning preferences distort parity mode.
                                    bounceFactor = 1f,
                                    glowFactor = 1f,
                                )
                            } else {
                                Text(
                                    text = item.text,
                                    style =
                                        MaterialTheme.typography.headlineMedium.copy(
                                            fontSize = lyricsTextSize.sp,
                                            fontWeight = FontWeight.Bold,
                                            lineHeight = (lyricsTextSize * 1.35f).sp,
                                            fontFamily = lyricsFontFamily ?: MaterialTheme.typography.headlineMedium.fontFamily,
                                            letterSpacing = 0.sp,
                                            textAlign = TextAlign.Center,
                                            textDirection = TextDirection.Content,
                                        ),
                                    color = textColor,
                                    textAlign = TextAlign.Center,
                                    // ShapedLyricsLine reserves this inset while the line is in
                                    // its animated window. Keep the exact same wrapping width for
                                    // lines before/after that window so switching renderers can
                                    // never reflow the lyric text.
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = SHAPED_HORIZONTAL_OVERSCAN),
                                )
                            }
                        } else {
                            Text(
                                text = item.text,
                                style =
                                    MaterialTheme.typography.headlineMedium.copy(
                                        fontSize = lyricsTextSize.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontStyle = FontStyle.Normal,
                                        lineHeight = (lyricsTextSize * lyricsLineSpacing).sp,
                                        fontFamily = lyricsFontFamily ?: MaterialTheme.typography.headlineMedium.fontFamily,
                                        letterSpacing = 0.sp,
                                        textAlign = TextAlign.Center,
                                        textDirection = TextDirection.Content,
                                    ),
                                color = textColor,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        if (translationText != null) {
                            Text(
                                text = translationText,
                                style = supplementaryTextStyle,
                                color = textColor.copy(alpha = 0.65f),
                                textAlign = textAlign,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(top = (lyricsTextSize * 0.3f).dp),
                            )
                        }
                }
            }

            // Bottom spacer for overscroll
            item {
                Spacer(modifier = Modifier.height(300.dp))
            }
        }

        // ── Resume auto-scroll button ──
        if (isManualScrolling && isSynced) {
            androidx.compose.material3.FilledTonalButton(
                onClick = {
                    isManualScrolling = false
                },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(
                    text = "Resume",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        if (isSelectionModeActive) {
            mediaMetadata?.let { metadata ->
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(48.dp)
                                    .background(
                                        color = Color.Black.copy(alpha = 0.3f),
                                        shape = CircleShape,
                                    ).clickable {
                                        isSelectionModeActive = false
                                        selectedIndices.clear()
                                    },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.close),
                                contentDescription = stringResource(R.string.cancel),
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        Row(
                            modifier =
                                Modifier
                                    .background(
                                        color =
                                            if (selectedIndices.isNotEmpty()) {
                                                Color.White.copy(alpha = 0.9f)
                                            } else {
                                                Color.White.copy(alpha = 0.5f)
                                            },
                                        shape = RoundedCornerShape(24.dp),
                                    ).clickable(enabled = selectedIndices.isNotEmpty()) {
                                        if (selectedIndices.isNotEmpty()) {
                                            val sortedIndices = selectedIndices.sorted()
                                            val selectedLyricsText =
                                                sortedIndices
                                                    .mapNotNull { entriesWithWords.getOrNull(it)?.text }
                                                    .joinToString("\n")

                                            if (selectedLyricsText.isNotBlank()) {
                                                shareDialogData =
                                                    Triple(
                                                        selectedLyricsText,
                                                        metadata.title ?: "",
                                                        metadata.artists.joinToString { it.name },
                                                    )
                                                showShareDialog = true
                                            }
                                            isSelectionModeActive = false
                                            selectedIndices.clear()
                                        }
                                    }.padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.share),
                                contentDescription = stringResource(R.string.share_selected),
                                tint = Color.Black,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text = stringResource(R.string.share),
                                color = Color.Black,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showShareDialog && shareDialogData != null) {
        val (lyricsText, songTitle, artists) = shareDialogData!!
        BasicAlertDialog(onDismissRequest = { showShareDialog = false }) {
            Card(
                shape = RoundedCornerShape(28.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                modifier =
                    Modifier
                        .padding(16.dp)
                        .fillMaxWidth(0.85f),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = stringResource(R.string.share_lyrics),
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    shareLyricsAsText(
                                        context = context,
                                        payload = LyricsSharePayload(lyricsText, songTitle, artists),
                                        songId = mediaMetadata?.id,
                                    )
                                    showShareDialog = false
                                }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.share),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.share_as_text),
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    shareDialogData = Triple(lyricsText, songTitle, artists)
                                    showShareImageDialog = true
                                    showShareDialog = false
                                }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.share),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.share_as_image),
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Text(
                            text = stringResource(R.string.cancel),
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                            modifier =
                                Modifier
                                    .clickable { showShareDialog = false }
                                    .padding(vertical = 8.dp, horizontal = 12.dp),
                        )
                    }
                }
            }
        }
    }

    if (showShareImageDialog && shareDialogData != null) {
        val (lyricsText, songTitle, artists) = shareDialogData!!
        LyricsShareImageDialog(
            mediaMetadata = mediaMetadata,
            payload = LyricsSharePayload(lyricsText, songTitle, artists),
            onDismissRequest = { showShareImageDialog = false },
        )
    }
}

// ──────────────────────────────────────────────────────────────────────
// Line-level composable: renders words with fluid fill animation
// ──────────────────────────────────────────────────────────────────────

@Composable
private fun LyricsLineV2(
    mainTimeline: LyricsTextTimeline,
    currentPositionMs: Long,
    textColor: Color,
    baseFontSize: Float,
    textAlign: TextAlign,
    lyricsFontFamily: FontFamily?,
    isPlaying: Boolean,
    bounceFactor: Float,
    glowFactor: Float,
) {
    // Shape the provider's complete line once. Like desktop CharTimeline, every provider timed
    // token may map into that authoritative text; unmatched background tokens simply disappear.
    if (mainTimeline.displayText.isNotBlank()) {
        val mainBaseStyle = MaterialTheme.typography.headlineMedium
        ShapedLyricsLine(
            timeline = mainTimeline,
            currentPositionMs = currentPositionMs,
            isPlaying = isPlaying,
            textStyle =
                mainBaseStyle.copy(
                    fontSize = baseFontSize.sp,
                    fontWeight = FontWeight.Bold,
                    fontStyle = FontStyle.Normal,
                    lineHeight = (baseFontSize * 1.35f).sp,
                    fontFamily = lyricsFontFamily ?: mainBaseStyle.fontFamily,
                    letterSpacing = 0.sp,
                    textAlign = textAlign,
                    textDirection = TextDirection.Content,
                ),
            textColor = textColor,
            bounceFactor = bounceFactor,
            glowFactor = glowFactor,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ──────────────────────────────────────────────────────────────────────
// Instrumental break icon: music-note filled bottom-to-top over the gap
// ──────────────────────────────────────────────────────────────────────

@Composable
private fun InstrumentalBreakItem(
    durationMs: Long,
    currentPositionMs: Long,
    startTimeMs: Long,
    textColor: Color,
    isPlaying: Boolean,
) {
    val latestPosition = rememberUpdatedState(currentPositionMs)
    val springState = remember(startTimeMs, durationMs) { LyricsInterludeSpringState() }
    var frame by remember(startTimeMs, durationMs) { mutableStateOf(springState.snapshot()) }
    val pausedPositionKey = if (isPlaying) Long.MIN_VALUE else currentPositionMs

    LaunchedEffect(startTimeMs, durationMs, isPlaying, pausedPositionKey) {
        if (!isPlaying) {
            springState.setTarget(
                LyricsInterludeMotion.springTarget(startTimeMs, durationMs, latestPosition.value),
                snap = true,
            )
            frame = springState.snapshot()
            return@LaunchedEffect
        }

        var lastFrameNanos = 0L
        while (isActive) {
            withFrameNanos { frameNanos ->
                val elapsedNanos =
                    if (lastFrameNanos == 0L) {
                        16_666_667L
                    } else {
                        frameNanos - lastFrameNanos
                    }
                lastFrameNanos = frameNanos
                val dt = (elapsedNanos / 1e9).coerceIn(0.001, 0.10)
                springState.setTarget(
                    LyricsInterludeMotion.springTarget(startTimeMs, durationMs, latestPosition.value),
                )
                springState.step(dt)
                frame = springState.snapshot()
            }
        }
    }

    androidx.compose.foundation.Canvas(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(64.dp),
    ) {
        val baseRadius = 7.5.dp.toPx()
        val gap = 9.dp.toPx()
        val step = baseRadius * 2f + gap
        val groupScale = frame.groupScale.coerceIn(0f, 1f)
        val yUnit = 48.dp.toPx()

        frame.dots.forEachIndexed { index, dot ->
            val scale = dot.scale.coerceAtLeast(0f) * groupScale
            val opacity = dot.opacity.coerceIn(0f, 1f) * groupScale
            val glow = dot.glow.coerceIn(0f, 1.15f) * groupScale
            val radius = baseRadius * scale
            if (radius <= 0.1f || opacity <= 0.001f) return@forEachIndexed
            val center =
                Offset(
                    x = size.width / 2f + (index - 1) * step,
                    y = size.height / 2f + dot.yOffsetFraction * yUnit,
                )
            if (glow > 0.01f) {
                drawCircle(
                    color = textColor.copy(alpha = (0.08f * glow * opacity).coerceIn(0f, 0.16f)),
                    radius = radius * 2.6f,
                    center = center,
                )
                drawCircle(
                    color = textColor.copy(alpha = (0.16f * glow * opacity).coerceIn(0f, 0.28f)),
                    radius = radius * 1.75f,
                    center = center,
                )
            }
            drawCircle(
                color = textColor.copy(alpha = (0.90f * opacity).coerceIn(0f, 1f)),
                radius = radius,
                center = center,
            )
        }
    }
}

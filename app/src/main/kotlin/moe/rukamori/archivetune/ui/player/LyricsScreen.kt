/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.player

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.C
import androidx.media3.common.Player.STATE_BUFFERING
import androidx.media3.common.Player.STATE_READY
import androidx.navigation.NavController
import androidx.palette.graphics.Palette
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalDatabase
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.chords.ChordRequest
import moe.rukamori.archivetune.chords.ChordTrack
import moe.rukamori.archivetune.chords.ChordTrackSource
import moe.rukamori.archivetune.chords.AndroidNeuralChordAnalyzer
import moe.rukamori.archivetune.chords.SongsterrChordClient
import moe.rukamori.archivetune.chords.resolveAndroidChordPriority
import moe.rukamori.archivetune.audio.rememberPlayerBeatPulse
import moe.rukamori.archivetune.constants.BlurRadiusKey
import moe.rukamori.archivetune.constants.DisableAnimationsKey
import moe.rukamori.archivetune.constants.DisableBlurKey
import moe.rukamori.archivetune.constants.DynamicThemeKey
import moe.rukamori.archivetune.constants.EnableHapticFeedbackKey
import moe.rukamori.archivetune.constants.LyricsBackgroundStyle
import moe.rukamori.archivetune.constants.LyricsBackgroundStyleKey
import moe.rukamori.archivetune.constants.LyricsMode
import moe.rukamori.archivetune.constants.LyricsModeKey
import moe.rukamori.archivetune.constants.PlayerBackgroundStyle
import moe.rukamori.archivetune.constants.PlayerBackgroundStyleKey
import moe.rukamori.archivetune.constants.PlayerCustomBlurKey
import moe.rukamori.archivetune.constants.PlayerCustomBrightnessKey
import moe.rukamori.archivetune.constants.PlayerCustomContrastKey
import moe.rukamori.archivetune.constants.PlayerCustomImageUriKey
import moe.rukamori.archivetune.constants.ShowLyricsPlayerControlsKey
import moe.rukamori.archivetune.db.entities.LyricsEntity
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.lyrics.PreparedLyrics
import moe.rukamori.archivetune.ui.component.LocalMenuState
import moe.rukamori.archivetune.ui.component.LyricsEnhanced
import moe.rukamori.archivetune.ui.component.LyricsV2
import moe.rukamori.archivetune.ui.component.PlayerSliderTrack
import moe.rukamori.archivetune.ui.menu.LyricsMenu
import moe.rukamori.archivetune.ui.theme.DynamicAppBackdrop
import moe.rukamori.archivetune.ui.theme.PlayerColorExtractor
import moe.rukamori.archivetune.ui.theme.ThemeSeedPalette
import moe.rukamori.archivetune.ui.theme.extractThemeSeedPalette
import moe.rukamori.archivetune.utils.makeTimeString
import moe.rukamori.archivetune.utils.rememberEnumPreference
import moe.rukamori.archivetune.utils.rememberPreference
import moe.rukamori.archivetune.viewmodels.LyricsRenderScreenState
import moe.rukamori.archivetune.viewmodels.LyricsRenderViewModel
import kotlin.coroutines.cancellation.CancellationException

private val AppleMusicFallbackGradient =
    listOf(
        Color(0xFF202020),
        Color(0xFF141414),
        Color(0xFF050505),
    )

@Suppress("UNUSED_PARAMETER")
@Composable
fun LyricsScreen(
    mediaMetadata: MediaMetadata,
    onBackClick: () -> Unit,
    navController: NavController,
    lyricsSyncOffset: Int,
    onLyricsSyncOffsetChange: (Int) -> Unit,
    onQueueClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    backHandlerEnabled: Boolean = true,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val player = playerConnection.player
    val context = LocalContext.current
    val menuState = LocalMenuState.current
    val database = LocalDatabase.current
    val view = LocalView.current

    val playbackState by playerConnection.playbackState.collectAsStateWithLifecycle()
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val deviceMusicVolumeController = rememberDeviceMusicVolumeController()
    val onVolumeChange =
        remember(deviceMusicVolumeController) {
            { volume: Float ->
                deviceMusicVolumeController.setVolumeFraction(volume)
            }
        }
    val currentLyrics by playerConnection.currentLyrics.collectAsStateWithLifecycle(initialValue = null)
    val lyricsRenderViewModel: LyricsRenderViewModel = hiltViewModel()
    val lyricsRenderState by lyricsRenderViewModel.state.collectAsStateWithLifecycle()
    val preparedLyrics = (lyricsRenderState as? LyricsRenderScreenState.Success)?.lyrics
    val lyricsUnavailable = currentLyrics?.lyrics == LyricsEntity.LYRICS_NOT_FOUND
    val lyricsSourceLabel =
        when (currentLyrics?.source) {
            LyricsEntity.Source.EMBEDDED.value -> stringResource(R.string.lyrics_source_embedded)
            LyricsEntity.Source.USER_SELECTION.value -> stringResource(R.string.lyrics_source_selected)
            LyricsEntity.Source.USER_EDIT.value -> stringResource(R.string.lyrics_source_edited)
            LyricsEntity.Source.TRANSLATION.value -> stringResource(R.string.lyrics_source_translation)
            LyricsEntity.Source.AI_TRANSLATION.value -> stringResource(R.string.lyrics_source_ai_translation)
            else -> null
        }

    val (enableHapticFeedback) = rememberPreference(EnableHapticFeedbackKey, true)
    val lyricsMode by rememberEnumPreference(LyricsModeKey, LyricsMode.V2)
    val playerBackground by rememberEnumPreference(PlayerBackgroundStyleKey, PlayerBackgroundStyle.DEFAULT)
    val configuredLyricsBackground by rememberEnumPreference(LyricsBackgroundStyleKey, LyricsBackgroundStyle.DEFAULT)
    val lyricsBackground = configuredLyricsBackground.resolveFor(playerBackground)
    val dynamicThemeEnabled by rememberPreference(DynamicThemeKey, false)
    val animationsDisabled by rememberPreference(DisableAnimationsKey, false)
    val disableBlur by rememberPreference(DisableBlurKey, false)
    val blurRadius by rememberPreference(BlurRadiusKey, 48f)
    val playerCustomImageUri by rememberPreference(PlayerCustomImageUriKey, "")
    val playerCustomBlur by rememberPreference(PlayerCustomBlurKey, 0f)
    val playerCustomContrast by rememberPreference(PlayerCustomContrastKey, 1f)
    val playerCustomBrightness by rememberPreference(PlayerCustomBrightnessKey, 1f)
    val foregroundColor =
        if (lyricsBackground == LyricsBackgroundStyle.FOLLOW_THEME) {
            MaterialTheme.colorScheme.onSurface
        } else {
            Color.White
        }
    val showPlayerControlsState =
        rememberPreference(ShowLyricsPlayerControlsKey, true)
    val showPlayerControls by showPlayerControlsState
    val onShowPlayerControlsChange =
        remember(showPlayerControlsState) {
            { showControls: Boolean ->
                showPlayerControlsState.value = showControls
            }
        }

    val hapticClick =
        remember(enableHapticFeedback, view) {
            {
                if (enableHapticFeedback) {
                    view.performHapticFeedback(
                        HapticFeedbackConstants.CONTEXT_CLICK,
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
                    )
                }
            }
        }
    val lyricsHelper =
        remember(context) {
            EntryPointAccessors
                .fromApplication(
                    context.applicationContext,
                    moe.rukamori.archivetune.di.LyricsHelperEntryPoint::class.java,
                ).lyricsHelper()
        }
    val neuralChordAnalyzer =
        remember(context.applicationContext) {
            AndroidNeuralChordAnalyzer(context.applicationContext)
        }
    DisposableEffect(neuralChordAnalyzer) {
        onDispose { neuralChordAnalyzer.close() }
    }

    LaunchedEffect(mediaMetadata.id, currentLyrics?.lyrics) {
        if (currentLyrics != null) return@LaunchedEffect
        try {
            val existingLyrics =
                withContext(Dispatchers.IO) {
                    database.lyrics(mediaMetadata.id).first()
                }
            if (existingLyrics != null) return@LaunchedEffect

            val lyrics =
                withContext(Dispatchers.IO) {
                    lyricsHelper.getLyrics(mediaMetadata)
                }
            withContext(Dispatchers.IO) {
                database.query {
                    insertLyricsIfAbsent(
                        id = mediaMetadata.id,
                        lyrics = lyrics,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    val positionState = remember(mediaMetadata.id) { mutableLongStateOf(0L) }
    val durationState = remember(mediaMetadata.id) { mutableLongStateOf(C.TIME_UNSET) }
    val pitchState = remember(mediaMetadata.id) { mutableFloatStateOf(1f) }
    var sliderPosition by remember(mediaMetadata.id) { mutableStateOf<Long?>(null) }
    var showChords by remember(mediaMetadata.id) { mutableStateOf(false) }
    var chordTrack by remember(mediaMetadata.id) { mutableStateOf<ChordTrack?>(null) }

    LaunchedEffect(mediaMetadata.id, mediaMetadata.duration, durationState.longValue) {
        val durationMs =
            mediaMetadata.duration
                .takeIf { it > 0 }
                ?.toLong()
                ?.times(1_000L)
                ?: durationState.longValue.takeIf { it > 0L }
                ?: 0L
        lyricsRenderViewModel.bind(mediaMetadata.id, durationMs)
    }
    var chordLoading by remember(mediaMetadata.id) { mutableStateOf(false) }
    var chordError by remember(mediaMetadata.id) { mutableStateOf<String?>(null) }
    var chordReloadKey by remember(mediaMetadata.id) { mutableIntStateOf(0) }
    var chordLocalOnlyRetry by remember(mediaMetadata.id) { mutableStateOf(false) }
    var gradientColors by remember(mediaMetadata.thumbnailUrl) { mutableStateOf(AppleMusicFallbackGradient) }
    var lyricsBackdropArtwork by remember(mediaMetadata.thumbnailUrl) { mutableStateOf<Bitmap?>(null) }
    var lyricsBackdropPalette by remember(mediaMetadata.thumbnailUrl) { mutableStateOf<ThemeSeedPalette?>(null) }

    val useDynamicLyricsBackdrop =
        dynamicThemeEnabled && lyricsBackground == LyricsBackgroundStyle.DEFAULT
    val lyricsBackdropBeatPulse =
        rememberPlayerBeatPulse(
            isPlaying = isPlaying,
            enabledOverride = useDynamicLyricsBackdrop,
            playerConnectionOverride = playerConnection,
        )

    val gradientColorsCache =
        remember {
            object : LinkedHashMap<String, List<Color>>(20, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<String, List<Color>>) = size > 20
            }
        }
    val fallbackColor = remember { Color.Black.toArgb() }

    LaunchedEffect(mediaMetadata.id, mediaMetadata.thumbnailUrl, lyricsBackground, useDynamicLyricsBackdrop) {
        if (lyricsBackground != LyricsBackgroundStyle.DEFAULT && lyricsBackground != LyricsBackgroundStyle.COLORING) {
            gradientColors = AppleMusicFallbackGradient
            lyricsBackdropArtwork = null
            lyricsBackdropPalette = null
            return@LaunchedEffect
        }
        val thumbnailUrl = mediaMetadata.thumbnailUrl
        if (thumbnailUrl == null) {
            gradientColors = AppleMusicFallbackGradient
            lyricsBackdropArtwork = null
            lyricsBackdropPalette = null
            return@LaunchedEffect
        }

        if (lyricsBackground == LyricsBackgroundStyle.COLORING) gradientColorsCache[thumbnailUrl]?.let {
            gradientColors = it
            lyricsBackdropArtwork = null
            lyricsBackdropPalette = null
            return@LaunchedEffect
        }

        gradientColors = AppleMusicFallbackGradient

        val request =
            ImageRequest
                .Builder(context)
                .data(thumbnailUrl)
                .size(Size(PlayerColorExtractor.Config.IMAGE_SIZE, PlayerColorExtractor.Config.IMAGE_SIZE))
                .allowHardware(false)
                .build()

        val extracted =
            try {
                val image =
                    withContext(Dispatchers.IO) {
                        context.imageLoader.execute(request)
                    }.image
                if (image == null) {
                    null
                } else {
                    val bitmap = image.toBitmap()
                    withContext(Dispatchers.Default) {
                        val palette =
                            Palette
                                .from(bitmap)
                                .maximumColorCount(PlayerColorExtractor.Config.MAX_COLOR_COUNT)
                                .resizeBitmapArea(PlayerColorExtractor.Config.BITMAP_AREA)
                                .generate()
                        Triple(
                            PlayerColorExtractor.extractGradientColors(
                                palette = palette,
                                fallbackColor = fallbackColor,
                            ),
                            bitmap,
                            bitmap.extractThemeSeedPalette(),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

        gradientColors = extracted?.first ?: AppleMusicFallbackGradient
        gradientColorsCache[thumbnailUrl] = gradientColors
        if (useDynamicLyricsBackdrop) {
            lyricsBackdropArtwork = extracted?.second
            lyricsBackdropPalette = extracted?.third
        } else {
            lyricsBackdropArtwork = null
            lyricsBackdropPalette = null
        }
    }

    LaunchedEffect(player, playbackState, mediaMetadata.id) {
        if (playbackState != STATE_READY && playbackState != STATE_BUFFERING) return@LaunchedEffect
        while (isActive) {
            positionState.longValue = player.currentPosition.coerceAtLeast(0L)
            durationState.longValue = player.duration
            pitchState.floatValue = player.playbackParameters.pitch
            delay(250)
        }
    }

    LaunchedEffect(showChords, mediaMetadata.id, chordReloadKey) {
        if (!showChords || chordTrack != null || chordLoading) return@LaunchedEffect
        val artist = mediaMetadata.artists.firstOrNull()?.name?.trim().orEmpty()
        chordLoading = true
        chordError = null
        try {
            val request =
                ChordRequest(
                    title = mediaMetadata.title,
                    artist = artist.ifBlank { "Unknown artist" },
                    durationMs = mediaMetadata.duration.takeIf { it > 0 }?.times(1_000L),
                    videoId = mediaMetadata.id,
                )
            val mediaItem = player.currentMediaItem
            val result =
                if (chordLocalOnlyRetry || artist.isBlank()) {
                    neuralChordAnalyzer.analyze(mediaItem, request, forceRecompute = chordLocalOnlyRetry)
                } else {
                    val songsterr = SongsterrChordClient(userAgent = "ArchiveTune/android chord-provider")
                    resolveAndroidChordPriority(
                        authored = { songsterr.getChords(request, allowDerived = false) },
                        localNeural = { neuralChordAnalyzer.analyze(mediaItem, request) },
                        derived = { songsterr.getChords(request, allowDerived = true) },
                    )
                }
            result.onSuccess {
                chordTrack = it
                chordLocalOnlyRetry = false
            }.onFailure { chordError = it.message ?: "No usable chords were found for this song." }
        } finally {
            chordLoading = false
        }
    }

    val showLyricsMenu: (Boolean) -> Unit = { initialShowSearch ->
        menuState.show {
            LyricsMenu(
                lyricsProvider = { currentLyrics },
                mediaMetadataProvider = { mediaMetadata },
                lyricsSyncOffset = lyricsSyncOffset,
                onLyricsSyncOffsetChange = onLyricsSyncOffsetChange,
                showPlayerControlsState = showPlayerControlsState,
                onShowPlayerControlsChange = onShowPlayerControlsChange,
                initialShowSearch = initialShowSearch,
                onDismiss = menuState::dismiss,
            )
        }
    }

    val isLoading = playbackState == STATE_BUFFERING || sliderPosition != null
    val orientation = LocalConfiguration.current.orientation

    BackHandler(enabled = backHandlerEnabled, onBack = onBackClick)

    Box(
        modifier =
            modifier
                .fillMaxSize(),
    ) {
        LyricsScreenBackground(
            style = lyricsBackground,
            mediaMetadata = mediaMetadata,
            gradientColors = gradientColors,
            dynamicArtwork = lyricsBackdropArtwork,
            dynamicPalette = lyricsBackdropPalette,
            dynamicEnabled = useDynamicLyricsBackdrop,
            isPlaying = isPlaying,
            animationsDisabled = animationsDisabled,
            beatPulse = lyricsBackdropBeatPulse,
            disableBlur = disableBlur,
            blurRadius = blurRadius,
            playerCustomImageUri = playerCustomImageUri,
            playerCustomBlur = playerCustomBlur,
            playerCustomContrast = playerCustomContrast,
            playerCustomBrightness = playerCustomBrightness,
        )

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .consumeUnhandledPointerInput(),
        )

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            AppleMusicGrabber(onClick = onBackClick)
            AppleMusicTrackHeader(
                mediaMetadata = mediaMetadata,
                foregroundColor = foregroundColor,
                lyricsSourceLabel = lyricsSourceLabel,
                chordsSelected = showChords,
                onChordsClick = { showChords = !showChords },
                onSearchLyricsClick = { showLyricsMenu(true) },
                onMoreClick = { showLyricsMenu(false) },
                onDismissClick = onBackClick,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
            )

            if (orientation == Configuration.ORIENTATION_LANDSCAPE && showPlayerControls) {
                val landscapeChordWidth =
                    (LocalConfiguration.current.screenWidthDp.dp * 0.28f)
                        .coerceIn(220.dp, 320.dp)
                Row(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 36.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnimatedVisibility(
                        visible = showChords,
                        modifier =
                            Modifier
                                .width(landscapeChordWidth)
                                .fillMaxHeight(),
                        enter =
                            fadeIn(tween(if (animationsDisabled) 0 else 260)) +
                                expandHorizontally(
                                    expandFrom = Alignment.Start,
                                    animationSpec = tween(if (animationsDisabled) 0 else 360),
                                ),
                        exit =
                            fadeOut(tween(if (animationsDisabled) 0 else 180)) +
                                shrinkHorizontally(
                                    shrinkTowards = Alignment.Start,
                                    animationSpec = tween(if (animationsDisabled) 0 else 300),
                                ),
                    ) {
                        ChordContent(
                            track = chordTrack,
                            loading = chordLoading,
                            error = chordError,
                            positionMs = positionState.longValue,
                            pitchMultiplier = pitchState.floatValue,
                            onRetry = {
                                chordTrack = null
                                chordError = null
                                chordLocalOnlyRetry = false
                                chordReloadKey++
                            },
                            onLocalModelRetry = {
                                chordTrack = null
                                chordError = null
                                chordLocalOnlyRetry = true
                                chordReloadKey++
                            },
                            onSeek = {
                                player.seekTo(it)
                                positionState.longValue = it
                            },
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(end = 24.dp),
                        )
                    }

                    AppleMusicLyricsPane(
                        lyricsMode = lyricsMode,
                        preparedLyrics = preparedLyrics,
                        foregroundColor = foregroundColor,
                        sliderPositionProvider = { sliderPosition },
                        lyricsSyncOffset = lyricsSyncOffset,
                        lyricsUnavailable = lyricsUnavailable,
                        animationsDisabled = animationsDisabled,
                        onSearchLyricsClick = { showLyricsMenu(true) },
                        modifier =
                            Modifier
                                .weight(1.15f)
                                .fillMaxHeight()
                                .padding(end = 32.dp),
                    )

                    Column(
                        modifier =
                            Modifier
                                .weight(if (showChords) 0.62f else 0.85f)
                                .widthIn(max = 420.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        AppleMusicControls(
                            positionProvider = { positionState.longValue },
                            durationProvider = { durationState.longValue },
                            sliderPosition = sliderPosition,
                            isPlaying = isPlaying,
                            isLoading = isLoading,
                            volume = deviceMusicVolumeController.volumeFraction,
                            onPositionChange = { sliderPosition = it },
                            onPositionChangeFinished = {
                                sliderPosition?.let {
                                    player.seekTo(it)
                                    positionState.longValue = it
                                }
                                sliderPosition = null
                            },
                            onVolumeChange = onVolumeChange,
                            onPreviousClick = {
                                hapticClick()
                                playerConnection.seekToPrevious()
                            },
                            onPlayPauseClick = {
                                hapticClick()
                                player.togglePlayPause()
                            },
                            onNextClick = {
                                hapticClick()
                                playerConnection.seekToNext()
                            },
                            foregroundColor = foregroundColor,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else {
                AnimatedContent(
                    targetState = showChords,
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    transitionSpec = {
                        val enterDuration = if (animationsDisabled) 0 else 320
                        val exitDuration = if (animationsDisabled) 0 else 220
                        if (targetState) {
                            (
                                fadeIn(tween(enterDuration)) +
                                    slideInHorizontally(tween(enterDuration)) { width -> -width / 8 }
                            ) togetherWith (
                                fadeOut(tween(exitDuration)) +
                                    slideOutHorizontally(tween(exitDuration)) { width -> width / 10 }
                            )
                        } else {
                            (
                                fadeIn(tween(enterDuration)) +
                                    slideInHorizontally(tween(enterDuration)) { width -> width / 8 }
                            ) togetherWith (
                                fadeOut(tween(exitDuration)) +
                                    slideOutHorizontally(tween(exitDuration)) { width -> -width / 10 }
                            )
                        }
                    },
                    label = "lyrics-surface-mode",
                ) { chordsVisible ->
                    if (chordsVisible) {
                        ChordContent(
                            track = chordTrack,
                            loading = chordLoading,
                            error = chordError,
                            positionMs = positionState.longValue,
                            pitchMultiplier = pitchState.floatValue,
                            onRetry = {
                                chordTrack = null
                                chordError = null
                                chordLocalOnlyRetry = false
                                chordReloadKey++
                            },
                            onLocalModelRetry = {
                                chordTrack = null
                                chordError = null
                                chordLocalOnlyRetry = true
                                chordReloadKey++
                            },
                            onSeek = {
                                player.seekTo(it)
                                positionState.longValue = it
                            },
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    } else {
                        AppleMusicLyricsPane(
                            lyricsMode = lyricsMode,
                            preparedLyrics = preparedLyrics,
                            foregroundColor = foregroundColor,
                            sliderPositionProvider = { sliderPosition },
                            lyricsSyncOffset = lyricsSyncOffset,
                            lyricsUnavailable = lyricsUnavailable,
                            animationsDisabled = animationsDisabled,
                            onSearchLyricsClick = { showLyricsMenu(true) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                if (showPlayerControls) {
                    AppleMusicControls(
                        positionProvider = { positionState.longValue },
                        durationProvider = { durationState.longValue },
                        sliderPosition = sliderPosition,
                        isPlaying = isPlaying,
                        isLoading = isLoading,
                        volume = deviceMusicVolumeController.volumeFraction,
                        onPositionChange = { sliderPosition = it },
                        onPositionChangeFinished = {
                            sliderPosition?.let {
                                player.seekTo(it)
                                positionState.longValue = it
                            }
                            sliderPosition = null
                        },
                        onVolumeChange = onVolumeChange,
                        onPreviousClick = {
                            hapticClick()
                            playerConnection.seekToPrevious()
                        },
                        onPlayPauseClick = {
                            hapticClick()
                            player.togglePlayPause()
                        },
                        onNextClick = {
                            hapticClick()
                            playerConnection.seekToNext()
                        },
                        foregroundColor = foregroundColor,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 40.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricsScreenBackground(
    style: LyricsBackgroundStyle,
    mediaMetadata: MediaMetadata,
    gradientColors: List<Color>,
    dynamicArtwork: Bitmap?,
    dynamicPalette: ThemeSeedPalette?,
    dynamicEnabled: Boolean,
    isPlaying: Boolean,
    animationsDisabled: Boolean,
    beatPulse: Float,
    disableBlur: Boolean,
    blurRadius: Float,
    playerCustomImageUri: String,
    playerCustomBlur: Float,
    playerCustomContrast: Float,
    playerCustomBrightness: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(
                    if (style == LyricsBackgroundStyle.FOLLOW_THEME) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        Color.Black
                    },
                ),
    ) {
        when (style) {
            LyricsBackgroundStyle.DEFAULT -> {
                if (dynamicEnabled && dynamicArtwork != null && dynamicPalette != null) {
                    DynamicAppBackdrop(
                        artwork = dynamicArtwork,
                        palette = dynamicPalette,
                        enabled = true,
                        isPlaying = isPlaying,
                        animationsDisabled = animationsDisabled,
                        beatPulse = beatPulse,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    AppleMusicBackground(
                        mediaMetadata = mediaMetadata,
                        gradientColors = gradientColors,
                    )
                }
            }

            LyricsBackgroundStyle.FOLLOW_THEME -> Unit

            LyricsBackgroundStyle.COLORING,
            LyricsBackgroundStyle.CUSTOM,
            -> {
                PlayerBackground(
                    playerBackground =
                        if (style == LyricsBackgroundStyle.CUSTOM) {
                            PlayerBackgroundStyle.CUSTOM
                        } else {
                            PlayerBackgroundStyle.COLORING
                        },
                    mediaMetadata = mediaMetadata,
                    gradientColors = gradientColors,
                    disableBlur = disableBlur,
                    blurRadius = blurRadius,
                    playerCustomImageUri = playerCustomImageUri,
                    playerCustomBlur = playerCustomBlur,
                    playerCustomContrast = playerCustomContrast,
                    playerCustomBrightness = playerCustomBrightness,
                )
            }
        }
    }
}

@Composable
private fun AppleMusicBackground(
    mediaMetadata: MediaMetadata,
    gradientColors: List<Color>,
    modifier: Modifier = Modifier,
) {
    val colors = if (gradientColors.isNotEmpty()) gradientColors else AppleMusicFallbackGradient
    val backgroundBrush =
        remember(colors) {
            Brush.verticalGradient(
                listOf(
                    colors.getOrElse(0) { AppleMusicFallbackGradient[0] }.copy(alpha = 0.88f),
                    colors.getOrElse(1) { AppleMusicFallbackGradient[1] }.copy(alpha = 0.76f),
                    colors.getOrElse(2) { AppleMusicFallbackGradient[2] }.copy(alpha = 0.96f),
                ),
            )
        }
    val bottomScrim =
        remember {
            Brush.verticalGradient(
                listOf(
                    Color.Transparent,
                    Color.Black.copy(alpha = 0.28f),
                ),
            )
        }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(AppleMusicFallbackGradient.last()),
    ) {
        AnimatedContent(
            targetState = mediaMetadata.thumbnailUrl,
            transitionSpec = { fadeIn(tween(700)) togetherWith fadeOut(tween(700)) },
            label = "lyrics-apple-background",
        ) { thumbnailUrl ->
            if (thumbnailUrl != null) {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .blur(46.dp)
                            .alpha(0.62f),
                )
            }
        }
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(backgroundBrush),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.18f)),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(bottomScrim),
        )
    }
}

@Composable
private fun AppleMusicGrabber(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val closeDescription = stringResource(R.string.close)
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(44.dp)
                .semantics { contentDescription = closeDescription }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                ),
    )
}

@Composable
private fun AppleMusicTrackHeader(
    mediaMetadata: MediaMetadata,
    foregroundColor: Color,
    lyricsSourceLabel: String?,
    chordsSelected: Boolean,
    onChordsClick: () -> Unit,
    onSearchLyricsClick: () -> Unit,
    onMoreClick: () -> Unit,
    onDismissClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val artistText =
        remember(mediaMetadata.id, mediaMetadata.artists) {
            mediaMetadata.artists.joinToString { it.name }
        }

    Row(
        modifier = modifier.heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(foregroundColor.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = mediaMetadata.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (mediaMetadata.thumbnailUrl == null) {
                Icon(
                    painter = painterResource(R.drawable.music_note),
                    contentDescription = null,
                    tint = foregroundColor.copy(alpha = 0.72f),
                    modifier = Modifier.size(26.dp),
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = mediaMetadata.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = foregroundColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = artistText,
                style = MaterialTheme.typography.bodyLarge,
                color = foregroundColor.copy(alpha = 0.72f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    listOfNotNull(
                        lyricsSourceLabel,
                        stringResource(R.string.lyrics_find_another_version),
                    ).joinToString("  ·  "),
                style = MaterialTheme.typography.labelMedium,
                color = foregroundColor.copy(alpha = 0.68f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onSearchLyricsClick,
                    ),
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        AppleMusicHeaderIconButton(
            iconRes = R.drawable.graphic_eq,
            contentDescription = if (chordsSelected) "Show lyrics" else "Show chords",
            foregroundColor = if (chordsSelected) foregroundColor else foregroundColor.copy(alpha = 0.82f),
            onClick = onChordsClick,
        )

        Spacer(modifier = Modifier.width(4.dp))

        AppleMusicHeaderIconButton(
            iconRes = R.drawable.close,
            contentDescription = stringResource(R.string.close),
            foregroundColor = foregroundColor,
            onClick = onDismissClick,
        )

        Spacer(modifier = Modifier.width(4.dp))

        AppleMusicHeaderIconButton(
            iconRes = R.drawable.more_horiz,
            contentDescription = stringResource(R.string.more_options),
            foregroundColor = foregroundColor,
            onClick = onMoreClick,
        )
    }
}

@Composable
private fun ChordContent(
    track: ChordTrack?,
    loading: Boolean,
    error: String?,
    positionMs: Long,
    pitchMultiplier: Float,
    onRetry: () -> Unit,
    onLocalModelRetry: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        track != null -> {
            if (track.source == ChordTrackSource.SONGSTERR_DERIVED_FROM_TAB) {
                Column(modifier = modifier) {
                    AndroidChordPanel(
                        track = track,
                        positionMs = positionMs,
                        pitchMultiplier = pitchMultiplier,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        onSeek = onSeek,
                    )
                    androidx.compose.material3.TextButton(
                        onClick = onLocalModelRetry,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text("Try local neural chords", color = Color.White)
                    }
                }
            } else {
                AndroidChordPanel(
                    track = track,
                    positionMs = positionMs,
                    pitchMultiplier = pitchMultiplier,
                    modifier = modifier,
                    onSeek = onSeek,
                )
            }
        }

        loading -> {
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                CircularWavyProgressIndicator(color = Color.White.copy(alpha = 0.86f))
            }
        }

        else -> {
            Column(
                modifier = modifier.padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    error ?: "No usable chords were found for this song.",
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.TextButton(onClick = onRetry) {
                        Text("Retry", color = Color.White)
                    }
                    androidx.compose.material3.TextButton(onClick = onLocalModelRetry) {
                        Text("Retry local model", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppleMusicHeaderIconButton(
    iconRes: Int,
    contentDescription: String,
    foregroundColor: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(48.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = false, radius = 24.dp),
                    role = Role.Button,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(foregroundColor.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                tint = foregroundColor,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun AppleMusicLyricsPane(
    lyricsMode: LyricsMode,
    preparedLyrics: PreparedLyrics?,
    foregroundColor: Color,
    sliderPositionProvider: () -> Long?,
    lyricsSyncOffset: Int,
    lyricsUnavailable: Boolean,
    animationsDisabled: Boolean,
    onSearchLyricsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val horizontalPadding = (maxWidth * 0.06f).coerceIn(20.dp, 48.dp)
        AnimatedContent(
            targetState = lyricsUnavailable,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val duration = if (animationsDisabled) 0 else 240
                fadeIn(tween(duration)) togetherWith fadeOut(tween(duration))
            },
            label = "lyrics-availability",
        ) { unavailable ->
            if (unavailable) {
                LyricsUnavailableContent(
                    foregroundColor = foregroundColor,
                    onSearchLyricsClick = onSearchLyricsClick,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = horizontalPadding),
                )
            } else {
                LyricsContent(
                    lyricsMode = lyricsMode,
                    preparedLyrics = preparedLyrics,
                    sliderPositionProvider = sliderPositionProvider,
                    lyricsSyncOffset = lyricsSyncOffset,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = horizontalPadding),
                    textColor = foregroundColor,
                )
            }
        }
    }
}

@Composable
private fun LyricsUnavailableContent(
    foregroundColor: Color,
    onSearchLyricsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.lyrics_not_found),
            style = MaterialTheme.typography.titleMedium,
            color = foregroundColor.copy(alpha = 0.72f),
        )
        androidx.compose.material3.TextButton(onClick = onSearchLyricsClick) {
            Icon(
                painter = painterResource(R.drawable.search),
                contentDescription = null,
                tint = foregroundColor,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.lyrics_find_another_version),
                color = foregroundColor,
            )
        }
    }
}

@Composable
private fun AppleMusicControls(
    positionProvider: () -> Long,
    durationProvider: () -> Long,
    sliderPosition: Long?,
    isPlaying: Boolean,
    isLoading: Boolean,
    volume: Float,
    onPositionChange: (Long) -> Unit,
    onPositionChangeFinished: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onPreviousClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    foregroundColor: Color,
    modifier: Modifier = Modifier,
) {
    val position = positionProvider()
    val duration = durationProvider()
    val hasDuration = duration != C.TIME_UNSET && duration > 0L
    val safeDuration = if (hasDuration) duration else 1L
    val currentPosition = (sliderPosition ?: position).coerceIn(0L, safeDuration)
    val remainingPosition = (safeDuration - currentPosition).coerceAtLeast(0L)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppleMusicSlider(
            value = currentPosition.toFloat(),
            valueRange = 0f..safeDuration.toFloat(),
            activeColor = foregroundColor.copy(alpha = 0.94f),
            inactiveColor = foregroundColor.copy(alpha = 0.28f),
            trackHeight = 8.dp,
            onValueChange = { onPositionChange(it.toLong()) },
            onValueChangeFinished = onPositionChangeFinished,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = makeTimeString(currentPosition),
                style = MaterialTheme.typography.labelMedium,
                color = foregroundColor.copy(alpha = 0.54f),
            )
            Text(
                text = if (hasDuration) "-${makeTimeString(remainingPosition)}" else "",
                style = MaterialTheme.typography.labelMedium,
                color = foregroundColor.copy(alpha = 0.54f),
            )
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 26.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppleMusicTransportButton(
                iconRes = R.drawable.skip_previous,
                contentDescription = stringResource(R.string.widget_previous),
                iconSize = 44.dp,
                touchSize = 68.dp,
                foregroundColor = foregroundColor,
                onClick = onPreviousClick,
            )
            IconButton(
                onClick = onPlayPauseClick,
                modifier = Modifier.size(74.dp),
            ) {
                if (isLoading) {
                    CircularWavyProgressIndicator(
                        modifier = Modifier.size(42.dp),
                        color = foregroundColor,
                    )
                } else {
                    Icon(
                        painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                        contentDescription =
                            if (isPlaying) {
                                stringResource(R.string.widget_pause)
                            } else {
                                stringResource(R.string.play)
                            },
                        tint = foregroundColor,
                        modifier = Modifier.size(54.dp),
                    )
                }
            }
            AppleMusicTransportButton(
                iconRes = R.drawable.skip_next,
                contentDescription = stringResource(R.string.next),
                iconSize = 44.dp,
                touchSize = 68.dp,
                foregroundColor = foregroundColor,
                onClick = onNextClick,
            )
        }

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 26.dp, bottom = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.volume_off),
                contentDescription = stringResource(R.string.minimum_volume),
                tint = foregroundColor.copy(alpha = 0.66f),
                modifier = Modifier.size(17.dp),
            )
            AppleMusicSlider(
                value = volume.coerceIn(0f, 1f),
                valueRange = 0f..1f,
                activeColor = foregroundColor.copy(alpha = 0.88f),
                inactiveColor = foregroundColor.copy(alpha = 0.24f),
                trackHeight = 8.dp,
                onValueChange = onVolumeChange,
                onValueChangeFinished = {},
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp),
            )
            Icon(
                painter = painterResource(R.drawable.volume_up),
                contentDescription = stringResource(R.string.maximum_volume),
                tint = foregroundColor.copy(alpha = 0.66f),
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun AppleMusicTransportButton(
    iconRes: Int,
    contentDescription: String?,
    iconSize: Dp,
    touchSize: Dp,
    foregroundColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(touchSize),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = foregroundColor,
            modifier = Modifier.size(iconSize),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppleMusicSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    activeColor: Color,
    inactiveColor: Color,
    trackHeight: Dp,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeStart = valueRange.start
    val safeEnd = valueRange.endInclusive.coerceAtLeast(safeStart + 1f)
    val safeRange = safeStart..safeEnd
    val sliderColors =
        SliderDefaults.colors(
            activeTrackColor = activeColor,
            activeTickColor = activeColor,
            thumbColor = Color.Transparent,
            inactiveTrackColor = inactiveColor,
        )

    Slider(
        value = value.coerceIn(safeRange),
        valueRange = safeRange,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        colors = sliderColors,
        thumb = { Spacer(modifier = Modifier.size(0.dp)) },
        track = { sliderState ->
            PlayerSliderTrack(
                sliderState = sliderState,
                colors = sliderColors,
                trackHeight = trackHeight,
            )
        },
        modifier = modifier.height(28.dp),
    )
}

@Composable
private fun LyricsContent(
    lyricsMode: LyricsMode,
    preparedLyrics: PreparedLyrics?,
    sliderPositionProvider: () -> Long?,
    lyricsSyncOffset: Int,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    when (lyricsMode) {
        LyricsMode.V2 -> {
            LyricsV2(
                sliderPositionProvider = sliderPositionProvider,
                lyricsSyncOffset = lyricsSyncOffset,
                preparedLyrics = preparedLyrics,
                modifier = modifier,
                textColorOverride = textColor,
            )
        }

        LyricsMode.ENHANCED -> {
            LyricsEnhanced(
                sliderPositionProvider = sliderPositionProvider,
                lyricsSyncOffset = lyricsSyncOffset,
                preparedLyrics = preparedLyrics,
                modifier = modifier,
                textColorOverride = textColor,
            )
        }
    }
}

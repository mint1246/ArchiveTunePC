/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.shared

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import moe.rukamori.archivetune.shared.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import kotlin.math.roundToInt

private data class SettingsSegmentEntry(
    val key: String,
    val icon: DrawableResource,
    val title: String,
    val subtitle: String,
    val accent: Color,
    val badge: String? = null,
    val onClick: () -> Unit,
)

private fun segmentedShape(index: Int, count: Int): RoundedCornerShape {
    val outer = 28.dp
    val inner = 6.dp
    return when {
        count <= 1 -> RoundedCornerShape(outer)
        index == 0 -> RoundedCornerShape(topStart = outer, topEnd = outer, bottomStart = inner, bottomEnd = inner)
        index == count - 1 -> RoundedCornerShape(topStart = inner, topEnd = inner, bottomStart = outer, bottomEnd = outer)
        else -> RoundedCornerShape(inner)
    }
}

private val languageOptions = listOf(
    "system" to "System default",
    "en" to "English",
    "ja" to "Japanese",
    "ko" to "Korean",
    "es" to "Spanish",
    "fr" to "French",
    "de" to "German",
    "pt" to "Portuguese",
    "it" to "Italian",
    "ru" to "Russian",
    "zh" to "Chinese",
    "ar" to "Arabic",
    "he" to "Hebrew",
    "hi" to "Hindi",
    "tr" to "Turkish",
    "pl" to "Polish",
)

private val countryOptions = listOf(
    "system" to "System default",
    "US" to "United States",
    "GB" to "United Kingdom",
    "CA" to "Canada",
    "AU" to "Australia",
    "JP" to "Japan",
    "KR" to "South Korea",
    "DE" to "Germany",
    "FR" to "France",
    "ES" to "Spain",
    "BR" to "Brazil",
    "IL" to "Israel",
    "IN" to "India",
    "IT" to "Italy",
    "MX" to "Mexico",
    "NL" to "Netherlands",
    "PL" to "Poland",
)

/**
 * Exact Android 16 Settings hub and sub-screens using SettingsSegmentedItem geometry
 * (52dp circular accent badge, 28dp/6dp segmented corners, 26dp horizontal inset).
 */
@Composable
internal fun AndroidFullSettingsContent(
    controller: MusicController,
    onBack: () -> Unit,
    onOpenFriends: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val library = controller.library?.collectAsState()?.value
    val settings = library?.settings ?: MusicSettings()
    val options by controller.playbackOptions.collectAsState()
    var page by rememberSaveable { mutableStateOf("Settings") }

    if (page == "Statistics") {
        AndroidStatisticsContent(controller = controller, onBack = { page = "Settings" }, modifier = modifier)
        return
    }

    Column(modifier.fillMaxSize()) {
        if (page == "Settings") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(Res.drawable.arrow_back), "Back")
                }
                Text(
                    "Settings",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        } else {
            RouteHeading(page) { page = "Settings" }
        }

        AnimatedContent(
            targetState = page,
            label = "settingsSubRoute",
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                val pop = targetState == "Settings"
                (fadeIn(tween(220)) + slideInHorizontally { if (pop) -it / 3 else it / 3 }) togetherWith
                    (fadeOut(tween(180)) + slideOutHorizontally { if (pop) it / 3 else -it / 3 })
            },
        ) { current ->
            when (current) {
                "Settings" -> SettingsRootList(
                    library = library,
                    onNavigate = { page = it },
                )

                "Account" -> AccountSettingsPage(controller = controller, library = library, onOpenFriends = onOpenFriends)
                "Appearance" -> AppearanceSettingsPage(controller = controller, settings = settings)
                "Playback" -> PlaybackSettingsPage(controller = controller, settings = settings, speed = options.speed)
                "Lyrics" -> LyricsSettingsPage(controller = controller, settings = settings)
                "Content" -> ContentSettingsPage(controller = controller, settings = settings)
                "Behavior & Privacy" -> PrivacySettingsPage(controller = controller, settings = settings)
                "Integrations" -> IntegrationsSettingsPage(controller = controller, onOpenFriends = onOpenFriends)
                "Storage" -> StorageSettingsPage(controller = controller, library = library, settings = settings)
                "Backup & Restore" -> BackupRestoreSettingsPage(controller = controller)
                "About" -> AboutSettingsPage(controller = controller)
            }
        }
    }
}

@Composable
private fun SettingsRootList(
    library: MusicLibraryState?,
    onNavigate: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val ytName = library?.youtubeAccount?.name
    val groups = listOf(
        listOf(
            SettingsSegmentEntry(
                key = "account",
                icon = Res.drawable.account,
                title = "Account",
                subtitle = ytName ?: "Sign in and manage account settings",
                accent = colors.primary,
                onClick = { onNavigate("Account") },
            ),
            SettingsSegmentEntry(
                key = "stats",
                icon = Res.drawable.stats,
                title = "Listening Stats",
                subtitle = "Your top songs, artists, and playback time",
                accent = colors.primary,
                onClick = { onNavigate("Statistics") },
            ),
        ),
        listOf(
            SettingsSegmentEntry(
                key = "appearance",
                icon = Res.drawable.palette,
                title = "Appearance",
                subtitle = "Themes, layout, and visual styles",
                accent = colors.secondary,
                onClick = { onNavigate("Appearance") },
            ),
            SettingsSegmentEntry(
                key = "playback",
                icon = Res.drawable.music_note,
                title = "Playback",
                subtitle = "Audio quality, crossfade, and controls",
                accent = colors.tertiary,
                onClick = { onNavigate("Playback") },
            ),
            SettingsSegmentEntry(
                key = "lyrics",
                icon = Res.drawable.lyrics,
                title = "Lyrics",
                subtitle = "Providers, layout, and sync options",
                accent = colors.secondary,
                onClick = { onNavigate("Lyrics") },
            ),
            SettingsSegmentEntry(
                key = "content",
                icon = Res.drawable.language,
                title = "Content",
                subtitle = "Language, region, and content filters",
                accent = colors.primary,
                onClick = { onNavigate("Content") },
            ),
            SettingsSegmentEntry(
                key = "privacy",
                icon = Res.drawable.swipe,
                title = "Behavior & Privacy",
                subtitle = "History, haptics, and privacy controls",
                accent = colors.primary,
                onClick = { onNavigate("Behavior & Privacy") },
            ),
        ),
        listOf(
            SettingsSegmentEntry(
                key = "integrations",
                icon = Res.drawable.auto_awesome,
                title = "Integrations",
                subtitle = "Discord Friends, presence, and external services",
                accent = colors.secondary,
                onClick = { onNavigate("Integrations") },
            ),
        ),
        listOf(
            SettingsSegmentEntry(
                key = "storage",
                icon = Res.drawable.storage,
                title = "Storage",
                subtitle = "Downloads, song cache, and image cache",
                accent = colors.primary,
                onClick = { onNavigate("Storage") },
            ),
            SettingsSegmentEntry(
                key = "backup",
                icon = Res.drawable.backup,
                title = "Backup & Restore",
                subtitle = "Export and import your library and settings",
                accent = colors.primary,
                onClick = { onNavigate("Backup & Restore") },
            ),
        ),
        listOf(
            SettingsSegmentEntry(
                key = "about",
                icon = Res.drawable.info,
                title = "About",
                subtitle = "Version, contributors, and licenses",
                accent = colors.secondary,
                badge = "15.2.0",
                onClick = { onNavigate("About") },
            ),
        ),
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 140.dp),
    ) {
        groups.forEachIndexed { groupIndex, group ->
            if (groupIndex > 0) {
                item(key = "gap_$groupIndex") { Spacer(Modifier.height(14.dp)) }
            }
            itemsIndexed(group, key = { _, item -> item.key }) { index, item ->
                SettingsSegmentedCard(
                    item = item,
                    index = index,
                    count = group.size,
                    modifier = Modifier
                        .padding(horizontal = 26.dp)
                        .padding(bottom = if (index < group.lastIndex) 2.dp else 0.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsSegmentedCard(
    item: SettingsSegmentEntry,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
) {
    val shape = remember(index, count) { segmentedShape(index, count) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "settingsCardScale",
    )
    val iconTint = contentColorFor(item.accent).let {
        if (it == Color.Unspecified) MaterialTheme.colorScheme.surface else it
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .clickable(interactionSource = interaction, indication = null, onClick = item.onClick),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(horizontal = 22.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(52.dp).clip(CircleShape).background(item.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(item.icon), null, Modifier.size(26.dp), tint = iconTint)
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!item.badge.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Text(item.badge, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun AccountSettingsPage(
    controller: MusicController,
    library: MusicLibraryState?,
    onOpenFriends: () -> Unit,
) {
    val ytAccount = library?.youtubeAccount
    val signingIn by controller.youtubeSigningIn.collectAsState()
    val authError by controller.youtubeAuthError.collectAsState()
    val socialState = controller.social?.state?.collectAsState()?.value

    LazyColumn(contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
        item { AndroidPreferenceGroupHeader("YouTube Music") }
        item {
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (signingIn) {
                            CircularWavyProgressIndicator(Modifier.size(26.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                        } else if (!ytAccount?.avatarUrl.isNullOrBlank()) {
                            AsyncImage(ytAccount?.avatarUrl, null, Modifier.fillMaxSize().clip(CircleShape))
                        } else {
                            Icon(
                                painterResource(if (ytAccount != null) Res.drawable.account else Res.drawable.login),
                                null,
                                Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            ytAccount?.name ?: "Sign in to YouTube Music",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            ytAccount?.email?.ifBlank { ytAccount.handle.ifBlank { "Signed in via Keychain" } }
                                ?: "Sync personalized Home recommendations & account library",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                        )
                    }
                }
            }
        }
        if (!authError.isNullOrBlank()) {
            item {
                Text(authError.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            }
        }
        item {
            if (ytAccount == null) {
                AndroidActionPreference(
                    title = if (signingIn) "Signing in..." else "Sign in with Google",
                    subtitle = "Credentials stay in iOS Keychain on this device",
                    icon = Res.drawable.login,
                    enabled = !signingIn && controller.platformBridge.canSignInYouTube,
                    onClick = controller::signInYouTube,
                )
            } else {
                AndroidActionPreference(
                    title = "Sign out of YouTube Music",
                    subtitle = "Remove saved cookies from Keychain",
                    icon = Res.drawable.logout,
                    destructive = true,
                    onClick = controller::signOutYouTube,
                )
            }
        }
        item { AndroidPreferenceGroupHeader("ArchiveTune Friends") }
        item {
            AndroidActionPreference(
                title = socialState?.account?.name ?: "Discord Social",
                subtitle = socialState?.account?.let { "@${it.username} • ${socialState.friends.size} friends" }
                    ?: "Sign in with Discord for friends, presence & chat",
                icon = Res.drawable.discord,
                onClick = onOpenFriends,
            )
        }
        item { Spacer(Modifier.height(120.dp)) }
    }
}

@Composable
private fun AppearanceSettingsPage(controller: MusicController, settings: MusicSettings) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
        item { AndroidPreferenceGroupHeader("Theme") }
        item {
            AndroidListPreference(
                title = "Dark theme",
                value = settings.theme,
                values = MusicThemeChoice.entries,
                icon = Res.drawable.dark_mode,
                valueText = {
                    when (it) {
                        MusicThemeChoice.SYSTEM -> "Follow system"
                        MusicThemeChoice.LIGHT -> "Off"
                        MusicThemeChoice.DARK -> "On"
                    }
                },
                onValueSelected = { v -> controller.updateSettings { it.copy(theme = v) } },
            )
        }
        item {
            AndroidSwitchPreference(
                title = "Pure black",
                subtitle = "Use true OLED black surfaces in dark theme",
                checked = settings.pureBlack,
                icon = Res.drawable.contrast,
                onCheckedChange = { v -> controller.updateSettings { it.copy(pureBlack = v) } },
            )
        }
        item {
            AndroidSwitchPreference(
                title = "Dynamic theme from artwork",
                subtitle = "Extract Material You palette and animated backdrop from the current track",
                checked = settings.dynamicTheme,
                icon = Res.drawable.palette,
                onCheckedChange = { v -> controller.updateSettings { it.copy(dynamicTheme = v) } },
            )
        }
        item { AndroidPreferenceGroupHeader("Player") }
        item {
            AndroidListPreference(
                title = "Player design",
                value = settings.playerDesignStyle,
                values = MusicPlayerDesignStyle.entries,
                icon = Res.drawable.buttons,
                valueText = { it.label },
                onValueSelected = { v -> controller.updateSettings { it.copy(playerDesignStyle = v) } },
            )
        }
        item {
            AndroidListPreference(
                title = "Player background",
                value = settings.playerBackgroundStyle,
                values = MusicPlayerBackgroundStyle.entries,
                icon = Res.drawable.blur_on,
                valueText = { it.label },
                onValueSelected = { v -> controller.updateSettings { it.copy(playerBackgroundStyle = v) } },
            )
        }
        item {
            AndroidListPreference(
                title = "Mini player background",
                value = settings.miniPlayerBackgroundStyle,
                values = MusicMiniPlayerBackgroundStyle.entries,
                icon = Res.drawable.nav_bar,
                valueText = { it.label },
                onValueSelected = { v -> controller.updateSettings { it.copy(miniPlayerBackgroundStyle = v) } },
            )
        }
        item {
            AndroidListPreference(
                title = "Slider style",
                value = settings.sliderStyle,
                values = MusicSliderStyle.entries,
                icon = Res.drawable.sliders,
                valueText = {
                    when (it) {
                        MusicSliderStyle.STANDARD -> "Standard"
                        MusicSliderStyle.WAVY -> "Wavy"
                        MusicSliderStyle.SLIM -> "Slim"
                    }
                },
                onValueSelected = { v -> controller.updateSettings { it.copy(sliderStyle = v) } },
            )
        }
        item {
            AndroidSliderPreference(
                title = "Thumbnail corner radius",
                valueText = "${settings.thumbnailCornerRadius.roundToInt()} dp",
                value = settings.thumbnailCornerRadius,
                valueRange = 0f..36f,
                icon = Res.drawable.motion_photos_on,
                onValueChange = { v -> controller.updateSettings { it.copy(thumbnailCornerRadius = v) } },
            )
        }
        item {
            AndroidSwitchPreference(
                title = "Crop thumbnail to square",
                checked = settings.cropThumbnailToSquare,
                icon = Res.drawable.grid_view,
                onCheckedChange = { v -> controller.updateSettings { it.copy(cropThumbnailToSquare = v) } },
            )
        }
        item {
            AndroidSwitchPreference(
                title = "Show player volume bar",
                checked = settings.showPlayerVolumeBar,
                icon = Res.drawable.volume_up,
                onCheckedChange = { v -> controller.updateSettings { it.copy(showPlayerVolumeBar = v) } },
            )
        }
        item {
            AndroidSwitchPreference(
                title = "Hide player thumbnail",
                checked = settings.hidePlayerThumbnail,
                icon = Res.drawable.hide_image,
                onCheckedChange = { v -> controller.updateSettings { it.copy(hidePlayerThumbnail = v) } },
            )
        }
        item {
            AndroidSwitchPreference(
                title = "Swipe thumbnail to change song",
                checked = settings.swipeThumbnail,
                icon = Res.drawable.swipe,
                onCheckedChange = { v -> controller.updateSettings { it.copy(swipeThumbnail = v) } },
            )
        }
        item {
            AndroidSwitchPreference(
                title = "Swipe mini player to change song",
                checked = settings.swipeToSong,
                icon = Res.drawable.swipe,
                onCheckedChange = { v -> controller.updateSettings { it.copy(swipeToSong = v) } },
            )
        }
        item { AndroidPreferenceGroupHeader("Home & Layout") }
        item {
            AndroidListPreference(
                title = "Default open tab",
                value = settings.defaultOpenTab,
                values = MusicDefaultTab.entries,
                icon = Res.drawable.tab,
                valueText = { it.label },
                onValueSelected = { v -> controller.updateSettings { it.copy(defaultOpenTab = v) } },
            )
        }
        item {
            AndroidListPreference(
                title = "Quick picks style",
                value = settings.quickPicksMode,
                values = MusicQuickPicksMode.entries,
                icon = Res.drawable.home_outlined,
                valueText = { it.label },
                onValueSelected = { v -> controller.updateSettings { it.copy(quickPicksMode = v) } },
            )
        }
        item {
            AndroidListPreference(
                title = "Grid item size",
                value = settings.gridItemSize,
                values = MusicGridItemSize.entries,
                icon = Res.drawable.grid_view,
                valueText = { it.label },
                onValueSelected = { v -> controller.updateSettings { it.copy(gridItemSize = v) } },
            )
        }
        item {
            AndroidSwitchPreference(
                title = "Show Home category chips",
                checked = settings.showHomeCategoryChips,
                icon = Res.drawable.filter_alt,
                onCheckedChange = { v -> controller.updateSettings { it.copy(showHomeCategoryChips = v) } },
            )
        }
        item {
            AndroidSwitchPreference(
                title = "Disable blur effects",
                checked = settings.disableBlur,
                icon = Res.drawable.blur_off,
                onCheckedChange = { v -> controller.updateSettings { it.copy(disableBlur = v) } },
            )
        }
        item {
            AndroidSwitchPreference(
                title = "Reduce animations",
                checked = settings.disableAnimations,
                icon = Res.drawable.animation,
                onCheckedChange = { v -> controller.updateSettings { it.copy(disableAnimations = v) } },
            )
        }
        item { Spacer(Modifier.height(120.dp)) }
    }
}

@Composable
private fun PlaybackSettingsPage(controller: MusicController, settings: MusicSettings, speed: Float) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
        item { AndroidPreferenceGroupHeader("Audio") }
        item {
            AndroidListPreference(
                title = "Audio quality",
                value = settings.audioQuality,
                values = MusicAudioQuality.entries,
                icon = Res.drawable.graphic_eq,
                valueText = {
                    when (it) {
                        MusicAudioQuality.AUTO -> "Auto"
                        MusicAudioQuality.LOW -> "Low (Save data)"
                        MusicAudioQuality.HIGH -> "High"
                    }
                },
                onValueSelected = { v -> controller.updateSettings { it.copy(audioQuality = v) } },
            )
        }
        item {
            AndroidListPreference(
                title = "Playback speed",
                value = speed,
                values = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f),
                icon = Res.drawable.speed,
                valueText = { "${it}x" },
                onValueSelected = { v ->
                    controller.setSpeed(v)
                    controller.updateSettings { it.copy(playbackSpeed = v) }
                },
            )
        }
        item {
            AndroidSwitchPreference(
                title = "Audio normalization",
                subtitle = "Balance perceived loudness across tracks",
                checked = settings.audioNormalization,
                icon = Res.drawable.equalizer,
                onCheckedChange = { v -> controller.updateSettings { it.copy(audioNormalization = v) } },
            )
        }
        item {
            AndroidSwitchPreference(
                title = "Skip silence",
                checked = settings.skipSilence,
                icon = Res.drawable.fast_forward,
                onCheckedChange = { v -> controller.updateSettings { it.copy(skipSilence = v) } },
            )
        }
        item { AndroidPreferenceGroupHeader("Crossfade") }
        item {
            AndroidSwitchPreference(
                title = "Enable crossfade",
                subtitle = "Smoothly transition between consecutive songs",
                checked = settings.crossfadeEnabled,
                icon = Res.drawable.waves,
                onCheckedChange = { v -> controller.updateSettings { it.copy(crossfadeEnabled = v) } },
            )
        }
        if (settings.crossfadeEnabled) {
            item {
                AndroidSliderPreference(
                    title = "Crossfade duration",
                    valueText = "${settings.crossfadeDurationSec.roundToInt()}s",
                    value = settings.crossfadeDurationSec,
                    valueRange = 1f..12f,
                    steps = 10,
                    icon = Res.drawable.timer,
                    onValueChange = { v -> controller.updateSettings { it.copy(crossfadeDurationSec = v) } },
                )
            }
            item {
                AndroidSwitchPreference(
                    title = "Gapless transition",
                    checked = settings.crossfadeGapless,
                    icon = Res.drawable.all_inclusive,
                    onCheckedChange = { v -> controller.updateSettings { it.copy(crossfadeGapless = v) } },
                )
            }
        }
        item { AndroidPreferenceGroupHeader("Queue & Behavior") }
        item {
            AndroidSwitchPreference(
                title = "Persistent queue",
                subtitle = "Restore queue when reopening ArchiveTune",
                checked = settings.persistentQueue,
                icon = Res.drawable.queue_music,
                onCheckedChange = { v -> controller.updateSettings { it.copy(persistentQueue = v) } },
            )
        }
        item {
            AndroidSwitchPreference(
                title = "Permanent shuffle",
                subtitle = "Keep shuffle enabled across queue replacements",
                checked = settings.permanentShuffle,
                icon = Res.drawable.shuffle,
                onCheckedChange = { v -> controller.updateSettings { it.copy(permanentShuffle = v) } },
            )
        }
        item {
            AndroidSwitchPreference(
                title = "Auto-skip next on error",
                subtitle = "Advance to the next track if a stream fails",
                checked = settings.autoSkipNextOnError,
                icon = Res.drawable.skip_next,
                onCheckedChange = { v -> controller.updateSettings { it.copy(autoSkipNextOnError = v) } },
            )
        }
        item {
            AndroidSwitchPreference(
                title = "Seek 10 seconds on double-tap",
                subtitle = "Uses 10s instead of 5s when double-tapping artwork",
                checked = settings.seekExtraSeconds,
                icon = Res.drawable.fast_forward,
                onCheckedChange = { v -> controller.updateSettings { it.copy(seekExtraSeconds = v) } },
            )
        }
        item {
            AndroidSwitchPreference(
                title = "Auto-download liked songs",
                subtitle = "Automatically save songs offline when you favorite them",
                checked = settings.autoDownloadOnLike,
                icon = Res.drawable.download,
                onCheckedChange = { v -> controller.updateSettings { it.copy(autoDownloadOnLike = v) } },
            )
        }
        item {
            AndroidSwitchPreference(
                title = "Keep screen awake",
                subtitle = "Prevent device sleep while ArchiveTune is open",
                checked = settings.keepScreenAwake,
                icon = Res.drawable.bolt,
                onCheckedChange = { v -> controller.updateSettings { it.copy(keepScreenAwake = v) } },
            )
        }
        item { Spacer(Modifier.height(120.dp)) }
    }
}

@Composable
private fun LyricsSettingsPage(controller: MusicController, settings: MusicSettings) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
        item { AndroidPreferenceGroupHeader("Display") }
        item {
            AndroidListPreference(
                title = "Lyrics background",
                value = settings.lyricsBackgroundStyle,
                values = MusicLyricsBackgroundStyle.entries,
                icon = Res.drawable.blur_on,
                valueText = { it.label },
                onValueSelected = { v -> controller.updateSettings { it.copy(lyricsBackgroundStyle = v) } },
            )
        }
        item {
            AndroidListPreference(
                title = "Text alignment",
                value = settings.lyricsAlignment,
                values = MusicLyricsAlignment.entries,
                icon = Res.drawable.format_align_center,
                valueText = { it.label },
                onValueSelected = { v -> controller.updateSettings { it.copy(lyricsAlignment = v) } },
            )
        }
        item {
            AndroidSliderPreference(
                title = "Lyrics text size",
                valueText = "${settings.lyricsTextSize.roundToInt()} sp",
                value = settings.lyricsTextSize,
                valueRange = 16f..36f,
                icon = Res.drawable.text_fields,
                onValueChange = { v -> controller.updateSettings { it.copy(lyricsTextSize = v) } },
            )
        }
        item {
            AndroidSliderPreference(
                title = "Line spacing",
                valueText = "${((settings.lyricsLineSpacing * 10f).roundToInt() / 10f)}x",
                value = settings.lyricsLineSpacing,
                valueRange = 1.0f..1.8f,
                icon = Res.drawable.format_align_left,
                onValueChange = { v -> controller.updateSettings { it.copy(lyricsLineSpacing = v) } },
            )
        }
        item {
            AndroidSwitchPreference(
                title = "Blur inactive lines",
                checked = settings.lyricsLineBlur,
                icon = Res.drawable.blur_on,
                onCheckedChange = { v -> controller.updateSettings { it.copy(lyricsLineBlur = v) } },
            )
        }
        item {
            AndroidSwitchPreference(
                title = "Tap line to seek",
                checked = settings.lyricsClick,
                icon = Res.drawable.lyrics,
                onCheckedChange = { v -> controller.updateSettings { it.copy(lyricsClick = v) } },
            )
        }
        item {
            AndroidSwitchPreference(
                title = "Auto-scroll lyrics",
                checked = settings.lyricsScroll,
                icon = Res.drawable.swipe,
                onCheckedChange = { v -> controller.updateSettings { it.copy(lyricsScroll = v) } },
            )
        }
        item {
            AndroidSliderPreference(
                title = "Timing offset",
                valueText = "${if (settings.lyricsOffsetMs > 0) "+" else ""}${settings.lyricsOffsetMs} ms",
                value = settings.lyricsOffsetMs.toFloat(),
                valueRange = -3000f..3000f,
                steps = 59,
                icon = Res.drawable.timer,
                onValueChange = { v -> controller.updateSettings { it.copy(lyricsOffsetMs = (v / 100f).roundToInt() * 100L) } },
            )
        }
        item { AndroidPreferenceGroupHeader("Providers") }
        item {
            AndroidSwitchPreference(
                title = "LRCLIB",
                subtitle = "Timed and plain community lyrics",
                checked = settings.enableLrclib,
                icon = Res.drawable.lyrics,
                onCheckedChange = { v -> controller.updateSettings { it.copy(enableLrclib = v) } },
            )
        }
        item {
            AndroidSwitchPreference(
                title = "YouLyPlus (Word-by-word)",
                subtitle = "Syllable-synced karaoke lyrics",
                checked = settings.enableYouLyPlus,
                icon = Res.drawable.auto_awesome,
                onCheckedChange = { v -> controller.updateSettings { it.copy(enableYouLyPlus = v) } },
            )
        }
        item { Spacer(Modifier.height(120.dp)) }
    }
}

@Composable
private fun ContentSettingsPage(controller: MusicController, settings: MusicSettings) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
        item { AndroidPreferenceGroupHeader("Locale") }
        item {
            AndroidListPreference(
                title = "Content language",
                value = settings.contentLanguage,
                values = languageOptions.map { it.first },
                icon = Res.drawable.language,
                valueText = { code -> languageOptions.firstOrNull { it.first == code }?.second ?: code },
                onValueSelected = { v -> controller.updateSettings { it.copy(contentLanguage = v) } },
            )
        }
        item {
            AndroidListPreference(
                title = "Content country",
                value = settings.contentCountry,
                values = countryOptions.map { it.first },
                icon = Res.drawable.location_on,
                valueText = { code -> countryOptions.firstOrNull { it.first == code }?.second ?: code },
                onValueSelected = { v -> controller.updateSettings { it.copy(contentCountry = v) } },
            )
        }
        item { AndroidPreferenceGroupHeader("Filters & Lists") }
        item {
            AndroidSwitchPreference(
                title = "Hide explicit content",
                checked = settings.hideExplicit,
                icon = Res.drawable.explicit,
                onCheckedChange = { v -> controller.updateSettings { it.copy(hideExplicit = v) } },
            )
        }
        item {
            AndroidSwitchPreference(
                title = "Hide videos",
                checked = settings.hideVideo,
                icon = Res.drawable.slow_motion_video,
                onCheckedChange = { v -> controller.updateSettings { it.copy(hideVideo = v) } },
            )
        }
        item {
            AndroidListPreference(
                title = "My Top length",
                value = settings.topLength,
                values = listOf(25, 50, 100, 150, 200),
                icon = Res.drawable.trending_up,
                valueText = { "Top $it" },
                onValueSelected = { v -> controller.updateSettings { it.copy(topLength = v) } },
            )
        }
        item { Spacer(Modifier.height(120.dp)) }
    }
}

@Composable
private fun PrivacySettingsPage(controller: MusicController, settings: MusicSettings) {
    var confirmClearHistory by remember { mutableStateOf(false) }
    var confirmClearSearch by remember { mutableStateOf(false) }
    var confirmResetStats by remember { mutableStateOf(false) }

    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("Clear listening history?") },
            text = { Text("This removes your recent play history from this device.") },
            confirmButton = {
                TextButton(onClick = { controller.clearHistory(); confirmClearHistory = false }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClearHistory = false }) { Text("Cancel") } },
        )
    }
    if (confirmClearSearch) {
        AlertDialog(
            onDismissRequest = { confirmClearSearch = false },
            title = { Text("Clear search history?") },
            text = { Text("This deletes all saved search queries.") },
            confirmButton = {
                TextButton(onClick = { controller.clearSearchHistory(); confirmClearSearch = false }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClearSearch = false }) { Text("Cancel") } },
        )
    }
    if (confirmResetStats) {
        AlertDialog(
            onDismissRequest = { confirmResetStats = false },
            title = { Text("Reset listening statistics?") },
            text = { Text("All recorded audible listening time and play counts will be permanently cleared.") },
            confirmButton = {
                TextButton(onClick = { controller.resetStatistics(); confirmResetStats = false }) { Text("Reset", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmResetStats = false }) { Text("Cancel") } },
        )
    }

    LazyColumn(contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
        item { AndroidPreferenceGroupHeader("Listen History") }
        item {
            AndroidSwitchPreference(
                title = "Pause listen history",
                subtitle = "Stop recording recent tracks and listening statistics",
                checked = settings.pauseListenHistory,
                icon = Res.drawable.history,
                onCheckedChange = { v -> controller.updateSettings { it.copy(pauseListenHistory = v) } },
            )
        }
        item {
            AndroidActionPreference(
                title = "Clear listen history",
                icon = Res.drawable.delete_history,
                onClick = { confirmClearHistory = true },
            )
        }
        item {
            AndroidActionPreference(
                title = "Reset listening statistics",
                icon = Res.drawable.stats,
                destructive = true,
                onClick = { confirmResetStats = true },
            )
        }
        item { AndroidPreferenceGroupHeader("Search History") }
        item {
            AndroidSwitchPreference(
                title = "Pause search history",
                checked = settings.pauseSearchHistory,
                icon = Res.drawable.search_off,
                onCheckedChange = { v -> controller.updateSettings { it.copy(pauseSearchHistory = v) } },
            )
        }
        item {
            AndroidActionPreference(
                title = "Clear search history",
                icon = Res.drawable.clear_all,
                onClick = { confirmClearSearch = true },
            )
        }
        item { AndroidPreferenceGroupHeader("Feedback") }
        item {
            AndroidSwitchPreference(
                title = "Haptic feedback",
                subtitle = "Tactile response on slider scrubbing and gestures",
                checked = settings.enableHapticFeedback,
                icon = Res.drawable.vibration,
                onCheckedChange = { v -> controller.updateSettings { it.copy(enableHapticFeedback = v) } },
            )
        }
        item { Spacer(Modifier.height(120.dp)) }
    }
}

@Composable
private fun IntegrationsSettingsPage(controller: MusicController, onOpenFriends: () -> Unit) {
    val social = controller.social?.state?.collectAsState()?.value
    LazyColumn(contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
        item { AndroidPreferenceGroupHeader("Social & Presence") }
        item {
            AndroidActionPreference(
                title = "Discord / ArchiveTune Friends",
                subtitle = social?.account?.let { "Connected as @${it.username}" } ?: "Connect your Discord account",
                icon = Res.drawable.discord,
                badge = if (social?.account != null) "Connected" else null,
                onClick = onOpenFriends,
            )
        }
        item { Spacer(Modifier.height(120.dp)) }
    }
}

@Composable
private fun StorageSettingsPage(
    controller: MusicController,
    library: MusicLibraryState?,
    settings: MusicSettings,
) {
    var refreshTick by remember { mutableIntStateOf(0) }
    val usage = remember(refreshTick, library?.downloads?.size, library?.cachedTracks?.size) {
        controller.storageUsage()
    }
    var confirmClearDownloads by remember { mutableStateOf(false) }

    if (confirmClearDownloads) {
        AlertDialog(
            onDismissRequest = { confirmClearDownloads = false },
            title = { Text("Remove all downloads?") },
            text = { Text("All ${library?.downloads?.size ?: 0} offline tracks will be deleted from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    controller.clearAllDownloads()
                    refreshTick++
                    confirmClearDownloads = false
                }) { Text("Delete all", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClearDownloads = false }) { Text("Cancel") } },
        )
    }

    val cacheSizes = listOf(128, 256, 512, 1024, 2048, 4096)

    LazyColumn(contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
        item { AndroidPreferenceGroupHeader("Downloads") }
        item {
            AndroidActionPreference(
                title = "Downloaded songs",
                subtitle = "${library?.downloads?.size ?: 0} tracks • ${formatBytes(usage.downloadsBytes)}",
                icon = Res.drawable.offline,
                destructive = (library?.downloads?.isNotEmpty() == true),
                enabled = library?.downloads?.isNotEmpty() == true,
                badge = if (library?.downloads?.isNotEmpty() == true) "Clear" else null,
                onClick = { confirmClearDownloads = true },
            )
        }
        item { AndroidPreferenceGroupHeader("Song Cache") }
        item {
            AndroidListPreference(
                title = "Max song cache size",
                value = settings.maxSongCacheSizeMb,
                values = cacheSizes,
                icon = Res.drawable.cached,
                valueText = { if (it >= 1024) "${it / 1024} GB" else "$it MB" },
                onValueSelected = { v -> controller.updateSettings { it.copy(maxSongCacheSizeMb = v) } },
            )
        }
        item {
            AndroidActionPreference(
                title = "Clear song cache",
                subtitle = "${library?.cachedTracks?.size ?: 0} recent items • ${formatBytes(usage.songCacheBytes)}",
                icon = Res.drawable.delete,
                onClick = {
                    controller.clearAudioCache()
                    refreshTick++
                },
            )
        }
        item { AndroidPreferenceGroupHeader("Image Cache") }
        item {
            AndroidListPreference(
                title = "Max image cache size",
                value = settings.maxImageCacheSizeMb,
                values = cacheSizes,
                icon = Res.drawable.cached,
                valueText = { if (it >= 1024) "${it / 1024} GB" else "$it MB" },
                onValueSelected = { v -> controller.updateSettings { it.copy(maxImageCacheSizeMb = v) } },
            )
        }
        item {
            AndroidActionPreference(
                title = "Clear image cache",
                subtitle = formatBytes(usage.imageCacheBytes),
                icon = Res.drawable.delete,
                onClick = {
                    controller.clearImageCache()
                    refreshTick++
                },
            )
        }
        item { Spacer(Modifier.height(120.dp)) }
    }
}

@Composable
private fun BackupRestoreSettingsPage(controller: MusicController) {
    var statusMessage by remember { mutableStateOf<String?>(null) }
    LazyColumn(contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
        item { AndroidPreferenceGroupHeader("Library Backup") }
        item {
            AndroidActionPreference(
                title = "Export backup",
                subtitle = "Save favorites, playlists, history, stats & settings as JSON",
                icon = Res.drawable.backup,
                enabled = controller.canShare,
                onClick = {
                    controller.exportBackup()
                    statusMessage = "Backup ready to share or save to Files."
                },
            )
        }
        item {
            AndroidActionPreference(
                title = "Import backup",
                subtitle = "Restore an ArchiveTune JSON backup from Files",
                icon = Res.drawable.restore,
                enabled = controller.platformBridge.canBackupRestore,
                onClick = {
                    controller.importBackup { ok ->
                        statusMessage = if (ok) "Backup restored successfully." else "Could not restore backup file."
                    }
                },
            )
        }
        if (statusMessage != null) {
            item {
                Text(
                    statusMessage.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
        item { Spacer(Modifier.height(120.dp)) }
    }
}

@Composable
private fun AboutSettingsPage(controller: MusicController) {
    var showLicenses by remember { mutableStateOf(false) }
    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text("Open-source licenses") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ArchiveTune — GPL-3.0 License", fontWeight = FontWeight.Bold)
                    Text("Compose Multiplatform & Material 3 — Apache-2.0")
                    Text("Kotlin Coroutines, Ktor, Kotlinx Serialization — Apache-2.0")
                    Text("Coil 3 — Apache-2.0")
                    Text("Material Color Utilities & MaterialKolor — Apache-2.0 / MIT")
                    Text("Skia / Skiko — BSD-3-Clause / Apache-2.0")
                }
            },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("Close") } },
        )
    }

    LazyColumn(contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(64.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(painterResource(Res.drawable.about_appbar), null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    Text("ArchiveTune", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Version 15.2.0 • iOS Parity Edition", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("© Rukamori — GPL-3.0 License", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { AndroidPreferenceGroupHeader("Links") }
        item {
            AndroidActionPreference(
                title = "GitHub",
                subtitle = "github.com/rukamori",
                icon = Res.drawable.github,
                onClick = { controller.openUrl("https://github.com/rukamori") },
            )
        }
        item {
            AndroidActionPreference(
                title = "Open-source licenses",
                subtitle = "Third-party notices and license terms",
                icon = Res.drawable.info,
                onClick = { showLicenses = true },
            )
        }
        item { Spacer(Modifier.height(120.dp)) }
    }
}

internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> "${((gb * 10).roundToInt() / 10.0)} GB"
        mb >= 1.0 -> "${((mb * 10).roundToInt() / 10.0)} MB"
        kb >= 1.0 -> "${kb.roundToInt()} KB"
        else -> "$bytes B"
    }
}

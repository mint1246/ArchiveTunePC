/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import org.jetbrains.compose.resources.painterResource
import moe.rukamori.archivetune.shared.resources.*

/** Compact PlaybackError.kt layout; native failures must never look like paused playback. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AndroidPlaybackErrorDialog(controller: MusicController) {
    val playback by controller.playback.collectAsState()
    val state by controller.state.collectAsState()
    val message = playback.error ?: state.error?.takeIf { state.failedPlayback }
    val failure = Triple(playback.track?.id, state.resolvingTrackId, message)
    var dismissed by remember { mutableStateOf<Triple<String?, String?, String?>?>(null) }
    LaunchedEffect(message) { if (message == null) dismissed = null }
    if (message == null || dismissed == failure) return
    val clipboard = LocalClipboardManager.current
    BasicAlertDialog(onDismissRequest = {}, properties = DialogProperties(
        dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 760.dp).fillMaxWidth().heightIn(max = maxHeight),
                shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                        Surface(Modifier.size(56.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer) {
                            Icon(painterResource(Res.drawable.error), null, Modifier.padding(14.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Unknown error", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Details", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SelectionContainer { Text(message, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                        }
                    }
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { dismissed = failure }) { Icon(painterResource(Res.drawable.close), null, Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.width(ButtonDefaults.IconSpacing)); Text("Close") }
                        FilledTonalButton(onClick = { clipboard.setText(AnnotatedString(message)) }) { Icon(painterResource(Res.drawable.select_all), null, Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.width(ButtonDefaults.IconSpacing)); Text("Copy") }
                        Button(onClick = { dismissed = failure; controller.retryPlayback() }) { Icon(painterResource(Res.drawable.replay), null, Modifier.size(ButtonDefaults.IconSize)); Spacer(Modifier.width(ButtonDefaults.IconSpacing)); Text("Retry") }
                    }
                }
            }
        }
    }
}

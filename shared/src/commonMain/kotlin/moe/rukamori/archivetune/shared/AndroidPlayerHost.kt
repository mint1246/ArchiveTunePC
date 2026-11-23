/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.shared

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** Keep this host composed over the application's content for both player anchors. */
@Composable
internal fun AndroidPlayerHost(
    controller: MusicController,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    navigationProximity: Float = 1f,
    collapsedBottomInset: Dp = 0.dp,
    onProgressChange: (Float) -> Unit = {},
    onPresenceChange: (Float) -> Unit = {},
) {
    val playback by controller.playback.collectAsState()
    if (playback.track == null) return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val height = maxHeight
        val collapsed = (70.dp + collapsedBottomInset).coerceAtMost(height)
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()
        val value = remember { Animatable(0.dp, Dp.VectorConverter) }
        val callback by rememberUpdatedState(onExpandedChange)
        var targetExpanded by remember { mutableStateOf(expanded) }
        var initialized by remember { mutableStateOf(false) }
        var dismissing by remember { mutableStateOf(false) }
        var overlayOpen by remember { mutableStateOf(false) }
        val soft = spring<Dp>(Spring.DampingRatioNoBouncy, Spring.StiffnessLow)
        val normal = spring<Dp>(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)
        LaunchedEffect(height, collapsed) {
            value.updateBounds(0.dp, height)
            if (!initialized) {
                initialized = true
                value.animateTo(if (expanded) height else collapsed, soft)
            } else if (!targetExpanded && !value.isRunning && !dismissing) value.snapTo(collapsed)
        }
        LaunchedEffect(expanded, height, collapsed) {
            if (!dismissing && (targetExpanded != expanded || (value.value != (if (expanded) height else collapsed) && !value.isRunning))) {
                targetExpanded = expanded
                value.animateTo(if (expanded) height else collapsed, soft)
            }
        }
        fun settle(target: Boolean, isSoft: Boolean) {
            targetExpanded = target
            callback(target)
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                value.animateTo(if (target) height else collapsed, if (isSoft) soft else normal)
            }
        }
        fun dismiss() {
            dismissing = true
            callback(false)
            val dismissedId = controller.playback.value.track?.id
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                value.animateTo(0.dp, normal)
                if (controller.playback.value.track?.id == dismissedId) controller.dismissPlayback()
                dismissing = false
            }
        }
        val progress = if (height > collapsed) ((value.value - collapsed) / (height - collapsed)).coerceIn(0f, 1f) else 1f
        val deviation = if (value.value > collapsed) value.value - collapsed else collapsed - value.value
        val presence = (1f - deviation / 74.dp).coerceIn(0f, 1f)
        SideEffect { onProgressChange(progress); onPresenceChange(presence) }
        Box(
            Modifier.fillMaxSize().offset { IntOffset(0, with(density) { (height - value.value).roundToPx() }.coerceAtLeast(0)) }
                .pointerInput(height, collapsed, overlayOpen) {
                    if (overlayOpen) return@pointerInput
                    val tracker = VelocityTracker()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        tracker.resetTracking()
                        tracker.addPointerInputChange(down)
                        var started = false
                        var completed = false
                        val wasExpanded = targetExpanded
                        try {
                            val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
                                change.consume()
                                started = true
                                tracker.addPointerInputChange(change)
                                scope.launch(start = CoroutineStart.UNDISPATCHED) { value.snapTo(value.value - with(density) { overSlop.toDp() }) }
                            } ?: return@awaitEachGesture
                            completed = verticalDrag(drag.id) { change ->
                                tracker.addPointerInputChange(change)
                                val delta = change.positionChange().y
                                scope.launch(start = CoroutineStart.UNDISPATCHED) { value.snapTo(value.value - with(density) { delta.toDp() }) }
                                change.consume()
                            }
                            if (completed) {
                                val velocity = -tracker.calculateVelocity().y
                                when {
                                    velocity > 250f -> settle(true, false)
                                    velocity < -250f && value.value < collapsed -> dismiss()
                                    velocity < -250f -> settle(false, false)
                                    value.value < collapsed / 2f -> dismiss()
                                    else -> settle(value.value >= (height - collapsed) / 2, false)
                                }
                            }
                        } finally {
                            tracker.resetTracking()
                            if (started && !completed) settle(wasExpanded, true)
                        }
                    }
                }
                .clip(RoundedCornerShape(topStart = if (progress < 1f) 16.dp else 0.dp, topEnd = if (progress < 1f) 16.dp else 0.dp)),
        ) {
            if (progress > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = ((progress - .25f) * 4f).coerceIn(0f, 1f) }) {
                AndroidFullPlayer(controller, onClose = { settle(false, true) }, onOverlayVisibilityChange = { overlayOpen = it })
            }
            if (progress < 1f) Box(Modifier.fillMaxWidth().height(collapsed).graphicsLayer { alpha = 1f - (progress * 4f).coerceAtMost(1f) }) {
                AndroidMiniPlayer(controller, onOpen = { settle(true, true) }, navigationProximity = navigationProximity * presence,
                    modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(horizontal = 12.dp))
            }
        }
    }
}

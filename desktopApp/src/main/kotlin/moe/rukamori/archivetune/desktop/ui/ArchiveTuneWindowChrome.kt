/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Minimize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Frame
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import kotlin.math.roundToInt

private val WindowResizeGrip = 6.dp

/**
 * Supplies the app shell with native-like window interactions.
 *
 * The controls are rendered by the app's own top bar, alongside search/navigation, rather
 * than in a detached title strip. This keeps the lyrics dialog and every destination under
 * the same chrome. Dragging compares the AWT pointer and window bounds in the same absolute
 * coordinate space. Window-relative Compose deltas feed the window's own movement back into
 * subsequent events and caused the previous title bar to jump or move in the wrong direction.
 */
@Composable
fun WindowScope.ArchiveTuneWindowChrome(
    windowState: WindowState,
    onCloseRequest: () -> Unit,
    content: @Composable (dragModifier: Modifier, windowControls: @Composable () -> Unit) -> Unit,
) {
    val maximized = windowState.placement == WindowPlacement.Maximized
    val lastFloatingBounds = remember(window) { mutableStateOf<Rectangle?>(null) }
    val toggleMaximize = {
        if (windowState.placement == WindowPlacement.Maximized) {
            // A session can start maximized without AWT having a useful normal-bounds rectangle.
            // Seed one before restoring so the Restore button/double-click visibly leaves maximize
            // instead of producing a non-maximized window that still covers the entire monitor.
            if (lastFloatingBounds.value == null) {
                window.bounds = fallbackRestoredBounds(window.bounds, window.minimumSize)
            }
            windowState.placement = WindowPlacement.Floating
        } else {
            lastFloatingBounds.value = Rectangle(window.bounds)
            windowState.placement = WindowPlacement.Maximized
        }
    }
    val dragModifier = remember(window, windowState) {
        Modifier
            .pointerInput(windowState) {
                detectTapGestures(onDoubleTap = {
                    toggleMaximize()
                })
            }
            .pointerInput(window, windowState) {
                var startWindowLocation: Point? = null
                var startPointerLocation: Point? = null
                var restoreAnchor: WindowDragRestoreAnchor? = null
                var restoreSize: Dimension? = null
                detectDragGestures(
                    onDragStart = {
                        val pointer = pointerLocation() ?: return@detectDragGestures
                        if (windowState.placement == WindowPlacement.Maximized) {
                            // Windows restores a maximized window once a title-bar drag passes the
                            // drag threshold, keeping the same point of the restored title bar under
                            // the cursor. Do the same rather than making maximized windows immovable.
                            restoreAnchor = windowDragRestoreAnchor(window.bounds, pointer)
                            restoreSize =
                                lastFloatingBounds.value?.size
                                    ?: fallbackRestoredSize(window.bounds, window.minimumSize)
                            startWindowLocation = null
                            startPointerLocation = null
                            windowState.placement = WindowPlacement.Floating
                        } else {
                            restoreAnchor = null
                            restoreSize = null
                            startWindowLocation = window.location
                            startPointerLocation = pointer
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val pointer = pointerLocation() ?: return@detectDragGestures
                        restoreAnchor?.let { anchor ->
                            val restoredSize = restoreSize ?: fallbackRestoredSize(anchor.maximizedBounds, window.minimumSize)
                            val restoredLocation = restoredDragLocation(
                                pointer = pointer,
                                restoredSize = restoredSize,
                                horizontalFraction = anchor.horizontalFraction,
                                pointerOffsetY = anchor.pointerOffsetY,
                            )
                            window.bounds = Rectangle(restoredLocation.x, restoredLocation.y, restoredSize.width, restoredSize.height)

                            // Compose changes WindowPlacement through AWT's maximized state. If the
                            // first drag event arrives before AWT has restored, setting bounds above
                            // still establishes the desired normal bounds; keep the anchor alive so
                            // the next event reapplies it once the native state catches up.
                            if (window.isActuallyMaximized()) return@detectDragGestures

                            lastFloatingBounds.value = Rectangle(window.bounds)
                            startWindowLocation = restoredLocation
                            startPointerLocation = pointer
                            restoreAnchor = null
                            restoreSize = null
                            return@detectDragGestures
                        }

                        val initialWindow = startWindowLocation
                        val initialPointer = startPointerLocation
                        if (initialWindow != null && initialPointer != null) {
                            window.location = Point(
                                initialWindow.x + pointer.x - initialPointer.x,
                                initialWindow.y + pointer.y - initialPointer.y,
                            )
                        }
                    },
                    onDragEnd = {
                        startWindowLocation = null
                        startPointerLocation = null
                        restoreAnchor = null
                        restoreSize = null
                    },
                    onDragCancel = {
                        startWindowLocation = null
                        startPointerLocation = null
                        restoreAnchor = null
                        restoreSize = null
                    },
                )
            }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        content(
            dragModifier,
            {
                WindowChromeControls(
                    maximized = maximized,
                    onMinimize = { (window as? Frame)?.let { it.extendedState = it.extendedState or Frame.ICONIFIED } },
                    onToggleMaximize = toggleMaximize,
                    onClose = onCloseRequest,
                )
            },
        )

        if (!maximized) {
            WindowResizeHandle(Modifier.align(Alignment.CenterEnd), ResizeDirection.EAST, windowState)
            WindowResizeHandle(Modifier.align(Alignment.BottomCenter), ResizeDirection.SOUTH, windowState)
            WindowResizeHandle(Modifier.align(Alignment.CenterStart), ResizeDirection.WEST, windowState)
            WindowResizeHandle(Modifier.align(Alignment.TopCenter), ResizeDirection.NORTH, windowState)
            WindowResizeHandle(Modifier.align(Alignment.BottomEnd), ResizeDirection.SOUTH_EAST, windowState)
            WindowResizeHandle(Modifier.align(Alignment.BottomStart), ResizeDirection.SOUTH_WEST, windowState)
            WindowResizeHandle(Modifier.align(Alignment.TopEnd), ResizeDirection.NORTH_EAST, windowState)
            WindowResizeHandle(Modifier.align(Alignment.TopStart), ResizeDirection.NORTH_WEST, windowState)
        }
    }
}

@Composable
private fun WindowChromeControls(
    maximized: Boolean,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        // Full-height rectangular targets make the custom chrome read as one integrated
        // browser-like strip and avoid a dead 12dp gutter beside the close button.
        modifier = Modifier.height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WindowChromeButton(Icons.Rounded.Minimize, "Minimize", onMinimize)
        WindowChromeButton(
            if (maximized) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
            if (maximized) "Restore" else "Maximize",
            onToggleMaximize,
        )
        WindowChromeButton(Icons.Rounded.Close, "Close", onClose, close = true)
    }
}

internal enum class ResizeDirection { NORTH, SOUTH, EAST, WEST, NORTH_EAST, NORTH_WEST, SOUTH_EAST, SOUTH_WEST }

@Composable
private fun WindowScope.WindowResizeHandle(
    modifier: Modifier,
    direction: ResizeDirection,
    windowState: WindowState,
) {
    Box(
        modifier = modifier
            .then(
                when (direction) {
                    ResizeDirection.NORTH, ResizeDirection.SOUTH -> Modifier.fillMaxWidth().height(WindowResizeGrip)
                    ResizeDirection.EAST, ResizeDirection.WEST -> Modifier.fillMaxHeight().width(WindowResizeGrip)
                    else -> Modifier.size(WindowResizeGrip)
                },
            )
            .pointerHoverIcon(PointerIcon(direction.resizeCursor()))
            .pointerInput(direction, windowState.placement) {
                var startBounds: java.awt.Rectangle? = null
                var startPointerLocation: Point? = null
                detectDragGestures(
                    onDragStart = {
                        if (windowState.placement != WindowPlacement.Maximized) {
                            startBounds = window.bounds
                            startPointerLocation = pointerLocation()
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        if (windowState.placement == WindowPlacement.Maximized) return@detectDragGestures
                        val bounds = startBounds
                        val initialPointer = startPointerLocation
                        val pointer = pointerLocation()
                        if (bounds != null && initialPointer != null && pointer != null) {
                            window.resizeFrom(
                                bounds,
                                Point(pointer.x - initialPointer.x, pointer.y - initialPointer.y),
                                direction,
                            )
                        }
                    },
                    onDragEnd = { startPointerLocation = null; startBounds = null },
                    onDragCancel = { startPointerLocation = null; startBounds = null },
                )
            },
    )
}

private fun ResizeDirection.resizeCursor(): Cursor =
    Cursor.getPredefinedCursor(
        when (this) {
            ResizeDirection.NORTH -> Cursor.N_RESIZE_CURSOR
            ResizeDirection.SOUTH -> Cursor.S_RESIZE_CURSOR
            ResizeDirection.EAST -> Cursor.E_RESIZE_CURSOR
            ResizeDirection.WEST -> Cursor.W_RESIZE_CURSOR
            ResizeDirection.NORTH_EAST -> Cursor.NE_RESIZE_CURSOR
            ResizeDirection.NORTH_WEST -> Cursor.NW_RESIZE_CURSOR
            ResizeDirection.SOUTH_EAST -> Cursor.SE_RESIZE_CURSOR
            ResizeDirection.SOUTH_WEST -> Cursor.SW_RESIZE_CURSOR
        },
    )

private fun pointerLocation(): Point? = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()

private fun Window.isActuallyMaximized(): Boolean =
    (this as? Frame)?.let { frame -> frame.extendedState and Frame.MAXIMIZED_BOTH != 0 } ?: false

internal data class WindowDragRestoreAnchor(
    val maximizedBounds: Rectangle,
    val horizontalFraction: Double,
    val pointerOffsetY: Int,
)

/**
 * Records the cursor's position within the maximized window using AWT screen coordinates. AWT
 * window bounds and [MouseInfo] use the same virtual-desktop coordinate space, including negative
 * coordinates on monitors positioned left/above the primary display.
 */
internal fun windowDragRestoreAnchor(maximizedBounds: Rectangle, pointer: Point): WindowDragRestoreAnchor {
    val width = maximizedBounds.width.coerceAtLeast(1)
    val horizontalFraction =
        ((pointer.x - maximizedBounds.x).toDouble() / width.toDouble()).coerceIn(0.0, 1.0)
    return WindowDragRestoreAnchor(
        maximizedBounds = Rectangle(maximizedBounds),
        horizontalFraction = horizontalFraction,
        pointerOffsetY = (pointer.y - maximizedBounds.y).coerceAtLeast(0),
    )
}

/** Keeps the same horizontal title-bar point beneath the pointer after restoring from maximize. */
internal fun restoredDragLocation(
    pointer: Point,
    restoredSize: Dimension,
    horizontalFraction: Double,
    pointerOffsetY: Int,
): Point {
    val width = restoredSize.width.coerceAtLeast(1)
    val height = restoredSize.height.coerceAtLeast(1)
    val xOffset = (width * horizontalFraction.coerceIn(0.0, 1.0)).roundToInt()
    val yOffset = pointerOffsetY.coerceIn(0, height - 1)
    return Point(pointer.x - xOffset, pointer.y - yOffset)
}

/**
 * A maximized process can be restored before ArchiveTune has observed any floating bounds (for
 * example when the previous session closed maximized). Use a comfortably smaller fallback rather
 * than restoring to a window that still fills the entire monitor and appears not to have restored.
 */
internal fun fallbackRestoredSize(maximizedBounds: Rectangle, minimum: Dimension): Dimension {
    val maxWidth = maximizedBounds.width.coerceAtLeast(1)
    val maxHeight = maximizedBounds.height.coerceAtLeast(1)
    val targetWidth = (maxWidth * 0.80).roundToInt().coerceAtLeast(minimum.width).coerceAtMost(maxWidth)
    val targetHeight = (maxHeight * 0.80).roundToInt().coerceAtLeast(minimum.height).coerceAtMost(maxHeight)
    return Dimension(targetWidth, targetHeight)
}

/** Centers the startup fallback within the monitor's maximized bounds, including negative origins. */
internal fun fallbackRestoredBounds(maximizedBounds: Rectangle, minimum: Dimension): Rectangle {
    val size = fallbackRestoredSize(maximizedBounds, minimum)
    return Rectangle(
        maximizedBounds.x + (maximizedBounds.width - size.width) / 2,
        maximizedBounds.y + (maximizedBounds.height - size.height) / 2,
        size.width,
        size.height,
    )
}

private fun Window.resizeFrom(
    start: Rectangle,
    delta: Point,
    direction: ResizeDirection,
) {
    bounds = resizedWindowBounds(start, delta, minimumSize, direction)
}

/** Pure resize geometry so every edge/corner and minimum-size clamp can be regression tested. */
internal fun resizedWindowBounds(
    start: Rectangle,
    delta: Point,
    minimum: Dimension,
    direction: ResizeDirection,
): Rectangle {
    val east = direction == ResizeDirection.EAST || direction == ResizeDirection.NORTH_EAST || direction == ResizeDirection.SOUTH_EAST
    val west = direction == ResizeDirection.WEST || direction == ResizeDirection.NORTH_WEST || direction == ResizeDirection.SOUTH_WEST
    val south = direction == ResizeDirection.SOUTH || direction == ResizeDirection.SOUTH_EAST || direction == ResizeDirection.SOUTH_WEST
    val north = direction == ResizeDirection.NORTH || direction == ResizeDirection.NORTH_EAST || direction == ResizeDirection.NORTH_WEST
    val targetWidth = (start.width + when {
        east -> delta.x
        west -> -delta.x
        else -> 0
    }).coerceAtLeast(minimum.width)
    val targetHeight = (start.height + when {
        south -> delta.y
        north -> -delta.y
        else -> 0
    }).coerceAtLeast(minimum.height)
    val targetX = if (west) start.x + start.width - targetWidth else start.x
    val targetY = if (north) start.y + start.height - targetHeight else start.y
    return Rectangle(targetX, targetY, targetWidth, targetHeight)
}

@Composable
private fun WindowChromeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    close: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val hoverColor = if (close) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    Box(
        modifier = Modifier
            .width(46.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(0.dp))
            .hoverable(interactionSource)
            .background(if (hovered) hoverColor else Color.Transparent)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(16.dp),
            tint = if (close && hovered) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

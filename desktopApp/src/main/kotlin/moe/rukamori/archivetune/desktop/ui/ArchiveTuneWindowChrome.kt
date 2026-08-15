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
import java.awt.Frame
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Window

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
    val toggleMaximize = {
        windowState.placement = if (maximized) WindowPlacement.Floating else WindowPlacement.Maximized
    }
    val dragModifier = remember(windowState.placement) {
        Modifier
            .pointerInput(windowState.placement) {
                detectTapGestures(onDoubleTap = {
                    toggleMaximize()
                })
            }
            .pointerInput(windowState.placement) {
                var startWindowLocation: Point? = null
                var startPointerLocation: Point? = null
                detectDragGestures(
                    onDragStart = {
                        // A maximized window deliberately remains maximized while dragging. This
                        // avoids the large jump caused by restoring asynchronously; double-click
                        // the same region to restore, matching the stable native fallback.
                        if (windowState.placement == WindowPlacement.Maximized) return@detectDragGestures
                        startWindowLocation = window.location
                        startPointerLocation = pointerLocation()
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        if (windowState.placement == WindowPlacement.Maximized) return@detectDragGestures
                        val initialWindow = startWindowLocation
                        val initialPointer = startPointerLocation
                        val pointer = pointerLocation()
                        if (initialWindow != null && initialPointer != null && pointer != null) {
                            window.location = Point(
                                initialWindow.x + pointer.x - initialPointer.x,
                                initialWindow.y + pointer.y - initialPointer.y,
                            )
                        }
                    },
                    onDragEnd = {
                        startWindowLocation = null
                        startPointerLocation = null
                    },
                    onDragCancel = {
                        startWindowLocation = null
                        startPointerLocation = null
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

private enum class ResizeDirection { NORTH, SOUTH, EAST, WEST, NORTH_EAST, NORTH_WEST, SOUTH_EAST, SOUTH_WEST }

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

private fun Window.resizeFrom(
    start: java.awt.Rectangle,
    delta: Point,
    direction: ResizeDirection,
) {
    val minimum = minimumSize
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
    setBounds(targetX, targetY, targetWidth, targetHeight)
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

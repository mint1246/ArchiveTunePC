package moe.rukamori.archivetune.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun DesktopNotificationHost(
    notifications: List<DesktopAppNotification>,
    onOpen: (DesktopAppNotification) -> Unit,
    onDismiss: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = notifications,
        transitionSpec = {
            val enter = fadeIn(tween(180, easing = FastOutSlowInEasing)) +
                slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { it / 5 }
            val exit = fadeOut(tween(140)) +
                slideOutHorizontally(tween(180, easing = FastOutSlowInEasing)) { it / 6 }
            (enter togetherWith exit).using(SizeTransform(clip = false))
        },
        label = "desktop-notifications",
        modifier = modifier,
    ) { visible ->
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.animateContentSize(),
        ) {
            visible.forEach { notification ->
                NotificationBubble(
                    notification = notification,
                    onOpen = { onOpen(notification) },
                    onDismiss = { onDismiss(notification.id) },
                )
            }
        }
    }
}

@Composable
private fun NotificationBubble(
    notification: DesktopAppNotification,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clickable = notification.destination != null
    Surface(
        modifier = Modifier
            .widthIn(min = 300.dp, max = 380.dp)
            .then(if (clickable) Modifier.clickable(onClick = onOpen) else Modifier),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 13.dp, end = 8.dp, bottom = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = notification.kind.icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(
                modifier = Modifier.padding(start = 12.dp).weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = notification.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = notification.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.size(4.dp))
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Dismiss notification",
                    modifier = Modifier.size(17.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val DesktopNotificationKind.icon: ImageVector
    get() = when (this) {
        DesktopNotificationKind.INFO -> Icons.Rounded.Info
        DesktopNotificationKind.FRIEND -> Icons.Rounded.Group
        DesktopNotificationKind.MESSAGE -> Icons.Rounded.ChatBubble
        DesktopNotificationKind.SUCCESS -> Icons.Rounded.Check
        DesktopNotificationKind.PARTY -> Icons.Rounded.PlayArrow
    }

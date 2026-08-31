package moe.rukamori.archivetune.desktop.ui

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.state.DesktopDestination

enum class DesktopNotificationKind {
    INFO,
    FRIEND,
    MESSAGE,
    SUCCESS,
    PARTY,
}

data class DesktopAppNotification(
    val id: Long,
    val title: String,
    val message: String,
    val kind: DesktopNotificationKind = DesktopNotificationKind.INFO,
    val destination: DesktopDestination? = null,
)

internal class DesktopNotificationCenter(
    private val scope: CoroutineScope,
) {
    private val nextId = AtomicLong(0L)
    private val _notifications = MutableStateFlow<List<DesktopAppNotification>>(emptyList())
    val notifications: StateFlow<List<DesktopAppNotification>> = _notifications.asStateFlow()

    fun post(
        title: String,
        message: String,
        kind: DesktopNotificationKind = DesktopNotificationKind.INFO,
        destination: DesktopDestination? = null,
        durationMs: Long = DEFAULT_DURATION_MS,
    ) {
        val notification = DesktopAppNotification(
            id = nextId.incrementAndGet(),
            title = title,
            message = message,
            kind = kind,
            destination = destination,
        )
        _notifications.update { current -> (current + notification).takeLast(MAX_VISIBLE_NOTIFICATIONS) }
        scope.launch {
            delay(durationMs)
            dismiss(notification.id)
        }
    }

    fun dismiss(id: Long) {
        _notifications.update { current -> current.filterNot { it.id == id } }
    }

    private companion object {
        const val DEFAULT_DURATION_MS = 6_000L
        const val MAX_VISIBLE_NOTIFICATIONS = 3
    }
}

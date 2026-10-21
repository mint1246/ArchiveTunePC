/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.linuxmedia

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global shortcuts for the Linux desktop.
 *
 * The class owns the `org.freedesktop.portal.GlobalShortcuts` session that carries the system wide
 * shortcuts. The portal only understands modifiers plus a single ordinary key, so chords that use
 * several ordinary keys are reported through [status] instead of being bound to something else.
 *
 * Every change of the shortcut list starts a new revision. A revision that was superseded while it
 * was still waiting for the desktop is closed, so an outdated prompt can never bind an outdated
 * shortcut and no outdated status is published.
 */
class LinuxGlobalKeyBindings(
    private val onAction: (String) -> Unit,
    private val portalFactory: () -> GlobalShortcutsPortal = { DbusGlobalShortcutsPortal() },
    private val diagnostic: (String) -> Unit = {},
) : AutoCloseable {
    private val _status = MutableStateFlow<String?>(null)

    /** Human readable warning about the global shortcuts, or null when everything is ready. */
    val status: StateFlow<String?> = _status.asStateFlow()

    private val lock = Any()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "archive-tune-linux-global-shortcuts").apply { isDaemon = true }
    }
    private val binder: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "archive-tune-linux-global-shortcuts-bind").apply { isDaemon = true }
    }
    private val repeats = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private var revision = 0L
    private var portal: GlobalShortcutsPortal? = null
    private var bindings: List<LinuxGlobalKeyBinding> = emptyList()
    @Volatile private var captureActive = false
    @Volatile private var closed = false

    /**
     * Replaces the shortcuts that are bound to the portal.
     *
     * The work happens in the background because the portal may show a confirmation dialog.
     */
    fun updateBindings(bindings: List<LinuxGlobalKeyBinding>) {
        val sanitized = bindings
            .mapNotNull { binding ->
                val keys = binding.virtualKeys
                    .asSequence()
                    .map(LinuxVirtualKeys::normalize)
                    .filter { it > 0 }
                    .toSet()
                binding.takeIf { keys.isNotEmpty() }?.copy(virtualKeys = keys)
            }
            .distinctBy { it.actionId }
        val revision = synchronized(lock) {
            if (closed || sanitized == this.bindings) return
            val next = this.revision + 1L
            this.revision = next
            this.bindings = sanitized
            next
        }
        repeats.keys.toList().forEach(::cancelRepeat)
        binder.execute { bind(revision) }
    }

    /**
     * Suspends the global shortcuts while a shortcut is being recorded.
     *
     * The portal keeps delivering activations while the settings surface is open, and those would
     * trigger playback commands in the middle of a recording.
     */
    fun setCaptureActive(active: Boolean) {
        captureActive = active
        if (active) repeats.keys.toList().forEach(::cancelRepeat)
    }

    private fun bind(revision: Long) {
        val snapshot = synchronized(lock) {
            if (closed || revision != this.revision) return
            bindings
        }
        val unsupported = snapshot.filterNot { LinuxShortcutTriggers.isSupported(it.virtualKeys) }
        val descriptors = snapshot.mapNotNull { binding ->
            val trigger = LinuxShortcutTriggers.triggerFor(binding.virtualKeys) ?: return@mapNotNull null
            LinuxGlobalShortcutDescriptor(
                id = binding.actionId,
                description = humanizeActionId(binding.actionId),
                trigger = trigger,
            )
        }
        if (descriptors.isEmpty()) {
            releasePortal(revision)
            publishStatus(revision = revision, unsupported = unsupported, missing = emptyList(), failure = null)
            return
        }
        val active = createPortal(revision) ?: run {
            publishStatus(revision = revision, unsupported = unsupported, missing = emptyList(), failure = "the desktop portal is not available")
            return
        }
        val result = try {
            active.bind(descriptors)
        } catch (failure: Exception) {
            diagnostic("The global shortcut request failed: ${failure.message}")
            GlobalShortcutsBindResult.Failed("the desktop portal refused the shortcut request")
        }
        if (isStale(revision)) {
            runCatching { active.close() }
            return
        }
        when (result) {
            is GlobalShortcutsBindResult.Bound -> {
                val missing = descriptors.map { it.id }.filterNot { it in result.boundIds }
                publishStatus(revision = revision, unsupported = unsupported, missing = missing, failure = null)
            }

            is GlobalShortcutsBindResult.Failed -> publishStatus(
                revision = revision,
                unsupported = unsupported,
                missing = emptyList(),
                failure = result.reason,
            )
        }
    }

    /**
     * Creates the portal of a revision and closes the portal of the previous one.
     *
     * Closing the previous portal ends the session of the previous revision, so the chords of the
     * old revision stop grabbing the keys and a pending confirmation dialog is dismissed.
     */
    private fun createPortal(revision: Long): GlobalShortcutsPortal? {
        val created = runCatching { portalFactory() }.getOrNull() ?: return null
        created.setListener(listenerFor(revision))
        val (accepted, previous) = synchronized(lock) {
            if (closed || revision != this.revision) {
                false to null
            } else {
                val current = portal
                portal = created
                true to current
            }
        }
        if (!accepted) {
            runCatching { created.close() }
            return null
        }
        previous?.let { runCatching { it.close() } }
        return created
    }

    private fun releasePortal(revision: Long) {
        val released = synchronized(lock) {
            if (closed || revision != this.revision) return
            val current = portal
            portal = null
            current
        }
        released?.let { runCatching { it.close() } }
    }

    private fun isStale(revision: Long): Boolean = synchronized(lock) {
        closed || revision != this.revision
    }

    private fun publishStatus(revision: Long, unsupported: List<LinuxGlobalKeyBinding>, missing: List<String>, failure: String?) {
        synchronized(lock) {
            if (closed || revision != this.revision) return
            _status.value = when {
                failure != null -> "System-wide shortcuts are unavailable: $failure."
                missing.isNotEmpty() ->
                    "The desktop did not accept the system-wide shortcut for " +
                        missing.joinToString(", ") { humanizeActionId(it) } + "."

                unsupported.isNotEmpty() ->
                    "Linux system-wide shortcuts support modifiers plus one key, for example Ctrl+Shift+K. " +
                        "${unsupported.size} saved shortcuts could not be registered."

                else -> null
            }
        }
    }

    private fun listenerFor(revision: Long): GlobalShortcutsListener = object : GlobalShortcutsListener {
        override fun onActivated(shortcutId: String) {
            if (captureActive) return
            trigger(revision, shortcutId)
        }

        override fun onDeactivated(shortcutId: String) {
            if (!isStale(revision)) cancelRepeat(shortcutId)
        }
        override fun onClosed() {
            if (isStale(revision)) return
            repeats.keys.toList().forEach(::cancelRepeat)
            publishStatus(revision, emptyList(), emptyList(), "the desktop shortcut session ended; set the shortcut again")
        }
    }

    private fun trigger(revision: Long, actionId: String) {
        val binding = synchronized(lock) {
            if (closed || revision != this.revision) null else bindings.firstOrNull { it.actionId == actionId }
        } ?: return
        runCatching { onAction(actionId) }
        if (binding.repeatWhileHeld) startRepeat(actionId)
    }

    private fun startRepeat(actionId: String) {
        cancelRepeat(actionId)
        val future = scheduler.scheduleWithFixedDelay(
            { if (!captureActive && !closed) runCatching { onAction(actionId) } },
            REPEAT_DELAY_MS,
            REPEAT_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
        repeats[actionId] = future
    }

    private fun cancelRepeat(actionId: String) {
        repeats.remove(actionId)?.let { future -> future.cancel(false) }
    }

    override fun close() {
        val portalToClose = synchronized(lock) {
            if (closed) return
            closed = true
            revision += 1L
            bindings = emptyList()
            val current = portal
            portal = null
            current
        }
        portalToClose?.let { runCatching { it.close() } }
        repeats.values.forEach { future -> future.cancel(false) }
        repeats.clear()
        binder.shutdownNow()
        scheduler.shutdownNow()
        _status.value = null
    }

    private companion object {
        const val REPEAT_DELAY_MS = 350L
        const val REPEAT_INTERVAL_MS = 80L

        /** Turns an action id such as `PLAY_PAUSE` into the text the portal shows. */
        fun humanizeActionId(actionId: String): String = actionId
            .lowercase()
            .replace('_', ' ')
            .replaceFirstChar { it.uppercase() }
    }
}

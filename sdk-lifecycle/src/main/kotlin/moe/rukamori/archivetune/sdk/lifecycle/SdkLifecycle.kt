/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.sdk.lifecycle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * Deterministic initialization and shutdown boundary for a group of headless SDK components.
 *
 * [SdkLifecycle] does not configure any SDK feature, open a network connection, or start a
 * playback session. It owns only two things a headless consumer must coordinate itself:
 *
 * 1. child [CoroutineScope]s created with [scope], which inherit a caller-supplied context and are
 *    cancelled before any component is closed; and
 * 2. a registry of [AutoCloseable] components and [onClosing] callbacks, closed in reverse
 *    registration order on [close].
 *
 * Registration and close are safe to call from different threads. [close] is idempotent and always
 * runs every registered cleanup once, even when one of them fails. Cancellation exceptions thrown
 * by cleanup are treated as normal early exits, not shutdown failures. A component registered
 * after shutdown has begun is closed immediately and is never retained.
 */
class SdkLifecycle(
    private val parentContext: CoroutineContext,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val entries = ArrayDeque<CleanupEntry>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val closingHooks = LinkedHashMap<String, () -> Unit>()

    /** True once [close] has begun; no new scope or component can be registered afterwards. */
    val isClosed: Boolean get() = closed.get()

    /**
     * Creates a child scope for an SDK component and registers its cancellation with this
     * lifecycle. The child has its own [SupervisorJob], so a failure in one component does not
     * cancel its siblings. The caller never closes the returned scope directly; [close] does.
     */
    fun scope(): CoroutineScope {
        val child = CoroutineScope(parentContext + SupervisorJob(parentContext[Job]))
        val registered =
            synchronized(lock) {
                if (closed.get()) {
                    false
                } else {
                    scopes.add(child)
                    true
                }
            }
        if (!registered) {
            child.cancel()
            throw IllegalStateException("SDK lifecycle is already closed")
        }
        return child
    }

    /**
     * Registers [component] for shutdown with this lifecycle and returns it unchanged.
     *
     * If the lifecycle is already closed, the component is closed immediately as a terminal
     * cleanup rather than leaking an unowned resource.
     */
    fun <T : AutoCloseable> register(component: T): T {
        register(CleanupEntry.Component(component))
        return component
    }

    /**
     * Registers a named, idempotent callback to run during shutdown, before registered
     * components. Re-registering the same [name] keeps the first callback and has no further
     * effect, so repeated initialization cannot install duplicate lifecycle hooks.
     */
    fun onClosing(
        name: String,
        hook: () -> Unit,
    ) {
        require(name.isNotBlank()) { "Hook name must not be blank" }
        val runNow = synchronized(lock) {
            if (closed.get()) true else {
                closingHooks.putIfAbsent(name, hook)
                false
            }
        }
        if (runNow) safeRun(hook, ignoreCancellation = false)?.let { throw it }
    }

    /**
     * Removes a previously registered component so it will not be closed by this lifecycle.
     * Returns true when the component was registered and removed. Useful when ownership of a
     * component is transferred to another owner.
     */
    fun unregister(component: AutoCloseable): Boolean =
        synchronized(lock) {
            entries.removeIf { entry -> entry is CleanupEntry.Component && entry.component === component }
        }

    /**
     * Closes all registered cleanup in reverse registration order, exactly once. Child scopes
     * registered with [scope] are cancelled before components and closing hooks run. Failures do
     * not stop the remaining cleanup: the first failure is rethrown and all later failures are
     * attached as suppressed exceptions.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val hooks: List<() -> Unit>
        val cleanup: List<CleanupEntry>
        val children: List<CoroutineScope>
        synchronized(lock) {
            children = scopes.toList()
            scopes.clear()
            hooks = closingHooks.values.toList().asReversed()
            closingHooks.clear()
            cleanup = entries.toList().asReversed()
            entries.clear()
        }

        var failure: Throwable? = null
        children.forEach { it.cancel() }
        for (hook in hooks) {
            failure = accumulate(failure, safeRun(hook, ignoreCancellation = false))
        }
        for (entry in cleanup) {
            val error =
                when (entry) {
                    is CleanupEntry.Component ->
                        safeRun({ entry.component.close() }, ignoreCancellation = true)
                }
            failure = accumulate(failure, error)
        }
        failure?.let { throw it }
    }

    private fun register(entry: CleanupEntry) {
        val wasClosed = synchronized(lock) {
            if (closed.get()) true else {
                entries.addLast(entry)
                false
            }
        }
        if (wasClosed && entry is CleanupEntry.Component) {
            safeRun({ entry.component.close() }, ignoreCancellation = true)?.let { throw it }
        }
    }

    private fun safeRun(
        action: () -> Unit,
        ignoreCancellation: Boolean,
    ): Throwable? =
        try {
            action()
            null
        } catch (cancellation: CancellationException) {
            if (ignoreCancellation) null else cancellation
        } catch (error: Throwable) {
            error
        }

    private fun accumulate(first: Throwable?, next: Throwable?): Throwable? {
        if (next == null) return first
        if (first == null) return next
        if (first !== next) first.addSuppressed(next)
        return first
    }

    private sealed interface CleanupEntry {
        data class Component(val component: AutoCloseable) : CleanupEntry
    }

    companion object {
        /**
         * Creates a lifecycle whose child scopes inherit the context of an already-owned scope.
         * The parent scope itself is never cancelled by [close], only the children created here.
         */
        fun forScope(scope: CoroutineScope): SdkLifecycle = SdkLifecycle(scope.coroutineContext)
    }
}

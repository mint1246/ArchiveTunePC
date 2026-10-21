/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.linuxmedia

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusSigHandler
import org.freedesktop.dbus.matchrules.DBusMatchRuleBuilder
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.messages.Error
import org.freedesktop.dbus.messages.Message
import org.freedesktop.dbus.messages.MethodCall
import org.freedesktop.dbus.messages.constants.MessageTypes
import org.freedesktop.dbus.types.Variant

/**
 * Talks to `org.freedesktop.portal.GlobalShortcuts` over a persistent D-Bus session connection.
 *
 * A single connection is kept for the whole lifetime of the portal object so the session, the
 * bound shortcuts and the activation signals all share one bus connection. The portal only allows
 * a single bind attempt per session, so every bind replaces the previous session and closes it
 * first, otherwise the old chords would keep grabbing the keys.
 */
class DbusGlobalShortcutsPortal(
    private val appId: String = DEFAULT_APP_ID,
    private val diagnostic: (String) -> Unit = {},
) : GlobalShortcutsPortal {
    private val lock = Any()
    private val responses = ConcurrentHashMap<String, CompletableFuture<PortalResponse>>()
    @Volatile private var connection: DBusConnection? = null
    @Volatile private var closed = false
    private var registered = false
    private var handlers: List<AutoCloseable> = emptyList()
    @Volatile private var sessionHandle: String? = null
    private var tokenCounter = 0

    @Volatile private var listener: GlobalShortcutsListener? = null

    override fun setListener(listener: GlobalShortcutsListener?) {
        this.listener = listener
    }

    override fun bind(shortcuts: List<LinuxGlobalShortcutDescriptor>): GlobalShortcutsBindResult {
        // Never hold the lifecycle lock while waiting for the user. Clearing/replacing a binding
        // must be able to cancel its request immediately from the settings screen.
        closeSession()
        if (closed) return GlobalShortcutsBindResult.Failed("the shortcut request was cancelled", cancelled = true)
        if (shortcuts.isEmpty()) return GlobalShortcutsBindResult.Bound(emptyList())
        var failure = "the desktop portal is not available on this session"
        repeat(BIND_ATTEMPTS) { attempt ->
            if (closed || Thread.currentThread().isInterrupted) {
                return GlobalShortcutsBindResult.Failed("the shortcut request was cancelled", cancelled = true)
            }
            val result = bindOnce(shortcuts)
            if (result is GlobalShortcutsBindResult.Bound) return result
            result as GlobalShortcutsBindResult.Failed
            if (result.cancelled) return result
            failure = result.reason
            diagnostic("Global shortcut attempt ${attempt + 1} failed: $failure")
            closeConnection()
            if (closed) return GlobalShortcutsBindResult.Failed("the shortcut request was cancelled", cancelled = true)
            if (attempt + 1 < BIND_ATTEMPTS) Thread.sleep(RETRY_DELAY_MS)
        }
        return GlobalShortcutsBindResult.Failed(failure)
    }

    private fun bindOnce(shortcuts: List<LinuxGlobalShortcutDescriptor>): GlobalShortcutsBindResult {
        val connection = ensureConnection()
            ?: return GlobalShortcutsBindResult.Failed("the desktop portal is not available on this session")
        val session = createSession(connection)
            ?: return GlobalShortcutsBindResult.Failed("the desktop portal did not create a global shortcut session")
        sessionHandle = session
        val result = bindShortcuts(connection, session, shortcuts)
        if (closed) return GlobalShortcutsBindResult.Failed("the shortcut request was cancelled", cancelled = true)
        return result
    }

    /** Opens the bus connection and installs the signal handlers. */
    private fun ensureConnection(): DBusConnection? {
        if (closed) return null
        connection?.let { existing ->
            if (existing.isConnected) return existing
            closeConnection()
        }
        return try {
            val created = DBusConnectionBuilder.forSessionBus().withShared(false).build()
            handlers = listOf(
                created.addGenericSigHandler(
                    DBusMatchRuleBuilder.create()
                        .withType("signal")
                        .withInterface(REQUEST_INTERFACE)
                        .withMember(RESPONSE_MEMBER)
                        .build(),
                    DBusSigHandler<DBusSignal> { signal -> handleResponse(signal) },
                ),
                created.addGenericSigHandler(
                    DBusMatchRuleBuilder.create()
                        .withType("signal")
                        .withInterface(GLOBAL_SHORTCUTS_INTERFACE)
                        .withMember(ACTIVATED_MEMBER)
                        .build(),
                    DBusSigHandler<DBusSignal> { signal -> handleActivation(signal, activated = true) },
                ),
                created.addGenericSigHandler(
                    DBusMatchRuleBuilder.create()
                        .withType("signal")
                        .withInterface(GLOBAL_SHORTCUTS_INTERFACE)
                        .withMember(DEACTIVATED_MEMBER)
                        .build(),
                    DBusSigHandler<DBusSignal> { signal -> handleActivation(signal, activated = false) },
                ),
            )
            handlers = handlers + created.addGenericSigHandler(
                DBusMatchRuleBuilder.create().withType("signal").withInterface(SESSION_INTERFACE).withMember("Closed").build(),
                DBusSigHandler<DBusSignal> { signal ->
                    if (sessionHandle != null && signal.path == sessionHandle) {
                        sessionHandle = null
                        listener?.onClosed()
                    }
                },
            ) + created.addGenericSigHandler(
                DBusMatchRuleBuilder.create().withType("signal").withInterface("org.freedesktop.DBus").withMember("NameOwnerChanged").build(),
                DBusSigHandler<DBusSignal> { signal ->
                    val parameters = runCatching { signal.parameters }.getOrNull()
                    if (parameters?.getOrNull(0) == DESKTOP_SERVICE) {
                        registered = false
                        if (sessionHandle != null) { sessionHandle = null; listener?.onClosed() }
                    }
                },
            )
            connection = created
            registered = false
            if (closed) { closeConnection(); null } else created
        } catch (failure: Exception) {
            diagnostic("Could not connect to the session bus: ${failure.message}")
            connection = null
            null
        }
    }

    /**
     * Associates the connection with an application id.
     *
     * Unsandboxed applications have to register before they may call a portal method. The portal
     * forgets the registration when it restarts, so the flag is dropped whenever a call fails and
     * the next attempt registers again.
     */
    private fun registerApplication(connection: DBusConnection): Boolean {
        if (registered) return true
        val token = nextToken("register")
        val call = connection.getMessageFactory().createMethodCall(
            DESKTOP_SERVICE,
            DESKTOP_PATH,
            REGISTRY_INTERFACE,
            "Register",
            0.toByte(),
            "sa{sv}",
            appId,
            emptyMap<String, Variant<*>>(),
        )
        // The registration is not a portal request, so it has no response signal.
        connection.sendMessage(call)
        val reply = call.getReply(CALL_TIMEOUT_MS)
        if (reply == null) {
            diagnostic("The desktop portal did not answer the application registration.")
            return false
        }
        if (reply.getType() == MessageTypes.ERROR.getId()) {
            // A repeated registration is reported as an error but keeps the earlier registration.
            diagnostic("The desktop portal refused the application registration: ${errorText(reply)}")
            return false
        }
        registered = true
        diagnostic("Registered the global shortcut portal as '$appId' (request $token).")
        return true
    }

    private fun createSession(connection: DBusConnection): String? {
        if (!registerApplication(connection)) return null
        val token = nextToken("session")
        val pending = responses.computeIfAbsent(token) { CompletableFuture() }
        val options = mapOf<String, Variant<*>>(
            "handle_token" to Variant(token),
            "session_handle_token" to Variant(nextToken("owner")),
        )
        val call = connection.getMessageFactory().createMethodCall(
            DESKTOP_SERVICE,
            DESKTOP_PATH,
            GLOBAL_SHORTCUTS_INTERFACE,
            "CreateSession",
            0.toByte(),
            "a{sv}",
            options,
        )
        connection.sendMessage(call)
        val reply = call.getReply(CALL_TIMEOUT_MS)
        if (reply == null || reply.getType() == MessageTypes.ERROR.getId()) {
            if (reply != null) diagnostic("The desktop portal refused the session: ${errorText(reply)}")
            responses.remove(token)
            registered = false
            return null
        }
        val response = awaitResponse(pending, token) ?: return null
        val results = when (response) {
            is PortalResponse.Success -> response.results
            is PortalResponse.Cancelled -> return null
            is PortalResponse.Failed -> return null
        }
        return results["session_handle"]?.value?.toString()
    }

    private fun bindShortcuts(
        connection: DBusConnection,
        session: String,
        shortcuts: List<LinuxGlobalShortcutDescriptor>,
    ): GlobalShortcutsBindResult {
        val payload: List<Any> = shortcuts.map { descriptor ->
            arrayOf<Any>(
                descriptor.id,
                mapOf<String, Variant<*>>(
                    "description" to Variant(descriptor.description),
                    "preferred_trigger" to Variant(descriptor.trigger),
                ),
            )
        }
        val token = nextToken("bind")
        val pending = responses.computeIfAbsent(token) { CompletableFuture() }
        val options = mapOf<String, Variant<*>>("handle_token" to Variant(token))
        val call = connection.getMessageFactory().createMethodCall(
            DESKTOP_SERVICE,
            DESKTOP_PATH,
            GLOBAL_SHORTCUTS_INTERFACE,
            "BindShortcuts",
            0.toByte(),
            "oa(sa{sv})sa{sv}",
            session,
            payload,
            "",
            options,
        )
        connection.sendMessage(call)
        val reply = call.getReply(CALL_TIMEOUT_MS)
        if (reply == null || reply.getType() == MessageTypes.ERROR.getId()) {
            if (reply != null) diagnostic("The desktop portal refused the shortcut request: ${errorText(reply)}")
            responses.remove(token)
            registered = false
            return GlobalShortcutsBindResult.Failed("the desktop portal refused the shortcut request")
        }
        val response = awaitResponse(pending, token)
            ?: return GlobalShortcutsBindResult.Failed("the desktop portal did not confirm the shortcut request")
        return when (response) {
            is PortalResponse.Success -> {
                val bound = response.results["shortcuts"]?.value as? List<*>
                val boundIds = bound.orEmpty().mapNotNull { entry ->
                    (entry as? Array<*>)?.firstOrNull()?.toString()
                }
                diagnostic("Bound global shortcuts: ${boundIds.ifEmpty { listOf("none") }}")
                GlobalShortcutsBindResult.Bound(boundIds)
            }

            is PortalResponse.Cancelled -> GlobalShortcutsBindResult.Failed(
                reason = "the shortcut request was dismissed",
                cancelled = true,
            )

            is PortalResponse.Failed -> GlobalShortcutsBindResult.Failed(response.reason)
        }
    }

    /** Waits for the `Response` signal of the request object that the portal created. */
    private fun awaitResponse(pending: CompletableFuture<PortalResponse>, token: String): PortalResponse? = try {
        pending.get(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    } catch (failure: Exception) {
        diagnostic("No portal response for $token: ${failure.message}")
        null
    } finally {
        responses.remove(token)
    }

    private fun handleResponse(signal: DBusSignal) {
        val path = signal.path ?: return
        // The token is the last element of the request path, which keeps the responses of other
        // applications out of this connection.
        val token = path.substringAfterLast('/')
        val pending = responses[token] ?: return
        val owner = connection?.uniqueName?.removePrefix(":")?.replace('.', '_') ?: return
        if (path != "/org/freedesktop/portal/desktop/request/$owner/$token") return
        val parameters = try {
            signal.parameters
        } catch (failure: Exception) {
            diagnostic("Could not read the portal response: ${failure.message}")
            return
        }
        val code = (parameters.getOrNull(0) as? Number)?.toLong() ?: return
        val raw = parameters.getOrNull(1) as? Map<*, *> ?: emptyMap<Any, Any>()
        val results: Map<String, Variant<*>> = raw.entries.associate { (key, value) ->
            key.toString() to (value as? Variant<*> ?: Variant(value.toString()))
        }
        val response = when (code) {
            RESPONSE_SUCCESS -> PortalResponse.Success(results)
            RESPONSE_CANCELLED -> PortalResponse.Cancelled
            else -> PortalResponse.Failed(results["error"]?.value?.toString() ?: "the portal request failed")
        }
        pending.complete(response)
    }

    private fun handleActivation(signal: DBusSignal, activated: Boolean) {
        val parameters = try {
            signal.parameters
        } catch (failure: Exception) {
            diagnostic("Could not read the portal activation: ${failure.message}")
            return
        }
        val session = parameters.getOrNull(0)?.toString()
        val shortcutId = parameters.getOrNull(1)?.toString() ?: return
        val current = sessionHandle
        if (current == null || session == null || session != current) return
        val target = listener ?: return
        if (activated) target.onActivated(shortcutId) else target.onDeactivated(shortcutId)
    }

    /** Closes the portal session so the previously bound chords stop grabbing the keys. */
    private fun closeSession() {
        val session = sessionHandle ?: return
        sessionHandle = null
        val connection = connection ?: return
        try {
            val call = connection.getMessageFactory().createMethodCall(
                DESKTOP_SERVICE,
                session,
                SESSION_INTERFACE,
                "Close",
                0.toByte(),
                "",
            )
            connection.sendMessage(call)
        } catch (failure: Exception) {
            diagnostic("Could not close the portal session: ${failure.message}")
        }
    }

    private fun errorText(reply: Message): String {
        val error = reply as? Error
        val message = error?.exception?.message
        if (!message.isNullOrBlank()) return message
        return reply.name ?: "unknown error"
    }

    private fun nextToken(prefix: String): String {
        tokenCounter += 1
        return "archivetune_${prefix}_$tokenCounter"
    }

    private fun closeConnection() {
        val old = synchronized(lock) {
            val previous = connection to handlers
            connection = null
            handlers = emptyList()
            registered = false
            sessionHandle = null
            previous
        }
        responses.values.forEach { it.complete(PortalResponse.Cancelled) }
        responses.clear()
        old.second.forEach { handler -> runCatching { handler.close() } }
        runCatching { old.first?.close() }
    }

    override fun close() {
        closed = true
        listener = null
        // Disconnecting also destroys this peer's portal sessions and pending request dialogs.
        closeConnection()
    }

    /** Answer of the portal for one request. */
    private sealed interface PortalResponse {
        data class Success(val results: Map<String, Variant<*>>) : PortalResponse

        data object Cancelled : PortalResponse

        data class Failed(val reason: String) : PortalResponse
    }

    companion object {
        const val DEFAULT_APP_ID = "archivetune"

        private const val DESKTOP_SERVICE = "org.freedesktop.portal.Desktop"
        private const val DESKTOP_PATH = "/org/freedesktop/portal/desktop"
        private const val GLOBAL_SHORTCUTS_INTERFACE = "org.freedesktop.portal.GlobalShortcuts"
        private const val REGISTRY_INTERFACE = "org.freedesktop.host.portal.Registry"
        private const val SESSION_INTERFACE = "org.freedesktop.portal.Session"
        private const val REQUEST_INTERFACE = "org.freedesktop.portal.Request"
        private const val RESPONSE_MEMBER = "Response"
        private const val ACTIVATED_MEMBER = "Activated"
        private const val DEACTIVATED_MEMBER = "Deactivated"
        private const val CALL_TIMEOUT_MS = 10_000L
        private const val RESPONSE_TIMEOUT_MS = 30_000L
        private const val BIND_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 750L
        private const val RESPONSE_SUCCESS = 0L
        private const val RESPONSE_CANCELLED = 1L
    }
}

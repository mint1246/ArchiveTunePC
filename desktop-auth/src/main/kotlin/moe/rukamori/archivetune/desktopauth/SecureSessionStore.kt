/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktopauth

import com.sun.jna.platform.win32.Crypt32Util
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** A deliberately small seam around the OS key protection API. */
interface DpapiProtector {
    fun protect(plainText: ByteArray): ByteArray
    fun unprotect(cipherText: ByteArray): ByteArray
}

/** Windows-only, current-user DPAPI encryption via JNA's maintained Win32 binding. */
object WindowsDpapiProtector : DpapiProtector {
    override fun protect(plainText: ByteArray): ByteArray {
        requireWindows()
        return Crypt32Util.cryptProtectData(plainText)
    }

    override fun unprotect(cipherText: ByteArray): ByteArray {
        requireWindows()
        return Crypt32Util.cryptUnprotectData(cipherText)
    }

    private fun requireWindows() {
        check(System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "Windows DPAPI is not available on this operating system"
        }
    }
}

/** No plaintext or weak portable fallback exists: unsupported platforms fail closed. */
class SecureStoreUnavailableException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

interface SecureSessionStore {
    @Throws(SecureStoreUnavailableException::class)
    fun read(): ByteArray?

    @Throws(SecureStoreUnavailableException::class)
    fun write(value: ByteArray)

    @Throws(SecureStoreUnavailableException::class)
    fun delete()
}

/**
 * Stores only DPAPI ciphertext. The file is intentionally unusable after copying to another user
 * or Windows installation. On non-Windows systems all access fails rather than downgrading to
 * plaintext storage.
 */
class DpapiFileSessionStore(
    private val path: Path,
    private val protector: DpapiProtector = WindowsDpapiProtector,
) : SecureSessionStore {
    override fun read(): ByteArray? =
        guarded {
            if (!Files.exists(path)) return@guarded null
            protector.unprotect(Files.readAllBytes(path))
        }

    override fun write(value: ByteArray) {
        guarded {
            val parent = checkNotNull(path.parent) { "Secure session path must have a parent directory" }
            Files.createDirectories(parent)
            val encrypted = protector.protect(value)
            // Keep the previous ciphertext usable if the process is interrupted during a write.
            // A torn DPAPI payload otherwise turns a transient crash into a forced sign-in.
            val temporary = Files.createTempFile(parent, ".${path.fileName}.", ".tmp")
            try {
                Files.write(
                    temporary,
                    encrypted,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
                try {
                    Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }

    override fun delete() {
        guarded {
            Files.deleteIfExists(path)
        }
    }

    private inline fun <T> guarded(block: () -> T): T =
        try {
            block()
        } catch (failure: SecureStoreUnavailableException) {
            throw failure
        } catch (failure: Throwable) {
            throw SecureStoreUnavailableException("Secure Windows session storage is unavailable", failure)
        }
}

/** Keep the existing Windows DPAPI files; Linux stores secrets in the desktop keyring. */
fun secureSessionStore(path: Path): SecureSessionStore =
    when {
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> DpapiFileSessionStore(path)
        System.getProperty("os.name").startsWith("Linux", ignoreCase = true) -> SecretServiceSessionStore(path)
        else -> throw SecureStoreUnavailableException("No secure session storage backend exists for this operating system")
    }

/** A result from secret-tool. The stderr bytes must never be logged: keyring failures may include private data. */
internal data class SecretToolResponse(
    val status: Int,
    val output: ByteArray = ByteArray(0),
    val hadErrorOutput: Boolean = false,
)

internal fun interface SecretToolRunner {
    fun execute(arguments: List<String>, input: ByteArray?): SecretToolResponse
}

/**
 * A libsecret/Secret Service keyring entry, scoped to the current login user and this application.
 * Cookies and tokens are sent to secret-tool only through stdin (base64 handles binary payloads),
 * never argv, environment, application settings or a plaintext file.
 */
class SecretServiceSessionStore private constructor(
    private val key: String,
    private val runner: SecretToolRunner,
) : SecureSessionStore {
    constructor(path: Path) : this(secureSessionKey(path), SystemSecretToolRunner)
    internal constructor(path: Path, runner: SecretToolRunner) : this(secureSessionKey(path), runner)

    override fun read(): ByteArray? = guarded {
        val result = runner.execute(listOf("lookup") + attributes, null)
        if (result.status == 1 && result.output.isEmpty() && !result.hadErrorOutput) return@guarded null
        check(result.status == 0 && !result.hadErrorOutput) { "Keyring lookup failed" }
        val encoded = result.output.toString(Charsets.US_ASCII).trimEnd('\r', '\n')
        check(encoded.isNotEmpty()) { "Keyring entry is empty" }
        Base64.getDecoder().decode(encoded)
    }

    override fun write(value: ByteArray) {
        guarded {
            require(value.size <= MAX_SECRET_BYTES) { "Session exceeds secure storage size limit" }
            val encoded = Base64.getEncoder().encode(value)
            try {
                val stdin = encoded + byteArrayOf('\n'.code.toByte())
                try {
                    val result = runner.execute(listOf("store", "--label=ArchiveTune private session") + attributes, stdin)
                    check(result.status == 0 && !result.hadErrorOutput) { "Keyring write failed" }
                } finally {
                    stdin.fill(0)
                }
            } finally {
                encoded.fill(0)
            }
        }
    }

    override fun delete() {
        guarded {
            val result = runner.execute(listOf("clear") + attributes, null)
            // secret-tool uses status 1 without stderr for a key that does not exist.
            check((result.status == 0 || result.status == 1) && !result.hadErrorOutput) {
                "Keyring removal failed"
            }
        }
    }

    private val attributes: List<String>
        get() = listOf("application", "archivetune", "session-key", key)

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (failure: SecureStoreUnavailableException) {
        throw failure
    } catch (failure: Throwable) {
        throw SecureStoreUnavailableException("Linux Secret Service session storage is unavailable", failure)
    }

    companion object {
        private const val MAX_SECRET_BYTES = 1024 * 1024
    }
}

/** Stable opaque identifier: neither the filesystem path nor its associated secret is passed to the keyring. */
private fun secureSessionKey(path: Path): String =
    MessageDigest.getInstance("SHA-256")
        .digest(path.toAbsolutePath().normalize().toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

private object SystemSecretToolRunner : SecretToolRunner {
    override fun execute(arguments: List<String>, input: ByteArray?): SecretToolResponse {
        val binary = Path.of("/usr/bin/secret-tool")
        check(Files.isExecutable(binary)) { "libsecret secret-tool is missing" }
        val process = ProcessBuilder(listOf(binary.toString()) + arguments).start()
        try {
            // Drain both pipes while the keyring unlock prompt may be showing. Keep secrets in memory.
            val output = CompletableFuture.supplyAsync { process.inputStream.use { it.readNBytes(MAX_OUTPUT_BYTES + 1) } }
            val errors = CompletableFuture.supplyAsync { process.errorStream.use { it.readNBytes(MAX_OUTPUT_BYTES + 1) } }
            val writer = CompletableFuture.runAsync {
                process.outputStream.use { stream -> if (input != null) stream.write(input) }
            }
            if (!process.waitFor(SECRET_TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IllegalStateException("Secret Service did not respond")
            }
            writer.get(2, TimeUnit.SECONDS)
            val stdout = output.get(2, TimeUnit.SECONDS)
            val stderr = errors.get(2, TimeUnit.SECONDS)
            check(stdout.size <= MAX_OUTPUT_BYTES && stderr.size <= MAX_OUTPUT_BYTES) { "Oversize keyring response" }
            return SecretToolResponse(process.exitValue(), stdout, stderr.isNotEmpty())
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private const val SECRET_TOOL_TIMEOUT_SECONDS = 20L
    private const val MAX_OUTPUT_BYTES = 2 * 1024 * 1024
}

/** Binary encoding avoids accidentally writing session secrets to a text log or configuration file. */
internal object StoredDesktopSessionCodec {
    private const val VERSION = 4
    private const val LEGACY_VERSION = 1

    fun encode(session: StoredDesktopSession): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(VERSION)
                output.writeString(session.authState.cookie)
                output.writeString(session.authState.visitorData)
                output.writeString(session.authState.dataSyncId)
                // v3 used to store a separately-normalized delegated id here. Keep the slot for
                // backwards compatibility; canonical ArchiveTune keeps the combined DataSyncId.
                output.writeString(null)
                output.writeString(session.authState.poToken)
                output.writeString(session.authState.poTokenGvs)
                output.writeString(session.authState.poTokenPlayer)
                output.writeBoolean(session.authState.webClientPoTokenEnabled)
                // v4 preserves binding metadata for generated playback proof. Without these fields,
                // a video-bound token restored after restart could be mistaken for a generic token.
                output.writeString(session.authState.poTokenGvsSession)
                output.writeString(session.authState.poTokenGvsVideoId)
                output.writeString(session.authState.poTokenPlayerVideoId)
                output.writeString(session.authState.poTokenSubs)
                output.writeString(session.authState.poTokenSubsVideoId)
                output.writeString(session.account.name)
                output.writeString(session.account.email)
                output.writeString(session.account.channelHandle)
                output.writeString(session.account.avatarUrl)
                output.writeLong(session.expiresAtEpochMillis ?: NO_EXPIRY)
            }
            bytes.toByteArray()
        }

    fun decode(value: ByteArray): StoredDesktopSession =
        DataInputStream(ByteArrayInputStream(value)).use { input ->
            val version = input.readInt()
            require(version in LEGACY_VERSION..VERSION) { "Unsupported desktop session format" }
            val cookie = input.readString()
            val visitorData = input.readString()
            val storedDataSyncId = input.readString()
            val legacyDelegatedSessionId = if (version >= 3) input.readString() else null
            val dataSyncId =
                if (legacyDelegatedSessionId != null && storedDataSyncId?.contains("||") != true) {
                    "$legacyDelegatedSessionId||${storedDataSyncId.orEmpty()}"
                } else {
                    storedDataSyncId
                }
            val poToken = input.readString()
            val poTokenGvs = input.readString()
            val poTokenPlayer = input.readString()
            val webClientPoTokenEnabled = input.readBoolean()
            val poTokenGvsSession = if (version >= 4) input.readString() else null
            val poTokenGvsVideoId = if (version >= 4) input.readString() else null
            val poTokenPlayerVideoId = if (version >= 4) input.readString() else null
            val poTokenSubs = if (version >= 4) input.readString() else null
            val poTokenSubsVideoId = if (version >= 4) input.readString() else null
            val authState =
                moe.rukamori.archivetune.innertube.PlaybackAuthState(
                    cookie = cookie,
                    visitorData = visitorData,
                    dataSyncId = dataSyncId,
                    poToken = poToken,
                    poTokenGvs = poTokenGvs,
                    poTokenGvsSession = poTokenGvsSession,
                    poTokenGvsVideoId = poTokenGvsVideoId,
                    poTokenPlayer = poTokenPlayer,
                    poTokenPlayerVideoId = poTokenPlayerVideoId,
                    poTokenSubs = poTokenSubs,
                    poTokenSubsVideoId = poTokenSubsVideoId,
                    webClientPoTokenEnabled = webClientPoTokenEnabled,
                ).normalized()
            require(authState.hasLoginCookie) { "Stored session has no YouTube login cookie" }
            val name = input.readString().orEmpty()
            val email = input.readString()
            val channelHandle = input.readString()
            val avatarUrl = if (version >= 2) input.readString() else null
            StoredDesktopSession(
                authState = authState,
                account = DesktopAccount(
                    name = name,
                    email = email,
                    channelHandle = channelHandle,
                    avatarUrl = avatarUrl?.takeIf { it.startsWith("https://", ignoreCase = true) },
                ),
                expiresAtEpochMillis = input.readLong().takeUnless { it == NO_EXPIRY },
            )
        }

    private fun DataOutputStream.writeString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }

    private fun DataInputStream.readString(): String? = if (readBoolean()) readUTF() else null

    private const val NO_EXPIRY = Long.MIN_VALUE
}

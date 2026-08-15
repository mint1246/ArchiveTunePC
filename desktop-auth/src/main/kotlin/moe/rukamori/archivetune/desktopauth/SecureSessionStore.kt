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

/** Binary encoding avoids accidentally writing session secrets to a text log or configuration file. */
internal object StoredDesktopSessionCodec {
    private const val VERSION = 2
    private const val LEGACY_VERSION = 1

    fun encode(session: StoredDesktopSession): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(VERSION)
                output.writeString(session.authState.cookie)
                output.writeString(session.authState.visitorData)
                output.writeString(session.authState.dataSyncId)
                output.writeString(session.authState.poToken)
                output.writeString(session.authState.poTokenGvs)
                output.writeString(session.authState.poTokenPlayer)
                output.writeBoolean(session.authState.webClientPoTokenEnabled)
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
            require(version == VERSION || version == LEGACY_VERSION) { "Unsupported desktop session format" }
            val authState =
                moe.rukamori.archivetune.innertube.PlaybackAuthState(
                    cookie = input.readString(),
                    visitorData = input.readString(),
                    dataSyncId = input.readString(),
                    poToken = input.readString(),
                    poTokenGvs = input.readString(),
                    poTokenPlayer = input.readString(),
                    webClientPoTokenEnabled = input.readBoolean(),
                ).normalized()
            require(authState.hasLoginCookie) { "Stored session has no YouTube login cookie" }
            val name = input.readString().orEmpty()
            val email = input.readString()
            val channelHandle = input.readString()
            val avatarUrl = if (version >= VERSION) input.readString() else null
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

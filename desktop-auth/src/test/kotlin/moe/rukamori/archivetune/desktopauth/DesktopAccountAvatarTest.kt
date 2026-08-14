package moe.rukamori.archivetune.desktopauth

import moe.rukamori.archivetune.innertube.PlaybackAuthState
import moe.rukamori.archivetune.innertube.models.AccountInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class DesktopAccountAvatarTest {
    @Test
    fun `backend account mapping retains only https avatar URLs`() {
        val mapped =
            AccountInfo("Example", "example@test", "@example", "https://yt3.googleusercontent.com/avatar?token=private")
                .toDesktopAccount()
        val rejected = AccountInfo("Example", null, null, "http://untrusted.test/avatar").toDesktopAccount()

        assertEquals("https://yt3.googleusercontent.com/avatar?token=private", mapped.avatarUrl)
        assertNull(rejected.avatarUrl)
    }

    @Test
    fun `session codec preserves avatar and migrates version one without it`() {
        val current =
            StoredDesktopSessionCodec.decode(
                StoredDesktopSessionCodec.encode(
                    StoredDesktopSession(
                        authState = PlaybackAuthState(cookie = "SAPISID=secret", dataSyncId = "sync"),
                        account = DesktopAccount("Example", null, null, "https://yt3.googleusercontent.com/avatar"),
                        expiresAtEpochMillis = null,
                    ),
                ),
            )
        val legacy = StoredDesktopSessionCodec.decode(versionOnePayload())

        assertEquals("https://yt3.googleusercontent.com/avatar", current.account.avatarUrl)
        assertNull(legacy.account.avatarUrl)
        assertEquals("Example", legacy.account.name)
    }

    @Test
    fun `account diagnostics and model rendering do not disclose avatar URL`() {
        val account = DesktopAccount("Example", null, null, "https://yt3.googleusercontent.com/avatar?token=private")

        assertTrue(!account.toString().contains("token=private"))
        // Avatar URLs are intentionally absent from DesktopSessionDiagnostic altogether.
        assertTrue(!DesktopSessionDiagnostic.signedOut().toString().contains("token=private"))
    }

    private fun versionOnePayload(): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(1)
                output.writeLegacyString("SAPISID=secret")
                output.writeLegacyString(null)
                output.writeLegacyString("sync")
                output.writeLegacyString(null)
                output.writeLegacyString(null)
                output.writeLegacyString(null)
                output.writeBoolean(false)
                output.writeLegacyString("Example")
                output.writeLegacyString(null)
                output.writeLegacyString(null)
                output.writeLong(Long.MIN_VALUE)
            }
            bytes.toByteArray()
        }

    private fun DataOutputStream.writeLegacyString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }
}

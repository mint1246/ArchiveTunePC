package moe.rukamori.archivetune.desktop.platform

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import moe.rukamori.archivetune.desktop.settings.DesktopSettingsStore
import moe.rukamori.archivetune.desktopauth.SecureSessionStore
import moe.rukamori.archivetune.lyrics.JvmLyricsTranslationCache

class DesktopLyricsTranslationServiceTest {
    @Test
    fun `credentials are trimmed cleared and removed without UI`() {
        val directory = Files.createTempDirectory("lyrics-translation-sdk-test")
        val credentials = MemoryCredentials()
        DesktopSettingsStore(directory.resolve("settings.json")).use { settings ->
            val service = DesktopLyricsTranslationService(
                settings = settings,
                credentialStore = credentials,
                cache = JvmLyricsTranslationCache(directory.resolve("translations.json")),
            )
            assertFalse(service.hasApiKey())
            service.setApiKey("  example-key  ")
            assertContentEquals("example-key".toByteArray(), credentials.value)
            assertTrue(credentials.lastWrittenBuffer!!.all { it == 0.toByte() })
            assertTrue(service.hasApiKey())
            assertTrue(credentials.lastReadBuffer!!.all { it == 0.toByte() })
            service.setApiKey("   ")
            assertFalse(service.hasApiKey())
        }
    }

    private class MemoryCredentials : SecureSessionStore {
        var value: ByteArray? = null
        var lastWrittenBuffer: ByteArray? = null
        var lastReadBuffer: ByteArray? = null

        override fun read(): ByteArray? = value?.copyOf()?.also { lastReadBuffer = it }

        override fun write(value: ByteArray) {
            lastWrittenBuffer = value
            this.value = value.copyOf()
        }

        override fun delete() {
            value = null
        }
    }
}

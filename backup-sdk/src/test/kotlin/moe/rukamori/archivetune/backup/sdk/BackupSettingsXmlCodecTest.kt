/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.backup.sdk

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupSettingsXmlCodecTest {
    @Test
    fun `write and read round-trips all preference types and special characters`() {
        val prefs =
            linkedMapOf<String, BackupPreferenceValue>(
                "darkMode" to BackupPreferenceValue.BooleanValue(true),
                "maxItems" to BackupPreferenceValue.IntValue(42),
                "lastSync" to BackupPreferenceValue.LongValue(9_876_543_210L),
                "playbackSpeed" to BackupPreferenceValue.FloatValue(1.25f),
                "note" to BackupPreferenceValue.StringValue("a & b <c> \"d\" 'e'\nline2"),
                "tags" to BackupPreferenceValue.StringSetValue(linkedSetOf("rock & roll", "<indie>", "jazz")),
            )

        val out = ByteArrayOutputStream()
        BackupSettingsXmlCodec.write(out, prefs)

        val xml = out.toString(Charsets.UTF_8)
        assertTrue(xml.contains("<ArchiveTuneBackup>"))
        assertTrue(xml.contains("<Settings>"))
        assertTrue(xml.contains("</Settings></ArchiveTuneBackup>"))

        val snapshot = BackupSettingsXmlCodec.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(mapOf("darkMode" to true), snapshot.booleans)
        assertEquals(mapOf("maxItems" to 42), snapshot.ints)
        assertEquals(mapOf("lastSync" to 9_876_543_210L), snapshot.longs)
        assertEquals(mapOf("playbackSpeed" to 1.25f), snapshot.floats)
        assertEquals(mapOf("note" to "a & b <c> \"d\" 'e'\nline2"), snapshot.strings)
        assertEquals(mapOf("tags" to setOf("rock & roll", "<indie>", "jazz")), snapshot.stringSets)
    }

    @Test
    fun `write excludes non-portable and explicit excluded keys and read filters excluded keys`() {
        val prefs =
            linkedMapOf<String, BackupPreferenceValue>(
                "scheduledBackupEnabled" to BackupPreferenceValue.BooleanValue(true),
                "scheduledBackupDirectoryUri" to BackupPreferenceValue.StringValue("content://tree/1"),
                "scheduledBackupDirectoryName" to BackupPreferenceValue.StringValue("Backups"),
                "innerTubeCookie" to BackupPreferenceValue.StringValue("secret-cookie"),
                "theme" to BackupPreferenceValue.StringValue("amoled"),
            )

        val out = ByteArrayOutputStream()
        BackupSettingsXmlCodec.write(
            outputStream = out,
            preferences = prefs,
            excludedKeyNames = BackupArchiveConstants.ACCOUNT_PREFERENCE_KEYS,
        )

        val snapshot = BackupSettingsXmlCodec.read(ByteArrayInputStream(out.toByteArray()))
        assertFalse("scheduledBackupEnabled" in snapshot.booleans)
        assertFalse("scheduledBackupDirectoryUri" in snapshot.strings)
        assertFalse("scheduledBackupDirectoryName" in snapshot.strings)
        assertFalse("innerTubeCookie" in snapshot.strings)
        assertEquals("amoled", snapshot.strings["theme"])

        val filteredOnRead =
            BackupSettingsXmlCodec.read(
                inputStream = ByteArrayInputStream(out.toByteArray()),
                excludedKeyNames = setOf("theme"),
            )
        assertTrue(filteredOnRead.isEmpty())
    }

    @Test
    fun `read returns empty snapshot for blank input`() {
        val snapshot = BackupSettingsXmlCodec.read(ByteArrayInputStream("   \n ".toByteArray()))
        assertTrue(snapshot.isEmpty())
    }

    @Test
    fun `write and read round-trips supplementary Unicode codepoints safely`() {
        val prefs =
            linkedMapOf<String, BackupPreferenceValue>(
                "emojiKey🎵" to BackupPreferenceValue.StringValue("Now playing 🎧🎶"),
                "emojiTags" to BackupPreferenceValue.StringSetValue(linkedSetOf("🎸", "🎹")),
            )

        val out = ByteArrayOutputStream()
        BackupSettingsXmlCodec.write(out, prefs)

        val snapshot = BackupSettingsXmlCodec.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals("Now playing 🎧🎶", snapshot.strings["emojiKey🎵"])
        assertEquals(setOf("🎸", "🎹"), snapshot.stringSets["emojiTags"])
    }

    @Test
    fun `write rejects illegal XML 1_0 control characters, non-characters, and unpaired surrogates`() {
        val invalidInputs =
            listOf(
                "nul\u0000byte",
                "vertical\u000Btab",
                "unit\u001Fsep",
                "nonchar\uFFFE",
                "nonchar\uFFFF",
                "loneHigh\uD83C",
                "loneLow\uDFB5",
            )

        invalidInputs.forEach { bad ->
            assertFailsWith<IllegalArgumentException> {
                BackupSettingsXmlCodec.write(
                    ByteArrayOutputStream(),
                    mapOf("k" to BackupPreferenceValue.StringValue(bad)),
                )
            }
            assertFailsWith<IllegalArgumentException> {
                BackupSettingsXmlCodec.write(
                    ByteArrayOutputStream(),
                    mapOf("k" to BackupPreferenceValue.StringSetValue(setOf(bad))),
                )
            }
            assertFailsWith<IllegalArgumentException> {
                BackupSettingsXmlCodec.write(
                    ByteArrayOutputStream(),
                    mapOf(bad to BackupPreferenceValue.BooleanValue(true)),
                )
            }
        }
    }
}

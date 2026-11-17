/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.backup.sdk

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.BufferedWriter
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory

object BackupSettingsXmlCodec {
    fun write(
        outputStream: OutputStream,
        preferences: Map<String, BackupPreferenceValue>,
        excludedKeyNames: Set<String> = emptySet(),
    ) {
        val writer = BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8))
        writer.write("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>")
        writer.write("<ArchiveTuneBackup>")
        writer.write("<Settings>")

        preferences.forEach { (keyName, value) ->
            if (
                keyName !in excludedKeyNames &&
                keyName !in BackupArchiveConstants.NON_PORTABLE_PREFERENCE_KEYS
            ) {
                writer.writePreference(keyName, value)
            }
        }

        writer.write("</Settings>")
        writer.write("</ArchiveTuneBackup>")
        writer.flush()
    }

    fun read(
        inputStream: InputStream,
        excludedKeyNames: Set<String> = emptySet(),
    ): BackupSettingsSnapshot {
        val content = inputStream.readBytes().toString(Charsets.UTF_8)
        if (content.isBlank()) return BackupSettingsSnapshot()

        val booleans = LinkedHashMap<String, Boolean>()
        val ints = LinkedHashMap<String, Int>()
        val longs = LinkedHashMap<String, Long>()
        val floats = LinkedHashMap<String, Float>()
        val strings = LinkedHashMap<String, String>()
        val stringSets = LinkedHashMap<String, Set<String>>()

        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }

        val handler =
            object : DefaultHandler() {
                private var currentStringSetKey: String? = null
                private var currentStringSetValues: LinkedHashSet<String>? = null
                private var inItem = false
                private val itemText = StringBuilder()

                override fun startElement(
                    uri: String?,
                    localName: String?,
                    qName: String?,
                    attributes: Attributes?,
                ) {
                    val tagName = qName?.takeIf { it.isNotEmpty() } ?: localName.orEmpty()
                    if (tagName == "item" && currentStringSetValues != null) {
                        inItem = true
                        itemText.setLength(0)
                        return
                    }

                    val keyName = attributes?.getValue("name") ?: return
                    if (keyName in excludedKeyNames) return

                    when (tagName) {
                        "boolean" -> {
                            attributes.getValue("value")?.toBoolean()?.let { booleans[keyName] = it }
                        }

                        "int" -> {
                            attributes.getValue("value")?.toIntOrNull()?.let { ints[keyName] = it }
                        }

                        "long" -> {
                            attributes.getValue("value")?.toLongOrNull()?.let { longs[keyName] = it }
                        }

                        "float" -> {
                            attributes.getValue("value")?.toFloatOrNull()?.let { floats[keyName] = it }
                        }

                        "string" -> {
                            attributes.getValue("value")?.let { strings[keyName] = it }
                        }

                        "string-set" -> {
                            currentStringSetKey = keyName
                            currentStringSetValues = LinkedHashSet()
                        }
                    }
                }

                override fun characters(
                    ch: CharArray,
                    start: Int,
                    length: Int,
                ) {
                    if (inItem) {
                        itemText.append(ch, start, length)
                    }
                }

                override fun endElement(
                    uri: String?,
                    localName: String?,
                    qName: String?,
                ) {
                    val tagName = qName?.takeIf { it.isNotEmpty() } ?: localName.orEmpty()
                    when (tagName) {
                        "item" -> {
                            if (inItem) {
                                currentStringSetValues?.add(itemText.toString())
                                inItem = false
                                itemText.setLength(0)
                            }
                        }

                        "string-set" -> {
                            val key = currentStringSetKey
                            val values = currentStringSetValues
                            if (key != null && values != null) {
                                stringSets[key] = values
                            }
                            currentStringSetKey = null
                            currentStringSetValues = null
                            inItem = false
                            itemText.setLength(0)
                        }
                    }
                }
            }

        factory.newSAXParser().parse(InputSource(StringReader(content)), handler)

        return BackupSettingsSnapshot(
            booleans = booleans,
            ints = ints,
            longs = longs,
            floats = floats,
            strings = strings,
            stringSets = stringSets,
        )
    }

    private fun BufferedWriter.writePreference(
        keyName: String,
        value: BackupPreferenceValue,
    ) {
        val tagName =
            when (value) {
                is BackupPreferenceValue.BooleanValue -> "boolean"
                is BackupPreferenceValue.IntValue -> "int"
                is BackupPreferenceValue.LongValue -> "long"
                is BackupPreferenceValue.FloatValue -> "float"
                is BackupPreferenceValue.StringValue -> "string"
                is BackupPreferenceValue.StringSetValue -> "string-set"
            }
        write("<")
        write(tagName)
        write(" name=\"")
        writeEscapedAttribute(keyName)
        write("\"")

        if (value is BackupPreferenceValue.StringSetValue) {
            write(">")
            value.value.forEach { item ->
                write("<item>")
                writeEscapedText(item)
                write("</item>")
            }
            write("</")
            write(tagName)
            write(">")
        } else {
            val rawValue =
                when (value) {
                    is BackupPreferenceValue.BooleanValue -> value.value.toString()
                    is BackupPreferenceValue.IntValue -> value.value.toString()
                    is BackupPreferenceValue.LongValue -> value.value.toString()
                    is BackupPreferenceValue.FloatValue -> value.value.toString()
                    is BackupPreferenceValue.StringValue -> value.value
                    is BackupPreferenceValue.StringSetValue -> ""
                }
            write(" value=\"")
            writeEscapedAttribute(rawValue)
            write("\" />")
        }
    }

    private fun BufferedWriter.writeEscapedAttribute(text: String) {
        forEachValidXmlCodePoint(text) { codePoint ->
            when (codePoint) {
                '&'.code -> write("&amp;")
                '<'.code -> write("&lt;")
                '>'.code -> write("&gt;")
                '"'.code -> write("&quot;")
                '\''.code -> write("&apos;")
                '\n'.code -> write("&#10;")
                '\r'.code -> write("&#13;")
                '\t'.code -> write("&#9;")
                else -> writeCodePoint(codePoint)
            }
        }
    }

    private fun BufferedWriter.writeEscapedText(text: String) {
        forEachValidXmlCodePoint(text) { codePoint ->
            when (codePoint) {
                '&'.code -> write("&amp;")
                '<'.code -> write("&lt;")
                '>'.code -> write("&gt;")
                else -> writeCodePoint(codePoint)
            }
        }
    }

    private inline fun forEachValidXmlCodePoint(
        text: String,
        action: (Int) -> Unit,
    ) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val codePoint: Int
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length || !Character.isLowSurrogate(text[i + 1])) {
                    throw IllegalArgumentException(
                        "Unpaired high surrogate at index $i (U+${c.code.toString(16).uppercase()}) is not valid in XML 1.0",
                    )
                }
                codePoint = Character.toCodePoint(c, text[i + 1])
                i += 2
            } else if (Character.isLowSurrogate(c)) {
                throw IllegalArgumentException(
                    "Unpaired low surrogate at index $i (U+${c.code.toString(16).uppercase()}) is not valid in XML 1.0",
                )
            } else {
                codePoint = c.code
                i += 1
            }

            if (!isValidXml10CodePoint(codePoint)) {
                throw IllegalArgumentException(
                    "Character U+${codePoint.toString(16).uppercase()} is not valid in XML 1.0",
                )
            }
            action(codePoint)
        }
    }

    private fun isValidXml10CodePoint(codePoint: Int): Boolean =
        codePoint == 0x9 ||
            codePoint == 0xA ||
            codePoint == 0xD ||
            codePoint in 0x20..0xD7FF ||
            codePoint in 0xE000..0xFFFD ||
            codePoint in 0x10000..0x10FFFF

    private fun BufferedWriter.writeCodePoint(codePoint: Int) {
        if (codePoint <= 0xFFFF) {
            write(codePoint)
        } else {
            write(Character.toChars(codePoint))
        }
    }
}

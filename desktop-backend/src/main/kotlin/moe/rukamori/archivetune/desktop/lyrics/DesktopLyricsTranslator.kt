package moe.rukamori.archivetune.desktop.lyrics

import moe.rukamori.archivetune.lyrics.LyricsDocument

/** Translates source lines while preserving their order and correspondence to the lyrics document. */
fun interface DesktopLyricsTranslator {
    suspend fun translate(
        itemId: String,
        document: LyricsDocument,
        sourceLines: List<String>,
        targetLanguage: String,
    ): List<String>
}

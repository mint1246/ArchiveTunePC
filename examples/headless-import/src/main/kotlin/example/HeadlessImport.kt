package example

import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.playlistimport.ImportResolver
import moe.rukamori.archivetune.playlistimport.ImportSongDescription

data class Song(val id: String, val title: String, val artist: String)

fun main() = runBlocking {
    val source = Song("source", "Halo", "Beyoncé")
    val local = Song("local", "Halo", "Beyonce")
    val resolver = ImportResolver<Song>(
        describe = { ImportSongDescription(it.title, listOf(it.artist)) },
        search = { emptyList() },
    )
    val results = resolver.resolve(listOf(source, source), listOf(local), localFirst = true)
    check(results.map { it.match?.id } == listOf("local", "local"))
    println("Resolved ${results.size} ordered occurrences against the local library")
}

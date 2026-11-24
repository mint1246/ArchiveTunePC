package moe.rukamori.archivetune.shared

/** A named shelf from the live catalog, preserving the backend's ordering. */
data class MusicShelf(
    val title: String,
    val tracks: List<MusicTrack>,
    val items: List<MusicCatalogItem> = tracks.map { MusicCatalogItem.Track(it) },
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val browseId: String? = null,
    val browseParams: String? = null,
    val featuredCards: List<MusicFeaturedCard> = emptyList(),
)

sealed interface MusicCatalogItem {
    data class Track(val track: MusicTrack) : MusicCatalogItem
    data class Browse(
        val id: String,
        val title: String,
        val subtitle: String? = null,
        val artworkUrl: String? = null,
        val kind: MusicBrowseKind = MusicBrowseKind.OTHER,
        val params: String? = null,
    ) : MusicCatalogItem
}

enum class MusicBrowseKind { PLAYLIST, ALBUM, ARTIST, OTHER }

data class MusicHomeChip(val title: String, val browseId: String, val params: String? = null)

data class MusicBrowsePage(
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val tracks: List<MusicTrack> = emptyList(),
    val shelves: List<MusicShelf> = emptyList(),
    val continuation: String? = null,
    val metadata: String? = null,
    val description: String? = null,
)

data class MusicHomeState(
    val chips: List<MusicHomeChip> = emptyList(),
    val selectedChip: MusicHomeChip? = null,
    val shelves: List<MusicShelf> = emptyList(),
    val continuation: String? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
)

data class MusicBrowseState(
    val depth: Int = 0,
    val item: MusicCatalogItem.Browse? = null,
    val page: MusicBrowsePage? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

data class MusicFeaturedCard(val item: MusicCatalogItem.Browse, val tracks: List<MusicTrack>)

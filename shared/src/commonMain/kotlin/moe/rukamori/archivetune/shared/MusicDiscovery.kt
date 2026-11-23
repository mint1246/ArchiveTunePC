package moe.rukamori.archivetune.shared

data class MusicMood(
    val title: String,
    val stripeColor: Long,
    val endpoint: MusicCatalogItem.Browse,
    val artworkUrl: String? = null,
)
data class MusicDiscovery(val moods: List<MusicMood> = emptyList(), val shelves: List<MusicShelf> = emptyList())
data class MusicDiscoveryState(
    val data: MusicDiscovery = MusicDiscovery(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
)

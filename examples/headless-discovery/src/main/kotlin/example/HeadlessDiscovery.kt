package example

import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.sdk.discovery.DiscoveryContentCache
import moe.rukamori.archivetune.sdk.discovery.DiscoveryKeys
import moe.rukamori.archivetune.sdk.discovery.QuickPickSeedPolicy
import moe.rukamori.archivetune.sdk.discovery.RelatedSongsResolver
import java.util.concurrent.atomic.AtomicReference

private data class DemoSong(
    val id: String,
    val title: String,
    val primaryArtistKey: String,
)

fun main() =
    runBlocking {
        val currentAccount = AtomicReference("account-a|US")
        val cache = DiscoveryContentCache<String>(accountScopeProvider = { currentAccount.get() })
        try {
            val key = DiscoveryKeys.browse("FEmusic_home")
            val firstAccountPage = cache.getOrLoad(key) { "home-for-account-a" }
            currentAccount.set("account-b|JP")
            val secondAccountPage = cache.getOrLoad(key) { "home-for-account-b" }
            check(firstAccountPage == "home-for-account-a")
            check(secondAccountPage == "home-for-account-b")
            check(cache.size == 2)

            val localSeeds =
                QuickPickSeedPolicy.selectLocalSeeds(
                    candidates =
                        listOf(
                            DemoSong("vid00000001", "First", "artist-1"),
                            DemoSong("vid00000002", "Same Artist", "artist-1"),
                        ),
                    limit = 2,
                    primaryArtistKeyOf = DemoSong::primaryArtistKey,
                )
            val remoteSeeds =
                QuickPickSeedPolicy.rankRemoteSeeds(
                    songs =
                        listOf(
                            DemoSong("vid00000002", "Same Artist", "artist-1"),
                            DemoSong("vid00000003", "Second", "artist-2"),
                            DemoSong("vid00000004", "Third", "artist-2"),
                        ),
                    limit = 4,
                    idOf = DemoSong::id,
                    artistKeysOf = { song -> listOf(song.primaryArtistKey) },
                )
            val mergedSeeds =
                QuickPickSeedPolicy.mergeSeeds(
                    localSeeds = localSeeds,
                    remoteSeeds = remoteSeeds,
                    limit = 2,
                    idOf = DemoSong::id,
                    primaryArtistKeyOf = DemoSong::primaryArtistKey,
                )
            check(mergedSeeds.map(DemoSong::id) == listOf("vid00000001", "vid00000003"))

            val resolved =
                RelatedSongsResolver.resolve(
                    seedSongId = "vid00000001",
                    idOf = DemoSong::id,
                    titleOf = DemoSong::title,
                    loadPrimary = { RelatedSongsResolver.PrimaryOutcome(emptyList()) },
                    loadSearchFallback = {
                        Result.success(
                            listOf(
                                DemoSong("vid00000001", "Seed Self", "artist-1"),
                                DemoSong("vid00000005", "Search Fallback", "artist-3"),
                            ),
                        )
                    },
                    loadLocalFallback = { emptyList() },
                )
            check(resolved.getOrNull()?.map(DemoSong::id) == listOf("vid00000005"))
            println("Headless discovery checks passed")
        } finally {
            cache.close()
        }
    }

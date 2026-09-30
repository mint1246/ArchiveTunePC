/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.platform

import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.innertube.cache.DesktopResourceCoordinator
import moe.rukamori.archivetune.innertube.models.EpisodeItem
import moe.rukamori.archivetune.innertube.models.PodcastItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.innertube.models.YTItem
import moe.rukamori.archivetune.innertube.pages.PodcastPage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPodcastRepositoryTest {
    @Test
    fun savedPodcastsAndEpisodesStayInsideTheirAccountScope() = runBlocking {
        val cacheDirectory = Files.createTempDirectory("archivetune-podcast-test")
        val coordinator = DesktopResourceCoordinator(cacheDirectory)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var accountScope = "account-one"
        val backend = TestPodcastBackend()
        val repository = DesktopPodcastRepository(
            backend = backend,
            coordinator = coordinator,
            scope = scope,
            accountScopeProvider = { accountScope },
        )
        val podcast = PodcastItem("MPSPshow", "playlist-show", "Show", null, null)
        val episode = EpisodeItem("episode-one", null, "Episode", null, null, null, null, 120, "", WatchEndpoint(videoId = "episode-one"))

        try {
            repository.refreshLibrary()
            assertTrue(repository.setPodcastSaved(podcast, true).isSuccess)
            assertTrue(repository.setEpisodeSaved(episode, true).isSuccess)
            assertTrue(repository.isPodcastSaved(podcast.browseId))
            assertTrue(repository.isEpisodeSaved(episode.id))

            accountScope = "account-two"
            repository.onAccountScopeChanged()
            assertFalse(repository.isPodcastSaved(podcast.browseId))
            assertFalse(repository.isEpisodeSaved(episode.id))

            accountScope = "account-one"
            repository.onAccountScopeChanged()
            assertTrue(repository.isPodcastSaved(podcast.browseId))
            assertTrue(repository.isEpisodeSaved(episode.id))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failedRemoteSaveDoesNotFabricateLibraryMembership() = runBlocking {
        val cacheDirectory = Files.createTempDirectory("archivetune-podcast-failure-test")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val backend = TestPodcastBackend(failPodcastSave = true, failEpisodeSave = true)
        val repository = DesktopPodcastRepository(
            backend = backend,
            coordinator = DesktopResourceCoordinator(cacheDirectory),
            scope = scope,
            accountScopeProvider = { "account-one" },
        )
        val podcast = PodcastItem("MPSPshow", "playlist-show", "Show", null, null)
        val episode = EpisodeItem("episode-one", null, "Episode", null, null, null, null, 120, "", WatchEndpoint(videoId = "episode-one"))

        try {
            repository.refreshLibrary()
            assertTrue(repository.setPodcastSaved(podcast, true).isFailure)
            assertTrue(repository.setEpisodeSaved(episode, true).isFailure)
            assertFalse(repository.isPodcastSaved(podcast.browseId))
            assertFalse(repository.isEpisodeSaved(episode.id))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun delayedEpisodeSaveFromPreviousAccountCannotMutateCurrentLibrary() = runBlocking {
        val cacheDirectory = Files.createTempDirectory("archivetune-podcast-account-race-test")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var accountScope = "account-one"
        val backend = TestPodcastBackend(episodeSaveGate = CompletableDeferred())
        val repository = DesktopPodcastRepository(
            backend = backend,
            coordinator = DesktopResourceCoordinator(cacheDirectory),
            scope = scope,
            accountScopeProvider = { accountScope },
        )
        val episode = EpisodeItem("episode-one", null, "Episode", null, null, null, null, 120, "", WatchEndpoint(videoId = "episode-one"))

        try {
            repository.refreshLibrary()
            val save = async { repository.setEpisodeSaved(episode, true) }
            backend.episodeSaveStarted.await()

            accountScope = "account-two"
            repository.onAccountScopeChanged()
            backend.episodeSaveGate!!.complete(Unit)
            assertTrue(save.await().isSuccess)
            assertFalse(repository.isEpisodeSaved(episode.id))

            accountScope = "account-one"
            repository.onAccountScopeChanged()
            assertFalse(repository.isEpisodeSaved(episode.id))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun refreshReconcilesRemotePodcastsAndEpisodesButKeepsExplicitLocalPodcasts() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val backend = TestPodcastBackend()
        val repository = DesktopPodcastRepository(
            backend = backend,
            coordinator = DesktopResourceCoordinator(Files.createTempDirectory("archivetune-podcast-remote-test")),
            scope = scope,
            accountScopeProvider = { "account-one" },
        )
        val remotePodcast = PodcastItem("MPSPremote", "playlist-remote", "Remote show", null, null)
        val localPodcast = PodcastItem("MPSPlocal", null, "Local show", null, null)
        val remoteEpisode = EpisodeItem("episode-remote", null, "Remote episode", null, null, null, null, 120, "", WatchEndpoint(videoId = "episode-remote"))

        try {
            repository.refreshLibrary()
            assertTrue(repository.setPodcastSaved(localPodcast, true).isSuccess)
            backend.remoteLibrary = DesktopRemotePodcastLibrary(listOf(remotePodcast), listOf(remoteEpisode))
            repository.refreshLibrary()
            assertTrue(repository.isPodcastSaved(remotePodcast.browseId))
            assertTrue(repository.isPodcastSaved(localPodcast.browseId))
            assertTrue(repository.isEpisodeSaved(remoteEpisode.id))

            backend.remoteLibrary = DesktopRemotePodcastLibrary(emptyList(), emptyList())
            repository.refreshLibrary()
            assertFalse(repository.isPodcastSaved(remotePodcast.browseId))
            assertTrue(repository.isPodcastSaved(localPodcast.browseId))
            assertFalse(repository.isEpisodeSaved(remoteEpisode.id))
        } finally {
            scope.cancel()
        }
    }

    private class TestPodcastBackend(
        private val failPodcastSave: Boolean = false,
        private val failEpisodeSave: Boolean = false,
        val episodeSaveGate: CompletableDeferred<Unit>? = null,
    ) : DesktopPodcastBackend {
        val episodeSaveStarted = CompletableDeferred<Unit>()
        var remoteLibrary = DesktopRemotePodcastLibrary(null, null)

        override suspend fun podcast(browseId: String): Result<PodcastPage> = Result.failure(UnsupportedOperationException())
        override suspend fun continuation(token: String): Result<PodcastPage.Continuation> = Result.failure(UnsupportedOperationException())
        override suspend fun search(query: String): Result<List<YTItem>> = Result.success(emptyList())
        override suspend fun savedLibrary(): Result<DesktopRemotePodcastLibrary> = Result.success(remoteLibrary)
        override suspend fun setPodcastSaved(playlistId: String, saved: Boolean): Result<Unit> =
            if (failPodcastSave) Result.failure(IllegalStateException("Remote save failed")) else Result.success(Unit)
        override suspend fun setEpisodeSaved(episodeId: String, saved: Boolean): Result<Unit> {
            episodeSaveStarted.complete(Unit)
            episodeSaveGate?.await()
            return if (failEpisodeSave) Result.failure(IllegalStateException("Remote save failed")) else Result.success(Unit)
        }
    }
}

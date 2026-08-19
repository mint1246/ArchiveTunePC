package moe.rukamori.archivetune.innertube.home

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.innertube.cache.DesktopResourceCoordinator
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopLibraryRepositoryTest {
    @Test
    fun `refresh failure preserves cached playlists and exposes error`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = repository(scope) { Result.failure(IllegalStateException("offline")) }
            repository.onAccountScopeChanged()
            repository.upsertCached(playlist("cached", "Cached playlist"))

            val result = repository.refresh()

            assertTrue(result.isFailure)
            assertEquals(listOf("cached"), repository.state.value.playlists.map { it.id })
            assertEquals("offline", repository.state.value.error)
            assertFalse(repository.state.value.isInitialLoading)
            assertFalse(repository.state.value.isRefreshing)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `refresh failure without cache exposes error instead of an empty success`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = repository(scope) { Result.failure(IllegalStateException("network down")) }

            val result = repository.refresh()

            assertTrue(result.isFailure)
            assertTrue(repository.state.value.playlists.isEmpty())
            assertEquals("network down", repository.state.value.error)
            assertFalse(repository.state.value.isInitialLoading)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `retry after failed refresh replaces error with fresh playlists`() = runBlocking {
        val calls = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = repository(scope) {
                if (calls.incrementAndGet() == 1) Result.failure(IllegalStateException("offline"))
                else Result.success(listOf(playlist("fresh", "Fresh playlist")))
            }

            repository.refresh()
            assertEquals("offline", repository.state.value.error)

            val retry = repository.refresh()

            assertTrue(retry.isSuccess)
            assertEquals(2, calls.get())
            assertEquals(listOf("fresh"), repository.state.value.playlists.map { it.id })
            assertEquals(null, repository.state.value.error)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `late refresh from previous account cannot overwrite newer library state`() = runBlocking {
        var accountScope = "account-a"
        val oldStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = repository(scope, accountScopeProvider = { accountScope }) {
                if (accountScope == "account-a") {
                    oldStarted.complete(Unit)
                    releaseOld.await()
                    Result.success(listOf(playlist("old", "Old account")))
                } else {
                    Result.success(listOf(playlist("new", "New account")))
                }
            }

            val oldRefresh = async { repository.refresh() }
            withTimeout(2_000) { oldStarted.await() }

            accountScope = "account-b"
            repository.onAccountScopeChanged()
            repository.refresh().getOrThrow()
            assertEquals("account-b", repository.state.value.accountScope)
            assertEquals(listOf("new"), repository.state.value.playlists.map { it.id })

            releaseOld.complete(Unit)
            oldRefresh.await()

            assertEquals("account-b", repository.state.value.accountScope)
            assertEquals(listOf("new"), repository.state.value.playlists.map { it.id })
        } finally {
            releaseOld.complete(Unit)
            scope.cancel()
        }
    }

    private fun repository(
        scope: CoroutineScope,
        accountScopeProvider: () -> String = { "account-a" },
        loader: suspend () -> Result<List<PlaylistItem>>,
    ) = DesktopLibraryRepository.forTesting(
        coordinator = DesktopResourceCoordinator(Files.createTempDirectory("archivetune-library-test")),
        scope = scope,
        accountScopeProvider = accountScopeProvider,
        playlistLoader = loader,
    )

    private fun playlist(id: String, title: String) = PlaylistItem(
        id = id,
        title = title,
        author = null,
        songCountText = null,
        thumbnail = null,
        playEndpoint = null,
        shuffleEndpoint = null,
        radioEndpoint = null,
        isEditable = true,
    )
}

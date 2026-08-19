package moe.rukamori.archivetune.innertube.search

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.innertube.cache.DesktopResourceCoordinator
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.pages.SearchSummary
import moe.rukamori.archivetune.innertube.pages.SearchSummaryPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopSearchRepositoryTest {
    @Test
    fun `normalized repeated query is served from memory`() = runBlocking {
        val calls = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = repository(scope) { query ->
                calls.incrementAndGet()
                Result.success(page(query))
            }

            repository.activate("  Radiohead  ")
            awaitQuery(repository, "radiohead")
            repository.activate("radiohead")
            awaitQuery(repository, "radiohead")

            assertEquals(1, calls.get())
            assertEquals("radiohead", repository.state.value.normalizedQuery)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `late older response cannot replace newer query`() = runBlocking {
        val oldResponse = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = repository(scope) { query ->
                if (query == "old") withContext(NonCancellable) { oldResponse.await() }
                Result.success(page(query))
            }

            repository.activate("old")
            delay(30)
            repository.activate("new")
            awaitQuery(repository, "new")
            oldResponse.complete(Unit)
            delay(80)

            assertEquals("new", repository.state.value.normalizedQuery)
            assertEquals("new", repository.state.value.items.single().title)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `failed search can retry same query and replace error with results`() = runBlocking {
        val calls = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = repository(scope) { query ->
                if (calls.incrementAndGet() == 1) {
                    Result.failure(IllegalStateException("offline"))
                } else {
                    Result.success(page(query))
                }
            }

            repository.activate("Radiohead")
            awaitError(repository, "offline")
            assertEquals(1, calls.get())

            repository.retry()
            awaitQuery(repository, "radiohead")

            assertEquals(2, calls.get())
            assertEquals(null, repository.state.value.error)
            assertEquals("Radiohead", repository.state.value.items.single().title)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `retry ignores blank current query`() = runBlocking {
        val calls = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = repository(scope) { query ->
                calls.incrementAndGet()
                Result.success(page(query))
            }

            repository.retry()
            delay(50)

            assertEquals(0, calls.get())
            assertEquals("", repository.state.value.query)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `late retry cannot replace a newer query`() = runBlocking {
        val retryStarted = CompletableDeferred<Unit>()
        val releaseRetry = CompletableDeferred<Unit>()
        val oldCalls = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = repository(scope) { query ->
                if (query == "old") {
                    if (oldCalls.incrementAndGet() == 1) {
                        Result.failure(IllegalStateException("offline"))
                    } else {
                        withContext(NonCancellable) {
                            retryStarted.complete(Unit)
                            releaseRetry.await()
                        }
                        Result.success(page(query))
                    }
                } else {
                    Result.success(page(query))
                }
            }

            repository.activate("old")
            awaitError(repository, "offline")
            repository.retry()
            withTimeout(2_000) { retryStarted.await() }

            repository.activate("new")
            awaitQuery(repository, "new")
            releaseRetry.complete(Unit)
            delay(80)

            assertEquals("new", repository.state.value.normalizedQuery)
            assertEquals("new", repository.state.value.items.single().title)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `query session memory is bounded`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = repository(scope, maxMemoryQueries = 3) { query -> Result.success(page(query)) }

            repeat(8) { index ->
                repository.activate("query-$index")
                awaitQuery(repository, "query-$index")
            }

            assertTrue(repository.memorySessionCount() <= 3)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `history survives repository reconstruction`() = runBlocking {
        val directory = Files.createTempDirectory("archivetune-search-cache")
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val first = DesktopSearchRepository(
                searchSummary = { Result.success(page(it)) },
                coordinator = DesktopResourceCoordinator(directory),
                scope = firstScope,
                accountScopeProvider = { "account" },
            )
            first.activate("Radiohead")
            awaitQuery(first, "radiohead")
            withTimeout(2_000) {
                while (DesktopResourceCoordinator(directory).readPersistent("search-history:account") == null) delay(20)
            }
        } finally {
            firstScope.cancel()
        }

        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val second = DesktopSearchRepository(
                searchSummary = { Result.success(page(it)) },
                coordinator = DesktopResourceCoordinator(directory),
                scope = secondScope,
                accountScopeProvider = { "account" },
            )
            withTimeout(2_000) {
                while (second.state.value.history.isEmpty()) delay(20)
            }
            assertEquals(listOf("Radiohead"), second.state.value.history)
        } finally {
            secondScope.cancel()
        }
    }

    private fun repository(
        scope: CoroutineScope,
        maxMemoryQueries: Int = 12,
        searcher: suspend (String) -> Result<SearchSummaryPage>,
    ) = DesktopSearchRepository(
        searchSummary = searcher,
        coordinator = DesktopResourceCoordinator(Files.createTempDirectory("archivetune-search-cache")),
        scope = scope,
        accountScopeProvider = { "account" },
        maxMemoryQueries = maxMemoryQueries,
    )

    private suspend fun awaitQuery(repository: DesktopSearchRepository, normalized: String) {
        withTimeout(2_000) {
            while (
                repository.state.value.normalizedQuery != normalized ||
                repository.state.value.isInitialLoading ||
                repository.state.value.items.isEmpty()
            ) {
                delay(10)
            }
        }
    }

    private suspend fun awaitError(repository: DesktopSearchRepository, expected: String) {
        withTimeout(2_000) {
            while (
                repository.state.value.isInitialLoading ||
                repository.state.value.isRefreshing ||
                repository.state.value.error == null
            ) {
                delay(10)
            }
        }
        assertEquals(expected, repository.state.value.error)
    }

    private fun page(query: String) = SearchSummaryPage(
        listOf(
            SearchSummary(
                title = "Top results",
                items = listOf(SongItem(id = "id-$query", title = query, artists = emptyList(), thumbnail = "")),
            ),
        ),
    )
}

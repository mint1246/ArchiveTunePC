package moe.rukamori.archivetune.desktop.platform

import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import moe.rukamori.archivetune.innertube.cache.DesktopResourceCoordinator
import moe.rukamori.archivetune.innertube.pages.ChartsPage
import moe.rukamori.archivetune.innertube.pages.ExplorePage

class DesktopExploreRepositoryTest {
    @Test
    fun `fresh content is reused and account changes clear it`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val backend = RecordingExploreBackend()
        var account = "first"
        val repository = DesktopExploreRepository(
            backend = backend,
            coordinator = DesktopResourceCoordinator(Files.createTempDirectory("explore-sdk-test")),
            scope = scope,
            accountScopeProvider = { account },
        )
        try {
            repository.activate()
            assertTrue(repository.state.value.hasContent)
            repository.activate()
            assertEquals(1, backend.exploreCalls)
            assertEquals(1, backend.chartsCalls)

            account = "second"
            repository.onAccountScopeChanged()
            assertFalse(repository.state.value.hasContent)
            repository.activate()
            assertEquals(2, backend.exploreCalls)
            assertEquals(2, backend.chartsCalls)
            assertEquals("second", repository.state.value.accountScope)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `stale account response cannot populate current account`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()
        var account = "first"
        val backend = object : DesktopExploreBackend {
            override suspend fun explore(): Result<ExplorePage> {
                requestStarted.complete(Unit)
                releaseRequest.await()
                return Result.success(ExplorePage(emptyList(), emptyList()))
            }

            override suspend fun charts() = Result.success(ChartsPage(emptyList(), null))
        }
        val repository = DesktopExploreRepository(
            backend = backend,
            coordinator = DesktopResourceCoordinator(Files.createTempDirectory("explore-account-test")),
            scope = scope,
            accountScopeProvider = { account },
        )
        try {
            val request = async { repository.activate() }
            requestStarted.await()
            account = "second"
            repository.onAccountScopeChanged()
            releaseRequest.complete(Unit)
            request.await()
            assertEquals("second", repository.state.value.accountScope)
            assertFalse(repository.state.value.hasContent)
        } finally {
            scope.cancel()
        }
    }

    private class RecordingExploreBackend : DesktopExploreBackend {
        var exploreCalls = 0
        var chartsCalls = 0

        override suspend fun explore(): Result<ExplorePage> {
            exploreCalls++
            return Result.success(ExplorePage(emptyList(), emptyList()))
        }

        override suspend fun charts(): Result<ChartsPage> {
            chartsCalls++
            return Result.success(ChartsPage(emptyList(), null))
        }
    }
}

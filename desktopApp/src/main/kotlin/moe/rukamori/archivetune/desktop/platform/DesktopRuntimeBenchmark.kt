package moe.rukamori.archivetune.desktop.platform

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import org.json.JSONObject
import kotlin.math.abs
import kotlin.system.exitProcess

/** Opt-in whole-app workload shared by the JVM and Native Image entry points. No account is used. */
internal class DesktopRuntimeBenchmark private constructor(
    private val runtime: DesktopRuntime,
    private val services: DesktopMusicServices,
) {
    private val firstFrame = CompletableDeferred<Pair<Float, Float>>()

    fun onWindowFrame(density: Float, fontScale: Float) { firstFrame.complete(density to fontScale) }

    private fun start() {
        runtime.applicationScope.launch(Dispatchers.IO) {
            var successful = false
            try {
                val root = Path.of(requireNotNull(System.getenv("ARCHIVETUNE_BENCHMARK_ROOT"))).toAbsolutePath().normalize()
                check(listOf(runtime.paths.config, runtime.paths.data, runtime.paths.cache, runtime.paths.logs).all {
                    it.toAbsolutePath().normalize().startsWith(root)
                }) { "Benchmark requires isolated application directories" }
                val audio = Path.of(requireNotNull(System.getenv("ARCHIVETUNE_BENCHMARK_AUDIO"))).toAbsolutePath().normalize()
                check(Files.isRegularFile(audio)) { "Benchmark audio fixture is missing" }
                val (density, fontScale) = withTimeout(120_000L) { firstFrame.await() }
                emit("window-ready", "density" to density, "font_scale" to fontScale)
                // Let initial UI/service work settle before sampling steady-state idle.
                delay(3_000L)
                phase("idle", seconds("ARCHIVETUNE_BENCHMARK_IDLE_SECONDS", 10))

                val playback = services.playback
                playback.setVolume(0f)
                playback.replaceQueue(listOf(PlaybackItem(
                    metadata = PlaybackMetadata(id = "runtime-benchmark-local", title = "Runtime benchmark", durationHintMs = 120_000L),
                    source = PlaybackSource.LocalFile(audio.toString()),
                )))
                withTimeout(20_000L) {
                    playback.state.first { it.status == PlaybackStatus.READY && it.positionMs >= 200L }
                }
                val initialPosition = playback.state.value.positionMs
                phase("playback", seconds("ARCHIVETUNE_BENCHMARK_PLAY_SECONDS", 20))
                check(playback.state.value.positionMs > initialPosition + 1_000L) { "Playback clock did not advance" }

                playback.seekTo(30_000L)
                withTimeout(5_000L) {
                    playback.state.first { it.status == PlaybackStatus.READY && abs(it.positionMs - 30_000L) < 2_000L }
                }
                playback.pause()
                withTimeout(5_000L) { playback.state.first { !it.playWhenReady } }
                phase("paused", 5)
                val pausedAt = playback.state.value.positionMs
                playback.play()
                withTimeout(5_000L) { playback.state.first { it.positionMs > pausedAt + 300L && it.isPlaying } }
                playback.stop()
                check(playback.state.value.status != PlaybackStatus.ERROR) { "Playback stop failed" }
                successful = true
                emit("complete", "success" to true, "local_playback" to true, "seek" to true, "pause_resume" to true)
            } catch (failure: Throwable) {
                // Only the category is public: neither runtime exceptions nor paths can expose a session.
                emit("failure", "category" to failure.javaClass.simpleName)
            } finally {
                withContext(NonCancellable) { runtime.close() }
                exitProcess(if (successful) 0 else 3)
            }
        }
    }

    private suspend fun phase(name: String, seconds: Int) {
        emit("phase-start", "phase" to name)
        delay(seconds * 1_000L)
        emit("phase-end", "phase" to name)
    }

    private fun seconds(variable: String, default: Int): Int =
        System.getenv(variable)?.toIntOrNull()?.coerceIn(2, 60) ?: default

    private fun emit(event: String, vararg fields: Pair<String, Any>) {
        val message = JSONObject().put("event", event).put("wall_ms", System.currentTimeMillis())
        fields.forEach { (key, value) -> message.put(key, value) }
        println("ARCHIVETUNE_RUNTIME_BENCHMARK\t$message")
        System.out.flush()
    }

    companion object {
        fun startIfRequested(runtime: DesktopRuntime, services: DesktopMusicServices): DesktopRuntimeBenchmark? =
            if (System.getenv("ARCHIVETUNE_RUNTIME_BENCHMARK") == "1") {
                DesktopRuntimeBenchmark(runtime, services).also { it.start() }
            } else null
    }
}

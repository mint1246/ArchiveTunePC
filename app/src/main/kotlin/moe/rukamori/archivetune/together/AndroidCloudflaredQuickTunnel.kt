/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.together

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber
import java.io.File
import java.net.URI

class AndroidCloudflaredQuickTunnel private constructor(
    val publicBaseUrl: String,
    private val process: Process,
    private val logJob: Job,
) {
    suspend fun close() {
        process.destroy()
        logJob.cancelAndJoin()
        if (process.isAlive) process.destroyForcibly()
    }

    companion object {
        private const val TunnelStartTimeoutMs = 45_000L
        private val QuickTunnelUrl = Regex("https://[a-z0-9-]+\\.trycloudflare\\.com", RegexOption.IGNORE_CASE)

        suspend fun start(
            context: Context,
            port: Int,
            scope: CoroutineScope,
        ): AndroidCloudflaredQuickTunnel {
            require(port in 1..65535) { "Invalid Quick Tunnel origin port" }
            val executable = File(context.applicationInfo.nativeLibraryDir, "libcloudflared.so")
            require(executable.isFile) {
                "Cloudflare tunnel helper is unavailable for this device architecture"
            }

            val process =
                ProcessBuilder(
                    executable.absolutePath,
                    "tunnel",
                    "--no-autoupdate",
                    "--url",
                    "http://127.0.0.1:$port",
                ).apply {
                    redirectErrorStream(true)
                    environment()["HOME"] = context.cacheDir.absolutePath
                    environment()["TMPDIR"] = context.cacheDir.absolutePath
                }.start()

            val publishedUrl = CompletableDeferred<String>()
            val logJob =
                scope.launch(Dispatchers.IO) {
                    try {
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                Timber.tag("TogetherCloudflared").d("cloudflared: %s", line)
                                publishedQuickTunnelUrl(line)?.let { url ->
                                    if (!publishedUrl.isCompleted) publishedUrl.complete(url)
                                }
                            }
                        }
                        if (!publishedUrl.isCompleted) {
                            publishedUrl.completeExceptionally(
                                IllegalStateException("Cloudflare Quick Tunnel exited before publishing an address"),
                            )
                        }
                    } catch (t: Throwable) {
                        if (!publishedUrl.isCompleted) publishedUrl.completeExceptionally(t)
                    }
                }

            return try {
                val publicBaseUrl = withTimeout(TunnelStartTimeoutMs) { publishedUrl.await() }
                AndroidCloudflaredQuickTunnel(publicBaseUrl = publicBaseUrl, process = process, logJob = logJob)
            } catch (t: Throwable) {
                process.destroy()
                logJob.cancelAndJoin()
                if (process.isAlive) process.destroyForcibly()
                throw t
            }
        }

        /** Ignore cloudflared's bootstrap API URL and wait for the generated public hostname. */
        internal fun publishedQuickTunnelUrl(line: String): String? =
            QuickTunnelUrl
                .findAll(line)
                .map(MatchResult::value)
                .firstOrNull { candidate ->
                    val host = runCatching { URI(candidate).host?.lowercase() }.getOrNull()
                    host != null &&
                        host.endsWith(".trycloudflare.com") &&
                        host != "api.trycloudflare.com"
                }
    }
}

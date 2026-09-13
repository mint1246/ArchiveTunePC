/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.together

import android.content.Context
import android.net.ConnectivityManager
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
import java.util.concurrent.atomic.AtomicReference

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

            val resolverFile = File(context.cacheDir, "r")
            resolverFile.writeText(
                resolverConfig(
                    runCatching {
                        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
                        val activeNetwork = connectivityManager?.activeNetwork
                        connectivityManager
                            ?.getLinkProperties(activeNetwork)
                            ?.dnsServers
                            ?.mapNotNull { it.hostAddress }
                            .orEmpty()
                    }.getOrDefault(emptyList()),
                ),
            )

            val process =
                ProcessBuilder(
                    executable.absolutePath,
                    "tunnel",
                    "--no-autoupdate",
                    "--url",
                    "http://127.0.0.1:$port",
                ).apply {
                    redirectErrorStream(true)
                    directory(context.cacheDir)
                    environment()["HOME"] = context.cacheDir.absolutePath
                    environment()["TMPDIR"] = context.cacheDir.absolutePath
                    environment()["SSL_CERT_DIR"] = androidCaDirectories()
                }.start()

            val publishedUrl = CompletableDeferred<String>()
            val lastLogLine = AtomicReference<String?>(null)
            val logJob =
                scope.launch(Dispatchers.IO) {
                    try {
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                lastLogLine.set(line)
                                Timber.tag("TogetherCloudflared").d("cloudflared: %s", line)
                                publishedQuickTunnelUrl(line)?.let { url ->
                                    if (!publishedUrl.isCompleted) publishedUrl.complete(url)
                                }
                            }
                        }
                        if (!publishedUrl.isCompleted) {
                            val detail = lastLogLine.get()?.trim()?.takeIf(String::isNotEmpty)?.take(600)
                            publishedUrl.completeExceptionally(
                                IllegalStateException(
                                    buildString {
                                        append("Cloudflare Quick Tunnel exited before publishing an address")
                                        if (detail != null) append(": ").append(detail)
                                    },
                                ),
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

        internal fun resolverConfig(addresses: List<String>): String {
            val normalized =
                addresses
                    .asSequence()
                    .map { it.substringBefore('%').trim() }
                    .filter(String::isNotEmpty)
                    .distinct()
                    .take(3)
                    .toList()
                    .ifEmpty { listOf("1.1.1.1", "1.0.0.1") }
            return buildString {
                normalized.forEach { address -> append("nameserver ").append(address).append('\n') }
                append("options timeout:2 attempts:2\n")
            }
        }

        internal fun androidCaDirectories(): String =
            listOf(
                "/apex/com.android.conscrypt/cacerts",
                "/system/etc/security/cacerts",
            ).joinToString(File.pathSeparator)
    }
}

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.platform

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import moe.rukamori.archivetune.desktop.settings.DesktopSettingsStore
import moe.rukamori.archivetune.sdk.lifecycle.SdkLifecycle
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

/** Owns application-scoped jobs and native/service resources for the complete desktop process. */
class DesktopRuntime private constructor(
    val paths: AppPaths,
    val settings: DesktopSettingsStore,
    private val logging: LoggingHandle,
) : AutoCloseable {
    private val lifecycle = SdkLifecycle(Dispatchers.Default + CoroutineName("ArchiveTuneDesktop"))
    val applicationScope: CoroutineScope = lifecycle.scope()
    val logger get() = logging.logger

    private val isClosed = AtomicBoolean(false)

    fun <T : AutoCloseable> own(resource: T): T {
        lifecycle.register(
            AutoCloseable {
                runCatching(resource::close).onFailure { error ->
                    logger.log(Level.WARNING, "A desktop service failed to close cleanly", error)
                }
            },
        )
        return resource
    }

    override fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        logger.info("ArchiveTune Desktop is shutting down")
        lifecycle.close()
        runCatching(settings::close).onFailure { error ->
            logger.log(Level.WARNING, "Desktop settings failed to flush cleanly", error)
        }
        logging.close()
    }

    companion object {
        fun start(): DesktopRuntime {
            val paths = AppPaths.resolve().createDirectories()
            val logging = DesktopLogging.configure(paths)
            val runtime = DesktopRuntime(
                paths = paths,
                settings = DesktopSettingsStore(paths.config.resolve("desktop-settings.properties")),
                logging = logging,
            )
            runtime.logger.info("Starting ArchiveTune Desktop ${DesktopBuildInfo.VERSION} on ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
            runtime.logger.info("Resolved desktop application directories: ${paths.logSummary()}")
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runtime.logger.log(Level.SEVERE, "Uncaught exception on thread ${thread.name}", error)
            }
            return runtime
        }
    }
}

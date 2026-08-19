/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.platform

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import moe.rukamori.archivetune.desktop.settings.DesktopSettingsStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

/** Owns application-scoped jobs and native/service resources for the complete desktop process. */
class DesktopRuntime private constructor(
    val paths: AppPaths,
    val settings: DesktopSettingsStore,
    private val logging: LoggingHandle,
) : AutoCloseable {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("ArchiveTuneDesktop"))
    val logger get() = logging.logger

    private val resources = CopyOnWriteArrayList<AutoCloseable>()
    private val isClosed = AtomicBoolean(false)

    fun <T : AutoCloseable> own(resource: T): T = resource.also(resources::add)

    override fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        logger.info("ArchiveTune Desktop is shutting down")
        applicationScope.cancel("Desktop application is closing")
        resources.asReversed().forEach { resource ->
            runCatching(resource::close).onFailure { error ->
                logger.log(Level.WARNING, "A desktop service failed to close cleanly", error)
            }
        }
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
            runtime.logger.info("Starting ArchiveTune Desktop 14.1.0 on ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
            runtime.logger.info("Resolved desktop application directories: ${paths.logSummary()}")
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runtime.logger.log(Level.SEVERE, "Uncaught exception on thread ${thread.name}", error)
            }
            return runtime
        }
    }
}

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.platform

import java.awt.Desktop
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

object DesktopPlatform {
    fun openDirectory(
        directory: Path,
        logger: Logger,
    ): Boolean = runCatching {
        require(Desktop.isDesktopSupported()) { "Desktop integration is unavailable" }
        val desktop = Desktop.getDesktop()
        require(desktop.isSupported(Desktop.Action.OPEN)) { "Opening folders is unavailable" }
        desktop.open(directory.toFile())
        true
    }.onFailure { error ->
        logger.log(Level.WARNING, "Unable to open application directory", error)
    }.getOrDefault(false)
}

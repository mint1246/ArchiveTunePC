/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

package moe.rukamori.archivetune.desktop.platform

import java.time.Instant
import java.util.logging.ConsoleHandler
import java.util.logging.FileHandler
import java.util.logging.Formatter
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

object DesktopLogging {
    fun configure(paths: AppPaths): LoggingHandle {
        val logger = Logger.getLogger("moe.rukamori.archivetune.desktop")
        logger.level = Level.INFO
        logger.useParentHandlers = false
        logger.handlers.forEach(logger::removeHandler)

        val formatter = ArchiveTuneLogFormatter()
        val handlers = buildList {
            add(
                ConsoleHandler().apply {
                    level = Level.INFO
                    this.formatter = formatter
                },
            )
            runCatching {
                FileHandler(
                    paths.logs.resolve("archivetune-%g.log").toString(),
                    5 * 1024 * 1024,
                    4,
                    true,
                ).apply {
                    level = Level.INFO
                    this.formatter = formatter
                }
            }.onFailure { error ->
                logger.log(Level.WARNING, "File logging is unavailable; continuing with console logging", error)
            }.getOrNull()?.let(::add)
        }
        handlers.forEach(logger::addHandler)

        return LoggingHandle(logger, handlers)
    }
}

class LoggingHandle internal constructor(
    val logger: Logger,
    private val handlers: List<Handler>,
) : AutoCloseable {
    override fun close() {
        handlers.forEach { handler ->
            runCatching {
                handler.flush()
                handler.close()
            }
        }
    }
}

private class ArchiveTuneLogFormatter : Formatter() {
    override fun format(record: LogRecord): String = buildString {
        append(Instant.ofEpochMilli(record.millis))
        append(' ')
        append(record.level.name.padEnd(7))
        append(' ')
        append(record.loggerName.substringAfterLast('.'))
        append(" - ")
        append(formatMessage(record))
        append(System.lineSeparator())
        record.thrown?.let { throwable ->
            append(throwable.stackTraceToString())
            append(System.lineSeparator())
        }
    }
}

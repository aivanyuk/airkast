package io.github.aivanyuk.airkast.media3

import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.Airkast.Logger.Level

/**
 * The player's lines, under the tag `player`. A line is built only when the logger takes its level,
 * and a logger that throws loses the line, never the cast. This is core's `Log` again, which is
 * internal to `airkast-core`.
 */
internal class PlayerLog(
    val logger: Airkast.Logger?,
) {
    inline fun debug(message: () -> String) = at(Level.Debug, null, message)

    inline fun info(message: () -> String) = at(Level.Info, null, message)

    inline fun warn(
        error: Throwable?,
        message: () -> String,
    ) = at(Level.Warn, error, message)

    inline fun at(
        level: Level,
        error: Throwable?,
        message: () -> String,
    ) {
        if (logger != null && level >= logger.minLevel) write(level, message(), error)
    }

    fun write(
        level: Level,
        message: String,
        error: Throwable?,
    ) {
        try {
            logger?.log(level, "player", message, error)
        } catch (_: Exception) {
        }
    }
}

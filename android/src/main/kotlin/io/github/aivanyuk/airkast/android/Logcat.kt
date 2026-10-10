package io.github.aivanyuk.airkast.android

import android.util.Log
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.Airkast.Logger.Level

/**
 * Writes the client's lines at [minLevel] and above to logcat, under the tag `airkast`, each
 * starting with the part that wrote it: `Airkast(context) { logger = Airkast.Logger.logcat() }`,
 * then `adb logcat -s airkast`.
 */
public fun Airkast.Logger.Companion.logcat(minLevel: Level = Level.Debug): Airkast.Logger {
    val lowest = minLevel
    return object : Airkast.Logger {
        override val minLevel: Level = lowest

        override fun log(
            level: Level,
            tag: String,
            message: String,
            error: Throwable?,
        ) {
            val priority =
                when (level) {
                    Level.Verbose -> Log.VERBOSE
                    Level.Debug -> Log.DEBUG
                    Level.Info -> Log.INFO
                    Level.Warn -> Log.WARN
                    Level.Error -> Log.ERROR
                }
            val line = if (error != null) "$tag: $message\n${Log.getStackTraceString(error)}" else "$tag: $message"
            Log.println(priority, LOGCAT_TAG, line)
        }
    }
}

private const val LOGCAT_TAG = "airkast"

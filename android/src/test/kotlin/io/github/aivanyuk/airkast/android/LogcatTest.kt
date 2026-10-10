package io.github.aivanyuk.airkast.android

import android.util.Log
import com.google.common.truth.Truth.assertThat
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.Airkast.Logger.Level
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogcatTest {
    @Test
    fun linesGoUnderOneTagAtTheirLevel() {
        val logger = Airkast.Logger.logcat()
        logger.log(Level.Warn, "control", "dropped a late answer", null)
        logger.log(Level.Error, "session", "ended", IOException("Connection reset"))
        val lines = ShadowLog.getLogsForTag("airkast")
        assertThat(lines.map { it.type }).containsExactly(Log.WARN, Log.ERROR).inOrder()
        assertThat(lines[0].msg).isEqualTo("control: dropped a late answer")
        assertThat(lines[1].msg).startsWith("session: ended\njava.io.IOException: Connection reset")
        assertThat(logger.minLevel).isEqualTo(Level.Debug)
        assertThat(Airkast.Logger.logcat(Level.Verbose).minLevel).isEqualTo(Level.Verbose)
    }
}

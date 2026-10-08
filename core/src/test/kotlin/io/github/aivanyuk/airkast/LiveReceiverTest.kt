package io.github.aivanyuk.airkast

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Plays on a real receiver: `AIRKAST_RECEIVER=192.168.1.20 ./gradlew :core:test`. `AIRKAST_URL`
 * picks the stream, Apple's public test stream by default. Skipped without a receiver.
 */
class LiveReceiverTest {
    private val host = System.getenv("AIRKAST_RECEIVER").orEmpty()
    private val url = System.getenv("AIRKAST_URL")?.takeIf { it.isNotBlank() }
        ?: "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_adv_example_hevc/master.m3u8"

    @Test
    fun playsSeeksPausesAndStops() = runBlocking {
        assumeTrue("Set AIRKAST_RECEIVER to run against a real receiver", host.isNotBlank())
        Airkast.connect(Receiver("live", host), options = SessionOptions(logger = { log("wire", it) })).use { session ->
            session.load(MediaItem(url, startSeconds = 30.0))
            withTimeout(30_000) { session.state.first { it == PlaybackState.Playing } }
            // The receiver reports playing a moment before the picture moves after a start jump.
            delay(5_000)
            val started = session.playbackInfo()
            log("started", started)
            assertThat(started.positionSeconds!!).isAtLeast(29.0)

            delay(5_000)
            val later = session.playbackInfo()
            log("5 s later", later)
            assertThat(later.positionSeconds!! - started.positionSeconds!!).isWithin(1.5).of(5.0)

            val landed = session.seek(120.0)
            log("seek", landed)
            assertThat(landed).isWithin(1.0).of(120.0)
            delay(3_000)
            withTimeout(15_000) { session.state.first { it == PlaybackState.Playing } }

            session.pause()
            withTimeout(10_000) { session.state.first { it == PlaybackState.Paused } }
            session.play()
            withTimeout(10_000) { session.state.first { it == PlaybackState.Playing } }

            log("media", session.selectedMedia())
            log("volume", session.volume())
            session.stop()
        }
    }

    /** `AIRKAST_LONG=1` plays [url] to its end and checks the position against the wall clock. */
    @Test
    fun followsAWholeItem() = runBlocking {
        assumeTrue("Set AIRKAST_RECEIVER and AIRKAST_LONG=1", host.isNotBlank() && System.getenv("AIRKAST_LONG") == "1")
        val logFile = System.getenv("AIRKAST_LOG")?.let(::File)
        val wire: (String) -> Unit = { line ->
            if (!line.startsWith("event channel:")) logFile?.appendText("${System.currentTimeMillis()} $line\n")
        }
        Airkast.connect(Receiver("live", host), options = SessionOptions(logger = wire)).use { session ->
            val ended = async { session.events.first { it is ReceiverEvent.ItemEnded || it is ReceiverEvent.Disconnected } }
            session.load(MediaItem(url))
            withTimeout(60_000) { session.state.first { it == PlaybackState.Playing } }
            delay(10_000)
            val first = session.playbackInfo()
            // Wall time: nanoTime runs slow on some VMs (5% under WSL), which reads as drift.
            val firstAt = System.currentTimeMillis()
            val duration = first.durationSeconds!!
            log("duration", duration)
            var worst = 0.0
            while (!ended.isCompleted) {
                delay(60_000)
                if (ended.isCompleted) break
                val info = session.playbackInfo()
                if (info.state != PlaybackState.Playing) continue
                val drift = (info.positionSeconds!! - first.positionSeconds!!) - (System.currentTimeMillis() - firstAt) / 1e3
                worst = maxOf(worst, kotlin.math.abs(drift))
                log("position", "${info.positionSeconds} of $duration, drift ${"%.2f".format(drift)} s")
                wire("position ${info.positionSeconds} drift ${"%.2f".format(drift)}")
            }
            log("end", ended.await())
            log("worst drift", worst)
            assertThat(ended.await()).isInstanceOf(ReceiverEvent.ItemEnded::class.java)
            // A host clock that gets stepped (WSL does every few minutes) moves the drift by
            // seconds, so this only catches a position that stalls or runs at the wrong rate.
            assertThat(worst).isLessThan(10.0)
        }
    }

    private fun log(label: String, value: Any?) = println("airkast live ${System.currentTimeMillis() % 100_000}: $label: $value")
}

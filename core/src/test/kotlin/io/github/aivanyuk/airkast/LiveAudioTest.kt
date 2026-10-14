package io.github.aivanyuk.airkast

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * Streams tones to a real receiver: `AIRKAST_AUDIO_RECEIVER=192.168.1.30:7000 ./gradlew :core:test`,
 * the port being the one its `_airplay._tcp` record names. Skipped without a receiver. What to
 * hear: rising beeps, a two-second pause, more beeps, then after a flush a steady higher tone.
 */
class LiveAudioTest {
    private val target = System.getenv("AIRKAST_AUDIO_RECEIVER").orEmpty()
    private val rate = System.getenv("AIRKAST_AUDIO_RATE")?.toIntOrNull() ?: 44_100
    private val volume = System.getenv("AIRKAST_AUDIO_VOLUME")?.toDoubleOrNull() ?: 0.2

    @Test
    fun playsPausesFlushesAndSetsTheVolume() =
        runBlocking {
            assumeTrue("Set AIRKAST_AUDIO_RECEIVER to run against a real receiver", target.isNotBlank())
            val receiver = Receiver("live", target.substringBefore(':'), target.substringAfter(':', "7000").toInt())
            val airkast = Airkast { logger = Airkast.Logger.println() }
            airkast.connectAudio(receiver, PcmFormat(sampleRate = rate)).use { stream ->
                val events = launch { stream.events.collect { println("event $it") } }
                println("volume ${stream.volume()}")
                stream.setVolume(volume)
                println("volume after setting $volume: ${stream.volume()}")

                val beeps = launch { stream.write(beeps(seconds = 30)) }
                stream.play()
                stream.setNowPlaying(NowPlaying("Beeps", "airkast", "Live test", duration = 30.seconds))
                delay(stream.latency + 4.seconds)
                val playing = stream.position
                println("position after ${stream.latency} + 4 s: $playing, buffered ${stream.buffered}")
                assertThat(playing.toDouble(DurationUnit.SECONDS)).isWithin(0.3).of(4.0)

                stream.pause()
                val paused = stream.position
                delay(2.seconds)
                assertThat(stream.position).isEqualTo(paused)
                stream.play()
                delay(stream.latency + 3.seconds)
                val resumed = stream.position
                println("position 3 s after resuming: $resumed")
                assertThat((resumed - paused).toDouble(DurationUnit.SECONDS)).isWithin(0.3).of(3.0)

                beeps.cancel()
                stream.flush()
                assertThat(stream.position).isEqualTo(0.seconds)
                launch { stream.write(tone(seconds = 4, pitch = 660.0)) }
                delay(stream.latency + 4.5.seconds)
                println("position after the flush: ${stream.position}, buffered ${stream.buffered}")
                assertThat(stream.buffered).isEqualTo(0.seconds)
                assertThat(stream.position.toDouble(DurationUnit.SECONDS)).isWithin(0.05).of(4.0)
                events.cancel()
            }
            delay(1.seconds)
        }

    /** A 0.3 s beep each second, a step higher each time. */
    private fun beeps(seconds: Int): ByteArray =
        pcm(seconds) { n ->
            val second = n / rate
            if (n % rate < rate * 3 / 10) 330.0 * Math.pow(2.0, (second % 8) / 6.0) else null
        }

    private fun tone(
        seconds: Int,
        pitch: Double,
    ): ByteArray = pcm(seconds) { pitch }

    private fun pcm(
        seconds: Int,
        pitch: (Int) -> Double?,
    ): ByteArray {
        val out = ByteArray(seconds * rate * 4)
        var phase = 0.0
        for (n in 0 until seconds * rate) {
            val hz = pitch(n)
            val sample = if (hz == null) 0 else (sin(phase) * 4000).toInt()
            if (hz != null) phase += 2 * PI * hz / rate
            for (channel in 0..1) {
                out[n * 4 + channel * 2] = sample.toByte()
                out[n * 4 + channel * 2 + 1] = (sample shr 8).toByte()
            }
        }
        return out
    }
}

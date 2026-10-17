package io.github.aivanyuk.airkast.session

import com.google.common.truth.Truth.assertThat
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.AudioStream
import io.github.aivanyuk.airkast.DisconnectReason
import io.github.aivanyuk.airkast.NowPlaying
import io.github.aivanyuk.airkast.PcmFormat
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.ReceiverEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

class AudioStreamTest {
    private val airkast =
        Airkast {
            keepAlive = false
            requestTimeout = 2.seconds
            audioLatency = 200.milliseconds
        }

    private fun stream(
        fake: FakeReceiver,
        format: PcmFormat = PcmFormat(),
    ): AudioStream = runBlocking { airkast.connectAudio(Receiver("fake", "127.0.0.1", fake.port), format) }

    @Test
    fun setsUpARealtimeAlacStreamAfterRecord() {
        FakeReceiver().use { fake ->
            stream(fake).use {
                val setups =
                    fake.requests
                        .withIndex()
                        .filter { it.value == "SETUP" }
                        .map { it.index }
                assertThat(setups).hasSize(2)
                assertThat(fake.requests.indexOf("RECORD")).isIn(setups[0]..setups[1])
                val setup = fake.audio!!.setup
                assertThat(setup["type"]).isEqualTo(96L)
                assertThat(setup["ct"]).isEqualTo(2L)
                assertThat(setup["audioFormat"]).isEqualTo(0x40000L)
                assertThat(setup["spf"]).isEqualTo(352L)
                assertThat(setup["sr"]).isEqualTo(44_100L)
                assertThat((setup["shk"] as ByteArray).size).isEqualTo(32)
                assertThat(it.isPlaying).isFalse()
            }
        }
        FakeReceiver().use { fake ->
            stream(fake, PcmFormat(sampleRate = 48_000)).use {
                assertThat(fake.audio!!.setup["audioFormat"]).isEqualTo(0x100000L)
                assertThat(fake.audio!!.setup["sr"]).isEqualTo(48_000L)
            }
        }
    }

    @Test
    fun playsWhatIsWrittenInOrderOnBothChannels() =
        runBlocking {
            FakeReceiver().use { fake ->
                stream(fake, PcmFormat(channels = 1)).use { stream ->
                    stream.play()
                    stream.write(ramp(0, 4_000, channels = 1))
                    val audio = fake.audio!!
                    waitFor { audio.pcm().size >= 4_000 * 4 }
                    val pcm = audio.pcm()
                    for (frame in 0 until 4_000) {
                        assertThat(sample(pcm, frame, 0)).isEqualTo(frame)
                        assertThat(sample(pcm, frame, 1)).isEqualTo(frame)
                    }
                    assertThat(audio.packets.map { it.marker }.take(3)).containsExactly(true, false, false).inOrder()

                    val record = fake.messages.first { it.startLine.startsWith("RECORD") }
                    val rtpTime = record.header("RTP-Info")!!.substringAfter("rtptime=").toLong()
                    assertThat(audio.packets.first().rtpTime).isEqualTo(rtpTime)
                    val sync = ByteBuffer.wrap(audio.syncs.first())
                    assertThat(sync.get(0)).isEqualTo(0x90.toByte())
                    assertThat(sync.get(1)).isEqualTo(0xD4.toByte())
                    assertThat(sync.getInt(16).toLong() and 0xffffffffL).isEqualTo(rtpTime)
                    // The frame playing when the stream starts is the latency before the first one sent.
                    assertThat(rtpTime - (sync.getInt(4).toLong() and 0xffffffffL)).isEqualTo(44_100L / 5)
                }
            }
        }

    @Test
    fun thePositionFollowsTheReceiversClock() =
        runBlocking {
            FakeReceiver().use { fake ->
                stream(fake).use { stream ->
                    stream.write(ramp(0, 44_100))
                    assertThat(stream.position).isEqualTo(Duration.ZERO)
                    assertThat(stream.buffered).isEqualTo(1.seconds)
                    stream.play()
                    assertThat(stream.isPlaying).isTrue()
                    delay(stream.latency + 500.milliseconds)
                    assertThat(stream.position.toDouble(DurationUnit.SECONDS)).isWithin(0.1).of(0.5)
                    assertThat(stream.buffered.toDouble(DurationUnit.SECONDS)).isWithin(0.1).of(0.5)
                    delay(800.milliseconds)
                    assertThat(stream.position).isEqualTo(1.seconds)
                    assertThat(stream.buffered).isEqualTo(Duration.ZERO)
                }
            }
        }

    @Test
    fun aPauseFlushesTheReceiverAndPlayGoesOnFromTheFramePlaying() =
        runBlocking {
            FakeReceiver().use { fake ->
                stream(fake).use { stream ->
                    stream.write(ramp(0, 30_000))
                    stream.play()
                    delay(stream.latency + 300.milliseconds)
                    stream.pause()
                    val paused = stream.position
                    assertThat(stream.isPlaying).isFalse()
                    val audio = fake.audio!!
                    waitFor { fake.requests.contains("FLUSH") }
                    // Packets sent before the pause may still be on their way.
                    var settled = -1
                    waitFor {
                        (settled == audio.packets.size).also {
                            settled = audio.packets.size
                            Thread.sleep(50)
                        }
                    }
                    val next = audio.packets.maxBy { it.rtpTime }
                    val flush = fake.messages.first { it.startLine.startsWith("FLUSH") }
                    assertThat(flush.header("RTP-Info"))
                        .isEqualTo(
                            "seq=${(next.sequence + 1) and 0xffff};rtptime=${(next.rtpTime + 352) and 0xffffffffL}",
                        )

                    delay(300.milliseconds)
                    assertThat(stream.position).isEqualTo(paused)
                    val before = audio.packets.size
                    stream.play()
                    waitFor { audio.packets.size > before }
                    val resumed = audio.packets[before]
                    assertThat(resumed.marker).isTrue()
                    // The position, a whole number of nanoseconds, rounds the frame down.
                    val frame = (paused.inWholeNanoseconds * 44_100 / 1_000_000_000).toInt()
                    assertThat(sample(resumed.pcm, 0, 0)).isAnyOf(frame, frame + 1)
                    assertThat(audio.syncs.last { it[0] == 0x90.toByte() }).isNotEqualTo(audio.syncs.first())
                }
            }
        }

    @Test
    fun aFlushDropsWhatWasWrittenAndPlaysWhatComesNext() =
        runBlocking {
            FakeReceiver().use { fake ->
                stream(fake).use { stream ->
                    stream.write(ramp(0, 20_000))
                    stream.play()
                    delay(stream.latency + 100.milliseconds)
                    stream.flush()
                    assertThat(stream.position).isEqualTo(Duration.ZERO)
                    assertThat(stream.buffered).isEqualTo(Duration.ZERO)
                    assertThat(stream.isPlaying).isTrue()
                    val audio = fake.audio!!
                    waitFor { fake.requests.contains("FLUSH") }
                    val before = audio.packets.size
                    stream.write(ramp(10_000, 2_000))
                    waitFor { audio.packets.drop(before).any { it.marker } }
                    val first = audio.packets.drop(before).first { it.marker }
                    assertThat(sample(first.pcm, 0, 0)).isEqualTo(10_000)
                }
            }
        }

    @Test
    fun aPacketTheReceiverLostGoesAgain() =
        runBlocking<Unit> {
            FakeReceiver().use { fake ->
                stream(fake).use { stream ->
                    stream.write(ramp(0, 10_000))
                    stream.play()
                    val audio = fake.audio!!
                    waitFor { audio.packets.size >= 5 }
                    val lost = audio.packets[2]
                    val count = audio.packets.size
                    audio.requestAgain(lost.sequence, 2)
                    waitFor {
                        audio.packets.size >= count + 2 &&
                            audio.packets.count { it.sequence == lost.sequence } == 2
                    }
                    val again = audio.packets.drop(count).first { it.sequence == lost.sequence }
                    assertThat(again.raw).isEqualTo(lost.raw)
                }
            }
        }

    @Test
    fun setsTheVolumeAndSaysWhatPlays() =
        runBlocking {
            FakeReceiver().use { fake ->
                stream(fake).use { stream ->
                    stream.setVolume(0.5)
                    stream.setVolume(0.0)
                    val volumes =
                        fake.messages
                            .filter {
                                it.startLine.startsWith(
                                    "SET_PARAMETER",
                                )
                            }.map { String(it.body) }
                    assertThat(volumes).containsExactly("volume: -15.000000\r\n", "volume: -144.000000\r\n").inOrder()
                    fake.volumeDecibels = -6.0
                    assertThat(stream.volume()).isWithin(0.001).of(0.8)

                    val cover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2)
                    stream.setNowPlaying(
                        NowPlaying("Song", "Artist", "Album", duration = 3.seconds, artwork = cover),
                        elapsed = 1.seconds,
                    )
                    val sent = fake.messages.filter { it.startLine.startsWith("SET_PARAMETER") }.drop(2)
                    assertThat(sent.map { it.header("Content-Type") })
                        .containsExactly("application/x-dmap-tagged", "image/jpeg", "text/parameters")
                        .inOrder()
                    val dmap = String(sent[0].body, Charsets.UTF_8)
                    assertThat(dmap).startsWith("mlit")
                    assertThat(dmap).contains("minm\u0000\u0000\u0000\u0004Song")
                    assertThat(dmap).contains("asar\u0000\u0000\u0000\u0006Artist")
                    assertThat(sent[1].body).isEqualTo(cover)
                    val (start, now, end) =
                        String(sent[2].body)
                            .removePrefix("progress: ")
                            .trim()
                            .split('/')
                            .map { it.toLong() }
                    assertThat(now - start).isEqualTo(44_100L)
                    assertThat(end - start).isEqualTo(3 * 44_100L)
                    assertThat(sent.all { it.header("RTP-Info") == "rtptime=$now" }).isTrue()
                }
            }
        }

    @Test
    fun aWriteWaitsForRoomAndOfferTakesWhatFits() =
        runBlocking {
            FakeReceiver().use { fake ->
                stream(fake).use { stream ->
                    // The buffer holds the latency and a second more: 1.2 s, 52920 frames.
                    val writing = async { stream.write(ramp(0, 60_000)) }
                    delay(200.milliseconds)
                    assertThat(writing.isCompleted).isFalse()
                    assertThat(stream.buffered.toDouble(DurationUnit.SECONDS)).isWithin(0.001).of(1.2)
                    assertThat(stream.offer(ByteBuffer.wrap(ByteArray(400)))).isEqualTo(0)
                    stream.play()
                    withTimeout(3_000) { writing.await() }
                    delay(stream.latency + 100.milliseconds)
                    val pcm = ByteBuffer.wrap(ByteArray(402))
                    assertThat(stream.offer(pcm)).isEqualTo(400)
                    assertThat(pcm.remaining()).isEqualTo(2)
                }
            }
        }

    @Test
    fun endsWhenTheReceiverHangsUp() =
        runBlocking<Unit> {
            FakeReceiver().use { fake ->
                val stream = stream(fake)
                val ended = async { stream.events.first { it is ReceiverEvent.Disconnected } }
                fake.dropConnections()
                val event = withTimeout(3_000) { ended.await() } as ReceiverEvent.Disconnected
                assertThat(event.reason).isEqualTo(DisconnectReason.ClosedByReceiver)
                assertThrows(AirkastException.Disconnected::class.java) {
                    runBlocking { stream.write(ByteArray(4)) }
                }
                assertThat(stream.events.first()).isInstanceOf(ReceiverEvent.Disconnected::class.java)
            }
        }

    @Test
    fun closeTearsTheStreamDown() =
        runBlocking<Unit> {
            FakeReceiver().use { fake ->
                val stream = stream(fake)
                stream.close()
                waitFor { fake.requests.contains("TEARDOWN") }
                val error =
                    assertThrows(AirkastException.Disconnected::class.java) { stream.offer(ByteBuffer.allocate(4)) }
                assertThat(error.reason).isEqualTo(DisconnectReason.ClosedByApp)
            }
        }

    /** [frames] frames whose samples count up from [from], on every channel. */
    private fun ramp(
        from: Int,
        frames: Int,
        channels: Int = 2,
    ): ByteArray {
        val out = ByteArray(frames * channels * 2)
        for (frame in 0 until frames) {
            for (channel in 0 until channels) {
                val at = (frame * channels + channel) * 2
                out[at] = (from + frame).toByte()
                out[at + 1] = ((from + frame) shr 8).toByte()
            }
        }
        return out
    }

    private fun sample(
        stereo: ByteArray,
        frame: Int,
        channel: Int,
    ): Int {
        val at = frame * 4 + channel * 2
        return (stereo[at].toInt() and 0xff) or ((stereo[at + 1].toInt() and 0xff) shl 8)
    }

    private suspend fun waitFor(condition: () -> Boolean) {
        withTimeout(3_000) {
            while (!condition()) delay(10)
        }
    }
}

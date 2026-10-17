package io.github.aivanyuk.airkast.exoplayer

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.google.common.truth.Truth.assertThat
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.AudioStream
import io.github.aivanyuk.airkast.DisconnectReason
import io.github.aivanyuk.airkast.NowPlaying
import io.github.aivanyuk.airkast.PcmFormat
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.ReceiverEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AirkastAudioSinkTest {
    private val phone = PhoneSink()
    private val sink = AirkastAudioSink(phone)

    @Test
    fun playsOnThePhoneUntilAStreamAttaches() {
        sink.configure(config(44_100, 2))
        sink.play()
        assertThat(sink.handleBuffer(pcm(100, channels = 2), 0, 1)).isTrue()
        assertThat(phone.bytes).isEqualTo(400)
        assertThat(phone.playing).isTrue()

        val stream = FakeStream()
        sink.attach(stream)
        assertThat(sink.handleBuffer(pcm(100, channels = 2), 5_000_000, 1)).isTrue()
        assertThat(phone.bytes).isEqualTo(400)
        assertThat(phone.playing).isFalse()
        assertThat(phone.flushes).isEqualTo(1)
        assertThat(stream.received.size()).isEqualTo(400)
        assertThat(stream.isPlaying).isTrue()
        stream.position = 250.milliseconds
        assertThat(sink.getCurrentPositionUs(false)).isEqualTo(5_250_000)

        sink.attach(null)
        sink.handleBuffer(pcm(10, channels = 2), 6_000_000, 1)
        assertThat(phone.bytes).isEqualTo(440)
        assertThat(phone.playing).isTrue()
    }

    @Test
    fun aFullStreamTakesTheRestLater() {
        val stream = FakeStream(room = 100)
        sink.attach(stream)
        sink.configure(config(44_100, 2))
        val buffer = pcm(50, channels = 2)
        assertThat(sink.handleBuffer(buffer, 0, 1)).isFalse()
        assertThat(buffer.remaining()).isEqualTo(100)
        stream.room = Int.MAX_VALUE
        assertThat(sink.handleBuffer(buffer, 0, 1)).isTrue()
        assertThat(stream.received.size()).isEqualTo(200)
    }

    @Test
    fun monoAt48kHzBecomesStereoAt44kHz() {
        val stream = FakeStream()
        sink.attach(stream)
        sink.configure(config(48_000, 1))
        repeat(10) { sink.handleBuffer(pcm(4_800, channels = 1, value = 1000), it * 100_000L, 1) }
        sink.playToEndOfStream()
        sink.isEnded
        // A second of 48 kHz mono comes out as a second of 44.1 kHz stereo, give or take Sonic's tail.
        assertThat(stream.received.size().toDouble()).isWithin(4 * 400.0).of(44_100 * 4.0)
        val out = ByteBuffer.wrap(stream.received.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        out.position(4 * 1000)
        assertThat(out.short.toInt()).isWithin(2).of(out.short.toInt())
    }

    @Test
    fun floatBecomes16Bit() {
        val stream = FakeStream()
        sink.attach(stream)
        sink.configure(config(44_100, 2, C.ENCODING_PCM_FLOAT))
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        buffer
            .putFloat(0.5f)
            .putFloat(-1f)
            .putFloat(1f)
            .putFloat(0f)
            .flip()
        assertThat(sink.handleBuffer(buffer, 0, 1)).isTrue()
        val out = ByteBuffer.wrap(stream.received.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertThat(List(4) { out.short.toInt() }).containsExactly(16383, -32767, 32767, 0).inOrder()
    }

    @Test
    fun pauseAndFlushGoToTheStream() {
        val stream = FakeStream()
        sink.attach(stream)
        sink.configure(config(44_100, 2))
        sink.play()
        sink.handleBuffer(pcm(10, channels = 2), 1_000_000, 1)
        assertThat(sink.getCurrentPositionUs(false)).isEqualTo(1_000_000)
        sink.pause()
        assertThat(stream.isPlaying).isFalse()
        sink.flush()
        assertThat(stream.flushes).isEqualTo(1)
        assertThat(sink.getCurrentPositionUs(false)).isEqualTo(AudioSink.CURRENT_POSITION_NOT_SET)
        stream.buffered = 1.milliseconds
        sink.playToEndOfStream()
        assertThat(sink.isEnded).isFalse()
        assertThat(sink.hasPendingData()).isTrue()
        stream.buffered = Duration.ZERO
        assertThat(sink.isEnded).isTrue()
    }

    @Test
    fun aStreamThatEndedTakesTheAudioAway() {
        val stream = FakeStream().apply { ended = true }
        sink.attach(stream)
        sink.configure(config(44_100, 2))
        val buffer = pcm(10, channels = 2)
        assertThat(sink.handleBuffer(buffer, 0, 1)).isTrue()
        assertThat(buffer.hasRemaining()).isFalse()
    }

    @Test
    fun compressedAudioIsDecodedFirst() {
        val ac3 =
            Format
                .Builder()
                .setSampleMimeType(MimeTypes.AUDIO_AC3)
                .setChannelCount(6)
                .setSampleRate(48_000)
                .build()
        assertThat(sink.getFormatSupport(ac3)).isEqualTo(AudioSink.SINK_FORMAT_UNSUPPORTED)
        assertThat(sink.getFormatSupport(format(44_100, 2))).isEqualTo(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY)
    }

    private fun format(
        rate: Int,
        channels: Int,
        encoding: Int = C.ENCODING_PCM_16BIT,
    ): Format =
        Format
            .Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setSampleRate(rate)
            .setChannelCount(channels)
            .setPcmEncoding(encoding)
            .build()

    private fun config(
        rate: Int,
        channels: Int,
        encoding: Int = C.ENCODING_PCM_16BIT,
    ) = AudioSink.AudioSinkConfig.Builder(format(rate, channels, encoding)).build()

    private fun pcm(
        frames: Int,
        channels: Int,
        value: Int = 7,
    ): ByteBuffer {
        val buffer = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames * channels) { buffer.putShort(value.toShort()) }
        return buffer.apply { flip() }
    }

    private class FakeStream(
        var room: Int = Int.MAX_VALUE,
    ) : AudioStream {
        override val receiver = Receiver("speaker", "192.0.2.3")
        override val format = PcmFormat()
        override val latency = 1_000.milliseconds
        override val events: Flow<ReceiverEvent> = emptyFlow()
        override var isPlaying = false
        override var position = Duration.ZERO
        override var buffered = Duration.ZERO
        val received = ByteArrayOutputStream()
        var flushes = 0
        var ended = false

        override suspend fun write(
            pcm: ByteArray,
            offset: Int,
            length: Int,
        ) {}

        override fun offer(pcm: ByteBuffer): Int {
            if (ended) throw AirkastException.Disconnected(null, DisconnectReason.ClosedByApp)
            val n = minOf(room, pcm.remaining()) / 4 * 4
            val bytes = ByteArray(n).also { pcm.get(it) }
            received.write(bytes)
            room -= n
            return n
        }

        override fun play() {
            isPlaying = true
        }

        override fun pause() {
            isPlaying = false
        }

        override fun flush() {
            flushes++
        }

        override suspend fun setVolume(volume: Double) {}

        override suspend fun volume(): Double? = null

        override suspend fun setNowPlaying(
            nowPlaying: NowPlaying,
            elapsed: Duration,
        ) {}

        override fun close() {}
    }

    /** The phone's output: counts what it plays. */
    private class PhoneSink : AudioSink {
        var bytes = 0
        var playing = false
        var flushes = 0

        override fun setListener(listener: AudioSink.Listener) {}

        override fun supportsFormat(format: Format) = true

        override fun getFormatSupport(format: Format) = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY

        override fun getCurrentPositionUs(sourceEnded: Boolean) = 0L

        override fun configure(config: AudioSink.AudioSinkConfig) {}

        override fun play() {
            playing = true
        }

        override fun handleDiscontinuity() {}

        override fun handleBuffer(
            buffer: ByteBuffer,
            presentationTimeUs: Long,
            encodedAccessUnitCount: Int,
        ): Boolean {
            bytes += buffer.remaining()
            buffer.position(buffer.limit())
            return true
        }

        override fun playToEndOfStream() {}

        override fun isEnded() = true

        override fun hasPendingData() = false

        override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {}

        override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT

        override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {}

        override fun getSkipSilenceEnabled() = false

        override fun setAudioAttributes(audioAttributes: AudioAttributes) {}

        override fun getAudioAttributes(): AudioAttributes = AudioAttributes.DEFAULT

        override fun setAudioSessionId(audioSessionId: Int) {}

        override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {}

        override fun getAudioTrackBufferSizeUs() = 0L

        override fun enableTunnelingV21() {}

        override fun disableTunneling() {}

        override fun setVolume(volume: Float) {}

        override fun pause() {
            playing = false
        }

        override fun flush() {
            flushes++
        }

        override fun reset() {}
    }
}

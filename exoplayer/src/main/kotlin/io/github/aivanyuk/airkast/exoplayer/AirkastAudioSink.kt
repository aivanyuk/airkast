package io.github.aivanyuk.airkast.exoplayer

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.google.common.collect.ImmutableList
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.AudioRoute
import io.github.aivanyuk.airkast.AudioStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.Duration

/**
 * An ExoPlayer `AudioSink` that plays through [delegate], the phone's own output, until an
 * [AudioStream] is [attach]ed, and then sends ExoPlayer's decoded audio to the stream: to a speaker,
 * a soundbar, or a TV that plays AirPlay audio. ExoPlayer keeps doing everything else, so the
 * playlist, gapless playback, local files and protected streams all play as they do on the phone.
 *
 * ```
 * val sink = AirkastAudioSink(context)
 * val exoPlayer = ExoPlayer.Builder(context, sink.renderersFactory(context)).build()
 * val player = AirkastPlayer(context, airkast) {
 *     localPlayer = exoPlayer
 *     audioRoute = sink
 * }
 * ```
 *
 * Audio that ExoPlayer already handed the old output is not moved: after an attach, seek the player
 * to its own position so it sends the audio again from there, as `AirkastPlayer` does. ExoPlayer
 * then reports the position the receiver plays, a stream's latency behind what it decodes.
 *
 * While a stream is attached, the sink takes PCM at any rate and channel count and resamples and
 * mixes it to the stream's format; playback speed stays at 1, and ExoPlayer's volume does not
 * change the receiver's. ExoPlayer decodes every format itself, never passing a compressed one
 * through to the phone's output, since the stream may attach in the middle of a track.
 *
 * It uses media3's unstable `AudioSink` API, so a media3 release that changes it may need an
 * airkast release.
 */
@OptIn(UnstableApi::class)
public class AirkastAudioSink(
    private val delegate: AudioSink,
) : ForwardingAudioSink(delegate),
    AudioRoute {
    /** A sink over ExoPlayer's `DefaultAudioSink` for [context]. */
    public constructor(context: Context) : this(DefaultAudioSink.Builder(context).build())

    @Volatile
    private var requested: AudioStream? = null

    // Everything below runs on ExoPlayer's playback thread.
    private var stream: AudioStream? = null
    private var input: Format? = null
    private var pipeline: AudioProcessingPipeline? = null

    /** Converted PCM that did not fit the stream's buffer yet. */
    private var pending: ByteBuffer? = null

    /** The media time of the first frame written since the last flush, which [getCurrentPositionUs] counts from. */
    private var startUs = C.TIME_UNSET
    private var playing = false
    private var endOfStream = false

    /** Sends the audio to [stream] from the next call ExoPlayer makes, or back to the phone for null. */
    override fun attach(stream: AudioStream?) {
        requested = stream
    }

    /** A `DefaultRenderersFactory` whose audio renderer plays through this sink, for `ExoPlayer.Builder`. */
    public fun renderersFactory(context: Context): RenderersFactory =
        object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean,
            ): AudioSink = this@AirkastAudioSink
        }

    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int =
        if (format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(format) else SINK_FORMAT_UNSUPPORTED

    override fun configure(config: AudioSink.AudioSinkConfig) {
        super.configure(config)
        input = config.format
        stream?.let(::configurePipeline)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        follow()
        val s = stream ?: return super.getCurrentPositionUs(sourceEnded)
        if (startUs == C.TIME_UNSET) return CURRENT_POSITION_NOT_SET
        return startUs + s.position.inWholeMicroseconds
    }

    override fun play() {
        playing = true
        follow()
        stream?.play() ?: super.play()
    }

    override fun pause() {
        playing = false
        follow()
        stream?.pause() ?: super.pause()
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        follow()
        val s = stream ?: return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        try {
            if (startUs == C.TIME_UNSET) startUs = presentationTimeUs
            if (!drain(s)) return false
            val pcm = toInt16(buffer)
            val p = pipeline
            if (p == null) {
                if (pcm === buffer) {
                    s.offer(buffer)
                    return !buffer.hasRemaining()
                }
                pending = pcm
            } else {
                p.queueInput(pcm)
                pending = p.getOutput()
            }
            drain(s)
            return !buffer.hasRemaining()
        } catch (_: AirkastException) {
            // The stream ended. The player that attached it hears that from the stream and attaches
            // another or none; the audio meanwhile goes nowhere.
            buffer.position(buffer.limit())
            pending = null
            return true
        }
    }

    override fun playToEndOfStream() {
        follow()
        if (stream == null) return super.playToEndOfStream()
        if (!endOfStream) {
            endOfStream = true
            pipeline?.let { p ->
                p.queueEndOfStream()
                if (pending?.hasRemaining() != true) pending = p.getOutput()
            }
        }
    }

    override fun isEnded(): Boolean {
        follow()
        val s = stream ?: return super.isEnded()
        return endOfStream && drainQuietly(s) && pipeline?.isEnded != false && s.buffered == Duration.ZERO
    }

    override fun hasPendingData(): Boolean {
        follow()
        val s = stream ?: return super.hasPendingData()
        return !drainQuietly(s) || s.buffered.isPositive()
    }

    override fun flush() {
        follow()
        super.flush()
        stream?.flush()
        clearStreamState()
    }

    override fun reset() {
        super.reset()
        stream?.flush()
        clearStreamState()
        pipeline?.reset()
        pipeline = null
        stream?.let(::configurePipeline)
    }

    /** Moves to the stream [attach] asked for, if it is another than the one in use. */
    private fun follow() {
        val next = requested
        val current = stream
        if (next === current) return
        if (current == null) {
            // Leaving the phone's output: what it buffered goes, and the player sends it again.
            super.pause()
            super.flush()
        }
        stream = next
        clearStreamState()
        pipeline?.reset()
        pipeline = null
        if (next != null) {
            configurePipeline(next)
            if (playing) next.play()
        } else if (playing) {
            super.play()
        }
    }

    private fun clearStreamState() {
        pending = null
        startUs = C.TIME_UNSET
        endOfStream = false
        pipeline?.flush(AudioProcessor.StreamMetadata.DEFAULT)
    }

    /** Builds the processors that bring ExoPlayer's PCM to [s]'s rate and channels, or none if it matches. */
    private fun configurePipeline(s: AudioStream) {
        val format = input ?: return
        pipeline?.reset()
        val channels = format.channelCount
        val processors = ImmutableList.builder<AudioProcessor>()
        if (channels != s.format.channels) {
            processors.add(
                ChannelMixingAudioProcessor().apply {
                    putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(channels, s.format.channels))
                },
            )
        }
        if (format.sampleRate != s.format.sampleRate) {
            processors.add(SonicAudioProcessor().apply { setOutputSampleRateHz(s.format.sampleRate) })
        }
        val list = processors.build()
        pipeline =
            if (list.isEmpty()) {
                null
            } else {
                AudioProcessingPipeline(list).apply {
                    configure(AudioProcessor.AudioFormat(format.sampleRate, channels, C.ENCODING_PCM_16BIT))
                    flush(AudioProcessor.StreamMetadata.DEFAULT)
                }
            }
    }

    /** Offers what is pending to [s]. True once nothing is left, or a pipeline has more to give. */
    private fun drain(s: AudioStream): Boolean {
        while (true) {
            val p = pending ?: return true
            if (p.hasRemaining()) s.offer(p)
            if (p.hasRemaining()) return false
            pending = pipeline?.getOutput()?.takeIf { it.hasRemaining() }
        }
    }

    private fun drainQuietly(s: AudioStream): Boolean =
        try {
            drain(s)
        } catch (_: AirkastException) {
            pending = null
            true
        }

    /** [buffer] itself when it holds 16-bit PCM, or its samples as 16-bit, which reads it to its end. */
    private fun toInt16(buffer: ByteBuffer): ByteBuffer {
        val encoding = input?.pcmEncoding ?: C.ENCODING_PCM_16BIT
        if (encoding == C.ENCODING_PCM_16BIT) return buffer
        val source = buffer.order(ByteOrder.LITTLE_ENDIAN)
        val width =
            when (encoding) {
                C.ENCODING_PCM_8BIT -> 1
                C.ENCODING_PCM_24BIT -> 3
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
                else -> throw IllegalStateException("Unexpected PCM encoding $encoding")
            }
        val out = ByteBuffer.allocate(source.remaining() / width * 2).order(ByteOrder.LITTLE_ENDIAN)
        while (source.remaining() >= width) {
            val sample =
                when (encoding) {
                    C.ENCODING_PCM_8BIT -> ((source.get().toInt() and 0xff) - 128) shl 8

                    // The top two of three little-endian bytes.
                    C.ENCODING_PCM_24BIT -> source.get().let { source.short.toInt() }

                    C.ENCODING_PCM_32BIT -> source.int shr 16

                    else -> (source.float.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
                }
            out.putShort(sample.toShort())
        }
        source.position(source.limit())
        out.flip()
        return out
    }
}

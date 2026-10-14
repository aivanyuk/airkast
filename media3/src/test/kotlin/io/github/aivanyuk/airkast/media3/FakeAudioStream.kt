package io.github.aivanyuk.airkast.media3

import io.github.aivanyuk.airkast.AudioStream
import io.github.aivanyuk.airkast.NowPlaying
import io.github.aivanyuk.airkast.PcmFormat
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.ReceiverEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.transformWhile
import java.nio.ByteBuffer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A stream that takes everything and remembers what it was told. */
internal class FakeAudioStream(
    override val receiver: Receiver,
    var receiverVolume: Double = 0.4,
) : AudioStream {
    private val mutableEvents = MutableSharedFlow<ReceiverEvent>(extraBufferCapacity = 16)
    override val format = PcmFormat()
    override val latency = 1.seconds
    override val events: Flow<ReceiverEvent> =
        mutableEvents.transformWhile {
            emit(it)
            it !is ReceiverEvent.Disconnected
        }
    override var isPlaying = false
    override val position = Duration.ZERO
    override val buffered = Duration.ZERO
    var closed = false
        private set
    val volumes = mutableListOf<Double>()
    val nowPlaying = mutableListOf<Pair<NowPlaying, Duration>>()

    fun emit(event: ReceiverEvent) = check(mutableEvents.tryEmit(event))

    override suspend fun write(
        pcm: ByteArray,
        offset: Int,
        length: Int,
    ) {}

    override fun offer(pcm: ByteBuffer): Int = pcm.remaining().also { pcm.position(pcm.limit()) }

    override fun play() {
        isPlaying = true
    }

    override fun pause() {
        isPlaying = false
    }

    override fun flush() {}

    override suspend fun setVolume(volume: Double) {
        volumes += volume
        receiverVolume = volume
    }

    override suspend fun volume(): Double = receiverVolume

    override suspend fun setNowPlaying(
        nowPlaying: NowPlaying,
        elapsed: Duration,
    ) {
        this.nowPlaying += nowPlaying to elapsed
    }

    override fun close() {
        closed = true
    }
}

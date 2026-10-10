package io.github.aivanyuk.airkast.media3

import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.AirkastSession
import io.github.aivanyuk.airkast.Media
import io.github.aivanyuk.airkast.PlaybackInfo
import io.github.aivanyuk.airkast.PlaybackState
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.ReceiverEvent
import io.github.aivanyuk.airkast.Track
import io.github.aivanyuk.airkast.TrackKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.transformWhile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A session that answers at once, reports what it was asked, and plays as the LG does. */
internal class FakeSession(
    override val receiver: Receiver = Receiver("tv", "192.0.2.1", 7000),
) : AirkastSession {
    private val shared = MutableSharedFlow<ReceiverEvent>(extraBufferCapacity = 64)
    override val events: Flow<ReceiverEvent> =
        shared.transformWhile { event ->
            emit(event)
            event !is ReceiverEvent.Disconnected
        }
    override val state = MutableStateFlow(PlaybackState.Unknown)

    val sent = mutableListOf<String>()
    var loaded: Media? = null
    var loadError: AirkastException? = null
    var info = PlaybackInfo(PlaybackState.Playing, 1.0, Duration.ZERO, 3077.seconds, emptyList(), emptyList(), ITEM)
    var volume: Double? = 0.5
    var closed = false
        private set

    fun emit(event: ReceiverEvent) {
        check(shared.tryEmit(event))
    }

    override suspend fun load(item: Media) {
        sent += "load"
        loadError?.let { throw it }
        loaded = item
        emit(ReceiverEvent.ItemChanged(ITEM, null))
        emit(ReceiverEvent.StateChanged(PlaybackState.Playing, null))
    }

    override suspend fun play() {
        sent += "play"
        emit(ReceiverEvent.StateChanged(PlaybackState.Playing, null))
    }

    override suspend fun pause() {
        sent += "pause"
        emit(ReceiverEvent.StateChanged(PlaybackState.Paused, null))
    }

    override suspend fun seek(position: Duration): Duration {
        sent += "seek $position"
        return position
    }

    override suspend fun playbackInfo(): PlaybackInfo = info

    override suspend fun tracks(): List<Track> = emptyList()

    override suspend fun selectTrack(
        kind: TrackKind,
        id: Long?,
    ) {}

    override suspend fun volume(): Double? = volume

    override suspend fun stop() {
        sent += "stop"
        emit(ReceiverEvent.StateChanged(PlaybackState.Stopped, null))
    }

    override fun close() {
        closed = true
        emit(ReceiverEvent.Disconnected(null))
    }

    companion object {
        const val ITEM = "A4271638-F960-41C7-A11A-AC1D0E6C2960"
    }
}

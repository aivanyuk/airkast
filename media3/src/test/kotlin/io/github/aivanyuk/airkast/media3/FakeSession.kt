package io.github.aivanyuk.airkast.media3

import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.MediaItem
import io.github.aivanyuk.airkast.MediaOption
import io.github.aivanyuk.airkast.MediaSelection
import io.github.aivanyuk.airkast.PlaybackInfo
import io.github.aivanyuk.airkast.PlaybackState
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.ReceiverEvent
import io.github.aivanyuk.airkast.VideoSession
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** A session that answers at once, reports what it was asked, and plays as the LG does. */
internal class FakeSession : VideoSession {
    override val receiver = Receiver("tv", "192.0.2.1", 7000)
    override val events = MutableSharedFlow<ReceiverEvent>(extraBufferCapacity = 64)
    override val state = MutableStateFlow(PlaybackState.Unknown)

    val sent = mutableListOf<String>()
    var loaded: MediaItem? = null
    var loadError: AirkastException? = null
    var info = PlaybackInfo(PlaybackState.Playing, 1.0, 0.0, 3077.0, emptyList(), emptyList(), ITEM)
    var decibels: Double? = -15.0

    fun emit(event: ReceiverEvent) {
        check(events.tryEmit(event))
    }

    override suspend fun load(item: MediaItem) {
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

    override suspend fun seek(positionSeconds: Double): Double {
        sent += "seek $positionSeconds"
        return positionSeconds
    }

    override suspend fun playbackInfo(): PlaybackInfo = info

    override suspend fun selectedMedia(): List<MediaOption> = emptyList()

    override suspend fun selectMedia(selections: List<MediaSelection>) {}

    override suspend fun volume(): Double? = decibels

    override suspend fun stop() {
        sent += "stop"
        emit(ReceiverEvent.StateChanged(PlaybackState.Stopped, null))
    }

    override fun close() {
        emit(ReceiverEvent.Disconnected(null))
    }

    companion object {
        const val ITEM = "A4271638-F960-41C7-A11A-AC1D0E6C2960"
    }
}

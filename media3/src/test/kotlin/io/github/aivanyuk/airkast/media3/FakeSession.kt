package io.github.aivanyuk.airkast.media3

import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.AirkastSession
import io.github.aivanyuk.airkast.Media
import io.github.aivanyuk.airkast.PlaybackInfo
import io.github.aivanyuk.airkast.PlaybackState
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.ReceiverEvent
import io.github.aivanyuk.airkast.Rendition
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
    var renditions: List<Rendition> = emptyList()
    var renditionsError: AirkastException? = null

    /** What the receiver reports selected, which a selection changes unless [switches] is off. */
    var selected: List<Track> = emptyList()
    var switches = true
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

    override suspend fun tracks(): List<Track> = selected

    override suspend fun renditions(): List<Rendition> {
        renditionsError?.let { throw it }
        return renditions
    }

    override suspend fun selectTrack(
        kind: TrackKind,
        id: Long?,
    ) {
        sent += "select $kind $id"
        if (!switches) return
        val others = selected.filter { it.kind != kind }
        val picked = renditions.firstOrNull { it.kind == kind && it.id == id }
        selected = others + listOfNotNull(picked?.let { Track(it.kind, it.id, it.name, it.language, it.forced) })
    }

    override suspend fun volume(): Double? = volume

    override suspend fun stop() {
        sent += "stop"
        emit(ReceiverEvent.StateChanged(PlaybackState.Stopped, null))
    }

    override fun close() {
        closed = true
        emit(ReceiverEvent.Disconnected(null))
    }

    /** Gives the session [RENDITIONS], with English audio and captions selected, as the stream's defaults. */
    fun withRenditions() =
        apply {
            renditions = RENDITIONS
            selected =
                listOf(
                    Track(TrackKind.Audio, 0, "English", "en", false),
                    Track(TrackKind.Subtitles, 2, "English", "en", false),
                )
        }

    companion object {
        const val ITEM = "A4271638-F960-41C7-A11A-AC1D0E6C2960"

        /** Two audio renditions, closed captions, Spanish subtitles and forced English ones. */
        val RENDITIONS =
            listOf(
                Rendition(TrackKind.Audio, 0, "English", "en", false, emptyList(), "aac"),
                Rendition(TrackKind.Audio, 1, "Русский", "ru", false, emptyList(), "aac"),
                Rendition(
                    TrackKind.Subtitles,
                    2,
                    "English",
                    "en",
                    false,
                    listOf(
                        "public.accessibility.transcribes-spoken-dialog",
                        "public.accessibility.describes-music-and-sound",
                    ),
                    "cc",
                ),
                Rendition(TrackKind.Subtitles, 3, "Español", "es-MX", false, emptyList(), "subs"),
                Rendition(TrackKind.Subtitles, 4, "English (forced)", "en", true, emptyList(), "subs"),
            )
    }
}

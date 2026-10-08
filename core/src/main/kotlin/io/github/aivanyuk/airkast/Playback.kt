package io.github.aivanyuk.airkast

/** What to play: a URL the receiver fetches itself, from [startSeconds]. */
public data class MediaItem(
    val url: String,
    val startSeconds: Double = 0.0,
    /**
     * `streaming` makes the receiver report what it has buffered, but the LG then reports a
     * sender's pause as loading. `file` reports no buffer and a pause as paused.
     */
    val streaming: Boolean = false,
)

public enum class PlaybackState { Loading, Playing, Paused, Stopped, Unknown }

public data class TimeRange(val startSeconds: Double, val durationSeconds: Double)

/** The answer to a position poll. Times the receiver marks invalid read as null. */
public data class PlaybackInfo(
    val state: PlaybackState,
    val rate: Double,
    val positionSeconds: Double?,
    val durationSeconds: Double?,
    val loaded: List<TimeRange>,
    val seekable: List<TimeRange>,
    val itemId: String?,
)

public enum class MediaKind(internal val wire: String) { Audio("soun"), Subtitles("sbtl") }

/** One rendition the receiver reports as selected. [id] follows the HLS master's order. */
public data class MediaOption(
    val kind: MediaKind,
    val id: Long,
    val name: String?,
    val language: String?,
    val forced: Boolean,
)

/** A selection to make. A subtitle selection with a null [id] turns subtitles off, but forced ones. */
public data class MediaSelection(val kind: MediaKind, val id: Long?)

/** What the receiver reports on its own. */
public sealed interface ReceiverEvent {
    /** `reason` is `ended` at the end of an item and `interrupted` when the TV switches away. */
    public data class StateChanged(val state: PlaybackState, val reason: String?) : ReceiverEvent

    public data class ItemChanged(val itemId: String?, val reason: String?) : ReceiverEvent

    public data class ItemEnded(val itemId: String?) : ReceiverEvent

    /** The TV's own remote changed the rate: pause, play or fast forward. */
    public data class RateChanged(val rate: Double, val positionSeconds: Double?) : ReceiverEvent

    public data class TimeJumped(val positionSeconds: Double?) : ReceiverEvent

    /**
     * A button on the TV's remote that the receiver leaves to the sender. BACK arrives as
     * [BACK_START] then [BACK_END], and the TV leaves the player only once the sender stops.
     * [VOLUME] carries the TV's volume, from 0 to 1.
     */
    public data class RemoteCommand(val code: String, val volume: Double?) : ReceiverEvent {
        public companion object {
            public const val BACK_START: String = "pbpr"
            public const val BACK_END: String = "pbal"
            public const val VOLUME: String = "dvlc"
        }
    }

    /** Any other message, as the receiver sent it. */
    public data class Other(val payload: Map<String, Any?>) : ReceiverEvent

    public data class Disconnected(val cause: Throwable?) : ReceiverEvent
}

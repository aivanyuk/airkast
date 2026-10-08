package io.github.aivanyuk.airkast

import io.github.aivanyuk.airkast.internal.Poko

/** What to play: a URL the receiver fetches itself, from [startSeconds]. */
@Poko
public class MediaItem(
    public val url: String,
    public val startSeconds: Double = 0.0,
    /**
     * `streaming` makes the receiver report what it has buffered, but the LG then reports a
     * sender's pause as loading. `file` reports no buffer and a pause as paused.
     */
    public val streaming: Boolean = false,
)

/** What the receiver says it is doing. New states may join in a minor release. */
public enum class PlaybackState { Loading, Playing, Paused, Stopped, Unknown }

@Poko
public class TimeRange(
    public val startSeconds: Double,
    public val durationSeconds: Double,
)

/** The answer to a position poll. Times the receiver marks invalid read as null. */
@Poko
public class PlaybackInfo(
    public val state: PlaybackState,
    public val rate: Double,
    public val positionSeconds: Double?,
    public val durationSeconds: Double?,
    public val loaded: List<TimeRange>,
    public val seekable: List<TimeRange>,
    public val itemId: String?,
)

public enum class MediaKind(
    internal val wire: String,
) {
    Audio("soun"),
    Subtitles("sbtl"),
}

/** One rendition the receiver reports as selected. [id] follows the HLS master's order. */
@Poko
public class MediaOption(
    public val kind: MediaKind,
    public val id: Long,
    public val name: String?,
    public val language: String?,
    public val forced: Boolean,
)

/** A selection to make. A subtitle selection with a null [id] turns subtitles off, but forced ones. */
@Poko
public class MediaSelection(
    public val kind: MediaKind,
    public val id: Long?,
)

/**
 * What the receiver reports on its own. New events may join in a minor release, so a `when` over
 * them keeps an `else` branch.
 */
public sealed interface ReceiverEvent {
    /** `reason` is `ended` at the end of an item and `interrupted` when the TV switches away. */
    @Poko
    public class StateChanged(
        public val state: PlaybackState,
        public val reason: String?,
    ) : ReceiverEvent

    @Poko
    public class ItemChanged(
        public val itemId: String?,
        public val reason: String?,
    ) : ReceiverEvent

    @Poko
    public class ItemEnded(
        public val itemId: String?,
    ) : ReceiverEvent

    /** The TV's own remote changed the rate: pause, play or fast forward. */
    @Poko
    public class RateChanged(
        public val rate: Double,
        public val positionSeconds: Double?,
    ) : ReceiverEvent

    @Poko
    public class TimeJumped(
        public val positionSeconds: Double?,
    ) : ReceiverEvent

    /**
     * A button on the TV's remote that the receiver leaves to the sender. BACK arrives as
     * [BACK_START] then [BACK_END], and the TV leaves the player only once the sender stops.
     * [VOLUME] carries the TV's volume, from 0 to 1.
     */
    @Poko
    public class RemoteCommand(
        public val code: String,
        public val volume: Double?,
    ) : ReceiverEvent {
        public companion object {
            public const val BACK_START: String = "pbpr"
            public const val BACK_END: String = "pbal"
            public const val VOLUME: String = "dvlc"
        }
    }

    /** Any other message, as the receiver sent it. */
    @Poko
    public class Other(
        public val payload: Map<String, Any?>,
    ) : ReceiverEvent

    @Poko
    public class Disconnected(
        public val cause: Throwable?,
    ) : ReceiverEvent
}

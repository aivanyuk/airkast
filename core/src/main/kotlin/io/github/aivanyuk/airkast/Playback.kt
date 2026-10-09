package io.github.aivanyuk.airkast

import io.github.aivanyuk.airkast.internal.Poko
import kotlin.time.Duration

/** What to play: a URL the receiver fetches itself, from [startAt]. */
@Poko
public class VideoItem(
    public val url: String,
    public val startAt: Duration = Duration.ZERO,
    /**
     * Loads the item as `streaming`, the default, rather than `file`. The LG reports what it has
     * buffered, and reports and switches tracks ([VideoSession.tracks]), only for a `streaming`
     * item. See docs/compatibility.md.
     */
    public val streaming: Boolean = true,
)

/** What the receiver says it is doing. New states may join in a minor release. */
public enum class PlaybackState { Loading, Playing, Paused, Stopped, Unknown }

/** The answer to a position poll. Times the receiver marks invalid read as null. */
@Poko
public class PlaybackInfo(
    public val state: PlaybackState,
    public val rate: Double,
    public val position: Duration?,
    public val duration: Duration?,
    /** What the receiver has buffered. Empty unless the item was loaded as `streaming`. */
    public val buffered: List<ClosedRange<Duration>>,
    public val seekable: List<ClosedRange<Duration>>,
    public val itemId: String?,
)

public enum class TrackKind(
    internal val wire: String,
) {
    Audio("soun"),
    Subtitles("sbtl"),
}

/** One rendition the receiver reports as selected. [id] follows the HLS master's order. */
@Poko
public class Track(
    public val kind: TrackKind,
    public val id: Long,
    public val name: String?,
    public val language: String?,
    public val forced: Boolean,
)

/**
 * Why the receiver changed its state or item, as the receiver names it. A receiver may send a
 * name not listed here, so compare against the constants rather than switch exhaustively.
 */
@JvmInline
public value class Reason(
    public val name: String,
) {
    public companion object {
        /** The item played to its end. */
        public val Ended: Reason = Reason("ended")

        /** The TV switched away from the item: another input, another app, or a second sender. */
        public val Interrupted: Reason = Reason("interrupted")
    }
}

/**
 * What the receiver reports on its own. New events may join in a minor release, so a `when` over
 * them keeps an `else` branch.
 */
public sealed interface ReceiverEvent {
    @Poko
    public class StateChanged(
        public val state: PlaybackState,
        public val reason: Reason?,
    ) : ReceiverEvent

    @Poko
    public class ItemChanged(
        public val itemId: String?,
        public val reason: Reason?,
    ) : ReceiverEvent

    @Poko
    public class ItemEnded(
        public val itemId: String?,
    ) : ReceiverEvent

    /** The TV's own remote changed the rate: pause, play or fast forward. */
    @Poko
    public class RateChanged(
        public val rate: Double,
        public val position: Duration?,
    ) : ReceiverEvent

    @Poko
    public class TimeJumped(
        public val position: Duration?,
    ) : ReceiverEvent

    /**
     * BACK on the TV's remote, which the receiver leaves to the sender: the TV leaves its player
     * only once the sender calls [VideoSession.stop].
     */
    public data object Back : ReceiverEvent

    /** The TV's remote changed its volume. [volume] runs from 0 to 1. */
    @Poko
    public class VolumeChanged(
        public val volume: Double,
    ) : ReceiverEvent

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

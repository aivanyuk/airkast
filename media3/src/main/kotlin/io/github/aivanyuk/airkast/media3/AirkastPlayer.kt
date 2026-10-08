package io.github.aivanyuk.airkast.media3

import android.content.Context
import android.os.Looper
import androidx.media3.common.Player
import io.github.aivanyuk.airkast.ReceiverEvent
import io.github.aivanyuk.airkast.VideoSession

/**
 * A media3 [Player] that plays on a receiver through a [VideoSession], so media3's UI and a
 * `MediaSession`, with its notification and lock screen, drive the TV as they drive a local player.
 *
 * It plays one media item, whose URL the receiver fetches itself. A media item set while a session
 * is attached loads at once, as a Cast player's does, and one set before waits for a session. The
 * device volume can be read but not set. What a `Player` has no place for, such as BACK on the
 * TV's remote ([ReceiverEvent.RemoteCommand]) or switching renditions, the app takes from
 * [session].
 *
 * Call it on its application looper, as any `Player`.
 */
public interface AirkastPlayer : Player {
    /**
     * The session this player drives, or null. Attaching one loads the current media item on it, at
     * the last position, so a session that replaces a lost one picks up where it left off. The
     * player sets this back to null when the session ends, and reports an error if the connection
     * failed rather than closed.
     *
     * The app opens and closes sessions: neither detaching one nor [release] closes it.
     */
    public var session: VideoSession?

    public class Builder(
        context: Context,
    ) {
        private val context = context.applicationContext
        private var looper: Looper = Looper.myLooper() ?: Looper.getMainLooper()
        private var keepAwake = true
        private var pollIntervalMillis = 1_000L

        /** The application looper. The current thread's, or the main one's, by default. */
        public fun setLooper(looper: Looper): Builder = apply { this.looper = looper }

        /**
         * Whether to hold a partial wake lock and a Wi-Fi lock while an item loads, plays or is
         * paused on the receiver. Without them, the phone sleeps with its screen off, the session's
         * keepalive stops, and the receiver may drop the sender. True by default; this artifact's
         * manifest declares `WAKE_LOCK`.
         */
        public fun setKeepAwake(keepAwake: Boolean): Builder = apply { this.keepAwake = keepAwake }

        /** How often to ask the receiver for its position while an item is on it. */
        public fun setPositionPollIntervalMillis(millis: Long): Builder =
            apply {
                require(millis > 0) { "The poll interval must be positive" }
                pollIntervalMillis = millis
            }

        public fun build(): AirkastPlayer =
            SessionPlayer(
                looper = looper,
                awake = if (keepAwake) KeepAwake(context) else null,
                pollIntervalMillis = pollIntervalMillis,
            )
    }
}

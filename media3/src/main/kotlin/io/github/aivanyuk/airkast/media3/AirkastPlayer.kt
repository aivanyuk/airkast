package io.github.aivanyuk.airkast.media3

import android.content.Context
import android.os.Looper
import androidx.media3.common.Player
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.AirkastSession
import io.github.aivanyuk.airkast.Compatibility
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.ReceiverEvent
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A media3 [Player] that plays on a receiver, so media3's UI and a `MediaSession`, with its
 * notification and lock screen, drive the TV as they drive a local player.
 *
 * [connect] runs a whole cast: it opens a session through the player's [Airkast], asks for a PIN
 * when the receiver needs one, plays the media item, and ends the cast when BACK is pressed on the
 * TV's remote. [connection] says where it stands. An app that opens its own sessions sets
 * [session] instead, and the player only plays on it.
 *
 * It plays one media item, whose URL the receiver fetches itself. A media item set while a session
 * is attached loads at once, as a Cast player's does, and one set before waits for a session. The
 * device volume can be read but not set. What a `Player` has no place for, such as switching
 * tracks, the app takes from [session].
 *
 * Call it on its application looper, as any `Player`.
 */
public interface AirkastPlayer : Player {
    /**
     * Where the cast stands, for the UI: idle, connecting, waiting for a PIN, or connected. It
     * follows [connect], [disconnect], a [session] set by hand, and a session that ends.
     */
    public val connection: StateFlow<Connection>

    /**
     * Connects to [receiver] and plays the media item on it. A cast to another receiver ends first,
     * and the item moves to [receiver] at the position it reached. A receiver that asks for a PIN
     * ([Compatibility.NeedsPin]), or any receiver when [withPin] is set, pairs first unless it
     * paired before: [connection] turns [Connection.AwaitingPin] until [enterPin].
     *
     * The player owns the session it opens: it closes it on [disconnect], on the next connect, and
     * on [release]. After a failure, or a session that ended by itself, [prepare] (the play button
     * of a notification, say) connects to the same receiver again.
     */
    public fun connect(
        receiver: Receiver,
        withPin: Boolean = false,
    )

    /** The PIN the receiver shows, while [connection] is [Connection.AwaitingPin]. */
    public fun enterPin(pin: String)

    /**
     * Ends the cast: stops the item on the receiver, which leaves its player, lets go of the
     * session, closing it if the player opened it, and clears the media item, which takes a
     * `MediaSession`'s notification down. A connect in progress, or its PIN prompt, is given up.
     */
    public fun disconnect()

    /**
     * The session this player drives, or null. Setting one by hand attaches it as it is: it loads
     * the current media item on it, at the last position, so a session that replaces a lost one
     * picks up where it left off, and the app keeps it. Neither detaching it nor [release] closes
     * it, and the app hears BACK on the TV's remote ([ReceiverEvent.Back]) from it.
     *
     * The player sets this back to null when the session ends, and reports an error if the
     * connection failed rather than closed.
     */
    public var session: AirkastSession?

    /** Where a cast stands: a `when` over the four states is exhaustive. */
    public sealed interface Connection {
        /**
         * No session. [failure] says why the last cast to [receiver] ended, when it did not end
         * through [disconnect]: the connect failed, or the session ended with
         * [AirkastException.Disconnected] (the network failed, the TV closed it, or another
         * sender took it).
         */
        public class Idle(
            public val receiver: Receiver?,
            public val failure: AirkastException?,
        ) : Connection {
            override fun toString(): String = "Idle(receiver=${receiver?.name}, failure=$failure)"
        }

        public class Connecting(
            public val receiver: Receiver,
        ) : Connection {
            override fun toString(): String = "Connecting(receiver=${receiver.name})"
        }

        /** [receiver] shows a PIN on its screen: pass what the user types to [enterPin], or [disconnect]. */
        public class AwaitingPin(
            public val receiver: Receiver,
        ) : Connection {
            override fun toString(): String = "AwaitingPin(receiver=${receiver.name})"
        }

        public class Connected(
            public val session: AirkastSession,
        ) : Connection {
            override fun toString(): String = "Connected(receiver=${session.receiver.name})"
        }
    }

    /** How to build an [AirkastPlayer]: `AirkastPlayer(context, airkast) { keepAwake = false }`. */
    public class Builder(
        context: Context,
        airkast: Airkast,
    ) {
        private val context = context.applicationContext

        /** The application looper. The current thread's, or the main one's, by default. */
        public var looper: Looper = Looper.myLooper() ?: Looper.getMainLooper()

        /**
         * Whether to hold a partial wake lock and a Wi-Fi lock while an item loads, plays or is
         * paused on the receiver. Without them, the phone sleeps with its screen off, the session's
         * keepalive stops, and the receiver may drop the sender. True by default; this artifact's
         * manifest declares `WAKE_LOCK`.
         */
        public var keepAwake: Boolean = true

        /** How often to ask the receiver for its position while an item is on it. */
        public var positionPollInterval: Duration = 1.seconds

        /**
         * Whether to load items as `streaming`, the default, rather than `file`. The LG reports
         * tracks and buffered ranges only for a `streaming` item.
         */
        public var streaming: Boolean = true

        /**
         * Whether BACK on the TV's remote ends a cast the player connected, as [disconnect] does.
         * The receiver leaves BACK to the sender, and the TV stays in its player until the sender
         * stops. With false, the app hears it as [ReceiverEvent.Back] on the session's events, and
         * decides.
         */
        public var disconnectOnBack: Boolean = true

        /** Opens the sessions [connect] asks for; tests replace it. */
        internal var connector: Connector = airkast.connector()

        public fun build(): AirkastPlayer {
            require(positionPollInterval.isPositive()) { "The poll interval must be positive" }
            return SessionPlayer(
                looper = looper,
                awake = if (keepAwake) KeepAwake(context) else null,
                pollInterval = positionPollInterval,
                streaming = streaming,
                disconnectOnBack = disconnectOnBack,
                connector = connector,
            )
        }
    }
}

/**
 * Builds an [AirkastPlayer] that connects through [airkast]. It needs no session yet: [connect]
 * opens one, or attach your own with [AirkastPlayer.session].
 */
public fun AirkastPlayer(
    context: Context,
    airkast: Airkast,
    block: AirkastPlayer.Builder.() -> Unit = {},
): AirkastPlayer = AirkastPlayer.Builder(context, airkast).apply(block).build()

/** How [AirkastPlayer.connect] opens a session: pairing first when asked to, then connecting. */
internal fun interface Connector {
    suspend fun connect(
        receiver: Receiver,
        withPin: Boolean,
        pin: suspend () -> String,
    ): AirkastSession
}

internal fun Airkast.connector() =
    Connector { receiver, withPin, pin ->
        if (withPin && credentialStore.get(receiver) == null) pair(receiver, pin)
        connect(receiver, pin)
    }

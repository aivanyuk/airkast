package io.github.aivanyuk.airkast

import io.github.aivanyuk.airkast.session.DefaultVideoSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import javax.net.SocketFactory

/**
 * A connection to one receiver that plays URLs on it. Every call suspends on I/O and is safe to
 * make from the main thread. A second sender connecting to the same receiver ends this session.
 */
public interface VideoSession : AutoCloseable {
    public val receiver: Receiver

    /** Receiver events, and a final [ReceiverEvent.Disconnected] when the session ends. */
    public val events: SharedFlow<ReceiverEvent>

    /** The latest playback state the receiver reported, kept for late collectors. */
    public val state: StateFlow<PlaybackState>

    /**
     * Plays [item], replacing whatever plays. Returns once the receiver has taken the item; throws
     * [AirkastException.Timeout] when it stays silent, which a reconnect usually fixes.
     */
    public suspend fun load(item: MediaItem)

    public suspend fun play()

    public suspend fun pause()

    /** Seeks the current item and returns the position the receiver reports. */
    public suspend fun seek(positionSeconds: Double): Double?

    public suspend fun playbackInfo(): PlaybackInfo

    public suspend fun selectedMedia(): List<MediaOption>

    public suspend fun selectMedia(selections: List<MediaSelection>)

    /** The TV's volume in dB from -30 to 0, or null if it does not say. Receivers may not let it be set. */
    public suspend fun volume(): Double?

    /** Ends playback on the receiver, which leaves its player. The session stays open for another [load]. */
    public suspend fun stop()

    /** Closes the connections. A receiver left playing usually stops and goes back to where it was. */
    override fun close()
}

public object Airkast {
    /** Pairs with [receiver] and opens a session. On Android, `airkast-android`'s overload picks the network. */
    public suspend fun connect(
        receiver: Receiver,
        identity: SenderIdentity = SenderIdentity(),
        options: SessionOptions = SessionOptions.DEFAULT,
    ): VideoSession = withContext(Dispatchers.IO) { DefaultVideoSession.open(receiver, identity, options) }
}

/**
 * How a session connects and behaves. Build one with `SessionOptions { ... }`, or change one with
 * [newBuilder]. New options join the [Builder] with defaults, so code that builds options keeps
 * compiling and linking across releases.
 */
public class SessionOptions private constructor(
    builder: Builder,
) {
    public val connectTimeoutMillis: Int = builder.connectTimeoutMillis
    public val requestTimeoutMillis: Long = builder.requestTimeoutMillis

    /** How long a load waits for the receiver to take the item. */
    public val loadTimeoutMillis: Long = builder.loadTimeoutMillis

    /** Sends `/feedback` every two seconds, as Apple's senders do. */
    public val keepAlive: Boolean = builder.keepAlive

    /**
     * Answers the receiver's NTP timing requests, which needs it to reach this device over UDP.
     * The LG CX never asks; send-airplay2 reports that tvOS stalls SETUP without it.
     */
    public val ntpTiming: Boolean = builder.ntpTiming

    /**
     * Creates the TCP connections to the receiver; null uses the platform's default. On Android,
     * a factory bound to the Wi-Fi network keeps them off mobile data and out of a VPN.
     */
    public val socketFactory: SocketFactory? = builder.socketFactory

    /** Receives one line per protocol step, for debugging. Lines never hold the media URL. */
    public val logger: ((String) -> Unit)? = builder.logger

    public fun newBuilder(): Builder = Builder(this)

    public class Builder() {
        public var connectTimeoutMillis: Int = 5_000
        public var requestTimeoutMillis: Long = 5_000
        public var loadTimeoutMillis: Long = 10_000
        public var keepAlive: Boolean = true
        public var ntpTiming: Boolean = false
        public var socketFactory: SocketFactory? = null
        public var logger: ((String) -> Unit)? = null

        internal constructor(options: SessionOptions) : this() {
            connectTimeoutMillis = options.connectTimeoutMillis
            requestTimeoutMillis = options.requestTimeoutMillis
            loadTimeoutMillis = options.loadTimeoutMillis
            keepAlive = options.keepAlive
            ntpTiming = options.ntpTiming
            socketFactory = options.socketFactory
            logger = options.logger
        }

        public fun build(): SessionOptions = SessionOptions(this)
    }

    public companion object {
        public val DEFAULT: SessionOptions = Builder().build()
    }
}

/** Builds [SessionOptions]: `SessionOptions { keepAlive = false }`. */
public fun SessionOptions(block: SessionOptions.Builder.() -> Unit): SessionOptions =
    SessionOptions.Builder().apply(block).build()

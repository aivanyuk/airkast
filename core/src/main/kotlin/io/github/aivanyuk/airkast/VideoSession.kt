package io.github.aivanyuk.airkast

import io.github.aivanyuk.airkast.session.DefaultVideoSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

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
    /** Pairs with [receiver] and opens a session. */
    public suspend fun connect(
        receiver: Receiver,
        identity: SenderIdentity = SenderIdentity(),
        options: SessionOptions = SessionOptions(),
    ): VideoSession = withContext(Dispatchers.IO) { DefaultVideoSession.open(receiver, identity, options) }
}

public data class SessionOptions(
    val connectTimeoutMillis: Int = 5_000,
    val requestTimeoutMillis: Long = 5_000,
    /** How long a load waits for the receiver to take the item. */
    val loadTimeoutMillis: Long = 10_000,
    /** Sends `/feedback` every two seconds, as Apple's senders do. */
    val keepAlive: Boolean = true,
    /**
     * Answers the receiver's NTP timing requests, which needs it to reach this device over UDP.
     * The LG CX never asks; send-airplay2 reports that tvOS stalls SETUP without it.
     */
    val ntpTiming: Boolean = false,
    /** Receives one line per protocol step, for debugging. Lines never hold the media URL. */
    val logger: ((String) -> Unit)? = null,
)

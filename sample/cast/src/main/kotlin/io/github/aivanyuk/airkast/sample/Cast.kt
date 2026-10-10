package io.github.aivanyuk.airkast.sample

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.Compatibility
import io.github.aivanyuk.airkast.Credentials
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.ReceiverEvent
import io.github.aivanyuk.airkast.SenderIdentity
import io.github.aivanyuk.airkast.SessionOptions
import io.github.aivanyuk.airkast.VideoSession
import io.github.aivanyuk.airkast.android.connect
import io.github.aivanyuk.airkast.android.pair
import io.github.aivanyuk.airkast.media3.AirkastPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface CastState {
    /** Not casting. [failure] says why the last cast to [receiver] ended, when it failed. */
    data class Idle(
        val failure: AirkastException? = null,
        val receiver: Receiver? = null,
    ) : CastState

    data class Connecting(
        val receiver: Receiver,
    ) : CastState

    /** [receiver] shows a PIN: [Cast.enterPin] or [Cast.cancelPin]. */
    data class AwaitingPin(
        val receiver: Receiver,
    ) : CastState

    data class Casting(
        val session: VideoSession,
    ) : CastState
}

/**
 * One cast for the whole process: the [AirkastPlayer] that [CastService]'s MediaSession and the
 * UI's controls drive, and the [VideoSession] it plays on. The player never opens or closes a
 * session; this does. Call it on the main thread.
 */
class Cast(
    private val context: Context,
) {
    private val scope = MainScope()

    val player: AirkastPlayer = AirkastPlayer(context)

    private val mutableState = MutableStateFlow<CastState>(CastState.Idle())
    val state: StateFlow<CastState> = mutableState.asStateFlow()

    private val mutableLog = MutableStateFlow<List<String>>(emptyList())

    /** What the receiver reported, newest last. */
    val log: StateFlow<List<String>> = mutableLog.asStateFlow()

    private var following: Job? = null
    private var pin: CompletableDeferred<String>? = null

    /** What pairing with a PIN left, by receiver. A real app keeps them where it keeps secrets. */
    private val pairings = context.getSharedPreferences("pairings", Context.MODE_PRIVATE)

    init {
        player.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) = note("player: ${error.errorCodeName}")
            },
        )
    }

    /**
     * Pairs with [receiver] and plays [url] on it, ending the cast before. A receiver that asks for
     * a PIN, or any receiver when [withPin] is set, pairs with one first, unless it paired before.
     * A connect runs to its end: it is not cancelled, so the session it opens is never left open
     * and unreferenced.
     */
    fun start(
        receiver: Receiver,
        url: String,
        withPin: Boolean = false,
    ) {
        if (state.value is CastState.Connecting || state.value is CastState.AwaitingPin) return
        val previous = (state.value as? CastState.Casting)?.session
        mutableState.value = CastState.Connecting(receiver)
        scope.launch {
            previous?.let { end(it) }
            val stored = pairings.getString(key(receiver), null)?.let(Credentials::decode)
            val session =
                try {
                    val credentials =
                        if (stored == null && (withPin || receiver.compatibility == Compatibility.NeedsPin)) {
                            pair(receiver)
                        } else {
                            stored
                        }
                    // Checks Android 17's local network permission, and binds the session to the
                    // Wi-Fi the receiver is on, off mobile data and out of a VPN.
                    Airkast.connect(context, receiver, IDENTITY, OPTIONS.copy { this.credentials = credentials })
                } catch (e: AirkastException) {
                    // A receiver that refuses its credentials has forgotten this sender: pair again.
                    if (e is AirkastException.PairingFailed && stored != null) forget(receiver)
                    mutableState.value = CastState.Idle(e, receiver)
                    return@launch
                }
            mutableState.value = CastState.Casting(session)
            following = scope.launch { follow(session) }
            player.setMediaItem(MediaItem.fromUri(url))
            player.playWhenReady = true
            // Attaching the session loads the item on the TV.
            player.session = session
        }
    }

    /** The PIN the TV shows, for [CastState.AwaitingPin]. */
    fun enterPin(code: String) {
        pin?.complete(code)
    }

    /** Gives up the pairing that [CastState.AwaitingPin] waits on. */
    fun cancelPin() {
        val receiver = (state.value as? CastState.AwaitingPin)?.receiver ?: return
        mutableState.value = CastState.Idle(receiver = receiver)
        // Cancels the start that waits for it, which closes the pairing's connection.
        pin?.cancel()
    }

    /** Stops playback, which takes the TV out of its player, and closes the session. */
    fun stop() {
        val session = (state.value as? CastState.Casting)?.session ?: return
        mutableState.value = CastState.Idle()
        scope.launch { end(session) }
    }

    /** Runs [block] on the session, or notes why it failed. Every failure is an [AirkastException]. */
    suspend fun <T> attempt(block: suspend VideoSession.() -> T): T? {
        val session = (state.value as? CastState.Casting)?.session ?: return null
        return try {
            session.block()
        } catch (e: AirkastException) {
            note("failed: $e")
            null
        }
    }

    fun note(line: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        mutableLog.update { (it + "$time $line").takeLast(LOG_LINES) }
    }

    /** Pairs with [receiver] once, asking the UI for the PIN, and keeps what it leaves. */
    private suspend fun pair(receiver: Receiver): Credentials {
        val credentials =
            Airkast.pair(context, receiver, IDENTITY, OPTIONS) {
                val code = CompletableDeferred<String>().also { pin = it }
                mutableState.value = CastState.AwaitingPin(receiver)
                code.await().also { mutableState.value = CastState.Connecting(receiver) }
            }
        pairings.edit().putString(key(receiver), credentials.encoded).apply()
        return credentials
    }

    private fun forget(receiver: Receiver) = pairings.edit().remove(key(receiver)).apply()

    private fun key(receiver: Receiver) = receiver.deviceId ?: receiver.host

    private suspend fun end(session: VideoSession) {
        following?.cancel()
        player.session = null
        // Without an item, the MediaSession takes its notification down.
        player.clearMediaItems()
        try {
            session.stop()
        } catch (_: AirkastException) {
            // The session ended already, and the TV with it.
        }
        session.close()
    }

    /**
     * Takes what a [Player] has no place for from the session: BACK on the TV's remote, and its
     * end. The flow completes with the session.
     */
    private suspend fun follow(session: VideoSession) {
        session.events.collect { event ->
            note(event.toString())
            when (event) {
                // The TV leaves its player only once the sender stops.
                is ReceiverEvent.Back -> {
                    stop()
                }

                // The connection dropped, or another sender took the TV. The player has let go of
                // the session already.
                is ReceiverEvent.Disconnected -> {
                    if ((state.value as? CastState.Casting)?.session === session) {
                        player.clearMediaItems()
                        mutableState.value = CastState.Idle(AirkastException.Disconnected(event.cause))
                    }
                }

                else -> {}
            }
        }
    }

    private companion object {
        const val TAG = "airkast"
        const val LOG_LINES = 100

        /** The name the TV shows for this sender. */
        val IDENTITY = SenderIdentity(name = "airkast sample")

        /** One line per protocol step to logcat. A line never holds the media URL. */
        val OPTIONS = SessionOptions { logger = { Log.d(TAG, it) } }
    }
}

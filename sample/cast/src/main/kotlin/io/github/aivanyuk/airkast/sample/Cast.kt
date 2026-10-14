package io.github.aivanyuk.airkast.sample

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.AirkastSession
import io.github.aivanyuk.airkast.MediaFormat
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.Secret
import io.github.aivanyuk.airkast.android.Airkast
import io.github.aivanyuk.airkast.android.logcat
import io.github.aivanyuk.airkast.media3.AirkastPlayer
import io.github.aivanyuk.airkast.media3.AirkastPlayer.Connection
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One player for the whole process: the [AirkastPlayer] that [CastService]'s MediaSession and the
 * UI's controls drive. It plays on the phone through an ExoPlayer until a cast starts, connects,
 * asks for a PIN or password, moves the item to the TV and back, and [AirkastPlayer.connection] says where it
 * stands; this adds a log of what the TV reported. Call it on the main thread.
 */
class Cast(
    context: Context,
) {
    private val scope = MainScope()

    /**
     * The app's one client. `Airkast(context)` names the sender after the app's label, checks
     * Android 17's local network permission, binds sessions to the Wi-Fi the receiver is on, and
     * keeps pairings in the app's no-backup files. This only adds a logger: `adb logcat -s airkast`.
     */
    val airkast = Airkast(context) { logger = Airkast.Logger.logcat() }

    val player: AirkastPlayer =
        AirkastPlayer(context, airkast) {
            localPlayer = ExoPlayer.Builder(context).build()
            // Where an app would count its casts for its statistics.
            eventListener = { note(it.toString()) }
        }

    private val mutableLog = MutableStateFlow<List<String>>(emptyList())

    /** What the receiver reported, newest last. */
    val log: StateFlow<List<String>> = mutableLog.asStateFlow()

    init {
        player.addListener(
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) = note("player: ${error.errorCodeName}")
            },
        )
        scope.launch {
            player.connection.collectLatest { connection ->
                note(connection.toString())
                // Completes when the session ends.
                if (connection is Connection.Connected) connection.session.events.collect { note(it.toString()) }
            }
        }
    }

    /**
     * Plays [url] on [receiver], from where the phone was in it, ending the cast before. A
     * receiver that asks for a PIN or password, or any receiver when [pairWith] is set, pairs
     * first unless it paired before. [format] is read from the URL when null.
     */
    fun start(
        receiver: Receiver,
        url: String,
        format: MediaFormat? = null,
        pairWith: Secret? = null,
    ) {
        show(url, format)
        // The phone stops while the TV connects, and the TV plays from where it stopped.
        player.stop()
        player.playWhenReady = true
        player.connect(receiver, pairWith)
    }

    /** Plays [url] on the phone, from where the TV was in it when casting. */
    fun playHere(
        url: String,
        format: MediaFormat? = null,
    ) {
        player.disconnect()
        show(url, format)
        player.prepare()
        player.play()
    }

    /**
     * Makes [url] the item unless it is already, so moving it between phone and TV keeps its
     * position. AirkastPlayer reads the format from the item's MIME type, so a [format] picked
     * by hand goes there. Any `video/` or `audio/` type loads as a file, and ExoPlayer plays
     * every one of them as a progressive file, whatever the container.
     */
    private fun show(
        url: String,
        format: MediaFormat?,
    ) {
        val mimeType =
            when (format) {
                MediaFormat.Hls -> MimeTypes.APPLICATION_M3U8
                MediaFormat.File -> MimeTypes.VIDEO_MP4
                else -> null
            }
        val current = player.currentMediaItem?.localConfiguration
        if (current?.uri?.toString() != url || current.mimeType != mimeType) {
            player.setMediaItem(
                MediaItem
                    .Builder()
                    .setUri(url)
                    .setMimeType(mimeType)
                    .build(),
            )
        }
    }

    /** Runs [block] on the session, or notes why it failed. Every failure is an [AirkastException]. */
    suspend fun <T> attempt(block: suspend AirkastSession.() -> T): T? {
        val session = player.session ?: return null
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

    private companion object {
        const val LOG_LINES = 100
    }
}

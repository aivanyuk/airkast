package io.github.aivanyuk.airkast.media3

import android.os.Handler
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.AirkastSession
import io.github.aivanyuk.airkast.AudioStream
import io.github.aivanyuk.airkast.NowPlaying
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.Secret
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * [AirkastPlayer] with a local player, as media3's `CastPlayer` has one: it forwards to [local]
 * until a session attaches to [remote], then to [remote], with the item moved across at the
 * position it reached, and back to [local] when the cast ends.
 *
 * A cast to a speaker leaves [local] playing, with its audio on a stream. Meanwhile this tells the
 * receiver what plays, and the device volume is the receiver's.
 */
@OptIn(UnstableApi::class)
internal class HandoffPlayer(
    private val local: Player,
    private val remote: SessionPlayer,
    private val log: PlayerLog = PlayerLog(null),
) : ForwardingSimpleBasePlayer(local),
    AirkastPlayer,
    Handoff {
    private val scope = CoroutineScope(SupervisorJob() + Handler(local.applicationLooper).asCoroutineDispatcher())

    /** The stream [local]'s audio plays on, during a cast to a speaker. */
    private var stream: AudioStream? = null

    /** The receiver's volume from 0 to [MAX_VOLUME], while [stream] plays. */
    private var streamVolume = 0

    private val nowPlaying =
        object : Player.Listener {
            override fun onEvents(
                player: Player,
                events: Player.Events,
            ) {
                if (events.containsAny(
                        EVENT_MEDIA_METADATA_CHANGED,
                        EVENT_POSITION_DISCONTINUITY,
                        EVENT_TIMELINE_CHANGED,
                    )
                ) {
                    tellNowPlaying()
                }
            }
        }

    init {
        remote.handoff = this
        local.addListener(nowPlaying)
    }

    override val connection: StateFlow<AirkastPlayer.Connection> get() = remote.connection

    override var session: AirkastSession?
        get() = remote.session
        set(value) {
            remote.session = value
        }

    override fun connect(
        receiver: Receiver,
        pairWith: Secret?,
    ) = remote.connect(receiver, pairWith)

    override fun enterSecret(value: String) = remote.enterSecret(value)

    override fun disconnect() = remote.disconnect()

    override fun attached() {
        if (player !== local) return
        local.currentMediaItem?.let { item ->
            log.debug { "the local player's item moves to the receiver at ${local.currentPosition} ms" }
            remote.setMediaItem(item, local.currentPosition)
            remote.playWhenReady = local.playWhenReady
        }
        setPlayer(remote)
        local.stop()
    }

    override fun rerouted(stream: AudioStream?) {
        this.stream = stream
        if (player === local && local.currentMediaItem != null) {
            // What the old output buffered stays there: the player sends it again from where it is.
            log.debug {
                "the local player's audio moves ${if (stream != null) "to the receiver" else "back"} at ${local.currentPosition} ms"
            }
            local.seekTo(local.currentMediaItemIndex, local.currentPosition)
        }
        if (stream != null) {
            tellNowPlaying()
            scope.launch {
                val level =
                    try {
                        stream.volume()
                    } catch (_: AirkastException) {
                        null
                    } ?: return@launch
                if (this@HandoffPlayer.stream !== stream) return@launch
                streamVolume = (level * MAX_VOLUME).roundToInt()
                invalidateState()
            }
        }
        invalidateState()
    }

    /** Tells the receiver of [stream] what [local] plays, and how far into it. */
    private fun tellNowPlaying() {
        val stream = stream ?: return
        val metadata = local.mediaMetadata
        val duration = local.duration.takeIf { it != C.TIME_UNSET }?.milliseconds
        val playing =
            NowPlaying(
                title = (metadata.title ?: metadata.displayTitle)?.toString(),
                artist = (metadata.artist ?: metadata.albumArtist)?.toString(),
                album = metadata.albumTitle?.toString(),
                duration = duration,
                artwork = metadata.artworkData,
            )
        val elapsed = local.currentPosition.milliseconds
        scope.launch {
            try {
                stream.setNowPlaying(playing, elapsed)
            } catch (e: AirkastException) {
                log.debug { "the receiver did not take what plays: $e" }
            }
        }
    }

    override fun ended(
        item: MediaItem?,
        positionMs: Long,
    ) {
        if (player !== remote) return
        // Paused: the cast may have ended with nobody at the phone, such as by BACK on the TV.
        local.playWhenReady = false
        if (item != null) {
            log.debug { "the item comes back to the local player at $positionMs ms" }
            // The local playlist keeps its other items unless the cast moved on to another one.
            if (local.currentMediaItem == item) local.seekTo(positionMs) else local.setMediaItem(item, positionMs)
            local.prepare()
        }
        setPlayer(local)
    }

    override fun getState(): State {
        val state = streamed(super.getState())
        if (!local.isCommandAvailable(COMMAND_SET_VIDEO_SURFACE) ||
            state.availableCommands.contains(COMMAND_SET_VIDEO_SURFACE)
        ) {
            return state
        }
        val commands =
            state.availableCommands
                .buildUpon()
                .add(COMMAND_SET_VIDEO_SURFACE)
                .build()
        return state.buildUpon().setAvailableCommands(commands).build()
    }

    /** [state], with the receiver's volume as the device's while [stream] plays. */
    private fun streamed(state: State): State {
        if (stream == null) return state
        val commands =
            state.availableCommands
                .buildUpon()
                .addAll(
                    COMMAND_GET_DEVICE_VOLUME,
                    COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                    COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
                ).build()
        return state
            .buildUpon()
            .setAvailableCommands(commands)
            .setDeviceInfo(STREAM_DEVICE)
            .setDeviceVolume(streamVolume)
            .setIsDeviceMuted(streamVolume == 0)
            .build()
    }

    override fun handleSetDeviceVolume(
        deviceVolume: Int,
        flags: Int,
    ): ListenableFuture<*> {
        val s = stream ?: return super.handleSetDeviceVolume(deviceVolume, flags)
        streamVolume = deviceVolume.coerceIn(0, MAX_VOLUME)
        val level = streamVolume.toDouble() / MAX_VOLUME
        scope.launch {
            try {
                s.setVolume(level)
            } catch (e: AirkastException) {
                log.debug { "the receiver did not take the volume: $e" }
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> =
        if (stream ==
            null
        ) {
            super.handleIncreaseDeviceVolume(flags)
        } else {
            handleSetDeviceVolume(streamVolume + STEP, flags)
        }

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> =
        if (stream ==
            null
        ) {
            super.handleDecreaseDeviceVolume(flags)
        } else {
            handleSetDeviceVolume(streamVolume - STEP, flags)
        }

    // The picture is the local player's, so a surface set during a cast is there when it ends.
    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        when (videoOutput) {
            is SurfaceView -> local.setVideoSurfaceView(videoOutput)
            is TextureView -> local.setVideoTextureView(videoOutput)
            is SurfaceHolder -> local.setVideoSurfaceHolder(videoOutput)
            is Surface -> local.setVideoSurface(videoOutput)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        when (videoOutput) {
            null -> local.clearVideoSurface()
            is SurfaceView -> local.clearVideoSurfaceView(videoOutput)
            is TextureView -> local.clearVideoTextureView(videoOutput)
            is SurfaceHolder -> local.clearVideoSurfaceHolder(videoOutput)
            is Surface -> local.clearVideoSurface(videoOutput)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        local.removeListener(nowPlaying)
        remote.release()
        scope.cancel()
        if (local.isCommandAvailable(COMMAND_RELEASE)) local.release()
        return Futures.immediateVoidFuture()
    }
}

private const val MAX_VOLUME = 100
private const val STEP = 5

private val STREAM_DEVICE: DeviceInfo =
    DeviceInfo
        .Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
        .setMinVolume(0)
        .setMaxVolume(MAX_VOLUME)
        .build()

/** How a [SessionPlayer] passes the item to and from a local player: see [HandoffPlayer]. */
internal interface Handoff {
    /**
     * The local player's audio goes to [stream] from now on, or back to the phone for null, so it
     * plays again from its position into the new output.
     */
    fun rerouted(stream: AudioStream?)

    /** A session attached and the player's state shows it: the local player's item moves to it. */
    fun attached()

    /** The cast ended with [item] at [positionMs], before the player lets go of it. */
    fun ended(
        item: MediaItem?,
        positionMs: Long,
    )
}

package io.github.aivanyuk.airkast.media3

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.aivanyuk.airkast.AirkastSession
import io.github.aivanyuk.airkast.Receiver
import kotlinx.coroutines.flow.StateFlow

/**
 * [AirkastPlayer] with a local player, as media3's `CastPlayer` has one: it forwards to [local]
 * until a session attaches to [remote], then to [remote], with the item moved across at the
 * position it reached, and back to [local] when the cast ends.
 */
@OptIn(UnstableApi::class)
internal class HandoffPlayer(
    private val local: Player,
    private val remote: SessionPlayer,
) : ForwardingSimpleBasePlayer(local),
    AirkastPlayer,
    Handoff {
    init {
        remote.handoff = this
    }

    override val connection: StateFlow<AirkastPlayer.Connection> get() = remote.connection

    override var session: AirkastSession?
        get() = remote.session
        set(value) {
            remote.session = value
        }

    override fun connect(
        receiver: Receiver,
        withPin: Boolean,
    ) = remote.connect(receiver, withPin)

    override fun enterPin(pin: String) = remote.enterPin(pin)

    override fun disconnect() = remote.disconnect()

    override fun attached() {
        if (player !== local) return
        local.currentMediaItem?.let { item ->
            remote.setMediaItem(item, local.currentPosition)
            remote.playWhenReady = local.playWhenReady
        }
        setPlayer(remote)
        local.stop()
    }

    override fun ended(
        item: MediaItem?,
        positionMs: Long,
    ) {
        if (player !== remote) return
        // Paused: the cast may have ended with nobody at the phone, such as by BACK on the TV.
        local.playWhenReady = false
        if (item != null) {
            // The local playlist keeps its other items unless the cast moved on to another one.
            if (local.currentMediaItem == item) local.seekTo(positionMs) else local.setMediaItem(item, positionMs)
            local.prepare()
        }
        setPlayer(local)
    }

    override fun getState(): State {
        val state = super.getState()
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
        remote.release()
        if (local.isCommandAvailable(COMMAND_RELEASE)) local.release()
        return Futures.immediateVoidFuture()
    }
}

/** How a [SessionPlayer] passes the item to and from a local player: see [HandoffPlayer]. */
internal interface Handoff {
    /** A session attached and the player's state shows it: the local player's item moves to it. */
    fun attached()

    /** The cast ended with [item] at [positionMs], before the player lets go of it. */
    fun ended(
        item: MediaItem?,
        positionMs: Long,
    )
}

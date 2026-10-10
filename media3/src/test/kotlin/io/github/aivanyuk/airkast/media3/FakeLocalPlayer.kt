package io.github.aivanyuk.airkast.media3

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** A local player that is ready as soon as it is prepared, holds its position, and keeps its surface. */
@OptIn(UnstableApi::class)
internal class FakeLocalPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    var items: List<MediaItem> = emptyList()
        private set
    var index = 0
        private set
    var positionMs = 0L
    var output: Any? = null
        private set
    var released = false
        private set
    private var wantsPlay = false
    private var playback = Player.STATE_IDLE

    override fun getState(): State {
        val builder =
            State
                .Builder()
                .setAvailableCommands(
                    Player.Commands
                        .Builder()
                        .addAllCommands()
                        .build(),
                ).setPlayWhenReady(wantsPlay, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        if (items.isEmpty()) return builder.build()
        return builder
            .setPlaylist(items.mapIndexed { i, item -> MediaItemData.Builder(i).setMediaItem(item).build() })
            .setCurrentMediaItemIndex(index)
            .setContentPositionMs(positionMs)
            .setPlaybackState(playback)
            .build()
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        items = mediaItems
        index = startIndex.coerceAtLeast(0)
        positionMs = startPositionMs.coerceAtLeast(0)
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (items.isNotEmpty()) playback = Player.STATE_READY
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        wantsPlay = playWhenReady
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        if (mediaItemIndex != C.INDEX_UNSET) index = mediaItemIndex
        this.positionMs = positionMs
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        playback = Player.STATE_IDLE
        return Futures.immediateVoidFuture()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        output = videoOutput
        return Futures.immediateVoidFuture()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        if (videoOutput == null || videoOutput === output) output = null
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        return Futures.immediateVoidFuture()
    }
}

package io.github.aivanyuk.airkast.media3

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.PlaybackState
import io.github.aivanyuk.airkast.ReceiverEvent
import io.github.aivanyuk.airkast.VideoSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import io.github.aivanyuk.airkast.MediaItem as RemoteItem

/**
 * [AirkastPlayer] over media3's `SimpleBasePlayer`. Every handler updates the state before it
 * returns and sends the command after, so the UI never waits on the network. The receiver's events
 * and a position poll correct the state as they come. Everything runs on the application looper.
 */
@OptIn(UnstableApi::class)
internal class SessionPlayer(
    looper: Looper,
    private val awake: KeepAwake?,
    private val pollIntervalMillis: Long,
) : SimpleBasePlayer(looper),
    AirkastPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Handler(looper).asCoroutineDispatcher())
    private var attached: VideoSession? = null
    private var sessionJob: Job? = null
    private var loadJob: Job? = null

    private var item: MediaItem? = null
    private var itemUid = Any()

    /** The receiver has taken [item], so its events and polls describe it. */
    private var loaded = false

    /** The receiver's ID for [item], from the item change that followed its load. */
    private var receiverItemId: String? = null

    /** A load is out, and the next item change with an ID names it. */
    private var awaitingItem = false

    private var playback = Player.STATE_IDLE
    private var wantsPlay = false
    private var wantsPlayReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
    private var rate = 1f
    private var position = PositionSupplier.getConstant(0)
    private var durationMs = C.TIME_UNSET
    private var error: PlaybackException? = null
    private var volume = 0

    override var session: VideoSession?
        get() = attached
        set(value) {
            check(Looper.myLooper() == applicationLooper) { "AirkastPlayer is used on its application looper" }
            if (value === attached) return
            detach()
            error = null
            if (value != null) attach(value)
            changed()
        }

    override fun getState(): State {
        val builder =
            State
                .Builder()
                .setAvailableCommands(COMMANDS)
                .setPlayWhenReady(wantsPlay, wantsPlayReason)
                .setDeviceInfo(DEVICE_INFO)
                .setDeviceVolume(volume)
        val current = item ?: return builder.setPlaybackState(Player.STATE_IDLE).setPlayerError(error).build()
        val data =
            MediaItemData
                .Builder(itemUid)
                .setMediaItem(current)
                .setDurationUs(if (durationMs == C.TIME_UNSET) C.TIME_UNSET else durationMs * 1000)
                .setIsSeekable(durationMs != C.TIME_UNSET)
                // A live stream reports no duration.
                .setIsDynamic(loaded && durationMs == C.TIME_UNSET)
                .build()
        builder
            .setPlaylist(listOf(data))
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(position)
            .setPlaybackState(playback)
            .setIsLoading(playback == Player.STATE_BUFFERING)
        if (playback == Player.STATE_IDLE) builder.setPlayerError(error)
        return builder.build()
    }

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        val s = attached
        if (s != null && loaded && mediaItems.isEmpty()) send(s) { stop() }
        loadJob?.cancel()
        item = mediaItems.getOrNull(if (startIndex == C.INDEX_UNSET) 0 else startIndex)
        itemUid = Any()
        loaded = false
        receiverItemId = null
        awaitingItem = false
        durationMs = C.TIME_UNSET
        error = null
        rate = 1f
        playback = Player.STATE_IDLE
        moveTo(if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs)
        val current = item
        if (s != null && current != null) load(s, current)
        hold()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        val s = attached
        val current = item
        if (s != null && current != null && playback == Player.STATE_IDLE && loadJob?.isActive != true) {
            error = null
            load(s, current)
            hold()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        wantsPlay = playWhenReady
        wantsPlayReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
        moveTo(position.get())
        val s = attached
        // Rebuffering included: a pause the receiver never hears would be undone by its next report.
        if (s != null && loaded) send(s) { if (playWhenReady) play() else pause() }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        val target = if (positionMs == C.TIME_UNSET) 0 else positionMs
        moveTo(target)
        val s = attached
        val current = item
        when {
            s == null || current == null -> {}

            // The receiver let the item go at its end, so a seek plays it again from there.
            playback == Player.STATE_ENDED -> {
                load(s, current)
                hold()
            }

            loaded -> {
                scope.launch {
                    val at =
                        try {
                            s.seek(target / 1000.0)
                        } catch (_: AirkastException) {
                            null
                        }
                    if (at != null && attached === s && loaded) {
                        moveTo((at * 1000).toLong())
                        changed()
                    }
                }
            }
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        val s = attached
        if (s != null && (loaded || loadJob?.isActive == true)) send(s) { stop() }
        loadJob?.cancel()
        loaded = false
        receiverItemId = null
        awaitingItem = false
        playback = Player.STATE_IDLE
        error = null
        moveTo(position.get())
        hold()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        detach()
        awake?.hold(false)
        scope.cancel()
        return Futures.immediateVoidFuture()
    }

    private fun attach(s: VideoSession) {
        attached = s
        // Undispatched, so the collector is listening before the load below sends anything.
        sessionJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                launch(start = CoroutineStart.UNDISPATCHED) { s.events.collect { onEvent(s, it) } }
                volume(s)
                while (isActive) {
                    delay(pollIntervalMillis)
                    if (loaded && (playback == Player.STATE_READY || playback == Player.STATE_BUFFERING)) poll(s)
                }
            }
        item?.let { load(s, it) }
    }

    private fun detach() {
        sessionJob?.cancel()
        sessionJob = null
        loadJob?.cancel()
        attached = null
        loaded = false
        receiverItemId = null
        awaitingItem = false
        if (playback != Player.STATE_ENDED) playback = Player.STATE_IDLE
        moveTo(position.get())
    }

    private fun load(
        s: VideoSession,
        media: MediaItem,
    ) {
        val url = media.localConfiguration?.uri?.toString()
        if (url == null) {
            playback = Player.STATE_IDLE
            error =
                PlaybackException("The media item has no URI", null, PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK)
            return
        }
        val startMs = position.get()
        loadJob?.cancel()
        loaded = false
        receiverItemId = null
        awaitingItem = true
        playback = Player.STATE_BUFFERING
        moveTo(startMs)
        loadJob =
            scope.launch {
                try {
                    s.load(RemoteItem(url, startSeconds = startMs / 1000.0))
                    loaded = true
                    if (!wantsPlay) s.pause()
                } catch (e: AirkastException) {
                    // An ended session reports itself through its events.
                    if (attached === s && e !is AirkastException.Disconnected) {
                        awaitingItem = false
                        playback = Player.STATE_IDLE
                        error = playbackException(e)
                    }
                }
                changed()
            }
    }

    private fun onEvent(
        s: VideoSession,
        event: ReceiverEvent,
    ) {
        if (attached !== s) return
        when (event) {
            is ReceiverEvent.StateChanged -> {
                onState(event.state, event.reason)
            }

            is ReceiverEvent.ItemChanged -> {
                if (awaitingItem && event.itemId != null) {
                    awaitingItem = false
                    receiverItemId = event.itemId
                    loaded = true
                }
            }

            is ReceiverEvent.ItemEnded -> {
                if (loaded && event.itemId == receiverItemId) end()
            }

            is ReceiverEvent.RateChanged -> {
                if (!loaded) return
                if (event.rate > 0) rate = event.rate.toFloat()
                remoteWants(event.rate > 0)
                moveTo(event.positionSeconds?.let { (it * 1000).toLong() } ?: position.get())
            }

            is ReceiverEvent.TimeJumped -> {
                if (!loaded) return
                event.positionSeconds?.let { moveTo((it * 1000).toLong()) }
            }

            is ReceiverEvent.RemoteCommand -> {
                if (event.code != ReceiverEvent.RemoteCommand.VOLUME) return
                event.volume?.let { volume = (it * MAX_VOLUME).roundToInt().coerceIn(0, MAX_VOLUME) }
            }

            is ReceiverEvent.Disconnected -> {
                detach()
                error =
                    event.cause?.let {
                        PlaybackException(
                            "The connection to the receiver failed",
                            it,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        )
                    }
            }

            else -> {
                return
            }
        }
        changed()
    }

    private fun onState(
        state: PlaybackState,
        reason: String?,
    ) {
        if (!loaded) return
        when (state) {
            PlaybackState.Playing -> {
                playback = Player.STATE_READY
                remoteWants(true)
            }

            PlaybackState.Paused -> {
                playback = Player.STATE_READY
                remoteWants(false)
            }

            PlaybackState.Loading -> {
                playback = Player.STATE_BUFFERING
            }

            PlaybackState.Stopped -> {
                if (reason == "ended") {
                    end()
                } else {
                    // Stopped from the TV, such as by switching its input. The app reads why from the session.
                    loaded = false
                    playback = Player.STATE_IDLE
                }
            }

            else -> {}
        }
        moveTo(position.get())
    }

    private suspend fun poll(s: VideoSession) {
        val info =
            try {
                s.playbackInfo()
            } catch (_: AirkastException) {
                return
            }
        if (attached !== s || !loaded) return
        if (info.itemId != null && receiverItemId != null && info.itemId != receiverItemId) return
        durationMs = info.durationSeconds?.let { (it * 1000).toLong() } ?: C.TIME_UNSET
        if (info.rate > 0) rate = info.rate.toFloat()
        when (info.state) {
            PlaybackState.Playing -> {
                playback = Player.STATE_READY
                remoteWants(true)
            }

            PlaybackState.Paused -> {
                playback = Player.STATE_READY
                remoteWants(false)
            }

            PlaybackState.Loading -> {
                playback = Player.STATE_BUFFERING
            }

            else -> {}
        }
        moveTo(info.positionSeconds?.let { (it * 1000).toLong() } ?: position.get())
        changed()
    }

    private suspend fun volume(s: VideoSession) {
        val decibels =
            try {
                s.volume()
            } catch (_: AirkastException) {
                null
            } ?: return
        if (attached !== s) return
        // The receiver's scale runs from -30 dB to 0, with -144 for muted.
        volume = ((decibels + 30) / 30 * MAX_VOLUME).roundToInt().coerceIn(0, MAX_VOLUME)
        changed()
    }

    private fun end() {
        loaded = false
        playback = Player.STATE_ENDED
        if (durationMs != C.TIME_UNSET) moveTo(durationMs)
    }

    private fun remoteWants(play: Boolean) {
        if (wantsPlay == play) return
        wantsPlay = play
        wantsPlayReason = Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE
    }

    /** Sets the position, advancing from here at the receiver's rate while it plays. */
    private fun moveTo(positionMs: Long) {
        val playing = loaded && playback == Player.STATE_READY && wantsPlay
        position = PositionSupplier.getExtrapolating(positionMs, if (playing) rate else 0f)
    }

    private fun send(
        s: VideoSession,
        command: suspend VideoSession.() -> Unit,
    ) {
        scope.launch {
            try {
                s.command()
            } catch (_: AirkastException) {
                // A late answer leaves the state to the next event or poll; an ended session reports itself.
            }
        }
    }

    private fun hold() {
        awake?.hold(attached != null && (playback == Player.STATE_BUFFERING || playback == Player.STATE_READY))
    }

    private fun changed() {
        hold()
        invalidateState()
    }

    private companion object {
        const val MAX_VOLUME = 100

        val DEVICE_INFO: DeviceInfo =
            DeviceInfo
                .Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                .setMinVolume(0)
                .setMaxVolume(MAX_VOLUME)
                .build()

        val COMMANDS: Player.Commands =
            Player.Commands
                .Builder()
                .addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_PREPARE,
                    Player.COMMAND_STOP,
                    Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_BACK,
                    Player.COMMAND_SEEK_FORWARD,
                    Player.COMMAND_SET_MEDIA_ITEM,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_METADATA,
                    Player.COMMAND_GET_DEVICE_VOLUME,
                    Player.COMMAND_RELEASE,
                ).build()

        fun playbackException(e: AirkastException): PlaybackException =
            PlaybackException(
                e.message,
                e,
                when (e) {
                    is AirkastException.Timeout -> PlaybackException.ERROR_CODE_TIMEOUT
                    is AirkastException.Unreachable -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                    is AirkastException.Rejected -> PlaybackException.ERROR_CODE_REMOTE_ERROR
                    else -> PlaybackException.ERROR_CODE_UNSPECIFIED
                },
            )
    }
}

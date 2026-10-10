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
import io.github.aivanyuk.airkast.AirkastSession
import io.github.aivanyuk.airkast.Media
import io.github.aivanyuk.airkast.PlaybackState
import io.github.aivanyuk.airkast.Reason
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.ReceiverEvent
import io.github.aivanyuk.airkast.media3.AirkastPlayer.Connection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * [AirkastPlayer] over media3's `SimpleBasePlayer`. Every handler updates the state before it
 * returns and sends the command after, so the UI never waits on the network. The receiver's events
 * and a position poll correct the state as they come. Everything runs on the application looper,
 * the connects [connector] makes and the PIN prompts they wait on included.
 */
@OptIn(UnstableApi::class)
internal class SessionPlayer(
    looper: Looper,
    private val awake: KeepAwake?,
    private val pollInterval: Duration,
    private val streaming: Boolean,
    private val disconnectOnBack: Boolean,
    private val connector: Connector,
) : SimpleBasePlayer(looper),
    AirkastPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Handler(looper).asCoroutineDispatcher())
    private var attached: AirkastSession? = null

    private val mutableConnection = MutableStateFlow<Connection>(Connection.Idle(null, null))
    override val connection: StateFlow<Connection> = mutableConnection.asStateFlow()

    /** The session [connect] opened, which the player closes. */
    private var owned: AirkastSession? = null

    /** Sessions the player opened that are stopping before they close. */
    private val ending = mutableSetOf<AirkastSession>()

    /** The receiver of the last [connect], which [prepare] connects to again once the cast has dropped. */
    private var lastReceiver: Receiver? = null
    private var connecting: Job? = null
    private var pin: CompletableDeferred<String>? = null

    /** The local player's side, when the player has one: see [HandoffPlayer]. */
    internal var handoff: Handoff? = null
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

    override var session: AirkastSession?
        get() = attached
        set(value) {
            checkLooper()
            if (value === attached) return
            giveUpConnecting()
            lastReceiver = null
            // The app's own session it keeps as it is; one the player opened ends.
            letGo(stop = owned != null)
            error = null
            if (value != null) attach(value) else handBack()
            mutableConnection.value = if (value != null) Connection.Connected(value) else Connection.Idle(null, null)
            changed()
            if (value != null) handoff?.attached()
        }

    override fun connect(
        receiver: Receiver,
        withPin: Boolean,
    ) {
        checkLooper()
        open(receiver, withPin)
        changed()
    }

    override fun enterPin(pin: String) {
        checkLooper()
        this.pin?.complete(pin)
    }

    override fun disconnect() {
        checkLooper()
        giveUpConnecting()
        lastReceiver = null
        letGo(stop = true)
        handBack()
        setItem(null, 0)
        mutableConnection.value = Connection.Idle(null, null)
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
        setItem(mediaItems.getOrNull(if (startIndex == C.INDEX_UNSET) 0 else startIndex), startPositionMs)
        val current = item
        if (s != null && current != null) load(s, current)
        hold()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        val s = attached
        val current = item
        val receiver = lastReceiver
        when {
            s != null -> {
                if (current != null && playback == Player.STATE_IDLE && loadJob?.isActive != true) {
                    error = null
                    load(s, current)
                    hold()
                }
            }

            // The cast this player connected dropped or failed, and the user asks to play again.
            receiver != null && current != null && connecting == null -> {
                open(receiver, withPin = false)
            }
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
                            s.seek(target.milliseconds)
                        } catch (_: AirkastException) {
                            null
                        }
                    if (at != null && attached === s && loaded) {
                        moveTo(at.inWholeMilliseconds)
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
        giveUpConnecting()
        letGo(stop = false)
        ending.forEach { it.close() }
        ending.clear()
        awake?.hold(false)
        scope.cancel()
        mutableConnection.value = Connection.Idle(null, null)
        return Futures.immediateVoidFuture()
    }

    /**
     * Opens a session to [receiver] through [connector], ending the cast before. A connect that
     * [giveUpConnecting] cancels closes what it opened (`Airkast.connect` does), and its PIN
     * prompt with it.
     */
    private fun open(
        receiver: Receiver,
        withPin: Boolean,
    ) {
        giveUpConnecting()
        letGo(stop = true)
        error = null
        lastReceiver = receiver
        mutableConnection.value = Connection.Connecting(receiver)
        connecting =
            scope.launch {
                val s =
                    try {
                        connector.connect(receiver, withPin) { askForPin(receiver) }
                    } catch (e: AirkastException) {
                        connecting = null
                        error = playbackException(e)
                        mutableConnection.value = Connection.Idle(receiver, e)
                        handBack()
                        changed()
                        return@launch
                    }
                connecting = null
                owned = s
                attach(s)
                mutableConnection.value = Connection.Connected(s)
                changed()
                handoff?.attached()
            }
    }

    private suspend fun askForPin(receiver: Receiver): String {
        val code = CompletableDeferred<String>().also { pin = it }
        mutableConnection.value = Connection.AwaitingPin(receiver)
        return code.await().also {
            pin = null
            mutableConnection.value = Connection.Connecting(receiver)
        }
    }

    private fun giveUpConnecting() {
        connecting?.cancel()
        connecting = null
        pin = null
    }

    /**
     * Detaches the session. With [stop], the receiver stops the item first, which takes it out of
     * its player. A session the player opened then closes.
     */
    private fun letGo(stop: Boolean) {
        val s = attached ?: return
        val mine = s === owned
        val playing = loaded || loadJob?.isActive == true
        owned = null
        detach()
        when {
            stop && playing -> {
                if (mine) ending += s
                scope.launch {
                    try {
                        s.stop()
                    } catch (_: AirkastException) {
                        // It ended already, and the receiver's player with it.
                    } finally {
                        if (mine) {
                            ending -= s
                            s.close()
                        }
                    }
                }
            }

            mine -> {
                s.close()
            }
        }
    }

    /** With a local player, the cast that ended gives it the item, and this player is left empty. */
    private fun handBack() {
        val local = handoff ?: return
        local.ended(item, position.get())
        setItem(null, 0)
    }

    private fun setItem(
        media: MediaItem?,
        startPositionMs: Long,
    ) {
        loadJob?.cancel()
        item = media
        itemUid = Any()
        loaded = false
        receiverItemId = null
        awaitingItem = false
        durationMs = C.TIME_UNSET
        error = null
        rate = 1f
        playback = Player.STATE_IDLE
        moveTo(if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs)
    }

    private fun checkLooper() =
        check(Looper.myLooper() == applicationLooper) {
            "AirkastPlayer is used on its application looper"
        }

    private fun attach(s: AirkastSession) {
        attached = s
        // Undispatched, so the collector is listening before the load below sends anything.
        sessionJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                launch(start = CoroutineStart.UNDISPATCHED) { s.events.collect { onEvent(s, it) } }
                volume(s)
                while (isActive) {
                    delay(pollInterval)
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
        s: AirkastSession,
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
                    s.load(Media(url, startAt = startMs.milliseconds, streaming = streaming))
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
        s: AirkastSession,
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
                moveTo(event.position?.inWholeMilliseconds ?: position.get())
            }

            is ReceiverEvent.TimeJumped -> {
                if (!loaded) return
                event.position?.let { moveTo(it.inWholeMilliseconds) }
            }

            is ReceiverEvent.VolumeChanged -> {
                volume = (event.volume * MAX_VOLUME).roundToInt().coerceIn(0, MAX_VOLUME)
            }

            // The TV leaves its player only once the sender stops.
            is ReceiverEvent.Back -> {
                if (s === owned && disconnectOnBack) disconnect()
                return
            }

            is ReceiverEvent.Disconnected -> {
                if (s === owned) owned = null
                detach()
                error =
                    event.cause?.let {
                        PlaybackException(
                            "The connection to the receiver failed",
                            it,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        )
                    }
                mutableConnection.value = Connection.Idle(s.receiver, AirkastException.Disconnected(event.cause))
                handBack()
            }

            else -> {
                return
            }
        }
        changed()
    }

    private fun onState(
        state: PlaybackState,
        reason: Reason?,
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
                if (reason == Reason.Ended) {
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

    private suspend fun poll(s: AirkastSession) {
        val info =
            try {
                s.playbackInfo()
            } catch (_: AirkastException) {
                return
            }
        if (attached !== s || !loaded) return
        if (info.itemId != null && receiverItemId != null && info.itemId != receiverItemId) return
        durationMs = info.duration?.inWholeMilliseconds ?: C.TIME_UNSET
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
        moveTo(info.position?.inWholeMilliseconds ?: position.get())
        changed()
    }

    private suspend fun volume(s: AirkastSession) {
        val level =
            try {
                s.volume()
            } catch (_: AirkastException) {
                null
            } ?: return
        if (attached !== s) return
        volume = (level * MAX_VOLUME).roundToInt().coerceIn(0, MAX_VOLUME)
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
        s: AirkastSession,
        command: suspend AirkastSession.() -> Unit,
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
                    is AirkastException.Timeout -> {
                        PlaybackException.ERROR_CODE_TIMEOUT
                    }

                    is AirkastException.Unreachable, is AirkastException.Disconnected -> {
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                    }

                    is AirkastException.NotPermitted -> {
                        PlaybackException.ERROR_CODE_IO_NO_PERMISSION
                    }

                    is AirkastException.Rejected,
                    is AirkastException.PairingFailed,
                    is AirkastException.PinRejected,
                    -> {
                        PlaybackException.ERROR_CODE_REMOTE_ERROR
                    }

                    else -> {
                        PlaybackException.ERROR_CODE_UNSPECIFIED
                    }
                },
            )
    }
}

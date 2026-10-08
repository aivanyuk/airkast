package io.github.aivanyuk.airkast.session

import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.MediaItem
import io.github.aivanyuk.airkast.MediaKind
import io.github.aivanyuk.airkast.MediaOption
import io.github.aivanyuk.airkast.MediaSelection
import io.github.aivanyuk.airkast.PlaybackInfo
import io.github.aivanyuk.airkast.PlaybackState
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.ReceiverEvent
import io.github.aivanyuk.airkast.SenderIdentity
import io.github.aivanyuk.airkast.SessionOptions
import io.github.aivanyuk.airkast.TimeRange
import io.github.aivanyuk.airkast.VideoSession
import io.github.aivanyuk.airkast.crypto.Hkdf
import io.github.aivanyuk.airkast.wire.BinaryPlist
import io.github.aivanyuk.airkast.wire.HttpMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * AirPlay video v2: pair transiently, SETUP the session and its event channel, SETUP a type-130
 * stream, then drive playback with `/command` messages whose answers come on the event channel.
 */
internal class DefaultVideoSession private constructor(
    override val receiver: Receiver,
    private val options: SessionOptions,
    private val control: ControlConnection,
    private val timing: TimingResponder?,
) : VideoSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableEvents = MutableSharedFlow<ReceiverEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<ReceiverEvent> = mutableEvents.asSharedFlow()
    private val mutableState = MutableStateFlow(PlaybackState.Unknown)
    override val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    private val sessionId = UUID.randomUUID().toString().uppercase()
    private val commandSessionId = UUID.randomUUID().toString().uppercase()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Map<String, Any?>>>()
    private val nextMessageId = AtomicLong(1)
    private val closed = AtomicBoolean(false)
    private var eventChannel: EventChannel? = null
    private var streamId = 0L
    private val rtspUri = "rtsp://${control.localAddress.hostAddress}/${SecureRandom().nextInt() and Int.MAX_VALUE}"

    @Volatile
    private var itemId: String? = null

    @Volatile
    private var itemTaken: CompletableDeferred<Unit>? = null

    private fun start(
        identity: SenderIdentity,
        sessionKey: ByteArray,
    ) {
        val base =
            control
                .exchange(
                    "SETUP",
                    rtspUri,
                    body =
                        BinaryPlist.encode(
                            mapOf(
                                "deviceID" to identity.deviceId,
                                "sessionUUID" to sessionId,
                                "timingProtocol" to if (timing != null) "NTP" else "None",
                                "isMultiSelectAirPlay" to true,
                                "groupContainsGroupLeader" to false,
                                "macAddress" to identity.deviceId,
                                "model" to identity.model,
                                "name" to identity.name,
                                "osBuildVersion" to identity.osBuildVersion,
                                "osName" to identity.osName,
                                "osVersion" to identity.osVersion,
                                "senderSupportsRelay" to false,
                                "sourceVersion" to identity.sourceVersion,
                                "statsCollectionEnabled" to false,
                            ) + (timing?.let { mapOf("timingPort" to it.port.toLong()) } ?: emptyMap()),
                        ),
                    contentType = ControlConnection.BPLIST,
                ).requireSuccess("SETUP")
        options.logger?.invoke("SETUP ${base.status} ${plist(base).keys}")
        val eventPort =
            (plist(base)["eventPort"] as? Long)?.toInt()
                ?: throw AirkastException.Rejected("SETUP without an event port", base.status)
        val eventSocket = connectWithRetry(eventPort)
        options.logger?.invoke("event channel connected to $eventPort from ${eventSocket.localPort}")
        eventChannel =
            EventChannel(eventSocket, sessionKey, ::onMessage, ::onEventChannelClosed) { options.logger?.invoke(it) }

        // Without RECORD the LG plays the item but never sends an event about it.
        control.exchange("RECORD", rtspUri).requireSuccess("RECORD")

        val stream =
            control
                .exchange(
                    "SETUP",
                    rtspUri,
                    body =
                        BinaryPlist.encode(
                            mapOf(
                                "streams" to
                                    listOf(
                                        mapOf(
                                            "type" to STREAM_TYPE,
                                            "controlType" to 1L,
                                            "clientTypeUUID" to URL_STREAM_CLIENT_TYPE,
                                            "channelID" to "${identity.deviceId}-RCS-1",
                                            "clientUUID" to UUID.randomUUID().toString().uppercase(),
                                        ),
                                    ),
                            ),
                        ),
                    contentType = ControlConnection.BPLIST,
                ).requireSuccess("SETUP stream")
        streamId = ((plist(stream)["streams"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("streamID") as? Long
            ?: throw AirkastException.Rejected("SETUP stream without an ID", stream.status)

        if (options.keepAlive) {
            scope.launch {
                while (isActive && !closed.get()) {
                    delay(FEEDBACK_INTERVAL_MILLIS)
                    try {
                        control.exchange("POST", "/feedback")
                    } catch (_: LateReply) {
                        // The LG CX (webOS 04.64.00) once went quiet for over 5 s, 13 minutes into a session, and played on.
                    } catch (e: Exception) {
                        end(e)
                    }
                }
            }
        }
    }

    private fun connectWithRetry(port: Int): Socket {
        var attempt = 0
        while (true) {
            try {
                return connect(InetSocketAddress(receiver.host, port), options)
            } catch (e: AirkastException.Unreachable) {
                // The event port can refuse for a moment right after SETUP names it.
                if (e.cause !is ConnectException || ++attempt >= 5) throw e
                Thread.sleep(200)
            }
        }
    }

    override suspend fun load(item: MediaItem) {
        val id = UUID.randomUUID().toString().uppercase()
        val taken = CompletableDeferred<Unit>()
        itemId = id
        itemTaken = taken
        io {
            command(
                mapOf(
                    "type" to "insertPlayQueueItem",
                    "item" to
                        mapOf(
                            "uuid" to id,
                            "mediaType" to if (item.streaming) "streaming" else "file",
                            "Content-Location" to item.url,
                            "Start-Position" to cmTime(item.startSeconds),
                        ),
                ),
            )
            command(
                mapOf(
                    "type" to "setProperty",
                    "property" to "isInterestedInDateRange",
                    "value" to true,
                    "item" to mapOf("uuid" to id),
                ),
            )
            command(mapOf("type" to "setProperty", "property" to "actionAtItemEnd", "value" to 1L))
            command(mapOf("type" to "setRate", "rate" to 1.0))
        }
        withTimeoutOrNull(options.loadTimeoutMillis) { taken.await() }
            ?: throw AirkastException.Timeout("The receiver did not take the item")
    }

    override suspend fun play() = io { command(mapOf("type" to "setRate", "rate" to 1.0)) }

    override suspend fun pause() = io { command(mapOf("type" to "setRate", "rate" to 0.0)) }

    override suspend fun seek(positionSeconds: Double): Double? {
        val zero = cmTime(0.0)
        val reply =
            request(
                mapOf(
                    "type" to "seek",
                    "time" to cmTime(positionSeconds),
                    "toleranceBefore" to zero,
                    "toleranceAfter" to zero,
                    "item" to mapOf("uuid" to itemId),
                ),
            )
        return seconds(reply["position"])
    }

    override suspend fun playbackInfo(): PlaybackInfo {
        val info = request(mapOf("type" to "playbackInfo"))["info"] as? Map<*, *> ?: emptyMap<String, Any?>()
        return PlaybackInfo(
            state = state(info["playbackState"] as? String),
            rate = (info["rate"] as? Number)?.toDouble() ?: 0.0,
            positionSeconds = seconds(info["position"]),
            durationSeconds = seconds(info["duration"]),
            loaded = ranges(info["loadedTimeRanges"]),
            seekable = ranges(info["seekableTimeRanges"]),
            itemId = (info["item"] as? Map<*, *>)?.get("uuid") as? String,
        )
    }

    override suspend fun selectedMedia(): List<MediaOption> {
        val value = request(mapOf("type" to "property", "property" to "selectedMediaArray"))["value"] as? List<*>
        return value.orEmpty().mapNotNull { option ->
            option as? Map<*, *> ?: return@mapNotNull null
            val kind =
                MediaKind.entries.firstOrNull { it.wire == option["MediaSelectionGroupMediaType"] }
                    ?: return@mapNotNull null
            MediaOption(
                kind = kind,
                id = option["MediaSelectionOptionsPersistentID"] as? Long ?: return@mapNotNull null,
                name = option["MediaSelectionOptionsName"] as? String,
                language = option["MediaSelectionOptionsExtendedLanguageTag"] as? String,
                forced = option["MediaSelectionOptionsForced"] == true,
            )
        }
    }

    override suspend fun selectMedia(selections: List<MediaSelection>) =
        io {
            val value =
                selections.map { selection ->
                    buildMap<String, Any?> {
                        put("MediaSelectionGroupMediaType", selection.kind.wire)
                        selection.id?.let { put("MediaSelectionOptionsPersistentID", it) }
                    }
                }
            command(
                mapOf(
                    "type" to "setProperty",
                    "property" to "selectedMediaArray",
                    "value" to value,
                    "item" to mapOf("uuid" to itemId),
                ),
            )
        }

    override suspend fun volume(): Double? =
        io {
            if (closed.get()) throw AirkastException.Disconnected(null)
            val reply =
                try {
                    control.exchange(
                        "GET_PARAMETER",
                        rtspUri,
                        body = "volume\r\n".toByteArray(),
                        contentType = "text/parameters",
                    )
                } catch (e: LateReply) {
                    throw AirkastException.Timeout(e.message.orEmpty())
                } catch (e: IOException) {
                    end(e)
                    throw AirkastException.Disconnected(e)
                }
            if (reply.status !in 200..299) return@io null
            String(reply.body).substringAfter("volume:", "").trim().toDoubleOrNull()
        }

    override suspend fun stop() = io { command(mapOf("type" to "stop")) }

    override fun close() = end(null)

    private suspend fun request(payload: Map<String, Any?>): Map<String, Any?> {
        val id = nextMessageId.getAndIncrement()
        val reply = CompletableDeferred<Map<String, Any?>>()
        pending[id] = reply
        try {
            io { command(payload + mapOf("kind" to "request", "messageID" to id)) }
            return withTimeoutOrNull(options.requestTimeoutMillis) { reply.await() }
                ?: throw AirkastException.Timeout("No answer to ${payload["type"]}")
        } finally {
            pending.remove(id)
        }
    }

    private fun command(payload: Map<String, Any?>) {
        if (closed.get()) throw AirkastException.Disconnected(null)
        val response =
            try {
                control.exchange(
                    "POST",
                    "/command",
                    ControlConnection.HTTP,
                    headers =
                        listOf(
                            "User-Agent" to COMMAND_USER_AGENT,
                            "X-Apple-ProtocolVersion" to "1",
                            "X-Apple-Session-ID" to commandSessionId,
                            "X-Apple-StreamID" to streamId.toString(),
                        ),
                    body = BinaryPlist.encode(mapOf("params" to mapOf("data" to BinaryPlist.encode(payload)))),
                    contentType = ControlConnection.BPLIST,
                )
            } catch (e: LateReply) {
                throw AirkastException.Timeout("No answer to ${payload["type"]}")
            } catch (e: Exception) {
                end(e)
                throw AirkastException.Disconnected(e)
            }
        options.logger?.invoke("command ${payload["type"]} ${response.status}")
        response.requireSuccess(payload["type"].toString())
    }

    private fun onMessage(message: Map<String, Any?>) {
        options.logger?.invoke("event ${message["kind"] ?: ""} ${message["type"]} ${message["name"] ?: ""}")
        if (message["kind"] == "response") {
            (message["messageID"] as? Long)?.let { pending[it]?.complete(message) }
            return
        }
        val item = (message["item"] as? Map<*, *>)?.get("uuid") as? String
        val event =
            when (message["type"]) {
                "playbackState" -> {
                    ReceiverEvent.StateChanged(state(message["name"] as? String), message["reason"] as? String)
                }

                "sendMediaRemoteCommand" -> {
                    ReceiverEvent.RemoteCommand(
                        message["value"] as? String ?: "",
                        (message["volume"] as? Number)?.toDouble(),
                    )
                }

                "notification" -> {
                    when (message["name"]) {
                        "currentItemChanged" -> {
                            ReceiverEvent.ItemChanged(item, message["reason"] as? String)
                        }

                        "itemPlayedToEnd" -> {
                            ReceiverEvent.ItemEnded(item)
                        }

                        "rateChanged" -> {
                            ReceiverEvent.RateChanged(
                                (message["rate"] as? Number)?.toDouble() ?: 0.0,
                                seconds(message["position"]),
                            )
                        }

                        "timeJumped" -> {
                            ReceiverEvent.TimeJumped(seconds(message["position"]))
                        }

                        else -> {
                            ReceiverEvent.Other(message)
                        }
                    }
                }

                else -> {
                    ReceiverEvent.Other(message)
                }
            }
        if (event is ReceiverEvent.ItemChanged && item != null && item == itemId) itemTaken?.complete(Unit)
        if (event is ReceiverEvent.StateChanged) mutableState.value = event.state
        mutableEvents.tryEmit(event)
    }

    private fun onEventChannelClosed(cause: Throwable?) = end(cause)

    private fun end(cause: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        val error = AirkastException.Disconnected(cause)
        pending.values.forEach { it.completeExceptionally(error) }
        itemTaken?.completeExceptionally(error)
        mutableState.value = PlaybackState.Stopped
        mutableEvents.tryEmit(ReceiverEvent.Disconnected(cause))
        runCatching { eventChannel?.close() }
        runCatching { control.close() }
        runCatching { timing?.close() }
        scope.cancel()
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun HttpMessage.requireSuccess(request: String): HttpMessage {
        if (status !in 200..299) throw AirkastException.Rejected(request, status)
        return this
    }

    companion object {
        private const val STREAM_TYPE = 130L
        private const val URL_STREAM_CLIENT_TYPE = "A6B27562-B43A-4F2D-B75F-82391E250194"
        private const val FEEDBACK_INTERVAL_MILLIS = 2_000L

        /** The version Apple's senders use for `/command`. */
        private const val COMMAND_USER_AGENT = "AirPlay/870.14.1"

        fun open(
            receiver: Receiver,
            identity: SenderIdentity,
            options: SessionOptions,
        ): VideoSession {
            options.logger?.invoke(
                "receiver ${receiver.model} srcvers ${receiver.sourceVersion} " +
                    "features 0x${receiver.features.toString(16)} ${receiver.compatibility}",
            )
            val socket =
                connect(InetSocketAddress(receiver.host, receiver.port), options).apply {
                    soTimeout = options.requestTimeoutMillis.toInt()
                    tcpNoDelay = true
                }
            val control = ControlConnection(socket, identity) { options.logger?.invoke(it) }
            var timing: TimingResponder? = null
            var session: DefaultVideoSession? = null
            try {
                val sessionKey = TransientPairing.pair(control)
                control.encrypt(
                    Hkdf.sha512(sessionKey, "Control-Salt", "Control-Write-Encryption-Key"),
                    Hkdf.sha512(sessionKey, "Control-Salt", "Control-Read-Encryption-Key"),
                )
                options.logger?.invoke("paired")
                if (options.ntpTiming) {
                    timing = TimingResponder(socket.localAddress, socket.inetAddress) { options.logger?.invoke(it) }
                }
                session = DefaultVideoSession(receiver, options, control, timing)
                session.start(identity, sessionKey)
                return session
            } catch (e: Exception) {
                // A session that got as far as its event channel closes that too.
                runCatching { session?.close() }
                runCatching { timing?.close() }
                runCatching { control.close() }
                throw when (e) {
                    is AirkastException -> e
                    is LateReply -> AirkastException.Timeout(e.message.orEmpty())
                    is IOException -> AirkastException.Disconnected(e)
                    else -> AirkastException.UnexpectedReply(e)
                }
            }
        }

        /** A connected socket, or [AirkastException.Unreachable]. */
        private fun connect(
            address: InetSocketAddress,
            options: SessionOptions,
        ): Socket {
            val socket =
                try {
                    options.socketFactory?.createSocket() ?: Socket()
                } catch (e: IOException) {
                    throw AirkastException.Unreachable(e)
                }
            try {
                socket.connect(address, options.connectTimeoutMillis)
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw AirkastException.Unreachable(e)
            }
            return socket
        }

        private fun plist(message: HttpMessage): Map<*, *> =
            if (BinaryPlist.isPlist(message.body)) {
                BinaryPlist.decode(message.body) as? Map<*, *>
                    ?: emptyMap<Any, Any>()
            } else {
                emptyMap<Any, Any>()
            }

        fun cmTime(seconds: Double): Map<String, Any?> =
            mapOf("value" to Math.round(seconds * 1000), "timescale" to 1000L, "flags" to 1L, "epoch" to 0L)

        /** A CMTime's seconds, or null unless it is valid and finite (flags bit 0 set, bit 4 clear). */
        fun seconds(time: Any?): Double? {
            val map = time as? Map<*, *> ?: return null
            val flags = (map["flags"] as? Number)?.toLong() ?: return null
            val timescale = (map["timescale"] as? Number)?.toLong() ?: return null
            val value = (map["value"] as? Number)?.toLong() ?: return null
            if (flags and 1L == 0L || flags and 0x1CL != 0L || timescale <= 0) return null
            return value.toDouble() / timescale
        }

        fun state(name: String?): PlaybackState =
            when (name) {
                "loading" -> PlaybackState.Loading
                "playing" -> PlaybackState.Playing
                "paused" -> PlaybackState.Paused
                "stopped" -> PlaybackState.Stopped
                else -> PlaybackState.Unknown
            }

        private fun ranges(value: Any?): List<TimeRange> =
            (value as? List<*>).orEmpty().mapNotNull { range ->
                val map = range as? Map<*, *> ?: return@mapNotNull null
                TimeRange(
                    seconds(map["start"]) ?: return@mapNotNull null,
                    seconds(map["duration"]) ?: return@mapNotNull null,
                )
            }
    }
}

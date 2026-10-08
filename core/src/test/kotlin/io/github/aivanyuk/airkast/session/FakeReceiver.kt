package io.github.aivanyuk.airkast.session

import io.github.aivanyuk.airkast.crypto.Hkdf
import io.github.aivanyuk.airkast.crypto.SrpClient.Companion.G
import io.github.aivanyuk.airkast.crypto.SrpClient.Companion.N
import io.github.aivanyuk.airkast.crypto.SrpClient.Companion.N_LENGTH
import io.github.aivanyuk.airkast.crypto.SrpClient.Companion.sha512
import io.github.aivanyuk.airkast.crypto.SrpClient.Companion.unsigned
import io.github.aivanyuk.airkast.wire.BinaryPlist
import io.github.aivanyuk.airkast.wire.HttpMessage
import io.github.aivanyuk.airkast.wire.Link
import io.github.aivanyuk.airkast.wire.Tlv8
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A receiver on loopback that pairs transiently, accepts the session and its stream, and answers
 * `/command` the way the LG CX does, with its replies and events on the event channel.
 */
internal class FakeReceiver(
    private val takesItems: Boolean = true,
    private val corruptProof: Boolean = false,
) : AutoCloseable {
    private val control = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val events = ServerSocket(0, 1, InetAddress.getLoopbackAddress())

    @Volatile
    private var recorded = false
    val port: Int get() = control.localPort
    val commands = CopyOnWriteArrayList<Map<String, Any?>>()

    @Volatile
    private var eventLink: Link? = null

    @Volatile
    private var controlLink: Link? = null

    @Volatile
    private var position = 0.0

    init {
        thread(isDaemon = true, name = "fake-receiver") { runCatching { serve() } }
    }

    private fun serve() {
        val link = Link(control.accept()).also { controlLink = it }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes).also { it[0] = 0x55 }
        val x = BigInteger(1, sha512(salt, sha512("Pair-Setup:3939".toByteArray())))
        val v = G.modPow(x, N)
        val b = BigInteger(256, SecureRandom())
        val k = BigInteger(1, sha512(unsigned(N), pad(unsigned(G))))
        val serverPublic = k.multiply(v).add(G.modPow(b, N)).mod(N)
        var sessionKey = ByteArray(0)
        while (true) {
            val request = HttpMessage.read(link.input) ?: return
            val path = request.startLine.split(' ')[1]
            when {
                path == "/pair-pin-start" -> {
                    link.reply(request)
                }

                path == "/pair-setup" -> {
                    val tlv = Tlv8.decode(request.body)
                    if (tlv[Tlv8.SEQUENCE]!![0].toInt() == 1) {
                        link.reply(
                            request,
                            Tlv8.encode(
                                Tlv8.SEQUENCE to byteArrayOf(2),
                                Tlv8.SALT to salt,
                                Tlv8.PUBLIC_KEY to unsigned(serverPublic),
                            ),
                        )
                    } else {
                        val a = tlv.getValue(Tlv8.PUBLIC_KEY)
                        val u = BigInteger(1, sha512(pad(a), pad(unsigned(serverPublic))))
                        val s = BigInteger(1, a).multiply(v.modPow(u, N)).modPow(b, N)
                        sessionKey = sha512(unsigned(s))
                        val proof = sha512(a, tlv.getValue(Tlv8.PROOF), sessionKey)
                        if (corruptProof) proof[0] = (proof[0] + 1).toByte()
                        link.reply(request, Tlv8.encode(Tlv8.SEQUENCE to byteArrayOf(4), Tlv8.PROOF to proof))
                        link.encrypt(
                            Hkdf.sha512(sessionKey, "Control-Salt", "Control-Read-Encryption-Key"),
                            Hkdf.sha512(sessionKey, "Control-Salt", "Control-Write-Encryption-Key"),
                        )
                    }
                }

                request.startLine.startsWith("SETUP") -> {
                    val body = BinaryPlist.decode(request.body) as Map<*, *>
                    if (body.containsKey("streams")) {
                        link.reply(
                            request,
                            BinaryPlist.encode(
                                mapOf("streams" to listOf(mapOf("streamID" to 1L, "type" to 130L))),
                            ),
                        )
                    } else {
                        val key = sessionKey
                        thread(isDaemon = true) {
                            eventLink =
                                Link(events.accept()).apply {
                                    encrypt(
                                        Hkdf.sha512(key, "Events-Salt", "Events-Write-Encryption-Key"),
                                        Hkdf.sha512(key, "Events-Salt", "Events-Read-Encryption-Key"),
                                    )
                                }
                        }
                        link.reply(
                            request,
                            BinaryPlist.encode(
                                mapOf(
                                    "eventPort" to events.localPort.toLong(),
                                    "timingPort" to 0L,
                                ),
                            ),
                        )
                    }
                }

                request.startLine.startsWith("RECORD") -> {
                    recorded = true
                    link.reply(request)
                }

                path == "/command" -> {
                    link.reply(request)
                    val outer = BinaryPlist.decode(request.body) as Map<*, *>

                    @Suppress("UNCHECKED_CAST")
                    val command =
                        BinaryPlist.decode(
                            (outer["params"] as Map<*, *>)["data"] as ByteArray,
                        ) as Map<String, Any?>
                    commands += command
                    respond(command)
                }

                else -> {
                    link.reply(request)
                }
            }
        }
    }

    private fun respond(command: Map<String, Any?>) {
        val id = command["messageID"]
        when (command["type"]) {
            "insertPlayQueueItem" -> {
                if (takesItems && recorded) {
                    val item = command["item"] as Map<*, *>
                    position = DefaultVideoSession.seconds(item["Start-Position"]) ?: 0.0
                    event(
                        mapOf(
                            "type" to "notification",
                            "name" to "currentItemChanged",
                            "item" to mapOf("uuid" to item["uuid"]),
                        ),
                    )
                    event(
                        mapOf("type" to "playbackState", "name" to "playing", "item" to mapOf("uuid" to item["uuid"])),
                    )
                }
            }

            "playbackInfo" -> {
                event(
                    mapOf(
                        "kind" to "response",
                        "type" to "playbackInfo",
                        "messageID" to id,
                        "info" to
                            mapOf(
                                "rate" to 1L,
                                "playbackState" to "playing",
                                "position" to DefaultVideoSession.cmTime(position),
                                "duration" to DefaultVideoSession.cmTime(3077.0),
                                "loadedTimeRanges" to
                                    listOf(
                                        mapOf(
                                            "start" to DefaultVideoSession.cmTime(position),
                                            "duration" to DefaultVideoSession.cmTime(30.0),
                                        ),
                                    ),
                            ),
                    ),
                )
            }

            "seek" -> {
                if (command["item"] != null && command["toleranceBefore"] != null) {
                    position = DefaultVideoSession.seconds(command["time"])!!
                    event(
                        mapOf(
                            "kind" to "response",
                            "type" to "seek",
                            "messageID" to id,
                            "position" to DefaultVideoSession.cmTime(position),
                        ),
                    )
                }
            }

            "setRate" -> {
                event(
                    mapOf(
                        "type" to "playbackState",
                        "name" to if (command["rate"] == 0.0) "paused" else "playing",
                    ),
                )
            }
        }
    }

    /** Sends one event the way the LG does, and waits for the sender's empty 200. */
    fun event(payload: Map<String, Any?>) {
        val link = generateSequence { eventLink ?: Thread.sleep(10).let { null } }.first()
        val body = BinaryPlist.encode(mapOf("params" to mapOf("data" to BinaryPlist.encode(payload))))
        synchronized(link) {
            link.write(
                HttpMessage(
                    "POST /command RTSP/1.0",
                    listOf(
                        "X-Apple-StreamID" to "1",
                        "Content-Type" to "application/x-apple-binary-plist",
                    ),
                    body,
                ).encode(),
            )
            HttpMessage.read(link.input)
        }
    }

    fun dropConnections() {
        runCatching { eventLink?.close() }
        runCatching { controlLink?.close() }
    }

    override fun close() {
        dropConnections()
        control.close()
        events.close()
    }

    private fun Link.reply(
        request: HttpMessage,
        body: ByteArray = ByteArray(0),
    ) = write(
        HttpMessage(
            "${request.requestProtocol} 200 OK",
            listOfNotNull(
                request.header("CSeq")?.let {
                    "CSeq" to it
                },
            ),
            body,
        ).encode(),
    )

    private fun pad(bytes: ByteArray): ByteArray =
        if (bytes.size >=
            N_LENGTH
        ) {
            bytes
        } else {
            ByteArray(N_LENGTH - bytes.size) + bytes
        }
}

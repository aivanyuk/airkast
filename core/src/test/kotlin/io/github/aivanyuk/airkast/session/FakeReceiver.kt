package io.github.aivanyuk.airkast.session

import io.github.aivanyuk.airkast.crypto.ChaCha20Poly1305
import io.github.aivanyuk.airkast.crypto.Ed25519
import io.github.aivanyuk.airkast.crypto.Hkdf
import io.github.aivanyuk.airkast.crypto.SrpClient.Companion.G
import io.github.aivanyuk.airkast.crypto.SrpClient.Companion.N
import io.github.aivanyuk.airkast.crypto.SrpClient.Companion.N_LENGTH
import io.github.aivanyuk.airkast.crypto.SrpClient.Companion.sha512
import io.github.aivanyuk.airkast.crypto.SrpClient.Companion.unsigned
import io.github.aivanyuk.airkast.crypto.X25519
import io.github.aivanyuk.airkast.wire.BinaryPlist
import io.github.aivanyuk.airkast.wire.HttpMessage
import io.github.aivanyuk.airkast.wire.Link
import io.github.aivanyuk.airkast.wire.Tlv8
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val RANDOM = SecureRandom()

/**
 * A receiver on loopback that pairs transiently or with a PIN, accepts the session and its stream,
 * and answers `/command` the way the LG CX does, with its replies and events on the event channel.
 */
internal class FakeReceiver(
    private val takesItems: Boolean = true,
    private val corruptProof: Boolean = false,
    /** Milliseconds to wait before the first answer to a path or a command type. */
    lateAnswers: Map<String, Long> = emptyMap(),
    /** A path or command type that the receiver goes silent at, for good. */
    private val hangsAt: String? = null,
    /** The PIN it shows for a pairing that is not transient. */
    val pin: String = "2468",
) : AutoCloseable {
    private val longTermSeed = ByteArray(32).also(RANDOM::nextBytes)
    val receiverId: String = UUID.randomUUID().toString().uppercase()

    /** The senders it paired with, by id, and their long-term public keys. */
    private val senders = ConcurrentHashMap<String, ByteArray>()

    /** Whether a pairing asked it to show its PIN. */
    @Volatile
    var pinShown = false
        private set

    /** An error status that `/pair-verify` answers with, as a busy receiver might, or null to verify. */
    @Volatile
    var verifyStatus: Int? = null

    /** Whether a sender proved a pairing with pair-verify. */
    @Volatile
    var verified = false
        private set

    private val lateAnswers = lateAnswers.toMutableMap()
    private val control = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val events = ServerSocket(0, 1, InetAddress.getLoopbackAddress())

    @Volatile
    private var recorded = false
    val port: Int get() = control.localPort
    val commands = CopyOnWriteArrayList<Map<String, Any?>>()

    /** The sender's name from the base SETUP's `X-Apple-Client-Name`, read as UTF-8. */
    @Volatile
    var clientName: String? = null
        private set

    @Volatile
    var feedbacks = 0
        private set

    @Volatile
    private var eventLink: Link? = null

    @Volatile
    private var controlLink: Link? = null

    @Volatile
    private var position = Duration.ZERO

    /** The LG reports a paused `streaming` item as `loading`, and a paused `file` item as `paused`. */
    @Volatile
    private var streaming = false

    init {
        thread(isDaemon = true, name = "fake-receiver") { runCatching { serve() } }
    }

    private fun serve() {
        // A pairing with a PIN takes one connection, and the session after it another.
        while (true) {
            val link = Link(control.accept()).also { controlLink = it }
            runCatching { serve(link) }
            runCatching { link.close() }
        }
    }

    private fun serve(link: Link) {
        var srp: SrpServer? = null
        var sessionKey = ByteArray(0)
        var verifyPrivate = ByteArray(0)
        var senderPublic = ByteArray(0)
        while (true) {
            val request = HttpMessage.read(link.input) ?: return
            val path = request.startLine.split(' ')[1]
            when {
                path == "/pair-pin-start" -> {
                    if (request.header("X-Apple-HKP") == "3") pinShown = true
                    link.reply(request)
                }

                path == "/pair-setup" -> {
                    val tlv = Tlv8.decode(request.body)
                    when (tlv.getValue(Tlv8.SEQUENCE)[0].toInt()) {
                        1 -> {
                            val transient = (tlv[Tlv8.FLAGS]?.get(0)?.toInt() ?: 0) and Tlv8.FLAG_TRANSIENT != 0
                            val server = SrpServer(if (transient) "3939" else pin, transient).also { srp = it }
                            link.reply(
                                request,
                                Tlv8.encode(
                                    Tlv8.SEQUENCE to byteArrayOf(2),
                                    Tlv8.SALT to server.salt,
                                    Tlv8.PUBLIC_KEY to unsigned(server.public),
                                ),
                            )
                        }

                        3 -> {
                            val server = srp!!
                            val a = tlv.getValue(Tlv8.PUBLIC_KEY)
                            val clientProof = tlv.getValue(Tlv8.PROOF)
                            val key = server.sessionKey(a, clientProof)
                            if (key == null) {
                                link.reply(
                                    request,
                                    Tlv8.encode(Tlv8.SEQUENCE to byteArrayOf(4), Tlv8.ERROR to byteArrayOf(2)),
                                )
                                continue
                            }
                            sessionKey = key
                            val proof = sha512(a, clientProof, sessionKey)
                            if (corruptProof) proof[0] = (proof[0] + 1).toByte()
                            link.reply(request, Tlv8.encode(Tlv8.SEQUENCE to byteArrayOf(4), Tlv8.PROOF to proof))
                            if (server.transient) link.encryptControl(sessionKey)
                        }

                        5 -> {
                            link.reply(request, exchangeLongTermKeys(sessionKey, tlv.getValue(Tlv8.ENCRYPTED_DATA)))
                        }
                    }
                }

                path == "/pair-verify" && verifyStatus != null -> {
                    link.write(HttpMessage("${request.requestProtocol} $verifyStatus Busy", emptyList()).encode())
                }

                path == "/pair-verify" -> {
                    val tlv = Tlv8.decode(request.body)
                    if (tlv.getValue(Tlv8.SEQUENCE)[0].toInt() == 1) {
                        senderPublic = tlv.getValue(Tlv8.PUBLIC_KEY)
                        verifyPrivate = ByteArray(32).also(RANDOM::nextBytes)
                        val public = X25519.publicKey(verifyPrivate)
                        sessionKey = X25519.sharedSecret(verifyPrivate, senderPublic)
                        val inner =
                            Tlv8.encode(
                                Tlv8.IDENTIFIER to receiverId.toByteArray(),
                                Tlv8.SIGNATURE to
                                    Ed25519.sign(longTermSeed, public + receiverId.toByteArray() + senderPublic),
                            )
                        link.reply(
                            request,
                            Tlv8.encode(
                                Tlv8.SEQUENCE to byteArrayOf(2),
                                Tlv8.PUBLIC_KEY to public,
                                Tlv8.ENCRYPTED_DATA to
                                    ChaCha20Poly1305.seal(
                                        verifyKey(sessionKey),
                                        nonce("PV-Msg02"),
                                        inner,
                                        ByteArray(0),
                                    ),
                            ),
                        )
                    } else {
                        val inner =
                            Tlv8.decode(
                                ChaCha20Poly1305.open(
                                    verifyKey(sessionKey),
                                    nonce("PV-Msg03"),
                                    tlv.getValue(Tlv8.ENCRYPTED_DATA),
                                    ByteArray(0),
                                ),
                            )
                        val senderId = inner.getValue(Tlv8.IDENTIFIER)
                        val known = senders[String(senderId)]
                        val signed = senderPublic + senderId + X25519.publicKey(verifyPrivate)
                        if (known == null || !Ed25519.verify(known, signed, inner.getValue(Tlv8.SIGNATURE))) {
                            link.reply(
                                request,
                                Tlv8.encode(
                                    Tlv8.SEQUENCE to byteArrayOf(4),
                                    Tlv8.ERROR to byteArrayOf(2),
                                ),
                            )
                        } else {
                            verified = true
                            link.reply(request, Tlv8.encode(Tlv8.SEQUENCE to byteArrayOf(4)))
                            link.encryptControl(sessionKey)
                        }
                    }
                }

                request.startLine.startsWith("SETUP") -> {
                    request.header("X-Apple-Client-Name")?.let {
                        clientName = String(it.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                    }
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
                    val outer = BinaryPlist.decode(request.body) as Map<*, *>

                    @Suppress("UNCHECKED_CAST")
                    val command =
                        BinaryPlist.decode(
                            (outer["params"] as Map<*, *>)["data"] as ByteArray,
                        ) as Map<String, Any?>
                    commands += command
                    stall(command["type"] as String)
                    link.reply(request)
                    respond(command)
                }

                else -> {
                    if (path == "/feedback") feedbacks++
                    stall(path)
                    link.reply(request)
                }
            }
        }
    }

    private fun stall(key: String) {
        if (key == hangsAt) Thread.sleep(Long.MAX_VALUE)
        lateAnswers.remove(key)?.let(Thread::sleep)
    }

    private fun respond(command: Map<String, Any?>) {
        val id = command["messageID"]
        when (command["type"]) {
            "insertPlayQueueItem" -> {
                if (takesItems && recorded) {
                    val item = command["item"] as Map<*, *>
                    streaming = item["mediaType"] == "streaming"
                    position = DefaultVideoSession.duration(item["Start-Position"]) ?: Duration.ZERO
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
                                "duration" to DefaultVideoSession.cmTime(3077.seconds),
                                "loadedTimeRanges" to
                                    listOf(
                                        mapOf(
                                            "start" to DefaultVideoSession.cmTime(position),
                                            "duration" to DefaultVideoSession.cmTime(30.seconds),
                                        ),
                                    ),
                            ),
                    ),
                )
            }

            "seek" -> {
                if (command["item"] != null && command["toleranceBefore"] != null) {
                    position = DefaultVideoSession.duration(command["time"])!!
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
                        "name" to
                            when {
                                command["rate"] != 0.0 -> "playing"
                                streaming -> "loading"
                                else -> "paused"
                            },
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

    /** Forgets every sender it paired with, as a receiver reset would. */
    fun forgetSenders() = senders.clear()

    /** Pair-setup M5 to M6: checks the sender's long-term key, keeps it, and answers with its own. */
    private fun exchangeLongTermKeys(
        secret: ByteArray,
        encrypted: ByteArray,
    ): ByteArray {
        val key = Hkdf.sha512(secret, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
        val m5 = Tlv8.decode(ChaCha20Poly1305.open(key, nonce("PS-Msg05"), encrypted, ByteArray(0)))
        val senderId = m5.getValue(Tlv8.IDENTIFIER)
        val senderKey = m5.getValue(Tlv8.PUBLIC_KEY)
        val senderX = Hkdf.sha512(secret, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
        check(Ed25519.verify(senderKey, senderX + senderId + senderKey, m5.getValue(Tlv8.SIGNATURE)))
        senders[String(senderId)] = senderKey
        val id = receiverId.toByteArray()
        val public = Ed25519.publicKey(longTermSeed)
        val receiverX = Hkdf.sha512(secret, "Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info")
        val m6 =
            Tlv8.encode(
                Tlv8.IDENTIFIER to id,
                Tlv8.PUBLIC_KEY to public,
                Tlv8.SIGNATURE to Ed25519.sign(longTermSeed, receiverX + id + public),
            )
        return Tlv8.encode(
            Tlv8.SEQUENCE to byteArrayOf(6),
            Tlv8.ENCRYPTED_DATA to ChaCha20Poly1305.seal(key, nonce("PS-Msg06"), m6, ByteArray(0)),
        )
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

    private fun Link.encryptControl(secret: ByteArray) =
        encrypt(
            Hkdf.sha512(secret, "Control-Salt", "Control-Read-Encryption-Key"),
            Hkdf.sha512(secret, "Control-Salt", "Control-Write-Encryption-Key"),
        )

    private fun verifyKey(secret: ByteArray) =
        Hkdf.sha512(secret, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")

    private fun nonce(label: String) = ByteArray(4) + label.toByteArray()

    /** The server half of SRP-6a: checks the sender's proof, which a wrong PIN fails. */
    private inner class SrpServer(
        password: String,
        val transient: Boolean,
    ) {
        val salt = ByteArray(16).also(RANDOM::nextBytes).also { it[0] = 0x55 }
        private val v = G.modPow(BigInteger(1, sha512(salt, sha512("Pair-Setup:$password".toByteArray()))), N)
        private val b = BigInteger(256, RANDOM)
        val public: BigInteger =
            BigInteger(1, sha512(unsigned(N), pad(unsigned(G))))
                .multiply(v)
                .add(G.modPow(b, N))
                .mod(N)

        /** K, or null when [clientProof] does not prove the same password. */
        fun sessionKey(
            a: ByteArray,
            clientProof: ByteArray,
        ): ByteArray? {
            val u = BigInteger(1, sha512(pad(a), pad(unsigned(public))))
            val key = sha512(unsigned(BigInteger(1, a).multiply(v.modPow(u, N)).modPow(b, N)))
            val hn = sha512(unsigned(N))
            val hg = sha512(unsigned(G))
            val hng = ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
            val expected =
                sha512(
                    unsigned(BigInteger(1, hng)),
                    sha512("Pair-Setup".toByteArray()),
                    salt,
                    a,
                    unsigned(public),
                    key,
                )
            return if (expected.contentEquals(clientProof)) key else null
        }
    }

    private fun pad(bytes: ByteArray): ByteArray =
        if (bytes.size >=
            N_LENGTH
        ) {
            bytes
        } else {
            ByteArray(N_LENGTH - bytes.size) + bytes
        }
}

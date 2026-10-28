package io.github.aivanyuk.airkast.session

import com.google.common.truth.Truth.assertThat
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.Airkast.Logger.Level
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.SenderIdentity
import io.github.aivanyuk.airkast.wire.BinaryPlist
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Asks real receivers what they report about their audio latency, without playing anything:
 * `AIRKAST_PROBE_RECEIVERS=192.168.1.30:36617,192.168.1.40:7000 ./gradlew :core:test --tests '*AudioProbeTest'`,
 * each address the one the receiver's `_airplay._tcp` record names. Skipped without one.
 *
 * For each receiver it prints `GET /info`'s `audioLatencies`, the base SETUP's reply, RECORD's
 * reply headers, where a receiver may name an `Audio-Latency` in frames, and the stream SETUP's
 * reply, then tears the session down. The steps and their order are `DefaultAudioStream.start`'s,
 * since the Samsung HW-Q990B plays nothing in another order. No audio packet goes out: the
 * receiver switches to AirPlay, gets silence for two seconds, and goes back. It pairs transiently,
 * so a receiver that asks for a PIN or password fails at pairing, which the probe prints and goes
 * on to the next receiver. The multi-room design (docs/design/multi-room-audio.md) names this as
 * its first step.
 */
class AudioProbeTest {
    private val targets =
        System
            .getenv("AIRKAST_PROBE_RECEIVERS")
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private val logger = Airkast.Logger.println(Level.Verbose)
    private val identity = SenderIdentity()
    private val options =
        SessionOptions(
            connectTimeout = 5.seconds,
            requestTimeout = 5.seconds,
            approvalTimeout = 60.seconds,
            loadTimeout = 10.seconds,
            keepAlive = false,
            ntpTiming = true,
            audioLatency = 1.seconds,
            socketFactory = null,
            resolvers = emptyList(),
            logger = logger,
            eventListener = null,
            credentials = null,
        )
    private val random = SecureRandom()

    @Test
    fun printsWhatReceiversReportAboutTheirLatency() {
        assumeTrue("Set AIRKAST_PROBE_RECEIVERS to run against real receivers", targets.isNotEmpty())
        val failures =
            targets.mapNotNull { target ->
                try {
                    probe(target)
                    null
                } catch (e: Exception) {
                    say(target, "failed: $e")
                    e.printStackTrace()
                    "$target: $e"
                }
            }
        assertThat(failures).isEmpty()
    }

    private fun probe(target: String) {
        val typedIn = Receiver("probe", target.substringBefore(':'), target.substringAfter(':', "7000").toInt())
        val log = Log(logger, "probe")
        val paired = Opening.pair(typedIn, identity, options)
        val control = paired.control
        control.use {
            TimingResponder(control.localAddress, control.remoteAddress, Log(logger, "timing")).use { timing ->
                // The LG CX, the Samsung HW-Q990B and a Mac answer this before pairing too; after
                // it saves a connection.
                val info =
                    Opening.plist(
                        control
                            .exchange("GET", "/info", body = INFO_QUALIFIER, contentType = ControlConnection.BPLIST)
                            .requireSuccess("GET /info"),
                    )
                val receiver = Receiver(typedIn.name, typedIn.host, typedIn.port, Receiver.propertiesFromInfo(info))
                say(target, "${receiver.model} srcvers ${receiver.sourceVersion}, audio ${receiver.audioCompatibility}")
                say(target, "GET /info audioLatencies: ${render(info["audioLatencies"])}")
                info.keys
                    .filter { it.toString().contains("latenc", ignoreCase = true) && it != "audioLatencies" }
                    .forEach { say(target, "GET /info $it: ${render(info[it])}") }

                val uri = "rtsp://${control.localAddress.hostAddress}/${random.nextInt() and Int.MAX_VALUE}"
                val session = UUID.randomUUID().toString().uppercase()
                val base = Opening.setUp(control, uri, Opening.sessionBody(identity, session, timing), options, log)
                say(target, "SETUP reply: ${render(base)}")
                (base["timingPort"] as? Long)?.takeIf { it > 0 }?.let { timing.reach(it.toInt()) }
                val eventPort = (base["eventPort"] as? Long)?.toInt() ?: error("SETUP without an event port")
                EventChannel(
                    Opening.connectEvents(receiver, eventPort, options, log),
                    paired.sessionKey,
                    { say(target, "event: ${render(it)}") },
                    { say(target, "event channel closed: $it") },
                    Log(logger, "events"),
                ).use {
                    val seq = random.nextInt() and 0xffff
                    val rtpTime = random.nextInt().toLong() and 0xffffffffL
                    val record =
                        control.exchange(
                            "RECORD",
                            uri,
                            headers =
                                listOf(
                                    "X-Apple-ProtocolVersion" to "1",
                                    "Range" to "npt=0-",
                                    "RTP-Info" to "seq=$seq;rtptime=$rtpTime",
                                ),
                        )
                    say(target, "RECORD ${record.status}, headers: ${render(record.headers)}")
                    say(target, "RECORD Audio-Latency: ${record.header("Audio-Latency") ?: "none"}")
                    record.requireSuccess("RECORD")

                    DatagramSocket(InetSocketAddress(control.localAddress, 0)).use { controlSocket ->
                        val stream =
                            control.exchange(
                                "SETUP",
                                uri,
                                body = streamBody(controlSocket.localPort),
                                contentType = ControlConnection.BPLIST,
                            )
                        say(target, "stream SETUP ${stream.status}, headers: ${render(stream.headers)}")
                        say(target, "stream SETUP reply: ${render(Opening.plist(stream))}")
                        stream.requireSuccess("SETUP stream")

                        // The receiver asks for the time meanwhile, which the timing log shows.
                        Thread.sleep(2_000)
                        val teardown =
                            control.exchange(
                                "TEARDOWN",
                                uri,
                                body = BinaryPlist.encode(emptyMap<String, Any?>()),
                                contentType = ControlConnection.BPLIST,
                            )
                        say(target, "TEARDOWN ${teardown.status}")
                    }
                }
            }
        }
    }

    /** The stream SETUP `DefaultAudioStream` sends, for 44.1 kHz ALAC, with a key that seals nothing. */
    private fun streamBody(controlPort: Int): ByteArray =
        BinaryPlist.encode(
            mapOf(
                "streams" to
                    listOf(
                        mapOf(
                            "type" to 96L,
                            "ct" to 2L,
                            "audioFormat" to 0x40000L,
                            "audioMode" to "default",
                            "spf" to 352L,
                            "sr" to 44_100L,
                            "latencyMin" to 44_100L / 4,
                            "latencyMax" to 44_100L * 2,
                            "shk" to ByteArray(32).also(random::nextBytes),
                            "controlPort" to controlPort.toLong(),
                            "isMedia" to true,
                            "supportsDynamicStreamID" to false,
                            "streamConnectionID" to (random.nextInt().toLong() and 0xffffffffL),
                        ),
                    ),
            ),
        )

    private fun say(
        target: String,
        message: String,
    ) = println("probe $target: $message")

    private fun render(value: Any?): String =
        when (value) {
            null -> "none"
            is Map<*, *> -> value.entries.joinToString(", ", "{", "}") { "${it.key}: ${render(it.value)}" }
            is List<*> -> value.joinToString(", ", "[", "]") { render(it) }
            is Pair<*, *> -> "${value.first}: ${value.second}"
            is ByteArray -> "<${value.size} bytes>"
            else -> value.toString()
        }

    private companion object {
        val INFO_QUALIFIER = BinaryPlist.encode(mapOf("qualifier" to listOf("txtAirPlay")))
    }
}

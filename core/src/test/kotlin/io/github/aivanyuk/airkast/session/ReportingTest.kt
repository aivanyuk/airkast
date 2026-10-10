package io.github.aivanyuk.airkast.session

import com.google.common.truth.Truth.assertThat
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.Airkast.Event
import io.github.aivanyuk.airkast.Airkast.Logger.Level
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.Compatibility
import io.github.aivanyuk.airkast.Media
import io.github.aivanyuk.airkast.Receiver
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What a client tells its [Airkast.logger] and its [Airkast.eventListener]. */
class ReportingTest {
    private val events = CopyOnWriteArrayList<Event>()
    private val lines = CopyOnWriteArrayList<Line>()

    private class Line(
        val level: Level,
        val tag: String,
        val message: String,
    )

    private fun airkast(threshold: Level = Level.Verbose) =
        Airkast {
            keepAlive = false
            loadTimeout = 1.seconds
            requestTimeout = 2.seconds
            logger =
                object : Airkast.Logger {
                    override val minLevel = threshold

                    override fun log(
                        level: Level,
                        tag: String,
                        message: String,
                        error: Throwable?,
                    ) {
                        lines += Line(level, tag, message)
                    }
                }
            eventListener = { events += it }
        }

    @Test
    fun aCastReportsItsConnectItsLoadAndItsEnd() =
        runBlocking {
            FakeReceiver().use { fake ->
                val receiver = Receiver("fake", "127.0.0.1", fake.port)
                airkast().connect(receiver).use { it.load(Media("https://example.com/a.m3u8")) }
                assertThat(events.map { it::class })
                    .containsExactly(Event.Connected::class, Event.Loaded::class, Event.SessionEnded::class)
                    .inOrder()
                assertThat(events.map { it.receiver }.distinct()).containsExactly(receiver)
                val connected = events[0] as Event.Connected
                assertThat(connected.pairing).isEqualTo(Event.Pairing.Transient)
                assertThat(connected.took).isGreaterThan(Duration.ZERO)
                assertThat((events[2] as Event.SessionEnded).failure).isNull()
            }
        }

    @Test
    fun aLoadTheReceiverNeverTakesFails() =
        runBlocking {
            FakeReceiver(takesItems = false).use { fake ->
                airkast().connect(Receiver("fake", "127.0.0.1", fake.port)).use { session ->
                    runCatching { session.load(Media("https://example.com/a.m3u8")) }
                }
                val failed = events.filterIsInstance<Event.LoadFailed>().single()
                assertThat(failed.failure).isInstanceOf(AirkastException.Timeout::class.java)
                assertThat(failed.took).isAtLeast(1.seconds)
            }
        }

    @Test
    fun aSessionTheReceiverDropsEndsWithItsFailure() =
        runBlocking {
            FakeReceiver().use { fake ->
                airkast().connect(Receiver("fake", "127.0.0.1", fake.port))
                fake.dropConnections()
                withTimeout(2_000) { while (events.none { it is Event.SessionEnded }) delay(10) }
                val ended = events.filterIsInstance<Event.SessionEnded>().single()
                assertThat(ended.failure).isInstanceOf(AirkastException.Disconnected::class.java)
                assertThat(lines.any { it.level == Level.Error && it.tag == "session" }).isTrue()
            }
        }

    @Test
    fun anUnreachableReceiverFailsTheConnectAndNoSessionEnds() {
        val port = ServerSocket(0).use { it.localPort }
        runCatching { runBlocking { airkast().connect(Receiver("off", "127.0.0.1", port)) } }
        val failed = events.single() as Event.ConnectFailed
        assertThat(failed.failure).isInstanceOf(AirkastException.Unreachable::class.java)
    }

    @Test
    fun aPinPairingIsTimedApartFromTheConnect() =
        runBlocking {
            FakeReceiver().use { fake ->
                airkast()
                    .connect(asksForAPin(fake)) {
                        delay(1_000)
                        fake.pin
                    }.close()
                assertThat(events.map { it::class })
                    .containsExactly(Event.Paired::class, Event.Connected::class, Event.SessionEnded::class)
                    .inOrder()
                val paired = events[0] as Event.Paired
                val connected = events[1] as Event.Connected
                assertThat(paired.took).isAtLeast(1.seconds)
                assertThat(connected.pairing).isEqualTo(Event.Pairing.Verified)
                assertThat(connected.took).isLessThan(paired.took)
            }
        }

    @Test
    fun aWrongPinIsReported() =
        runBlocking {
            FakeReceiver().use { fake ->
                runCatching { airkast().pair(Receiver("fake", "127.0.0.1", fake.port)) { "0000" } }
                val failed = events.single() as Event.PairingFailed
                assertThat(failed.failure).isInstanceOf(AirkastException.SecretRejected::class.java)
            }
        }

    @Test
    fun aPasswordTypedWhenTheSessionStartsIsLeftOutOfTheConnectTime() =
        runBlocking {
            FakeReceiver(digestPassword = "hunter2").use { fake ->
                airkast()
                    .connect(Receiver("fake", "127.0.0.1", fake.port)) {
                        delay(1_000)
                        "hunter2"
                    }.close()
                val connected = events.first() as Event.Connected
                assertThat(connected.pairing).isEqualTo(Event.Pairing.Transient)
                assertThat(connected.took).isLessThan(1.seconds)
            }
        }

    @Test
    fun credentialsTheReceiverRefusesAreReportedAsDropped() =
        runBlocking {
            FakeReceiver().use { fake ->
                val airkast = airkast()
                val receiver = asksForAPin(fake)
                airkast.pair(receiver) { fake.pin }
                fake.forgetSenders()
                runCatching { airkast.connect(receiver) }
                assertThat(events.map { it::class })
                    .containsExactly(Event.Paired::class, Event.CredentialsDropped::class, Event.ConnectFailed::class)
                    .inOrder()
            }
        }

    @Test
    fun aLoggerHearsOnlyItsLevelAndAbove() =
        runBlocking {
            FakeReceiver().use { fake ->
                airkast(Level.Info).connect(Receiver("fake", "127.0.0.1", fake.port)).use {
                    it.load(Media("https://example.com/a.m3u8"))
                }
                assertThat(lines.map { it.level }.distinct()).containsExactly(Level.Info)
                assertThat(lines.map { it.tag }).containsExactly("connect", "session", "session").inOrder()
            }
        }

    @Test
    fun everyPartTagsItsLinesAndNoneHoldsTheMediaUrl() =
        runBlocking {
            FakeReceiver().use { fake ->
                airkast().connect(Receiver("fake", "127.0.0.1", fake.port)).use { session ->
                    session.load(Media("https://example.com/private/a.m3u8"))
                    session.playbackInfo()
                }
                assertThat(lines.map { it.tag }.toSet())
                    .containsAtLeast("connect", "pairing", "control", "events", "session")
                assertThat(lines.filter { "private" in it.message }).isEmpty()
                assertThat(lines.single { "playbackInfo" in it.message && it.tag == "session" }.level)
                    .isEqualTo(Level.Verbose)
            }
        }

    @Test
    fun aLoggerAndAListenerThatThrowLeaveTheSessionAlone() =
        runBlocking {
            FakeReceiver().use { fake ->
                val airkast =
                    Airkast {
                        keepAlive = false
                        logger = Airkast.Logger { _, _, _, _ -> error("logger") }
                        eventListener = { error("listener") }
                    }
                airkast.connect(Receiver("fake", "127.0.0.1", fake.port)).use { session ->
                    session.load(Media("https://example.com/a.m3u8"))
                    session.pause()
                }
                assertThat(fake.commands.last()["type"]).isEqualTo("setRate")
            }
        }

    /** [fake] as discovery would describe a receiver that asks for a PIN. */
    private fun asksForAPin(fake: FakeReceiver) =
        Receiver(
            "fake",
            "127.0.0.1",
            fake.port,
            mapOf("features" to "0x7F8AD0,0x38BCB46", "flags" to "0x8", "deviceid" to fake.receiverId),
        ).also { check(it.compatibility == Compatibility.NeedsPin) }
}

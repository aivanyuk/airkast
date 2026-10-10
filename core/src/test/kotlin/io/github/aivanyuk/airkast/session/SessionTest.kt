package io.github.aivanyuk.airkast.session

import com.google.common.truth.Truth.assertThat
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.Media
import io.github.aivanyuk.airkast.PlaybackState
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.ReceiverEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SessionTest {
    private val airkast =
        Airkast {
            keepAlive = false
            loadTimeout = 1.seconds
            requestTimeout = 2.seconds
        }

    @Test
    fun loadsPollsSeeksAndStops() =
        runBlocking {
            FakeReceiver().use { fake ->
                airkast.connect(Receiver("fake", "127.0.0.1", fake.port)).use { session ->
                    session.load(Media("https://example.com/master.m3u8", startAt = 10.minutes))
                    val insert = fake.commands.first { it["type"] == "insertPlayQueueItem" }
                    val item = insert["item"] as Map<*, *>
                    assertThat(DefaultVideoSession.duration(item["Start-Position"])).isEqualTo(10.minutes)
                    assertThat(item["mediaType"]).isEqualTo("streaming")
                    assertThat(fake.commands.map { it["type"] })
                        .containsExactly("insertPlayQueueItem", "setProperty", "setProperty", "setRate")
                        .inOrder()

                    val info = session.playbackInfo()
                    assertThat(info.state).isEqualTo(PlaybackState.Playing)
                    assertThat(info.position).isEqualTo(10.minutes)
                    assertThat(info.duration).isEqualTo(3077.seconds)
                    assertThat(info.buffered.single()).isEqualTo(10.minutes..10.minutes + 30.seconds)

                    assertThat(session.seek(20.minutes)).isEqualTo(20.minutes)
                    val seek = fake.commands.last { it["type"] == "seek" }
                    assertThat((seek["item"] as Map<*, *>)["uuid"]).isEqualTo(item["uuid"])

                    session.pause()
                    withTimeout(2_000) { session.state.first { it == PlaybackState.Paused } }
                    session.stop()
                    assertThat(fake.commands.last()["type"]).isEqualTo("stop")
                }
            }
        }

    @Test
    fun aReceiverThatNamesTheNewItemOnlyInOtherNotificationsTakesItAllTheSame() =
        runBlocking<Unit> {
            FakeReceiver(namesTheNewItem = false).use { fake ->
                airkast.connect(Receiver("fake", "127.0.0.1", fake.port)).use { session ->
                    session.load(Media("https://example.com/a.m3u8"))
                    withTimeout(2_000) { session.state.first { it == PlaybackState.Playing } }
                }
            }
        }

    @Test
    fun aSilentReceiverTimesTheLoadOut() =
        runBlocking {
            FakeReceiver(takesItems = false).use { fake ->
                airkast.connect(Receiver("fake", "127.0.0.1", fake.port)).use { session ->
                    val error = runCatching { session.load(Media("https://example.com/a.m3u8")) }.exceptionOrNull()
                    assertThat(error).isInstanceOf(AirkastException.Timeout::class.java)
                }
            }
        }

    @Test
    fun aDroppedConnectionEndsTheSession() =
        runBlocking {
            FakeReceiver().use { fake ->
                val session = airkast.connect(Receiver("fake", "127.0.0.1", fake.port))
                val ended = async { session.events.first { it is ReceiverEvent.Disconnected } }
                yield()
                fake.dropConnections()
                withTimeout(2_000) { ended.await() }
                val error = runCatching { session.play() }.exceptionOrNull()
                assertThat(error).isInstanceOf(AirkastException.Disconnected::class.java)
            }
        }

    @Test
    fun eventsEndWithDisconnectedAndALateCollectorHearsItToo() =
        runBlocking {
            FakeReceiver().use { fake ->
                val session = airkast.connect(Receiver("fake", "127.0.0.1", fake.port))
                val heard = async { session.events.toList() }
                yield()
                session.load(Media("https://example.com/a.m3u8"))
                session.close()
                val events = withTimeout(2_000) { heard.await() }
                assertThat(events.last()).isInstanceOf(ReceiverEvent.Disconnected::class.java)
                assertThat(events.count { it is ReceiverEvent.Disconnected }).isEqualTo(1)
                assertThat(events.any { it is ReceiverEvent.ItemChanged }).isTrue()
                val late = withTimeout(2_000) { session.events.toList() }
                assertThat(late).hasSize(1)
                assertThat(late.single()).isInstanceOf(ReceiverEvent.Disconnected::class.java)
            }
        }

    @Test
    fun aLateFeedbackAnswerKeepsTheSession() =
        runBlocking {
            FakeReceiver(lateAnswers = mapOf("/feedback" to 900)).use { fake ->
                val patient =
                    airkast.copy {
                        keepAlive = true
                        requestTimeout = 500.milliseconds
                    }
                patient.connect(Receiver("fake", "127.0.0.1", fake.port)).use { session ->
                    session.load(Media("https://example.com/a.m3u8"))
                    withTimeout(8_000) { while (fake.feedbacks < 2) delay(50) }
                    assertThat(session.playbackInfo().position).isEqualTo(Duration.ZERO)
                    session.pause()
                    assertThat(fake.commands.last()["type"]).isEqualTo("setRate")
                    assertThat(session.state.value).isNotEqualTo(PlaybackState.Stopped)
                }
            }
        }

    @Test
    fun aLateCommandAnswerTimesOutAndTheSessionLivesOn() =
        runBlocking {
            FakeReceiver(lateAnswers = mapOf("stop" to 900)).use { fake ->
                val quick = airkast.copy { requestTimeout = 500.milliseconds }
                quick.connect(Receiver("fake", "127.0.0.1", fake.port)).use { session ->
                    session.load(Media("https://example.com/a.m3u8"))
                    val error = runCatching { session.stop() }.exceptionOrNull()
                    assertThat(error).isInstanceOf(AirkastException.Timeout::class.java)
                    session.play()
                    assertThat(
                        fake.commands.map { it["type"] }.takeLast(2),
                    ).containsExactly("stop", "setRate").inOrder()
                }
            }
        }

    @Test
    fun aReceiverThatGoesSilentEndsTheSession() =
        runBlocking {
            FakeReceiver(hangsAt = "/feedback").use { fake ->
                val patient =
                    airkast.copy {
                        keepAlive = true
                        requestTimeout = 500.milliseconds
                    }
                val session = patient.connect(Receiver("fake", "127.0.0.1", fake.port))
                val ended = withTimeout(10_000) { session.events.first { it is ReceiverEvent.Disconnected } }
                assertThat((ended as ReceiverEvent.Disconnected).cause).hasMessageThat().contains("earlier request")
            }
        }

    @Test
    fun aWrongServerProofFailsThePairing() {
        FakeReceiver(corruptProof = true).use { fake ->
            assertThrows(AirkastException.PairingFailed::class.java) {
                runBlocking { airkast.connect(Receiver("fake", "127.0.0.1", fake.port)) }
            }
        }
    }

    @Test
    fun aReceiverThatIsOffIsUnreachable() {
        val port = ServerSocket(0).use { it.localPort }
        val error =
            runCatching { runBlocking { airkast.connect(Receiver("off", "127.0.0.1", port)) } }
                .exceptionOrNull()
        assertThat(error).isInstanceOf(AirkastException.Unreachable::class.java)
    }

    @Test
    fun aReceiverThatHangsUpWhilePairingDisconnects() {
        ServerSocket(0).use { server ->
            thread(isDaemon = true) { runCatching { server.accept().close() } }
            val error =
                runCatching {
                    runBlocking { airkast.connect(Receiver("rude", "127.0.0.1", server.localPort)) }
                }.exceptionOrNull()
            assertThat(error).isInstanceOf(AirkastException.Disconnected::class.java)
        }
    }
}

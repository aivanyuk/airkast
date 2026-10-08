package io.github.aivanyuk.airkast.session

import com.google.common.truth.Truth.assertThat
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.MediaItem
import io.github.aivanyuk.airkast.PlaybackState
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.ReceiverEvent
import io.github.aivanyuk.airkast.SessionOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class SessionTest {
    private val options =
        SessionOptions {
            keepAlive = false
            loadTimeoutMillis = 1_000
            requestTimeoutMillis = 2_000
        }

    @Test
    fun loadsPollsSeeksAndStops() =
        runBlocking {
            FakeReceiver().use { fake ->
                Airkast.connect(Receiver("fake", "127.0.0.1", fake.port), options = options).use { session ->
                    session.load(MediaItem("https://example.com/master.m3u8", startSeconds = 600.0))
                    val insert = fake.commands.first { it["type"] == "insertPlayQueueItem" }
                    val item = insert["item"] as Map<*, *>
                    assertThat(DefaultVideoSession.seconds(item["Start-Position"])).isEqualTo(600.0)
                    assertThat(item["mediaType"]).isEqualTo("file")
                    assertThat(fake.commands.map { it["type"] })
                        .containsExactly("insertPlayQueueItem", "setProperty", "setProperty", "setRate")
                        .inOrder()

                    val info = session.playbackInfo()
                    assertThat(info.state).isEqualTo(PlaybackState.Playing)
                    assertThat(info.positionSeconds).isEqualTo(600.0)
                    assertThat(info.durationSeconds).isEqualTo(3077.0)
                    assertThat(info.loaded.single().durationSeconds).isEqualTo(30.0)

                    assertThat(session.seek(1200.0)).isEqualTo(1200.0)
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
    fun aSilentReceiverTimesTheLoadOut() =
        runBlocking {
            FakeReceiver(takesItems = false).use { fake ->
                Airkast.connect(Receiver("fake", "127.0.0.1", fake.port), options = options).use { session ->
                    val error = runCatching { session.load(MediaItem("https://example.com/a.m3u8")) }.exceptionOrNull()
                    assertThat(error).isInstanceOf(AirkastException.Timeout::class.java)
                }
            }
        }

    @Test
    fun aDroppedConnectionEndsTheSession() =
        runBlocking {
            FakeReceiver().use { fake ->
                val session = Airkast.connect(Receiver("fake", "127.0.0.1", fake.port), options = options)
                val ended = async { session.events.first { it is ReceiverEvent.Disconnected } }
                yield()
                fake.dropConnections()
                withTimeout(2_000) { ended.await() }
                val error = runCatching { session.play() }.exceptionOrNull()
                assertThat(error).isInstanceOf(AirkastException.Disconnected::class.java)
            }
        }

    @Test
    fun aLateFeedbackAnswerKeepsTheSession() =
        runBlocking {
            FakeReceiver(lateAnswers = mapOf("/feedback" to 900)).use { fake ->
                val patient =
                    options
                        .newBuilder()
                        .apply {
                            keepAlive = true
                            requestTimeoutMillis = 500
                        }.build()
                Airkast.connect(Receiver("fake", "127.0.0.1", fake.port), options = patient).use { session ->
                    session.load(MediaItem("https://example.com/a.m3u8"))
                    withTimeout(8_000) { while (fake.feedbacks < 2) delay(50) }
                    assertThat(session.playbackInfo().positionSeconds).isEqualTo(0.0)
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
                val quick = options.newBuilder().apply { requestTimeoutMillis = 500 }.build()
                Airkast.connect(Receiver("fake", "127.0.0.1", fake.port), options = quick).use { session ->
                    session.load(MediaItem("https://example.com/a.m3u8"))
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
                    options
                        .newBuilder()
                        .apply {
                            keepAlive = true
                            requestTimeoutMillis = 500
                        }.build()
                val session = Airkast.connect(Receiver("fake", "127.0.0.1", fake.port), options = patient)
                val ended = withTimeout(10_000) { session.events.first { it is ReceiverEvent.Disconnected } }
                assertThat((ended as ReceiverEvent.Disconnected).cause).hasMessageThat().contains("earlier request")
            }
        }

    @Test
    fun aWrongServerProofFailsThePairing() {
        FakeReceiver(corruptProof = true).use { fake ->
            assertThrows(AirkastException.PairingFailed::class.java) {
                runBlocking { Airkast.connect(Receiver("fake", "127.0.0.1", fake.port), options = options) }
            }
        }
    }

    @Test
    fun aReceiverThatIsOffIsUnreachable() {
        val port = ServerSocket(0).use { it.localPort }
        val error =
            runCatching { runBlocking { Airkast.connect(Receiver("off", "127.0.0.1", port), options = options) } }
                .exceptionOrNull()
        assertThat(error).isInstanceOf(AirkastException.Unreachable::class.java)
    }

    @Test
    fun aReceiverThatHangsUpWhilePairingDisconnects() {
        ServerSocket(0).use { server ->
            thread(isDaemon = true) { runCatching { server.accept().close() } }
            val error =
                runCatching {
                    runBlocking { Airkast.connect(Receiver("rude", "127.0.0.1", server.localPort), options = options) }
                }.exceptionOrNull()
            assertThat(error).isInstanceOf(AirkastException.Disconnected::class.java)
        }
    }
}

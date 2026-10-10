package io.github.aivanyuk.airkast.media3

import com.google.common.truth.Truth.assertThat
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.Credentials
import io.github.aivanyuk.airkast.Receiver
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/** How `AirkastPlayer.connect(receiver, withPin = true)` opens a session through an [Airkast]. */
class ConnectorTest {
    private val airkast =
        Airkast {
            connectTimeout = 2.seconds
            requestTimeout = 2.seconds
        }

    @Test
    fun withPinVerifiesAReceiverItPairedWithBefore() {
        val credentials = Credentials.decode("${"11".repeat(32)}:${"22".repeat(32)}:4142:4344")!!
        val path =
            firstRequest { receiver ->
                runBlocking { airkast.credentialStore.put(receiver, credentials) }
            }
        assertThat(path).isEqualTo("/pair-verify")
    }

    @Test
    fun withPinPairsAReceiverItNeverPairedWith() {
        assertThat(firstRequest {}).isEqualTo("/pair-pin-start")
    }

    /** The path of the first request a connect with [Connector.connect]'s `withPin` sends, after [setUp]. */
    private fun firstRequest(setUp: (Receiver) -> Unit): String =
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val receiver = Receiver("tv", "127.0.0.1", server.localPort)
            setUp(receiver)
            val path = CompletableFuture<String>()
            thread(isDaemon = true) {
                runCatching {
                    // Hanging up after the request line fails the connect, which is all this needs.
                    server.accept().use {
                        path.complete(
                            it
                                .getInputStream()
                                .bufferedReader()
                                .readLine()
                                .split(' ')[1],
                        )
                    }
                }.onFailure(path::completeExceptionally)
            }
            runCatching { runBlocking { airkast.connector().connect(receiver, withPin = true) { "2468" } } }
            path.get(5, TimeUnit.SECONDS)
        }
}

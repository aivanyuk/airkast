package io.github.aivanyuk.airkast.media3

import com.google.common.truth.Truth.assertThat
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.Credentials
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.Secret
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

/** How `AirkastPlayer.connect(receiver, pairWith = …)` opens a session through an [Airkast]. */
class ConnectorTest {
    private val airkast =
        Airkast {
            connectTimeout = 2.seconds
            requestTimeout = 2.seconds
        }

    @Test
    fun pairWithVerifiesAReceiverItPairedWithBefore() {
        val credentials = Credentials.decode("${"11".repeat(32)}:${"22".repeat(32)}:4142:4344")!!
        val path =
            firstRequest(Secret.Pin) { receiver ->
                runBlocking { airkast.credentialStore.put(receiver, credentials) }
            }
        assertThat(path).isEqualTo("/pair-verify")
    }

    @Test
    fun pairWithAPinPairsAReceiverItNeverPairedWith() {
        assertThat(firstRequest(Secret.Pin) {}).isEqualTo("/pair-pin-start")
    }

    @Test
    fun pairWithAPasswordShowsNothingOnTheScreen() {
        assertThat(firstRequest(Secret.Password) {}).isEqualTo("/pair-setup")
    }

    /** The path of the first request a connect that pairs with [secret] sends, after [setUp]. */
    private fun firstRequest(
        secret: Secret,
        setUp: (Receiver) -> Unit,
    ): String =
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
            runCatching { runBlocking { airkast.connector().connect(receiver, pairWith = secret) { "2468" } } }
            path.get(5, TimeUnit.SECONDS)
        }
}

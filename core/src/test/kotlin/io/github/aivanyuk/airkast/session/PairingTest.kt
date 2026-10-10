package io.github.aivanyuk.airkast.session

import com.google.common.truth.Truth.assertThat
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.Credentials
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.SessionOptions
import io.github.aivanyuk.airkast.VideoItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class PairingTest {
    private val options =
        SessionOptions {
            keepAlive = false
            requestTimeout = 2.seconds
        }

    @Test
    fun pairsWithThePinOnTheScreenThenConnectsWithoutIt() =
        runBlocking {
            FakeReceiver().use { fake ->
                val receiver = Receiver("fake", "127.0.0.1", fake.port)
                val credentials =
                    Airkast.pair(receiver, options = options) {
                        assertThat(fake.pinShown).isTrue()
                        fake.pin
                    }
                assertThat(Credentials.decode(credentials.encoded)).isEqualTo(credentials)

                Airkast.connect(receiver, options = options.copy { this.credentials = credentials }).use { session ->
                    session.load(VideoItem("https://example.com/a.m3u8"))
                    assertThat(fake.commands.first()["type"]).isEqualTo("insertPlayQueueItem")
                }
                assertThat(fake.verified).isTrue()
            }
        }

    @Test
    fun aWrongPinIsRejected() {
        FakeReceiver().use { fake ->
            val error = pairing(fake) { "0000" }
            assertThat(error).isInstanceOf(AirkastException.PinRejected::class.java)
        }
    }

    @Test
    fun aDismissedPinPromptCancelsThePairing() {
        FakeReceiver().use { fake ->
            val error = pairing(fake) { throw CancellationException("dismissed") }
            assertThat(error).isInstanceOf(CancellationException::class.java)
            // The connection is closed, so the receiver takes the next one.
            assertThat(pairing(fake) { fake.pin }).isNull()
        }
    }

    @Test
    fun aReceiverThatForgotTheSenderRefusesItsCredentials() =
        runBlocking {
            FakeReceiver().use { fake ->
                val receiver = Receiver("fake", "127.0.0.1", fake.port)
                val credentials = Airkast.pair(receiver, options = options) { fake.pin }
                fake.forgetSenders()
                val error =
                    runCatching {
                        Airkast.connect(receiver, options = options.copy { this.credentials = credentials })
                    }.exceptionOrNull()
                assertThat(error).isInstanceOf(AirkastException.PairingFailed::class.java)
                assertThat(fake.verified).isFalse()
            }
        }

    @Test
    fun credentialsForAnotherReceiverFail() =
        runBlocking {
            val credentials =
                FakeReceiver().use { other ->
                    Airkast.pair(Receiver("other", "127.0.0.1", other.port), options = options) { other.pin }
                }
            FakeReceiver().use { fake ->
                val error =
                    runCatching {
                        Airkast.connect(
                            Receiver("fake", "127.0.0.1", fake.port),
                            options = options.copy { this.credentials = credentials },
                        )
                    }.exceptionOrNull()
                assertThat(error).isInstanceOf(AirkastException.PairingFailed::class.java)
                assertThat(error).hasMessageThat().contains("not the one")
            }
        }

    @Test
    fun credentialsDecodeOnlyWhatTheyEncode() {
        val key = "11".repeat(32)
        val credentials = Credentials.decode("$key:$key:4142:4344")!!
        assertThat(credentials.encoded).isEqualTo("$key:$key:4142:4344")
        assertThat(credentials.toString()).isEqualTo("Credentials(receiverId=AB)")
        assertThat(Credentials.decode("")).isNull()
        assertThat(Credentials.decode("$key:$key:4142")).isNull()
        assertThat(Credentials.decode("$key:${"11".repeat(31)}:4142:4344")).isNull()
        assertThat(Credentials.decode("$key:$key:zz:4344")).isNull()
        assertThat(Credentials.decode("$key:$key::4344")).isNull()
    }

    /** What pairing with [fake] threw, or null when it paired. */
    private fun pairing(
        fake: FakeReceiver,
        pin: suspend () -> String,
    ): Throwable? =
        runCatching {
            runBlocking {
                Airkast.pair(
                    Receiver("fake", "127.0.0.1", fake.port),
                    options = options,
                    pin = pin,
                )
            }
        }.exceptionOrNull()
}

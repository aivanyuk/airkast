package io.github.aivanyuk.airkast.session

import com.google.common.truth.Truth.assertThat
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.Compatibility
import io.github.aivanyuk.airkast.Credentials
import io.github.aivanyuk.airkast.Media
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.Secret
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class PairingTest {
    private val airkast =
        Airkast {
            keepAlive = false
            requestTimeout = 2.seconds
        }

    @Test
    fun pairsWithThePinOnTheScreenThenConnectsWithoutIt() =
        runBlocking {
            FakeReceiver().use { fake ->
                val receiver = Receiver("fake", "127.0.0.1", fake.port)
                val credentials =
                    airkast.pair(receiver) {
                        assertThat(fake.pinShown).isTrue()
                        fake.pin
                    }
                assertThat(Credentials.decode(credentials.encoded)).isEqualTo(credentials)
                assertThat(airkast.credentialStore.get(receiver)).isEqualTo(credentials)

                airkast.connect(receiver).use { session ->
                    session.load(Media("https://example.com/a.m3u8"))
                    assertThat(fake.commands.first()["type"]).isEqualTo("insertPlayQueueItem")
                }
                assertThat(fake.verified).isTrue()
            }
        }

    @Test
    fun aReceiverThatAsksForAPinPairsOnItsFirstConnectOnly() =
        runBlocking {
            FakeReceiver().use { fake ->
                val receiver = asksForAPin(fake)
                val asked = mutableListOf<Secret>()
                val ask: suspend (Secret) -> String = { secret ->
                    asked += secret
                    fake.pin
                }
                airkast.connect(receiver, ask).close()
                airkast.connect(receiver, ask).close()
                assertThat(asked).containsExactly(Secret.Pin)
                assertThat(fake.verified).isTrue()
            }
        }

    @Test
    fun aReceiverThatAsksForAPasswordPairsWithItOnItsFirstConnectOnly() =
        runBlocking {
            FakeReceiver(password = "hunter2").use { fake ->
                val receiver = asksForAPassword(fake)
                val asked = mutableListOf<Secret>()
                val ask: suspend (Secret) -> String = { secret ->
                    asked += secret
                    fake.password!!
                }
                airkast.connect(receiver, ask).use { session ->
                    session.load(Media("https://example.com/a.m3u8"))
                }
                airkast.connect(receiver, ask).close()
                assertThat(asked).containsExactly(Secret.Password)
                assertThat(fake.pinShown).isFalse()
                assertThat(fake.verified).isTrue()
            }
        }

    @Test
    fun aReceiverThatAsksAgainForThePasswordItPairedWithGetsItWithoutAsking() =
        runBlocking {
            // A Mac set to "Require password" pairs with it, then asks for it on every SETUP.
            FakeReceiver(password = "hunter2", digestPassword = "hunter2").use { fake ->
                val receiver = asksForAPassword(fake)
                val asked = mutableListOf<Secret>()
                val ask: suspend (Secret) -> String = { secret ->
                    asked += secret
                    "hunter2"
                }
                airkast.connect(receiver, ask).use { session ->
                    session.load(Media("https://example.com/a.m3u8"))
                }
                airkast.connect(receiver, ask).close()
                assertThat(asked).containsExactly(Secret.Password)
                assertThat(fake.verified).isTrue()
                assertThat(fake.unsigned).isEqualTo(0)
            }
        }

    @Test
    fun aPasswordTheReceiverNoLongerTakesIsAskedForAgainAndKept() =
        runBlocking {
            FakeReceiver(password = "hunter2", digestPassword = "hunter2").use { fake ->
                val receiver = asksForAPassword(fake)
                airkast.pair(receiver, Secret.Password) { "hunter2" }
                fake.digestPassword = "hunter3"
                val asked = mutableListOf<Secret>()
                val ask: suspend (Secret) -> String = { secret ->
                    asked += secret
                    "hunter3"
                }
                airkast.connect(receiver, ask).close()
                airkast.connect(receiver, ask).close()
                assertThat(asked).containsExactly(Secret.Password)
                assertThat(airkast.credentialStore.get(receiver)?.password).isEqualTo("hunter3")
            }
        }

    @Test
    fun aWrongPinIsRejected() {
        FakeReceiver().use { fake ->
            val error = pairing(fake) { "0000" }
            assertThat(error).isInstanceOf(AirkastException.SecretRejected::class.java)
            assertThat((error as AirkastException.SecretRejected).secret).isEqualTo(Secret.Pin)
        }
    }

    @Test
    fun aWrongPasswordIsRejected() {
        FakeReceiver(password = "hunter2").use { fake ->
            val error = pairing(fake, Secret.Password) { "hunter3" }
            assertThat((error as AirkastException.SecretRejected).secret).isEqualTo(Secret.Password)
            assertThat(error).hasMessageThat().contains("password")
            assertThat(pairing(fake, Secret.Password) { "hunter2" }).isNull()
        }
    }

    @Test
    fun aReceiverThatAsksForItsPasswordWhenTheSessionStartsIsAskedOnEveryConnect() =
        runBlocking {
            FakeReceiver(digestPassword = "hunter2").use { fake ->
                // Nothing in its record says so: it asks with a Digest challenge on SETUP.
                val receiver = Receiver("fake", "127.0.0.1", fake.port)
                val asked = mutableListOf<Secret>()
                val ask: suspend (Secret) -> String = { secret ->
                    asked += secret
                    "hunter2"
                }
                airkast.connect(receiver, ask).use { session ->
                    session.load(Media("https://example.com/a.m3u8"))
                    session.pause()
                }
                airkast.connect(receiver, ask).close()
                assertThat(asked).containsExactly(Secret.Password, Secret.Password)
                assertThat(fake.commands.map { it["type"] }).contains("setRate")
                assertThat(fake.unsigned).isEqualTo(0)
                assertThat(airkast.credentialStore.get(receiver)).isNull()
            }
        }

    @Test
    fun aWrongPasswordWhenTheSessionStartsIsRejected() =
        runBlocking {
            FakeReceiver(digestPassword = "hunter2").use { fake ->
                val receiver = Receiver("fake", "127.0.0.1", fake.port)
                val error = runCatching { airkast.connect(receiver) { "hunter3" } }.exceptionOrNull()
                assertThat((error as AirkastException.SecretRejected).secret).isEqualTo(Secret.Password)
            }
        }

    @Test
    fun withoutAPromptAPasswordAskedForWhenTheSessionStartsFailsThePairing() =
        runBlocking {
            FakeReceiver(digestPassword = "hunter2").use { fake ->
                val error = runCatching { airkast.connect(Receiver("fake", "127.0.0.1", fake.port)) }.exceptionOrNull()
                assertThat(error).isInstanceOf(AirkastException.PairingFailed::class.java)
                assertThat(error).hasMessageThat().contains("password")
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
    fun aReceiverThatForgotTheSenderLosesItsCredentialsAndPairsAgain() =
        runBlocking {
            FakeReceiver().use { fake ->
                val receiver = asksForAPin(fake)
                airkast.pair(receiver) { fake.pin }
                fake.forgetSenders()
                val error = runCatching { airkast.connect(receiver) { fake.pin } }.exceptionOrNull()
                assertThat(error).isInstanceOf(AirkastException.PairingFailed::class.java)
                assertThat(fake.verified).isFalse()
                assertThat(airkast.credentialStore.get(receiver)).isNull()

                airkast.connect(receiver) { fake.pin }.close()
                assertThat(fake.verified).isTrue()
            }
        }

    @Test
    fun credentialsForAnotherReceiverFail() =
        runBlocking {
            val credentials =
                FakeReceiver().use { other ->
                    airkast.pair(Receiver("other", "127.0.0.1", other.port)) { other.pin }
                }
            FakeReceiver().use { fake ->
                val receiver = Receiver("fake", "127.0.0.1", fake.port)
                airkast.credentialStore.put(receiver, credentials)
                val error = runCatching { airkast.connect(receiver) }.exceptionOrNull()
                assertThat(error).isInstanceOf(AirkastException.PairingFailed::class.java)
                assertThat(error).hasMessageThat().contains("not the one")
                assertThat(airkast.credentialStore.get(receiver)).isNull()
            }
        }

    @Test
    fun aReceiverThatFailsToVerifyForAnotherReasonKeepsTheCredentials() =
        runBlocking {
            FakeReceiver().use { fake ->
                val receiver = asksForAPin(fake)
                val credentials = airkast.pair(receiver) { fake.pin }
                fake.verifyStatus = 503
                val error = runCatching { airkast.connect(receiver) { error("No PIN is asked for") } }.exceptionOrNull()
                assertThat(error).isInstanceOf(AirkastException.PairingFailed::class.java)
                assertThat(airkast.credentialStore.get(receiver)).isEqualTo(credentials)

                fake.verifyStatus = null
                airkast.connect(receiver).close()
                assertThat(fake.verified).isTrue()
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
        // After pairing with a password, a fifth field holds it.
        val withPassword = Credentials.decode("$key:$key:4142:4344:68756e74657232")!!
        assertThat(withPassword.password).isEqualTo("hunter2")
        assertThat(withPassword.encoded).isEqualTo("$key:$key:4142:4344:68756e74657232")
        assertThat(withPassword.toString()).doesNotContain("hunter2")
        assertThat(Credentials.decode("$key:$key:4142:4344:68:69")).isNull()
        assertThat(Credentials.decode("$key:$key:4142:4344:")!!.password).isNull()
        assertThat(Credentials.decode("$key:$key:4142:4344:d0bad0bed0b4")!!.password).isEqualTo("код")
        assertThat(Credentials.decode("$key:${"11".repeat(31)}:4142:4344")).isNull()
        assertThat(Credentials.decode("$key:$key:zz:4344")).isNull()
        assertThat(Credentials.decode("$key:$key::4344")).isNull()
    }

    /** [fake] as discovery would describe a receiver that asks for a PIN. */
    private fun asksForAPin(fake: FakeReceiver) =
        Receiver(
            "fake",
            "127.0.0.1",
            fake.port,
            mapOf("features" to "0x7F8AD0,0x38BCB46", "flags" to "0x8", "deviceid" to fake.receiverId),
        ).also { check(it.compatibility == Compatibility.NeedsPin) }

    /** [fake] as discovery would describe a receiver with a password set. */
    private fun asksForAPassword(fake: FakeReceiver) =
        Receiver(
            "fake",
            "127.0.0.1",
            fake.port,
            mapOf("features" to "0x7F8AD0,0x38BCB46", "pw" to "true", "deviceid" to fake.receiverId),
        ).also { check(it.compatibility == Compatibility.NeedsPassword) }

    /** What pairing with [fake] for [secret] threw, or null when it paired. */
    private fun pairing(
        fake: FakeReceiver,
        secret: Secret = Secret.Pin,
        ask: suspend () -> String,
    ): Throwable? =
        runCatching {
            runBlocking { airkast.pair(Receiver("fake", "127.0.0.1", fake.port), secret, ask) }
        }.exceptionOrNull()
}

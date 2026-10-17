package io.github.aivanyuk.airkast.session

import com.google.common.truth.Truth.assertThat
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.Compatibility
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.Secret
import io.github.aivanyuk.airkast.wire.BinaryPlist
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.ServerSocket
import kotlin.time.Duration.Companion.seconds

class DescribeTest {
    private val airkast =
        Airkast {
            keepAlive = false
            connectTimeout = 1.seconds
            requestTimeout = 2.seconds
        }

    @Test
    fun aReceiverTypedInByHandIsDescribedByItsInfo(): Unit =
        runBlocking {
            FakeReceiver().use { fake ->
                val typedIn = Receiver("tv", "127.0.0.1", fake.port)
                assertThat(typedIn.compatibility).isEqualTo(Compatibility.Unknown)

                val described = airkast.describe(typedIn)
                assertThat(described.compatibility).isEqualTo(Compatibility.Supported)
                assertThat(described.model).isEqualTo("OLED65CX6LA")
                assertThat(described.deviceId).isEqualTo("02:00:00:00:00:01")
                assertThat(described.sourceVersion).isEqualTo("377.25.06")
                // The TXT record itself, which it sends when asked.
                assertThat(described.properties).containsEntry("acl", "0")
                val asked = BinaryPlist.decode(fake.messages.single().body) as Map<*, *>
                assertThat(asked["qualifier"]).isEqualTo(listOf("txtAirPlay"))
                assertThat(listOf(described.name, described.host, described.port))
                    .containsExactly("tv", "127.0.0.1", fake.port)
                    .inOrder()
                // Before pairing, on a connection of its own.
                assertThat(fake.requests).containsExactly("GET /info")
            }
        }

    @Test
    fun aReceiverThatSendsNoTxtRecordIsDescribedByItsKeys(): Unit =
        runBlocking {
            FakeReceiver(sendsTxt = false).use { fake ->
                val described = airkast.describe(Receiver("tv", "127.0.0.1", fake.port))
                assertThat(described.compatibility).isEqualTo(Compatibility.Supported)
                assertThat(described.properties).containsEntry("flags", "0x244")
                assertThat(described.properties).doesNotContainKey("acl")
            }
        }

    @Test
    fun theAnswerReplacesPropertiesOfTheSameName(): Unit =
        runBlocking {
            FakeReceiver().use { fake ->
                val stale = Receiver("tv", "127.0.0.1", fake.port, mapOf("flags" to "0x80", "act" to "0"))
                val described = airkast.describe(stale)
                assertThat(described.properties).containsEntry("flags", "0x244")
                assertThat(described.properties).containsEntry("act", "0")
            }
        }

    @Test
    fun aConnectToAReceiverTypedInByHandAsksForThePinItsInfoSays(): Unit =
        runBlocking {
            FakeReceiver(info = LG_INFO + ("statusFlags" to 0x8L)).use { fake ->
                val asked = mutableListOf<Secret>()
                val session =
                    airkast.connect(Receiver("tv", "127.0.0.1", fake.port)) { secret ->
                        asked += secret
                        fake.pin
                    }
                session.use {
                    assertThat(it.receiver.compatibility).isEqualTo(Compatibility.NeedsPin)
                    assertThat(airkast.credentialStore.get(it.receiver)).isNotNull()
                }
                assertThat(asked).containsExactly(Secret.Pin)
                assertThat(fake.requests.first()).isEqualTo("GET /info")
            }
        }

    @Test
    fun aConnectGoesOnWhenTheReceiverRefusesInfo(): Unit =
        runBlocking {
            FakeReceiver(info = null).use { fake ->
                val typedIn = Receiver("tv", "127.0.0.1", fake.port)
                assertThrows(AirkastException.Rejected::class.java) { runBlocking { airkast.describe(typedIn) } }
                airkast.connect(typedIn).use { session ->
                    assertThat(session.receiver).isEqualTo(typedIn)
                }
            }
        }

    @Test
    fun aConnectToNothingFailsOnceWithoutTryingAgain() {
        val port = ServerSocket(0).use { it.localPort }
        val error =
            runCatching {
                runBlocking {
                    airkast.connect(
                        Receiver("off", "127.0.0.1", port),
                    )
                }
            }.exceptionOrNull()
        assertThat(error).isInstanceOf(AirkastException.Unreachable::class.java)
    }

    @Test
    fun aDiscoveredReceiverIsNotAskedForItsInfo(): Unit =
        runBlocking {
            FakeReceiver().use { fake ->
                val discovered = Receiver("tv", "127.0.0.1", fake.port, mapOf("features" to "0x7F8AD0,0x38BCB46"))
                airkast.connect(discovered).close()
                assertThat(fake.requests).doesNotContain("GET /info")
            }
        }
}

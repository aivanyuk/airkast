package io.github.aivanyuk.airkast

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SealedCredentialStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val tv = Receiver("tv", "10.0.0.2", properties = mapOf("deviceid" to "AA:BB:CC:DD:EE:FF"))
    private val typedIn = Receiver("tv", "10.0.0.3")
    private val credentials = Credentials.decode("${"11".repeat(32)}:${"22".repeat(32)}:4142:4344:68756e74657232")!!
    private val file by lazy { File(folder.root, "pairings/credentials") }
    private val sealer = FakeSealer()
    private val lines = mutableListOf<Pair<Airkast.Logger.Level, String>>()
    private val logger = Airkast.Logger { level, tag, message, _ -> lines += level to "$tag: $message" }
    private val warnings get() = lines.filter { it.first == Airkast.Logger.Level.Warn }.map { it.second }

    private fun store() = CredentialStore.file(sealer, logger) { file }

    @Test
    fun aPairingOutlivesTheStoreAndNeverReachesTheFileInTheClear() =
        runBlocking {
            store().put(tv, credentials)
            store().put(typedIn, credentials)
            val reopened = store()
            assertThat(reopened.get(tv)).isEqualTo(credentials)
            assertThat(reopened.get(Receiver("renamed", "10.0.0.9", properties = tv.properties))).isEqualTo(credentials)
            assertThat(reopened.get(typedIn)).isEqualTo(credentials)
            assertThat(reopened.get(Receiver("tv", "10.0.0.3", 7001))).isNull()
            assertThat(file.readText()).doesNotContain("22".repeat(32))
            assertThat(file.readText()).doesNotContain("68756e74657232")
            // The plain file store reads none of it.
            assertThat(CredentialStore.file(file).get(tv)).isNull()

            reopened.remove(tv)
            assertThat(store().get(tv)).isNull()
            assertThat(store().get(typedIn)).isEqualTo(credentials)
            assertThat(warnings).isEmpty()
        }

    @Test
    fun aPairingSealedUnderAKeySinceLostReadsAsNone() =
        runBlocking {
            store().put(tv, credentials)
            sealer.newKey()
            val store = store()
            assertThat(store.get(tv)).isNull()
            assertThat(warnings).hasSize(1)
            assertThat(warnings.single()).startsWith("credentials: ")

            store.put(tv, credentials)
            assertThat(store.get(tv)).isEqualTo(credentials)
            assertThat(store().get(tv)).isEqualTo(credentials)
        }

    @Test
    fun aPairingThatCannotBeSealedStaysInMemory() =
        runBlocking {
            val store = store()
            store.put(typedIn, credentials)
            sealer.sealFailure = IllegalStateException("no")
            store.put(tv, credentials)
            assertThat(store.get(tv)).isEqualTo(credentials)
            assertThat(store().get(tv)).isNull()
            assertThat(warnings).hasSize(1)

            // One failure fails only its own value.
            sealer.sealFailure = null
            store.put(tv, credentials)
            assertThat(store().get(tv)).isEqualTo(credentials)
            store.remove(tv)
            assertThat(store.get(tv)).isNull()
            assertThat(store().get(typedIn)).isEqualTo(credentials)
        }

    @Test
    fun aSealerWithNoKeyLeavesEveryPairingInMemory() =
        runBlocking {
            CredentialStore.file(file).put(typedIn, credentials)
            store().put(tv, credentials)
            val written = file.readText()
            sealer.sealFailure = IOException("no key")
            sealer.openFailure = IOException("no key")
            val store = store()
            assertThat(store.get(tv)).isNull()
            store.put(tv, credentials)
            store.put(typedIn, credentials)
            assertThat(store.get(tv)).isEqualTo(credentials)
            assertThat(file.readText()).isEqualTo(written)
            assertThat(sealer.calls).isEqualTo(3)
            assertThat(warnings).hasSize(1)
        }

    @Test
    fun aPairingKeptInTheClearIsSealedOnTheFirstRead() =
        runBlocking {
            CredentialStore.file(file).put(tv, credentials)
            CredentialStore.file(file).put(typedIn, credentials)
            assertThat(file.readText()).contains("22".repeat(32))

            val store = store()
            assertThat(store.get(tv)).isEqualTo(credentials)
            assertThat(file.readText()).doesNotContain("22".repeat(32))
            assertThat(store().get(typedIn)).isEqualTo(credentials)
            assertThat(
                lines.map {
                    it.second
                },
            ).containsExactly("credentials: sealed the pairings an earlier version kept in the clear")
            assertThat(warnings).isEmpty()
        }

    @Test
    fun aPairingKeptInTheClearStillReadsWhenItCannotBeSealed() =
        runBlocking {
            CredentialStore.file(file).put(tv, credentials)
            sealer.sealFailure = IOException("no key")
            assertThat(store().get(tv)).isEqualTo(credentials)
            assertThat(file.readText()).contains("22".repeat(32))
        }

    @Test
    fun buildingTouchesNeitherTheFileNorTheSealer() {
        CredentialStore.file(sealer) { error("Found on first use") }
        assertThat(sealer.calls).isEqualTo(0)
    }

    /** AES-GCM under a key held in memory, which [newKey] replaces as a Keystore wipe would. */
    private class FakeSealer : CredentialStore.Sealer {
        private var key = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES")
        var sealFailure: Exception? = null
        var openFailure: Exception? = null
        var calls = 0

        fun newKey() {
            key = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES")
        }

        override fun seal(value: ByteArray): ByteArray {
            calls++
            sealFailure?.let { throw it }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            return cipher.iv + cipher.doFinal(value)
        }

        override fun open(sealed: ByteArray): ByteArray {
            calls++
            openFailure?.let { throw it }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12))
            return cipher.doFinal(sealed, 12, sealed.size - 12)
        }
    }
}

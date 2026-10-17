package io.github.aivanyuk.airkast.android

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.CredentialStore
import io.github.aivanyuk.airkast.Credentials
import io.github.aivanyuk.airkast.Receiver
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.ProviderException
import javax.crypto.spec.SecretKeySpec

// The Keystore itself is a device's: these run the sealer against FakeAndroidKeyStore.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 34])
class EncryptedCredentialStoreTest {
    @get:Rule
    val keyStore = FakeAndroidKeyStore()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val tv = Receiver("tv", "192.168.50.241", properties = mapOf("deviceid" to "AA:BB:CC:DD:EE:FF"))
    private val typedIn = Receiver("tv", "192.168.50.242")
    private val privateKey = "22".repeat(32)
    private val credentials = Credentials.decode("${"11".repeat(32)}:$privateKey:4142:4344:68756e74657232")!!
    private val file get() = File(app.noBackupFilesDir, CREDENTIALS_FILE)
    private val warnings = mutableListOf<String>()
    private val logger =
        Airkast.Logger { level, tag, message, _ ->
            if (level == Airkast.Logger.Level.Warn) warnings += "$tag: $message"
        }

    @Test
    fun aPairingOutlivesTheStoreAndTheFileNeverHoldsItInTheClear() =
        runBlocking {
            CredentialStore.encrypted(app).put(tv, credentials)
            CredentialStore.encrypted(app).put(typedIn, credentials)
            val reopened = CredentialStore.encrypted(app)
            assertThat(reopened.get(tv)).isEqualTo(credentials)
            assertThat(
                reopened.get(Receiver("renamed", "192.168.50.9", properties = tv.properties)),
            ).isEqualTo(credentials)
            assertThat(reopened.get(typedIn)).isEqualTo(credentials)
            assertThat(reopened.get(Receiver("tv", "192.168.50.242", 7001))).isNull()

            val bytes = String(file.readBytes(), Charsets.ISO_8859_1)
            assertThat(bytes).doesNotContain(privateKey)
            assertThat(bytes).doesNotContain("68756e74657232")
            assertThat(bytes).doesNotContain("hunter2")
            assertThat(FakeAndroidKeyStore.keys.keys).containsExactly(KEY_ALIAS)
            assertThat(FakeAndroidKeyStore.generated).isEqualTo(1)
        }

    @Test
    fun aPairingSealedUnderAKeySinceLostReadsAsNone() =
        runBlocking {
            CredentialStore.encrypted(app).put(tv, credentials)
            FakeAndroidKeyStore.keys.clear()
            val store = CredentialStore.encrypted(app, logger)
            assertThat(store.get(tv)).isNull()
            assertThat(warnings).hasSize(1)

            store.put(tv, credentials)
            assertThat(store.get(tv)).isEqualTo(credentials)
            assertThat(CredentialStore.encrypted(app).get(tv)).isEqualTo(credentials)
        }

    @Test
    fun aPairingThatCannotBeSealedStaysInMemory() =
        runBlocking {
            // A key the cipher refuses fails each value, not the store.
            FakeAndroidKeyStore.keys[KEY_ALIAS] = SecretKeySpec(ByteArray(5), "AES")
            val store = CredentialStore.encrypted(app, logger)
            store.put(tv, credentials)
            assertThat(store.get(tv)).isEqualTo(credentials)
            assertThat(warnings).hasSize(1)
            assertThat(file.exists()).isFalse()
            assertThat(CredentialStore.encrypted(app).get(tv)).isNull()
        }

    @Test
    fun aKeystoreThatCannotMakeTheKeyLeavesPairingsInMemory() =
        runBlocking {
            FakeAndroidKeyStore.failure = ProviderException("Keystore operation failed")
            val store = CredentialStore.encrypted(app, logger)
            store.put(tv, credentials)
            store.put(typedIn, credentials)
            assertThat(store.get(tv)).isEqualTo(credentials)
            assertThat(store.get(typedIn)).isEqualTo(credentials)
            assertThat(file.exists()).isFalse()
            assertThat(warnings).hasSize(1)

            FakeAndroidKeyStore.failure = null
            assertThat(CredentialStore.encrypted(app).get(tv)).isNull()
        }

    @Test
    fun aPairingThatVersion050KeptInTheClearIsSealedOnTheFirstRead() =
        runBlocking {
            // What Airkast(context) wrote in 0.5.0.
            CredentialStore.file(file).put(tv, credentials)
            assertThat(file.readText()).contains(privateKey)

            assertThat(Airkast(app).credentialStore.get(tv)).isEqualTo(credentials)
            assertThat(file.readText()).doesNotContain(privateKey)
            assertThat(CredentialStore.encrypted(app).get(tv)).isEqualTo(credentials)
        }

    @Test
    fun theDefaultStoreWarnsThroughTheClientsLogger() =
        runBlocking {
            FakeAndroidKeyStore.failure = ProviderException("Keystore operation failed")
            val airkast = Airkast(app) { logger = this@EncryptedCredentialStoreTest.logger }
            airkast.credentialStore.put(tv, credentials)
            assertThat(airkast.credentialStore.get(tv)).isEqualTo(credentials)
            assertThat(warnings).hasSize(1)
            assertThat(warnings.single()).startsWith("credentials: ")
        }
}

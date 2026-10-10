package io.github.aivanyuk.airkast.android

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.CredentialStore
import io.github.aivanyuk.airkast.Credentials
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.SenderIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AirkastAndroidTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val tv = Receiver("tv", "192.168.50.241", properties = mapOf("deviceid" to "AA:BB:CC:DD:EE:FF"))
    private val credentials = Credentials.decode("${"11".repeat(32)}:${"22".repeat(32)}:4142:4344")!!

    @Test
    fun theTvShowsTheAppsLabel() {
        assertThat(Airkast(app).identity.name).isEqualTo(app.applicationInfo.loadLabel(app.packageManager).toString())
    }

    @Test
    fun pairingsOutliveTheClientInTheNoBackupFiles() =
        runBlocking {
            Airkast(app).credentialStore.put(tv, credentials)
            assertThat(Airkast(app).credentialStore.get(tv)).isEqualTo(credentials)
            assertThat(File(app.noBackupFilesDir, CREDENTIALS_FILE).exists()).isTrue()
        }

    @Test
    fun theBlockChangesTheDefaults() {
        val memory = CredentialStore.inMemory()
        val airkast =
            Airkast(app) {
                identity =
                    identity.let {
                        io.github.aivanyuk.airkast.SenderIdentity(
                            name = "Living room",
                            deviceId = it.deviceId,
                        )
                    }
                credentialStore = memory
            }
        assertThat(airkast.identity.name).isEqualTo("Living room")
        assertThat(airkast.credentialStore).isSameInstanceAs(memory)
    }
}

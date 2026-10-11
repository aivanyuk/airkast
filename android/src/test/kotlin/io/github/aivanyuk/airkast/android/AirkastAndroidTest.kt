package io.github.aivanyuk.airkast.android

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Looper
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
    fun buildingLeavesTheNoBackupFilesAloneUntilAPairingIsAskedFor() =
        runBlocking {
            // Android creates the directory whenever it is asked for, so asking is disk I/O.
            val askedOn = mutableListOf<Thread>()
            val context =
                object : ContextWrapper(app) {
                    override fun getApplicationContext(): Context = this

                    override fun getNoBackupFilesDir(): File {
                        synchronized(askedOn) { askedOn += Thread.currentThread() }
                        return super.getNoBackupFilesDir()
                    }
                }
            val airkast = Airkast(context)
            assertThat(askedOn).isEmpty()
            airkast.credentialStore.put(tv, credentials)
            assertThat(airkast.credentialStore.get(tv)).isEqualTo(credentials)
            assertThat(askedOn).hasSize(1)
            assertThat(askedOn.single()).isNotSameInstanceAs(Looper.getMainLooper().thread)
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

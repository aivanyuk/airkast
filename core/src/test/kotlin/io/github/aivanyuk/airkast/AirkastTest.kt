package io.github.aivanyuk.airkast

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.time.Duration.Companion.seconds

class AirkastTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val tv = Receiver("tv", "10.0.0.2", properties = mapOf("deviceid" to "AA:BB:CC:DD:EE:FF"))
    private val typedIn = Receiver("tv", "10.0.0.3")
    private val credentials = Credentials.decode("${"11".repeat(32)}:${"22".repeat(32)}:4142:4344")!!

    @Test
    fun aCopyChangesWhatItSaysAndSharesTheRest() {
        val airkast = Airkast { requestTimeout = 3.seconds }
        val quick = airkast.copy { requestTimeout = 1.seconds }
        assertThat(quick.requestTimeout).isEqualTo(1.seconds)
        assertThat(airkast.requestTimeout).isEqualTo(3.seconds)
        assertThat(quick.identity).isSameInstanceAs(airkast.identity)
        assertThat(quick.credentialStore).isSameInstanceAs(airkast.credentialStore)
    }

    @Test
    fun oneClientKeepsOneDeviceId() {
        val airkast = Airkast()
        assertThat(airkast.copy {}.identity.deviceId).isEqualTo(airkast.identity.deviceId)
        assertThat(Airkast().identity.deviceId).isNotEqualTo(airkast.identity.deviceId)
    }

    @Test
    fun aRefusingSocketFactoryFailsTheConnectBeforeAnyConnection() {
        val airkast = Airkast { socketFactory = { throw AirkastException.NotPermitted("no") } }
        val error = runCatching { runBlocking { airkast.connect(tv) } }.exceptionOrNull()
        assertThat(error).isInstanceOf(AirkastException.NotPermitted::class.java)
    }

    @Test
    fun theSocketFactoryIsAskedPerReceiver() {
        val asked = mutableListOf<String>()
        val airkast =
            Airkast {
                connectTimeout = 1.seconds
                socketFactory = { receiver ->
                    asked += receiver.host
                    SocketFactory.getDefault()
                }
            }
        val port = ServerSocket(0).use { it.localPort }
        runCatching { runBlocking { airkast.connect(Receiver("off", "127.0.0.1", port)) } }
        assertThat(asked).containsExactly("127.0.0.1")
    }

    @Test
    fun aConnectCancelledWhileItOpensClosesWhatItOpened() =
        runBlocking {
            val started = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val closed = CompletableDeferred<Unit>()
            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    opening {
                        started.countDown()
                        finish.await(5, TimeUnit.SECONDS)
                        AutoCloseable { closed.complete(Unit) }
                    }
                }
            check(started.await(5, TimeUnit.SECONDS))
            job.cancel()
            finish.countDown()
            job.join()
            assertThat(closed.isCompleted).isTrue()
        }

    @Test
    fun theMemoryStoreKeepsCredentialsByDeviceIdOrAddress() =
        runBlocking {
            val store = CredentialStore.inMemory()
            store.put(tv, credentials)
            assertThat(store.get(Receiver("renamed", "10.0.0.9", properties = tv.properties))).isEqualTo(credentials)
            assertThat(store.get(typedIn)).isNull()
            store.put(typedIn, credentials)
            assertThat(store.get(Receiver("tv", "10.0.0.3", 7001))).isNull()
            store.remove(tv)
            assertThat(store.get(tv)).isNull()
            assertThat(store.get(typedIn)).isEqualTo(credentials)
        }

    @Test
    fun theFileStoreKeepsCredentialsAcrossInstances() =
        runBlocking {
            val file = File(folder.root, "pairings/credentials")
            CredentialStore.file(file).put(tv, credentials)
            CredentialStore.file(file).put(typedIn, credentials)
            val reopened = CredentialStore.file(file)
            assertThat(reopened.get(tv)).isEqualTo(credentials)
            assertThat(reopened.get(typedIn)).isEqualTo(credentials)
            reopened.remove(tv)
            assertThat(CredentialStore.file(file).get(tv)).isNull()
            assertThat(CredentialStore.file(file).get(typedIn)).isEqualTo(credentials)
            assertThat(file.canRead()).isTrue()
            assertThat(File(file.parentFile, "credentials.tmp").exists()).isFalse()
        }

    @Test
    fun theFileStoreKeepsItsFileFromOtherUsers() {
        assumeTrue("POSIX permissions only", "posix" in FileSystems.getDefault().supportedFileAttributeViews())
        val file = File(folder.root, "credentials")
        runBlocking { CredentialStore.file(file).put(tv, credentials) }
        assertThat(Files.getPosixFilePermissions(file.toPath()))
            .containsExactly(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    }

    @Test
    fun aFileStoreThatCannotWriteKeepsCredentialsInMemory() =
        runBlocking {
            // A directory where the file should be: it reads as empty and never writes.
            val file = folder.newFolder("taken").also { File(it, "inside").createNewFile() }
            val store = CredentialStore.file(file)
            assertThat(store.get(tv)).isNull()
            store.put(tv, credentials)
            assertThat(store.get(tv)).isEqualTo(credentials)
        }

    @Test
    fun aFileStoreSkipsWhatItCannotDecode() =
        runBlocking {
            val file = folder.newFile("credentials")
            file.writeText("AA\\:BB\\:CC\\:DD\\:EE\\:FF=not credentials\n")
            assertThat(CredentialStore.file(file).get(tv)).isNull()
        }
}

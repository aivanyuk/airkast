package io.github.aivanyuk.airkast.session

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.AirkastSession
import io.github.aivanyuk.airkast.HlsMasterTest
import io.github.aivanyuk.airkast.Media
import io.github.aivanyuk.airkast.MediaFormat
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.TrackKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The session reads the loaded master from a server on loopback, as it would from a CDN. */
class RenditionsTest {
    private val airkast =
        Airkast {
            keepAlive = false
            loadTimeout = 1.seconds
            requestTimeout = 2.seconds
        }
    private val fetches = AtomicInteger()
    private val threads = Executors.newCachedThreadPool()
    private val server =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            executor = threads
            createContext("/") { exchange ->
                fetches.incrementAndGet()
                val path = exchange.requestURI.path
                val body =
                    when (path) {
                        "/master.m3u8" -> HlsMasterTest.BIPBOP_ADV_HEVC.toByteArray()
                        "/huge.m3u8" -> ByteArray(MasterPlaylist.MAX_SIZE + 1) { '#'.code.toByte() }
                        "/page.m3u8" -> "<html><body>Sign in</body></html>".toByteArray()
                        else -> null
                    }
                if (path == "/slow.m3u8") Thread.sleep(2_000)
                if (body == null) {
                    exchange.sendResponseHeaders(404, -1)
                } else {
                    // A length of 0 sends the body chunked, so the cap is met while reading it.
                    exchange.sendResponseHeaders(200, if (path == "/huge.m3u8") 0 else body.size.toLong())
                    exchange.responseBody.write(body)
                }
                exchange.close()
            }
            start()
        }

    private fun url(name: String) = "http://127.0.0.1:${server.address.port}/$name"

    @After
    fun stopServer() {
        server.stop(0)
        threads.shutdownNow()
    }

    private fun withSession(
        client: Airkast = airkast,
        block: suspend (AirkastSession) -> Unit,
    ) = runBlocking {
        FakeReceiver().use { fake ->
            client.connect(Receiver("fake", "127.0.0.1", fake.port)).use { block(it) }
        }
    }

    @Test
    fun theLoadedMastersRenditionsAreReadOnceUntilTheNextLoad() =
        withSession { session ->
            assertThat(session.renditions()).isEmpty()

            session.load(Media(url("master.m3u8")))
            val renditions = session.renditions()
            assertThat(renditions.map { it.kind to it.id })
                .containsExactly(TrackKind.Audio to 0L, TrackKind.Subtitles to 1L, TrackKind.Subtitles to 2L)
                .inOrder()
            assertThat(session.renditions()).isEqualTo(renditions)
            assertThat(fetches.get()).isEqualTo(1)

            session.load(Media(url("master.m3u8")))
            session.renditions()
            assertThat(fetches.get()).isEqualTo(2)
        }

    @Test
    fun aFileHasNoRenditionsAndIsNotFetched() =
        withSession { session ->
            session.load(Media(url("film"), format = MediaFormat.File))
            assertThat(session.renditions()).isEmpty()
            assertThat(fetches.get()).isEqualTo(0)
        }

    @Test
    fun aMasterTheServerDoesNotHaveIsUnreadable() =
        withSession { session ->
            session.load(Media(url("missing.m3u8")))
            val error =
                assertThrows(AirkastException.MasterUnreadable::class.java) { runBlocking { session.renditions() } }
            assertThat(error.status).isEqualTo(404)
        }

    @Test
    fun aBodyPastTheCapIsUnreadable() =
        withSession { session ->
            session.load(Media(url("huge.m3u8")))
            val error =
                assertThrows(AirkastException.MasterUnreadable::class.java) { runBlocking { session.renditions() } }
            assertThat(error.status).isNull()
            assertThat(error).hasMessageThat().contains("larger")
        }

    @Test
    fun aBodyThatIsNotAPlaylistIsUnreadable() =
        withSession { session ->
            session.load(Media(url("page.m3u8")))
            assertThrows(AirkastException.MasterUnreadable::class.java) { runBlocking { session.renditions() } }
        }

    @Test
    fun aServerSilentPastTheRequestTimeoutIsUnreadable() =
        withSession(airkast.copy { requestTimeout = 500.milliseconds }) { session ->
            session.load(Media(url("slow.m3u8")))
            val error =
                assertThrows(AirkastException.MasterUnreadable::class.java) { runBlocking { session.renditions() } }
            assertThat(error).hasCauseThat().isInstanceOf(SocketTimeoutException::class.java)
        }

    @Test
    fun aFailedReadIsTriedAgainOnTheNextCall() =
        withSession { session ->
            session.load(Media(url("missing.m3u8")))
            repeat(2) {
                assertThrows(AirkastException.MasterUnreadable::class.java) { runBlocking { session.renditions() } }
            }
            assertThat(fetches.get()).isEqualTo(2)
        }
}

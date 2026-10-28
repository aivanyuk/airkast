package io.github.aivanyuk.airkast.session

import com.google.common.truth.Truth.assertThat
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.AirkastSession
import io.github.aivanyuk.airkast.LocalFileResolver
import io.github.aivanyuk.airkast.Media
import io.github.aivanyuk.airkast.MediaFormat
import io.github.aivanyuk.airkast.MediaResolver
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.Rendition
import io.github.aivanyuk.airkast.ResolvedMedia
import io.github.aivanyuk.airkast.Resource
import io.github.aivanyuk.airkast.TrackKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/** `load` asks the client's resolvers, and the receiver fetches what they publish on the session's origin. */
class ResolverTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val airkast =
        Airkast {
            keepAlive = false
            loadTimeout = 1.seconds
            requestTimeout = 2.seconds
        }

    /** What the resolvers in a test published and closed, by the name they gave it. */
    private val closed = CopyOnWriteArrayList<String>()

    /** Resolves `resolve:<name>` to an HLS playlist it serves, and leaves anything else alone. */
    private val playlists =
        MediaResolver { media, _, origin ->
            val name = media.url.removePrefix("resolve:").takeIf { it != media.url } ?: return@MediaResolver null
            val url = origin.publish("$name.m3u8", Resource.text("application/vnd.apple.mpegurl") { "#EXTM3U\n" })
            object : ResolvedMedia {
                override val media = Media(url, media.startAt)
                override val renditions = listOf(Rendition(TrackKind.Audio, 0, name, "en", false, emptyList(), "aud"))

                override fun close() {
                    closed += name
                    origin.withdraw(url)
                }
            }
        }

    private fun withSession(
        client: Airkast = airkast,
        block: suspend (AirkastSession, FakeReceiver) -> Unit,
    ) = runBlocking {
        FakeReceiver().use { fake ->
            client.connect(Receiver("fake", "127.0.0.1", fake.port)).use { block(it, fake) }
        }
    }

    private fun FakeReceiver.loadedItems() =
        commands.filter { it["type"] == "insertPlayQueueItem" }.map { it["item"] as Map<*, *> }

    @Test
    fun aFileOnThePhoneIsServedToTheReceiver() {
        val bytes = ByteArray(50_000) { it.toByte() }
        val file = folder.newFile("film.mp4").apply { writeBytes(bytes) }
        withSession { session, fake ->
            session.load(Media(file.toURI().toString(), startAt = 30.seconds))
            val item = fake.loadedItems().single()
            assertThat(item["mediaType"]).isEqualTo("file")
            val url = item["Content-Location"] as String
            assertThat(url).matches("http://127\\.0\\.0\\.1:\\d+/[0-9a-f]{32}/file-[0-9a-f-]{36}\\.mp4")
            assertThat(DefaultVideoSession.duration(item["Start-Position"])).isEqualTo(30.seconds)

            val connection = URL(url).openConnection() as HttpURLConnection
            assertThat(connection.contentType).isEqualTo("video/mp4")
            assertThat(connection.inputStream.use { it.readBytes() }).isEqualTo(bytes)
        }
    }

    @Test
    fun theServedFileIsWithdrawnOnceTheNextItemLoads() {
        val file = folder.newFile("song.mp3").apply { writeBytes(ByteArray(10)) }
        withSession { session, fake ->
            session.load(Media(file.toURI().toString()))
            val url = fake.loadedItems().single()["Content-Location"] as String
            session.load(Media("https://example.com/a.m3u8"))
            assertThat(status(url)).isNotEqualTo(200)
        }
    }

    @Test
    fun theFirstResolverThatHandlesTheItemWins() {
        val second = MediaResolver { _, _, _ -> error("not asked") }
        withSession(airkast.copy { resolvers = listOf(playlists, second) }) { session, fake ->
            session.load(Media("resolve:first"))
            val url = fake.loadedItems().single()["Content-Location"] as String
            assertThat(url).endsWith("/first.m3u8")
            assertThat(status(url)).isEqualTo(200)
            assertThat(session.renditions().single().name).isEqualTo("first")
        }
    }

    @Test
    fun aResolvedItemClosesOnTheNextLoadOnStopAndWhenTheSessionEnds() {
        withSession(airkast.copy { resolvers = listOf(playlists) }) { session, _ ->
            session.load(Media("resolve:one"))
            assertThat(closed).isEmpty()
            session.load(Media("resolve:two"))
            assertThat(closed).containsExactly("one")
            session.stop()
            assertThat(closed).containsExactly("one", "two").inOrder()
            session.load(Media("resolve:three"))
            session.close()
            assertThat(closed).containsExactly("one", "two", "three").inOrder()
        }
    }

    @Test
    fun anItemNoResolverHandlesGoesAsItIs() {
        withSession(airkast.copy { resolvers = listOf(playlists) }) { session, fake ->
            session.load(Media("https://example.com/a.m3u8"))
            assertThat(fake.loadedItems().single()["Content-Location"]).isEqualTo("https://example.com/a.m3u8")
        }
    }

    @Test
    fun aUrlTheReceiverCannotFetchFailsAtOnce() {
        val file = folder.newFile("film.mp4")
        withSession(airkast.copy { resolvers = emptyList() }) { session, fake ->
            for (url in listOf(file.toURI().toString(), "content://media/external/video/7", "/sdcard/film.mp4")) {
                val error =
                    assertThrows(AirkastException.UnsupportedMedia::class.java) {
                        runBlocking { session.load(Media(url, format = MediaFormat.File)) }
                    }
                assertThat(error.format).isEqualTo(MediaFormat.File)
            }
            assertThat(fake.loadedItems()).isEmpty()
        }
    }

    @Test
    fun aMissingFileFailsAtOnce() {
        withSession { session, fake ->
            val url =
                folder.root
                    .resolve("gone.mp4")
                    .toURI()
                    .toString()
            val error =
                assertThrows(AirkastException.UnsupportedMedia::class.java) { runBlocking { session.load(Media(url)) } }
            assertThat(error.message).isEqualTo("The file the item names cannot be read")
            assertThat(fake.loadedItems()).isEmpty()
        }
    }

    @Test
    fun aResolverThatFailsLeavesTheItemBeforeIt() {
        val failing =
            MediaResolver { media, _, _ ->
                if (media.url.startsWith("fail:")) throw AirkastException.UnsupportedMedia(media.format, "No") else null
            }
        withSession(airkast.copy { resolvers = listOf(failing, playlists) }) { session, fake ->
            session.load(Media("resolve:one"))
            assertThrows(
                AirkastException.UnsupportedMedia::class.java,
            ) { runBlocking { session.load(Media("fail:x")) } }
            assertThat(fake.loadedItems()).hasSize(1)
            assertThat(closed).isEmpty()
            assertThat(session.renditions().single().name).isEqualTo("one")
        }
    }

    @Test
    fun anotherExceptionFromAResolverIsAnAirkastException() {
        val broken = MediaResolver { _, _, _ -> throw java.io.IOException("no socket") }
        val reported = CopyOnWriteArrayList<Airkast.Event>()
        val client = airkast.copy { resolvers = listOf(broken) }.copy { eventListener = { reported += it } }
        withSession(client) { session, fake ->
            val error =
                assertThrows(AirkastException.UnsupportedMedia::class.java) {
                    runBlocking { session.load(Media("https://example.com/a.m3u8")) }
                }
            assertThat(error.cause).hasMessageThat().isEqualTo("no socket")
            assertThat(reported.filterIsInstance<Airkast.Event.LoadFailed>().single().failure).isSameInstanceAs(error)
            assertThat(fake.loadedItems()).isEmpty()
        }
    }

    @Test
    fun aPublishOnAClosedSessionIsItsDisconnect() {
        var session: AirkastSession? = null
        val late =
            MediaResolver { _, _, origin ->
                session!!.close()
                origin.publish("late.m3u8", Resource.text("application/vnd.apple.mpegurl") { "" })
                null
            }
        withSession(airkast.copy { resolvers = listOf(late) }) { opened, _ ->
            session = opened
            assertThrows(AirkastException.Disconnected::class.java) {
                runBlocking { opened.load(Media("https://example.com/a.m3u8")) }
            }
        }
    }

    @Test
    fun aLoadTheReceiverDoesNotTakeKeepsServingTheItemBefore() {
        val client = airkast.copy { resolvers = listOf(playlists) }
        FakeReceiver(takesItems = false).use { fake ->
            runBlocking {
                client.connect(Receiver("fake", "127.0.0.1", fake.port)).use { session ->
                    for (name in listOf("one", "two")) {
                        val load = runCatching { session.load(Media("resolve:$name")) }
                        assertThat(load.exceptionOrNull()).isInstanceOf(AirkastException.Timeout::class.java)
                    }
                    val first = fake.loadedItems().first()["Content-Location"] as String
                    assertThat(closed).isEmpty()
                    assertThat(status(first)).isEqualTo(200)
                    session.stop()
                    assertThat(closed).containsExactly("two", "one")
                }
            }
        }
    }

    @Test
    fun aResolvedItemNoReceiverLoadsIsClosedAndRefused() {
        val dash =
            MediaResolver { media, _, _ ->
                object : ResolvedMedia {
                    override val media = Media("https://example.com/out.mpd")

                    override fun close() {
                        closed += "dash"
                    }
                }
            }
        withSession(airkast.copy { resolvers = listOf(dash) }) { session, fake ->
            val error =
                assertThrows(AirkastException.UnsupportedMedia::class.java) {
                    runBlocking { session.load(Media("https://example.com/in.m3u8")) }
                }
            assertThat(error.format).isEqualTo(MediaFormat.Dash)
            assertThat(closed).containsExactly("dash")
            assertThat(fake.loadedItems()).isEmpty()
        }
    }

    @Test
    fun aClientServesFilesByDefaultAndACopyKeepsItsResolvers() {
        assertThat(Airkast().resolvers.single()).isInstanceOf(LocalFileResolver::class.java)
        val custom = airkast.copy { resolvers = listOf(playlists) }
        assertThat(custom.copy { loadTimeout = 2.seconds }.resolvers).containsExactly(playlists)
    }

    private fun status(url: String): Int? =
        runCatching { (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 1_000 }.responseCode }
            .getOrNull()
}

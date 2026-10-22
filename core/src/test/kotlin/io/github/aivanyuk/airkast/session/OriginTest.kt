package io.github.aivanyuk.airkast.session

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ConnectException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The origin, fetched over loopback as a receiver fetches it. */
class OriginTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val origin = Origin(InetAddress.getLoopbackAddress())

    @After
    fun close() = origin.close()

    @Test
    fun aTextIsServedWithCorsHeaders() {
        val url = origin.publish("master.m3u8", Resource.text(M3U8) { "#EXTM3U\n" })
        assertThat(url).matches("http://127\\.0\\.0\\.1:\\d+/[0-9a-f]{32}/master\\.m3u8")

        val response = Client(url).get()
        assertThat(response.status).isEqualTo(200)
        assertThat(response.text).isEqualTo("#EXTM3U\n")
        assertThat(response.header("Content-Type")).isEqualTo(M3U8)
        assertThat(response.header("Content-Length")).isEqualTo("8")
        assertThat(response.header("Access-Control-Allow-Origin")).isEqualTo("*")
        assertThat(response.header("Accept-Ranges")).isEqualTo("bytes")
    }

    @Test
    fun aTextIsWrittenAgainOnEveryRequest() {
        val count = AtomicInteger()
        val url =
            origin.publish(
                "live.m3u8",
                Resource.text(M3U8) { "#EXT-X-MEDIA-SEQUENCE:${count.incrementAndGet()}" },
            )
        Client(url).use { client ->
            assertThat(client.get().text).endsWith(":1")
            assertThat(client.get().text).endsWith(":2")
        }
    }

    @Test
    fun aFileIsServedInTheRangesAsked() {
        val bytes = ByteArray(100_000) { it.toByte() }
        val file = folder.newFile("film.mp4").apply { writeBytes(bytes) }
        val url = origin.publish("film.mp4", Resource.file(file, "video/mp4"))
        Client(url).use { client ->
            val whole = client.get()
            assertThat(whole.status).isEqualTo(200)
            assertThat(whole.body).isEqualTo(bytes)

            val middle = client.get("Range" to "bytes=70000-70009")
            assertThat(middle.status).isEqualTo(206)
            assertThat(middle.header("Content-Range")).isEqualTo("bytes 70000-70009/100000")
            assertThat(middle.body).isEqualTo(bytes.copyOfRange(70_000, 70_010))

            val rest = client.get("Range" to "bytes=99990-")
            assertThat(rest.header("Content-Range")).isEqualTo("bytes 99990-99999/100000")
            assertThat(rest.body).isEqualTo(bytes.copyOfRange(99_990, 100_000))

            val tail = client.get("Range" to "bytes=-4")
            assertThat(tail.body).isEqualTo(bytes.copyOfRange(99_996, 100_000))

            val past = client.get("Range" to "bytes=100000-")
            assertThat(past.status).isEqualTo(416)
            assertThat(past.header("Content-Range")).isEqualTo("bytes */100000")
            assertThat(past.body).isEmpty()
        }
    }

    @Test
    fun headSaysTheLengthAndSendsNoBody() {
        val file = folder.newFile("song.mp3").apply { writeBytes(ByteArray(1234)) }
        val url = origin.publish("song.mp3", Resource.file(file, "audio/mpeg"))
        Client(url).use { client ->
            val head = client.request("HEAD")
            assertThat(head.status).isEqualTo(200)
            assertThat(head.header("Content-Length")).isEqualTo("1234")
            assertThat(head.body).isEmpty()
            // The connection is still in step: the next answer is the next request's.
            assertThat(client.get().body).hasLength(1234)
        }
    }

    @Test
    fun aPreflightIsAnswered() {
        val url = origin.publish("master.m3u8", Resource.text(M3U8) { "#EXTM3U\n" })
        val response = Client(url).request("OPTIONS")
        assertThat(response.status).isEqualTo(204)
        assertThat(response.header("Access-Control-Allow-Origin")).isEqualTo("*")
        assertThat(response.header("Access-Control-Allow-Methods")).isEqualTo("GET, HEAD, OPTIONS")
        assertThat(response.header("Access-Control-Allow-Headers")).isEqualTo("Range")
    }

    @Test
    fun anUnpublishedPathIsNotFound() {
        val url = origin.publish("master.m3u8", Resource.text(M3U8) { "#EXTM3U\n" })
        assertThat(Client(url.replace("master", "other")).get().status).isEqualTo(404)
        val unprefixed = URI(url).let { "http://${it.host}:${it.port}/master.m3u8" }
        assertThat(Client(unprefixed).get().status).isEqualTo(404)
    }

    @Test
    fun aResourceThatFailsIsAServerError() {
        val url = origin.publish("gone.mp4", Resource.file(folder.root.resolve("gone.mp4"), "video/mp4"))
        val broken = origin.publish("broken.m3u8", Resource.text(M3U8) { error("no playlist") })
        Client(url).use { client ->
            assertThat(client.get().status).isEqualTo(500)
            assertThat(client.get().status).isEqualTo(500)
        }
        assertThat(Client(broken).get().status).isEqualTo(500)
    }

    @Test
    fun anotherMethodIsRefusedAndTheConnectionClosed() {
        val url = origin.publish("master.m3u8", Resource.text(M3U8) { "#EXTM3U\n" })
        Client(url).use { client ->
            val response = client.request("POST")
            assertThat(response.status).isEqualTo(405)
            assertThat(response.header("Allow")).isEqualTo("GET, HEAD, OPTIONS")
            assertThat(client.closed()).isTrue()
        }
    }

    @Test
    fun aRequestThatDoesNotParseIsABadOne() {
        val url = origin.publish("master.m3u8", Resource.text(M3U8) { "#EXTM3U\n" })
        Client(url).use { client ->
            client.send("NONSENSE\r\n\r\n")
            assertThat(client.read(head = false).status).isEqualTo(400)
            assertThat(client.closed()).isTrue()
        }
    }

    @Test
    fun twoConnectionsAreServedAtOnce() {
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow =
            origin.publish(
                "slow.m3u8",
                object : Resource {
                    override val contentType = M3U8

                    override fun open(): Content {
                        opened.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        return Content(0) { InputStream.nullInputStream() }
                    }
                },
            )
        val fast = origin.publish("fast.m3u8", Resource.text(M3U8) { "#EXTM3U\n" })
        Client(slow).use { first ->
            first.send(first.requestText("GET"))
            assertThat(opened.await(5, TimeUnit.SECONDS)).isTrue()
            assertThat(Client(fast).get().status).isEqualTo(200)
            release.countDown()
            assertThat(first.read(head = false).status).isEqualTo(200)
        }
    }

    @Test
    fun theServerStopsOnceTheLastResourceIsWithdrawn() {
        val first = origin.publish("a.m3u8", Resource.text(M3U8) { "#EXTM3U\n" })
        val second = origin.publish("b.m3u8", Resource.text(M3U8) { "#EXTM3U\n" })
        val port = origin.port!!
        origin.withdraw(first)
        assertThat(Client(first).get().status).isEqualTo(404)
        assertThat(Client(second).get().status).isEqualTo(200)

        origin.withdraw(second)
        assertThat(origin.port).isNull()
        assertThrows(ConnectException::class.java) { Socket(InetAddress.getLoopbackAddress(), port).close() }

        val again = origin.publish("a.m3u8", Resource.text(M3U8) { "#EXTM3U\n" })
        assertThat(Client(again).get().status).isEqualTo(200)
    }

    @Test
    fun aClosedOriginPublishesNothing() {
        origin.publish("a.m3u8", Resource.text(M3U8) { "#EXTM3U\n" })
        origin.close()
        assertThat(origin.port).isNull()
        assertThrows(IllegalStateException::class.java) { origin.publish("a.m3u8", Resource.text(M3U8) { "" }) }
    }

    @Test
    fun aNameIsARelativePath() {
        assertThat(origin.publish("video/1/main.m3u8", Resource.text(M3U8) { "" })).endsWith("/video/1/main.m3u8")
        for (name in listOf("", "/a", "a/", "../a", "a/./b", "a b", "a?b", "a%2fb")) {
            assertThrows(IllegalArgumentException::class.java) { origin.publish(name, Resource.text(M3U8) { "" }) }
        }
    }

    @Test
    fun anIpv6AddressGoesInBrackets() {
        val address = Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1").address, 3)
        assertThat(Origin.host(address)).isEqualTo("[fe80:0:0:0:0:0:0:1%253]")
        assertThat(Origin.host(InetAddress.getByName("192.168.1.4"))).isEqualTo("192.168.1.4")
    }

    @Test
    fun aRangeIsReadAsTheRfcReadsIt() {
        assertThat(Ranges.parse("bytes=0-9", 100)).isEqualTo(0L..9L)
        assertThat(Ranges.parse("bytes=90-200", 100)).isEqualTo(90L..99L)
        assertThat(Ranges.parse("bytes=90-", 100)).isEqualTo(90L..99L)
        assertThat(Ranges.parse("bytes=-10", 100)).isEqualTo(90L..99L)
        assertThat(Ranges.parse("bytes=-200", 100)).isEqualTo(0L..99L)
        assertThat(Ranges.parse("Bytes = 5-5", 100)).isNull()
        assertThat(Ranges.parse("bytes=100-", 100)!!.isEmpty()).isTrue()
        assertThat(Ranges.parse("bytes=-0", 100)!!.isEmpty()).isTrue()
        assertThat(Ranges.parse("bytes=0-", 0)!!.isEmpty()).isTrue()
        for (ignored in listOf("bytes=0-1,5-6", "items=0-1", "bytes=9-2", "bytes=a-b", "bytes=5", "bytes=-")) {
            assertThat(Ranges.parse(ignored, 100)).isNull()
        }
    }

    /** One connection to the origin, kept open between requests as a receiver keeps it. */
    private class Client(
        url: String,
    ) : AutoCloseable {
        private val uri = URI(url)
        private val socket = Socket(uri.host, uri.port).apply { soTimeout = 5_000 }
        private val input = socket.getInputStream().buffered()

        fun get(vararg headers: Pair<String, String>) = request("GET", *headers)

        fun request(
            method: String,
            vararg headers: Pair<String, String>,
        ): Reply {
            send(requestText(method, *headers))
            return read(head = method == "HEAD")
        }

        fun requestText(
            method: String,
            vararg headers: Pair<String, String>,
        ): String =
            buildString {
                append("$method ${uri.rawPath} HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\n")
                for ((name, value) in headers) append("$name: $value\r\n")
                append("\r\n")
            }

        fun send(text: String) {
            socket.getOutputStream().apply {
                write(text.toByteArray(Charsets.ISO_8859_1))
                flush()
            }
        }

        /** The next response; [head] when it answers a HEAD, whose Content-Length has no body behind it. */
        fun read(head: Boolean): Reply {
            val lines = generateSequence { line() }.takeWhile { it.isNotEmpty() }.toList()
            val headers = lines.drop(1).map { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
            val reply = Reply(lines.first().split(' ')[1].toInt(), headers, ByteArray(0))
            val length = if (head) 0 else reply.header("Content-Length")!!.toInt()
            return Reply(reply.status, headers, ByteArray(length) { input.read().toByte() })
        }

        private fun line(): String {
            val line = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                check(b >= 0) { "The connection closed inside a head" }
                if (b == '\n'.code) return line.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r")
                line.write(b)
            }
        }

        fun closed(): Boolean = input.read() < 0

        override fun close() = socket.close()
    }

    private class Reply(
        val status: Int,
        private val headers: List<Pair<String, String>>,
        val body: ByteArray,
    ) {
        val text get() = String(body, Charsets.UTF_8)

        fun header(name: String) = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
    }

    private companion object {
        const val M3U8 = "application/vnd.apple.mpegurl"
    }
}

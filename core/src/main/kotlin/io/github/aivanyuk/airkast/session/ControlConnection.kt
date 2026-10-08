package io.github.aivanyuk.airkast.session

import io.github.aivanyuk.airkast.SenderIdentity
import io.github.aivanyuk.airkast.wire.HttpMessage
import io.github.aivanyuk.airkast.wire.Link
import java.io.EOFException
import java.io.IOException
import java.net.Socket
import java.security.SecureRandom

/** The connection a sender pairs on and sends its RTSP and HTTP requests over, one at a time. */
internal class ControlConnection(
    socket: Socket,
    private val identity: SenderIdentity,
    private val log: (String) -> Unit = {},
) : AutoCloseable {
    private val link = Link(socket)
    private var sequence = 0
    private val dacpId = HEX.let { digits -> String(CharArray(16) { digits[RANDOM.nextInt(16)] }) }
    private val activeRemote = (RANDOM.nextInt().toLong() and 0xffffffffL).toString()

    val localAddress get() = link.localAddress
    val remoteAddress get() = link.remoteAddress

    fun encrypt(
        writeKey: ByteArray,
        readKey: ByteArray,
    ) = link.encrypt(writeKey, readKey)

    @Synchronized
    fun exchange(
        method: String,
        target: String,
        protocol: String = RTSP,
        headers: List<Pair<String, String>> = emptyList(),
        body: ByteArray = ByteArray(0),
        contentType: String? = null,
    ): HttpMessage {
        val all =
            buildList {
                add("CSeq" to (++sequence).toString())
                add("DACP-ID" to dacpId)
                add("Active-Remote" to activeRemote)
                add("Client-Instance" to dacpId)
                if (headers.none { it.first.equals("User-Agent", ignoreCase = true) }) {
                    add("User-Agent" to identity.userAgent)
                }
                if (contentType != null) add("Content-Type" to contentType)
                addAll(headers)
            }
        link.write(HttpMessage("$method $target $protocol", all, body).encode())
        val response =
            try {
                HttpMessage.read(link.input) ?: throw EOFException("The receiver closed the connection")
            } catch (e: IOException) {
                log("control: $method $target got no answer: $e")
                throw IOException("$method $target: ${e.message}", e)
            }
        log("control: $method $target -> ${response.startLine}, ${response.body.size} bytes")
        return response
    }

    override fun close() = link.close()

    companion object {
        const val RTSP = "RTSP/1.0"
        const val HTTP = "HTTP/1.1"
        const val BPLIST = "application/x-apple-binary-plist"
        private const val HEX = "0123456789ABCDEF"
        private val RANDOM = SecureRandom()
    }
}

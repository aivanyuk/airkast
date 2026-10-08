package io.github.aivanyuk.airkast.wire

import io.github.aivanyuk.airkast.crypto.ChaCha20Poly1305
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.net.Socket

/**
 * One TCP connection to a receiver. It is plain until [encrypt], then every byte travels in HAP
 * frames: a little-endian length of at most 1024 bytes, which is also the frame's associated
 * data, then the sealed bytes. Each direction counts its own frames into the nonce.
 */
internal class Link(private val socket: Socket) : AutoCloseable {
    private val raw = BufferedInputStream(socket.getInputStream())
    private val out = socket.getOutputStream()
    private var writeKey: ByteArray? = null
    private var readKey: ByteArray? = null
    private var writeCounter = 0L
    private var readCounter = 0L
    private var plain = ByteArray(0)
    private var plainOffset = 0

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            if (!fill()) return -1
            return plain[plainOffset++].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, plain.size - plainOffset)
            plain.copyInto(b, off, plainOffset, plainOffset + n)
            plainOffset += n
            return n
        }
    }

    val localAddress get() = socket.localAddress
    val remoteAddress get() = socket.inetAddress

    fun encrypt(writeKey: ByteArray, readKey: ByteArray) {
        this.writeKey = writeKey
        this.readKey = readKey
    }

    @Synchronized
    fun write(bytes: ByteArray) {
        val key = writeKey
        if (key == null) {
            out.write(bytes)
        } else {
            var offset = 0
            while (offset < bytes.size) {
                val length = minOf(FRAME, bytes.size - offset)
                val aad = byteArrayOf(length.toByte(), (length shr 8).toByte())
                val sealed = ChaCha20Poly1305.seal(key, nonce(writeCounter++), bytes.copyOfRange(offset, offset + length), aad)
                out.write(aad)
                out.write(sealed)
                offset += length
            }
        }
        out.flush()
    }

    /** Makes decrypted bytes available; false at the end of the stream. */
    private fun fill(): Boolean {
        if (plainOffset < plain.size) return true
        val key = readKey
        if (key == null) {
            val chunk = ByteArray(FRAME)
            val n = raw.read(chunk)
            if (n < 0) return false
            plain = chunk.copyOf(n)
        } else {
            val lo = raw.read()
            if (lo < 0) return false
            val hi = raw.read()
            if (hi < 0) throw EOFException("Frame header cut short")
            val length = lo or (hi shl 8)
            require(length in 1..FRAME) { "Frame of $length bytes" }
            val sealed = ByteArray(length + ChaCha20Poly1305.TAG_LENGTH)
            readFully(sealed)
            plain = ChaCha20Poly1305.open(key, nonce(readCounter++), sealed, byteArrayOf(lo.toByte(), hi.toByte()))
        }
        plainOffset = 0
        return true
    }

    private fun readFully(buffer: ByteArray) {
        var read = 0
        while (read < buffer.size) {
            val n = raw.read(buffer, read, buffer.size - read)
            if (n < 0) throw EOFException("Frame cut short")
            read += n
        }
    }

    override fun close() = socket.close()

    private companion object {
        const val FRAME = 1024

        fun nonce(counter: Long): ByteArray = ByteArray(12).also {
            for (i in 0 until 8) it[4 + i] = (counter ushr (8 * i)).toByte()
        }
    }
}

package io.github.aivanyuk.airkast.wire

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

class LinkTest {
    private val key = ByteArray(32) { it.toByte() }

    /** Two frames' worth of message, sealed the way a receiver seals it. */
    private val sealed: ByteArray by lazy {
        val message = HttpMessage("RTSP/1.0 200 OK", listOf("CSeq" to "7"), ByteArray(1500) { 'x'.code.toByte() })
        loopback { sender, receiver ->
            Link(sender).apply { encrypt(key, key) }.write(message.encode())
            sender.shutdownOutput()
            receiver.getInputStream().readBytes()
        }
    }

    @Test
    fun aTimeoutBeforeAMessageConsumesNothing() {
        loopback { sender, receiver ->
            receiver.soTimeout = 100
            val link = Link(receiver).apply { encrypt(key, key) }
            assertThrows(SocketTimeoutException::class.java) { link.await() }
            sender.getOutputStream().write(sealed)
            assertThat(link.await()).isTrue()
            assertThat(HttpMessage.read(link.input)!!.body.size).isEqualTo(1500)
        }
    }

    @Test
    fun aStallInsideAMessageIsWaitedOut() {
        loopback { sender, receiver ->
            receiver.soTimeout = 100
            val link = Link(receiver).apply { encrypt(key, key) }
            thread {
                sender.getOutputStream().write(sealed, 0, 600)
                Thread.sleep(250)
                sender.getOutputStream().write(sealed, 600, sealed.size - 600)
            }
            assertThat(link.await()).isTrue()
            assertThat(HttpMessage.read(link.input)!!.header("CSeq")).isEqualTo("7")
        }
    }

    @Test
    fun aStallThatLastsFailsTheRead() {
        loopback { sender, receiver ->
            receiver.soTimeout = 50
            val link = Link(receiver).apply { encrypt(key, key) }
            // The first frame whole (a length, 1024 bytes and a tag), then part of the second.
            sender.getOutputStream().write(sealed, 0, 2 + 1024 + 16 + 300)
            assertThat(link.await()).isTrue()
            val error = assertThrows(IOException::class.java) { HttpMessage.read(link.input) }
            assertThat(error).isNotInstanceOf(SocketTimeoutException::class.java)
        }
    }

    private fun <T> loopback(block: (sender: Socket, receiver: Socket) -> T): T =
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(server.inetAddress, server.localPort).use { sender ->
                server.accept().use { receiver -> block(sender, receiver) }
            }
        }
}

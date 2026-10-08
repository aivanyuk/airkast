package io.github.aivanyuk.airkast.session

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/**
 * Answers the receiver's NTP-style timing requests. Without it a receiver still plays, but it
 * sends no events, so the sender cannot see the state or read the position.
 */
internal class TimingResponder(
    bind: InetAddress,
    private val receiver: InetAddress,
    private val log: (String) -> Unit = {},
) : AutoCloseable {
    private val socket = DatagramSocket(InetSocketAddress(bind, 0))
    val port: Int get() = socket.localPort

    private val thread =
        Thread(::serve, "airkast-timing").apply {
            isDaemon = true
            start()
        }

    private fun serve() {
        val buffer = ByteArray(128)
        while (!socket.isClosed) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: Exception) {
                return
            }
            log("timing request from ${packet.address.hostAddress}:${packet.port}, ${packet.length} bytes")
            if (packet.address != receiver || packet.length < PACKET) continue
            val reply = reply(buffer, ntpNow())
            runCatching { socket.send(DatagramPacket(reply, reply.size, packet.socketAddress)) }
        }
    }

    override fun close() = socket.close()

    companion object {
        private const val PACKET = 32
        private const val NTP_EPOCH_OFFSET = 0x83AA7E80L

        /** The reply echoes the request's send time as its reference and stamps now twice. */
        fun reply(
            request: ByteArray,
            now: Long,
        ): ByteArray =
            ByteBuffer
                .allocate(PACKET)
                .apply {
                    put(request[0])
                    put(0xD3.toByte())
                    putShort(7)
                    putInt(0)
                    put(request, 24, 8)
                    putLong(now)
                    putLong(now)
                }.array()

        fun ntpNow(): Long {
            val millis = System.currentTimeMillis()
            val seconds = millis / 1000 + NTP_EPOCH_OFFSET
            val fraction = ((millis % 1000) shl 32) / 1000
            return (seconds shl 32) or fraction
        }
    }
}

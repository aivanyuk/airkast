package io.github.aivanyuk.airkast.session

import io.github.aivanyuk.airkast.crypto.ChaCha20Poly1305
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * The receiving end of a realtime audio stream that [FakeReceiver] set up: it opens the packets
 * with the stream's key, reads their ALAC back to PCM, and keeps the syncs.
 */
internal class FakeAudio(
    setup: Map<*, *>,
) : AutoCloseable {
    val setup: Map<*, *> = setup
    private val key = setup["shk"] as ByteArray
    private val data = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
    private val control = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
    val dataPort: Int get() = data.localPort
    val controlPort: Int get() = control.localPort
    val packets = CopyOnWriteArrayList<Packet>()
    val syncs = CopyOnWriteArrayList<ByteArray>()

    @Volatile
    private var sender: SocketAddress? = null

    class Packet(
        val marker: Boolean,
        val sequence: Int,
        val rtpTime: Long,
        /** Stereo 16-bit little-endian PCM, as the ALAC frame carried it. */
        val pcm: ByteArray,
        val raw: ByteArray,
    )

    init {
        thread(isDaemon = true, name = "fake-audio-data") {
            val buffer = ByteArray(2048)
            while (!data.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                runCatching { data.receive(packet) }.onFailure { return@thread }
                packets += open(buffer.copyOf(packet.length))
            }
        }
        thread(isDaemon = true, name = "fake-audio-control") {
            val buffer = ByteArray(64)
            while (!control.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                runCatching { control.receive(packet) }.onFailure { return@thread }
                sender = packet.socketAddress
                syncs += buffer.copyOf(packet.length)
            }
        }
    }

    /** All the PCM received so far, in sequence order, each packet once. */
    fun pcm(): ByteArray {
        val out = ByteArrayOutputStream()
        packets.distinctBy { it.sequence }.forEach { out.write(it.pcm) }
        return out.toByteArray()
    }

    /** Asks the sender for [count] packets from [first] again, as a receiver that lost them does. */
    fun requestAgain(
        first: Int,
        count: Int,
    ) {
        val request =
            ByteBuffer
                .allocate(8)
                .put(0x80.toByte())
                .put(0xD5.toByte())
                .putShort(1)
                .putShort(first.toShort())
                .putShort(count.toShort())
                .array()
        control.send(DatagramPacket(request, request.size, sender))
    }

    private fun open(raw: ByteArray): Packet {
        val header = raw.copyOfRange(0, 12)
        val nonce = ByteArray(4) + raw.copyOfRange(raw.size - 8, raw.size)
        val alac = ChaCha20Poly1305.open(key, nonce, raw.copyOfRange(12, raw.size - 8), header.copyOfRange(4, 12))
        val buffer = ByteBuffer.wrap(header)
        buffer.get()
        val marker = buffer.get().toInt() and 0x80 != 0
        return Packet(
            marker = marker,
            sequence = buffer.short.toInt() and 0xffff,
            rtpTime = buffer.int.toLong() and 0xffffffffL,
            pcm = decode(alac),
            raw = raw,
        )
    }

    /** Reads an uncompressed ALAC channel-pair frame of the stream's frame length back to little-endian PCM. */
    private fun decode(alac: ByteArray): ByteArray {
        val frames = (setup["spf"] as Long).toInt()
        val bits = BitReader(alac)
        check(bits.read(3) == 1) { "not a channel pair" }
        bits.read(4)
        bits.read(12)
        check(bits.read(1) == 0) { "a sample count" }
        bits.read(2)
        check(bits.read(1) == 1) { "compressed" }
        val out = ByteArray(frames * 4)
        for (i in 0 until frames * 2) {
            val sample = bits.read(16)
            out[i * 2] = sample.toByte()
            out[i * 2 + 1] = (sample shr 8).toByte()
        }
        check(bits.read(3) == 7) { "no end element" }
        return out
    }

    override fun close() {
        data.close()
        control.close()
    }

    private class BitReader(
        private val bytes: ByteArray,
    ) {
        private var bit = 0

        fun read(count: Int): Int {
            var value = 0
            repeat(count) {
                value = (value shl 1) or ((bytes[bit / 8].toInt() shr (7 - bit % 8)) and 1)
                bit++
            }
            return value
        }
    }
}

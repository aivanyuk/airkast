package io.github.aivanyuk.airkast.session

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer

class PcmBufferTest {
    @Test
    fun wrapsAroundAndReadsBackInOrder() {
        val buffer = PcmBuffer(capacity = 4)
        assertThat(buffer.append(frames(1, 2, 3), 0, 12, channels = 2)).isEqualTo(3)
        buffer.release(2)
        assertThat(buffer.space).isEqualTo(3)
        assertThat(buffer.append(frames(4, 5, 6, 7), 0, 16, channels = 2)).isEqualTo(3)
        val out = ByteArray(16)
        buffer.read(2, 4, out)
        assertThat(out).isEqualTo(frames(3, 4, 5, 6))
        assertThrows(IllegalArgumentException::class.java) { buffer.read(1, 1, out) }
    }

    @Test
    fun monoPlaysOnBothChannelsAndAnOddByteWaits() {
        val buffer = PcmBuffer(capacity = 8)
        val mono = ByteBuffer.wrap(byteArrayOf(1, 0, 2, 0, 9))
        assertThat(buffer.append(mono, channels = 1)).isEqualTo(2)
        assertThat(mono.remaining()).isEqualTo(1)
        val out = ByteArray(8)
        buffer.read(0, 2, out)
        assertThat(out).isEqualTo(frames(1, 2))
    }

    @Test
    fun resetNumbersFromZero() {
        val buffer = PcmBuffer(capacity = 4)
        buffer.append(frames(1, 2), 0, 8, channels = 2)
        buffer.reset()
        assertThat(buffer.start).isEqualTo(0)
        assertThat(buffer.end).isEqualTo(0)
        assertThat(buffer.space).isEqualTo(4)
    }

    /** Stereo frames whose two samples both hold the value given. */
    private fun frames(vararg values: Int): ByteArray =
        ByteArray(values.size * 4).also { out ->
            values.forEachIndexed { i, v ->
                for (channel in 0..1) out[i * 4 + channel * 2] = v.toByte()
            }
        }
}

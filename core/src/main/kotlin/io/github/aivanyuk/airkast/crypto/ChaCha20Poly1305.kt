package io.github.aivanyuk.airkast.crypto

import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.MessageDigest

/**
 * ChaCha20-Poly1305 AEAD (RFC 8439). Written out because the JCA has no provider for it below
 * Android 9, and every message on a receiver connection goes through it.
 */
internal object ChaCha20Poly1305 {
    const val KEY_LENGTH = 32
    const val NONCE_LENGTH = 12
    const val TAG_LENGTH = 16

    fun seal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        checkSizes(key, nonce)
        val out = ByteArray(plaintext.size + TAG_LENGTH)
        xor(key, nonce, plaintext, 0, plaintext.size, out)
        tag(key, nonce, aad, out, plaintext.size).copyInto(out, plaintext.size)
        return out
    }

    fun open(
        key: ByteArray,
        nonce: ByteArray,
        sealed: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        checkSizes(key, nonce)
        if (sealed.size < TAG_LENGTH) throw GeneralSecurityException("Sealed message shorter than its tag")
        val length = sealed.size - TAG_LENGTH
        val expected = tag(key, nonce, aad, sealed, length)
        if (!MessageDigest.isEqual(expected, sealed.copyOfRange(length, sealed.size))) {
            throw GeneralSecurityException("Authentication tag mismatch")
        }
        val out = ByteArray(length)
        xor(key, nonce, sealed, 0, length, out)
        return out
    }

    private fun checkSizes(
        key: ByteArray,
        nonce: ByteArray,
    ) {
        require(key.size == KEY_LENGTH) { "Key must be $KEY_LENGTH bytes" }
        require(nonce.size == NONCE_LENGTH) { "Nonce must be $NONCE_LENGTH bytes" }
    }

    /** XORs [length] bytes of [input] with the keystream from block counter 1. */
    private fun xor(
        key: ByteArray,
        nonce: ByteArray,
        input: ByteArray,
        offset: Int,
        length: Int,
        out: ByteArray,
    ) {
        var counter = 1
        var position = 0
        while (position < length) {
            val stream = block(key, nonce, counter++)
            val n = minOf(64, length - position)
            for (i in 0 until n) {
                out[position + i] =
                    (input[offset + position + i].toInt() xor stream[i].toInt()).toByte()
            }
            position += n
        }
    }

    private fun tag(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray,
        length: Int,
    ): ByteArray {
        val oneTimeKey = block(key, nonce, 0).copyOf(32)
        val mac = Poly1305(oneTimeKey)
        mac.update(aad, 0, aad.size)
        mac.pad(aad.size)
        mac.update(ciphertext, 0, length)
        mac.pad(length)
        val lengths = ByteArray(16)
        putLongLe(lengths, 0, aad.size.toLong())
        putLongLe(lengths, 8, length.toLong())
        mac.update(lengths, 0, 16)
        return mac.finish()
    }

    internal fun block(
        key: ByteArray,
        nonce: ByteArray,
        counter: Int,
    ): ByteArray {
        val state = IntArray(16)
        state[0] = 0x61707865
        state[1] = 0x3320646e
        state[2] = 0x79622d32
        state[3] = 0x6b206574
        for (i in 0 until 8) state[4 + i] = intLe(key, i * 4)
        state[12] = counter
        for (i in 0 until 3) state[13 + i] = intLe(nonce, i * 4)
        val x = state.copyOf()
        repeat(10) {
            quarter(x, 0, 4, 8, 12)
            quarter(x, 1, 5, 9, 13)
            quarter(x, 2, 6, 10, 14)
            quarter(x, 3, 7, 11, 15)
            quarter(x, 0, 5, 10, 15)
            quarter(x, 1, 6, 11, 12)
            quarter(x, 2, 7, 8, 13)
            quarter(x, 3, 4, 9, 14)
        }
        val out = ByteArray(64)
        for (i in 0 until 16) {
            val v = x[i] + state[i]
            out[i * 4] = v.toByte()
            out[i * 4 + 1] = (v ushr 8).toByte()
            out[i * 4 + 2] = (v ushr 16).toByte()
            out[i * 4 + 3] = (v ushr 24).toByte()
        }
        return out
    }

    private fun quarter(
        x: IntArray,
        a: Int,
        b: Int,
        c: Int,
        d: Int,
    ) {
        x[a] += x[b]
        x[d] = (x[d] xor x[a]).rotateLeft(16)
        x[c] += x[d]
        x[b] = (x[b] xor x[c]).rotateLeft(12)
        x[a] += x[b]
        x[d] = (x[d] xor x[a]).rotateLeft(8)
        x[c] += x[d]
        x[b] = (x[b] xor x[c]).rotateLeft(7)
    }

    private fun intLe(
        b: ByteArray,
        o: Int,
    ): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private fun putLongLe(
        b: ByteArray,
        o: Int,
        v: Long,
    ) {
        for (i in 0 until 8) b[o + i] = (v ushr (8 * i)).toByte()
    }

    /** Poly1305 over BigInteger: receiver messages are small, so clarity wins over speed. */
    private class Poly1305(
        key: ByteArray,
    ) {
        private val r = leInt(key, 0, 16).and(CLAMP)
        private val s = leInt(key, 16, 16)
        private var acc = BigInteger.ZERO
        private val pending = ByteArray(16)
        private var pendingSize = 0

        fun update(
            data: ByteArray,
            offset: Int,
            length: Int,
        ) {
            for (i in 0 until length) {
                pending[pendingSize++] = data[offset + i]
                if (pendingSize == 16) flushBlock()
            }
        }

        /** Zero-pads the current input to a 16-byte boundary, as the AEAD construction asks. */
        fun pad(length: Int) {
            if (length % 16 != 0) update(ByteArray(16 - length % 16), 0, 16 - length % 16)
        }

        fun finish(): ByteArray {
            if (pendingSize > 0) flushBlock()
            val tag = acc.add(s).and(MASK_128)
            val out = ByteArray(16)
            val bytes = tag.toByteArray()
            for (i in 0 until 16) {
                val index = bytes.size - 1 - i
                out[i] = if (index >= 0) bytes[index] else 0
            }
            return out
        }

        private fun flushBlock() {
            val n = leInt(pending, 0, pendingSize).setBit(8 * pendingSize)
            acc = acc.add(n).multiply(r).mod(P)
            pendingSize = 0
        }

        private companion object {
            val P: BigInteger = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5))
            val MASK_128: BigInteger = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)
            val CLAMP = BigInteger("0ffffffc0ffffffc0ffffffc0fffffff", 16)

            fun leInt(
                b: ByteArray,
                offset: Int,
                length: Int,
            ): BigInteger {
                val be = ByteArray(length + 1)
                for (i in 0 until length) be[length - i] = b[offset + i]
                return BigInteger(be)
            }
        }
    }
}

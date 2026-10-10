package io.github.aivanyuk.airkast

/**
 * What [Airkast.pair] leaves a sender and a receiver that asks for a PIN agreeing on: the
 * receiver's long-term public key and id, and this sender's key and id. Kept in
 * [Airkast.credentialStore], they let every later [Airkast.connect] in without a PIN, until the
 * receiver forgets the sender. They hold the sender's private key, so a [CredentialStore] keeps
 * [encoded] where the app keeps secrets.
 *
 * Not a `@Poko` class: its `toString` leaves the private key out.
 */
public class Credentials internal constructor(
    internal val receiverKey: ByteArray,
    internal val senderSeed: ByteArray,
    internal val receiverId: ByteArray,
    internal val senderId: ByteArray,
) {
    /**
     * The credentials as one line to store: four hex fields, `ltpk:ltsk:atv_id:client_id`, the
     * layout pyatv uses for its AirPlay credentials.
     */
    public val encoded: String
        get() = listOf(receiverKey, senderSeed, receiverId, senderId).joinToString(":") { it.hex() }

    override fun equals(other: Any?): Boolean = other is Credentials && other.encoded == encoded

    override fun hashCode(): Int = encoded.hashCode()

    override fun toString(): String = "Credentials(receiverId=${String(receiverId)})"

    public companion object {
        private const val KEY_LENGTH = 32

        /** The credentials [encoded] holds, or null when it is not what [Credentials.encoded] writes. */
        public fun decode(encoded: String): Credentials? {
            val fields = encoded.trim().split(':').map { it.unhex() ?: return null }
            if (fields.size != 4 || fields[0].size != KEY_LENGTH || fields[1].size != KEY_LENGTH) return null
            if (fields[2].isEmpty() || fields[3].isEmpty()) return null
            return Credentials(fields[0], fields[1], fields[2], fields[3])
        }

        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

        private fun String.unhex(): ByteArray? {
            if (length % 2 != 0) return null
            return ByteArray(length / 2) {
                val high = Character.digit(this[2 * it], 16)
                val low = Character.digit(this[2 * it + 1], 16)
                if (high < 0 || low < 0) return null
                ((high shl 4) or low).toByte()
            }
        }
    }
}

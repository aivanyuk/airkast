package io.github.aivanyuk.airkast

import io.github.aivanyuk.airkast.internal.Poko
import java.security.SecureRandom

/**
 * What the sender tells a receiver about itself in SETUP. The defaults are the values receivers
 * are known to accept; [deviceId] is a random, locally administered MAC address, never a real one.
 */
@Poko
public class SenderIdentity(
    public val name: String = "airkast",
    public val deviceId: String = randomDeviceId(),
    public val model: String = "iPhone14,3",
    public val osName: String = "iPhone OS",
    public val osVersion: String = "16.5",
    public val osBuildVersion: String = "20F66",
    public val sourceVersion: String = "690.7.1",
    public val userAgent: String = "AirPlay/550.10",
) {
    public companion object {
        public fun randomDeviceId(): String {
            val bytes = ByteArray(6).also(SecureRandom()::nextBytes)
            bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
            return bytes.joinToString(":") { "%02X".format(it) }
        }
    }
}

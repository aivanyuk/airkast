package io.github.aivanyuk.airkast

import java.security.SecureRandom

/**
 * What the sender tells a receiver about itself in SETUP. The defaults are the values receivers
 * are known to accept; [deviceId] is a random, locally administered MAC address, never a real one.
 */
public data class SenderIdentity(
    val name: String = "airkast",
    val deviceId: String = randomDeviceId(),
    val model: String = "iPhone14,3",
    val osName: String = "iPhone OS",
    val osVersion: String = "16.5",
    val osBuildVersion: String = "20F66",
    val sourceVersion: String = "690.7.1",
    val userAgent: String = "AirPlay/550.10",
) {
    public companion object {
        public fun randomDeviceId(): String {
            val bytes = ByteArray(6).also(SecureRandom()::nextBytes)
            bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
            return bytes.joinToString(":") { "%02X".format(it) }
        }
    }
}

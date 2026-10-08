package io.github.aivanyuk.airkast.session

import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.crypto.SrpClient
import io.github.aivanyuk.airkast.wire.Tlv8

/**
 * HomeKit pair-setup's first two exchanges with the transient flag and the fixed PIN 3939: a
 * pairing that lasts one connection and needs no code on the screen. Returns the SRP session key
 * the channel keys derive from.
 */
internal object TransientPairing {
    private const val PIN = "3939"
    private val HEADERS = listOf("X-Apple-HKP" to "4")
    private const val TLV = "application/octet-stream"

    fun pair(connection: ControlConnection): ByteArray {
        connection.exchange("POST", "/pair-pin-start", ControlConnection.HTTP, HEADERS, contentType = TLV)
        val m2 =
            post(
                connection,
                Tlv8.encode(
                    Tlv8.METHOD to byteArrayOf(0),
                    Tlv8.SEQUENCE to byteArrayOf(1),
                    Tlv8.FLAGS to byteArrayOf(Tlv8.FLAG_TRANSIENT.toByte()),
                ),
            )
        val salt = m2[Tlv8.SALT] ?: throw AirkastException.PairingFailed("The receiver sent no salt")
        val serverPublic =
            m2[Tlv8.PUBLIC_KEY] ?: throw AirkastException.PairingFailed("The receiver sent no public key")
        val srp = SrpClient("Pair-Setup", PIN)
        val proof = srp.respond(salt, serverPublic)
        val m4 =
            post(
                connection,
                Tlv8.encode(Tlv8.SEQUENCE to byteArrayOf(3), Tlv8.PUBLIC_KEY to srp.publicKey, Tlv8.PROOF to proof),
            )
        val serverProof = m4[Tlv8.PROOF] ?: throw AirkastException.PairingFailed("The receiver sent no proof")
        if (!srp.verifyServer(serverProof)) throw AirkastException.PairingFailed("The receiver's proof does not match")
        return srp.sessionKey
    }

    private fun post(
        connection: ControlConnection,
        body: ByteArray,
    ): Map<Int, ByteArray> {
        val response = connection.exchange("POST", "/pair-setup", ControlConnection.HTTP, HEADERS, body, TLV)
        if (response.status !in 200..299) {
            throw AirkastException.PairingFailed("Pair-setup answered ${response.status}")
        }
        val tlv = Tlv8.decode(response.body)
        tlv[Tlv8.ERROR]?.let { throw AirkastException.PairingFailed("Pair-setup error ${it.firstOrNull()?.toInt()}") }
        return tlv
    }
}

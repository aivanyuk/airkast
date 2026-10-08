package io.github.aivanyuk.airkast.crypto

import com.google.common.truth.Truth.assertThat
import java.math.BigInteger
import java.security.GeneralSecurityException
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * SRP and HKDF vectors come from srptools and pyatv's key derivation, the pairing that works
 * against real receivers. The AEAD vector is RFC 8439 section 2.8.2.
 */
class CryptoVectorsTest {
    @Test
    fun srpMatchesSrptools() {
        val client = client()
        assertThat(client.publicKey.hex()).isEqualTo(CLIENT_PUBLIC)
        val proof = client.respond(SALT.unhex(), SERVER_PUBLIC.unhex())
        assertThat(client.sessionKey.hex()).isEqualTo(SESSION_KEY)
        assertThat(proof.hex()).isEqualTo(CLIENT_PROOF)
        assertThat(client.verifyServer(SERVER_PROOF.unhex())).isTrue()
    }

    @Test
    fun srpRejectsAWrongServerProof() {
        val client = client()
        client.respond(SALT.unhex(), SERVER_PUBLIC.unhex())
        assertThat(client.verifyServer(ByteArray(64))).isFalse()
    }

    @Test
    fun hkdfMatchesPyatvChannelKeys() {
        val sessionKey = SESSION_KEY.unhex()
        assertThat(Hkdf.sha512(sessionKey, "Control-Salt", "Control-Write-Encryption-Key").hex())
            .isEqualTo("31e4d98686692ce62e8554fb98f945a452126c2e6525522a49d5a02afa4c037e")
        assertThat(Hkdf.sha512(sessionKey, "Events-Salt", "Events-Read-Encryption-Key").hex())
            .isEqualTo("4e0d09291f53d21488d520f3418cc0bcc9c7ecbe0365bfcbc91b73860f481ffe")
    }

    @Test
    fun aeadMatchesRfc8439() {
        val key = "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f".unhex()
        val nonce = "070000004041424344454647".unhex()
        val aad = "50515253c0c1c2c3c4c5c6c7".unhex()
        val plaintext = (
            "Ladies and Gentlemen of the class of '99: If I could offer you only one tip " +
                "for the future, sunscreen would be it."
            ).toByteArray()
        val sealed = ChaCha20Poly1305.seal(key, nonce, plaintext, aad)
        assertThat(sealed.hex()).isEqualTo(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b" +
                "1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
                "3ff4def08e4b7a9de576d26586cec64b6116" +
                "1ae10b594f09e26a7e902ecbd0600691",
        )
        assertThat(ChaCha20Poly1305.open(key, nonce, sealed, aad)).isEqualTo(plaintext)
    }

    @Test
    fun aeadMatchesPyatvFraming() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12).also { it[4] = 5 }
        val aad = byteArrayOf(11, 0)
        assertThat(ChaCha20Poly1305.seal(key, nonce, "hello world".toByteArray(), aad).hex())
            .isEqualTo("4b6dfa662a053051f9c65d1b77b34c8c172315e1d6c78dd51ecc4a")
    }

    @Test
    fun aeadRefusesATamperedMessage() {
        val key = ByteArray(32)
        val nonce = ByteArray(12)
        val sealed = ChaCha20Poly1305.seal(key, nonce, "frame".toByteArray(), ByteArray(0))
        sealed[0] = (sealed[0].toInt() xor 1).toByte()
        assertThrows(GeneralSecurityException::class.java) {
            ChaCha20Poly1305.open(key, nonce, sealed, ByteArray(0))
        }
    }

    private fun client() = SrpClient("Pair-Setup", "3939", BigInteger("2222222222222222222222222222222222222222222222222222222222222222", 16))

    private companion object {
        const val SALT = "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf"
        const val SERVER_PUBLIC =
            "5d2eadb4f8861c7887e8a3f3f5571895bc7acb9381a283d0e595a75611b0ca9bc79cc37be6aa24a76d6dc1a3c09cfa4d" +
            "0862e27ee9faf45fdae5890f7569450daf9baffc2f36fcb08b1922a630d6cd77f138ed36928131df631ee505737b8f19" +
            "4609a740caee917e0e5cb9161684d650df0cff52ee4966214c7aa56a01350d7d2861f1a4e9cde57c715d42f0c0763783" +
            "d4a00fdad65ac6da8e5d8cc65f9c0641efbabd1117e9fb433d93014a0b5c0d906c01368e9257868492518df224637053" +
            "93827facbb3e372737a56c4307a5403c847ae9f11f14c9288311dffb1532d1095915b8e4296ca32e6593dd4555d5e60d" +
            "6685a67899f98c2f801f0850c0fe3bc81aca5b44021aaa5b6e5caa70b365cdc11871f4268698fce74244375ead10e3f7" +
            "ff99958c072593340ee1d459bbd4e495c3b2639a4fef8e450c72081e95622eb95d6e5a5738fe728c43c5e9b4ad33cba1" +
            "d40dc7088be5159e137263e875d76b8d862c0193879412afb17719c47c2fdd3b9bb61ff360fb371797adc286e61c1316"
        const val CLIENT_PUBLIC =
            "81093727015ff43ece825f4417676f6b9296c8e61f98009a5c4d2045561198c92f0151afa817ca3737da3e71611a5f84" +
            "d80074adae5c40c6565d99d030705f4daf4a08bed17de15b5f7109e1d431a599d2c305f0c305a1a4c87b9d95f981553a" +
            "19cb21d461835a27f5e0455f1fe865633119ae86aca1db4ee95d6c411c8e84a23952b595f966ce4ef022ef2fe825caf9" +
            "1c456bc760aaa10c652f46f7eea471ae835b50c564219d57d1acdfb70fb59d55c0a6beb33672675cc1ba4fd88c9aa07e" +
            "83d66fb9f19104fa251e3a6341f59103da79d27f4b9bdfcff286f9e3cb6ce50d8deff275640515e0b1c8cc9f2d78fae8" +
            "239ade555f240f28942cb8fe8d319777cf1484d6e34930aaf699ff67e31530981488b05612178ab4954ae047ffde2ce9" +
            "806f30f0b260225e5270449ea8614ba3b03dde9a452ce73d78c03656d66979b03152d56f77295e91a91921ac6add385b" +
            "b2e7ee836171acb9058c1d6501509bfdff3c9d24bc4d6db18497ef66d7dbbbd6189f74f5ac4a83130659ee3e89f89dea"
        const val SESSION_KEY =
            "c70e7f4d813e534d74defe060a62875c732599d3ed992eac8a02860d38754dd8809057a0c3787753c0bc1384986ecf35" +
            "8379951049c6db3d0a9264474b758587"
        const val CLIENT_PROOF =
            "56cf29efdfed1ec12ab8969d44fb7c8ba6572bf921d33e594ada00d83e07590b362ddb46ed8797e53845548428bd37cb" +
            "baf1e676995e8c736432853602a1adcf"
        const val SERVER_PROOF =
            "31c8fe6e51d35bbb1f3f754eb9884d80b5a0fcd23667362a876f00f844b9e8cfab1dbc161a92bd6b122faadcb0152b4e" +
            "0671ef9c668cb76b9d1552551b67246f"
    }
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

internal fun String.unhex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

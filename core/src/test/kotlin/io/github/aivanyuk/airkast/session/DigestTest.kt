package io.github.aivanyuk.airkast.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DigestTest {
    @Test
    fun answersAChallengeAsRfc2617DoesWithoutQop() {
        val digest =
            Digest
                .challenge("Digest realm=\"raop\", nonce=\"8E1F64A7C1D2B3A4F5E6D7C8B9A0F1E2\"")!!
                .answer("hunter2")
        // MD5 by Python's hashlib: HA1 39dab325…, HA2 cb656534….
        assertThat(digest.authorization("SETUP", "rtsp://192.168.1.20/1234567")).isEqualTo(
            "Digest username=\"AirPlay\", realm=\"raop\", nonce=\"8E1F64A7C1D2B3A4F5E6D7C8B9A0F1E2\", " +
                "uri=\"rtsp://192.168.1.20/1234567\", response=\"960683ce0009cb64ee59c9ce24ca5263\"",
        )
    }

    @Test
    fun readsOnlyADigestChallengeWithARealmAndANonce() {
        assertThat(Digest.challenge("digest nonce=\"1\",realm=\"r\", opaque=\"x\"")).isNotNull()
        assertThat(Digest.challenge("Basic realm=\"raop\"")).isNull()
        assertThat(Digest.challenge("Digest realm=\"raop\"")).isNull()
        assertThat(Digest.challenge(null)).isNull()
    }
}

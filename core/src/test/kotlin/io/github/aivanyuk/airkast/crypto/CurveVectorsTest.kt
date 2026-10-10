package io.github.aivanyuk.airkast.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * X25519 against RFC 7748 section 6.1, Ed25519 against RFC 8032 section 7.1, and both against the
 * JDK's own providers (the tests run on JDK 21, which has them) on random keys.
 */
class CurveVectorsTest {
    private val random = SecureRandom()

    @Test
    fun x25519MatchesRfc7748() {
        val alice = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".unhex()
        val bob = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb".unhex()
        val alicePublic = X25519.publicKey(alice)
        val bobPublic = X25519.publicKey(bob)
        assertThat(alicePublic.hex()).isEqualTo("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        assertThat(bobPublic.hex()).isEqualTo("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertThat(X25519.sharedSecret(alice, bobPublic).hex()).isEqualTo(shared)
        assertThat(X25519.sharedSecret(bob, alicePublic).hex()).isEqualTo(shared)
    }

    @Test
    fun x25519MatchesTheJdk() {
        repeat(20) {
            val ours = random.bytes(32)
            val theirs = random.bytes(32)
            val agreement = KeyAgreement.getInstance("XDH")
            agreement.init(KeyFactory.getInstance("XDH").generatePrivate(PKCS8EncodedKeySpec(X25519_PKCS8 + ours)))
            agreement.doPhase(
                KeyFactory
                    .getInstance("XDH")
                    .generatePublic(X509EncodedKeySpec(X25519_X509 + X25519.publicKey(theirs))),
                true,
            )
            assertThat(X25519.sharedSecret(ours, X25519.publicKey(theirs))).isEqualTo(agreement.generateSecret())
        }
    }

    @Test
    fun ed25519MatchesRfc8032() {
        val seed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".unhex()
        val publicKey = Ed25519.publicKey(seed)
        assertThat(publicKey.hex()).isEqualTo("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val signature = Ed25519.sign(seed, ByteArray(0))
        assertThat(signature.hex()).isEqualTo(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46b" +
                "d25bf5f0595bbe24655141438e7a100b",
        )
        assertThat(Ed25519.verify(publicKey, ByteArray(0), signature)).isTrue()
    }

    @Test
    fun ed25519MatchesTheJdk() {
        repeat(20) { n ->
            val seed = random.bytes(32)
            val message = random.bytes(n * 7)
            val signer = Signature.getInstance("Ed25519")
            signer.initSign(
                KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(ED25519_PKCS8 + seed)),
            )
            signer.update(message)
            val expected = signer.sign()
            assertThat(Ed25519.sign(seed, message)).isEqualTo(expected)

            val jdkPublic =
                KeyFactory.getInstance("Ed25519").generatePublic(
                    X509EncodedKeySpec(ED25519_X509 + Ed25519.publicKey(seed)),
                )
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(jdkPublic)
            verifier.update(message)
            assertThat(verifier.verify(expected)).isTrue()
            assertThat(Ed25519.verify(Ed25519.publicKey(seed), message, expected)).isTrue()
        }
    }

    @Test
    fun ed25519RefusesAnAlteredMessageOrSignature() {
        val seed = random.bytes(32)
        val publicKey = Ed25519.publicKey(seed)
        val signature = Ed25519.sign(seed, "pair".toByteArray())
        assertThat(Ed25519.verify(publicKey, "Pair".toByteArray(), signature)).isFalse()
        signature[40] = (signature[40].toInt() xor 1).toByte()
        assertThat(Ed25519.verify(publicKey, "pair".toByteArray(), signature)).isFalse()
        assertThat(Ed25519.verify(publicKey, "pair".toByteArray(), signature.copyOf(63))).isFalse()
        assertThat(Ed25519.verify(Ed25519.publicKey(random.bytes(32)), "pair".toByteArray(), signature)).isFalse()
    }

    private fun SecureRandom.bytes(n: Int) = ByteArray(n).also(::nextBytes)

    private companion object {
        // DER prefixes that wrap a raw 32-byte key for the JDK's key factories.
        val X25519_PKCS8 = "302e020100300506032b656e04220420".unhex()
        val X25519_X509 = "302a300506032b656e032100".unhex()
        val ED25519_PKCS8 = "302e020100300506032b657004220420".unhex()
        val ED25519_X509 = "302a300506032b6570032100".unhex()
    }
}

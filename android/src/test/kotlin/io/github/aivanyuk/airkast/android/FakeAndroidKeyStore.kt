package io.github.aivanyuk.airkast.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.junit.rules.ExternalResource
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * An `AndroidKeyStore` provider for the length of a test, since Robolectric has none: a KeyStore
 * and an AES KeyGenerator that keep keys in memory, so [KeystoreSealer] runs as it does on a
 * device up to the cipher, which the JVM's own provider runs. The generator takes only the spec
 * the sealer is meant to ask for. What a device's Keystore does to the key is left to a device.
 */
class FakeAndroidKeyStore : ExternalResource() {
    override fun before() {
        keys.clear()
        failure = null
        generated = 0
        Security.addProvider(AndroidKeyStore())
    }

    override fun after() {
        Security.removeProvider(NAME)
    }

    class AndroidKeyStore : Provider(NAME, 1.0, "Keys in memory, for tests") {
        init {
            put("KeyStore.$NAME", Store::class.java.name)
            put("KeyGenerator.AES", Generator::class.java.name)
        }
    }

    class Store : KeyStoreSpi() {
        override fun engineGetKey(
            alias: String,
            password: CharArray?,
        ): Key? = keys[alias]

        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null

        override fun engineGetCertificate(alias: String): Certificate? = null

        override fun engineGetCreationDate(alias: String): Date? = null

        override fun engineSetKeyEntry(
            alias: String,
            key: Key,
            password: CharArray?,
            chain: Array<out Certificate>?,
        ) {
            keys[alias] = key as SecretKey
        }

        override fun engineSetKeyEntry(
            alias: String,
            key: ByteArray,
            chain: Array<out Certificate>?,
        ): Unit = throw UnsupportedOperationException()

        override fun engineSetCertificateEntry(
            alias: String,
            cert: Certificate,
        ): Unit = throw UnsupportedOperationException()

        override fun engineDeleteEntry(alias: String) {
            keys.remove(alias)
        }

        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys)

        override fun engineContainsAlias(alias: String): Boolean = keys.containsKey(alias)

        override fun engineSize(): Int = keys.size

        override fun engineIsKeyEntry(alias: String): Boolean = keys.containsKey(alias)

        override fun engineIsCertificateEntry(alias: String): Boolean = false

        override fun engineGetCertificateAlias(cert: Certificate): String? = null

        override fun engineStore(
            stream: OutputStream?,
            password: CharArray?,
        ) = Unit

        override fun engineLoad(
            stream: InputStream?,
            password: CharArray?,
        ) = Unit
    }

    class Generator : KeyGeneratorSpi() {
        private var spec: KeyGenParameterSpec? = null

        override fun engineInit(random: SecureRandom?): Unit = throw UnsupportedOperationException("A spec, please")

        override fun engineInit(
            keysize: Int,
            random: SecureRandom?,
        ): Unit = throw UnsupportedOperationException("A spec, please")

        override fun engineInit(
            params: AlgorithmParameterSpec?,
            random: SecureRandom?,
        ) {
            spec = params as KeyGenParameterSpec
        }

        override fun engineGenerateKey(): SecretKey {
            failure?.let { throw it }
            val spec = checkNotNull(spec)
            check(spec.purposes == KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            check(spec.blockModes.toList() == listOf(KeyProperties.BLOCK_MODE_GCM))
            check(spec.encryptionPaddings.toList() == listOf(KeyProperties.ENCRYPTION_PADDING_NONE))
            check(spec.keySize == 256)
            check(!spec.isUserAuthenticationRequired)
            val key = SecretKeySpec(ByteArray(spec.keySize / 8).also(SecureRandom()::nextBytes), "AES")
            keys[spec.keystoreAlias] = key
            generated++
            return key
        }
    }

    companion object {
        const val NAME = "AndroidKeyStore"

        /** The keys by alias. Clearing them is what a factory reset or a Keystore wipe does. */
        val keys = ConcurrentHashMap<String, SecretKey>()

        /** What making a key throws, as a broken Keystore does, or null to make one. */
        @Volatile
        var failure: RuntimeException? = null

        @Volatile
        var generated = 0
    }
}

package io.github.aivanyuk.airkast.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.CredentialStore
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps credentials in [file], as `CredentialStore.file` does, with each pairing sealed with
 * AES-256-GCM under a key that lives in the Android Keystore, so the file holds no private key and
 * no password in the clear. The key, under the alias `airkast-pairings`, is made on first use and
 * asks for no screen lock, so a cast from the background can open it. [file] is in the app's
 * no-backup files by default, so a backup never carries it to another device. `Airkast(context)`
 * uses this store, logging to the client's logger.
 *
 * A pairing in the clear, as `CredentialStore.file` and airkast 0.5.0 keep it, still reads: the
 * first read seals it and rewrites the file. No failure reaches the caller. After a factory reset
 * or a Keystore wipe, a pairing reads as none, so the receiver asks for its PIN or password again.
 * A pairing the Keystore cannot seal stays in memory for this process, and so does every pairing
 * after the Keystore fails to make or load the key. Each goes to [logger] at
 * [Airkast.Logger.Level.Warn], under the tag `credentials`.
 *
 * Building one does no I/O: the store finds [file] and the key on its first use, on
 * `Dispatchers.IO`.
 */
public fun CredentialStore.Companion.encrypted(
    context: Context,
    logger: Airkast.Logger? = null,
    file: () -> File = noBackupFile(context),
): CredentialStore = CredentialStore.file(KeystoreSealer(KEY_ALIAS), logger, file)

private fun noBackupFile(context: Context): () -> File {
    val app = context.applicationContext
    return { File(app.noBackupFilesDir, CREDENTIALS_FILE) }
}

/**
 * Seals with AES-256-GCM under the Keystore key [alias], made when there is none: the IV the
 * Keystore picked, then the ciphertext and its tag. A Keystore that cannot make or load the key
 * throws [IOException], which tells the store there is no key.
 */
internal class KeystoreSealer(
    private val alias: String,
) : CredentialStore.Sealer {
    @Volatile
    private var key: SecretKey? = null

    override fun seal(value: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(value)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val key = key()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_LENGTH))
        return cipher.doFinal(sealed, IV_LENGTH, sealed.size - IV_LENGTH)
    }

    private fun key(): SecretKey =
        key ?: synchronized(KeystoreSealer::class.java) {
            // Two stores over one alias would otherwise each make a key, and the second replaces the first.
            key ?: try {
                (load() ?: generate()).also { key = it }
            } catch (e: GeneralSecurityException) {
                throw IOException("The Keystore has no key for pairings", e)
            } catch (e: ProviderException) {
                throw IOException("The Keystore has no key for pairings", e)
            }
        }

    private fun load(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(alias, null) as? SecretKey
    }

    private fun generate(): SecretKey {
        val spec =
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}

internal const val KEY_ALIAS = "airkast-pairings"

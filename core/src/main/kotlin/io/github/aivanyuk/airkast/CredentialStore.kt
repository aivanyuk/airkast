package io.github.aivanyuk.airkast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * Where an [Airkast] keeps the [Credentials] that pairing with a PIN or password leaves, one per
 * receiver, so the user types it once. They hold the sender's private key for that receiver, and
 * the receiver's password after pairing with one, so an app with a place for secrets implements
 * this over it. Calls come from any thread.
 *
 * A receiver found by discovery is best known by its [Receiver.deviceId], which stays when its
 * address changes. One typed in by hand has only its host and port.
 */
public interface CredentialStore {
    public suspend fun get(receiver: Receiver): Credentials?

    public suspend fun put(
        receiver: Receiver,
        credentials: Credentials,
    )

    public suspend fun remove(receiver: Receiver)

    public companion object {
        /**
         * Keeps credentials while the store lives, so a receiver asks for its PIN or password again
         * in the next process.
         */
        public fun inMemory(): CredentialStore = MemoryCredentialStore()

        /**
         * Keeps credentials in [file], readable by its owner only, and in memory. A file it cannot
         * read or write leaves them in memory for this process. `Airkast(context)` keeps one in
         * the app's no-backup files, so a backup never carries a private key to another device.
         */
        public fun file(file: File): CredentialStore = FileCredentialStore(file)
    }
}

/** The key the built-in stores file a receiver under: its `deviceid`, or its host and port. */
internal val Receiver.storeKey: String get() = deviceId ?: "$host:$port"

private class MemoryCredentialStore : CredentialStore {
    private val entries = ConcurrentHashMap<String, Credentials>()

    override suspend fun get(receiver: Receiver): Credentials? = entries[receiver.storeKey]

    override suspend fun put(
        receiver: Receiver,
        credentials: Credentials,
    ) {
        entries[receiver.storeKey] = credentials
    }

    override suspend fun remove(receiver: Receiver) {
        entries.remove(receiver.storeKey)
    }
}

/** One `key=credentials` line per receiver, in a properties file that each change replaces whole. */
private class FileCredentialStore(
    private val file: File,
) : CredentialStore {
    private val lock = Any()
    private var cache: Map<String, String>? = null

    override suspend fun get(receiver: Receiver): Credentials? =
        withContext(Dispatchers.IO) { entries()[receiver.storeKey]?.let(Credentials::decode) }

    override suspend fun put(
        receiver: Receiver,
        credentials: Credentials,
    ) = withContext(Dispatchers.IO) { update { it[receiver.storeKey] = credentials.encoded } }

    override suspend fun remove(receiver: Receiver) =
        withContext(Dispatchers.IO) {
            update { it.remove(receiver.storeKey) }
        }

    private fun entries(): Map<String, String> = synchronized(lock) { cache ?: read().also { cache = it } }

    private fun update(change: (MutableMap<String, String>) -> Unit) =
        synchronized(lock) {
            val entries = entries().toMutableMap().also(change)
            cache = entries
            runCatching { write(entries) }
            Unit
        }

    private fun read(): Map<String, String> =
        try {
            if (!file.exists()) {
                emptyMap()
            } else {
                val properties = Properties().apply { file.inputStream().use(::load) }
                properties.stringPropertyNames().associateWith(properties::getProperty)
            }
        } catch (_: IOException) {
            emptyMap()
        } catch (_: IllegalArgumentException) {
            // A malformed \u escape: the file is not one this store wrote.
            emptyMap()
        }

    private fun write(entries: Map<String, String>) {
        file.absoluteFile.parentFile?.mkdirs()
        val temp = File(file.absoluteFile.parentFile, "${file.name}.tmp")
        temp.delete()
        temp.createNewFile()
        temp.ownerOnly()
        val properties = Properties().apply { entries.forEach { (key, value) -> setProperty(key, value) } }
        temp.outputStream().use { properties.store(it, "airkast pairings: they hold private keys") }
        // On Windows a rename does not replace a file.
        if (!temp.renameTo(file) && !(file.delete() && temp.renameTo(file))) {
            throw IOException("Cannot replace $file")
        }
    }
}

/**
 * Makes this readable and writable by its owner alone, before a key reaches it. `File` sets a
 * permission for the owner without taking it from anyone else, so each is cleared for everybody
 * first: with owner-only calls alone, a file the umask made `rw-r--r--` stays that way.
 * `Files.setPosixFilePermissions` does it in one call, but Android has it only from API 26.
 */
private fun File.ownerOnly() {
    setReadable(false, false)
    setReadable(true, true)
    setWritable(false, false)
    setWritable(true, true)
}

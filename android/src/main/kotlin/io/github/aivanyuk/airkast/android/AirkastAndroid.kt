@file:JvmName("AirkastAndroid")

package io.github.aivanyuk.airkast.android

import android.content.Context
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.CredentialStore
import io.github.aivanyuk.airkast.SenderIdentity
import java.io.File

/**
 * An [Airkast] with Android's defaults, which [block] may change:
 *
 * - [Airkast.identity] names the sender after the app's label, which is what a TV shows.
 * - [Airkast.socketFactory] fails with [AirkastException.NotPermitted] when the app may not reach
 *   the local network, and binds the session's connections to the network that holds the receiver
 *   ([LocalNetwork.socketFactory]).
 * - [Airkast.credentialStore] keeps pairings in the app's no-backup files, so a receiver asks for
 *   its PIN or password once, and no backup carries the private keys to another device.
 *
 * Build one for the app and share it: `Airkast(context) { logger = Airkast.Logger.logcat() }`.
 */
public fun Airkast(
    context: Context,
    block: Airkast.Builder.() -> Unit = {},
): Airkast {
    val app = context.applicationContext
    return Airkast {
        identity = SenderIdentity(name = app.label())
        socketFactory = { receiver ->
            if (!LocalNetwork.isAccessible(app)) throw notPermitted()
            LocalNetwork.socketFactory(app, receiver.host)
        }
        credentialStore = CredentialStore.file(File(app.noBackupFilesDir, CREDENTIALS_FILE))
        block()
    }
}

private fun Context.label(): String =
    applicationInfo.loadLabel(packageManager).toString().ifBlank { SenderIdentity().name }

internal const val CREDENTIALS_FILE = "airkast-credentials"

internal fun notPermitted() =
    AirkastException.NotPermitted("The app may not reach the local network: ${LocalNetwork.PERMISSION} is not granted")

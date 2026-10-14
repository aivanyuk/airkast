@file:JvmName("AirkastAndroid")

package io.github.aivanyuk.airkast.android

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.CredentialStore
import io.github.aivanyuk.airkast.SenderIdentity
import java.io.File

/**
 * An [Airkast] with Android's defaults, which [block] may change:
 *
 * - [Airkast.identity] names the sender after the app's label, which is what a TV shows, and
 *   gives it a [SenderIdentity.deviceId] that stays across launches, so a Mac or an Apple TV asks
 *   once whether to allow it: [SenderIdentity.deviceIdFrom] the device's `ANDROID_ID` and the
 *   app's package. It changes with a factory reset, and tells a receiver nothing about either.
 * - [Airkast.socketFactory] fails with [AirkastException.NotPermitted] when the app may not reach
 *   the local network, and binds the session's connections to the network that holds the receiver
 *   ([LocalNetwork.socketFactory]).
 * - [Airkast.credentialStore] keeps pairings in the app's no-backup files, so a receiver asks for
 *   its PIN or password once, and no backup carries the private keys to another device. The
 *   store finds that directory, which Android creates when asked for it, on its first use and off
 *   the caller's thread.
 *
 * Building one does no I/O, so it may run on the main thread, in `Application.onCreate` or a
 * dependency graph. Build one for the app and share it:
 * `Airkast(context) { logger = Airkast.Logger.logcat() }`.
 */
public fun Airkast(
    context: Context,
    block: Airkast.Builder.() -> Unit = {},
): Airkast {
    val app = context.applicationContext
    return Airkast {
        identity = SenderIdentity(name = app.label(), deviceId = app.deviceId())
        socketFactory = { receiver ->
            if (!LocalNetwork.isAccessible(app)) throw notPermitted()
            LocalNetwork.socketFactory(app, receiver.host)
        }
        credentialStore = CredentialStore.file { File(app.noBackupFilesDir, CREDENTIALS_FILE) }
        block()
    }
}

/**
 * The app's sender ID on this device: random when the device has no `ANDROID_ID`, as an emulator
 * may not. `ANDROID_ID` only seeds a hash with the package, and never leaves the device as it is.
 */
@SuppressLint("HardwareIds")
private fun Context.deviceId(): String {
    val device = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
    if (device.isNullOrBlank()) return SenderIdentity.randomDeviceId()
    return SenderIdentity.deviceIdFrom("$device:$packageName")
}

private fun Context.label(): String =
    applicationInfo.loadLabel(packageManager).toString().ifBlank { SenderIdentity().name }

internal const val CREDENTIALS_FILE = "airkast-credentials"

internal fun notPermitted() =
    AirkastException.NotPermitted("The app may not reach the local network: ${LocalNetwork.PERMISSION} is not granted")

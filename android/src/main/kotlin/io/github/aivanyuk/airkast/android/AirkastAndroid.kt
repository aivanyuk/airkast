@file:JvmName("AirkastAndroid")

package io.github.aivanyuk.airkast.android

import android.content.Context
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.Credentials
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.SenderIdentity
import io.github.aivanyuk.airkast.SessionOptions
import io.github.aivanyuk.airkast.VideoSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [Airkast.connect] on Android. It fails with [AirkastException.NotPermitted] when the app may not
 * reach the local network, and binds the session's connections to the network that holds
 * [receiver] (see [LocalNetwork.socketFactory]), unless [options] names a socket factory.
 */
public suspend fun Airkast.connect(
    context: Context,
    receiver: Receiver,
    identity: SenderIdentity = SenderIdentity(),
    options: SessionOptions = SessionOptions.DEFAULT,
): VideoSession = connect(receiver, identity, bound(context, receiver, options))

/** [Airkast.pair] on Android, which reaches [receiver] as [Airkast.connect]'s overload does. */
public suspend fun Airkast.pair(
    context: Context,
    receiver: Receiver,
    identity: SenderIdentity = SenderIdentity(),
    options: SessionOptions = SessionOptions.DEFAULT,
    pin: suspend () -> String,
): Credentials = pair(receiver, identity, bound(context, receiver, options), pin)

private suspend fun bound(
    context: Context,
    receiver: Receiver,
    options: SessionOptions,
): SessionOptions {
    if (!LocalNetwork.isAccessible(context)) throw notPermitted()
    if (options.socketFactory != null) return options
    val factory = withContext(Dispatchers.IO) { LocalNetwork.socketFactory(context, receiver.host) }
    return options.copy { socketFactory = factory }
}

internal fun notPermitted() =
    AirkastException.NotPermitted("The app may not reach the local network: ${LocalNetwork.PERMISSION} is not granted")

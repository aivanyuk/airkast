@file:JvmName("AirkastAndroid")

package io.github.aivanyuk.airkast.android

import android.content.Context
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.AirkastException
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
): VideoSession {
    if (!LocalNetwork.accessible(context)) throw notPermitted()
    val bound =
        if (options.socketFactory != null) {
            options
        } else {
            val factory = withContext(Dispatchers.IO) { LocalNetwork.socketFactory(context, receiver.host) }
            options.newBuilder().apply { socketFactory = factory }.build()
        }
    return connect(receiver, identity, bound)
}

internal fun notPermitted() =
    AirkastException.NotPermitted("The app may not reach the local network: ${LocalNetwork.PERMISSION} is not granted")

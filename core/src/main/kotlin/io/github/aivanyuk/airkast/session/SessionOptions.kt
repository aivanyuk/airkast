package io.github.aivanyuk.airkast.session

import io.github.aivanyuk.airkast.Credentials
import javax.net.SocketFactory
import kotlin.time.Duration

/**
 * What one connect takes from its `Airkast`: the client's settings, with the socket factory and
 * credentials for this receiver, and the password for one that asks with an HTTP Digest challenge:
 * the one kept with the credentials, unless the user typed another.
 */
internal class SessionOptions(
    val connectTimeout: Duration,
    val requestTimeout: Duration,
    val loadTimeout: Duration,
    val keepAlive: Boolean,
    val ntpTiming: Boolean,
    val socketFactory: SocketFactory?,
    val logger: ((String) -> Unit)?,
    val credentials: Credentials?,
    val password: String? = credentials?.password,
) {
    fun withPassword(password: String): SessionOptions =
        SessionOptions(
            connectTimeout,
            requestTimeout,
            loadTimeout,
            keepAlive,
            ntpTiming,
            socketFactory,
            logger,
            credentials,
            password,
        )
}

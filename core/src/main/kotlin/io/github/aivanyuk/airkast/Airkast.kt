package io.github.aivanyuk.airkast

import io.github.aivanyuk.airkast.session.DefaultVideoSession
import io.github.aivanyuk.airkast.session.SessionOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.net.SocketFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Opens sessions to receivers, configured once: what the sender tells a receiver about itself, how
 * it reaches one, how long it waits, and where it keeps pairings. Build one for the app with
 * `Airkast { ... }` and share it; [copy] makes a variant. On Android, `Airkast(context)` from
 * `airkast-android` sets the platform's defaults.
 *
 * ```
 * val airkast = Airkast { logger = ::println }
 * val session = airkast.connect(receiver) { askTheUserForThePin() }
 * ```
 */
public class Airkast private constructor(
    builder: Builder,
) {
    /** What the sender tells a receiver about itself. A TV shows its [SenderIdentity.name]. */
    public val identity: SenderIdentity = builder.identity

    public val connectTimeout: Duration = builder.connectTimeout
    public val requestTimeout: Duration = builder.requestTimeout

    /** How long a load waits for the receiver to take the item. */
    public val loadTimeout: Duration = builder.loadTimeout

    /** Sends `/feedback` every two seconds, as Apple's senders do. */
    public val keepAlive: Boolean = builder.keepAlive

    /**
     * Answers the receiver's NTP timing requests, which needs it to reach this device over UDP. On
     * by default: a Mac answers SETUP with 500 without it, and send-airplay2 reports that tvOS
     * stalls SETUP. The LG CX plays either way.
     */
    public val ntpTiming: Boolean = builder.ntpTiming

    /**
     * Picks the socket factory for the connections to a receiver, or null for the platform's
     * default. It runs before every connect and pairing, off the caller's thread, and may refuse
     * with an [AirkastException]. On Android, `Airkast(context)` sets one that fails with
     * [AirkastException.NotPermitted] without the local network permission, and binds the
     * connections to the Wi-Fi or Ethernet network the receiver is on, off mobile data and out of a
     * VPN. Setting another replaces both.
     */
    public val socketFactory: (Receiver) -> SocketFactory? = builder.socketFactory

    /**
     * Where [pair] keeps the credentials a PIN leaves, and [connect] finds them. In memory by
     * default; `Airkast(context)` keeps them in the app's files.
     */
    public val credentialStore: CredentialStore = builder.credentialStore

    /** Receives one line per protocol step, for debugging. Lines never hold the media URL or a key. */
    public val logger: ((String) -> Unit)? = builder.logger

    /**
     * Pairs with [receiver] and opens a session in the protocol it speaks. A receiver this client
     * paired with before proves that pairing with the credentials in [credentialStore]. One that
     * asks for a PIN ([Compatibility.NeedsPin]) and has none pairs first, as [pair] does, when
     * [pin] is given; without [pin], it fails with [AirkastException.PairingFailed]. Any other
     * receiver pairs transiently.
     *
     * A receiver that refuses its credentials has forgotten this sender, or is not the receiver
     * they are for: they leave [credentialStore], and the next connect pairs again. Any other
     * [AirkastException.PairingFailed], such as a busy receiver's error status, keeps them. A
     * connect cancelled midway closes what it opened.
     */
    public suspend fun connect(
        receiver: Receiver,
        pin: (suspend () -> String)? = null,
    ): AirkastSession {
        val stored = credentialStore.get(receiver)
        val credentials =
            stored ?: if (pin != null && receiver.compatibility == Compatibility.NeedsPin) pair(receiver, pin) else null
        val options = options(receiver, credentials)
        return try {
            opening { DefaultVideoSession.open(receiver, identity, options) }
        } catch (e: AirkastException.PairingFailed) {
            if (credentials != null && e.credentialsRefused) credentialStore.remove(receiver)
            throw e
        }
    }

    /**
     * Pairs once with a receiver that asks for a PIN, and keeps the [Credentials] in
     * [credentialStore] for every [connect] after. The receiver shows a PIN on its screen, and
     * [pin] returns what the user typed; it runs in the caller's context, so it may show a dialog
     * and wait, and cancelling it gives up the pairing. [connect] calls this itself for a receiver
     * that says it asks for a PIN; call it directly for one that asks without saying so, such as a
     * receiver typed in by hand. A wrong PIN throws [AirkastException.PinRejected].
     */
    public suspend fun pair(
        receiver: Receiver,
        pin: suspend () -> String,
    ): Credentials {
        val options = options(receiver, null)
        val credentials =
            opening { DefaultVideoSession.startPairing(receiver, identity, options) }.use { pairing ->
                val code = pin()
                withContext(Dispatchers.IO) { DefaultVideoSession.finishPairing(pairing, code) }
            }
        credentialStore.put(receiver, credentials)
        return credentials
    }

    /** A copy with [block]'s changes: `airkast.copy { requestTimeout = 2.seconds }`. It shares the [credentialStore]. */
    public fun copy(block: Builder.() -> Unit): Airkast = Builder(this).apply(block).build()

    private suspend fun options(
        receiver: Receiver,
        credentials: Credentials?,
    ): SessionOptions {
        val factory = withContext(Dispatchers.IO) { socketFactory(receiver) }
        return SessionOptions(
            connectTimeout = connectTimeout,
            requestTimeout = requestTimeout,
            loadTimeout = loadTimeout,
            keepAlive = keepAlive,
            ntpTiming = ntpTiming,
            socketFactory = factory,
            logger = logger,
            credentials = credentials,
        )
    }

    /**
     * How to build an [Airkast]: `Airkast { logger = ::println }`. New options join with defaults,
     * so code that builds one keeps compiling across releases.
     */
    public class Builder() {
        public var identity: SenderIdentity = SenderIdentity()
        public var connectTimeout: Duration = 5.seconds
        public var requestTimeout: Duration = 5.seconds
        public var loadTimeout: Duration = 10.seconds
        public var keepAlive: Boolean = true
        public var ntpTiming: Boolean = true
        public var socketFactory: (Receiver) -> SocketFactory? = { null }
        public var credentialStore: CredentialStore = CredentialStore.inMemory()
        public var logger: ((String) -> Unit)? = null

        internal constructor(airkast: Airkast) : this() {
            identity = airkast.identity
            connectTimeout = airkast.connectTimeout
            requestTimeout = airkast.requestTimeout
            loadTimeout = airkast.loadTimeout
            keepAlive = airkast.keepAlive
            ntpTiming = airkast.ntpTiming
            socketFactory = airkast.socketFactory
            credentialStore = airkast.credentialStore
            logger = airkast.logger
        }

        public fun build(): Airkast = Airkast(this)
    }
}

/** Builds an [Airkast]: `Airkast()` with the defaults, or `Airkast { logger = ::println }`. */
public fun Airkast(block: Airkast.Builder.() -> Unit = {}): Airkast = Airkast.Builder().apply(block).build()

/**
 * Runs [open] on the IO dispatcher. A caller cancelled meanwhile never gets what [open] returns,
 * since `withContext` drops it, so this closes it.
 */
internal suspend fun <T : AutoCloseable> opening(open: () -> T): T {
    var opened: T? = null
    try {
        return withContext(Dispatchers.IO) { open().also { opened = it } }
    } catch (e: CancellationException) {
        opened?.close()
        throw e
    }
}

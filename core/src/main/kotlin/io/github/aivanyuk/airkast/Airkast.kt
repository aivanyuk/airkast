package io.github.aivanyuk.airkast

import io.github.aivanyuk.airkast.internal.Poko
import io.github.aivanyuk.airkast.session.DefaultVideoSession
import io.github.aivanyuk.airkast.session.Log
import io.github.aivanyuk.airkast.session.SessionOptions
import io.github.aivanyuk.airkast.session.report
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.net.SocketFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Opens sessions to receivers, configured once: what the sender tells a receiver about itself, how
 * it reaches one, how long it waits, and where it keeps pairings. Build one for the app with
 * `Airkast { ... }` and share it; [copy] makes a variant. On Android, `Airkast(context)` from
 * `airkast-android` sets the platform's defaults.
 *
 * ```
 * val airkast = Airkast { logger = Airkast.Logger.println() }
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

    /**
     * Takes the client's debugging lines, from every connect, pairing and session: one per protocol
     * step at [Logger.Level.Debug], and every message on the wire at [Logger.Level.Verbose]. A line
     * never holds the media URL, a key, a PIN or anything a pairing derives. Null, the default,
     * logs nothing.
     */
    public val logger: Logger? = builder.logger

    /**
     * Hears what the client does, for an app's statistics: connects, pairings, loads and how
     * sessions end, each with how long it took ([Event]). Null, the default, hears nothing.
     */
    public val eventListener: ((Event) -> Unit)? = builder.eventListener

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
        val log = Log(logger, "connect")
        val start = TimeSource.Monotonic.markNow()
        var pairing = Duration.ZERO
        var credentials: Credentials? = null
        try {
            credentials = credentialStore.get(receiver)
                ?: if (pin != null && receiver.compatibility == Compatibility.NeedsPin) {
                    val mark = TimeSource.Monotonic.markNow()
                    try {
                        pair(receiver, pin)
                    } finally {
                        pairing = mark.elapsedNow()
                    }
                } else {
                    null
                }
            val options = options(receiver, credentials)
            val session = opening { DefaultVideoSession.open(receiver, identity, options) }
            val took = start.elapsedNow() - pairing
            val how = if (credentials != null) Event.Pairing.Verified else Event.Pairing.Transient
            log.info { "connected in ${took.inWholeMilliseconds} ms, ${how.name.lowercase()}" }
            eventListener.report(Event.Connected(receiver, how, took), log)
            return session
        } catch (e: AirkastException) {
            if (credentials != null && e is AirkastException.PairingFailed && e.credentialsRefused) {
                credentialStore.remove(receiver)
                log.warn { "the receiver refused its credentials, which leave the store" }
                eventListener.report(Event.CredentialsDropped(receiver), log)
            }
            val took = start.elapsedNow() - pairing
            log.error(e) { "connect failed after ${took.inWholeMilliseconds} ms" }
            eventListener.report(Event.ConnectFailed(receiver, e, took), log)
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
        val log = Log(logger, "pairing")
        val start = TimeSource.Monotonic.markNow()
        val credentials =
            try {
                val options = options(receiver, null)
                opening { DefaultVideoSession.startPairing(receiver, identity, options) }.use { pairing ->
                    log.debug { "the receiver shows a PIN" }
                    val code = pin()
                    withContext(Dispatchers.IO) { DefaultVideoSession.finishPairing(pairing, code) }
                }
            } catch (e: AirkastException) {
                log.error(e) { "pairing failed" }
                eventListener.report(Event.PairingFailed(receiver, e), log)
                throw e
            }
        credentialStore.put(receiver, credentials)
        val took = start.elapsedNow()
        log.info { "paired in ${took.inWholeMilliseconds} ms" }
        eventListener.report(Event.Paired(receiver, took), log)
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
            eventListener = eventListener,
            credentials = credentials,
        )
    }

    /**
     * Where the client's debugging lines go: `Airkast { logger = Airkast.Logger.println() }`, or
     * `Airkast.Logger.logcat()` from `airkast-android`. The `tag` says which part wrote a line:
     * `connect`, `pairing`, `session`, `control` (the requests to the receiver), `events` (what the
     * receiver sends), `timing`, `player` (`AirkastPlayer`) or `discovery` (`ReceiverDiscovery`).
     * More may join.
     *
     * A line comes on the thread that did the work: the caller's, an I/O thread, or the thread that
     * reads a session's events. [log] must not block it, so a logger that writes to a file or the
     * network hands the line to a thread of its own. One that throws loses the line, never the
     * session.
     */
    public fun interface Logger {
        /** The lowest level [log] takes. A line below it is never built. */
        public val minLevel: Level get() = Level.Debug

        public fun log(
            level: Level,
            tag: String,
            message: String,
            error: Throwable?,
        )

        /**
         * [Verbose] is every message on the wire, several a second while an item plays. [Debug] is
         * each protocol step, [Info] a connect, pairing or load that worked and a session the app
         * closed, [Warn] something the client recovered from, such as a late answer, and [Error] a
         * connect, pairing, load or session that failed.
         */
        public enum class Level { Verbose, Debug, Info, Warn, Error }

        public companion object {
            /** Prints lines at [minLevel] and above to standard output, for a desktop JVM or a test. */
            public fun println(minLevel: Level = Level.Debug): Logger {
                val lowest = minLevel
                return object : Logger {
                    override val minLevel: Level = lowest

                    override fun log(
                        level: Level,
                        tag: String,
                        message: String,
                        error: Throwable?,
                    ) {
                        kotlin.io.println("${level.name.first()} airkast/$tag: $message")
                        error?.printStackTrace(System.out)
                    }
                }
            }
        }
    }

    /**
     * What the client did, for an app's statistics, as [Airkast.eventListener] hears it. Each event
     * names its [receiver]: its `model`, `sourceVersion` and `compatibility` describe the device,
     * while its `name` and `host` are the user's own. The client times what it reports, so an app
     * needs no start event to match an end with. New events may join in a minor release, so a
     * `when` over them keeps an `else` branch.
     *
     * An event comes on the thread it happened on: the caller's for a connect, a pairing, a load,
     * and a session the app closes; an I/O thread, or the thread that reads the session's events,
     * for a session that ends by itself. The listener must not block it, so one that sends events
     * to a server hands them to a thread or scope of its own. One that throws loses the event,
     * never the session.
     */
    public sealed interface Event {
        public val receiver: Receiver

        /**
         * [Airkast.connect] opened a session, [pairing] says how, in [took]. A PIN pairing it ran
         * first is left out of [took]: [Event.Paired] reports it.
         */
        @Poko
        public class Connected(
            override val receiver: Receiver,
            public val pairing: Pairing,
            public val took: Duration,
        ) : Event

        /**
         * [Airkast.connect] failed with [failure] after [took], a PIN pairing it ran first left
         * out. A pairing that failed there reports [Event.PairingFailed] first. A connect that was
         * cancelled reports nothing.
         */
        @Poko
        public class ConnectFailed(
            override val receiver: Receiver,
            public val failure: AirkastException,
            public val took: Duration,
        ) : Event

        /** A PIN pairing kept new [Credentials], [took] after it began, the user's typing included. */
        @Poko
        public class Paired(
            override val receiver: Receiver,
            public val took: Duration,
        ) : Event

        /** A PIN pairing failed with [failure]: [AirkastException.PinRejected] for a wrong PIN. */
        @Poko
        public class PairingFailed(
            override val receiver: Receiver,
            public val failure: AirkastException,
        ) : Event

        /**
         * The receiver refused the credentials in [Airkast.credentialStore], so they left it: it
         * forgot this sender, and the next connect pairs again.
         */
        @Poko
        public class CredentialsDropped(
            override val receiver: Receiver,
        ) : Event

        /** The receiver took an item that [AirkastSession.load] sent, [took] after the load began. */
        @Poko
        public class Loaded(
            override val receiver: Receiver,
            public val took: Duration,
        ) : Event

        /**
         * A load failed with [failure] after [took]: [AirkastException.Timeout] when the receiver
         * never took the item. A load that was cancelled reports nothing.
         */
        @Poko
        public class LoadFailed(
            override val receiver: Receiver,
            public val failure: AirkastException,
            public val took: Duration,
        ) : Event

        /**
         * A session ended, [lasted] after it opened. [failure] is null when the app closed it, and
         * [AirkastException.Disconnected] when the receiver or the network ended it.
         */
        @Poko
        public class SessionEnded(
            override val receiver: Receiver,
            public val lasted: Duration,
            public val failure: AirkastException?,
        ) : Event

        /** How a session's keys were made. New ways may join in a minor release. */
        public enum class Pairing {
            /** A pairing for this connection alone, as for a receiver that asks for no PIN. */
            Transient,

            /** Proof of a pairing made before with a PIN, from the [Airkast.credentialStore]. */
            Verified,
        }
    }

    /**
     * How to build an [Airkast]: `Airkast { logger = Airkast.Logger.println() }`. New options join
     * with defaults, so code that builds one keeps compiling across releases.
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
        public var logger: Logger? = null
        public var eventListener: ((Event) -> Unit)? = null

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
            eventListener = airkast.eventListener
        }

        public fun build(): Airkast = Airkast(this)
    }
}

/** Builds an [Airkast]: `Airkast()` with the defaults, or `Airkast { logger = Airkast.Logger.println() }`. */
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

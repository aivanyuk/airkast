package io.github.aivanyuk.airkast

/**
 * Why talking to a receiver failed. Every failure the public API reports is one of these. New
 * subclasses may join in a minor release, so a `when` over them keeps an `else` branch.
 */
public sealed class AirkastException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /**
     * The receiver refused the pairing, or answered it with something unexpected. A receiver that
     * asks for a password ends a connect here when no prompt was given to ask for it.
     */
    public class PairingFailed internal constructor(
        message: String,
        /** The receiver refused the credentials themselves, so only pairing again gets in. */
        internal val credentialsRefused: Boolean,
        /** The receiver answered SETUP with an HTTP Digest challenge, which only a password answers. */
        internal val passwordAsked: Boolean = false,
    ) : AirkastException(message) {
        public constructor(message: String) : this(message, credentialsRefused = false)
    }

    /**
     * The receiver did not take the [secret] the user gave: a wrong PIN or password. Pairing again
     * starts over.
     */
    public class SecretRejected(
        public val secret: Secret,
    ) : AirkastException(
            when (secret) {
                Secret.Pin -> "The receiver did not take the PIN"
                Secret.Password -> "The receiver did not take the password"
            },
        )

    /** The receiver answered a request with an error status. */
    public class Rejected(
        public val request: String,
        public val status: Int,
    ) : AirkastException("$request answered $status")

    /**
     * The receiver did not answer in time. The session stays open: the late answer is dropped when
     * it comes, and the session ends with [Disconnected] only if the receiver is still silent at
     * the next request. A load the receiver does not take in time ends here too.
     */
    public class Timeout(
        message: String,
    ) : AirkastException(message)

    /**
     * No connection to the receiver could be opened: it is off or asleep, on another network, or a
     * firewall or VPN is in the way.
     */
    public class Unreachable(
        cause: Throwable,
    ) : AirkastException("The receiver could not be reached", cause)

    /** The receiver answered with something this library cannot read. */
    public class UnexpectedReply(
        cause: Throwable,
    ) : AirkastException("The receiver's answer could not be read", cause)

    /** The connection to the receiver is gone: it closed, another sender took over, or the network failed. */
    public class Disconnected(
        cause: Throwable?,
    ) : AirkastException("The receiver connection closed", cause)

    /**
     * The platform does not let this app reach the local network. On Android 17, an app that
     * targets SDK 37 needs `ACCESS_LOCAL_NETWORK` granted first (docs/compatibility.md).
     */
    public class NotPermitted(
        message: String,
    ) : AirkastException(message)

    /**
     * Scanning for receivers could not start. [code] is the platform's error, such as one of
     * `NsdManager`'s `FAILURE_*` codes on Android.
     */
    public class DiscoveryFailed(
        public val code: Int,
    ) : AirkastException("Discovery failed to start: $code")
}

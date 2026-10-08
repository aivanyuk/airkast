package io.github.aivanyuk.airkast

/**
 * Why talking to a receiver failed. Every failure the public API reports is one of these. New
 * subclasses may join in a minor release, so a `when` over them keeps an `else` branch.
 */
public sealed class AirkastException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** The receiver refused the pairing, or answered it with something unexpected. */
    public class PairingFailed(
        message: String,
    ) : AirkastException(message)

    /** The receiver answered a request with an error status. */
    public class Rejected(
        public val request: String,
        public val status: Int,
    ) : AirkastException("$request answered $status")

    /** The receiver did not answer in time. A load whose session stays silent ends here too. */
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

package io.github.aivanyuk.airkast

/** Why talking to a receiver failed. */
public sealed class AirkastException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The receiver refused the pairing, or answered it with something unexpected. */
    public class PairingFailed(message: String) : AirkastException(message)

    /** The receiver answered a request with an error status. */
    public class Rejected(public val request: String, public val status: Int) :
        AirkastException("$request answered $status")

    /** The receiver did not answer in time. A load whose session stays silent ends here too. */
    public class Timeout(message: String) : AirkastException(message)

    /** The connection to the receiver is gone: it closed, another sender took over, or the network failed. */
    public class Disconnected(cause: Throwable?) : AirkastException("The receiver connection closed", cause)
}

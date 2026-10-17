package io.github.aivanyuk.airkast.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Whether this app may reach the local network, as a [state] a cast button follows: whether to
 * ask for [permission] before it scans, and whether the user has answered. The app asks itself,
 * with the activity result API, and decides how often to ask. [ReceiverDiscovery] and
 * [Airkast][io.github.aivanyuk.airkast.android.Airkast]`(context)` check the same grant, and fail
 * with [AirkastException.NotPermitted][io.github.aivanyuk.airkast.AirkastException.NotPermitted]
 * without it.
 *
 * Building one reads the grant, which the system answers from memory, so it may run on the main
 * thread.
 */
public class LocalNetworkPermission(
    context: Context,
) {
    private val context = context.applicationContext
    private val current = MutableStateFlow(read())

    /** The permission to request: [LocalNetwork.PERMISSION]. */
    public val permission: String = LocalNetwork.PERMISSION

    /**
     * The grant as last read, when this was built or at the last [refresh]. It changes only on a
     * [refresh], so a change the user makes in Settings shows after the next one.
     */
    public val state: StateFlow<State> = current.asStateFlow()

    /**
     * Reads the grant again, off the caller's thread. Call it when the screen starts, in `onStart`,
     * and with the result of the request.
     */
    public suspend fun refresh() {
        current.value = withContext(Dispatchers.IO) { read() }
    }

    private fun read(): State =
        when {
            !LocalNetwork.needsPermission(context) -> State.NotNeeded
            LocalNetwork.isAccessible(context) -> State.Granted
            else -> State.Denied
        }

    /** What [state] says. */
    public enum class State {
        /** Below Android 17, or the app targets SDK 36 or lower: the local network is open. */
        NotNeeded,

        /** The user granted [permission]. */
        Granted,

        /** The user has not granted [permission], either not asked yet or refused. Ask before scanning. */
        Denied,
    }
}

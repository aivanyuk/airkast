package io.github.aivanyuk.airkast.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
     * The grant as last read. Collecting it reads the grant again first, so a collector that starts
     * with the screen, as `collectAsStateWithLifecycle` and `repeatOnLifecycle` do, sees a change
     * the user made in Settings.
     */
    public val state: StateFlow<State> = Refreshing()

    /**
     * Reads the grant again, off the caller's thread. Call it with the result of the request, and
     * where the app reads [state]'s value without collecting it, from `onStart`.
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

    // Implementing StateFlow takes this opt-in, since kotlinx-coroutines may add members to it. The
    // delegate answers everything but collect.
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private inner class Refreshing : StateFlow<State> by current {
        override suspend fun collect(collector: FlowCollector<State>): Nothing {
            refresh()
            current.collect(collector)
        }
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

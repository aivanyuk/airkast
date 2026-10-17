package io.github.aivanyuk.airkast.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.Airkast.Logger.Level
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.Receiver
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.update
import java.util.ArrayDeque

/**
 * Finds receivers on the local network through `NsdManager`. Collect [receivers] while a picker
 * may show them; collection scans, and cancelling stops the scan. [known] keeps every receiver a
 * scan found after it stops, so [find] turns a [Receiver.id] the app kept back into a [Receiver].
 * [logger] takes the scan's lines under the tag `discovery`, as an [Airkast.logger] does.
 *
 * Build one and share it, as the client is: a second instance starts with nothing [known].
 */
public class ReceiverDiscovery(
    context: Context,
    private val logger: Airkast.Logger? = null,
) {
    private val context = context.applicationContext
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val seen = MutableStateFlow<List<Receiver>>(emptyList())

    /**
     * Every receiver this instance has found, one per [Receiver.id] with its latest record, sorted
     * by name. It outlasts the scan, and a receiver that goes away stays in it, so it says where a
     * receiver was last seen, not whether it is there now: [receivers] says that, and a connect
     * tells.
     */
    public val known: StateFlow<List<Receiver>> = seen.asStateFlow()

    /**
     * Every receiver that answers, sorted by name, including ones airkast cannot play on: filter
     * on [Receiver.compatibility]. Fails with [AirkastException.NotPermitted] when the app may
     * not reach the local network, and [AirkastException.DiscoveryFailed] when `NsdManager`
     * cannot start the scan.
     */
    public val receivers: Flow<List<Receiver>> =
        callbackFlow {
            if (!LocalNetwork.isAccessible(context)) {
                throw notPermitted().also { log(Level.Error, it) { "no local network permission" } }
            }
            val found = LinkedHashMap<String, Receiver>()
            val resolving =
                Resolver(nsd, ::log) { receiver ->
                    log(Level.Debug) { "resolved ${receiver.name}: ${receiver.model} ${receiver.compatibility}" }
                    remember(receiver)
                    synchronized(found) {
                        found[receiver.name] = receiver
                        trySend(found.values.sortedBy { it.name.lowercase() })
                    }
                }
            val listener =
                object : NsdManager.DiscoveryListener {
                    override fun onServiceFound(service: NsdServiceInfo) {
                        log(Level.Debug) { "found ${service.serviceName}" }
                        resolving.add(service)
                    }

                    override fun onServiceLost(service: NsdServiceInfo) {
                        log(Level.Debug) { "lost ${service.serviceName}" }
                        synchronized(found) {
                            if (found.remove(service.serviceName) !=
                                null
                            ) {
                                trySend(found.values.sortedBy { it.name.lowercase() })
                            }
                        }
                    }

                    override fun onStartDiscoveryFailed(
                        serviceType: String,
                        errorCode: Int,
                    ) {
                        val failure = AirkastException.DiscoveryFailed(errorCode)
                        log(Level.Error, failure) { "the scan failed to start" }
                        close(failure)
                    }

                    override fun onStopDiscoveryFailed(
                        serviceType: String,
                        errorCode: Int,
                    ) = log(Level.Warn) { "the scan failed to stop: $errorCode" }

                    override fun onDiscoveryStarted(serviceType: String) = log(Level.Debug) { "scanning" }

                    override fun onDiscoveryStopped(serviceType: String) = log(Level.Debug) { "stopped" }
                }
            trySend(emptyList())
            nsd.discoverServices(Receiver.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            awaitClose {
                runCatching { nsd.stopServiceDiscovery(listener) }
                resolving.close()
            }
        }

    /** The receiver in [known] with this [Receiver.id], or null if no scan of this instance found it. */
    public fun find(id: String): Receiver? = seen.value.firstOrNull { it.id == id }

    private fun remember(receiver: Receiver) =
        seen.update { known -> (known.filter { it.id != receiver.id } + receiver).sortedBy { it.name.lowercase() } }

    /**
     * Writes a line to [logger], if it takes [level]. A logger that throws loses the line, never the
     * scan. This is core's `Log` again, which is internal to `airkast-core`.
     */
    private fun log(
        level: Level,
        error: Throwable? = null,
        message: () -> String,
    ) {
        val logger = logger ?: return
        if (level < logger.minLevel) return
        try {
            logger.log(level, "discovery", message(), error)
        } catch (_: Exception) {
        }
    }

    /** Resolves services one at a time, since NsdManager refuses a second resolve in flight before API 34. */
    private class Resolver(
        private val nsd: NsdManager,
        private val log: (Level, Throwable?, () -> String) -> Unit,
        private val onResolved: (Receiver) -> Unit,
    ) {
        private val queue = ArrayDeque<NsdServiceInfo>()
        private var busy = false
        private var closed = false

        @Synchronized
        fun add(service: NsdServiceInfo) {
            if (closed) return
            queue.add(service)
            next()
        }

        @Synchronized
        fun close() {
            closed = true
            queue.clear()
        }

        @Synchronized
        private fun done() {
            busy = false
            next()
        }

        private fun next() {
            if (busy || closed) return
            val service = queue.poll() ?: return
            busy = true
            @Suppress("DEPRECATION")
            nsd.resolveService(
                service,
                object : NsdManager.ResolveListener {
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        info.toReceiver()?.let(onResolved)
                        done()
                    }

                    override fun onResolveFailed(
                        info: NsdServiceInfo,
                        errorCode: Int,
                    ) {
                        log(Level.Warn, null) { "resolving ${info.serviceName} failed: $errorCode" }
                        done()
                    }
                },
            )
        }
    }
}

internal fun NsdServiceInfo.toReceiver(): Receiver? {
    val address =
        if (Build.VERSION.SDK_INT >= 34) {
            hostAddresses.firstOrNull { it is java.net.Inet4Address } ?: hostAddresses.firstOrNull()
        } else {
            @Suppress("DEPRECATION")
            host
        } ?: return null
    val properties = attributes.mapValues { (_, value) -> value?.toString(Charsets.UTF_8).orEmpty() }
    return Receiver(serviceName, address.hostAddress ?: return null, port, properties)
}

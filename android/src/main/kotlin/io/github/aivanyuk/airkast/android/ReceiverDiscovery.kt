package io.github.aivanyuk.airkast.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import io.github.aivanyuk.airkast.Receiver
import java.util.ArrayDeque
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Finds receivers on the local network through `NsdManager`. Collect [receivers] while a picker
 * may show them; collection scans, and cancelling stops the scan.
 */
public class ReceiverDiscovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    /** Every receiver that answers, sorted by name, including ones that cannot play URLs. */
    public fun receivers(): Flow<List<Receiver>> = callbackFlow {
        val found = LinkedHashMap<String, Receiver>()
        val resolving = Resolver(nsd) { receiver ->
            synchronized(found) {
                found[receiver.name] = receiver
                trySend(found.values.sortedBy { it.name.lowercase() })
            }
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) = resolving.add(service)

            override fun onServiceLost(service: NsdServiceInfo) {
                synchronized(found) {
                    if (found.remove(service.serviceName) != null) trySend(found.values.sortedBy { it.name.lowercase() })
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(IllegalStateException("Discovery failed to start: $errorCode"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
        }
        trySend(emptyList())
        nsd.discoverServices(Receiver.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            resolving.close()
        }
    }

    /** Resolves services one at a time, since NsdManager refuses a second resolve in flight before API 34. */
    private class Resolver(private val nsd: NsdManager, private val onResolved: (Receiver) -> Unit) {
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

                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = done()
                },
            )
        }
    }
}

internal fun NsdServiceInfo.toReceiver(): Receiver? {
    val address = if (Build.VERSION.SDK_INT >= 34) {
        hostAddresses.firstOrNull { it is java.net.Inet4Address } ?: hostAddresses.firstOrNull()
    } else {
        @Suppress("DEPRECATION")
        host
    } ?: return null
    val properties = attributes.mapValues { (_, value) -> value?.toString(Charsets.UTF_8).orEmpty() }
    return Receiver(serviceName, address.hostAddress ?: return null, port, properties)
}

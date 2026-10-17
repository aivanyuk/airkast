package io.github.aivanyuk.airkast.android

import android.app.Application
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.Compatibility
import io.github.aivanyuk.airkast.Receiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
class ReceiverDiscoveryTest {
    private fun service(deviceId: String? = null) =
        NsdServiceInfo().apply {
            serviceName = "Big Mama"
            serviceType = "_airplay._tcp"
            port = 7000
            setAttribute("features", "0x7F8AD0,0x38BCB46")
            setAttribute("flags", "0x244")
            if (deviceId != null) setAttribute("deviceid", deviceId)
        }

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private val nsd get() = shadowOf(app.getSystemService(Context.NSD_SERVICE) as NsdManager)

    /** Collects [discovery]'s scan into [lists] until the job is cancelled. */
    private fun CoroutineScope.scan(
        discovery: ReceiverDiscovery,
        lists: MutableList<List<Receiver>> = mutableListOf(),
    ): Job {
        val job = launch(Dispatchers.Unconfined) { discovery.receivers.collect { lists += it } }
        shadowOf(Looper.getMainLooper()).idle()
        return job
    }

    /** Finds the service and resolves it at [host], as `NsdManager` reports it. */
    private fun found(
        host: String,
        deviceId: String? = null,
    ) {
        nsd.getDiscoveryListeners(Receiver.SERVICE_TYPE)!!.single().onServiceFound(service(deviceId))
        val resolved = service(deviceId).apply { hostAddresses = listOf(InetAddress.getByName(host)) }
        nsd.getResolveListeners(service(deviceId))!!.last().onServiceResolved(resolved)
    }

    @Test
    @Config(sdk = [23])
    fun marshmallowReadsTheOneHostAndTheTxtRecord() {
        val info = service()
        @Suppress("DEPRECATION")
        info.host = InetAddress.getByName("192.168.50.241")
        val receiver = info.toReceiver()!!
        assertThat(receiver.host).isEqualTo("192.168.50.241")
        assertThat(receiver.port).isEqualTo(7000)
        assertThat(receiver.compatibility).isEqualTo(Compatibility.Supported)
    }

    @Test
    @Config(sdk = [34])
    fun android14PrefersAnIpv4AmongTheHostAddresses() {
        val info = service()
        info.hostAddresses = listOf(InetAddress.getByName("fe80::1"), InetAddress.getByName("192.168.50.241"))
        assertThat(info.toReceiver()!!.host).isEqualTo("192.168.50.241")
    }

    @Test
    @Config(sdk = [37])
    fun discoveryFailsWithoutTheLocalNetworkPermission() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.applicationInfo.targetSdkVersion = 37
        assertThrows(AirkastException.NotPermitted::class.java) {
            runBlocking { ReceiverDiscovery(app).receivers.first() }
        }
    }

    @Test
    @Config(sdk = [34])
    fun aLoggerThatThrowsLeavesTheScanRunning() =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val nsd = shadowOf(app.getSystemService(Context.NSD_SERVICE) as NsdManager)
            val lines = mutableListOf<String>()
            val logger =
                Airkast.Logger { _, tag, message, _ ->
                    lines += "$tag: $message"
                    error("logger")
                }
            val lists = mutableListOf<List<Receiver>>()
            val scan =
                launch(Dispatchers.Unconfined) { ReceiverDiscovery(app, logger).receivers.collect { lists += it } }
            shadowOf(Looper.getMainLooper()).idle()
            nsd.getDiscoveryListeners(Receiver.SERVICE_TYPE)!!.single().onServiceFound(service())
            val resolved = service().apply { hostAddresses = listOf(InetAddress.getByName("192.168.50.241")) }
            nsd.getResolveListeners(service())!!.single().onServiceResolved(resolved)
            withTimeout(5_000) { while (lists.lastOrNull().isNullOrEmpty()) yield() }
            assertThat(lists.last().single().host).isEqualTo("192.168.50.241")
            assertThat(lines).contains("discovery: found Big Mama")
            assertThat(lines).contains("discovery: resolved Big Mama: null Supported")
            scan.cancel()
        }

    @Test
    @Config(sdk = [34])
    fun aReceiverThatGoesAwayStaysKnown() =
        runBlocking {
            val discovery = ReceiverDiscovery(app)
            val lists = mutableListOf<List<Receiver>>()
            val scan = scan(discovery, lists)
            found("192.168.50.241")
            withTimeout(5_000) { while (lists.lastOrNull().isNullOrEmpty()) yield() }
            nsd.getDiscoveryListeners(Receiver.SERVICE_TYPE)!!.single().onServiceLost(service())
            withTimeout(5_000) { while (lists.last().isNotEmpty()) yield() }
            val known = discovery.known.value.single()
            assertThat(known.id).isEqualTo("192.168.50.241:7000")
            scan.cancel()
        }

    @Test
    @Config(sdk = [34])
    fun aNewAddressReplacesTheRecordUnderTheSameId() =
        runBlocking {
            val discovery = ReceiverDiscovery(app)
            val scan = scan(discovery)
            found("192.168.50.241", deviceId = "AA:BB:CC:DD:EE:FF")
            found("192.168.50.17", deviceId = "AA:BB:CC:DD:EE:FF")
            val known = discovery.known.value.single()
            assertThat(known.id).isEqualTo("AA:BB:CC:DD:EE:FF")
            assertThat(known.host).isEqualTo("192.168.50.17")
            scan.cancel()
        }

    @Test
    @Config(sdk = [34])
    fun findWorksAfterTheScanStops() =
        runBlocking {
            val discovery = ReceiverDiscovery(app)
            val scan = scan(discovery)
            found("192.168.50.241", deviceId = "AA:BB:CC:DD:EE:FF")
            scan.cancel()
            scan.join()
            assertThat(discovery.find("AA:BB:CC:DD:EE:FF")?.host).isEqualTo("192.168.50.241")
            assertThat(discovery.find("192.168.50.241:7000")).isNull()
            // A second instance has its own memory.
            assertThat(ReceiverDiscovery(app).find("AA:BB:CC:DD:EE:FF")).isNull()
        }
}

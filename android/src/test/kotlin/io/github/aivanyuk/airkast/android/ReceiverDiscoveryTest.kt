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
import kotlinx.coroutines.Dispatchers
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
    private fun service() =
        NsdServiceInfo().apply {
            serviceName = "Big Mama"
            serviceType = "_airplay._tcp"
            port = 7000
            setAttribute("features", "0x7F8AD0,0x38BCB46")
            setAttribute("flags", "0x244")
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
}

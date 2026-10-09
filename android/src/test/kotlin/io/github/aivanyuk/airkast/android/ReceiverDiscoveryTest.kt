package io.github.aivanyuk.airkast.android

import android.app.Application
import android.net.nsd.NsdServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.Compatibility
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
}

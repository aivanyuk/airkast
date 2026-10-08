package io.github.aivanyuk.airkast.android

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.Receiver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkChoiceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val connectivity = shadowOf(app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
    private var nextId = 100

    @Before
    fun noNetworks() = connectivity.clearAllNetworks()

    @Test
    fun theWifiThatHoldsTheReceiverWinsOverAVpnAndMobileData() {
        // A full-tunnel VPN that also claims the LAN's subnet, mobile data, then the home Wi-Fi.
        network(NetworkCapabilities.TRANSPORT_VPN, "192.168.50.5", 24)
        network(NetworkCapabilities.TRANSPORT_CELLULAR, "100.64.3.9", 10)
        val home = network(NetworkCapabilities.TRANSPORT_WIFI, "192.168.50.17", 24)
        network(NetworkCapabilities.TRANSPORT_WIFI, "10.0.0.2", 24)

        assertThat(LocalNetwork.socketFactory(app, "192.168.50.241")).isSameInstanceAs(home)
    }

    @Test
    fun ethernetCounts() {
        val wired = network(NetworkCapabilities.TRANSPORT_ETHERNET, "192.168.1.4", 24)
        assertThat(LocalNetwork.socketFactory(app, "192.168.1.30")).isSameInstanceAs(wired)
    }

    @Test
    fun noNetworkHoldsAReceiverOutsideEverySubnetOrANameToLookUp() {
        network(NetworkCapabilities.TRANSPORT_WIFI, "192.168.50.17", 24)
        assertThat(LocalNetwork.socketFactory(app, "172.16.0.9")).isNull()
        assertThat(LocalNetwork.socketFactory(app, "big-mama.local")).isNull()
    }

    @Test
    @Config(sdk = [37])
    fun connectingFailsAtOnceWithoutTheLocalNetworkPermission() {
        app.applicationInfo.targetSdkVersion = 37
        assertThrows(AirkastException.NotPermitted::class.java) {
            runBlocking { Airkast.connect(app, Receiver("tv", "192.168.50.241")) }
        }
    }

    /** Adds a connected network and returns the socket factory that stands for it. */
    private fun network(
        transport: Int,
        address: String,
        prefixLength: Int,
    ): SocketFactory {
        val network = ShadowNetwork.newInstance(nextId++)
        val factory = NamedFactory(address)
        shadowOf(network).setSocketFactory(factory)
        connectivity.addNetwork(network, connectedInfo())
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addTransportType(transport)
        connectivity.setNetworkCapabilities(network, capabilities)
        // LinkAddress's constructor and addLinkAddress are hidden in the SDK's stubs.
        val link =
            ReflectionHelpers.callConstructor(
                LinkAddress::class.java,
                ClassParameter.from(InetAddress::class.java, InetAddress.getByName(address)),
                ClassParameter.from(Int::class.javaPrimitiveType, prefixLength),
            )
        val properties = LinkProperties()
        ReflectionHelpers.callInstanceMethod<Any>(
            properties,
            "addLinkAddress",
            ClassParameter.from(LinkAddress::class.java, link),
        )
        connectivity.setLinkProperties(network, properties)
        return factory
    }

    // Robolectric's addNetwork still takes the NetworkInfo that API 29 deprecated. Named in full, since
    // an import of a deprecated class warns outside the suppression.
    @Suppress("DEPRECATION")
    private fun connectedInfo(): android.net.NetworkInfo =
        ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI,
            0,
            true,
            android.net.NetworkInfo.State.CONNECTED,
        )

    private class NamedFactory(
        private val name: String,
    ) : SocketFactory() {
        override fun createSocket(): Socket = Socket()

        override fun createSocket(
            host: String,
            port: Int,
        ): Socket = error(name)

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int,
        ): Socket = error(name)

        override fun createSocket(
            host: InetAddress,
            port: Int,
        ): Socket = error(name)

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int,
        ): Socket = error(name)
    }
}

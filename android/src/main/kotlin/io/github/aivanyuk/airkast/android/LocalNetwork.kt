package io.github.aivanyuk.airkast.android

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import java.net.InetAddress
import javax.net.SocketFactory

/**
 * This app's way to the local network, where receivers are. Android 17 blocks it for an app that
 * targets SDK 37 or more until the user grants [PERMISSION], which the app declares and asks for
 * itself (docs/compatibility.md, "Android 17").
 */
public object LocalNetwork {
    /** `android.permission.ACCESS_LOCAL_NETWORK`, in the NEARBY_DEVICES permission group. */
    public const val PERMISSION: String = "android.permission.ACCESS_LOCAL_NETWORK"

    private const val ANDROID_17 = 37

    /** Whether this app needs [PERMISSION]: Android 17 or later, and a target SDK of 37 or more. */
    public fun needsPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= ANDROID_17 && context.applicationInfo.targetSdkVersion >= ANDROID_17

    /** Whether this app may reach the local network now. */
    public fun isAccessible(context: Context): Boolean =
        !needsPermission(context) ||
            context.checkPermission(PERMISSION, Process.myPid(), Process.myUid()) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * A socket factory on the Wi-Fi or Ethernet network whose subnet holds [host]. Without it, a
     * connection goes over the default network, which is mobile data on a Wi-Fi without internet,
     * and a VPN that takes all traffic. Null when no such network holds [host], or [host] is not
     * an IP address. Blocks briefly on the connectivity service: call it off the main thread.
     */
    public fun socketFactory(
        context: Context,
        host: String,
    ): SocketFactory? {
        val address = numericAddress(host) ?: return null
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // A one-shot lookup per connection. The replacement, a NetworkCallback, is a registration
        // to keep and release for the life of a scan, which this does not need.
        @Suppress("DEPRECATION")
        val networks = connectivity.allNetworks
        return networks
            .firstOrNull { network ->
                val capabilities = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
                val lan =
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                val links = connectivity.getLinkProperties(network)?.linkAddresses.orEmpty()
                lan &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    links.any { sameSubnet(address, it.address, it.prefixLength) }
            }?.socketFactory
    }
}

/** [host] as an address when it is an IPv4 or IPv6 literal, never through a DNS lookup. */
internal fun numericAddress(host: String): InetAddress? {
    val literal = IPV4.matches(host) || (host.contains(':') && host.all { it.isLetterOrDigit() || it in ":.%" })
    return if (literal) runCatching { InetAddress.getByName(host) }.getOrNull() else null
}

/** Whether [address] is in the subnet of [network] with [prefixLength] leading bits. */
internal fun sameSubnet(
    address: InetAddress,
    network: InetAddress,
    prefixLength: Int,
): Boolean {
    val a = address.address
    val b = network.address
    if (a.size != b.size || prefixLength !in 0..a.size * 8) return false
    for (bit in 0 until prefixLength) {
        val mask = 0x80 ushr (bit % 8)
        if (a[bit / 8].toInt() and mask != b[bit / 8].toInt() and mask) return false
    }
    return true
}

private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

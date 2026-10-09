package io.github.aivanyuk.airkast.android

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
class LocalNetworkTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    @Config(sdk = [36])
    fun androidSixteenNeedsNoPermission() {
        app.applicationInfo.targetSdkVersion = 37
        assertThat(LocalNetwork.needsPermission(app)).isFalse()
        assertThat(LocalNetwork.isAccessible(app)).isTrue()
    }

    @Test
    @Config(sdk = [37])
    fun androidSeventeenNeedsItOnlyForATargetOf37() {
        app.applicationInfo.targetSdkVersion = 36
        assertThat(LocalNetwork.needsPermission(app)).isFalse()

        app.applicationInfo.targetSdkVersion = 37
        assertThat(LocalNetwork.needsPermission(app)).isTrue()
        assertThat(LocalNetwork.isAccessible(app)).isFalse()

        shadowOf(app).grantPermissions(LocalNetwork.PERMISSION)
        assertThat(LocalNetwork.isAccessible(app)).isTrue()
    }

    @Test
    fun aReceiverIsFoundInItsSubnet() {
        val tv = InetAddress.getByName("192.168.50.241")
        assertThat(sameSubnet(tv, InetAddress.getByName("192.168.50.17"), 24)).isTrue()
        assertThat(sameSubnet(tv, InetAddress.getByName("192.168.51.17"), 24)).isFalse()
        assertThat(sameSubnet(tv, InetAddress.getByName("192.168.51.17"), 22)).isTrue()
        assertThat(sameSubnet(tv, InetAddress.getByName("fe80::1"), 64)).isFalse()
        assertThat(sameSubnet(InetAddress.getByName("fe80::aa:1"), InetAddress.getByName("fe80::1"), 64)).isTrue()
    }

    @Test
    fun onlyAnAddressLiteralIsParsed() {
        assertThat(numericAddress("192.168.50.241")).isEqualTo(InetAddress.getByName("192.168.50.241"))
        assertThat(numericAddress("fe80::1")).isNotNull()
        assertThat(numericAddress("big-mama.local")).isNull()
    }
}

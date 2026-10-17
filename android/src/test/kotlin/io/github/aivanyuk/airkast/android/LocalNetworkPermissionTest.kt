package io.github.aivanyuk.airkast.android

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.aivanyuk.airkast.android.LocalNetworkPermission.State
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class LocalNetworkPermissionTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    @Config(sdk = [34])
    fun belowAndroidSeventeenItIsNotNeeded() =
        runBlocking {
            app.applicationInfo.targetSdkVersion = 37
            val permission = LocalNetworkPermission(app)
            assertThat(permission.state.value).isEqualTo(State.NotNeeded)
            permission.refresh()
            assertThat(permission.state.value).isEqualTo(State.NotNeeded)
            assertThat(permission.permission).isEqualTo("android.permission.ACCESS_LOCAL_NETWORK")
        }

    @Test
    @Config(sdk = [37])
    fun androidSeventeenNeedsItOnlyForATargetOf37() {
        app.applicationInfo.targetSdkVersion = 36
        assertThat(LocalNetworkPermission(app).state.value).isEqualTo(State.NotNeeded)
    }

    @Test
    @Config(sdk = [37])
    fun aGrantShowsAfterARefresh() =
        runBlocking {
            app.applicationInfo.targetSdkVersion = 37
            val permission = LocalNetworkPermission(app)
            assertThat(permission.state.value).isEqualTo(State.Denied)

            shadowOf(app).grantPermissions(LocalNetwork.PERMISSION)
            assertThat(permission.state.value).isEqualTo(State.Denied)
            permission.refresh()
            assertThat(permission.state.value).isEqualTo(State.Granted)

            // As when the user takes it back in Settings.
            shadowOf(app).denyPermissions(LocalNetwork.PERMISSION)
            permission.refresh()
            assertThat(permission.state.value).isEqualTo(State.Denied)
        }

    @Test
    @Config(sdk = [37])
    fun aCollectorSeesTheGrantAfterARefresh() =
        runBlocking {
            app.applicationInfo.targetSdkVersion = 37
            val permission = LocalNetworkPermission(app)
            val seen = mutableListOf<State>()
            val collecting = launch(Dispatchers.Unconfined) { permission.state.collect { seen += it } }

            shadowOf(app).grantPermissions(LocalNetwork.PERMISSION)
            assertThat(seen).containsExactly(State.Denied)
            permission.refresh()
            yield()
            assertThat(seen).containsExactly(State.Denied, State.Granted).inOrder()
            collecting.cancel()
        }
}

package io.github.aivanyuk.airkast.media3

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.media3.AirkastPlayer.Connection
import io.github.aivanyuk.airkast.media3.AirkastSessionService.Companion.STOP_CASTING
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AirkastSessionServiceTest {
    private val sessions = mutableListOf<FakeSession>()
    private val service = Robolectric.buildService(TestService::class.java)
    private var destroyed = false

    @After
    fun release() {
        if (!destroyed) service.destroy()
        player.release()
    }

    @Test
    fun theSessionDrivesTheAppsPlayerAndOpensItsLauncherActivity() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val launcher = ComponentName(app, "com.example.Launcher")
        shadowOf(app.packageManager).apply {
            addActivityIfNotPresent(launcher)
            addIntentFilterForActivity(
                launcher,
                IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
            )
        }
        val session = start()
        assertThat(session.player).isSameInstanceAs(player)
        assertThat(shadowOf(session.sessionActivity).savedIntent.component).isEqualTo(launcher)
    }

    @Test
    fun stopCastingIsOfferedWhileACastIsOn() {
        val session = start()
        assertThat(session.mediaButtonPreferences).isEmpty()

        casting()
        val button = session.mediaButtonPreferences.single()
        assertThat(button.sessionCommand).isEqualTo(STOP_CASTING)
        assertThat(button.displayName.toString()).isEqualTo("Stop casting")

        player.disconnect()
        idle()
        assertThat(session.mediaButtonPreferences).isEmpty()
    }

    @Test
    fun stopCastingEndsTheCast() {
        val session = start()
        casting()
        val callback = AirkastSessionService.StopCasting(player)
        val result = callback.onCustomCommand(session, controller(trusted = true), STOP_CASTING, Bundle.EMPTY)
        idle()
        assertThat(result.get().resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
        assertThat(sessions.single().closed).isTrue()
        assertThat(player.connection.value).isInstanceOf(Connection.Idle::class.java)
    }

    @Test
    fun otherCommandsAreNotSupported() {
        val session = start()
        val other = SessionCommand("other", Bundle.EMPTY)
        val callback = AirkastSessionService.StopCasting(player)
        val result = callback.onCustomCommand(session, controller(trusted = true), other, Bundle.EMPTY)
        assertThat(result.get().resultCode).isEqualTo(SessionError.ERROR_NOT_SUPPORTED)
    }

    @Test
    fun onlyTrustedControllersMayStopTheCast() {
        val session = start()
        val callback = AirkastSessionService.StopCasting(player)
        val trusted = callback.onConnectAsync(session, controller(trusted = true)).get()
        val untrusted = callback.onConnectAsync(session, controller(trusted = false)).get()
        assertThat(trusted.availableSessionCommands.contains(STOP_CASTING)).isTrue()
        assertThat(untrusted.availableSessionCommands.contains(STOP_CASTING)).isFalse()
    }

    @Test
    fun theServiceReleasesTheSessionButNotThePlayer() {
        start()
        casting()
        service.destroy()
        destroyed = true
        idle()
        assertThat(sessions.single().closed).isFalse()
        assertThat(player.connection.value).isInstanceOf(Connection.Connected::class.java)
    }

    private fun start(): MediaSession {
        service.create()
        return service.get().onGetSession(controller(trusted = true))!!
    }

    private fun casting() {
        player.setMediaItem(MediaItem.fromUri("https://example.com/master.m3u8"))
        player.connect(Receiver("tv", "192.0.2.1"))
        idle()
        check(player.connection.value is Connection.Connected)
    }

    private fun controller(trusted: Boolean) =
        MediaSession.ControllerInfo.createTestOnlyControllerInfo(
            "com.example.controller",
            0,
            0,
            MediaLibraryInfo.VERSION_INT,
            0,
            trusted,
            Bundle.EMPTY,
            true,
        )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    class TestService : AirkastSessionService() {
        override val player: AirkastPlayer get() = AirkastSessionServiceTest.player
    }

    private companion object {
        lateinit var player: AirkastPlayer
    }

    init {
        player =
            AirkastPlayer(ApplicationProvider.getApplicationContext(), Airkast()) {
                connector = Connector { receiver, _, _ -> FakeSession(receiver).also { sessions += it } }
            }
    }
}

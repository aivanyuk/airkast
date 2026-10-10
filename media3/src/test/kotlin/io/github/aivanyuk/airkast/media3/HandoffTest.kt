package io.github.aivanyuk.airkast.media3

import android.os.Looper
import android.view.TextureView
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.ReceiverEvent
import io.github.aivanyuk.airkast.media3.AirkastPlayer.Connection
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/** [AirkastPlayer.Builder.localPlayer]: the item moves to the TV and back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HandoffTest {
    private val sessions = mutableListOf<FakeSession>()
    private var failure: AirkastException? = null
    private val local = FakeLocalPlayer()
    private val player =
        AirkastPlayer(ApplicationProvider.getApplicationContext(), Airkast()) {
            localPlayer = local
            connector =
                Connector { receiver, _, _ ->
                    failure?.let { throw it }
                    FakeSession(receiver).also { sessions += it }
                }
        }
    private val tv = Receiver("tv", "192.0.2.1")
    private val item = MediaItem.fromUri("https://example.com/master.m3u8")

    @After
    fun release() {
        player.release()
    }

    @Test
    fun playsLocallyUntilACastStarts() {
        playingHere(at = 120_000)
        assertThat(player.deviceInfo.playbackType).isEqualTo(DeviceInfo.PLAYBACK_TYPE_LOCAL)
        assertThat(player.isPlaying).isTrue()
        assertThat(player.currentPosition).isEqualTo(120_000)
    }

    @Test
    fun aCastTakesTheItemWhereItWas() {
        playingHere(at = 120_000)
        player.connect(tv)
        // The phone plays on while the receiver connects.
        assertThat(player.deviceInfo.playbackType).isEqualTo(DeviceInfo.PLAYBACK_TYPE_LOCAL)
        idle()
        val session = sessions.single()
        assertThat(session.loaded?.url).isEqualTo("https://example.com/master.m3u8")
        assertThat(session.loaded?.startAt).isEqualTo(120.seconds)
        assertThat(local.playbackState).isEqualTo(Player.STATE_IDLE)
        assertThat(player.deviceInfo.playbackType).isEqualTo(DeviceInfo.PLAYBACK_TYPE_REMOTE)
        assertThat(player.isPlaying).isTrue()
    }

    @Test
    fun disconnectGivesTheItemBackPausedWhereTheTvLeftIt() {
        val session = casting()
        session.emit(ReceiverEvent.TimeJumped(300.seconds))
        idle()
        player.disconnect()
        idle()
        assertThat(session.sent.last()).isEqualTo("stop")
        assertThat(session.closed).isTrue()
        assertThat(player.deviceInfo.playbackType).isEqualTo(DeviceInfo.PLAYBACK_TYPE_LOCAL)
        assertThat(player.currentMediaItem).isEqualTo(item)
        assertThat(player.currentPosition).isAtLeast(300_000)
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
        assertThat(player.playWhenReady).isFalse()
        assertThat(player.connection.value).isInstanceOf(Connection.Idle::class.java)
    }

    @Test
    fun backOnTheTvGivesTheItemBack() {
        val session = casting()
        session.emit(ReceiverEvent.Back)
        idle()
        assertThat(session.closed).isTrue()
        assertThat(player.deviceInfo.playbackType).isEqualTo(DeviceInfo.PLAYBACK_TYPE_LOCAL)
        assertThat(player.currentMediaItem).isEqualTo(item)
    }

    @Test
    fun aDroppedCastGivesTheItemBackAndSaysWhy() {
        val session = casting()
        session.emit(ReceiverEvent.TimeJumped(300.seconds))
        session.emit(ReceiverEvent.Disconnected(IOException("Connection reset")))
        idle()
        assertThat((player.connection.value as Connection.Idle).failure)
            .isInstanceOf(AirkastException.Disconnected::class.java)
        assertThat(player.deviceInfo.playbackType).isEqualTo(DeviceInfo.PLAYBACK_TYPE_LOCAL)
        assertThat(player.currentPosition).isAtLeast(300_000)
        assertThat(player.playerError).isNull()

        // Play resumes on the phone rather than connecting again.
        player.play()
        idle()
        assertThat(sessions).hasSize(1)
        assertThat(player.isPlaying).isTrue()
    }

    @Test
    fun aFailedConnectLeavesThePhonePlaying() {
        failure = AirkastException.Unreachable(IOException("No route to host"))
        playingHere(at = 120_000)
        player.connect(tv)
        idle()
        assertThat((player.connection.value as Connection.Idle).failure)
            .isInstanceOf(AirkastException.Unreachable::class.java)
        assertThat(player.isPlaying).isTrue()
        assertThat(player.playerError).isNull()
    }

    @Test
    fun aPlaylistKeepsItsOtherItems() {
        val items = List(3) { MediaItem.fromUri("https://example.com/$it.m3u8") }
        player.setMediaItems(items, 1, 0)
        player.prepare()
        player.play()
        player.connect(tv)
        idle()
        assertThat(sessions.single().loaded?.url).isEqualTo("https://example.com/1.m3u8")
        assertThat(player.mediaItemCount).isEqualTo(1)

        player.disconnect()
        idle()
        assertThat(player.mediaItemCount).isEqualTo(3)
        assertThat(player.currentMediaItemIndex).isEqualTo(1)
    }

    @Test
    fun anItemCastLaterComesBackInsteadOfTheOneBefore() {
        casting()
        val other = MediaItem.fromUri("https://example.com/other.m3u8")
        player.setMediaItem(other)
        idle()
        assertThat(sessions.single().loaded?.url).isEqualTo("https://example.com/other.m3u8")
        player.disconnect()
        idle()
        assertThat(local.items).containsExactly(other)
    }

    @Test
    fun theSurfaceGoesToThePhoneEvenDuringACast() {
        casting()
        assertThat(player.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)).isTrue()
        val view = TextureView(ApplicationProvider.getApplicationContext())
        player.setVideoTextureView(view)
        assertThat(local.output).isSameInstanceAs(view)
        player.clearVideoTextureView(view)
        assertThat(local.output).isNull()
    }

    @Test
    fun aSessionSetByHandTakesTheItemAndGivesItBack() {
        playingHere(at = 60_000)
        val session = FakeSession()
        player.session = session
        idle()
        assertThat(session.loaded?.startAt).isEqualTo(60.seconds)
        assertThat(player.deviceInfo.playbackType).isEqualTo(DeviceInfo.PLAYBACK_TYPE_REMOTE)

        player.session = null
        idle()
        assertThat(session.closed).isFalse()
        assertThat(player.deviceInfo.playbackType).isEqualTo(DeviceInfo.PLAYBACK_TYPE_LOCAL)
        assertThat(player.currentMediaItem).isEqualTo(item)
    }

    @Test
    fun releaseReleasesTheLocalPlayerAndClosesTheSession() {
        casting()
        player.release()
        assertThat(sessions.single().closed).isTrue()
        assertThat(local.released).isTrue()
    }

    private fun playingHere(at: Long) {
        player.setMediaItem(item, at)
        player.prepare()
        player.play()
        idle()
    }

    private fun casting(): FakeSession {
        playingHere(at = 0)
        player.connect(tv)
        idle()
        check(player.isPlaying && player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE)
        return sessions.last()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}

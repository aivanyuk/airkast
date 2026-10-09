package io.github.aivanyuk.airkast.media3

import android.os.Looper
import android.os.PowerManager
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.PlaybackState
import io.github.aivanyuk.airkast.ReceiverEvent
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import java.io.IOException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import java.time.Duration as JavaDuration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AirkastPlayerTest {
    private val fake = FakeSession()
    private val player = AirkastPlayer(ApplicationProvider.getApplicationContext())
    private val item = MediaItem.fromUri("https://example.com/master.m3u8")

    @After
    fun release() {
        player.release()
    }

    @Test
    fun anItemSetBeforeASessionLoadsWhenOneAttaches() {
        player.setMediaItem(item, 600_000)
        player.playWhenReady = true
        idle()
        assertThat(fake.sent).isEmpty()
        assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)

        player.session = fake
        idle()
        assertThat(fake.loaded?.url).isEqualTo("https://example.com/master.m3u8")
        assertThat(fake.loaded?.startAt).isEqualTo(10.minutes)
        assertThat(fake.loaded?.streaming).isTrue()
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
        assertThat(player.isPlaying).isTrue()
        assertThat(wakeLock().isHeld).isTrue()
    }

    @Test
    fun anItemSetWithASessionLoadsAtOnce() {
        player.session = fake
        player.playWhenReady = true
        player.setMediaItem(item)
        assertThat(player.playbackState).isEqualTo(Player.STATE_BUFFERING)
        idle()
        assertThat(fake.sent).containsExactly("load")
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
    }

    @Test
    fun anItemLoadedPausedStaysPaused() {
        player.session = fake
        player.setMediaItem(item)
        idle()
        assertThat(fake.sent).containsExactly("load", "pause").inOrder()
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
        assertThat(player.isPlaying).isFalse()
    }

    @Test
    fun pauseAndSeekGoToTheReceiver() {
        playing()
        player.pause()
        player.seekTo(1_200_000)
        assertThat(player.currentPosition).isEqualTo(1_200_000)
        idle()
        assertThat(fake.sent).containsExactly("load", "pause", "seek 20m").inOrder()
        assertThat(player.playWhenReady).isFalse()
        assertThat(player.currentPosition).isEqualTo(1_200_000)
    }

    @Test
    fun aPauseWhileRebufferingReachesTheReceiver() {
        playing()
        fake.emit(ReceiverEvent.StateChanged(PlaybackState.Loading, null))
        idle()
        assertThat(player.playbackState).isEqualTo(Player.STATE_BUFFERING)
        player.pause()
        idle()
        assertThat(fake.sent.last()).isEqualTo("pause")
        fake.emit(ReceiverEvent.StateChanged(PlaybackState.Paused, null))
        idle()
        assertThat(player.playWhenReady).isFalse()
    }

    @Test
    fun aPlayWhileAPausedLoadRebuffersReachesTheReceiver() {
        player.session = fake
        player.setMediaItem(item)
        idle()
        fake.emit(ReceiverEvent.StateChanged(PlaybackState.Loading, null))
        idle()
        player.play()
        idle()
        assertThat(fake.sent).containsExactly("load", "pause", "play").inOrder()
        assertThat(player.isPlaying).isTrue()
    }

    @Test
    fun streamingOffLoadsItemsAsFiles() {
        val files = AirkastPlayer(ApplicationProvider.getApplicationContext()) { streaming = false }
        files.session = fake
        files.setMediaItem(item)
        idle()
        assertThat(fake.loaded?.streaming).isFalse()
        files.release()
    }

    @Test
    fun theTvsRemoteReachesThePlayer() {
        playing()
        fake.emit(ReceiverEvent.StateChanged(PlaybackState.Paused, null))
        idle()
        assertThat(player.playWhenReady).isFalse()
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)

        fake.emit(ReceiverEvent.RateChanged(1.0, 30.seconds))
        idle()
        assertThat(player.isPlaying).isTrue()
        assertThat(player.currentPosition).isAtLeast(30_000)

        fake.emit(ReceiverEvent.TimeJumped(90.seconds))
        idle()
        assertThat(player.currentPosition).isAtLeast(90_000)
    }

    @Test
    fun thePollKeepsThePositionAndDuration() {
        playing()
        fake.info = fake.info.copy(position = 100.seconds)
        shadowOf(Looper.getMainLooper()).idleFor(JavaDuration.ofMillis(1_000))
        assertThat(player.duration).isEqualTo(3_077_000)
        assertThat(player.currentPosition).isIn(
            com.google.common.collect.Range
                .closed(100_000L, 101_000L),
        )
        assertThat(player.isCurrentMediaItemSeekable).isTrue()
    }

    @Test
    fun aStreamWithoutADurationIsLive() {
        playing()
        fake.info = fake.info.copy(duration = null)
        shadowOf(Looper.getMainLooper()).idleFor(JavaDuration.ofMillis(1_000))
        assertThat(player.isCurrentMediaItemDynamic).isTrue()
        assertThat(player.isCurrentMediaItemSeekable).isFalse()
    }

    @Test
    fun theEndOfTheItemEndsThePlayerAndLetsThePhoneSleep() {
        playing()
        fake.emit(ReceiverEvent.ItemEnded(FakeSession.ITEM))
        idle()
        assertThat(player.playbackState).isEqualTo(Player.STATE_ENDED)
        assertThat(wakeLock().isHeld).isFalse()

        player.seekTo(0)
        idle()
        assertThat(fake.sent.last()).isEqualTo("load")
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
    }

    @Test
    fun anotherItemsEndIsIgnored() {
        playing()
        fake.emit(ReceiverEvent.ItemEnded("an earlier item"))
        idle()
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
    }

    @Test
    fun aFailedConnectionIsAnError() {
        playing()
        fake.emit(ReceiverEvent.Disconnected(IOException("Connection reset")))
        idle()
        assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)
        assertThat(player.playerError?.errorCode).isEqualTo(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        assertThat(player.session).isNull()
        assertThat(wakeLock().isHeld).isFalse()
    }

    @Test
    fun aClosedSessionIsNoError() {
        playing()
        fake.close()
        idle()
        assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)
        assertThat(player.playerError).isNull()
        assertThat(player.session).isNull()
    }

    @Test
    fun aLoadTheReceiverDoesNotTakeIsATimeout() {
        fake.loadError = AirkastException.Timeout("The receiver did not take the item")
        player.session = fake
        player.setMediaItem(item)
        idle()
        assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)
        assertThat(player.playerError?.errorCode).isEqualTo(PlaybackException.ERROR_CODE_TIMEOUT)

        fake.loadError = null
        player.prepare()
        idle()
        assertThat(player.playerError).isNull()
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
    }

    @Test
    fun stopLeavesTheItemAndTheSession() {
        playing()
        player.stop()
        idle()
        assertThat(fake.sent.last()).isEqualTo("stop")
        assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)
        assertThat(player.currentMediaItem).isEqualTo(item)
        assertThat(player.session).isSameInstanceAs(fake)
        assertThat(wakeLock().isHeld).isFalse()
    }

    @Test
    fun theTvsVolumeCanBeReadButNotSet() {
        playing()
        assertThat(player.deviceVolume).isEqualTo(50)
        fake.emit(ReceiverEvent.VolumeChanged(0.8))
        idle()
        assertThat(player.deviceVolume).isEqualTo(80)
        assertThat(player.deviceInfo.playbackType).isEqualTo(androidx.media3.common.DeviceInfo.PLAYBACK_TYPE_REMOTE)
        assertThat(player.isCommandAvailable(Player.COMMAND_GET_DEVICE_VOLUME)).isTrue()
        assertThat(player.isCommandAvailable(Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS)).isFalse()
    }

    @Test
    fun aNewSessionPicksUpWhereTheLostOneLeftOff() {
        playing()
        fake.emit(ReceiverEvent.TimeJumped(300.seconds))
        fake.emit(ReceiverEvent.Disconnected(IOException("Connection reset")))
        idle()
        val next = FakeSession()
        player.session = next
        idle()
        assertThat(next.loaded?.startAt).isAtLeast(300.seconds)
        assertThat(player.playerError).isNull()
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
    }

    @Test
    fun noLocksWhenKeepAwakeIsOff() {
        val quiet = AirkastPlayer(ApplicationProvider.getApplicationContext()) { keepAwake = false }
        quiet.session = fake
        quiet.setMediaItem(item)
        quiet.play()
        idle()
        assertThat(quiet.isPlaying).isTrue()
        // The class's own player created the latest lock, and never took it.
        assertThat(wakeLock().isHeld).isFalse()
        quiet.release()
    }

    private fun playing() {
        player.session = fake
        player.setMediaItem(item)
        player.play()
        idle()
        check(player.isPlaying)
    }

    private fun wakeLock(): PowerManager.WakeLock = ShadowPowerManager.getLatestWakeLock()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun io.github.aivanyuk.airkast.PlaybackInfo.copy(
        position: kotlin.time.Duration? = this.position,
        duration: kotlin.time.Duration? = this.duration,
    ) = io.github.aivanyuk.airkast
        .PlaybackInfo(state, rate, position, duration, buffered, seekable, itemId)
}

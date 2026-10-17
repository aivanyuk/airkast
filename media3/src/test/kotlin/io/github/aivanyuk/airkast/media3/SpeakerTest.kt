package io.github.aivanyuk.airkast.media3

import android.os.Looper
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.AudioStream
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.ReceiverEvent
import io.github.aivanyuk.airkast.Secret
import io.github.aivanyuk.airkast.media3.AirkastPlayer.Connection
import io.github.aivanyuk.airkast.media3.AirkastPlayer.Event
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/** [AirkastPlayer.Builder.audioRoute]: a cast to a speaker streams the local player's audio. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeakerTest {
    private val streams = mutableListOf<FakeAudioStream>()
    private val sessions = mutableListOf<FakeSession>()
    private val routed = mutableListOf<AudioStream?>()
    private val events = mutableListOf<Event>()
    private var failure: AirkastException? = null
    private var password: String? = null
    private val local = FakeLocalPlayer()
    private var streamsTo: (Receiver) -> Boolean = { it.name == "soundbar" }
    private val player =
        AirkastPlayer(ApplicationProvider.getApplicationContext(), Airkast()) {
            localPlayer = local
            audioRoute = AudioRouteFake()
            streamsAudio = { streamsTo(it) }
            eventListener = { events += it }
            connector = Connector { receiver, _, _ -> FakeSession(receiver).also { sessions += it } }
            audioConnector =
                AudioConnector { receiver, _, ask ->
                    val wanted = password
                    if (wanted != null && ask(Secret.Password) != wanted) {
                        throw AirkastException.SecretRejected(Secret.Password)
                    }
                    failure?.let { throw it }
                    FakeAudioStream(receiver).also { streams += it }
                }
        }
    private val soundbar = Receiver("soundbar", "192.0.2.3")
    private val tv = Receiver("tv", "192.0.2.1")
    private val item =
        MediaItem
            .Builder()
            .setUri("https://example.com/song.mp3")
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle("Song")
                    .setArtist("Artist")
                    .setAlbumTitle("Album")
                    .build(),
            ).build()

    private inner class AudioRouteFake : io.github.aivanyuk.airkast.AudioRoute {
        override fun attach(stream: AudioStream?) {
            routed += stream
        }
    }

    @After
    fun release() {
        player.release()
    }

    @Test
    fun aSpeakerTakesTheLocalPlayersAudioAndTheLocalPlayerPlaysOn() {
        playingHere(at = 42_000)
        player.connect(soundbar)
        idle()
        val stream = streams.single()
        assertThat(routed).containsExactly(stream)
        assertThat(player.connection.value).isInstanceOf(Connection.Streaming::class.java)
        assertThat((player.connection.value as Connection.Streaming).stream).isSameInstanceAs(stream)
        assertThat(sessions).isEmpty()
        // The local player plays on, and sends its audio again from where it was.
        assertThat(player.isPlaying).isTrue()
        assertThat(local.seeks.last()).isEqualTo(0 to 42_000L)
        assertThat(events.single()).isInstanceOf(Event.CastStarted::class.java)
        val (playing, elapsed) = stream.nowPlaying.last()
        assertThat(playing.title).isEqualTo("Song")
        assertThat(playing.artist).isEqualTo("Artist")
        assertThat(playing.album).isEqualTo("Album")
        assertThat(elapsed).isEqualTo(42.seconds)
    }

    @Test
    fun theDeviceVolumeIsTheSpeakers() {
        playingHere(at = 0)
        player.connect(soundbar)
        idle()
        val stream = streams.single()
        assertThat(player.deviceInfo.playbackType).isEqualTo(DeviceInfo.PLAYBACK_TYPE_REMOTE)
        assertThat(player.deviceVolume).isEqualTo(40)
        player.setDeviceVolume(70, 0)
        idle()
        assertThat(stream.volumes.last()).isWithin(0.001).of(0.7)
        player.increaseDeviceVolume(0)
        idle()
        assertThat(player.deviceVolume).isEqualTo(75)
        player.disconnect()
        idle()
        assertThat(player.deviceInfo.playbackType).isEqualTo(DeviceInfo.PLAYBACK_TYPE_LOCAL)
    }

    @Test
    fun disconnectBringsTheAudioBack() {
        playingHere(at = 10_000)
        player.connect(soundbar)
        idle()
        val stream = streams.single()
        local.moveTo(15_000)
        player.disconnect()
        idle()
        assertThat(routed).containsExactly(stream, null).inOrder()
        assertThat(stream.closed).isTrue()
        assertThat(local.seeks.last()).isEqualTo(0 to 15_000L)
        assertThat(player.connection.value).isInstanceOf(Connection.Idle::class.java)
        assertThat(player.currentMediaItem).isEqualTo(item)
        assertThat((events.last() as Event.CastEnded).reason).isEqualTo(Event.EndReason.Disconnect)
    }

    @Test
    fun aStreamThatDropsEndsTheCastWithItsFailure() {
        playingHere(at = 0)
        player.connect(soundbar)
        idle()
        val stream = streams.single()
        stream.emit(ReceiverEvent.Disconnected(IOException("gone")))
        idle()
        assertThat(routed.last()).isNull()
        val idle = player.connection.value as Connection.Idle
        assertThat(idle.receiver).isEqualTo(soundbar)
        assertThat(idle.failure).isInstanceOf(AirkastException.Disconnected::class.java)
        val ended = events.last() as Event.CastEnded
        assertThat(ended.reason).isEqualTo(Event.EndReason.Lost)
        assertThat(ended.failure).isNotNull()
    }

    @Test
    fun aFailedConnectLeavesTheLocalPlayerAlone() {
        playingHere(at = 0)
        failure = AirkastException.Unreachable(IOException("no route"))
        player.connect(soundbar)
        idle()
        assertThat(routed).isEmpty()
        assertThat((player.connection.value as Connection.Idle).failure).isSameInstanceAs(failure)
        assertThat(events.single()).isInstanceOf(Event.CastFailed::class.java)
        assertThat(player.isPlaying).isTrue()
    }

    @Test
    fun aSpeakerThatRefusesItsPasswordAsksAgain() {
        playingHere(at = 0)
        password = "hunter2"
        player.connect(soundbar)
        idle()
        player.enterSecret("hunter1")
        idle()
        assertThat((player.connection.value as Connection.AwaitingSecret).rejected).isEqualTo(1)
        player.enterSecret("hunter2")
        idle()
        assertThat(player.connection.value).isInstanceOf(Connection.Streaming::class.java)
        assertThat(events.single()).isInstanceOf(Event.CastStarted::class.java)
    }

    @Test
    fun aTvStillTakesTheUrlAndAConnectThereEndsTheStream() {
        playingHere(at = 0)
        player.connect(soundbar)
        idle()
        player.connect(tv)
        idle()
        assertThat(streams.single().closed).isTrue()
        assertThat(routed.last()).isNull()
        assertThat(sessions.single().loaded?.url).isEqualTo("https://example.com/song.mp3")
        assertThat(player.connection.value).isInstanceOf(Connection.Connected::class.java)
    }

    @Test
    fun aRouteNeedsALocalPlayer() {
        assertThrows(IllegalArgumentException::class.java) {
            AirkastPlayer(ApplicationProvider.getApplicationContext(), Airkast()) { audioRoute = AudioRouteFake() }
        }
    }

    private fun playingHere(at: Long) {
        player.setMediaItem(item, at)
        player.prepare()
        player.play()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}

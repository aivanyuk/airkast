package io.github.aivanyuk.airkast.media3

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.AirkastException
import io.github.aivanyuk.airkast.AirkastSession
import io.github.aivanyuk.airkast.Receiver
import io.github.aivanyuk.airkast.ReceiverEvent
import io.github.aivanyuk.airkast.media3.AirkastPlayer.Connection
import io.github.aivanyuk.airkast.media3.AirkastPlayer.Event
import io.github.aivanyuk.airkast.media3.AirkastPlayer.Event.EndReason
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/** [AirkastPlayer.connect] and what follows it: the player runs the cast. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectTest {
    private val connector = FakeConnector()
    private val heard = mutableListOf<Event>()
    private val player = player()
    private val tv = Receiver("tv", "192.0.2.1")
    private val item = MediaItem.fromUri("https://example.com/master.m3u8")

    @After
    fun release() {
        player.release()
    }

    @Test
    fun connectOpensASessionAndPlaysTheItemOnIt() {
        player.setMediaItem(item)
        player.play()
        player.connect(tv)
        assertThat(player.connection.value).isInstanceOf(Connection.Connecting::class.java)
        idle()
        val session = connector.sessions.single()
        assertThat((player.connection.value as Connection.Connected).session).isSameInstanceAs(session)
        assertThat(player.session).isSameInstanceAs(session)
        assertThat(session.loaded?.url).isEqualTo("https://example.com/master.m3u8")
        assertThat(player.isPlaying).isTrue()
    }

    @Test
    fun aReceiverThatAsksForAPinWaitsForIt() {
        connector.asksForPin = true
        player.connect(tv)
        idle()
        assertThat((player.connection.value as Connection.AwaitingPin).receiver).isEqualTo(tv)
        player.enterPin("2468")
        idle()
        assertThat(connector.pin).isEqualTo("2468")
        assertThat(player.connection.value).isInstanceOf(Connection.Connected::class.java)
    }

    @Test
    fun withPinPairsAnyReceiver() {
        player.connect(tv, withPin = true)
        idle()
        assertThat(player.connection.value).isInstanceOf(Connection.AwaitingPin::class.java)
        assertThat(connector.asked.single().second).isTrue()
    }

    @Test
    fun aFailedConnectIsIdleWithTheFailureAndPrepareTriesAgain() {
        connector.failure = AirkastException.Unreachable(IOException("No route to host"))
        player.setMediaItem(item)
        player.connect(tv)
        idle()
        val idle = player.connection.value as Connection.Idle
        assertThat(idle.receiver).isEqualTo(tv)
        assertThat(idle.failure).isInstanceOf(AirkastException.Unreachable::class.java)
        assertThat(player.playerError?.errorCode).isEqualTo(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)

        connector.failure = null
        player.prepare()
        idle()
        assertThat(player.connection.value).isInstanceOf(Connection.Connected::class.java)
        assertThat(player.playerError).isNull()
        assertThat(connector.asked).hasSize(2)
    }

    @Test
    fun disconnectStopsAndClosesTheSessionAndClearsTheItem() {
        val session = casting()
        player.disconnect()
        idle()
        assertThat(session.sent.last()).isEqualTo("stop")
        assertThat(session.closed).isTrue()
        assertThat(player.session).isNull()
        assertThat(player.mediaItemCount).isEqualTo(0)
        assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)
        val idle = player.connection.value as Connection.Idle
        assertThat(idle.failure).isNull()
        assertThat(idle.receiver).isNull()
    }

    @Test
    fun disconnectGivesUpAConnectInProgress() {
        val hold = CompletableDeferred<Unit>().also { connector.hold = it }
        player.connect(tv)
        idle()
        player.disconnect()
        hold.complete(Unit)
        idle()
        assertThat(connector.sessions).isEmpty()
        assertThat(player.session).isNull()
        assertThat(player.connection.value).isInstanceOf(Connection.Idle::class.java)
    }

    @Test
    fun disconnectGivesUpAPinPrompt() {
        connector.asksForPin = true
        player.connect(tv)
        idle()
        player.disconnect()
        player.enterPin("2468")
        idle()
        assertThat(connector.pin).isNull()
        assertThat(player.connection.value).isInstanceOf(Connection.Idle::class.java)
    }

    @Test
    fun backOnTheTvEndsACastThePlayerOpened() {
        val session = casting()
        session.emit(ReceiverEvent.Back)
        idle()
        assertThat(session.sent.last()).isEqualTo("stop")
        assertThat(session.closed).isTrue()
        assertThat(player.connection.value).isInstanceOf(Connection.Idle::class.java)
    }

    @Test
    fun backIsLeftToTheAppWhenItAsks() {
        val own = player { disconnectOnBack = false }
        own.setMediaItem(item)
        own.connect(tv)
        idle()
        val session = connector.sessions.single()
        session.emit(ReceiverEvent.Back)
        idle()
        assertThat(session.sent).doesNotContain("stop")
        assertThat(own.connection.value).isInstanceOf(Connection.Connected::class.java)
        own.release()
    }

    @Test
    fun aSessionTheAppSetIsTheAppsToStopAndClose() {
        val session = FakeSession()
        player.session = session
        player.setMediaItem(item)
        idle()
        assertThat(player.connection.value).isInstanceOf(Connection.Connected::class.java)
        session.emit(ReceiverEvent.Back)
        idle()
        assertThat(session.sent).doesNotContain("stop")

        player.disconnect()
        idle()
        assertThat(session.sent.last()).isEqualTo("stop")
        assertThat(session.closed).isFalse()
    }

    @Test
    fun connectingElsewhereMovesTheCastAndItsPosition() {
        val first = casting()
        first.emit(ReceiverEvent.TimeJumped(300.seconds))
        idle()
        val other = Receiver("bedroom", "192.0.2.2")
        player.connect(other)
        idle()
        assertThat(first.sent.last()).isEqualTo("stop")
        assertThat(first.closed).isTrue()
        val second = connector.sessions.last()
        assertThat(second.receiver).isEqualTo(other)
        assertThat(second.loaded?.startAt).isAtLeast(300.seconds)
        assertThat(player.isPlaying).isTrue()
    }

    @Test
    fun aDroppedCastIsIdleWithItsCauseAndPrepareReconnects() {
        val first = casting()
        first.emit(ReceiverEvent.TimeJumped(300.seconds))
        first.emit(ReceiverEvent.Disconnected(IOException("Connection reset")))
        idle()
        val idle = player.connection.value as Connection.Idle
        assertThat(idle.receiver).isEqualTo(tv)
        assertThat(idle.failure).isInstanceOf(AirkastException.Disconnected::class.java)
        assertThat(player.currentMediaItem).isEqualTo(item)

        player.prepare()
        idle()
        val second = connector.sessions.last()
        assertThat(second).isNotSameInstanceAs(first)
        assertThat(second.loaded?.startAt).isAtLeast(300.seconds)
        assertThat(player.connection.value).isInstanceOf(Connection.Connected::class.java)
    }

    @Test
    fun releaseClosesTheSessionThePlayerOpened() {
        val own = player()
        own.setMediaItem(item)
        own.connect(tv)
        idle()
        own.release()
        assertThat(connector.sessions.single().closed).isTrue()
    }

    @Test
    fun aCastReportsItsStartAndItsEnd() {
        casting()
        player.disconnect()
        idle()
        assertThat(heard.map { it::class }).containsExactly(Event.CastStarted::class, Event.CastEnded::class).inOrder()
        assertThat(heard.map { it.receiver }.distinct()).containsExactly(tv)
        val ended = heard[1] as Event.CastEnded
        assertThat(ended.reason).isEqualTo(EndReason.Disconnect)
        assertThat(ended.failure).isNull()
    }

    @Test
    fun eachWayACastEndsIsReported() {
        casting().emit(ReceiverEvent.Back)
        idle()
        casting().emit(ReceiverEvent.Disconnected(IOException("Connection reset")))
        idle()
        casting()
        player.connect(Receiver("bedroom", "192.0.2.2"))
        idle()
        player.session = FakeSession()
        player.session = null
        player.release()
        val ended = heard.filterIsInstance<Event.CastEnded>()
        assertThat(ended.map { it.reason })
            .containsExactly(
                EndReason.Back,
                EndReason.Lost,
                EndReason.Replaced,
                EndReason.Replaced,
                EndReason.Disconnect,
            ).inOrder()
        assertThat(ended[1].failure).isInstanceOf(AirkastException.Disconnected::class.java)
        assertThat(ended.filter { it.reason != EndReason.Lost }.map { it.failure }.distinct()).containsExactly(null)
    }

    @Test
    fun releaseEndsTheCast() {
        val own = player()
        own.setMediaItem(item)
        own.connect(tv)
        idle()
        own.release()
        assertThat((heard.last() as Event.CastEnded).reason).isEqualTo(EndReason.Released)
    }

    @Test
    fun aFailedConnectIsReported() {
        connector.failure = AirkastException.Unreachable(IOException("No route to host"))
        player.connect(tv)
        idle()
        val failed = heard.single() as Event.CastFailed
        assertThat(failed.failure).isSameInstanceAs(connector.failure)
    }

    @Test
    fun aPinPromptGivenUpIsReported() {
        connector.asksForPin = true
        player.connect(tv)
        idle()
        player.disconnect()
        idle()
        val abandoned = heard.single() as Event.CastAbandoned
        assertThat(abandoned.receiver).isEqualTo(tv)
        assertThat(abandoned.atPin).isTrue()
    }

    @Test
    fun thePlayerLogsThroughItsClientUnderItsOwnTag() {
        val lines = mutableListOf<Pair<String, String>>()
        val airkast = Airkast { logger = Airkast.Logger { _, tag, message, _ -> lines += tag to message } }
        val own =
            AirkastPlayer(ApplicationProvider.getApplicationContext(), airkast) {
                connector = this@ConnectTest.connector
            }
        own.setMediaItem(item)
        own.connect(tv)
        idle()
        own.release()
        assertThat(lines).contains("player" to "cast started")
        assertThat(lines.map { it.first }.distinct()).containsExactly("player")
    }

    @Test
    fun aListenerThatThrowsLeavesTheCastAlone() {
        val own = player { eventListener = { error("listener") } }
        own.setMediaItem(item)
        own.connect(tv)
        idle()
        assertThat(own.connection.value).isInstanceOf(Connection.Connected::class.java)
        own.release()
    }

    private fun player(block: AirkastPlayer.Builder.() -> Unit = {}): AirkastPlayer =
        AirkastPlayer(ApplicationProvider.getApplicationContext(), Airkast()) {
            connector = this@ConnectTest.connector
            eventListener = { heard += it }
            block()
        }

    private fun casting(): FakeSession {
        player.setMediaItem(item)
        player.play()
        player.connect(tv)
        idle()
        check(player.isPlaying)
        return connector.sessions.last()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private class FakeConnector : Connector {
        val sessions = mutableListOf<FakeSession>()
        val asked = mutableListOf<Pair<Receiver, Boolean>>()
        var failure: AirkastException? = null
        var asksForPin = false
        var pin: String? = null
        var hold: CompletableDeferred<Unit>? = null

        override suspend fun connect(
            receiver: Receiver,
            withPin: Boolean,
            pin: suspend () -> String,
        ): AirkastSession {
            asked += receiver to withPin
            hold?.await()
            if (asksForPin || withPin) this.pin = pin()
            failure?.let { throw it }
            return FakeSession(receiver).also { sessions += it }
        }
    }
}

package io.github.aivanyuk.airkast.media3

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.AirkastException
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration as JavaDuration

/** The receiver's renditions as media3's `Tracks`, selected through `TrackSelectionParameters`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TracksTest {
    private val fake = FakeSession().withRenditions()
    private val lines = mutableListOf<Pair<Airkast.Logger.Level, String>>()
    private val player =
        AirkastPlayer(ApplicationProvider.getApplicationContext(), Airkast()) {
            logger = Airkast.Logger { level, _, message, _ -> lines += level to message }
        }
    private val item = MediaItem.fromUri("https://example.com/master.m3u8")

    @After
    fun release() {
        player.release()
    }

    @Test
    fun theReceiversRenditionsAreTheTracksOnceItTakesTheItem() {
        assertThat(player.isCommandAvailable(Player.COMMAND_GET_TRACKS)).isFalse()
        assertThat(player.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)).isTrue()
        playing()
        assertThat(player.isCommandAvailable(Player.COMMAND_GET_TRACKS)).isTrue()
        val groups = player.currentTracks.groups
        assertThat(groups.map { it.type })
            .containsExactly(
                C.TRACK_TYPE_AUDIO,
                C.TRACK_TYPE_AUDIO,
                C.TRACK_TYPE_TEXT,
                C.TRACK_TYPE_TEXT,
                C.TRACK_TYPE_TEXT,
            ).inOrder()
        assertThat(groups.map { it.isSelected }).containsExactly(true, false, true, false, false).inOrder()
        assertThat(groups.map { it.mediaTrackGroup.id }).containsExactly("0", "1", "2", "3", "4").inOrder()
        val captions = groups[2].getTrackFormat(0)
        assertThat(captions.id).isEqualTo("2")
        assertThat(captions.label).isEqualTo("English")
        assertThat(captions.language).isEqualTo("en")
        assertThat(captions.roleFlags)
            .isEqualTo(C.ROLE_FLAG_TRANSCRIBES_DIALOG or C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND)
        assertThat(groups[4].getTrackFormat(0).selectionFlags).isEqualTo(C.SELECTION_FLAG_FORCED)
        assertThat(groups.all { it.isSupported }).isTrue()
        // The receiver's own picks stand.
        assertThat(fake.sent.filter { it.startsWith("select") }).isEmpty()
    }

    @Test
    fun aFileHasNoTracks() {
        player.session = fake
        player.setMediaItem(MediaItem.fromUri("https://example.com/film.mp4"))
        player.play()
        idle()
        assertThat(player.isCommandAvailable(Player.COMMAND_GET_TRACKS)).isFalse()
        assertThat(player.currentTracks.groups).isEmpty()
    }

    @Test
    fun aPreferredLanguageSelectsTheFirstRenditionInIt() {
        playing()
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setPreferredAudioLanguage("ru")
                .setPreferredTextLanguage("es")
                .build()
        idle()
        // es matches es-MX by its primary subtag.
        assertThat(fake.sent).containsAtLeast("select Audio 1", "select Subtitles 3").inOrder()
        assertThat(player.currentTracks.groups.map { it.isSelected })
            .containsExactly(false, true, false, true, false)
            .inOrder()
    }

    @Test
    fun aSelectionTheReceiverHasSendsNothing() {
        playing()
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setPreferredAudioLanguage("en")
                .build()
        idle()
        assertThat(fake.sent.filter { it.startsWith("select") }).isEmpty()
    }

    @Test
    fun anOverrideSelectsItsGroup() {
        playing()
        val spanish = player.currentTracks.groups[3]
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(TrackSelectionOverride(spanish.mediaTrackGroup, 0))
                .build()
        idle()
        assertThat(fake.sent.last()).isEqualTo("select Subtitles 3")
        assertThat(player.currentTracks.groups[3].isSelected).isTrue()
        assertThat(player.currentTracks.groups[2].isSelected).isFalse()
    }

    @Test
    fun disablingTextTurnsSubtitlesOff() {
        playing()
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        idle()
        assertThat(fake.sent.last()).isEqualTo("select Subtitles null")
        assertThat(player.currentTracks.isTypeSelected(C.TRACK_TYPE_TEXT)).isFalse()
        assertThat(player.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO)).isTrue()
    }

    @Test
    fun subtitlesTurnOffAsMedia3sControlsTurnThemOff() {
        playing()
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_FORCED.inv())
                .setPreferredTextLanguage(null)
                .build()
        idle()
        assertThat(fake.sent.last()).isEqualTo("select Subtitles null")
    }

    @Test
    fun parametersSetBeforeTheItemApplyOnceTheReceiverTakesIt() {
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setPreferredAudioLanguage("ru")
                .build()
        idle()
        assertThat(player.trackSelectionParameters.preferredAudioLanguages).containsExactly("ru")
        playing()
        assertThat(fake.sent.filter { it.startsWith("select") }).containsExactly("select Audio 1")
        assertThat(player.currentTracks.groups[1].isSelected).isTrue()

        // And on every item after.
        val next = FakeSession().withRenditions()
        player.session = next
        idle()
        assertThat(next.sent).contains("select Audio 1")
    }

    @Test
    fun theTracksShowWhatWasAskedUntilTheReceiverSaysOtherwise() {
        playing()
        fake.switches = false
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setPreferredAudioLanguage("ru")
                .build()
        idle()
        assertThat(player.currentTracks.groups[1].isSelected).isTrue()
        shadowOf(Looper.getMainLooper()).idleFor(JavaDuration.ofSeconds(5))
        assertThat(player.currentTracks.groups[0].isSelected).isTrue()
        assertThat(player.currentTracks.groups[1].isSelected).isFalse()
    }

    @Test
    fun anUnreadableMasterLeavesNoTracksAndTheReceiversPicks() {
        fake.renditionsError = AirkastException.ManifestUnreadable("The master playlist answered 403", status = 403)
        player.trackSelectionParameters =
            player.trackSelectionParameters
                .buildUpon()
                .setPreferredAudioLanguage("ru")
                .build()
        playing()
        assertThat(player.currentTracks.groups).isEmpty()
        assertThat(fake.sent.filter { it.startsWith("select") }).isEmpty()
        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
        assertThat(lines.map { it.first }).contains(Airkast.Logger.Level.Warn)
    }

    @Test
    fun parametersStayWhenTheCastEnds() {
        playing()
        val parameters =
            TrackSelectionParameters.DEFAULT
                .buildUpon()
                .setPreferredTextLanguage("es")
                .build()
        player.trackSelectionParameters = parameters
        player.disconnect()
        idle()
        assertThat(player.trackSelectionParameters).isEqualTo(parameters)
        assertThat(player.currentTracks.groups).isEmpty()
    }

    private fun playing() {
        player.session = fake
        player.setMediaItem(item)
        player.play()
        idle()
        check(player.isPlaying)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
}

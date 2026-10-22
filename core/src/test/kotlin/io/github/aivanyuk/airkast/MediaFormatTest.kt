package io.github.aivanyuk.airkast

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaFormatTest {
    @Test
    fun anHlsPlaylistIsHls() {
        assertThat(MediaFormat.of("https://example.com/master.m3u8")).isEqualTo(MediaFormat.Hls)
        assertThat(MediaFormat.of("https://example.com/live/INDEX.M3U8?token=a.mp4")).isEqualTo(MediaFormat.Hls)
    }

    @Test
    fun videoAndAudioFilesAreFiles() {
        for (name in listOf("film.mp4", "film.m4v", "clip.MOV", "song.mp3", "song.aac", "song.m4a", "song.flac")) {
            assertThat(MediaFormat.of("https://example.com/media/$name")).isEqualTo(MediaFormat.File)
        }
    }

    @Test
    fun aDashManifestIsDash() {
        assertThat(MediaFormat.of("https://example.com/angel-one/dash.mpd")).isEqualTo(MediaFormat.Dash)
        assertThat(MediaFormat.of("https://example.com/live/Manifest.MPD?t=1")).isEqualTo(MediaFormat.Dash)
        assertThat(MediaFormat.of("https://example.com/watch/7", "application/dash+xml")).isEqualTo(MediaFormat.Dash)
        assertThat(MediaFormat.of("https://example.com/a.m3u8", "Application/DASH+XML; profiles=x")).isEqualTo(
            MediaFormat.Dash,
        )
    }

    @Test
    fun theQueryAndFragmentAreNotTheExtension() {
        assertThat(MediaFormat.of("https://example.com/film.mp4?expires=1#t=10")).isEqualTo(MediaFormat.File)
        assertThat(MediaFormat.of("https://example.com/play?file=film.mp4")).isEqualTo(MediaFormat.Hls)
        assertThat(MediaFormat.of("https://example.com/v1.2/stream")).isEqualTo(MediaFormat.Hls)
    }

    @Test
    fun aUrlThatSaysNothingIsHls() {
        assertThat(MediaFormat.of("https://example.com/watch/7")).isEqualTo(MediaFormat.Hls)
        assertThat(MediaFormat.of("https://example.com/")).isEqualTo(MediaFormat.Hls)
        assertThat(MediaFormat.of("")).isEqualTo(MediaFormat.Hls)
    }

    @Test
    fun theMimeTypeWinsOverTheExtension() {
        assertThat(MediaFormat.of("https://example.com/watch/7", "video/mp4")).isEqualTo(MediaFormat.File)
        assertThat(MediaFormat.of("https://example.com/a", "audio/mpeg")).isEqualTo(MediaFormat.File)
        assertThat(MediaFormat.of("https://example.com/a", "Audio/MP4; codecs=mp4a.40.2")).isEqualTo(MediaFormat.File)
        assertThat(MediaFormat.of("https://example.com/a.mp4", "application/x-mpegURL")).isEqualTo(MediaFormat.Hls)
        assertThat(
            MediaFormat.of("https://example.com/a.mp4", "application/vnd.apple.mpegurl"),
        ).isEqualTo(MediaFormat.Hls)
        assertThat(MediaFormat.of("https://example.com/a", "audio/mpegurl")).isEqualTo(MediaFormat.Hls)
    }

    @Test
    fun aMimeTypeItDoesNotKnowFallsBackToTheExtension() {
        assertThat(MediaFormat.of("https://example.com/a.mp4", "application/octet-stream")).isEqualTo(MediaFormat.File)
        assertThat(MediaFormat.of("https://example.com/a", "application/octet-stream")).isEqualTo(MediaFormat.Hls)
    }

    @Test
    fun mediaReadsItsFormatFromItsUrl() {
        assertThat(Media("https://example.com/master.m3u8").format).isEqualTo(MediaFormat.Hls)
        assertThat(Media("https://example.com/song.mp3").format).isEqualTo(MediaFormat.File)
        assertThat(Media("https://example.com/watch/7", format = MediaFormat.File).format).isEqualTo(MediaFormat.File)
    }
}

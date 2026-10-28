package io.github.aivanyuk.airkast

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration.Companion.minutes

class LocalFileResolverTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val receiver = Receiver("fake", "127.0.0.1", 7000)

    /** Records what is published, at `http://origin/<name>`. */
    private class RecordingOrigin : Origin {
        val published = mutableMapOf<String, Resource>()

        override fun publish(
            name: String,
            resource: Resource,
        ): String = "http://origin/$name".also { published[it] = resource }

        override fun withdraw(url: String) {
            published.remove(url)
        }
    }

    private val origin = RecordingOrigin()

    private fun resolve(media: Media) = runBlocking { LocalFileResolver().resolve(media, receiver, origin) }

    @Test
    fun aFileIsPublishedWithItsTypeAndWithdrawnOnClose() {
        val file = folder.newFile("Song.MP3").apply { writeBytes(ByteArray(3)) }
        val resolved = resolve(Media(file.toURI().toString(), startAt = 1.minutes))!!
        assertThat(resolved.media.url).matches("http://origin/file-[0-9a-f-]{36}\\.mp3")
        assertThat(resolved.media.format).isEqualTo(MediaFormat.File)
        assertThat(resolved.media.startAt).isEqualTo(1.minutes)
        assertThat(resolved.renditions).isNull()
        val resource = origin.published.getValue(resolved.media.url)
        assertThat(resource.contentType).isEqualTo("audio/mpeg")
        assertThat(resource.open().length).isEqualTo(3)

        resolved.close()
        assertThat(origin.published).isEmpty()
    }

    @Test
    fun aFileWithoutAKnownExtensionIsServedAsBytes() {
        val file = folder.newFile("recording").apply { writeBytes(ByteArray(1)) }
        val resolved = resolve(Media(file.toURI().toString(), format = MediaFormat.File))!!
        assertThat(resolved.media.url).matches("http://origin/file-[0-9a-f-]{36}")
        assertThat(origin.published.getValue(resolved.media.url).contentType).isEqualTo("application/octet-stream")
    }

    @Test
    fun anythingButAFileUrlOfAFileIsLeftAlone() {
        val playlist = folder.newFile("master.m3u8")
        assertThat(resolve(Media("https://example.com/film.mp4"))).isNull()
        assertThat(resolve(Media("content://media/external/video/7", format = MediaFormat.File))).isNull()
        assertThat(resolve(Media(playlist.toURI().toString()))).isNull()
        assertThat(origin.published).isEmpty()
    }

    @Test
    fun aFileThatCannotBeReadIsUnsupported() {
        val missing =
            folder.root
                .resolve("gone.mp4")
                .toURI()
                .toString()
        assertThrows(AirkastException.UnsupportedMedia::class.java) { resolve(Media(missing)) }
        val directory = folder.newFolder("films.mp4").toURI().toString()
        assertThrows(
            AirkastException.UnsupportedMedia::class.java,
        ) { resolve(Media(directory, format = MediaFormat.File)) }
        assertThrows(AirkastException.UnsupportedMedia::class.java) { resolve(Media("file://host/film.mp4")) }
    }
}

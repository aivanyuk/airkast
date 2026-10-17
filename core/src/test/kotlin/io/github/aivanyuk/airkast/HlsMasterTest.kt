package io.github.aivanyuk.airkast

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HlsMasterTest {
    @Test
    fun bipbopsAdvancedHevcExampleNumbersAsTheMacDoes() {
        val renditions = HlsMaster.renditions(BIPBOP_ADV_HEVC)
        assertThat(renditions)
            .containsExactly(
                Rendition(TrackKind.Audio, 0, "English", "en-US", false, emptyList(), "a1"),
                Rendition(TrackKind.Subtitles, 1, "English", "en", false, emptyList(), "cc"),
                Rendition(TrackKind.Subtitles, 2, "English", "en", false, emptyList(), "sub1"),
            ).inOrder()
    }

    @Test
    fun subtitlesBeforeAudioCountFirstAndARepeatedRenditionCountsOnce() {
        val master =
            """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Russian, forced",LANGUAGE="ru",FORCED=YES,URI="s/ru-forced.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="en",URI="s/en.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="Russian",LANGUAGE="ru",DEFAULT=YES,URI="a/ru-aac.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="English",LANGUAGE="en",CHARACTERISTICS="public.accessibility.describes-video,public.easy-to-read",URI="a/en-aac.m3u8"
            #EXT-X-MEDIA:TYPE=VIDEO,GROUP-ID="angles",NAME="Wide",URI="v/wide.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="ac3",NAME="Russian",LANGUAGE="ru",URI="a/ru-ac3.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="ac3",NAME="Ukrainian",LANGUAGE="uk",URI="a/uk-ac3.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,CODECS="avc1.640028,mp4a.40.2",AUDIO="aac",SUBTITLES="subs"
            v/1080.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2400000,CODECS="avc1.640028,ac-3",AUDIO="ac3",SUBTITLES="subs"
            v/1080.m3u8
            """.trimIndent()
        val renditions = HlsMaster.renditions(master)
        assertThat(renditions.map { Triple(it.kind, it.id, it.name) })
            .containsExactly(
                Triple(TrackKind.Subtitles, 0L, "Russian, forced"),
                Triple(TrackKind.Subtitles, 1L, "English"),
                Triple(TrackKind.Audio, 2L, "Russian"),
                Triple(TrackKind.Audio, 3L, "English"),
                Triple(TrackKind.Audio, 4L, "Ukrainian"),
            ).inOrder()
        assertThat(renditions[0].forced).isTrue()
        assertThat(renditions[2].group).isEqualTo("aac")
        assertThat(renditions[3].characteristics)
            .containsExactly("public.accessibility.describes-video", "public.easy-to-read")
            .inOrder()
        assertThat(renditions[4].group).isEqualTo("ac3")
    }

    @Test
    fun attributesInAnyOrderAndWindowsLineEndsRead() {
        val master =
            "#EXTM3U\r\n" +
                "#EXT-X-MEDIA:URI=\"a.m3u8\",LANGUAGE=\"de\",NAME=\"Deutsch\",GROUP-ID=\"a\",TYPE=AUDIO\r\n" +
                "#EXT-X-MEDIA-SEQUENCE:7\r\n"
        assertThat(HlsMaster.renditions(master))
            .containsExactly(Rendition(TrackKind.Audio, 0, "Deutsch", "de", false, emptyList(), "a"))
    }

    @Test
    fun aMediaPlaylistHasNoRenditions() {
        val media = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:6.0,\nsegment0.ts\n"
        assertThat(HlsMaster.renditions(media)).isEmpty()
    }

    companion object {
        /**
         * Apple's `bipbop_adv_example_hevc/master.m3u8`, as served on 2026-10-16: its
         * `EXT-X-MEDIA` tags whole, and the first of its variants.
         */
        val BIPBOP_ADV_HEVC =
            """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-INDEPENDENT-SEGMENTS


            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a1",NAME="English",LANGUAGE="en-US",AUTOSELECT=YES,DEFAULT=YES,CHANNELS="2",URI="a1/prog_index.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a2",NAME="English",LANGUAGE="en-US",AUTOSELECT=YES,DEFAULT=YES,CHANNELS="6",URI="a2/prog_index.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a3",NAME="English",LANGUAGE="en-US",AUTOSELECT=YES,DEFAULT=YES,CHANNELS="6",URI="a3/prog_index.m3u8"


            #EXT-X-MEDIA:TYPE=CLOSED-CAPTIONS,GROUP-ID="cc",LANGUAGE="en",NAME="English",DEFAULT=YES,AUTOSELECT=YES,INSTREAM-ID="CC1"


            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="sub1",LANGUAGE="en",NAME="English",AUTOSELECT=YES,DEFAULT=YES,FORCED=NO,URI="s1/en/prog_index.m3u8"


            #EXT-X-I-FRAME-STREAM-INF:AVERAGE-BANDWIDTH=928091,BANDWIDTH=1015727,CODECS="avc1.640028",RESOLUTION=1920x1080,URI="tp5/iframe_index.m3u8"


            #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=2190673,BANDWIDTH=2523597,CODECS="avc1.640020,mp4a.40.2",RESOLUTION=960x540,FRAME-RATE=60.000,CLOSED-CAPTIONS="cc",AUDIO="a1",SUBTITLES="sub1"
            v5/prog_index.m3u8
            """.trimIndent()
    }
}

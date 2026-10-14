package io.github.aivanyuk.airkast.sample

/**
 * Something to cast in each format, as the live test plays them. Apple's stream sends CORS
 * headers, which the LG's player needs for HLS; the files are ExoPlayer's test media.
 */
enum class SampleMedia(
    val url: String,
) {
    Stream("https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_adv_example_hevc/master.m3u8"),

    /** An hour of frame counter, for seeking. */
    Video("https://storage.googleapis.com/exoplayer-test-media-1/mp4/frame-counter-one-hour.mp4"),
    Audio("https://storage.googleapis.com/exoplayer-test-media-0/play.mp3"),

    /** A few minutes of music, for a speaker. */
    Music("https://storage.googleapis.com/exoplayer-test-media-0/Jazz_In_Paris.mp3"),
}

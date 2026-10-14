plugins {
    id("airkast.android.library")
}

description = "An ExoPlayer AudioSink that plays the app's audio on an AirPlay speaker."

android {
    namespace = "io.github.aivanyuk.airkast.exoplayer"
}

dependencies {
    api(project(":core"))
    // AirkastAudioSink is an ExoPlayer AudioSink, and resamples with media3-common's processors.
    api(libs.androidx.media3.exoplayer)
}

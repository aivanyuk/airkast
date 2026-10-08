plugins {
    id("airkast.android.library")
}

description = "A media3 Player that plays on an AirPlay receiver, for media3's UI and MediaSession."

android {
    namespace = "io.github.aivanyuk.airkast.media3"
}

dependencies {
    api(project(":core"))
    api(libs.androidx.media3.common)
    // The player's own looper as a dispatcher, with delays that run on it.
    implementation(libs.kotlinx.coroutines.android)
}

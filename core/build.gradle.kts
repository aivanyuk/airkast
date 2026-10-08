plugins {
    id("airkast.jvm.library")
}

description = "Plays a video URL on an AirPlay video receiver: pairing, the session and its commands."

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // The live tests play on a real receiver only when AIRKAST_RECEIVER names one.
    environment("AIRKAST_RECEIVER", System.getenv("AIRKAST_RECEIVER") ?: "")
    testLogging {
        events("failed")
        showStandardStreams = true
    }
}

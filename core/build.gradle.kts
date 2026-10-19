plugins {
    id("airkast.jvm.library")
}

description = "Plays a video URL on an AirPlay video receiver: pairing, the session and its commands."

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // The live tests play on a real receiver only when AIRKAST_RECEIVER or AIRKAST_AUDIO_RECEIVER names one.
    environment("AIRKAST_RECEIVER", System.getenv("AIRKAST_RECEIVER") ?: "")
    environment("AIRKAST_URL", System.getenv("AIRKAST_URL") ?: "")
    environment("AIRKAST_AUDIO_RECEIVER", System.getenv("AIRKAST_AUDIO_RECEIVER") ?: "")
    environment("AIRKAST_AUDIO_RATE", System.getenv("AIRKAST_AUDIO_RATE") ?: "")
    environment("AIRKAST_AUDIO_VOLUME", System.getenv("AIRKAST_AUDIO_VOLUME") ?: "")
    environment("AIRKAST_PROBE_RECEIVERS", System.getenv("AIRKAST_PROBE_RECEIVERS") ?: "")
    // Gradle tracks no environment variable as an input, so a run that names a receiver would be
    // up to date, or come from the build cache, after one that named none. A live run never is.
    val live = listOf("AIRKAST_RECEIVER", "AIRKAST_AUDIO_RECEIVER", "AIRKAST_PROBE_RECEIVERS")
    if (live.any { !System.getenv(it).isNullOrBlank() }) {
        outputs.upToDateWhen { false }
        outputs.cacheIf("a live test runs against a real receiver") { false }
    }
    testLogging {
        events("failed")
        showStandardStreams = true
    }
}

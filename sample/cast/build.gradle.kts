// The sample apps' integration of airkast: Cast.kt builds the app's Airkast and the AirkastPlayer
// that plays on the phone and runs the cast, and CastService.kt puts a MediaSession over the
// player. :sample:compose and :sample:views are two UIs over this one module, so a screen in
// either toolkit drives the same code.
plugins {
    id("com.android.library")
    id("org.jlleitschuh.gradle.ktlint")
    id("airkast.sample")
}

android {
    namespace = "io.github.aivanyuk.airkast.sample"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
    }

    lint {
        warningsAsErrors = true
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

ktlint {
    android.set(true)
    version.set(libs.versions.ktlintEngine.get())
}

dependencies {
    // io.github.aivanyuk:airkast-android and airkast-media3, which airkast.sample builds from this
    // repository.
    api(libs.airkast.android)
    api(libs.airkast.media3)
    // The phone's own player, which the AirkastPlayer hands the item to and from.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
}

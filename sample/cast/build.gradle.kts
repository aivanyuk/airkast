// The sample apps' integration of airkast: Cast.kt builds the app's Airkast and the AirkastPlayer
// that runs the cast, and CastService.kt puts a MediaSession over the player. :sample:compose and
// :sample:views are two UIs over this one module, so a screen in either toolkit drives the same
// code. It builds against the modules in this repository, so it always shows the current API; an
// app takes the published artifacts instead, as the README says.
plugins {
    id("com.android.library")
    id("org.jlleitschuh.gradle.ktlint")
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
    // In an app: implementation("com.github.aivanyuk.airkast:airkast-android:<version>"), and
    // airkast-media3 likewise.
    api(project(":android"))
    api(project(":media3"))
    api(libs.androidx.media3.session)
}

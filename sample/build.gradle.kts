// The sample app: an app's integration of airkast, end to end. It builds against the modules in
// this repository, so it always shows the current API; an app takes the published artifacts
// instead, as the README says. Cast.kt and CastService.kt are the integration; ui/ is Compose.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jlleitschuh.gradle.ktlint")
}

android {
    namespace = "io.github.aivanyuk.airkast.sample"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.aivanyuk.airkast.sample"
        minSdk = 23
        // 37 puts the sample under Android 17's local network permission, which it asks for.
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
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
    implementation(project(":android"))
    implementation(project(":media3"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
}

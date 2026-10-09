// The sample app with its UI in Compose: one screen over :sample:cast, which holds the
// integration. `./gradlew :sample:compose:installDebug` puts it on a device.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jlleitschuh.gradle.ktlint")
}

android {
    namespace = "io.github.aivanyuk.airkast.sample.compose"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.aivanyuk.airkast.sample.compose"
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
    implementation(project(":sample:cast"))
    implementation(libs.androidx.media3.ui)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}

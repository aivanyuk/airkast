// The sample app with its UI in views: one activity with layouts over :sample:cast, which holds
// the integration. `./gradlew :sample:views:installDebug` puts it on a device.
plugins {
    id("com.android.application")
    id("org.jlleitschuh.gradle.ktlint")
}

android {
    namespace = "io.github.aivanyuk.airkast.sample.views"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.aivanyuk.airkast.sample.views"
        minSdk = 23
        // 37 puts the sample under Android 17's local network permission, which it asks for.
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        viewBinding = true
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

    implementation(libs.material)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
}

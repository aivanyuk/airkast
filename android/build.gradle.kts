plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "io.github.aivanyuk.airkast.android"
    compileSdk = 37
    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":core"))
}

publishing {
    publications {
        create<MavenPublication>("release") {
            artifactId = "airkast-android"
            afterEvaluate { from(components["release"]) }
        }
    }
}

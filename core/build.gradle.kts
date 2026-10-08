import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

kotlin {
    explicitApi()
    compilerOptions.jvmTarget = JvmTarget.JVM_17
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // The live test plays on a real receiver only when AIRKAST_RECEIVER names one.
    environment("AIRKAST_RECEIVER", System.getenv("AIRKAST_RECEIVER") ?: "")
    testLogging { events("failed"); showStandardStreams = true }
}

publishing {
    publications {
        create<MavenPublication>("release") {
            artifactId = "airkast-core"
            from(components["java"])
        }
    }
}

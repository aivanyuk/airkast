import org.gradle.accessors.dm.LibrariesForLibs
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

/**
 * A plain JVM module that desktop JVMs and Android apps both consume. Its floors are the ones in
 * docs/compatibility.md, "Senders": Java 11 bytecode and the Java 11 class library, Android API 23
 * for every call it makes, and Kotlin 2.2 for the code that calls it. The Android convention
 * (airkast.android.library) holds the same Kotlin settings; change them together.
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
    id("org.jlleitschuh.gradle.ktlint")
    id("dev.drewhamilton.poko")
    id("ru.vyarus.animalsniffer")
    id("airkast.publish")
}

val libs = the<LibrariesForLibs>()

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    withSourcesJar()
}

kotlin {
    explicitApi()
    coreLibrariesVersion = libs.versions.kotlinStdlibFloor.get()
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
        // Compiles against the JDK 11 class library, whatever JDK runs the build, so no call to a
        // newer JDK's API (List.removeFirst is the classic) reaches a consumer.
        freeCompilerArgs.add("-Xjdk-release=11")
        apiVersion = KotlinVersion.KOTLIN_2_2
        languageVersion = KotlinVersion.KOTLIN_2_2
        allWarningsAsErrors = true
    }

    // The public API, dumped to api/. A change to the dump is a change to the API: see
    // docs/releasing.md, "What counts as a break".
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        referenceDumpDir.set(layout.projectDirectory.dir("api"))
    }
}

poko {
    pokoAnnotation.set("io/github/aivanyuk/airkast/internal/Poko")
}

ktlint {
    version.set(libs.versions.ktlintEngine.get())
}

// Every class in the jar may only call what Android 6.0 (API 23) has, after D8's desugaring. The
// tests run on the desktop JVM and are free to use anything.
animalsniffer {
    sourceSets = listOf(project.sourceSets["main"])
}

dependencies {
    signature(variantOf(libs.gummy.bears.api23) { artifactType("signature") })
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

publishing {
    publications {
        create<MavenPublication>("release") {
            from(components["java"])
        }
    }
}

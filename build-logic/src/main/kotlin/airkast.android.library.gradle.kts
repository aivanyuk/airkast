import org.gradle.accessors.dm.LibrariesForLibs
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * An Android library module. minSdk 23 is the floor in docs/compatibility.md, "Senders"; lint's
 * NewApi holds every call to it, and the unit tests run under Robolectric at the SDK levels where
 * the platform changed. The Kotlin settings match airkast.jvm.library; change them together.
 */
plugins {
    id("com.android.library")
    id("org.jlleitschuh.gradle.ktlint")
    id("airkast.publish")
}

val libs = the<LibrariesForLibs>()

android {
    compileSdk = 37

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        warningsAsErrors = true
        ignoreTestSources = true
        // A newer version of a dependency is not a defect in this commit, and would fail the build
        // on the day it is released. Versions move in their own change.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric reaches into FileDescriptor's internals, which JDK 17 and later close.
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }

    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

kotlin {
    explicitApi()
    coreLibrariesVersion = libs.versions.kotlinStdlibFloor.get()
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
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

// KGP's ABI dump gets no classes from a module on AGP's built-in Kotlin (AGP 9.4, KGP 2.4.20): the
// dump comes out empty, and every check passes. This hands it the release compiler's output, as KGP
// does for a JVM module. The task's input type is internal to KGP, hence the reflection, which fails
// the build if a KGP bump renames it. Remove this once KGP wires the module itself: the dump then
// lists every class twice.
tasks.matching { it.name == "internalDumpKotlinAbi" }.configureEach {
    val release = tasks.named<KotlinCompile>("compileReleaseKotlin").flatMap { it.destinationDirectory }
    val targetInfo =
        Class
            .forName("org.jetbrains.kotlin.gradle.tasks.abi.KotlinAbiDumpTaskImpl\$JvmTargetInfo")
            .getConstructor(String::class.java, FileCollection::class.java)
            .newInstance("", files(release))
    @Suppress("UNCHECKED_CAST")
    (javaClass.getMethod("getJvm").invoke(this) as ListProperty<Any>).add(targetInfo)
}

ktlint {
    android.set(true)
    version.set(libs.versions.ktlintEngine.get())
}

dependencies {
    "testImplementation"(libs.junit)
    "testImplementation"(libs.truth)
    "testImplementation"(libs.robolectric)
    "testImplementation"(libs.androidx.test.core)
}

publishing {
    publications {
        create<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
        }
    }
}

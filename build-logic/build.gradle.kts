plugins {
    `kotlin-dsl`
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {
    // compileOnly: the convention scripts need these plugins' types; the main build applies the
    // plugins by id and resolves them once, in its root build.gradle.kts.
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.ktlint.gradlePlugin)
    compileOnly(libs.animalSniffer.gradlePlugin)
    compileOnly(libs.poko.gradlePlugin)
    // Makes the catalog's type-safe accessors visible inside precompiled script plugins
    // (gradle/gradle#15383).
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}

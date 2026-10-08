plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

allprojects {
    group = "com.github.aivanyuk.airkast"
    version = providers.environmentVariable("VERSION").getOrElse("0.1.0-SNAPSHOT")
}

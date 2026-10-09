// The plugins that build-logic's conventions and the sample apply by id, resolved once here.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.animalSniffer) apply false
    alias(libs.plugins.poko) apply false
}

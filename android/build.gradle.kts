plugins {
    id("airkast.android.library")
}

description = "Finds AirPlay receivers through NsdManager and reaches them over the right network."

android {
    namespace = "io.github.aivanyuk.airkast.android"
}

dependencies {
    api(project(":core"))
}

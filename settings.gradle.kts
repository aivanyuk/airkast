pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "airkast"

include(":core")
include(":android")
include(":media3")

// The reference apps over the modules above, in Compose and in views, and the integration they
// share. Not published.
include(":sample:cast")
include(":sample:compose")
include(":sample:views")

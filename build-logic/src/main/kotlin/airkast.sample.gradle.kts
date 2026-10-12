/**
 * A sample module. The samples declare airkast by the coordinates an app uses, with no version, and
 * this builds those artifacts from the modules in this repository, so the samples always run the
 * current code.
 */
configurations.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.aivanyuk:airkast-core")).using(project(":core"))
        substitute(module("io.github.aivanyuk:airkast-android")).using(project(":android"))
        substitute(module("io.github.aivanyuk:airkast-media3")).using(project(":media3"))
    }
}

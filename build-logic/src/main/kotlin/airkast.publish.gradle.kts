/**
 * Coordinates and POM for every published module. Each library convention creates the publication
 * itself, from its own component, as `airkast-<module>`.
 */
plugins {
    `maven-publish`
}

group = "com.github.aivanyuk.airkast"

// JitPack sets VERSION to the tag (or commit) it builds, so the tag is the only place a version is
// written down. A local build is a snapshot. See docs/releasing.md.
version = providers.environmentVariable("VERSION").getOrElse("0.0.0-SNAPSHOT")

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifactId = "airkast-${project.name}"
        pom {
            name.set("airkast-${project.name}")
            description.set(provider { project.description })
            url.set("https://github.com/aivanyuk/airkast")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("aivanyuk")
                    name.set("Artem Ivaniuk")
                }
            }
            scm {
                url.set("https://github.com/aivanyuk/airkast")
                connection.set("scm:git:https://github.com/aivanyuk/airkast.git")
            }
        }
    }
}

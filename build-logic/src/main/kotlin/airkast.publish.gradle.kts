/**
 * Coordinates, POM and signing for every published module, as `io.github.aivanyuk:airkast-<module>`
 * on Maven Central. Each library convention picks the component it publishes.
 */
plugins {
    id("com.vanniktech.maven.publish.base")
}

// The release workflow sets VERSION to the tag it publishes, so the tag is the only place a version
// is written down. A local build is a snapshot, which needs no signature. See docs/releasing.md.
version = providers.environmentVariable("VERSION").getOrElse("0.0.0-SNAPSHOT")

mavenPublishing {
    coordinates(groupId = "io.github.aivanyuk", artifactId = "airkast-${project.name}")
    // The workflow's credentials come in as mavenCentralUsername, mavenCentralPassword,
    // signingInMemoryKey and signingInMemoryKeyPassword.
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

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
                url.set("https://github.com/aivanyuk")
            }
        }
        scm {
            url.set("https://github.com/aivanyuk/airkast")
            connection.set("scm:git:https://github.com/aivanyuk/airkast.git")
            developerConnection.set("scm:git:ssh://git@github.com/aivanyuk/airkast.git")
        }
    }
}

# Releasing

How airkast is versioned, what counts as a breaking change, and how a release is published to
Maven Central.

## Versions

- **[Semantic Versioning](https://semver.org/)**: `MAJOR.MINOR.PATCH`, with pre-releases as
  `-alpha.N`, `-beta.N` and `-rc.N` (`0.2.0-beta.1`).
- **The git tag is the version**, with no `v`: tag `0.2.0` publishes version `0.2.0`. The release
  workflow builds the tag with `VERSION` set to it, and `airkast.publish` reads that. No file in the
  repository holds a version, so none can disagree with the tag. A local build is
  `0.0.0-SNAPSHOT`.
- **Every artifact shares the version**, and all of them release together, changed or not. A
  caller who upgrades one upgrades all of them.
- **Tags are immutable.** A ruleset stops a tag from moving or being deleted. Maven Central never
  replaces or deletes a version it has published, so a moved tag could not change what users get,
  only make the tag and the artifacts disagree. A broken release is fixed by the next patch.

## Before 1.0

- The API is still being shaped against its first consumer, so a **minor** release (`0.2.0`
  after `0.1.x`) changes it freely, with no deprecation, shim or migration note: the changelog
  lists what changed under "Changed" or "Removed".
- A **patch** release (`0.1.1`) fixes bugs and adds receiver quirks, and may add API.
- 1.0 comes once the API has held through two minor releases and runs on a second receiver
  model. The compatibility rules below start there.

## From 1.0

- **Major** for a break, **minor** for new API or a raised floor that the platform forces, and
  **patch** for fixes.
- A public declaration is deprecated (`WARNING`) for at least one minor release, then `ERROR`,
  and is removed only in the next major.

## What counts as a break

From 1.0. Before it, a break is recorded in the changelog and nothing more.

- **The API dump.** In `*/api/*.api`, a removed or changed line breaks callers. An added line
  does not. A new member of a sealed type or an enum (a `ReceiverEvent`, an `AirkastException`,
  a `Compatibility` case) is not a break either: callers keep an `else` branch.
- **A floor.** Raising minSdk, the JVM bytecode or class library level, the Kotlin language or
  API version, or the minimum kotlin-stdlib or kotlinx-coroutines version.
- **Behaviour a caller relies on.** A call that changes what it returns or throws for the same
  input, a default that changes, or an event that stops arriving.
- **Not a break**: a quirk for a new receiver, a changed log line, a new option with a default
  that keeps today's behaviour.

## Cutting a release

1. In a pull request, move "Unreleased" in [CHANGELOG.md](../CHANGELOG.md) under a heading for
   the version, with the date, and check the version against the rules above. Change the version
   in the README's Install section to the new one. Merge it.
2. Tag `main` at that merge and push the tag:

   ```bash
   git tag -a 0.2.0 -m "airkast 0.2.0"
   git push origin 0.2.0
   ```

3. The tag starts the [release workflow](../.github/workflows/release.yml), which signs every
   artifact, uploads them to the Central Portal and waits for Central to validate them. Central
   then publishes them on its own, and they reach Maven Central within about half an hour:

   ```bash
   gh run watch "$(gh run list --workflow release.yml --limit 1 --json databaseId -q '.[0].databaseId')"
   curl -sfI https://repo1.maven.org/maven2/io/github/aivanyuk/airkast-core/0.2.0/airkast-core-0.2.0.pom
   ```

   A failed run published nothing: fix the cause on `main` and release the next patch.

4. Create a GitHub release for the tag, with the changelog's section as its notes.

## Consuming

The artifacts are `io.github.aivanyuk:airkast-<module>` on Maven Central, which needs no
repository of its own. Versions up to 0.3.2 were built by JitPack, as
`com.github.aivanyuk.airkast:airkast-<module>`, and are not on Central.

To try a change before its release, publish it to the local Maven repository
(`./gradlew publishToMavenLocal`, as `0.0.0-SNAPSHOT`) or include the build as below.

To work on airkast and an app side by side, the app includes this build and substitutes the
artifacts. The project names differ from the artifact IDs, so the substitution names them:

```kotlin
// the app's settings.gradle.kts
includeBuild("../airkast") {
    dependencySubstitution {
        substitute(module("io.github.aivanyuk:airkast-core")).using(project(":core"))
        substitute(module("io.github.aivanyuk:airkast-android")).using(project(":android"))
    }
}
```

## The publishing account

The release workflow reads four repository secrets:

- `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD`: a user token from the
  [Central Portal](https://central.sonatype.com/account), under an account that owns the
  `io.github.aivanyuk` namespace (signing in with GitHub verifies it).
- `SIGNING_KEY`: the ASCII-armored secret key that signs the artifacts
  (`gpg --armor --export-secret-keys <id>`), whose public key is on a keyserver Central reads,
  such as keys.openpgp.org.
- `SIGNING_KEY_PASSWORD`: that key's passphrase.

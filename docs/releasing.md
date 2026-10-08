# Releasing

## Versions

- **[Semantic Versioning](https://semver.org/)**: `MAJOR.MINOR.PATCH`, with pre-releases as
  `-alpha.N`, `-beta.N` and `-rc.N` (`0.2.0-beta.1`).
- **The git tag is the version**, with no `v`: tag `0.2.0` publishes version `0.2.0`. JitPack
  builds the tag with `VERSION` set to it, and `airkast.publish` reads that. No file in the
  repository holds a version, so none can disagree with the tag. A local build is
  `0.0.0-SNAPSHOT`.
- **Every artifact shares the version**, and all of them release together, changed or not. A
  caller who upgrades one upgrades all of them.
- **Tags are immutable.** A ruleset stops a tag from moving or being deleted. JitPack caches
  what a tag built, and a moved tag would give two users two different libraries under one
  version. A broken release is fixed by the next patch.

## Before 1.0

- A **minor** release (`0.2.0` after `0.1.x`) may break the API or a floor. Its changelog lists
  each break under "Changed" or "Removed", and what callers do about it.
- A **patch** release (`0.1.1`) never breaks. It fixes bugs, adds receiver quirks, and may add
  API.
- 1.0 comes once the API has held through two minor releases and runs on a second receiver
  model.

## From 1.0

- **Major** for a break, **minor** for new API or a raised floor that the platform forces, and
  **patch** for fixes.
- A public declaration is deprecated (`WARNING`) for at least one minor release, then `ERROR`,
  and is removed only in the next major.

## What counts as a break

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
   the version, with the date, and check the version against the rules above. Merge it.
2. Tag `main` at that merge and push the tag:

   ```bash
   git tag -a 0.2.0 -m "airkast 0.2.0"
   git push origin 0.2.0
   ```

3. Start JitPack's build by asking for the POM, then read the build log:

   ```bash
   curl -sf https://jitpack.io/com/github/aivanyuk/airkast/airkast-core/0.2.0/airkast-core-0.2.0.pom
   curl -s https://jitpack.io/com/github/aivanyuk/airkast/0.2.0/build.log | tail
   ```

4. Create a GitHub release for the tag, with the changelog's section as its notes.

## Consuming

```kotlin
maven("https://jitpack.io") { content { includeGroup("com.github.aivanyuk.airkast") } }
```

The `content` filter keeps every other dependency off JitPack. A commit (`<sha>`) or
`main-SNAPSHOT` works the same way, for trying a change before its release; a release build of
an app never uses one.

To work on airkast and an app side by side, the app includes this build and substitutes the
artifacts. The project names differ from the artifact IDs, so the substitution names them:

```kotlin
// the app's settings.gradle.kts
includeBuild("../airkast") {
    dependencySubstitution {
        substitute(module("com.github.aivanyuk.airkast:airkast-core")).using(project(":core"))
        substitute(module("com.github.aivanyuk.airkast:airkast-android")).using(project(":android"))
    }
}
```

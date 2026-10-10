# Contributing

The rules every change follows. [docs/architecture.md](docs/architecture.md) explains the shape
they protect, [docs/compatibility.md](docs/compatibility.md) what runs where, and
[docs/releasing.md](docs/releasing.md) how versions work.

## Before you push

```bash
./gradlew check
```

It runs everything CI runs: the JVM tests, the Android tests under Robolectric, Android lint,
ktlint, the public API check (`checkKotlinAbi`) and Animal Sniffer. Warnings are errors, in the
compiler and in lint. Fix the warning, or suppress it at the call site with a comment that says
why. `./gradlew ktlintFormat` fixes most formatting.

## Branches and pull requests

- `main` only moves through pull requests, and a pull request merges only when CI's `verify` job
  passes. Nobody pushes to `main` or rewrites it.
- One topic per pull request. Its title says what changes for someone who uses the library.
- Commit subjects are one sentence in the imperative, with no type prefix: "Bind the session to
  the network the receiver is on", not "feat: network binding".
- A user-visible change adds a line under "Unreleased" in [CHANGELOG.md](CHANGELOG.md): what
  changed, in a sentence.
- Release tags are immutable ([releasing](docs/releasing.md)).

## Automated review

Every pull request gets a review from an automated reviewer, posted as `tmikx`. It holds the diff
against [.github/review-rules.md](.github/review-rules.md), following
[.github/review.md](.github/review.md), and posts `REQUEST_CHANGES` when it finds a bug and
`COMMENT` otherwise. It never approves or pushes, and its findings are one reader's opinion with
a rule ID attached. The rubric is an index into this file and `docs/`: a change to a rule here
changes its row there in the same PR. A PR labelled `skip-review` is left alone.

## The public API

- **Explicit API mode** is on: every public declaration says `public`, and anything a caller
  doesn't need is `internal`. Public types live in `io.github.aivanyuk.airkast` (and
  `.android`); `crypto`, `wire`, `session` and `internal` hold internals only.
- **The dumps in `*/api/*.api` are the API.** A change that touches it runs
  `./gradlew updateKotlinAbi` and commits the new dump with the change, so the diff shows what
  callers will see. [Releasing](docs/releasing.md#what-counts-as-a-break) says which diffs break
  callers.
- **No data classes in the public API.** Value types are `@Poko` classes: they compare by value,
  and they have no `copy` or `componentN` to break when a property joins them.
- **Before 1.0, the API changes freely.** It is still being shaped against its first consumer,
  so a change needs no deprecation, no shim and no migration note: the changelog says what
  changed, and the API dump shows it. From 1.0, a new property goes last with a default, the old
  constructor stays as a `@Deprecated(level = HIDDEN)` secondary constructor, and a removal goes
  through a deprecation cycle ([releasing](docs/releasing.md#from-10)).
- **Options that will grow are builders**, as `Airkast` is: a new option joins the `Builder`
  with a default, and a `Type { … }` function builds one. A platform's defaults are a function
  that fills the same builder (`Airkast(context) { … }`), never a second type.
- **Each level hides the one below and leaves it in reach**
  ([architecture](docs/architecture.md#three-levels)): a default is a builder property an app can
  change, and the player takes a session set by hand as readily as one it opened.
- **A time is a `kotlin.time.Duration`**, never a number with the unit in its name, and a volume
  runs from 0 to 1. The API is Kotlin's: every call suspends, so Java is not a target.
- **`sample/` compiles against the modules**, so a change to the API changes the sample with it,
  and the sample shows the current way to make the call.
- **Sealed types and enums may gain members** in a minor release (`ReceiverEvent`,
  `AirkastException`, `Compatibility`, `PlaybackState`). Their KDoc says so where it matters;
  callers keep an `else` branch.
- **Every failure is an `AirkastException`**, or a `CancellationException`. A new way to fail is
  a new subclass with KDoc that says when it happens.
- **Every suspend function is main-safe.** It moves blocking I/O off the caller's thread itself.
- **No logging library.** `Airkast.Builder.logger` takes one line per protocol step. A line never
  holds a media URL, a key or anything a pairing derives.
- **No new runtime dependency** in `airkast-core` beyond the Kotlin standard library and
  kotlinx-coroutines. `airkast-android` adds only the Android platform. `airkast-media3` adds
  media3-common, media3-session and kotlinx-coroutines-android, and uses media3's unstable API
  only in internal classes.

## Compatibility floors

The build enforces these, and [docs/compatibility.md](docs/compatibility.md) explains them:

- `airkast-core` is Java 11 bytecode against the JDK 11 class library, and calls only what
  Android 6.0 (API 23) has after D8's desugaring. Animal Sniffer checks every call.
- `airkast-android` has minSdk 23. Lint's `NewApi` checks every call, and a call above the floor
  sits behind a `Build.VERSION.SDK_INT` check.
- Callers need Kotlin 2.2 or later: the code compiles at language and API version 2.2, against
  kotlin-stdlib 2.2.21 and kotlinx-coroutines 1.10.2.
- `airkast-media3` compiles against media3 1.11.1.

Raising a floor is a breaking change ([releasing](docs/releasing.md#what-counts-as-a-break)).

## Tests

- New logic comes with tests, failure paths included.
- Crypto is tested against published vectors, codecs by round trip and against bytes captured
  from a receiver, and protocol steps against `FakeReceiver` over loopback.
- Android code runs under Robolectric at the API levels where the platform changed. A test pins
  them with `@Config(sdk = [...])`.
- A change to what goes over the wire also runs the live test against a real receiver
  (`AIRKAST_RECEIVER`), and records the receiver, its firmware and `srcvers`, the date and the
  result in [docs/compatibility.md](docs/compatibility.md).

## Receiver quirks

A behaviour that one receiver needs lives in the session code with a comment that names the
receiver and firmware it was seen on, and gets a line in
[docs/compatibility.md](docs/compatibility.md). When two receivers need different behaviour, the
choice is made in one place, from what the TXT record says (`model`, `srcvers`, the feature
bits), never by retrying after an error.

## Sources and names

- Protocol facts may come from anywhere public. Code may come only from sources under a licence
  compatible with Apache-2.0, credited in the README. A GPL source gives facts, never code.
- "AirPlay" is Apple's trademark. It may describe what airkast talks to ("works with AirPlay
  receivers"), but no artifact, package, class or file is named after it.

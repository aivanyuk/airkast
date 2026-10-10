# Review rules: what the automated reviewer checks a PR against

This is the rubric for the automated PR reviewer ([review.md](review.md), fired by a GitHub
`pull_request` webhook, with a daily sweep behind it). It is an **index of checkable assertions**,
not a second copy of the rules. [CONTRIBUTING.md](../CONTRIBUTING.md) and `docs/` are the
authority, and every rule below points at the passage that states it. **When this file disagrees
with them, they win and this file is the bug**, fixed in the same PR that changes the rule.

## How to use it

1. Read the diff, and collect the rule IDs whose *Trigger* the diff matches. Skip the rest.
2. For each collected rule, read the authority passage before judging. The reasons carry the
   exceptions.
3. Report a deviation as `path:line`, the rule ID, what the code does, and the fix. A finding with
   no rule ID and no passage behind it is an opinion, and is labelled `nit`.

Severity: **`rule`**, a departure from a stated rule; **`bug`**, wrong regardless of any rule;
**`question`**, looks like a departure but the diff alone cannot tell; **`nit`**, taste with no
rule behind it.

## P: Process

| ID | Assertion | Trigger |
| --- | --- | --- |
| P1 | The change arrives as a PR against `main`, on one topic, and its title says what changes for someone who uses the library. | Always |
| P2 | Commit subjects are one sentence in the imperative with no type prefix (`Bind the session to …`, not `feat: …`). | New commits |
| P3 | A change a caller can see adds a line under "Unreleased" in `CHANGELOG.md`, under Added, Changed, Removed or Fixed, saying what changed. Before 1.0 that is all a break needs. | Public API, behaviour or floor change |
| P4 | A change may depart from a written rule, but says so: a comment, or an edit to the passage in the same PR, and to this file if a rule here moves. | Diff contradicts CONTRIBUTING.md or `docs/` |
| P5 | Nothing attributes the work to a tool or an AI: no co-author trailer, no "generated with" line in a commit, PR, doc or comment. | Commits, PR body, docs |
| P6 | A PR that claims verification names what ran: `./gradlew check`, and the live test with the receiver when it claims one. | PR body claims tests |

Authority: CONTRIBUTING.md § Branches and pull requests, docs/releasing.md.

## A: The public API

| ID | Assertion | Trigger |
| --- | --- | --- |
| A1 | A declaration is public only if a caller needs it. Public types live in `io.github.aivanyuk.airkast` or `.android`; `crypto`, `wire`, `session` and `internal` hold `internal` code only. | New or changed `public` declaration, new package |
| A2 | A changed `*/api/*.api` dump is deliberate: every removed or changed line is a break, so the PR is aimed at a minor release before 1.0 (a major after), and P3 records it. Before 1.0 no deprecation or shim is expected; from 1.0 a removal goes through the deprecation cycle. An added line is fine. | Diff touches `api/*.api` |
| A3 | No `data class` in the public API. A public value is a `@Poko` class. From 1.0, a property added to one goes last with a default, and the old constructor stays as a `@Deprecated(level = DeprecationLevel.HIDDEN)` secondary constructor. | New public class, new constructor parameter |
| A4 | Options that will grow are builder properties with defaults, as in `Airkast.Builder`, never new parameters on `connect`. A platform's defaults fill the same builder (`Airkast(context) { … }`); an app can change each one. | New option, new parameter on a public function |
| A5 | Every failure the public API reports is an `AirkastException` subclass (or a `CancellationException`). A new failure is a new subclass with KDoc saying when it happens. No raw `IOException`, `IllegalStateException` or `SecurityException` escapes a public call. | New `throw`, new public call that does I/O |
| A6 | A new member of a sealed type or enum that callers switch on (`ReceiverEvent`, `AirkastException`, `Compatibility`, `PlaybackState`) is fine, and its KDoc says when it happens. | New subclass or enum entry |
| A7 | Public declarations carry KDoc that says what a caller needs and the receivers or platforms it depends on, unless the name says it all. | New public declaration |
| A8 | A time in the public API is a `kotlin.time.Duration`, never a `Double` of seconds or a `Long` of millis, and a volume runs from 0 to 1. | New public property or parameter that holds a time or a volume |
| A9 | A change to the API changes `sample/` with it, so the sample compiles and shows the new call. | Diff touches `api/*.api` |

Authority: CONTRIBUTING.md § The public API, docs/releasing.md § What counts as a break.

## T: Threading and failure

| ID | Assertion | Trigger |
| --- | --- | --- |
| T1 | A public suspend function is main-safe: blocking socket, DNS or file I/O runs under `Dispatchers.IO` (or another thread the library owns), never on the caller's dispatcher. | New or changed public `suspend fun`, new blocking call |
| T2 | The control connection serves one exchange at a time: a new caller goes through `ControlConnection.exchange`, never writes to its link directly. | Changes in `session/` |
| T3 | The event channel's reader never suspends or blocks on a collector: events go out through `tryEmit` on the buffered flow and the state through the `StateFlow`. | Changes to `onMessage`, `EventChannel` |
| T4 | An I/O failure ends the session through `end(cause)`: pending requests fail with `Disconnected`, the state goes to `Stopped`, `ReceiverEvent.Disconnected` comes last, and the scope is cancelled. Nothing swallows an `IOException` and carries on with a socket in an unknown state. | New `catch`, new connection, new coroutine |
| T5 | The session does not reconnect by itself; a retry policy, if one comes, is an `Airkast.Builder` option that defaults to off. `AirkastPlayer` connects again only on `prepare()`, which the user starts. | Retry or reconnect logic |
| T6 | A log line (`Airkast.Builder.logger`) never holds a media URL, a key, a PIN, a pairing secret or the bytes a pairing derives. Lines go through `Log` (or `PlayerLog`), never `logger.log` directly, so a logger that throws never reaches a session, and a message is a lambda wherever it runs per message on the wire. | New `log.…`, `logger`, `Log(` |
| T7 | An event (`Airkast.Event`, `AirkastPlayer.Event`) goes to its listener through `report`, which catches what the listener throws. An event never carries a media URL or a key. | New `report(`, new event class |

Authority: docs/architecture.md § Threading and § Failure, CONTRIBUTING.md § The public API.

## C: Compatibility

| ID | Assertion | Trigger |
| --- | --- | --- |
| C1 | `airkast-core` stays plain JVM: no `android.*` import, and no new runtime dependency beyond kotlin-stdlib and kotlinx-coroutines. `airkast-android` adds only the Android platform; `airkast-media3` adds media3-common and kotlinx-coroutines-android, with media3's unstable API in internal classes only. A module never depends on a sibling other than `:core`. | New import, new `dependencies` line |
| C2 | Raising a floor (minSdk, JVM bytecode or class library level, Kotlin language or API version, the stdlib or coroutines minimum) is a break: P3 and A2's release rules apply, and docs/compatibility.md § Callers or § Senders changes with it. | `build-logic/`, `libs.versions.toml` floor entries |
| C3 | An Android call above minSdk sits behind a `Build.VERSION.SDK_INT` check, and a Robolectric test pins the levels on both sides with `@Config(sdk = [...])`. Lint's NewApi decides the call; the reviewer checks the test. | New `SDK_INT` branch, new platform API |
| C4 | Network code on Android goes through `Airkast.Builder.socketFactory` and `LocalNetwork`, so the session binds to the receiver's network and fails with `NotPermitted` when Android 17 withholds `ACCESS_LOCAL_NETWORK`. A new path that opens a socket or starts `NsdManager` does both. | New socket, new `NsdManager` call |
| C5 | A setting shared by modules lives in a `build-logic` convention, not in one module's build file. | New block in a module's `build.gradle.kts` |

Authority: CONTRIBUTING.md § Compatibility floors, docs/compatibility.md § Senders, docs/architecture.md § Seams.

## W: The wire and receivers

| ID | Assertion | Trigger |
| --- | --- | --- |
| W1 | A behaviour one receiver needs carries a comment naming the receiver and the firmware it was seen on, and a line in docs/compatibility.md under that receiver. | New special case in `session/` |
| W2 | When two receivers need different behaviour, the choice is made once, from the TXT record (`model`, `srcvers`, feature bits), never by retrying after an error or sniffing the failure. | Branch on receiver behaviour |
| W3 | A change to what goes over the wire (a command, a header, a plist key, the pairing or SETUP flow) ran the live test on a receiver, and docs/compatibility.md § Checked gains a row with the receiver, its firmware and `srcvers`, the date and the result. The PR says so. | Changes in `session/`, `wire/`, `crypto/` that alter bytes sent |
| W4 | `Receiver.compatibility` stays in step with docs/compatibility.md § How it decides: a new rule or bit changes both, and `ReceiverTest` covers it with a real TXT record where one exists. | `Receiver.kt` |
| W5 | Crypto changes keep their vector tests passing unchanged; a new primitive comes with published vectors (RFC, srptools, pyatv). | `crypto/` |

Authority: CONTRIBUTING.md § Receiver quirks and § Tests, docs/compatibility.md.

## X: Tests

| ID | Assertion | Trigger |
| --- | --- | --- |
| X1 | New logic comes with tests, failure paths included. A protocol step is tested against `FakeReceiver` over loopback; Android code under Robolectric. | New logic |
| X2 | The live tests stay opt-in: they skip unless `AIRKAST_RECEIVER` names a receiver, so CI never needs one. | `LiveReceiverTest`, new test needing a device |

Authority: CONTRIBUTING.md § Tests.

## S: Sources and names

| ID | Assertion | Trigger |
| --- | --- | --- |
| S1 | Code comes only from sources under a licence compatible with Apache-2.0, credited in the README. A GPL source (DiPlay, for one) gives facts, never code. Code that looks transcribed from another project is a `question`. | New protocol code, a comment citing a project |
| S2 | No artifact, package, class, file or module is named after "AirPlay". Describing what airkast talks to ("AirPlay receivers") is fine. | New name |

Authority: CONTRIBUTING.md § Sources and names, NOTICE.

## Already enforced (not the reviewer's business)

A script decides these exactly; never report them:

- formatting: ktlint (`ktlintCheck`);
- compiler and lint warnings: warnings are errors, and lint's NewApi;
- a public API change without its dump: `checkKotlinAbi`;
- a call `airkast-core` makes that Android 6.0 lacks: Animal Sniffer (`animalsnifferMain`);
- the tests themselves: CI's `verify` job runs `./gradlew check`.

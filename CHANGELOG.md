# Changelog

Every release, newest first. The format follows [Keep a Changelog](https://keepachangelog.com/),
and versions follow [docs/releasing.md](docs/releasing.md). A pull request that changes what a
caller sees adds its line under "Unreleased".

## Unreleased

### Added

- `AirkastSessionService`, a `MediaSessionService` over the app's `AirkastPlayer`, which keeps a
  cast going with the app in the background: subclass it with the player and declare it in the
  manifest. Its notification has a Stop casting button while a cast is on, and it releases only
  the session, never the player. `sessionActivity()` and `buildSession(builder)` change the
  session. `airkast-media3` now depends on media3-session, and the sample's `CastService` is one.
- Statistics: `Airkast.Builder.eventListener` hears what the client did as typed `Airkast.Event`s,
  each timed by the client: `Connected` (with how it paired), `ConnectFailed`, `Paired`,
  `PairingFailed`, `CredentialsDropped`, `Loaded`, `LoadFailed` and `SessionEnded` (with the
  failure when the receiver or the network ended it). `AirkastPlayer.Builder.eventListener` hears
  how casts start and end: `CastStarted`, `CastFailed`, `CastAbandoned` (with the PIN or
  password it was asking for, if any) and `CastEnded`, with its reason (`Disconnect`, `Back`, `Replaced`, `Lost`, `Released`)
  and length. Both are called on the thread where the thing happened, and one that throws never
  reaches the session.
- `Airkast.Logger.println()` for a desktop JVM, and `Airkast.Logger.logcat()` in
  `airkast-android`, which writes under the tag `airkast`. `AirkastPlayer` logs its connects,
  PIN prompts, handoffs, wake locks and how casts end through the client's logger, or
  `AirkastPlayer.Builder.logger`, and `ReceiverDiscovery(context, logger)` logs the scan.
- `AirkastPlayer` runs a whole cast: `connect(receiver)` opens a session through the app's
  `Airkast`, asks for a PIN or password when the receiver needs one (`connection` turns
  `AwaitingSecret` until `enterSecret`), plays the media item, and `disconnect()` stops the TV's
  player, closes the session and clears the item. `connection` is a `StateFlow` of `Idle` (with
  the failure), `Connecting`, `AwaitingSecret` and `Connected`. BACK on the TV's remote ends a
  cast the player opened, unless `disconnectOnBack = false`. After a dropped cast, `prepare()`
  connects again. Setting `session` by hand works as before: the app keeps that session.
- `AirkastPlayer.Builder.localPlayer`, as media3's `CastPlayer.Builder.setLocalPlayer`: the app's
  own player, such as an `ExoPlayer`, plays until a cast starts. Its current item then moves to
  the receiver at the position it reached, and comes back paused where the receiver left it when
  the cast ends, by `disconnect()`, BACK, a failure or `session = null`. One `MediaSession` over
  the `AirkastPlayer` serves both, and the video surface stays with the local player. The sample
  apps play on the phone and hand over.
- Pairing with a PIN or password: `airkast.connect(receiver) { secret -> ask(secret) }` pairs a
  receiver that asks for a PIN (`Compatibility.NeedsPin`) or a password (`NeedsPassword`) on its
  first connect, keeps the `Credentials` in the client's `CredentialStore`, and proves the pairing
  on every connect after. The prompt gets a `Secret`, `Pin` or `Password`, so the app asks for
  the right one. `airkast.pair(receiver, Secret.Password) { … }` pairs one that asks without
  saying so. A wrong one throws `AirkastException.SecretRejected`, and credentials a receiver
  refuses leave the store. `Credentials.encoded` and `Credentials.decode` store them, in pyatv's
  format, with a fifth field for the password, which a Mac asks for again with an HTTP Digest
  challenge on every SETUP. The PIN is checked on the LG CX and the password on a Mac set to
  "Require password", from the sample app.
- `CredentialStore`, where a client keeps pairings: `CredentialStore.inMemory()`, the default,
  `CredentialStore.file(file)`, or the app's own over its secrets. `Airkast(context)` keeps them
  in a file in the app's no-backup files.
- `Compatibility.AccessRestricted`, for a receiver that lets in only its owner's devices (`act=2`):
  a Mac's AirPlay Receiver at its default, "Current User".

### Changed

Breaking, under the rules for a minor release before 1.0 ([releasing](docs/releasing.md)):

- `Airkast.Builder.logger` takes an `Airkast.Logger` in place of `(String) -> Unit`: each line
  has a level (`Verbose` for every message on the wire, `Debug` for each protocol step, then
  `Info`, `Warn`, `Error`) and a tag for the part that wrote it, and the logger's `minLevel`
  (`Debug` by default) keeps lines below it from being built. A lambda still works:
  `logger = Airkast.Logger { level, tag, message, error -> … }`.
- `Airkast` is a client, configured once and shared, in place of an object with
  `connect(receiver, identity, options)`: `Airkast { … }` builds one, `airkast.connect(receiver)`
  opens a session, and `airkast.copy { … }` makes a variant. `SessionOptions` and its builder are
  gone: their options, with `identity`, `socketFactory` and `credentialStore`, are properties of
  `Airkast.Builder`. One client keeps one `SenderIdentity`, where every connect made a new random
  device id.
- On Android, `Airkast(context) { … }` replaces `Airkast.connect(context, …)` and
  `Airkast.pair(context, …)`. The TV shows the app's label in place of "airkast". Its
  `socketFactory` checks the local network permission and binds to the receiver's network, as
  the overloads did.
- `socketFactory` takes the receiver: `(Receiver) -> SocketFactory?`, and may refuse a connect
  with an `AirkastException`.
- `VideoSession` is `AirkastSession` and `VideoItem` is `Media`, so a receiver that plays only
  audio, or a protocol other than AirPlay video v2, can join without another rename.
- `AirkastPlayer(context, airkast)` takes the client it connects through.
- `Receiver.isSupported` is true for `Compatibility.NeedsPin` and `NeedsPassword` too, since
  such a receiver now plays once paired.
- `ntpTiming` is on by default. A Mac answers SETUP with 500 without it, and the LG CX plays
  either way. The receiver now needs to reach the sender over UDP.

### Fixed

- A connect cancelled midway closes the session it opened, where it used to leave it open and
  unreferenced, and a pairing cancelled while it connects closes its connection.
- `AirkastPlayer.disconnect()` clears the media item, which takes a `MediaSession`'s notification
  down. `clearMediaItems()` cannot: the player offers no `COMMAND_CHANGE_MEDIA_ITEMS`, so media3
  ignores it, and the sample's cast left its notification up after it ended.
- A load on a Mac ends when the Mac takes the item. Its `currentItemChanged` names no item, so
  `load` timed out after `loadTimeout` while the Mac played, and `AirkastPlayer` never polled the
  position: its notification and controls stood still.
- A Mac's "Allow … to AirPlay" prompt names the app, from `SenderIdentity.name`, where it showed
  "". The name now goes in an `X-Apple-Client-Name` header on every request.

## 0.2.0 - 2026-10-09

### Added

- `sample/`, the integration end to end in `sample/cast` (discovery, Android 17's local network
  permission, `AirkastPlayer` under a `MediaSession`, tracks, and the TV's events), with an app
  over it in Compose (`sample/compose`) and one in views (`sample/views`).
- `Receiver.isSupported`, and `SessionOptions.copy { … }` in place of `newBuilder()`.
- `AirkastPlayer.Builder.streaming`, on by default, with `streaming = false` for `file` items.

### Changed

Breaking, under the rules for a minor release before 1.0 ([releasing](docs/releasing.md)):

- Every time is a `kotlin.time.Duration`: `VideoItem.startAt`, `seek(position)` and what it
  returns, `PlaybackInfo.position`, `duration`, `buffered` and `seekable` (now
  `List<ClosedRange<Duration>>`, in place of `TimeRange`), `RateChanged.position`,
  `TimeJumped.position`, `SessionOptions.connectTimeout`, `requestTimeout` and `loadTimeout`,
  and `AirkastPlayer.Builder.positionPollInterval`. Callers write `10.minutes` for `600.0`.
- `MediaItem` is `VideoItem`, so it no longer clashes with media3's `MediaItem`.
- Tracks: `MediaKind`, `MediaOption` and `MediaSelection` are `TrackKind` and `Track`;
  `selectedMedia()` is `tracks()`, and `selectMedia(list)` is `selectTrack(kind, id)`, one kind
  per call.
- `ReceiverEvent.RemoteCommand` and its string codes are `ReceiverEvent.Back`, sent on the key-up
  when the TV expects the sender to stop, and `ReceiverEvent.VolumeChanged`. Other codes arrive as
  `ReceiverEvent.Other`.
- `VideoSession.volume()` runs from 0 to 1, as `VolumeChanged` does, in place of decibels.
- `StateChanged.reason` and `ItemChanged.reason` are a `Reason` value class, with `Ended` and
  `Interrupted` named, in place of a string.
- `AirkastPlayer.Builder` has properties in place of setters, and `AirkastPlayer(context) { … }`
  builds one.
- `ReceiverDiscovery.receivers` is a property, and `LocalNetwork.accessible` and
  `permissionRequired` are `isAccessible` and `needsPermission`.
- `VideoSession.events` is a `Flow` that completes after `Disconnected`, and emits that alone to
  a collector that comes once the session has ended, in place of a `SharedFlow` that never
  completed and, with no replay, left a late collector waiting for an end it had missed.
- Items load as `streaming` by default (`VideoItem.streaming`), since the LG reports tracks and
  buffered ranges only for a `streaming` item: a `file` item answers an empty track list and
  ignores a selection, which is why 0.1 could never switch a track. The LG reports a paused
  `streaming` item as `loading` with rate 0; the session reports `Paused` while the rate is 0, so
  `state`, the events and the player all say paused.

## 0.1.1 - 2026-10-09

The code of 0.1.0, built.

### Fixed

- JitPack builds the release. 0.1.0's build failed before compiling anything, because JitPack
  resolved the `openjdk21` alias to a JDK that would not download. `jitpack.yml` now names an
  exact JDK.

## 0.1.0 - 2026-10-08

Never built on JitPack, so it cannot be resolved: use 0.1.1, which holds the same code.

The first release. What it holds:

### Requires

- Android 6.0 (API 23) or a Java 11 JVM; Kotlin 2.2 or later in the calling code; kotlin-stdlib
  2.2.21 and kotlinx-coroutines 1.10.2 at least. Before the first release `airkast-android` had
  minSdk 21 and the jars were Java 17 bytecode.
- `airkast-media3`: media3 1.11.1.

### Added

- `Airkast.connect` pairs with an AirPlay video v2 receiver transiently, with no PIN, and opens
  a `VideoSession`: load a URL at a start position, play, pause, seek, stop, poll the position
  and buffered ranges, read and switch audio and subtitle renditions, and read the volume.
- `VideoSession.events` and `state`: playback state, item changes and ends, the TV remote's rate
  changes, jumps, BACK and volume, and the end of the session.
- `Receiver.compatibility` says from the TXT record whether airkast can play on a receiver, and
  if not, why.
- `SessionOptions`, built with `SessionOptions { … }`, including a `socketFactory` for the
  session's connections.
- `airkast-android`: `ReceiverDiscovery` over `NsdManager`; `LocalNetwork` for Android 17's
  local network permission; `Airkast.connect(context, …)`, which checks that permission and binds
  the session to the network the receiver is on. Discovery and connecting fail with
  `AirkastException.NotPermitted` without the permission.
- `airkast-media3`: `AirkastPlayer`, a media3 `Player` over a `VideoSession`. It loads its item
  when a session attaches, follows the receiver's events and position, reads the TV's volume, and
  holds a wake lock and a Wi-Fi lock while a cast plays (`setKeepAwake(false)` to opt out).
- Every failure is an `AirkastException`: `Unreachable` when no connection opens, `Disconnected`
  when one drops, `UnexpectedReply`, `PairingFailed`, `Rejected`, `Timeout`, `NotPermitted`, and
  `DiscoveryFailed` with the platform's error code.
- A receiver that answers late costs one `Timeout`, not the session. The late answer is dropped
  when it comes, and the session ends only if the receiver is still silent at the next request.

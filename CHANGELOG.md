# Changelog

Every release, newest first. The format follows [Keep a Changelog](https://keepachangelog.com/),
and versions follow [docs/releasing.md](docs/releasing.md). A pull request that changes what a
caller sees adds its line under "Unreleased".

## Unreleased

### Added

- `Receiver.isSupported`, and `SessionOptions.copy { … }` in place of `newBuilder()`.
- `AirkastPlayer.Builder.streaming` loads items as `streaming`, which the LG needs to report
  tracks and buffered ranges (docs/compatibility.md).

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

### Fixed

- `tracks()` and `selectTrack()` say that the LG reports and switches tracks only for a
  `streaming` item: a `file` item, the default, answers an empty list and ignores a selection.
  The player reads a `loading` report as paused while it asked for the pause, which is how the LG
  reports a paused `streaming` item.

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

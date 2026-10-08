# Changelog

Every release, newest first. The format follows [Keep a Changelog](https://keepachangelog.com/),
and versions follow [docs/releasing.md](docs/releasing.md). A pull request that changes what a
caller sees adds its line under "Unreleased".

## Unreleased

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

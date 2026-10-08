# Architecture

## Modules

| Module | Artifact | Holds | Depends on |
| --- | --- | --- | --- |
| `:core` | `airkast-core` | The protocol: pairing, the session, its commands and events. Plain JVM | kotlin-stdlib, kotlinx-coroutines-core |
| `:android` | `airkast-android` | Discovery through `NsdManager`, the local network permission, binding to the receiver's network, `Airkast.connect(context, …)` | `:core` |
| `:media3` | `airkast-media3` | `AirkastPlayer`, a media3 `Player` over a `VideoSession`, so a media3 UI and `MediaSession` drive a receiver, and the wake and Wi-Fi locks a cast needs | `:core`, media3-common, kotlinx-coroutines-android |

Modules depend on `:core` and never on each other. An app that draws its own controls doesn't
pull media3, and a desktop JVM doesn't pull Android. All artifacts share one version
([releasing](releasing.md)).

`build-logic/` holds the conventions every module applies: `airkast.jvm.library`,
`airkast.android.library` and `airkast.publish`. A setting that applies to more than one module
goes there, never into a module's own build file.

## Layers in `:core`

| Package | Holds | May use |
| --- | --- | --- |
| `io.github.aivanyuk.airkast` | The public API: `Airkast`, `VideoSession`, `Receiver`, `Compatibility`, `SessionOptions`, the values and events, `AirkastException` | everything below |
| `.session` | The protocol's state: `DefaultVideoSession` (AirPlay video v2), `ControlConnection`, `EventChannel`, `TransientPairing`, `TimingResponder` | `wire`, `crypto` |
| `.wire` | Encodings and framing: HTTP and RTSP messages, TLV8, binary plists, the encrypted `Link`. No protocol decisions | `crypto` |
| `.crypto` | SRP-6a, HKDF-SHA512, ChaCha20-Poly1305, as pure functions with vector tests | nothing |
| `.internal` | Build support, such as the `@Poko` annotation | nothing |

Everything outside the root package is `internal`. A lower layer never imports a higher one.

## One session

`Airkast.connect` opens the control connection, pairs, and encrypts it. `DefaultVideoSession`
then sends the base SETUP, opens the event channel, sends RECORD and a SETUP for the type-130
stream, and from there drives playback with `POST /command`. The receiver answers every command
with 200 and sends results and events over the event channel. A request carries a `messageID`,
and its answer comes back on the event channel with the same ID.

The protocol facts, with the receivers they were seen on, are in
[compatibility.md](compatibility.md).

## Seams

- **`VideoSession` is the protocol seam.** Another protocol, such as AirPlay video v1's
  `POST /play`, or a v2 session that pairs with a PIN, is another implementation, picked in
  `Airkast.connect` from `Receiver.compatibility`. Callers don't change. The `Compatibility` case
  it clears becomes `Supported` in the same change.
- **`SessionOptions.socketFactory` is the network seam.** `:core` opens every connection through
  it, so `:android` can bind the session to a network without `:core` knowing Android.
- **`SessionOptions.logger` is the logging seam**, so the app routes lines to its own logger.

## Threading

- Every public suspend function is main-safe: it moves blocking I/O to `Dispatchers.IO` itself.
- The control connection serves one exchange at a time, so a position poll and a command never
  interleave on the wire.
- One daemon thread per session reads the event channel. It never suspends: events go out through
  a `SharedFlow` with a buffer of 64, and the last playback state through a `StateFlow`, for
  collectors that start late.
- A session owns a `CoroutineScope` for its keepalive (`POST /feedback` every two seconds).
  `close()` cancels it.

## Failure

- Everything the public API throws is an `AirkastException`, or a `CancellationException`.
- An I/O failure on either connection ends the session. It fails every pending request with
  `Disconnected`, sets the state to `Stopped` and emits `ReceiverEvent.Disconnected` last.
- A session never reconnects by itself. Whether to reconnect depends on whether the user still
  wants the cast, which only the app knows. A later `ReconnectPolicy` may change this, as an
  option.

## The media3 player

`AirkastPlayer` is a public interface that extends media3's `Player`. Its implementation,
`SessionPlayer`, extends `SimpleBasePlayer`, which media3 marks unstable, so that class stays
internal and no unstable type reaches airkast's API dump.

- **State first, then the wire.** Every `handle…` method updates the player's state and returns a
  completed future, then sends the command. The receiver's events and a position poll (once a
  second) correct the state when they arrive.
- **One item.** A media item loads as soon as a session is attached, as a Cast player's does. A
  new session loads it again at the last position.
- **The session is the app's.** The player never opens or closes one. It lets go of a session
  that has ended, and reports a `PlaybackException` only when the connection failed, not when it
  was closed.
- **Locks follow the state.** A partial wake lock and a Wi-Fi lock are held while a session is
  attached and the item is buffering or ready, paused included, since the keepalive has to go on.

## Public API rules

[CONTRIBUTING.md](../CONTRIBUTING.md#the-public-api) has them. In short: explicit API mode, `@Poko`
values instead of data classes, builders for options, one exception type, and a checked API dump
for every module.

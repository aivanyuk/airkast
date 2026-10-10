# Architecture

## Modules

| Module | Artifact | Holds | Depends on |
| --- | --- | --- | --- |
| `:core` | `airkast-core` | The protocol: pairing, the session, its commands and events. Plain JVM | kotlin-stdlib, kotlinx-coroutines-core |
| `:android` | `airkast-android` | Discovery through `NsdManager`, the local network permission, binding to the receiver's network, and `Airkast(context)`, a client with those as its defaults | `:core` |
| `:media3` | `airkast-media3` | `AirkastPlayer`, a media3 `Player` that runs a cast through an `Airkast`, so a media3 UI and `MediaSession` drive a receiver, hands the item to and from the app's local player, and holds the wake and Wi-Fi locks a cast needs | `:core`, media3-common, kotlinx-coroutines-android |
| `:sample:cast` | none | The reference integration: `Cast` builds the app's `Airkast` and an `AirkastPlayer` over an `ExoPlayer`, and `CastService` puts a `MediaSession` over the player | `:android`, `:media3`, media3-exoplayer |
| `:sample:compose`, `:sample:views` | none | The sample apps: one screen over `Cast`, in Compose and in views | `:sample:cast` |

Modules depend on `:core` and never on each other. An app that draws its own controls doesn't
pull media3, and a desktop JVM doesn't pull Android. All artifacts share one version
([releasing](releasing.md)). The `:sample:*` modules are never published, and `:sample:cast` is
the one place that depends on both `:android` and `:media3`.

## Three levels

The API hides the protocol in three levels, as OkHttp and Retrofit do, or Coil's `ImageLoader`
and `AsyncImage`. Each level uses only the public API of the one below, so an app can step down a
level for one thing and keep the rest.

1. **`AirkastSession`**: one connection to one receiver, from `Airkast.connect`. Its calls are the
   protocol's: load, play, seek, tracks, events.
2. **`Airkast`**: the client, configured once and shared. It opens sessions, pairs, and keeps
   pairings in its `CredentialStore`. Its builder holds every option, and `copy { }` makes a
   variant. `Airkast(context)` in `:android` builds the same class with Android's defaults.
3. **`AirkastPlayer`**: a media3 `Player` that runs a cast through a client: the connect, the
   PIN, BACK on the TV's remote and the end, with a `connection` flow for the UI. With a local
   player, it plays on the phone between casts. Setting its `session` by hand steps down to
   level 1 and keeps the player.

A default lives in the level that knows it: Android's in `Airkast(context)`, the player's in
`AirkastPlayer.Builder`, the protocol's in the session. Each one is a builder property an app
can change.

`build-logic/` holds the conventions every module applies: `airkast.jvm.library`,
`airkast.android.library` and `airkast.publish`. A setting that applies to more than one module
goes there, never into a module's own build file.

## Layers in `:core`

| Package | Holds | May use |
| --- | --- | --- |
| `io.github.aivanyuk.airkast` | The public API: `Airkast`, `AirkastSession`, `Media`, `Receiver`, `Compatibility`, `Credentials`, `CredentialStore`, the values and events, `AirkastException` | everything below |
| `.session` | The protocol's state: `DefaultVideoSession` (AirPlay video v2), `SessionOptions` (what one connect takes from its client), `ControlConnection`, `EventChannel`, the pairings (`TransientPairing`, `PinPairing`, `PairVerify`), `TimingResponder` | `wire`, `crypto` |
| `.wire` | Encodings and framing: HTTP and RTSP messages, TLV8, binary plists, the encrypted `Link`. No protocol decisions | `crypto` |
| `.crypto` | SRP-6a, HKDF-SHA512, ChaCha20-Poly1305, X25519, Ed25519, as pure functions with vector tests | nothing |
| `.internal` | Build support, such as the `@Poko` annotation | nothing |

Everything outside the root package is `internal`. A lower layer never imports a higher one.

## One session

`Airkast.connect` opens the control connection, pairs, and encrypts it. It pairs transiently,
or, with credentials in the client's `CredentialStore`, proves the pairing `Airkast.pair` made with
a PIN (pair-verify), and the channel keys derive from that pairing's secret. A receiver that
advertises a PIN and has no credentials pairs first, when the caller gave a way to ask for it. `DefaultVideoSession`
then sends the base SETUP, opens the event channel, sends RECORD and a SETUP for the type-130
stream, and from there drives playback with `POST /command`. The receiver answers every command
with 200 and sends results and events over the event channel. A request carries a `messageID`,
and its answer comes back on the event channel with the same ID.

The protocol facts, with the receivers they were seen on, are in
[compatibility.md](compatibility.md).

## Seams

- **`AirkastSession` is the protocol seam.** Another protocol is another implementation, picked
  in `Airkast.connect` from what the receiver advertises and what the `Media` holds: AirPlay
  video v1's `POST /play`, a receiver that plays only audio (RAOP, where the sender decodes and
  streams the audio itself), or a local file or DASH stream the sender serves over HTTP. Callers
  don't change, which is why neither the session nor the item is named for video. The
  `Compatibility` case it clears becomes `Supported` in the same change. With a second
  implementation, the choice becomes a list of factories on `Airkast.Builder`, as Retrofit's
  converter factories and Coil's components are, so an app can add its own; until then it is a
  branch in `connect`.
- **`Airkast.Builder.socketFactory` is the network seam.** `:core` opens every connection
  through the factory it returns for the receiver, so `:android` can refuse a connect without the
  local network permission and bind the session to a network without `:core` knowing Android.
- **`CredentialStore` is the storage seam.** `:core` keeps pairings in memory or in a file,
  `:android` puts that file in the no-backup files, and an app with a keystore implements its
  own.
- **Pairing is a step before the session.** How a connection pairs (transient, pair-verify, and
  later a password) changes only the secret the session's keys derive from, so it is chosen in
  `DefaultVideoSession.open` and the session after it is the same.
- **`Airkast.Builder.logger` is the logging seam**, so the app routes lines to its own logger.

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
- A late answer is not a failure of the connection. The request throws `Timeout`, the control
  connection reads and drops the answer before its next request, and only a receiver that is
  still silent then ends the session. A read times out only between messages: inside one, `Link`
  waits out a few timeouts, since giving up there would leave the stream out of step.
- A connect cancelled midway closes what it opened. `withContext` drops a result its caller no
  longer waits for, so `Airkast` closes it rather than leave a session open and unreferenced.
- A session never reconnects by itself. Whether to reconnect depends on whether the user still
  wants the cast, which only the app knows. A later `ReconnectPolicy` may change this, as an
  option. `AirkastPlayer` connects again on `prepare()` after a cast it opened dropped, since that
  is media3's retry, which only the user starts. With a local player, the item goes back to the
  phone instead, and `prepare()` plays it there.

## The media3 player

`AirkastPlayer` is a public interface that extends media3's `Player`. Its implementation,
`SessionPlayer`, extends `SimpleBasePlayer`, which media3 marks unstable, so that class stays
internal and no unstable type reaches airkast's API dump.

- **State first, then the wire.** Every `handle…` method updates the player's state and returns a
  completed future, then sends the command. The receiver's events and a position poll (once a
  second) correct the state when they arrive.
- **One item.** A media item loads as soon as a session is attached, as a Cast player's does. A
  new session loads it again at the last position.
- **A session is the player's or the app's.** One that `connect` opened is the player's: it
  closes it on `disconnect`, on the next `connect`, when a session set by hand replaces it, and on
  `release`, and it ends the cast on BACK unless `disconnectOnBack` is off. One the app set is the
  app's: the player never closes it, and leaves BACK to it. Either way it lets go of a session
  that has ended, and reports a `PlaybackException` only when the connection failed, not when it
  was closed.
- **`disconnect` clears the item.** The player offers no `COMMAND_CHANGE_MEDIA_ITEMS`, so media3's
  `clearMediaItems()` does nothing on it. `disconnect` clears the item itself, which takes a
  `MediaSession`'s notification down: media3 keeps one up for an idle player that has played.
  With a local player, the item goes to it instead, and the notification stays.
- **A local player, as `CastPlayer` has one.** With `localPlayer`, `build()` returns
  `HandoffPlayer`, a `ForwardingSimpleBasePlayer` over the local player or `SessionPlayer`,
  whichever plays. `SessionPlayer` tells it when a session has attached, once its state shows
  the session, and when a cast has ended, before it lets go of the item, so a handoff never
  shows an empty player in between. Only the current item moves: the local player keeps its
  playlist, unless the cast moved on to another item. The item comes back paused, since a cast
  may end with nobody at the phone, by BACK on the TV or a dropped network. The video surface
  always goes to the local player, so one set during a cast is there when it ends.
- **It connects through `Airkast`, never `airkast-android`.** The app passes the client in, so
  `:media3` depends on `:core` alone.
- **Locks follow the state.** A partial wake lock and a Wi-Fi lock are held while a session is
  attached and the item is buffering or ready, paused included, since the keepalive has to go on.

## Public API rules

[CONTRIBUTING.md](../CONTRIBUTING.md#the-public-api) has them. In short: explicit API mode, `@Poko`
values instead of data classes, builders for options, one exception type, and a checked API dump
for every module.

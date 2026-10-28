# Architecture

How the code is organised, and why. This page is for contributors. To use the library, read the
[README](../README.md) and the [guide](guide.md).

Two terms appear throughout:

- **Control connection**: the TCP connection that carries the sender's requests (HTTP and RTSP)
  and the receiver's answers. It is encrypted once pairing completes.
- **Event channel**: a second connection, on which the receiver sends results and events, such
  as a state change or BACK pressed on its remote.

## Modules

| Module | Artifact | Holds | Depends on |
| --- | --- | --- | --- |
| `:core` | `airkast-core` | The protocol: pairing, the session, its commands and events, and the audio stream. Plain JVM | kotlin-stdlib, kotlinx-coroutines-core |
| `:android` | `airkast-android` | Discovery through `NsdManager`, the local network permission, binding to the receiver's network, and `Airkast(context)`, a client with those as its defaults | `:core` |
| `:media3` | `airkast-media3` | `AirkastPlayer`, a media3 `Player` that runs a cast through an `Airkast`, so a media3 UI and `MediaSession` drive a receiver, hands the item to and from the app's local player, routes that player's audio to a speaker through an `AudioRoute`, and holds the wake and Wi-Fi locks a cast needs; `AirkastSessionService`, a `MediaSessionService` over it | `:core`, media3-common, media3-session, kotlinx-coroutines-android |
| `:exoplayer` | `airkast-exoplayer` | `AirkastAudioSink`, an ExoPlayer `AudioSink` that plays on the phone until an `AudioStream` attaches, and is an `AudioRoute` | `:core`, media3-exoplayer |
| `:sample:cast` | none | The reference integration: `Cast` builds the app's `Airkast` and an `AirkastPlayer` over an `ExoPlayer` with `AirkastAudioSink`, and `CastService` puts a `MediaSession` over the player | `airkast-android`, `airkast-media3` and `airkast-exoplayer`, built from `:android`, `:media3` and `:exoplayer`; media3-exoplayer |
| `:sample:compose`, `:sample:views` | none | The sample apps: one screen over `Cast`, in Compose and in views | `:sample:cast` |

Modules depend on `:core` and never on each other, so an app that draws its own controls doesn't
pull in media3, and a desktop JVM doesn't pull in Android. All artifacts share one version
([releasing](releasing.md)).

The `:sample:*` modules are never published. `:sample:cast` is the one place that depends on
`:android`, `:media3` and `:exoplayer` together. `:media3` routes a speaker's audio through
`AudioRoute`, an interface in `:core`, so it never depends on `:exoplayer`, and an app without
ExoPlayer passes a route of its own. The samples declare them by their Maven Central coordinates, as an app
does, and the `airkast.sample` convention substitutes the modules in this repository.

## Three levels

The API hides the protocol in three levels, as OkHttp and Retrofit do, or Coil's `ImageLoader`
and `AsyncImage`. Each level uses only the public API of the one below, so an app can step down a
level for one thing and keep the rest.

1. **`AirkastSession`**: one connection to one receiver, from `Airkast.connect`. Its calls are the
   protocol's: load, play, seek, tracks, events. **`AudioStream`**, from `Airkast.connectAudio`, is
   its counterpart for a speaker: the app writes PCM, as to an `AudioTrack`.
2. **`Airkast`**: the client, configured once and shared. It opens sessions, pairs, and keeps
   pairings in its `CredentialStore`. Its builder holds every option, and `copy { }` makes a
   variant. `Airkast(context)` in `:android` builds the same class with Android's defaults.
3. **`AirkastPlayer`**: a media3 `Player` that runs a cast through a client: the connect, the
   PIN or password, BACK on the TV's remote and the end, with a `connection` flow for the UI.
   With a local player, it plays on the phone between casts, and with an `AudioRoute` too it casts
   to a speaker by leaving that player playing and routing its audio to an `AudioStream`. Setting
   its `session` by hand steps down to level 1 and keeps the player.

A default lives in the level that knows it: Android's in `Airkast(context)`, the player's in
`AirkastPlayer.Builder`, the protocol's in the session. Each one is a builder property an app
can change.

`build-logic/` holds the conventions the modules apply: `airkast.jvm.library`,
`airkast.android.library` and `airkast.publish` for the libraries, and `airkast.sample` for the
samples. A setting that applies to more than one module goes there, never into a module's own
build file.

## Layers in `:core`

| Package | Holds | May use |
| --- | --- | --- |
| `io.github.aivanyuk.airkast` | The public API: `Airkast`, `AirkastSession`, `AudioStream`, `AudioRoute`, `Media`, `PcmFormat`, `NowPlaying`, `Receiver`, `Compatibility`, `Credentials`, `CredentialStore`, `MediaResolver`, `ResolvedMedia`, `LocalFileResolver`, `Origin`, `Resource`, the values and events, `AirkastException` | everything below |
| `.session` | The protocol's state: `Opening` (the control connection, pairing and base SETUP every session starts with), `DefaultVideoSession` (AirPlay video v2), `DefaultAudioStream` (AirPlay 2 realtime audio) and its `PcmBuffer`, `SessionOptions` (what one connect takes from its client), `ControlConnection`, `EventChannel`, the pairings (`TransientPairing`, `SecretPairing`, `PairVerify`), `Digest`, `TimingResponder`, `HttpOrigin` (the HTTP server a session's `Origin` is), `MasterPlaylist` (fetches the HLS master that `renditions()` reads), `RemoteCommands` (the remote's button codes as `RemoteCommand`), and `Log`, which writes to the client's logger | `wire`, `crypto` |
| `.wire` | Encodings and framing: HTTP and RTSP messages, TLV8, binary plists, the encrypted `Link`, RTP audio and sync packets, uncompressed ALAC frames, DMAP. No protocol decisions | `crypto` |
| `.crypto` | SRP-6a, HKDF-SHA512, ChaCha20-Poly1305, X25519, Ed25519, as pure functions with vector tests. airkast carries its own ChaCha20-Poly1305 because Android's JCA has no provider for it below Android 9, and its own X25519 and Ed25519 because the JCA has none below Android 13 | nothing |
| `.internal` | Build support, such as the `@Poko` annotation | nothing |

Everything outside the root package is `internal`. A lower layer never imports a higher one.

## One session

`Airkast.connect` opens a session in these steps:

1. **Describe, for a receiver typed in by hand.** A receiver with no TXT record is asked
   `GET /info` first, on a connection of its own, so the steps below know what discovery would
   have said. Credentials are looked up under the described receiver, then under the one typed
   in, since a pairing made before is filed under its host and port.
2. **Pair.** It opens the control connection and pairs. With credentials for the receiver in the
   client's `CredentialStore`, it proves the pairing that `Airkast.pair` made with a PIN or
   password (pair-verify). Without them, a receiver that advertises a PIN or password pairs first,
   if the caller gave a prompt function. Any other receiver pairs transiently.
3. **Encrypt.** The control connection's keys derive from the pairing's shared secret.
4. **Set up the stream.** `DefaultVideoSession` sends the base SETUP, opens the event channel,
   and sends RECORD and a SETUP for the type-130 stream.
5. **Answer a Digest challenge, if any.** A receiver may answer the base SETUP with an HTTP Digest
   challenge, as a Mac with a password does. The password kept with the credentials answers it,
   and then signs every request on the control connection. With no password kept, or a wrong one,
   the open fails, and a connect with a prompt opens again with the password the user types, and
   keeps it.
6. **Play.** From there it drives playback with `POST /command`. The receiver answers every
   command with 200, and sends the result over the event channel. A request carries a
   `messageID`, and its result comes back on the event channel with the same ID.

## One audio stream

`Airkast.connectAudio` pairs as `connect` does, with the same prompts, then:

1. **Set up the session.** `DefaultAudioStream` sends the base SETUP with NTP timing, sends the
   receiver's timing port one request so a firewall lets the receiver's in, and opens the event
   channel.
2. **RECORD, then set up the stream.** RECORD names the first packet's sequence number and RTP
   time; a SETUP for a type-96 stream of ALAC follows, with a random key for the packets.
3. **Send.** One thread wakes every few milliseconds and sends every packet that falls due within
   `audioLatency` of the receiver's output, each 352 frames of what the app wrote, or silence when
   one falls due within a quarter of it with nothing written. A sync each second maps the RTP
   clock to NTP time, read from the same clock the timing replies use. Another thread sends the
   packets the receiver asks for again.
4. **Pause and flush** send FLUSH in the order the app called them, and the next timeline starts
   once the receiver has answered. A pause keeps what was sent and not heard, so play goes on
   from the frame that was playing.

The position is counted from what each packet carried and when the sync says it plays, so it
stands still through silence and pauses, and is what the receiver plays, not what was written.

The protocol facts, with the receivers they were seen on, are in
[compatibility.md](compatibility.md).

## Seams

- **`AudioStream` is the speaker seam.** A speaker plays what the sender decodes, which no
  `Media` URL can say, so it is a stream of PCM, not an `AirkastSession`, and the app's own player
  stays the decoder. `AudioRoute` is how a player's output moves to one: `AirkastPlayer` attaches
  the stream it opened, and `:exoplayer`'s sink is one route. Buffered audio (type 103), PTP
  timing and a group of speakers are further implementations behind the same interface.
- **`AirkastSession` is the protocol seam.** Another protocol is another implementation, picked
  in `Airkast.connect` from what the receiver advertises, such as AirPlay video v1's
  `POST /play`. A speaker is not one: its audio comes from the app's player, through an
  `AudioStream`. Nor is media the receiver cannot fetch, such as DASH or a file on the phone: the
  protocol is the same, only the item changes, so it is a step inside `load`
  ([design/sender-served-media.md](design/sender-served-media.md)), the resolver seam below. Callers
  don't change, which is why neither the session nor the item is named for video. The
  `Compatibility` case it clears becomes `Supported` in the same change. With a second
  implementation, the choice becomes a list of factories on `Airkast.Builder`, as Retrofit's
  converter factories and Coil's components are, so an app can add its own; until then it is a
  branch in `connect`.
- **`Airkast.Builder.resolvers` is the media seam.** `load` asks each `MediaResolver` in order
  to turn an item the receiver cannot fetch into one it can, and the first that handles it
  publishes what the receiver needs on the session's `Origin`, an HTTP server bound to the
  control connection's local address. The session keeps the `ResolvedMedia` with the loaded item
  and closes it once the receiver has moved on. `:core` serves `file:` URLs; a DASH translator in
  a `:dash` module, and remuxing in an Android one, are further resolvers an app adds. An item no
  resolver serves, in a format no receiver loads or at a URL that is not `http` or `https`, fails
  at once with `UnsupportedMedia`.
- **`Airkast.Builder.socketFactory` is the network seam.** `:core` opens every connection
  through the factory it returns for the receiver, so `:android` can refuse a connect without the
  local network permission and bind the session to a network without `:core` knowing Android.
- **`CredentialStore` is the storage seam.** `:core` keeps pairings in memory or in a file,
  which a `CredentialStore.Sealer` may encrypt. `:android` puts that file in the no-backup files
  and encrypts it with a key in the Android Keystore, and an app with another place for secrets
  implements its own store.
- **Pairing is a step before the session.** How a connection pairs (transient, pair-verify after
  a PIN or password) changes only the secret the session's keys derive from, so it is chosen in
  `DefaultVideoSession.open` and the session after it is the same. A Digest password only adds a
  header to the control connection's requests.
- **`Airkast.Builder.logger` and `eventListener` are the reporting seams**, as OkHttp's logger
  and `EventListener` are, or Coil's. The logger takes lines for debugging, each with a level and
  a tag, and builds none below its `minLevel`. The listener takes typed `Airkast.Event`s for an
  app's statistics, timed by the client, so an app parses no text and matches no start with an
  end. `AirkastPlayer.Builder` has its own `eventListener` for how casts start and end, since
  those are the player's, and logs through the client's logger unless given another.
  `ReceiverDiscovery` takes a logger, since it sees no client.

## Threading

- Every public suspend function is main-safe: it moves blocking I/O to `Dispatchers.IO` itself.
- Building does no I/O. `Airkast(context)` hands `CredentialStore.encrypted` a function that finds
  the no-backup files, which the store calls on its first use, on `Dispatchers.IO`, since Android
  creates that directory on the thread that asks for it. The store makes or loads its Keystore
  key there too. It does read the app's label and
  `ANDROID_ID` as it builds, from system services that hold them in memory.
- The control connection serves one exchange at a time, so a position poll and a command never
  interleave on the wire.
- One daemon thread per session reads the event channel. It never suspends: events go out through
  a `SharedFlow` with a buffer of 64, and the last playback state through a `StateFlow`, for
  collectors that start late.
- A session owns a `CoroutineScope` for its keepalive (`POST /feedback` every two seconds).
  `close()` cancels it.
- The logger and the event listeners run on the thread where the thing happened: the caller's,
  an I/O thread, the event channel's reader, or the player's looper. The library calls them
  synchronously and catches what they throw, so a slow or failing one never holds up or ends a
  session; their docs say not to block.

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
  was closed, and it follows play/pause, next and previous on the receiver's remote unless
  `followsRemote` is off: on the player the app sees, which is the local player during a cast to
  a speaker.
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
- **The session service drives the app's player.** `AirkastSessionService` builds the
  `MediaSession` over the player its subclass returns, and releases only the session: the player
  lives as long as the process, so a cast outlives the service and the UI drives it directly.
  Stop casting is a custom session command, offered to the controllers the session trusts, the
  notification's among them, and shown through the media button preferences while `connection`
  is not `Idle`. The app declares the service, not the library: one the library declared would
  be bound by controllers in every app that depends on it, beside the app's own.
- **It connects through `Airkast`, never `airkast-android`.** The app passes the client in, so
  `:media3` depends on `:core` alone.
- **Tracks are the master's renditions.** Once the receiver takes an HLS item, `ReceiverTracks`
  reads `session.renditions()` and lists each one as a media3 `Tracks.Group`, selected as the
  receiver last reported through `tracks()`, or as the player's `TrackSelectionParameters` asked
  until the receiver reports that. A selection is one `selectTrack` call, so media3's own track
  dialogs switch the TV.
- **Locks follow the state.** A partial wake lock and a Wi-Fi lock are held while a session is
  attached and the item is buffering or ready, paused included, since the keepalive has to go on,
  and for as long as a stream to a speaker is open.
- **A speaker keeps the local player.** A cast to a receiver that `streamsAudio` picks opens an
  `AudioStream` and attaches it to the `audioRoute`; `HandoffPlayer` keeps forwarding to the local
  player, seeks it to where it is so it sends its audio again into the new output, tells the
  receiver what plays, and reports a remote device whose volume is the receiver's. The end of the
  cast attaches null, and seeks again. The route belongs to the local player, so `build()` asks for
  one with the other.

## Public API rules

[CONTRIBUTING.md](../CONTRIBUTING.md#the-public-api) has them. In short: explicit API mode, `@Poko`
values instead of data classes, builders for options, one exception type, and a checked API dump
for every module.

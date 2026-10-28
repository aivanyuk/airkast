# Changelog

Every release, newest first. The format follows [Keep a Changelog](https://keepachangelog.com/),
and versions follow [docs/releasing.md](docs/releasing.md). A pull request that changes what a
caller sees adds its line under "Unreleased".

## Unreleased

### Added

- A file on the phone casts: `load` serves a `file:` URL to the receiver from the phone, over
  plain HTTP with byte ranges, through `LocalFileResolver`, and `AirkastPlayer` does so for a
  media item with a `file:` URI. `Airkast.Builder.resolvers` lists what `load` asks to serve an
  item the receiver cannot fetch, `LocalFileResolver` by default; a `MediaResolver` of an app's
  own publishes a `Resource` on the session's `Origin` and returns a `ResolvedMedia`, which the
  session closes once the receiver moves on.
- `load` throws `UnsupportedMedia` at once for an item at a URL that is not `http` or `https`,
  such as a `content:` URI, that no resolver serves, where it sent it and failed with `Timeout`.

- `MediaFormat.Dash`, which `MediaFormat.of` reads from a `.mpd` URL or the
  `application/dash+xml` MIME type. No receiver loads DASH, so `load` throws the new
  `AirkastException.UnsupportedMedia` at once and sends the receiver nothing, where it sent the
  item as HLS and failed with `Timeout` after `loadTimeout`. `AirkastPlayer` reports it as
  `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`.

### Changed

- `AirkastException.MasterUnreadable` is now `ManifestUnreadable`, the name a DASH manifest the
  phone cannot read will share.

## 0.6.0 - 2026-10-17

### Added

- `AirkastPlayer.Builder.disconnectOnInterrupt`, true by default: when the TV switches away from
  the item, to another input or app, the player ends a cast it opened as BACK does, with the new
  `EndReason.Interrupted`, where it went idle and kept the session.
- `DisconnectReason` says how a session or stream ended: `ClosedByReceiver` when the receiver
  hung up, as for another sender, `ConnectionFailed` when the connection was reset or the
  receiver went silent, `ClosedByApp`, or `Unknown`. `ReceiverEvent.Disconnected` and
  `AirkastException.Disconnected` carry it as `reason`.
- `CredentialStore.encrypted(context)` in `airkast-android` keeps pairings in a file with each
  one encrypted with AES-256-GCM under a key in the Android Keystore. A pairing it can no longer
  decrypt reads as none, so the receiver asks again, and one it cannot encrypt stays in memory for
  the process; neither fails a connect.
- `CredentialStore.file(sealer, logger) { file }` encrypts the file with a
  `CredentialStore.Sealer` of the app's own.
- `LocalNetworkPermission` in `airkast-android`: Android 17's local network permission as a
  `StateFlow` for a cast button, `NotNeeded`, `Granted` or `Denied`, with the `permission` to
  request and `refresh()` to read the grant again when the screen starts and after the request's
  result. The app asks, and decides how often.
- `Receiver.id`, the key the built-in credential stores keep a receiver under: its `deviceid`, or
  `host:port` without one. `ReceiverDiscovery.known` keeps every receiver an instance has found,
  by `id`, after the scan stops, and `ReceiverDiscovery.find(id)` returns one of them, so an app
  that keeps a receiver's `id` finds the receiver again after its picker stopped the scan.
- `AirkastSession.renditions()` lists every audio and subtitle rendition of the loaded HLS item,
  with the ids `selectTrack` takes, read from its master playlist, where `tracks()` lists only the
  selected ones. `HlsMaster.renditions(text)` numbers a master the app has read itself, and
  `AirkastException.MasterUnreadable` says the session could not read it.
- `AirkastPlayer` lists an HLS item's audio and subtitle renditions in `currentTracks`, with the
  ones the receiver reports selected, and selects them through `trackSelectionParameters`: an
  override of a group, preferred audio and text languages, and text disabled for subtitles off.
  Parameters set before the item is on the receiver apply once it is, and with a local player they
  move to the receiver and back with the item. A media3 track dialog, or an app's own one written
  against `Player`, switches the TV's tracks.
- `ReceiverEvent.RemoteCommand` with a `RemoteCommand` (`Play`, `Pause`, `PlayPause`, `Next`,
  `Previous`): the playback buttons a receiver's remote leaves to the sender, such as a
  speaker's, which came as `ReceiverEvent.Other`. `AirkastPlayer` follows them, as a media button,
  unless `AirkastPlayer.Builder.followsRemote` is off.
- `Airkast.describe(receiver)` asks a receiver typed in by hand what it is, with `GET /info`,
  and returns it with its feature bits, status flags, model and device ID, so its
  `compatibility` and `audioCompatibility` are known before connecting. `connect` and
  `connectAudio` do this themselves for a receiver without a TXT record, so one that asks for a
  PIN or password pairs on its first connect.

### Changed

- `AirkastPlayer` sets no player error when the receiver hangs up, as when another sender takes
  it, so a notification does not say playback failed; a failed connection still sets
  `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED`. The constructors of `ReceiverEvent.Disconnected` and
  `AirkastException.Disconnected` take the reason, and a session the receiver closes logs at
  `Info`, not `Error`.
- `Airkast(context)` keeps pairings with `CredentialStore.encrypted`, where it kept them in the
  clear, in the same no-backup file. A pairing made with 0.5.0 still reads, and the first read
  encrypts it.
- `AirkastPlayer.connect` asks again for a PIN or password the receiver refused, where it ended
  the cast with `SecretRejected`. The state stays `Connection.AwaitingSecret`, whose new
  `rejected` counts the refusals since the connect began, until the receiver takes one or the app
  calls `disconnect()`. `Event.CastAbandoned` carries `rejected` too.
- A PIN or password the receiver refuses is logged at `Debug`, without a stack trace, where it was
  an `Error` from both `Airkast.pair` and `Airkast.connect`.
- `AudioStream.setNowPlaying` sends only the parts the receiver's metadata feature bits say it
  shows: track info, artwork and progress. A receiver with no feature bits to read, such as one
  typed in by hand and not described, still gets all three.
- `Airkast.Builder.socketFactory` also runs before `describe`, so a connect to a receiver typed in
  by hand asks it twice.

## 0.5.0 - 2026-10-14

### Added

- `Media.format` says whether the URL is an HLS stream or a file, such as MP4 video or MP3 or AAC
  audio, and the receiver loads it that way. It is read from the URL's extension by default, and
  `MediaFormat.of(url, mimeType)` reads it from a MIME type too. `AirkastPlayer` reads it from each
  media item's MIME type, or from its URL.
- Speakers and soundbars: `Airkast.connectAudio(receiver, format)` opens an `AudioStream`, which
  plays PCM the app's own player decodes, as an `AudioTrack` does: `write` and `offer`, `play`,
  `pause`, `flush`, `position`, `setVolume`, and `setNowPlaying` for a receiver with a screen. It
  plays on any receiver that takes AirPlay 2 audio, TVs and Macs included, with 44.1 or 48 kHz
  PCM. Checked on a Samsung HW-Q990B soundbar. `Airkast.Builder.audioLatency`, one second by
  default, sets how far ahead of the receiver it sends.
- `Receiver.audioCompatibility` and `Receiver.isAudioSupported` say whether a receiver takes an
  audio stream, and `Compatibility.NoAudio` when it does not.
- `airkast-exoplayer`, a new artifact: `AirkastAudioSink`, an ExoPlayer `AudioSink` that plays on
  the phone until an `AudioStream` attaches, then sends ExoPlayer's audio there, resampled and
  mixed as the stream needs.
- `AirkastPlayer.Builder.audioRoute` and `streamsAudio`: with a route, such as `AirkastAudioSink`,
  `connect` to a speaker keeps the local player playing and moves its audio to the speaker. The
  new state `Connection.Streaming` says so, the device volume is the speaker's, so the phone's
  volume keys set it, and the speaker is told what plays. `AudioRoute` is the interface in
  `airkast-core` that a player of the app's own implements.
- `Airkast.Builder.approvalTimeout`, a minute by default: how long a connect waits for the
  receiver to take the session, while a Mac or an Apple TV asks its user to allow the sender.
- `SenderIdentity.deviceIdFrom(seed)` makes a sender ID that is the same for the same seed.

### Changed

- `Airkast(context)` keeps the sender's `deviceId` across launches, derived from the device's
  `ANDROID_ID` and the app's package, where it made a new one each time the app started. A Mac
  asks its user to allow a sender once, not after every launch.
- `AirkastPlayer.Connection` has a fifth state, `Streaming`, so a `when` over it needs a branch
  for it.
- The NTP timing replies read a monotonic clock, to the nanosecond, where they read the wall
  clock to the millisecond.
- `Media.streaming` is replaced by `Media.format`, and `AirkastPlayer.Builder.streaming` is
  removed. A URL ending in a file's extension, such as `.mp4` or `.mp3`, now loads as a file
  rather than as a stream; any other URL loads as HLS, as before.

### Fixed

- The first cast to a Mac after the app started failed with `Timeout` while the Mac asked its
  user to allow the sender: the base SETUP gave up after `requestTimeout`.

## 0.4.0 - 2026-10-12

### Changed

- airkast is on Maven Central from this release, as `io.github.aivanyuk:airkast-core`,
  `airkast-android` and `airkast-media3`, in place of JitPack's `com.github.aivanyuk.airkast`.
  Drop the JitPack repository and change the group. Versions up to 0.3.2 stay on JitPack only.

## 0.3.2 - 2026-10-11

### Added

- `CredentialStore.file { … }` takes a function that finds the file, which the store calls once,
  on `Dispatchers.IO`, the first time it is used.

### Fixed

- `Airkast(context)` does no I/O, so an app may build it on the main thread. It asked for the
  no-backup files directory as it was built, which Android creates on the calling thread, even
  when the block replaced the credential store. The store now finds the directory on its first
  use.

## 0.3.1 - 2026-10-10

The code of 0.3.0, built.

### Fixed

- JitPack builds the release. 0.3.0's build failed before compiling anything: on JitPack's new
  build host, the JDK 21.0.2 that `jitpack.yml` named cannot open `gradle-wrapper.jar`.
  `jitpack.yml` now names Temurin 21.0.10.

## 0.3.0 - 2026-10-10

Never built on JitPack, so it cannot be resolved: use 0.3.1, which holds the same code.

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

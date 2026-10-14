# Guide

This guide covers airkast in detail: the client's options, finding receivers, pairing, the media3
player, using a session directly, logs and statistics, and errors. Start with the
[README](../README.md) for installation and a quick start.

## Three levels of API

airkast has three levels. Each one is built on the public API of the level below, so you can use
the level that fits your app, and step down a level for one task without giving up the rest.

| Level | Type | Use it when |
| --- | --- | --- |
| Player | `AirkastPlayer` (`airkast-media3`) | Your Android app uses media3. The player runs the whole cast: it connects, pairs, plays, and ends the cast. |
| Client | `Airkast` (`airkast-core`) | You open sessions yourself, for example without media3 or on a desktop JVM. You build one client and share it. |
| Session | `AirkastSession` (`airkast-core`) | You send commands to one receiver: load, play, pause, seek, switch tracks, read events. |

The player opens its sessions through a client, and the client opens sessions. You can also open
a session yourself and give it to the player
(see [Give the player your own session](#give-the-player-your-own-session)).

## The client

`Airkast` holds the settings every connection uses: what the sender tells the receiver about
itself, how it reaches the receiver, the timeouts, and where pairings are kept. Build one for the
app and share it.

On Android, `Airkast(context)` from `airkast-android` builds a client with Android defaults:

- It names the sender after your app's label. The TV shows this name.
- It fails a connection at once with `AirkastException.NotPermitted` when the app lacks Android
  17's local network permission
  (see [Android 17's local network permission](#android-17s-local-network-permission)).
- It binds each connection to the Wi-Fi or Ethernet network whose subnet contains the receiver.
  Without that binding, a connection can go over mobile data when the Wi-Fi has no internet
  access, or into a VPN, and time out.
- It keeps pairings in a file in the app's no-backup files, so a backup never copies a private
  key to another device.

On a desktop JVM, `Airkast { ... }` from `airkast-core` builds a client with the same properties.
It keeps pairings in memory unless you set `credentialStore`.

Building a client does no disk or network I/O, so you can build it on the main thread, in
`Application.onCreate`.

### Options

Set options in the builder block:

```kotlin
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.SenderIdentity
import io.github.aivanyuk.airkast.android.Airkast
import io.github.aivanyuk.airkast.android.logcat
import kotlin.time.Duration.Companion.seconds

val airkast = Airkast(context) {
    identity = SenderIdentity(name = "Living room remote")
    requestTimeout = 3.seconds
    logger = Airkast.Logger.logcat()
    credentialStore = MyKeystoreStore(context)
}
```

| Property | Default | What it does |
| --- | --- | --- |
| `identity` | `SenderIdentity()`; the app's label with `Airkast(context)` | What the sender tells the receiver about itself. A TV shows its `name`. |
| `connectTimeout` | 5 seconds | How long opening a connection may take. |
| `requestTimeout` | 5 seconds | How long the receiver may take to answer a request. |
| `loadTimeout` | 10 seconds | How long a `load` waits for the receiver to take the item. |
| `keepAlive` | `true` | Sends a keepalive request every two seconds, as Apple's senders do. |
| `ntpTiming` | `true` | Answers the receiver's clock-sync (NTP) requests. The receiver must be able to reach the sender over UDP. A Mac rejects a session without it. |
| `socketFactory` | none; with `Airkast(context)`, the permission check and network binding above | Picks the socket factory for each receiver. Setting your own replaces both Android behaviours. |
| `credentialStore` | `CredentialStore.inMemory()`; a file in the no-backup files with `Airkast(context)` | Where pairings are kept (see [Pairing](#pairing)). |
| `logger` | none | Takes debugging lines (see [Logs and statistics](#logs-and-statistics)). |
| `eventListener` | none | Takes typed events for statistics (see [Logs and statistics](#logs-and-statistics)). |

`airkast.copy { ... }` makes a client with some options changed. The copy shares the original's
credential store:

```kotlin
val patient = airkast.copy { loadTimeout = 30.seconds }
```

## Finding receivers

On Android, `ReceiverDiscovery(context).receivers` is a `Flow` of every receiver on the local
network, sorted by name. Collecting the flow scans the network, and cancelling the collection
stops the scan. Collect it while a picker is on screen:

```kotlin
ReceiverDiscovery(context).receivers.collect { receivers ->
    showPicker(receivers.filter { it.isSupported })
}
```

The list includes receivers that airkast cannot play on. `Receiver.compatibility` says whether it
can, and if not, why:

| `Compatibility` | Meaning | `isSupported` |
| --- | --- | --- |
| `Supported` | Plays without a PIN or password. | yes |
| `NeedsPin` | Asks for the PIN shown on its screen the first time. | yes |
| `NeedsPassword` | Asks for the password set on it the first time. | yes |
| `Unknown` | Typed in by hand, so there is nothing to read. Connecting is the only test. | no |
| `AccessRestricted` | Lets in only its owner's Apple devices, such as a Mac at its default, "Current User". | no |
| `NoTransientPairing` | Does not offer the pairing airkast starts with. | no |
| `VideoV1Only` | Accepts URLs only over AirPlay video v1, which airkast does not speak. | no |
| `NoVideo` | Does not play video, such as a speaker. | no |

New cases may be added in a minor release, so a `when` over `Compatibility` keeps an `else`
branch. [compatibility.md](compatibility.md#how-receivercompatibility-decides) explains how each
case is decided.

To connect to a receiver that discovery cannot see, such as from an emulator or a desktop JVM,
build it from its address: `Receiver("TV", "192.168.1.20")`. Its compatibility is `Unknown`.

### Android 17's local network permission

On Android 17 (API 37), an app that targets SDK 37 cannot reach the local network until the user
grants `ACCESS_LOCAL_NETWORK`. Without it, connections time out with no error that names the cause,
so airkast checks first: discovery and connections through `Airkast(context)` fail at once with
`AirkastException.NotPermitted`.

1. If your app targets SDK 37 or later, declare the permission in your manifest. Apps that target
   a lower SDK leave it out.

   ```xml
   <uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />
   ```

2. Before you start discovery, check `LocalNetwork.isAccessible(context)`. If it returns `false`,
   request `LocalNetwork.PERMISSION` with the activity result API.

The permission is in the nearby devices group, so a user who has granted Bluetooth's nearby
devices permission is not asked again.

`airkast-android` declares `INTERNET` and `ACCESS_NETWORK_STATE` itself.

## Pairing

Every connection starts with a pairing, which sets up the keys that encrypt it. airkast handles
three kinds:

- **Transient pairing** needs no user input, and lasts for one connection. Receivers that ask
  for no PIN or password pair this way on every connection.
- **PIN pairing**: the receiver shows a PIN on its screen, and the user types it into your app.
- **Password pairing**: the user types the password set in the receiver's AirPlay settings.

A PIN or password pairing happens once. It leaves `Credentials`, which the client keeps in its
`credentialStore`. Every later connection proves the pairing with those credentials, without
asking the user.

### Ask the user

`Airkast.connect` takes a function that asks the user for the PIN or password. It is called only
when the receiver needs one and no credentials are stored, or when the receiver asks for its
password as the session starts and none is kept:

```kotlin
val session = airkast.connect(receiver) { secret ->
    // secret is Secret.Pin or Secret.Password. Return what the user typed.
    showPromptAndWait(secret)
}
```

The function runs in the caller's coroutine, so it can show a dialog and suspend until the user
answers. Cancelling it gives up the connection. `AirkastPlayer` does the same through its
`AwaitingSecret` state and `enterSecret`.

What can go wrong:

- A wrong PIN or password throws `AirkastException.SecretRejected`. Connect again to start over.
- A receiver that needs a PIN or password, connected to without a prompt function, fails with
  `AirkastException.PairingFailed`.
- A receiver that has forgotten the sender refuses its credentials. The connection fails with
  `PairingFailed`, the client removes the credentials, and the next connection pairs again.

Some receivers ask for a PIN or password without saying so in their network announcement. Pair
with them explicitly, then connect. With the player, `player.connect(receiver, pairWith =
Secret.Password)` does the same:

```kotlin
airkast.pair(receiver, Secret.Password) { showPromptAndWait(Secret.Password) }
val session = airkast.connect(receiver)
```

A Mac set to "Require password" asks for the password again each time a session starts. airkast
keeps the password with the credentials and answers for the user, so the user types it once.

### Store pairings yourself

`CredentialStore` is an interface with `get`, `put` and `remove`. The library provides two stores:

- `CredentialStore.inMemory()` keeps pairings while the store exists. The receiver asks again in
  the next process.
- `CredentialStore.file(file)` keeps them in a file that only its owner can read.
  `Airkast(context)` uses this, in the no-backup files.

A credential includes a private key, and the password for a receiver that uses one. An app with a
secure store, such as the Android Keystore, can implement `CredentialStore` over it.
`Credentials.encoded` and `Credentials.decode` convert credentials to and from a string.

## The media3 player

`AirkastPlayer` is a media3 `Player` that plays on a receiver. A media3 UI or a `MediaSession` over
it drives the TV the same way it drives a local player. Call it on its application looper, as
with any `Player`.

### Connection states

`player.connection` is a `StateFlow` of four states:

| State | Meaning |
| --- | --- |
| `Idle` | No session. `failure` says why the last cast ended, unless it ended through `disconnect()`. |
| `Connecting` | Connecting to `receiver`. |
| `AwaitingSecret` | `receiver` asks for a `secret`, the PIN on its screen or its password. Pass what the user typed to `enterSecret`, or call `disconnect()`. |
| `Connected` | Playing on `session`. |

### What the player does for you

- **Loads the item.** A media item set while a session is attached loads at once. One set before
  waits for a session. The receiver fetches the item's URL itself. The item's MIME type, set with
  `MediaItem.Builder.setMimeType`, says whether it is an HLS stream or a file, and without one the
  URL's extension does (see [Formats](#formats)).
- **Ends the cast on BACK.** By default, BACK on the TV's remote ends a cast the player opened, as
  `disconnect()` does. With `disconnectOnBack = false`, the app hears BACK as
  `ReceiverEvent.Back` on the session's events, and decides what to do.
- **Reconnects on `prepare()`.** After a failure, or a session that ended by itself, `prepare()`
  connects to the same receiver again. A notification's play button calls `prepare()`.
- **Keeps the phone awake.** The player holds a partial wake lock and a Wi-Fi lock while an item
  loads, plays or is paused on the receiver. Without them, the phone sleeps with its screen off,
  the session's keepalive stops, and the receiver may drop the sender. `airkast-media3` declares
  `WAKE_LOCK` for this.

Things a `Player` has no method for, such as switching tracks, are on the session:
`player.session?.selectTrack(...)`.

### Options

```kotlin
val player = AirkastPlayer(context, airkast) {
    localPlayer = ExoPlayer.Builder(context).build()
    disconnectOnBack = false
}
```

| Property | Default | What it does |
| --- | --- | --- |
| `localPlayer` | none | A player that plays while no cast does, such as your `ExoPlayer`. It must run on the same looper as the `AirkastPlayer`, or `build()` throws. See [Play on the phone too](#play-on-the-phone-too). |
| `disconnectOnBack` | `true` | Whether BACK on the TV's remote ends a cast the player opened. |
| `keepAwake` | `true` | Whether to hold the wake lock and Wi-Fi lock described above. |
| `positionPollInterval` | 1 second | How often the player asks the receiver for its position. |
| `looper` | the current thread's looper, or the main looper | The player's application looper. |
| `logger` | the client's `logger` | Takes the player's lines, under the tag `player`. |
| `eventListener` | none | Hears how casts start and end. See [Logs and statistics](#logs-and-statistics). |

### Play on the phone too

With `localPlayer`, the `AirkastPlayer` forwards to your player until a cast starts. It then stops
your player and plays its current item on the receiver, from the position it reached.

When the cast ends, through `disconnect()`, BACK on the TV, a failure, or `session` set to `null`,
the item returns to your player, paused at the position the receiver reached. It comes back paused
because nobody may be at the phone when a cast ends, for example after a dropped network.

One `MediaSession` over the `AirkastPlayer` serves both. Commands, the timeline and the device info
follow whichever player plays. The video surface always goes to the local player. Releasing the
`AirkastPlayer` releases the local player.

### Keep casting in the background

`AirkastSessionService` is a media3 `MediaSessionService` with a `MediaSession` over your player.
media3 runs it in the foreground while the player plays. A foreground service of type
`mediaPlayback` keeps network access when the device is idle, so the cast continues with the app
in the background.

Subclass it and return your player:

```kotlin
class PlaybackService : AirkastSessionService() {
    override val player get() = (application as App).player
}
```

Declare the service, with its permissions, in your app's manifest:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />

<service
    android:name=".PlaybackService"
    android:exported="true"
    android:foregroundServiceType="mediaPlayback">
    <intent-filter>
        <action android:name="androidx.media3.session.MediaSessionService" />
    </intent-filter>
</service>
```

The library does not declare the service itself, because a declared service would be bound in
every app that depends on the library.

The service releases only the `MediaSession`, never the player, so the cast outlives the service.
Keep the player in an object that lives as long as the process, such as your `Application`.

While a cast is on, the notification has a Stop casting button. To change what tapping the
notification opens (by default, the app's launcher activity), override `sessionActivity()`. To set
anything else on the `MediaSession`, override `buildSession(builder)`.

### Give the player your own session

An app that opens its own sessions sets `player.session` instead of calling `connect`. The player
loads the current item on that session, at the last position. The app keeps ownership: the player
never closes a session it did not open, and leaves BACK to the app. When the session ends, the
player sets `session` back to `null`.

## Using a session directly

Without media3, or on a desktop JVM, open a session with `Airkast.connect` and send it commands:

```kotlin
val session = airkast.connect(receiver) { secret -> showPromptAndWait(secret) }

session.load(Media("https://example.com/master.m3u8", startAt = 10.minutes))

scope.launch {
    session.events.collect { event ->
        when (event) {
            is ReceiverEvent.Back -> session.stop()
            is ReceiverEvent.StateChanged -> show(event.state)
            else -> Unit  // new events may be added in a minor release
        }
    }
}

val info = session.playbackInfo()  // position, duration, buffered ranges
session.seek(20.minutes)
session.close()
```

| Call | What it does |
| --- | --- |
| `load(media)` | Plays `media`, replacing what plays. Returns once the receiver has taken the item, or throws `Timeout` after `loadTimeout`. |
| `play()`, `pause()` | Resumes or pauses. |
| `seek(position)` | Seeks, and returns the position the receiver reports. |
| `playbackInfo()` | Returns the state, position, duration, rate, and buffered and seekable ranges. |
| `tracks()` | Returns the selected audio and subtitle renditions. |
| `selectTrack(kind, id)` | Selects a rendition. Ids follow the order of `EXT-X-MEDIA` tags in the HLS master playlist. A `null` id turns subtitles off, except forced ones. |
| `volume()` | Returns the TV's volume from 0 to 1, or `null`. |
| `stop()` | Ends playback. The receiver leaves its player, and the session stays open for another `load`. |
| `close()` | Closes the connections. |

`Media` takes the URL, a start position (`startAt`) and a `format`, read from the URL by default
(see [Formats](#formats)). A file served from a URL without its extension needs it given:

```kotlin
session.load(Media("https://example.com/watch/7", format = MediaFormat.File))
// or from the Content-Type the server sends
session.load(Media(url, format = MediaFormat.of(url, mimeType = "video/mp4")))
```

`session.events` emits what the receiver reports: state changes, the end of an item, a new item,
rate changes, time jumps, volume changes, and BACK on the TV's remote. The flow ends with
`ReceiverEvent.Disconnected`, and then completes. `session.state` is a `StateFlow` with the latest
playback state.

Pressing BACK on the TV's remote sends `ReceiverEvent.Back`. The TV stays in its player until the
sender calls `stop()`.

All times are `kotlin.time.Duration` values. A volume runs from 0 to 1.

A session does not reconnect by itself, because only the app knows whether the user still wants
the cast. Cancelling a `connect` closes whatever it opened.

## Logs and statistics

The client has two hooks: `logger` for debugging, and `eventListener` for your app's statistics.

```kotlin
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.android.Airkast
import io.github.aivanyuk.airkast.android.logcat
import io.github.aivanyuk.airkast.media3.AirkastPlayer

val airkast = Airkast(context) {
    logger = Airkast.Logger.logcat(minLevel = Airkast.Logger.Level.Debug)
    eventListener = { event ->
        when (event) {
            is Airkast.Event.Connected -> stats.record("connect", event.took, event.receiver.model)
            is Airkast.Event.ConnectFailed -> stats.record("connect_failed", event.failure::class.simpleName)
            else -> Unit  // new events may be added in a minor release
        }
    }
}
val player = AirkastPlayer(context, airkast) {
    eventListener = { event -> stats.record(event) }
}
```

**`logger`** takes lines with a level (`Verbose`, `Debug`, `Info`, `Warn`, `Error`) and a tag, such
as `connect`, `pairing`, `session`, `events`, `player` or `discovery`. `Debug` logs one line per
protocol step, and `Verbose` logs every message on the wire. Lines below the logger's `minLevel`
(`Debug` by default) are never built. Use `Airkast.Logger.logcat()` on Android, which writes under
the tag `airkast`, or `Airkast.Logger.println()` on a desktop JVM. A lambda works too:
`Airkast.Logger { level, tag, message, error -> ... }`.

**`eventListener`** on the client takes `Airkast.Event`s: `Connected` (with how it paired),
`ConnectFailed`, `Paired`, `PairingFailed`, `CredentialsDropped`, `Loaded`, `LoadFailed` and
`SessionEnded`. Most carry how long the step took, timed by the client. The player's
`eventListener` takes `AirkastPlayer.Event`s: `CastStarted`, `CastFailed`, `CastAbandoned`, and
`CastEnded`, which carries the reason and how long the cast lasted.

Both hooks are called synchronously, on the thread where the event happened. Do slow work, such
as a network call, on a thread of your own. A hook that throws loses that line or event, and never
affects the session.

Privacy: a log line never contains a media URL, a key, a PIN, a password, or anything derived from
a pairing. An event names its receiver. The receiver's `model`, `sourceVersion` and
`compatibility` describe the device, while its `name` and `host` are the user's own.

The player logs through the client's `logger` unless its builder sets another.
`ReceiverDiscovery(context, logger)` takes a logger too.

## Errors

Every failure airkast reports is an `AirkastException`, or a `CancellationException` when the
caller cancels. New subclasses may be added in a minor release, so a `when` over them keeps an
`else` branch.

Misuse is reported the Kotlin way instead: `AirkastPlayer.Builder.build()` throws
`IllegalArgumentException` for an invalid option, such as a local player on another looper.
`connect`, `disconnect`, `enterSecret` and `session` throw `IllegalStateException` when called off
the player's looper.

| Exception | When |
| --- | --- |
| `Unreachable` | No connection could be opened: the receiver is off or asleep, on another network, or a firewall or VPN is in the way. |
| `NotPermitted` | The app may not reach the local network. On Android 17, grant `ACCESS_LOCAL_NETWORK` first. |
| `PairingFailed` | The receiver refused the pairing, or needs a PIN or password and no prompt function was given. |
| `SecretRejected` | The user typed a wrong PIN or password. |
| `Rejected` | The receiver answered a request with an error status. |
| `Timeout` | The receiver did not answer in time. The session stays open, and ends only if the receiver is still silent at the next request. |
| `Disconnected` | The connection is gone: it closed, another sender took over the receiver, or the network failed. |
| `UnexpectedReply` | The receiver answered with something airkast cannot read. |
| `DiscoveryFailed` | Scanning for receivers could not start. `code` is the platform's error. |

## Threading

- Every `suspend` function is safe to call from the main thread. It moves blocking I/O to
  `Dispatchers.IO` itself.
- Building a client, with `Airkast(context)` or `Airkast { ... }`, does no disk or network I/O.
- `AirkastPlayer`, like every media3 `Player`, is called on its application looper.
- Loggers and event listeners run on the thread where the event happened, and must not block.

## Stream requirements

The receiver fetches and plays the URL itself, so:

- The receiver must reach the URL. A server on the phone's `localhost` does not work.
- `Media` holds only the URL, so the receiver cannot send your app's cookies or request headers.
- The receiver takes HLS streams and whole files, but not DASH, and files have been checked on
  the LG CX only (see [Formats](#formats)).
- The LG CX plays HLS through a web page, so the server must send CORS headers for every
  playlist and segment. It plays files from a server that sends none.
- With `ntpTiming` on, the default, the receiver sends clock-sync requests to the sender over
  UDP. A sender behind a firewall that drops them cannot start a session with a Mac.

[compatibility.md](compatibility.md) has the details for each receiver, Android version and
network setup.

### Formats

The receiver loads an HLS stream and a file in different ways, so `Media.format` says which one
the URL is. `MediaFormat.of(url, mimeType)` reads it from a MIME type when one is given, such as a
media3 item's or a server's `Content-Type`, then from the extension of the URL's path, and picks
`Hls` when neither says. `AirkastPlayer` reads it from each media item that way.

| What you play | `MediaFormat` | Status |
| --- | --- | --- |
| An HLS playlist (`.m3u8`) | `Hls` | Checked on the LG CX and a Mac. The receiver reports buffered ranges, and reports and switches tracks. |
| An MP4 or MOV video file, or an MP3, AAC or M4A audio file | `File` | Loaded as a file, as other AirPlay senders load them. Checked on the LG CX, not on a Mac yet. The receiver reports no tracks, and the LG no buffered or seekable ranges. |
| MPEG-DASH (`.mpd`) | none | Not played. The receivers' players take HLS and files, not DASH. |

An audio file goes to a TV or a Mac the same way as a video file. A speaker, such as a HomePod,
is `Compatibility.NoVideo` and not supported yet: it takes audio only as a stream the sender
decodes and sends itself.

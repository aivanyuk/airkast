# airkast

Play a video URL on a TV from Kotlin: pair, load, seek, pause, switch audio and subtitle tracks,
and follow what the TV reports. It talks to receivers that implement AirPlay video, such as
smart TVs with an AirPlay 2 receiver built in.

- **`airkast-core`**: plain Kotlin on the JVM, with no Android dependency. It runs on a desktop
  JVM against a real TV, which is how it is tested.
- **`airkast-android`**: receiver discovery through `NsdManager`, Android 17's local network
  permission, and connections bound to the network the receiver is on.
- **`airkast-media3`**: `AirkastPlayer`, a media3 `Player` that runs a whole cast, so media3's UI
  and a `MediaSession` drive the TV. Given the app's own player, it plays on the phone too, and
  moves the item to the TV and back. It keeps the phone awake while a cast plays, and
  `AirkastSessionService` keeps it going with the app in the background.

## Status

Early, and versioned 0.x: a minor release may change the API ([releasing](docs/releasing.md)).
It is checked against an LG CX (webOS, receiver 377.25.06) and a Mac's AirPlay Receiver
(960.13.25, set to "Anyone on the same network"), and reverse-engineered from public notes, with
no specification behind it. What works there:

- transient pairing, with no PIN on the screen;
- pairing once with the PIN the LG shows when set to ask for one, then pair-verify;
- pairing once with the password a Mac asks for when set to require one, which it also asks for,
  with HTTP Digest, on every connect after;
- loading an HLS URL at a start position, then play, pause, seek, stop and the next item;
- position, duration and buffered ranges;
- reading and switching audio and subtitle tracks;
- receiver events: state, end of item, the TV remote's pause, seek and BACK, and its volume.

It does not yet support receivers that let in only their owner's devices (a Mac at its default),
receivers that take URLs only over AirPlay video v1, Apple TV's remote channel, or setting the
volume (the LG reports it but ignores a change). [docs/compatibility.md](docs/compatibility.md)
lists what has been checked, on which receivers and Android versions.

## Use

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io") { content { includeGroup("com.github.aivanyuk.airkast") } }
    }
}

// build.gradle.kts
implementation("com.github.aivanyuk.airkast:airkast-core:<version>")
implementation("com.github.aivanyuk.airkast:airkast-android:<version>")
implementation("com.github.aivanyuk.airkast:airkast-media3:<version>") // for a media3 Player
```

airkast comes in three levels. Each one hides the level below it and leaves it in reach.

### A player that runs the cast

`AirkastPlayer` connects, pairs, plays and ends a cast, and a `MediaSession` over it gives the
notification and the lock screen:

```kotlin
val airkast = Airkast(context)  // one for the app
val player = AirkastPlayer(context, airkast)

player.setMediaItem(MediaItem.fromUri("https://example.com/master.m3u8"), 600_000)
player.playWhenReady = true
player.connect(receiver)  // pairs, asks for a PIN or password when the receiver needs one, plays

player.connection.collect { connection ->  // for the UI
    when (connection) {
        is Connection.Idle -> show(connection.failure)  // null after disconnect()
        is Connection.Connecting -> showProgress()
        is Connection.AwaitingSecret -> askFor(connection.secret) { player.enterSecret(it) }
        is Connection.Connected -> showTracks(connection.session.tracks())
    }
}

player.disconnect()  // the TV leaves its player, the session closes, the item clears
```

The player closes the sessions it opens, and ends the cast when BACK is pressed on the TV's
remote (`AirkastPlayer(context, airkast) { disconnectOnBack = false }` leaves BACK to the app). After a
dropped connection, `prepare()`, which a notification's play button calls, connects again and
picks up where the cast left off. An app that opens its own sessions sets `player.session`
instead, and keeps them: the player then only plays.

An app that plays on the phone too hands the player its own, as media3's `CastPlayer` takes one:

```kotlin
val player = AirkastPlayer(context, airkast) { localPlayer = ExoPlayer.Builder(context).build() }
```

The ExoPlayer plays until a cast starts. Then the item moves to the TV from the position it
reached, and when the cast ends it comes back, paused where the TV left it. Commands, the
timeline and the device info follow whichever plays, so one `MediaSession` serves both.

`AirkastSessionService` is that `MediaSession`, in a `MediaSessionService`: media3 runs it in the
foreground while the player plays, which keeps a cast going with the app in the background, and
its notification has a Stop casting button while a cast is on. The app subclasses it with its
player, which outlives the service:

```kotlin
class PlaybackService : AirkastSessionService() {
    override val player get() = (application as App).player
}
```

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

Overriding `sessionActivity()` changes what tapping the notification opens, the app's launcher
activity by default, and `buildSession(builder)` sets anything else on the session.

### A client, configured once

`Airkast(context)` names the sender after the app's label, checks Android 17's local network
permission, binds the connections to the Wi-Fi the receiver is on, and keeps pairings in the
app's no-backup files. Each of those is a builder property, as is everything else:

```kotlin
val airkast = Airkast(context) {
    identity = SenderIdentity(name = "Living room remote")  // what the TV shows
    requestTimeout = 3.seconds
    logger = Airkast.Logger.logcat()  // one line per protocol step, never a URL or a key
    credentialStore = MyKeystoreStore(context)  // a CredentialStore over the app's own secrets
}
val patient = airkast.copy { loadTimeout = 30.seconds }  // shares the credential store
```

On a desktop JVM, `Airkast { ... }` takes the same properties, keeps pairings in memory unless
given `CredentialStore.file(file)`, and connects to a receiver typed in by hand:
`Receiver("TV", "192.168.1.20")`.

### A session

```kotlin
// An app that targets SDK 37 declares ACCESS_LOCAL_NETWORK and asks for it before this.
val receiver = ReceiverDiscovery(context).receivers
    .mapNotNull { list -> list.firstOrNull { it.isSupported } }
    .first()
val session = airkast.connect(receiver) { secret -> askTheUserFor(secret) }  // once, if at all
session.load(Media("https://example.com/master.m3u8", startAt = 10.minutes))
scope.launch {
    session.events.collect { event ->  // completes when the session ends
        when (event) {
            is ReceiverEvent.Back -> session.stop()  // the TV leaves its player once the sender stops
            is ReceiverEvent.StateChanged -> show(event.state)
            else -> Unit  // new events may join in a minor release
        }
    }
}
val info = session.playbackInfo()  // position, duration, buffered ranges
session.seek(20.minutes)
session.close()
```

Every time is a `kotlin.time.Duration`, and a volume runs from 0 to 1. Cancelling a connect
closes whatever it opened.

A receiver that asks for a PIN or a password (`NeedsPin` or `NeedsPassword` in
[compatibility](docs/compatibility.md)) pairs on its first connect. The prompt gets the `Secret`
it asks for: a `Pin` the receiver shows on its screen while the prompt waits for the user, or the
`Password` set in its AirPlay settings. The credentials go to the client's `credentialStore`, so
every later connect gets in without asking. A wrong one throws `AirkastException.SecretRejected`. A receiver that has forgotten the
sender refuses its credentials with `PairingFailed`, which drops them, and the next connect pairs
again. A receiver that asks without saying so in its TXT record pairs with
`airkast.pair(receiver, Secret.Pin) { … }`, or `Secret.Password`. A Mac with a password asks for
it again with an HTTP Digest challenge whenever a session starts; the credentials keep the
password for that, so the user types it once.

The receiver fetches the stream itself. A receiver whose player is a web page (the LG's is)
needs CORS headers on every playlist and segment.

BACK on the TV's remote arrives as `ReceiverEvent.Back`, and the TV leaves its player only when
the sender calls `stop()`.

### Logs and statistics

Two hooks, one for debugging and one for an app's statistics. Both are called on the thread
where the thing happened, so they hand slow work, such as a network call, to a thread of their
own. One that throws loses its line or event, never the session.

```kotlin
val airkast = Airkast(context) {
    // Lines with a level and a tag (connect, pairing, session, control, events, timing, player,
    // discovery). Airkast.Logger.println() writes them to standard output on a desktop JVM.
    logger = Airkast.Logger.logcat(minLevel = Airkast.Logger.Level.Debug)
    // What the client did, timed: Connected, ConnectFailed, Paired, Loaded, SessionEnded, …
    eventListener = { event ->
        when (event) {
            is Airkast.Event.Connected -> stats.record("connect", event.took, event.receiver.model)
            is Airkast.Event.ConnectFailed -> stats.record("connect_failed", event.failure::class.simpleName)
            else -> Unit  // new events may join in a minor release
        }
    }
}
val player = AirkastPlayer(context, airkast) {
    // How casts start and end: CastStarted, CastFailed, CastAbandoned, CastEnded with its reason.
    eventListener = { event -> stats.record(event) }
}
```

The player logs through the client's `logger` unless its builder sets another, and
`ReceiverDiscovery(context, logger)` takes one too. A line never holds a media URL, a key, a PIN
or anything a pairing derives. An event names its receiver, whose `model`, `sourceVersion` and
`compatibility` describe the device, while its `name` and `host` are the user's own.

## Sample apps

[`sample/`](sample) holds the reference integration and two small apps over it, one with its UI
in Compose and one in views, so a reader in either toolkit sees the same calls in their own idiom:

- [`sample/cast`](sample/cast) is the integration, which both apps drive.
  [`Cast.kt`](sample/cast/src/main/kotlin/io/github/aivanyuk/airkast/sample/Cast.kt) builds the
  app's `Airkast` and the `AirkastPlayer` that plays on the phone and runs the cast, and keeps a
  log of what the TV says and how casts start and end;
  [`CastService.kt`](sample/cast/src/main/kotlin/io/github/aivanyuk/airkast/sample/CastService.kt)
  is the `AirkastSessionService` over it, for the notification and the lock screen.
- [`sample/compose`](sample/compose) is one screen in Compose over `Cast`: its flows, the
  player's `connection` among them, are
  collected with `collectAsStateWithLifecycle`, and media3's `PlayerView` sits in an `AndroidView`.
- [`sample/views`](sample/views) is the same screen as an activity with layouts: the flows are
  collected under `repeatOnLifecycle`, and `PlayerView` is in the layout.

Either app finds TVs or takes an address, asks for Android 17's local network permission, casts a
URL, switches tracks, and lists what the TV reports. The library needs neither toolkit: every
call suspends, and what changes is a `Flow`. They build against the modules in this repository:

```bash
./gradlew :sample:compose:installDebug
./gradlew :sample:views:installDebug
```

## Tests

```bash
./gradlew check
AIRKAST_RECEIVER=192.168.1.20 ./gradlew :core:test --tests '*LiveReceiverTest'
```

`check` runs the JVM tests, the Android tests under Robolectric at several API levels, lint,
ktlint, the public API check and Animal Sniffer. The crypto tests check SRP-6a and HKDF against
srptools and pyatv, ChaCha20-Poly1305 against RFC 8439, X25519 against RFC 7748 and Ed25519
against RFC 8032, and the curves against the JDK's own providers. ChaCha20-Poly1305 is
implemented here because the JCA has no provider for it below Android 9, and X25519 and Ed25519
because it has none below Android 13. The live test plays on a real receiver.

[CONTRIBUTING.md](CONTRIBUTING.md) has the rules a change follows, and
[docs/architecture.md](docs/architecture.md) the shape of the code.

## Credits

The protocol facts come from [pyatv](https://github.com/postlund/pyatv) (MIT) and
[send-airplay2](https://github.com/ilyalissoboi/send-airplay2) (Apache-2.0), and from watching a
real receiver. No code is copied from either. The Curve25519 field arithmetic follows
[TweetNaCl](https://tweetnacl.cr.yp.to/) (public domain).

## Licence

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

AirPlay is a trademark of Apple Inc. This project is not affiliated with, endorsed by or
sponsored by Apple.

# airkast

airkast is a Kotlin library that plays a video URL on an AirPlay receiver, such as a smart TV with
AirPlay 2 built in, or plays your app's audio on an AirPlay speaker, and controls playback from
your app. It runs on Android and on a desktop JVM.

To a TV, your app sends a URL. The receiver downloads and plays the stream itself, and airkast
sends it commands (play, pause, seek, switch audio or subtitle tracks) and reports what it does.
To a speaker or a soundbar, your app's own player decodes the audio, and airkast streams it there.
On Android, `AirkastPlayer` is a media3 `Player`, so the player UI, notification and lock-screen
controls your app already has can drive the TV or the speaker.

A few terms used throughout:

- **Receiver**: the device that plays, such as a TV, a Mac or a speaker. Represented by `Receiver`.
- **Sender**: your app, which tells the receiver what to play.
- **Pairing**: the handshake that sets up an encrypted connection. Some receivers, such as the
  LG CX at its default settings, pair without user input. Others ask for a PIN shown on the TV,
  or a password, the first time.
- **Session**: one open connection to one receiver, represented by `AirkastSession`, or by
  `AudioStream` for audio your app streams.

## When to use it

Use airkast to add "play on TV" for AirPlay receivers to an Android or JVM app that plays video
from a URL, or "play on speaker" to a music app, whatever its player. Apple's AirPlay frameworks
exist only on Apple platforms.

Before you adopt it, check these limitations:

- **The API is not stable yet.** airkast is versioned 0.x, and a minor release may change the
  API. [docs/releasing.md](docs/releasing.md) has the rules.
- **Only three receivers have been checked**: an LG OLED CX TV (webOS), a Mac's AirPlay Receiver
  set to "Anyone on the same network", and a Samsung HW-Q990B soundbar, for audio. Other
  receivers may work, but nobody has tested them.
  [docs/compatibility.md](docs/compatibility.md) lists every check.
- **A TV must reach the URL itself.** airkast does not mirror the screen, and streams only audio
  itself. It loads HLS streams and MP4, MP3 and AAC files, files so far checked on the LG only,
  and MPEG-DASH does not play ([formats](docs/guide.md#formats)). The LG plays HLS in a web page,
  so the server must send CORS headers for every playlist and segment. Files play without them.
- **Some receivers are not supported yet:**
  - receivers that let in only their owner's Apple devices, which includes a Mac at its default
    setting, "Current User";
  - receivers that accept URLs only over the older AirPlay video v1 protocol;
  - receivers that don't offer transient pairing, the pairing airkast starts with, which
    includes AirPlay 1 speakers;
  - more than one speaker at a time (multi-room).
- **A TV's volume cannot be set.** airkast reads the TV's volume, but the LG ignores every way of
  changing it. A speaker's can be set.
- **Audio reaches a speaker a second late.** The stream buffers one second ahead of the speaker
  (`audioLatency`), so play, pause and seek take effect that much later there.
- **Apple TV has not been checked.**

## Install

airkast is on Maven Central:

```kotlin
dependencies {
    implementation("io.github.aivanyuk:airkast-android:0.5.0")
    implementation("io.github.aivanyuk:airkast-media3:0.5.0")
    // For speakers, with ExoPlayer:
    implementation("io.github.aivanyuk:airkast-exoplayer:0.5.0")
}
```

| Artifact | What it contains | Add it when |
| --- | --- | --- |
| `airkast-core` | Connecting, pairing and the session's commands and events. Plain Kotlin, no Android. | You target a desktop JVM. The other artifacts include it. |
| `airkast-android` | Receiver discovery, Android 17's local network permission, binding connections to the receiver's Wi-Fi, and `Airkast(context)`, a client with Android defaults. | You target Android. |
| `airkast-media3` | `AirkastPlayer`, a media3 `Player` that runs a cast, and `AirkastSessionService`. | Your Android app uses media3. |
| `airkast-exoplayer` | `AirkastAudioSink`, an ExoPlayer `AudioSink` that sends ExoPlayer's audio to a speaker. | Your app plays on speakers through ExoPlayer. An app with another player writes to an `AudioStream` itself. |

Requirements:

- Android 6.0 (API 23) or later, or Java 11 or later on a desktop JVM.
- Kotlin 2.2 or later. The API is built on coroutines (`suspend` functions and `Flow`), so it is
  meant for Kotlin callers. Java is not a target.
- media3 1.11.1 or later, for `airkast-media3` and `airkast-exoplayer`.

Versions up to 0.3.2 were published on JitPack only, as `com.github.aivanyuk.airkast:airkast-core`
and so on. 0.1.0 and 0.3.0 never built there.

## Quick start

This plays a stream on the first receiver found on the network, from an Android app that uses
media3:

```kotlin
import androidx.media3.common.MediaItem
import io.github.aivanyuk.airkast.android.Airkast
import io.github.aivanyuk.airkast.android.ReceiverDiscovery
import io.github.aivanyuk.airkast.media3.AirkastPlayer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull

// Build one client and one player for the whole app, for example in your Application class.
val airkast = Airkast(context)
val player = AirkastPlayer(context, airkast)

// Inside a coroutine: wait for the first receiver that airkast can play on.
val receiver = ReceiverDiscovery(context).receivers
    .mapNotNull { receivers -> receivers.firstOrNull { it.isSupported } }
    .first()

player.setMediaItem(MediaItem.fromUri("https://example.com/master.m3u8"))
player.playWhenReady = true
player.connect(receiver)
```

`connect` returns at once. The player then connects, pairs, and loads the item on the receiver.
Follow its progress in `player.connection`, as shown in the next section.

If your app targets SDK 37 (Android 17), the system blocks the local network until the user grants
`ACCESS_LOCAL_NETWORK`. Declare that permission in your manifest and request it before you start
discovery. `LocalNetworkPermission(context).state` tells you whether you need to ask
([guide](docs/guide.md#android-17s-local-network-permission)). Without the permission, discovery
and connecting fail with `AirkastException.NotPermitted`. Apps that target a lower SDK leave the
permission out.

## Common tasks

These examples use `AirkastPlayer`. [docs/guide.md](docs/guide.md) covers every option, and how to
use a session directly without media3.

### Show the connection state and ask for a PIN or password

`player.connection` is a `StateFlow` that your UI collects. When a receiver asks for a PIN or a
password, the state is `AwaitingSecret`. Show a prompt, and pass what the user typed to
`enterSecret`:

```kotlin
import io.github.aivanyuk.airkast.media3.AirkastPlayer.Connection

player.connection.collect { connection ->
    when (connection) {
        is Connection.Idle -> showIdle(connection.failure)  // failure is null after disconnect()
        is Connection.Connecting -> showProgress()
        is Connection.AwaitingSecret -> askFor(connection.secret) { typed -> player.enterSecret(typed) }
        is Connection.Connected -> showCasting(connection.session.receiver)
    }
}
```

A receiver that pairs with a PIN or password asks for it only the first time. `Airkast(context)`
keeps the pairing in the app's no-backup files, so later connections go in without asking. A
receiver that pairs without one but asks for a password as each session starts is asked on every
connection.

### Stop casting

```kotlin
player.disconnect()
```

`disconnect` stops playback on the TV, closes the session and clears the item. By default, the
player also disconnects when the user presses BACK on the TV's remote, or switches the TV to
another input or app. To handle these yourself, build the player with `disconnectOnBack = false`
or `disconnectOnInterrupt = false`.

If the connection drops, the player does not reconnect by itself. Calling `prepare()` connects
again and continues from the last position. A notification's play button calls `prepare()`.

With a local player (next section), the item returns to the phone instead, both after
`disconnect` and after a dropped connection.

### Play on the phone and move playback to the TV

Give the player your own local player, as you would to media3's `CastPlayer`:

```kotlin
val player = AirkastPlayer(context, airkast) {
    localPlayer = ExoPlayer.Builder(context).build()
}
```

The `ExoPlayer` plays until a cast starts. The current item then moves to the TV at the same
position. When the cast ends, the item comes back to the `ExoPlayer`, paused where the TV stopped.
One `MediaSession` over the `AirkastPlayer` controls both.

### Play music on a speaker

A speaker or a soundbar plays no URL: your player decodes, and the speaker plays its audio. With
ExoPlayer, give the player `AirkastAudioSink` as its audio route:

```kotlin
import io.github.aivanyuk.airkast.exoplayer.AirkastAudioSink

val sink = AirkastAudioSink(context)
val player = AirkastPlayer(context, airkast) {
    localPlayer = ExoPlayer.Builder(context, sink.renderersFactory(context)).build()
    audioRoute = sink
}
```

`player.connect(receiver)` to a speaker then keeps the `ExoPlayer` playing, playlist and all, and
moves its audio to the speaker; `connection` turns `Streaming`, and the phone's volume keys set
the speaker's volume. A picker lists speakers with `receiver.isAudioSupported`. An app with its
own player opens the stream itself with `airkast.connectAudio(receiver)` and writes PCM to it
([guide](docs/guide.md#stream-audio-yourself)).

### Keep casting with the app in the background

Subclass `AirkastSessionService`, a media3 `MediaSessionService`, and return your player. media3
runs the service in the foreground while the player plays, so the cast continues when the app is
in the background. Its notification has a Stop casting button while a cast is on.

```kotlin
class PlaybackService : AirkastSessionService() {
    override val player get() = (application as App).player
}
```

Declare the service in your manifest:

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

The service releases only its `MediaSession`, never the player, so keep the player in an object
that lives as long as the process, such as your `Application`.

### Configure the client

`Airkast(context)` sets Android defaults: it names the sender after your app's label, gives it an
ID that stays the same across launches, checks the local network permission, binds connections to
the receiver's Wi-Fi, and keeps pairings in the app's no-backup files, encrypted with a key in the
Android Keystore. Every default is a builder property you can change:

```kotlin
import io.github.aivanyuk.airkast.Airkast
import io.github.aivanyuk.airkast.SenderIdentity
import io.github.aivanyuk.airkast.android.Airkast
import io.github.aivanyuk.airkast.android.logcat
import kotlin.time.Duration.Companion.seconds

val airkast = Airkast(context) {
    identity = SenderIdentity(name = "Living room remote", deviceId = identity.deviceId)
    requestTimeout = 3.seconds
    logger = Airkast.Logger.logcat()
}
```

The TV shows the sender's `name`. Keep `identity.deviceId` when you set a new identity: a Mac or
an Apple TV asks its user to allow each sender ID it has not seen, so a new ID on every launch
means a new question on every launch.

On a desktop JVM, build the client with `Airkast { ... }` from `airkast-core`, and connect to a
receiver by its address: `Receiver("TV", "192.168.1.20")`. Its sender ID is random unless you set
one, for example with `SenderIdentity.deviceIdFrom(seed)` and a seed your app keeps.
[docs/guide.md](docs/guide.md#the-client) lists every property and its default.

### Log and measure what happens

`logger` takes debugging lines, and `eventListener` takes typed events that you can count in your
app's statistics, such as `Connected`, `ConnectFailed` and `SessionEnded`, each with its duration.
`AirkastPlayer` has its own `eventListener`, for how casts start and end. Log lines never contain
a media URL, a key, a PIN or a password. See [docs/guide.md](docs/guide.md#logs-and-statistics).

## Sample apps

[`sample/`](sample) holds two small apps with the same screen, one built with Compose and one with
views. Both find TVs and speakers or take an address, ask for the local network permission, cast
a URL, play music on a speaker, switch tracks, and list what the receiver reports. They share one integration module:

- [`sample/cast`](sample/cast) builds the app's client, and the `AirkastPlayer` over an `ExoPlayer`
  with `AirkastAudioSink`, in
  [`Cast.kt`](sample/cast/src/main/kotlin/io/github/aivanyuk/airkast/sample/Cast.kt), and the
  `AirkastSessionService` in
  [`CastService.kt`](sample/cast/src/main/kotlin/io/github/aivanyuk/airkast/sample/CastService.kt).
- [`sample/compose`](sample/compose) collects the player's flows with
  `collectAsStateWithLifecycle`.
- [`sample/views`](sample/views) collects them under `repeatOnLifecycle`.

The samples declare airkast by its Maven Central coordinates, and the build substitutes the
modules in this repository, so they always run the current code. Install one on a connected
device:

```bash
./gradlew :sample:compose:installDebug
```

## Documentation

- [docs/guide.md](docs/guide.md): the full usage guide: the client's options, pairing, the
  player, speakers, using a session or an audio stream without media3, logs and statistics, and
  errors.
- [docs/compatibility.md](docs/compatibility.md): the receivers, Android versions and networks
  airkast has been checked on, and how each receiver behaves.
- [docs/architecture.md](docs/architecture.md): how the code is organised, for contributors.
- [docs/releasing.md](docs/releasing.md): versioning, what counts as a breaking change, and how
  releases are published.
- [CONTRIBUTING.md](CONTRIBUTING.md): the rules every change follows, and how to run the tests.
- [CHANGELOG.md](CHANGELOG.md): what changed in each release.

## Credits

The protocol facts come from [pyatv](https://github.com/postlund/pyatv) (MIT) and
[send-airplay2](https://github.com/ilyalissoboi/send-airplay2) (Apache-2.0), and from watching a
real receiver. No code is copied from either. How a sender streams audio to an AirPlay 2 speaker
comes from [owntone](https://github.com/owntone/owntone-server) (GPL-2.0), as facts only: no
code is taken from it. The Curve25519 field arithmetic follows
[TweetNaCl](https://tweetnacl.cr.yp.to/) (public domain).

## Licence

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

AirPlay is a trademark of Apple Inc. This project is not affiliated with, endorsed by or
sponsored by Apple.

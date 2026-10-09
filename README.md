# airkast

Play a video URL on a TV from Kotlin: pair, load, seek, pause, switch audio and subtitle tracks,
and follow what the TV reports. It talks to receivers that implement AirPlay video, such as
smart TVs with an AirPlay 2 receiver built in.

- **`airkast-core`**: plain Kotlin on the JVM, with no Android dependency. It runs on a desktop
  JVM against a real TV, which is how it is tested.
- **`airkast-android`**: receiver discovery through `NsdManager`, Android 17's local network
  permission, and connections bound to the network the receiver is on.
- **`airkast-media3`**: `AirkastPlayer`, a media3 `Player` over a session, so media3's UI and a
  `MediaSession` drive the TV. It keeps the phone awake while a cast plays.

## Status

Early, and versioned 0.x: a minor release may change the API ([releasing](docs/releasing.md)).
It is checked against an LG CX (webOS, receiver 377.25.06), and reverse-engineered from public
notes, with no specification behind it. What works there:

- transient pairing, with no PIN on the screen;
- loading an HLS URL at a start position, then play, pause, seek, stop and the next item;
- position, duration and buffered ranges;
- reading and switching audio and subtitle renditions;
- receiver events: state, end of item, the TV remote's pause, seek and BACK, and its volume.

It does not yet support receivers that demand a PIN or a password, receivers that take URLs
only over AirPlay video v1, Apple TV's remote channel, or setting the volume (the LG reports it
but ignores a change). [docs/compatibility.md](docs/compatibility.md) lists what has been
checked, on which receivers and Android versions.

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

On Android:

```kotlin
// An app that targets SDK 37 declares ACCESS_LOCAL_NETWORK and asks for it before this.
val receiver = ReceiverDiscovery(context).receivers
    .mapNotNull { list -> list.firstOrNull { it.isSupported } }
    .first()
val session = Airkast.connect(context, receiver)
session.load(VideoItem("https://example.com/master.m3u8", startAt = 10.minutes))
session.events.collect { event ->
    when (event) {
        is ReceiverEvent.Back -> session.stop()  // the TV leaves its player once the sender stops
        is ReceiverEvent.StateChanged -> show(event.state)
        else -> Unit  // new events may join in a minor release
    }
}
val info = session.playbackInfo()  // position, duration, buffered ranges
session.seek(20.minutes)
session.close()
```

Every time is a `kotlin.time.Duration`, and a volume runs from 0 to 1.

With media3, the player drives the session, and a `MediaSession` over it gives the notification
and the lock screen:

```kotlin
val player = AirkastPlayer(context)
player.setMediaItem(MediaItem.fromUri("https://example.com/master.m3u8"), 600_000)
player.playWhenReady = true
player.session = Airkast.connect(context, receiver)  // loads the item
```

The app still opens and closes the session, and takes from it what a `Player` has no place for:
BACK on the TV's remote, and switching tracks.

On a desktop JVM, `Airkast.connect(Receiver("TV", "192.168.1.20"))` takes a receiver typed in by
hand.

The receiver fetches the stream itself. A receiver whose player is a web page (the LG's is)
needs CORS headers on every playlist and segment.

BACK on the TV's remote arrives as `ReceiverEvent.Back`, and the TV leaves its player only when
the sender calls `stop()`.

## Tests

```bash
./gradlew check
AIRKAST_RECEIVER=192.168.1.20 ./gradlew :core:test --tests '*LiveReceiverTest'
```

`check` runs the JVM tests, the Android tests under Robolectric at several API levels, lint,
ktlint, the public API check and Animal Sniffer. The crypto tests check SRP-6a and HKDF against
srptools and pyatv, and ChaCha20-Poly1305 against RFC 8439. ChaCha20-Poly1305 is implemented here
because the JCA has no provider for it below Android 9. The live test plays on a real receiver.

[CONTRIBUTING.md](CONTRIBUTING.md) has the rules a change follows, and
[docs/architecture.md](docs/architecture.md) the shape of the code.

## Credits

The protocol facts come from [pyatv](https://github.com/postlund/pyatv) (MIT) and
[send-airplay2](https://github.com/ilyalissoboi/send-airplay2) (Apache-2.0), and from watching a
real receiver. No code is copied from either.

## Licence

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

AirPlay is a trademark of Apple Inc. This project is not affiliated with, endorsed by or
sponsored by Apple.

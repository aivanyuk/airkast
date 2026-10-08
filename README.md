# airkast

Play a video URL on a TV from Kotlin: pair, load, seek, pause, switch audio and subtitle tracks,
and follow what the TV reports. It talks to receivers that implement AirPlay video, such as
smart TVs with an AirPlay 2 receiver built in.

- **`airkast-core`**: plain Kotlin on the JVM, with no Android dependency. It runs on a desktop
  JVM against a real TV, which is how it is tested.
- **`airkast-android`**: receiver discovery through `NsdManager`.

## Status

Early. It is checked against an LG CX (webOS, receiver 377.25.06), and reverse-engineered from
public notes, with no specification behind it. What works there:

- transient pairing, with no PIN on the screen;
- loading an HLS URL at a start position, then play, pause, seek, stop and the next item;
- position, duration and buffered ranges;
- reading and switching audio and subtitle renditions;
- receiver events: state, end of item, the TV remote's pause, seek and BACK, and its volume.

It does not yet support receivers that demand a PIN or a password, Apple TV's MRP remote
channel, or setting the volume (the LG reports it but ignores a change).

## Use

```kotlin
// settings.gradle.kts: repositories { maven("https://jitpack.io") }
implementation("com.github.aivanyuk.airkast:airkast-core:<tag>")
implementation("com.github.aivanyuk.airkast:airkast-android:<tag>")
```

```kotlin
// Scans while collected. playsUrls is false for speakers and for TVs without URL playback.
val receiver = ReceiverDiscovery(context).receivers()
    .mapNotNull { list -> list.firstOrNull { it.playsUrls } }
    .first()
val session = Airkast.connect(receiver)
session.load(MediaItem("https://example.com/master.m3u8", startSeconds = 600.0))
session.events.collect { event -> /* StateChanged, ItemEnded, RemoteCommand... */ }
val info = session.playbackInfo()  // position, duration, buffered ranges
session.seek(1200.0)
session.close()
```

The receiver fetches the stream itself. A receiver whose player is a web page (the LG's is)
needs CORS headers on every playlist and segment.

The receiver also sends timing requests to the sender over UDP. Without answers it plays but
reports nothing, so the sender must be reachable from the TV. An emulator behind NAT is not.

BACK on the TV's remote arrives as `RemoteCommand(BACK_START)` and `RemoteCommand(BACK_END)`, and
the TV leaves its player only when the sender calls `stop()`.

## Tests

```bash
./gradlew :core:test
AIRKAST_RECEIVER=192.168.1.20 ./gradlew :core:test --tests '*LiveReceiverTest'
```

The crypto tests check SRP-6a and HKDF against srptools and pyatv, and ChaCha20-Poly1305 against
RFC 8439. ChaCha20-Poly1305 is implemented here because the JCA has no provider for it below
Android 9.

## Credits

The protocol facts come from [pyatv](https://github.com/postlund/pyatv) (MIT) and
[send-airplay2](https://github.com/ilyalissoboi/send-airplay2) (Apache-2.0), and from watching a
real receiver. No code is copied from either.

## Licence

Apache-2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

AirPlay is a trademark of Apple Inc. This project is not affiliated with, endorsed by or
sponsored by Apple.

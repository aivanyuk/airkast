# Compatibility

What airkast has been checked on, and what each platform version changes. A change that alters
what goes over the wire adds a row to "Checked" (see [CONTRIBUTING.md](../CONTRIBUTING.md#tests)).

## Receivers

### Checked

| Receiver | Firmware, `srcvers` | Date | Sender | Result |
| --- | --- | --- | --- | --- |
| LG OLED CX (webOS) | 04.64.00, 377.25.06 | 2026-10-08, at 8dd8c77 | desktop JVM 21 | `Supported`. Live test passes: start position, seek, pause, play, tracks, volume read, stop. A whole 51-minute episode to its end, position within 2 s of the wall clock |
| LG OLED CX (webOS) | 04.64.00, 377.25.06 | 2026-10-09, at c2ceb0f | desktop JVM 21 | `Supported`. Live test passes on a `streaming` item, now the default: start position, seek, pause (reported `Paused`), play, tracks read and subtitles off, volume read, stop. Probed the same day: for a `streaming` item tracks are read and switched (subtitles, audio with a rebuffer, subtitles off) on Apple's bipbop stream and on a kino.pub hls4 master; for a `file` item none are reported and a selection is ignored; a paused `streaming` item reads as `loading` with rate 0 |
| LG OLED CX (webOS) | 04.64.00, 377.25.06 | 2026-10-09, at be15526 | Xiaomi 2201117TY, Android 13, the sample app | `Supported`. Found by `ReceiverDiscovery`, cast and played the default stream through `AirkastPlayer`, by hand |
| LG OLED CX (webOS) | 04.64.00, 377.25.06 | 2026-10-10, before 0.3.0 | desktop JVM 21 (WSL) | `Supported` with `ntpTiming = true`. Live test passes: start position, seek, pause, play, tracks switched, stop |
| LG OLED CX (webOS) | 04.64.00, 377.25.06 as on 2026-10-09, not read again | 2026-10-10, at 4104649 | Xiaomi 2201117TY, Android 13, the sample app | `Supported`, then `NeedsPin` with its AirPlay settings set to ask for a PIN. By hand, through `AirkastPlayer.connect`: cast transiently first; with the PIN on, the sample listed it as asking for a PIN, asked for the one on the screen, paired (pair-setup M1 to M6), verified the pairing (pair-verify), and played. The credentials stayed in the app's no-backup files, readable by the app only |
| LG OLED CX (webOS) | 04.64.00 as on 2026-10-08, not read again; 377.25.06 from `/info` | 2026-10-10, at 67da794 | desktop JVM 21 (WSL) | `Supported`. Live test passes through a client built with `Airkast { }`: start position, seek, pause, play, tracks switched, volume read, stop |
| LG OLED CX (webOS) | 04.64.00 as on 2026-10-08, not read again; 377.25.06 from `/info` | 2026-10-10, at 54cce4b | Xiaomi 2201117TY, Android 13, the sample app | `Supported`. By hand, through `AirkastPlayer` with an `ExoPlayer` as `localPlayer`: played on the phone, moved to the TV at the same point when it was tapped, and came back to the phone paused where the TV was on BACK and on Disconnect, then resumed with play |
| MacBook Pro (MacBookPro18,1, macOS build 25G241) | 960.13.25 | 2026-10-10, before 0.3.0 | Xiaomi 2201117TY, Android 13, the sample app with `ntpTiming = true` | Plays with NTP timing, set to "Anyone on the same network". Transient pairing, load, play, pause from the Mac, the next item, tracks read and switched (closed captions, subtitles, subtitles off), playback info, stop, by hand. At "Current User" transient pairing is refused |
| MacBook Pro (MacBookPro18,1, macOS build 25G241 as above, not read again) | 960.13.25 | 2026-10-10, before 0.3.0 | Xiaomi 2201117TY, Android 13, the sample app | `NeedsPassword` with "Require password" on. By hand, through `AirkastPlayer.connect`: transient pairing refused; a wrong password refused at pair-setup M4 (`SecretRejected`); the right one paired (M1 to M6) with no `/pair-pin-start`, and later connects verified the pairing (pair-verify). Every base SETUP, even after pair-verify, answered 401 with a Digest challenge, a second 401 to a wrong password, and 200 to the right one, which the credentials then kept: later connects went in without asking. Played; `AirkastPlayer`'s notification did not follow, since the Mac's `currentItemChanged` carries no item uuid |

### How `Receiver.compatibility` decides

From the `_airplay._tcp` TXT record, in this order. Bit numbers follow pyatv's `AirPlayFlags`.

| Case | When | Receivers |
| --- | --- | --- |
| `Unknown` | No TXT record: typed in by hand | any |
| `NoVideo` | No video v2 (feature bit 49) and no video v1 (bit 0) | speakers; mirroring-only receivers |
| `VideoV1Only` | Video v1 without v2 | older Apple TVs; some third-party receivers |
| `AccessRestricted` | `act=2`, which pyatv reads as "Current User" | a Mac's AirPlay Receiver at its default, "Current User" |
| `NeedsPassword` | `pw=true`, or status flag `0x80`. The first `Airkast.connect` with a prompt pairs with the password, and later ones use the stored `Credentials`, which keep the password too | a Mac set to "Require password"; a receiver with a password set |
| `NeedsPin` | Status flag `0x8`. The first `Airkast.connect` with a prompt pairs, and later ones use the stored `Credentials` | the LG CX set to ask for a PIN; a receiver set to require a code |
| `NoTransientPairing` | Neither system pairing (bit 43) nor CoreUtils pairing (bit 48) | none seen yet |
| `Supported` | Anything else | the LG CX |

pyatv also reads status flag `0x200` as "pairing mandatory". The LG CX sets it (flags `0x244`) and
pairs without a PIN, so airkast ignores it. A receiver may also ask for a PIN or password without
flag `0x8` or `0x80`; transient pairing then fails with `PairingFailed`, and pairing with one is
the caller's choice (the sample offers both).

### Pairing

| How | When | Wire |
| --- | --- | --- |
| Transient | Every connect without credentials | `X-Apple-HKP: 4`, pair-setup M1 to M4 with the transient flag and PIN 3939 |
| With a PIN | `Airkast.pair`, or a connect that pairs first | `X-Apple-HKP: 3`, `/pair-pin-start` shows the PIN, pair-setup M1 to M6: SRP, then Ed25519 long-term keys exchanged under ChaCha20-Poly1305 |
| With a password | `Airkast.pair(receiver, Secret.Password)`, or a connect that pairs first | As with a PIN, with the password as the SRP secret and no `/pair-pin-start` |
| Pair-verify | Every connect with credentials in the client's `CredentialStore` | `X-Apple-HKP: 3`, `/pair-verify` M1 to M4: X25519, signed with both long-term keys |
| Digest | A base SETUP answered with 401 and `WWW-Authenticate: Digest`, as a Mac with a password answers every one | SETUP again, and every request after, with `Authorization: Digest`: RFC 2617 without `qop`, user name `AirPlay`, and the password kept with the credentials or typed |

The PIN pairing and pair-verify follow pyatv's AirPlay HAP procedures, and `Credentials.encoded`
is pyatv's credential string, `ltpk:ltsk:atv_id:client_id`. They are tested against
`FakeReceiver`, and checked by hand on the LG CX set to ask for a PIN.

The password pairing follows owntone's AirPlay 2 sender, which pairs a receiver with status flag
`0x80` the way it pairs one with a PIN, with the password in its place and nothing shown. owntone
also answers a 401 to SETUP with Digest and the password, as pyatv does for an AirPlay 1
ANNOUNCE; the user name is the one Rapid7's Apple TV login scanner tries. A Mac needs both, so
the password stays in the `Credentials` (a fifth field of `encoded`, after pyatv's four) and
answers the Digest challenge on every connect. A second 401 is a wrong password: the prompt is
asked once more, and the password it takes replaces the kept one. A receiver that pairs
transiently leaves no credentials to keep it in, so the prompt asks on every connect. Both are
tested against `FakeReceiver`, and checked by hand on a Mac set to "Require password".

### LG CX (webOS 04.64.00)

- It speaks AirPlay video v2 only. Its features are `0x7F8AD0,0x38BCB46`, and every AirPlay 1
  video endpoint (`/play`, `/playback-info`, `/scrub`, `/reverse`) answers 404.
- Before pairing, everything but `GET /info` answers 470. Transient pairing needs no PIN.
- Set to ask for a PIN in its AirPlay settings, it reads as `NeedsPin` (status flag `0x8`), shows
  a PIN for `/pair-pin-start`, pairs with it, and lets the sender in with pair-verify after.
- Without RECORD after the event channel opens, it plays but sends no events.
- It plays with or without NTP timing. With `timingProtocol: NTP` its SETUP answer adds a
  `timingPort`. No timing request was seen, but the one run with NTP (2026-10-10, the live test)
  was from WSL, where Windows' firewall would have dropped them.
- `Start-Position` must be a CMTime. `Start-Position-Seconds` is ignored.
- A seek without the item's UUID and both tolerances is ignored.
- `selectedMediaArray` lists the selected audio and subtitle renditions, and a `setProperty` on
  it switches them live (audio with a rebuffer), only for an item loaded with
  `mediaType: streaming`. A `file` item answers an empty list and ignores the selection, whatever
  the payload. Ids follow the master's `EXT-X-MEDIA` order: on Apple's bipbop stream audio 0–1
  then subtitles 2–9, on a kino.pub hls4 master subtitles 0–8 then audio 9–17. Turning subtitles
  off keeps the forced one.
- With `mediaType: streaming`, a pause reads as `loading` with rate 0 for as long as it lasts,
  as an event and on a poll. `file` reports it as `paused`, and reports no buffered ranges.
  airkast reports `Paused` for `loading` while the rate is 0, so a caller sees the same in both.
- It reads its volume (`GET_PARAMETER`), but ignores every way of setting it.
- BACK on its remote arrives as `pbpr` then `pbal`, and it leaves the player only once the
  sender stops. Its volume keys arrive as `dvlc`, with a volume from 0 to 1.
- Its player is Apple's web receiver on hls.js, so streams need CORS headers.
- A second sender takes over, and the first one's connection closes.
- Once, 13 minutes into a session, it left a `/feedback` unanswered for over 5 s, and played on.
  A late answer therefore costs the sender one `Timeout`, and the session ends only if the
  receiver is still silent at the next request.

### Mac (macOS AirPlay Receiver, 960.13.25)

- Its "Allow AirPlay for" setting decides who gets in. At "Current User", the default, it lets in
  only the owner's devices, which a third-party sender cannot be, and refuses transient pairing:
  an iPhone on the owner's Apple Account casts, airkast cannot. pyatv reads that setting as TXT
  `act=2` (`AccessRestricted`); the Mac's record at "Current User" is not checked yet. At "Anyone
  on the same network" it pairs transiently without a PIN, and `GET /info` reports
  `statusFlags = 4`. "Everyone" is not checked yet.
- With "Require password" on, it reads as `NeedsPassword`, refuses transient pairing, and pairs
  with the password as owntone does. It then answers every base SETUP with 401 and a Digest
  challenge, even after pair-verify, and takes the same password there. airkast answers that
  challenge once and signs every later request with its nonce for the session's life. The Mac
  took that over sessions of up to 36 s; whether it expires the nonce in a longer one is not
  checked yet, and a 401 there would reach the caller as `Rejected`. Discovery may still hold
  the record from before the password was set, and the receiver then reads as `Supported` until
  it is resolved again.
- `GET /info` answers 403 without an AirPlay `User-Agent`.
- It answers the base SETUP with 500 when `timingProtocol` is `None`. With `NTP` it asks for the
  time three times, within a second, and answers 200 once it has it, then asks again every two to
  three seconds for as long as the session lasts. A sender it cannot reach over UDP (WSL behind Windows' firewall, for
  one) waits on an unanswered SETUP.
- Its features are `0x4A7FCFD5,0x38174FDE`. Video v2 plays through the same `/command` flow as on
  the LG, with the same events, and a `selectedMediaArrayChanged` notification after a track
  switch.
- Track ids follow the master's `EXT-X-MEDIA` order, but renditions that differ only in their
  group appear to count once. On Apple's `bipbop_adv_example_hevc`, whose three English audio renditions
  differ only in channels, the ids are audio 0, closed captions 1 (reported as `sbtl`, with
  `TaggedMediaCharacteristics` for the hard of hearing) and subtitles 2. An id that names no
  rendition, or one of another kind, is answered 200 and ignored.
- With subtitles off, `selectedMediaArray` lists the audio rendition alone. The LG lists a forced
  subtitle as well.

### Not checked yet

- **Apple TV (tvOS).** send-airplay2 reports the same `/command` flow on tvOS 26, and that SETUP
  stalls without NTP timing (`Airkast.ntpTiming`, on by default). Depending on its AirPlay access setting
  it may ask for a PIN or password once (`Airkast.connect` with a prompt, or `Airkast.pair`), or, set to "Only people sharing this home", let in
  only members of its Home.
- **Other TVs with AirPlay 2** (Samsung, Sony, Vizio, Roku, other LG years): whatever their TXT
  record says. A report with the record, the firmware and the live test's result is welcome.

## Senders

### JVM

Java 11 or later. The jar is Java 11 bytecode compiled against the JDK 11 class library. It is
tested on JDK 21.

### Android

minSdk 23 (Android 6.0). Animal Sniffer checks `airkast-core` against API 23 with D8's
desugaring, so an app needs no core library desugaring. Lint checks `airkast-android`, and its
tests run under Robolectric at API 23, 34, 36 and 37.

| Android | What changes | What airkast does |
| --- | --- | --- |
| below 9 (API 28) | The JCA has no ChaCha20-Poly1305 | Carries its own (RFC 8439) |
| below 14 (API 34) | `NsdManager` resolves one service at a time, and a resolved service has one `host` | Resolves services one after another |
| 14 (API 34) and later | A resolved service has `hostAddresses`, which may list IPv6 before IPv4 | Takes the first IPv4 address |
| 17 (API 37), when the app targets 37 | The local network is blocked until the user grants `ACCESS_LOCAL_NETWORK`. A TCP connection times out with no error that names the cause, and `NsdManager` is blocked too | `ReceiverDiscovery`, and a connect through `Airkast(context)`, fail at once with `AirkastException.NotPermitted`. `LocalNetwork.isAccessible` tells an app when to ask |

The app declares `ACCESS_LOCAL_NETWORK` itself, and only when it targets SDK 37 or more, since
Android's guidance is to leave it out below that. It is in the `NEARBY_DEVICES` group, so a user
who granted Bluetooth's nearby devices permission is not asked again.

### Networks

- **Inbound UDP.** With `ntpTiming` on, the default, the receiver sends timing requests to the
  sender over UDP. A sender behind a firewall that drops them, such as WSL behind Windows'
  firewall, gets a SETUP that a Mac never answers. A phone on the receiver's Wi-Fi is reachable.
- **Wi-Fi without internet.** Android may keep mobile data as the default network, and a socket
  that is not bound to Wi-Fi then goes over mobile data and times out. A connect through
  `Airkast(context)` binds the session to the Wi-Fi or Ethernet network whose subnet holds the receiver.
- **VPN.** A VPN that takes all traffic takes LAN connections too. Binding to the Wi-Fi network
  gets around it when the VPN allows apps to bypass it. When it doesn't, casting may fail while
  the VPN is on.
- **Emulators.** The receiver never connects back to the sender, so an emulator behind NAT can
  drive a TV by its IP address. Its `NsdManager` doesn't see the LAN's mDNS, so the receiver is
  typed in.
- **Multicast.** `NsdManager` sends and reads mDNS itself, so no `MulticastLock` is needed.

### Screen off

A session sends `/feedback` every two seconds and keeps two TCP connections open. With the screen
off, the CPU sleeps unless something holds a wake lock. How long the LG keeps a sender that has
gone quiet has not been measured. An app that casts in the background runs a foreground service
of type `mediaPlayback`, which keeps network access under Doze, and holds a partial wake lock and
a Wi-Fi lock while a session plays. `AirkastPlayer` (`airkast-media3`) takes both locks while
an item loads, plays or is paused, and declares `WAKE_LOCK` for it. An app that drives an
`AirkastSession` without the player takes them itself. No phone has yet been checked through a
whole episode with its screen locked.

## Callers

- Kotlin 2.2 or later: airkast compiles at language and API version 2.2.
- kotlin-stdlib 2.2.21 and kotlinx-coroutines 1.10.2 at least. Gradle hands an app its own newer
  versions.
- Gradle metadata says JVM 11 (`org.gradle.jvm.version`).
- `airkast-media3`: media3 1.11.1 at least. Its player extends `SimpleBasePlayer`, which media3
  marks unstable, so a media3 release that changes it may need an airkast release to match.
- No reflection, so R8 needs no keep rules.

# Compatibility

This page records which receivers, Android versions and networks airkast has been checked on, and
how each receiver behaves. Read the summary to decide whether airkast fits your setup. The
receiver notes further down are protocol details, mostly for contributors.

A change that alters what goes over the wire adds a row to [Checked](#checked) (see
[CONTRIBUTING.md](../CONTRIBUTING.md#tests)).

Terms used on this page:

- **TXT record**: the key-value data a receiver announces on the local network over mDNS, read by
  `ReceiverDiscovery` into `Receiver.properties`.
- **Feature bits** and **status flags**: fields of the TXT record (`features` or `ft`, and
  `flags` or `sf`) that say what a receiver supports and requires.
- **`srcvers`**: the receiver's AirPlay software version, from the TXT record.
  `Receiver.sourceVersion` holds it.

## Summary

| Receiver | Status |
| --- | --- |
| LG OLED CX (webOS 04.64.00) | Plays. Checked with transient pairing and with a PIN, from a desktop JVM and from an Android 13 phone. |
| Mac's AirPlay Receiver (macOS, `srcvers` 960.13.25) | Plays when "Allow AirPlay for" is set to "Anyone on the same network", with or without "Require password". At the default, "Current User", it refuses airkast. |
| Samsung HW-Q990B soundbar (AirPlay SDK 2.0.7, `srcvers` 366.0) | Plays audio the app streams (`Airkast.connectAudio`), from a desktop JVM and from an Android 13 phone. Plays no URL: it is a speaker. |
| Apple TV | Not checked. |
| Other TVs with AirPlay 2 | Not checked. |
| Other AirPlay 2 speakers | Not checked. Those built on Apple's AirPlay SDK, as the Samsung is, are likely to behave the same. |

| Sender | Requirement |
| --- | --- |
| Android | Android 6.0 (API 23) or later. Android 17 needs the local network permission for apps that target SDK 37. |
| Desktop JVM | Java 11 or later. |
| Network | The receiver must reach the media URL, and, with `ntpTiming` on or for an audio stream, reach the sender over UDP. |

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
| MacBook Pro (MacBookPro18,1, macOS build 25G241 as above, not read again) | 960.13.25 | 2026-10-10, before 0.3.0 | Xiaomi 2201117TY, Android 13, the sample app | With `X-Apple-Client-Name` sent: the "Allow … to AirPlay" prompt for a new sender named the app ("airkast sample - compose"), where it showed "" without the header. An ASCII name only |
| LG OLED CX (webOS) | 04.64.00 as on 2026-10-08, not read again; 377.25.06 from the connect log | 2026-10-14, at e650851 | Xiaomi 2201117TY, Android 13, the sample app | `NeedsPassword`, paired before. By hand, through `AirkastPlayer`: ExoPlayer's test MP4 (`frame-counter-one-hour.mp4`) and MP3 (`play.mp3`), served without CORS headers, loaded as `file` (the sample's "Auto: File") and played. The MP4 from where the phone was, seek forward, pause (reported `Paused`), play, playback info (a 1 h duration, no buffered or seekable ranges), tracks (none reported), stop. The MP3 played to its end: `itemPlayedToEnd`, then `stopped`. `LiveReceiverTest` not run, since the TV now asks for a password |
| Samsung HW-Q990B (soundbar) | SAT-iMX8M22WWC-1010.0, 366.0 (`sdk` AirPlay;2.0.7) | 2026-10-14, before 0.5.0 | desktop JVM 21 (WSL) | `NoVideo`, audio `Supported`. `LiveAudioTest` passes at 44.1 and 48 kHz: transient pairing, NTP timing, a type-96 ALAC stream, play, pause and resume from the frame that played, flush, volume set and read back, the track's name and progress sent. The beeps, the pause, the resume and the tone after the flush heard by a microphone in the room, at the times the test's positions said |
| Samsung HW-Q990B (soundbar) | as above | 2026-10-14, before 0.5.0 | Xiaomi 2201117TY, Android 13, the sample app | By hand, through `AirkastPlayer` with an `ExoPlayer` over `AirkastAudioSink`: found by `ReceiverDiscovery` and listed as a speaker, connected in 1.2 s, ExoPlayer's test MP3 (`Jazz_In_Paris.mp3`) played on the soundbar, pause and play from the notification, the phone's volume keys changed the soundbar's volume, and Disconnect brought the audio back to the phone |
| LG OLED CX (webOS) | 04.64.00, 377.25.06, not read again | 2026-10-14, before 0.5.0 | Xiaomi 2201117TY, Android 13, the sample app | After the shared connect code moved and the timing clock changed to the monotonic one: played through `AirkastPlayer` from the sample app, by hand |
| MacBook Pro (MacBookPro18,1) | 960.13.25 from the connect log | 2026-10-14, before 0.5.0 | Xiaomi 2201117TY, Android 13, the sample app | `NeedsPassword`, paired before. By hand, through `AirkastPlayer` after the shared connect code moved: an MP4 file, an MP3 file and the music sample played as URLs. The first cast after the app started failed with `Timeout` at the base SETUP: the Mac asked "Allow … to AirPlay?", since `Airkast(context)` sends a new random `deviceID` each time the app starts, and held SETUP past the 5 s `requestTimeout` until it was answered. The next cast went in at once |
| LG OLED CX (webOS) | 04.64.00, not read again; 377.25.06 from the connect log | 2026-10-17, at a6045ca | Xiaomi 2201117TY, Android 13, the sample app | Set to ask for a code "first time only", it let the phone in transiently and advertised `Supported`, after the app's data was cleared: the sender ID, kept since 0.5.0, is the same. With its paired devices reset, `NeedsPin`. By hand, through `AirkastPlayer.connect`: a wrong PIN was refused at pair-setup (`SecretRejected`), the player connected again at once, the TV showed a new PIN 0.4 s later and the prompt said one PIN was refused; the new PIN paired (M1 to M6), the pairing verified, and the item moved to the TV where the phone was |
| LG OLED CX (webOS) | 04.64.00 as on 2026-10-08, not read again; 377.25.06 | 2026-10-17, at ac8a795 | desktop JVM 21 (WSL) | `Supported`. Apple's `bipbop_adv_example_hevc` master loaded as `streaming` at 30 s. `renditions()` listed audio 0 English (group `a1`; the same rendition listed in `a1`, `a2` and `a3`, counted once), subtitles 1 English (`cc`, closed captions) and subtitles 2 English (`sub1`). The LG reported audio 0 and subtitles 1 selected at the start, the stream's defaults, with the captions on screen. `selectTrack(Subtitles, 2)` was reported as subtitles 2, `selectTrack(Subtitles, 1)` as 1 again, and `selectTrack(Subtitles, null)` left audio 0 alone. The LG counts a rendition repeated across audio groups once, as the Mac does. On screen the two English tracks were not told apart by eye; the reported selections are the evidence |
| LG OLED CX (webOS), Samsung HW-Q990B, MacBook Pro (MacBookPro18,1) | as above, not read again | 2026-10-17, at 4cfedec | Xiaomi 2201117TY, Android 13, the sample app | One `ReceiverDiscovery` shared by the app: each receiver listed once, and again once when the picker was opened a second time. Cast to the LG and played. The `deviceid` was not checked across an address change |
| LG OLED CX (webOS) | 04.64.00, not read again; 377.25.06 from the connect log | 2026-10-17, at 66014b8 | Xiaomi 2201117TY, Android 13, the sample app | Paired with a PIN by the build before, which kept the pairing in the clear. Installed over it, `CredentialStore.encrypted` sealed it on the first connect under a new Android Keystore key (the file then held hex only), and the LG verified the pairing without asking: connected in 783 ms, against 474 ms before, the key made once. After the app was stopped and started again, the sealed pairing opened and verified, connected in 712 ms. Not checked: Android 6 (API 23), a factory reset, a backup restored to another phone |
| LG OLED CX (webOS) | 04.64.00, not read again; 377.25.06 from the connect log | 2026-10-17, at 0908cc6 with the sample's subtitle button | Xiaomi 2201117TY, Android 13, the sample app | `Supported`. By hand, through `AirkastPlayer` and its media3 `Tracks`, on Shaka's Angel One master (six audio renditions in one group, four subtitle ones): the sample's track dialog and `PlayerView`'s audio menu and subtitle button listed them, and picking German and French audio, two subtitle languages and subtitles off switched the TV. The LG reported each selection on the first `tracks()` read after it, about 0.1 s later; audio rebuffered for a second or two. Ids followed the master: audio 0 to 5, subtitles 6 to 9 |
| Samsung HW-Q990B (soundbar) | SAT-iMX8M22WWC-1010.0, 366.0 | 2026-10-17, after 0.5.0 | desktop JVM 21 (WSL) | `Airkast.describe` of the address typed in: `NoVideo`, audio `Supported`, from the TXT record it sent back in `txtAirPlay` (`acl=0`, flags `0x4`). `LiveAudioTest` passes through `connectAudio` to the address typed in, which describes it first. Its remote's play/pause, next and previous arrive on the event channel ([below](#samsung-hw-q990b-airplay-sdk-207-3660)); it made no DACP request |
| MacBook Pro (MacBookPro18,1, macOS build 25G241) | 960.13.25 | 2026-10-17, after 0.5.0 | desktop JVM 21 (WSL) | "Require password" on. `Airkast.describe` of the address typed in: `NeedsPassword`, from the TXT record it sent back (`acl=0`, flags `0x84`). Not connected |
| LG OLED CX (webOS) | 04.64.00, 377.25.06 | 2026-10-17, after 0.5.0 | desktop JVM 21 (WSL) | `Airkast.describe` of the address typed in: `Supported` for video and audio, from the TXT record it sent back (`acl=0`, flags `0x244`). Not connected |

### How `Receiver.compatibility` decides

`Receiver.compatibility` is read from the receiver's `_airplay._tcp` TXT record. The cases are
checked in this order, and the first that matches wins. Bit numbers follow pyatv's
`AirPlayFlags`.

| Case | When | Receivers |
| --- | --- | --- |
| `Unknown` | No TXT record: typed in by hand, and not described (`Airkast.describe`) | any |
| `NoVideo` | No video v2 (feature bit 49) and no video v1 (bit 0) | speakers; mirroring-only receivers |
| `VideoV1Only` | Video v1 without v2 | older Apple TVs; some third-party receivers |
| `AccessRestricted` | `act=2`, which pyatv reads as "Current User" | a Mac's AirPlay Receiver at its default, "Current User" |
| `NeedsPassword` | `pw=true`, or status flag `0x80`. The first `Airkast.connect` with a prompt pairs with the password, and later ones use the stored `Credentials`, which keep the password too | a Mac set to "Require password"; a receiver with a password set |
| `NeedsPin` | Status flag `0x8`. The first `Airkast.connect` with a prompt pairs, and later ones use the stored `Credentials` | the LG CX set to ask for a PIN; a receiver set to require a code |
| `NoTransientPairing` | Neither system pairing (bit 43) nor CoreUtils pairing (bit 48) | none seen yet |
| `Supported` | Anything else | the LG CX |

`Receiver.audioCompatibility` decides the same way for an audio stream, with one case of its own
in place of `NoVideo` and `VideoV1Only`:

| Case | When | Receivers |
| --- | --- | --- |
| `NoAudio` | No AirPlay audio (feature bit 9) | receivers that only show video or a screen |

Every other case is as above, so a speaker that asks for a PIN reads as `NeedsPin`, and one
without system or CoreUtils pairing, such as an AirPlay 1 speaker that needs RSA or FairPlay
authentication, as `NoTransientPairing`. The Samsung HW-Q990B (`0x445F8A00,0x1C340`, flags `0x4`)
is `NoVideo` and, for audio, `Supported`; the LG CX is `Supported` for both.

A receiver typed in by hand has no TXT record until `Airkast.describe` asks it `GET /info`,
which the LG CX, the Samsung HW-Q990B and a Mac answer before pairing. The request's body is
`{qualifier: [txtAirPlay]}`, as Apple's senders send it by Emanuele Cozzi's notes, and with it
all three answer with their TXT record in `txtAirPlay`, `acl` included: the record discovery
reads. Without that body, none of them sends `txtAirPlay`. A receiver that leaves it out gets its
properties from the answer's keys: `features` (a 64-bit integer, written as `0xLOW,0xHIGH`),
`statusFlags` as `flags`, `deviceID` as `deviceid`, `model`, `sourceVersion` as `srcvers`, and
`manufacturer`, with no `pw` or `act`: a password still shows in the status flags (`0x80`), but
an access restriction does not. The Samsung's and the Mac's `deviceID` is the `deviceid` of their
TXT record, so a pairing kept for one is found for the other.

The openairplay spec names some feature bits differently from pyatv: bit 48 is
`SupportsTransientPairing` there and CoreUtils pairing in pyatv, and bit 38 is CoreUtils pairing
there and `SupportsUnifiedMediaControl` in pyatv. `NoTransientPairing` checks bit 43 or 48, which
reads the same under either name for every receiver on this page. Emanuele Cozzi's notes side
with pyatv, and read transient pairing as bit 48 or 43, which is airkast's check.

The Mac's TXT record, with "Require password" on, has `acl=0` and no `act` key. pyatv reads
`acl=1` as pairing disabled, such as for a receiver that lets in only its Home. airkast does not
read `acl`.

pyatv also reads status flag `0x200` as "pairing mandatory". The LG CX sets it (flags `0x244`) but
pairs without a PIN, so airkast ignores that flag.

A receiver may ask for a PIN or password without setting flag `0x8` or `0x80`. Transient pairing
then fails with `PairingFailed`, and the app can call `Airkast.pair` with the secret the user
picks. The sample app offers both.

### Formats

What goes in `insertPlayQueueItem`'s `mediaType` for each `MediaFormat`, and what is known about
each receiver playing it. [The guide](guide.md#formats) explains formats from the app's side.

| Format | `mediaType` | What is known |
| --- | --- | --- |
| HLS | `streaming` | Plays on the LG CX and the Mac, with tracks and buffered ranges on the LG. HLS loaded as `file` plays on the LG too, without them |
| MP4, MOV, MP3, AAC, M4A and other files | `file` | Plays on the LG CX: an MP4 and an MP3, from a server that sends no CORS headers, with seek, pause and play, and the MP3 to its end. No tracks, and no buffered or seekable ranges. Not checked on a Mac yet; send-airplay2 casts MP4 files as `file` to an Apple TV (tvOS 26.6) and a Mac, and reports them playing. The live test plays an MP4 and an MP3 file when run (`AIRKAST_FILE_URL`, `AIRKAST_AUDIO_URL`) |
| MPEG-DASH | none | Not played. The LG's player is hls.js, and the Mac's and the Apple TV's is AVFoundation, which plays HLS and files. A sender would have to rewrite the manifest as HLS and serve it |

A speaker that takes AirPlay audio only reads as `NoVideo`. It plays what the sender decodes and
streams itself, which `Airkast.connectAudio` does: see [Audio streams](#audio-streams).

### Audio streams

How an `AudioStream` goes over the wire. It is AirPlay 2's realtime audio, as owntone sends it
with NTP timing; the facts come from owntone's sender, which is GPL, so no code does.

| Step | Wire |
| --- | --- |
| Pairing | As for a session: transient, or pair-verify with credentials |
| Base SETUP | The session's plist, with `timingProtocol: NTP` and the sender's `timingPort`. The reply names `eventPort` and the receiver's own `timingPort`, to which the sender sends one timing request so a stateful firewall in front of it lets the receiver's requests in |
| RECORD | Before the stream's SETUP, with `RTP-Info: seq=…;rtptime=…` naming the first packet |
| Stream SETUP | `type` 96, `ct` 2 (ALAC), `audioFormat` `0x40000` (ALAC 44100/16/2) or `0x100000` (48000/16/2), `spf` 352, `sr`, `latencyMin` a quarter second and `latencyMax` two, `shk` a random 32-byte key, and the sender's `controlPort`. The reply names `dataPort` and `controlPort` |
| Audio | RTP over UDP to `dataPort`: payload type 96, the marker bit on the first packet of a timeline, 352 frames each as an uncompressed ALAC frame (a channel-pair element with the not-compressed bit, the samples big-endian, the end element), sealed with ChaCha20-Poly1305 under `shk`, with the header's timestamp and SSRC as AAD, then the tag and the last 8 bytes of the nonce, which holds the sequence number |
| Sync | Each second, to the receiver's `controlPort`: `0x80 0xD4 0x0007` (`0x90` first after a start or a flush), the RTP time playing now, NTP now, and the RTP time `latency` ahead. The first one's last field is RECORD's `rtptime` |
| Retransmit | The receiver asks on the sender's control port (`0x80 0xD5`, first sequence number, count); the sender sends the packets again to `dataPort` |
| Pause, flush | `FLUSH` with `RTP-Info` naming the next packet; once it is answered, a new timeline starts with a `0x90` sync and a marked packet |
| Volume | `SET_PARAMETER`, `text/parameters`, `volume: -30.0` to `0.0` dB, `-144.0` for silent; `GET_PARAMETER` reads it |
| Now playing | `SET_PARAMETER` with `RTP-Info: rtptime=…`: DMAP (`mlit` with `minm`, `asar`, `asal`), the artwork as `image/jpeg` or `image/png`, and `progress: start/now/end` in RTP time. Each goes only to a receiver with its feature bit: 17 for the DMAP, 15 for the artwork, 16 for the progress, as owntone reads them and the openairplay spec lists them. The Samsung and the LG CX set all three. A receiver with no feature bits to read, such as one typed in by hand and not described, gets all three |
| Keepalive, end | `POST /feedback` every two seconds; `TEARDOWN` when the app closes the stream |

A failed UDP send drops the packet, which the receiver asks for again; sends that fail for three
seconds end the stream. Buffered audio (type 103) and PTP timing are not used.

### Samsung HW-Q990B (AirPlay SDK 2.0.7, 366.0)

What the soundbar does, as seen on the wire.

- It announces AirPlay on port 36617, not 7000, as host `Samsung.local`, with features
  `0x445F8A00,0x1C340`: AirPlay audio (bit 9), buffered audio (40), PTP (41) and CoreUtils
  pairing (48), and no video bit. `GET /info` names `sdk: AirPlay;2.0.7` and an OpenAVNU PTP
  stack.
- It pairs transiently, and takes NTP timing though it advertises PTP: after the base SETUP it
  asks for the time over UDP, and answers the stream's SETUP once it has it.
- It needs RECORD before the stream's SETUP. In the other order it answers every request with 200
  and plays nothing.
- It sets its volume from `SET_PARAMETER` and reads it back, to about 0.3 dB of what was set.
- It plays 44.1 and 48 kHz ALAC.
- It answers `GET /info` before pairing, with its features, status flags `4`, the device ID of
  its TXT record, `model` and `sdk`. Asked for `txtAirPlay`, it adds its TXT record.
- Its remote's play/pause, next and previous arrive on the event channel as `POST /command`
  with `type: sendMediaRemoteCommand` and `value` `plps`, `nitm` or `pitm`, which airkast reports
  as `ReceiverEvent.Other`. Its volume keys arrive as `ReceiverEvent.VolumeChanged`. Checked
  on 2026-10-17 with a silent stream: it never looked up a `_dacp._tcp` service or sent a DACP
  request, though airkast sends `DACP-ID` and `Active-Remote` and one was advertised.
- The openairplay spec's sample `GET /info` from a Denon AVR-X3500H has the same features
  (`0x1C340445F8A00`) and `srcvers` (366.0), and Emanuele Cozzi's notes give a Sonos One the same
  features, with flags `0x404`. Neither receiver is checked.

### Pairing

How each kind of pairing goes over the wire. [The guide](guide.md#pairing) explains pairing from
the app's side.

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

What the LG CX does, as seen on the wire.

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
  then subtitles 2–9, on a kino.pub hls4 master subtitles 0–8 then audio 9–17. A rendition
  repeated in another group counts once, as on the Mac: on `bipbop_adv_example_hevc` audio 0,
  closed captions 1, subtitles 2. Turning subtitles off keeps the forced one.
- With `mediaType: streaming`, a pause reads as `loading` with rate 0 for as long as it lasts,
  as an event and on a poll. `file` reports it as `paused`, and reports no buffered or seekable ranges.
  airkast reports `Paused` for `loading` while the rate is 0, so a caller sees the same in both.
- It reads its volume (`GET_PARAMETER`), but ignores every way of setting it. Its `GET /info`
  answers `volumeControlType: 1`, where a Mac answers 4; whether that value says the volume is
  fixed is not known.
- Its `GET /info` (2026-10-17) holds its features, status flags 580 (`0x244`), `model`
  (`OLED65CX6LA`) and `sourceVersion`. Asked for `txtAirPlay`, it adds its TXT record.
- BACK on its remote arrives as `pbpr` then `pbal`, and it leaves the player only once the
  sender stops. Its volume keys arrive as `dvlc`, with a volume from 0 to 1.
- Its player is Apple's web receiver on hls.js, so streams need CORS headers. A `file` item
  plays from a server that sends none.
- A second sender takes over, and the first one's connection closes.
- Once, 13 minutes into a session, it left a `/feedback` unanswered for over 5 s, and played on.
  A late answer therefore costs the sender one `Timeout`, and the session ends only if the
  receiver is still silent at the next request.

### Mac (macOS AirPlay Receiver, 960.13.25)

What a Mac's AirPlay Receiver does, as seen on the wire.

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
- It asks its user before it lets a new sender play, holding SETUP until they answer, and names
  the sender from the `X-Apple-Client-Name` header, as owntone sends it, not from SETUP's `name`.
  Without the header it shows "". A sender is new when its `deviceID` is, so `Airkast(context)`
  keeps one per app install, and the base SETUP waits `approvalTimeout` (a minute) rather than
  `requestTimeout`. Before that, the sample app sent a new `deviceID` on every launch, and the
  first cast after a launch failed with `Timeout` while the prompt was up.
- It answers the base SETUP with 500 when `timingProtocol` is `None`. With `NTP` it asks for the
  time three times, within a second, and answers 200 once it has it, then asks again every two to
  three seconds for as long as the session lasts. A sender it cannot reach over UDP (WSL behind
  Windows' firewall, for one) waits on an unanswered SETUP.
- Its features are `0x4A7FCFD5,0x38174FDE`. Video v2 plays through the same `/command` flow as on
  the LG, with the same events, and a `selectedMediaArrayChanged` notification after a track
  switch.
- Its `currentItemChanged` names no item, where the LG's does, and gives the reason
  `ReasonAddToPlayQueue`. Every other notification names the item, the first of them
  (`playbackLikelyToKeepUp`) before `currentItemChanged`, so a load ends at the first message
  that names it.
- Track ids follow the master's `EXT-X-MEDIA` order, but renditions that differ only in their
  group appear to count once. On Apple's `bipbop_adv_example_hevc`, whose three English audio
  renditions differ only in channels, the ids are audio 0, closed captions 1 (reported as `sbtl`,
  with `TaggedMediaCharacteristics` for the hard of hearing) and subtitles 2. An id that names no
  rendition, or one of another kind, is answered 200 and ignored.
- With subtitles off, `selectedMediaArray` lists the audio rendition alone. The LG lists a forced
  subtitle as well.

### Not checked yet

- **Apple TV (tvOS).** send-airplay2 reports the same `/command` flow on tvOS 26, and that SETUP
  stalls without NTP timing (`Airkast.ntpTiming`, on by default). Depending on its AirPlay
  access setting, it may ask for a PIN or password once (`Airkast.connect` with a prompt, or
  `Airkast.pair`), or, set to "Only people sharing this home", let in only members of its Home.
- **Other TVs with AirPlay 2** (Samsung, Sony, Vizio, Roku, other LG years): whatever their TXT
  record says. A report with the record, the firmware and the live test's result is welcome.

## Senders

What airkast needs from the device that sends, and what it does about platform differences.

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
[The guide](guide.md#android-17s-local-network-permission) shows how to request it.

### Networks

- **Inbound UDP.** With `ntpTiming` on, the default, the receiver sends timing requests to the
  sender over UDP. A sender behind a firewall that drops them, such as WSL behind Windows'
  firewall, gets a SETUP that a Mac never answers. A phone on the receiver's Wi-Fi is reachable.
  An audio stream always times over NTP, and sends the receiver's timing port a request first,
  which lets its requests through a stateful firewall: WSL's, for one. The receiver's
  retransmit requests come back the same way, to the port the syncs go out from.
- **Wi-Fi without internet.** Android may keep mobile data as the default network, and a socket
  that is not bound to Wi-Fi then goes over mobile data and times out. A connect through
  `Airkast(context)` binds the session to the Wi-Fi or Ethernet network whose subnet holds the
  receiver.
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
gone quiet has not been measured.

To keep casting in the background, an app needs two things:

- A foreground service of type `mediaPlayback`, which keeps network access under Doze.
  `AirkastSessionService` is one.
- A partial wake lock and a Wi-Fi lock while a session plays. `AirkastPlayer` takes both while an
  item loads, plays or is paused, and `airkast-media3` declares `WAKE_LOCK` for it. An app that
  drives an `AirkastSession` without the player takes the locks itself.

No phone has yet been checked through a whole episode with its screen locked.

## Callers

What an app that depends on airkast needs.

- Kotlin 2.2 or later: airkast compiles at language and API version 2.2.
- kotlin-stdlib 2.2.21 and kotlinx-coroutines 1.10.2 at least. Gradle hands an app its own newer
  versions.
- Gradle metadata says JVM 11 (`org.gradle.jvm.version`).
- `airkast-media3`: media3 1.11.1 at least. Its player extends `SimpleBasePlayer`, which media3
  marks unstable, so a media3 release that changes it may need an airkast release to match.
- `airkast-exoplayer`: media3-exoplayer 1.11.1 at least. `AirkastAudioSink` is an ExoPlayer
  `AudioSink`, which media3 marks unstable, with the same consequence.
- No reflection, so R8 needs no keep rules.

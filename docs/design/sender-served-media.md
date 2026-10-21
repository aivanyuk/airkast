# Casting what the receiver cannot fetch

A design, 2026-10-19. Nothing here is built. It answers two questions the owner asked: what it
takes to cast media the receiver cannot load from its URL, MPEG-DASH first, and which formats
that can cover. Each claim about a receiver or a player names where it comes from, and
[What to verify](#what-to-verify) lists what has to be checked on a receiver before the design is
settled. Read [architecture.md](../architecture.md#one-session) for how a session works today;
this page builds on it.

## What it is for

An app has something to play that the TV cannot load as it is:

- an MPEG-DASH stream, which no AirPlay receiver plays: the LG CX's player is hls.js and a Mac's
  is AVFoundation, and both take HLS and files ([compatibility](../compatibility.md#formats));
- a file on the phone, or behind a `content://` URI;
- an HLS stream or a file whose server wants the app's cookies or token headers, or sends no CORS
  headers, which the LG's hls.js needs;
- a container or codec the receiver's player does not read, such as MKV or VP9.

Today a `.mpd` URL is the worst case: `MediaFormat.of` knows no DASH, so the item goes out as
`streaming`, the receiver never takes it, and `load` fails with `Timeout` after `loadTimeout`.

## What AirPlay lets a sender do

There are three ways for a sender to give a receiver media. airkast has two.

| Way | What the receiver does | What airkast has | Fit for this problem |
| --- | --- | --- | --- |
| A URL the sender serves itself | Fetches it from the phone as it would from a CDN, in its own player, with `/command` for play, pause, seek and tracks | The session and `insertPlayQueueItem`, for `streaming` and `file` items; no HTTP server | The path for DASH, local files and streams the receiver cannot reach. The phone decodes nothing |
| An audio stream (type 96) | Plays PCM the app decoded | `AudioStream`, `AirkastAudioSink` | Any audio, on any receiver with `isAudioSupported`, the LG CX included. Done |
| Screen mirroring (type 110, or `POST /stream` on port 7100 in AirPlay 1) | Decodes H.264 the sender encodes | Nothing | Closed by FairPlay, below |

pyatv's `play_url` takes the first way for a local file: it "will internally start web server on a
random port, serving this file only and start streaming from there", and shuts it down when
streaming is done ([pyatv, Stream](https://pyatv.dev/development/stream/)). This design does the
same, and puts a DASH-to-HLS translation in front of the server.

### Why not mirroring

Mirroring is the only AirPlay path on which the sender decodes video, so it is the literal answer
to "on-device decoding". It is not a path for airkast:

- **The video key is wrapped with FairPlay.** The openairplay spec's mirroring pages describe the
  stream's `param1` as "AES key, encrypted with FairPlay" (72 bytes) and `param2` as the IV, and
  say the video payload "can be optionally AES encrypted"
  ([screen_mirroring/http_requests](https://openairplay.github.io/airplay-spec/screen_mirroring/http_requests.html),
  [stream_packets](https://openairplay.github.io/airplay-spec/screen_mirroring/stream_packets.html)).
  In AirPlay 2 the same key travels as `ekey` and `eiv` in SETUP: UxPlay's receiver decrypts
  `ekey` with its FairPlay code, hashes the result with the pair-verify shared secret, and when
  `ekey` and `eiv` are absent it sets up no mirror session and disconnects, logging "Mirroring
  not initialized at SETUP, playing will fail!" (UxPlay `lib/raop_handlers.h`, GPLv3, read for
  facts only). Apple's own senders run `POST /fp-setup` before that SETUP. Whether the LG CX or
  a Mac takes a type-110 stream with no `ekey` is not known; one probe answers it (step 7 below).
- **Sender-side FairPlay exists only as GPL code of unstated origin.** The one open-source Android
  sender found, "Mirror" on F-Droid (GPL-3.0-only), mirrors over "AirPlay 2 (modern) or AirPlay 1
  (legacy)" through a Go library its README labels "RAOP + FairPlay + H.264", and names Apple TV,
  macOS and UxPlay as receivers, not any TV
  ([F-Droid](https://f-droid.org/packages/io.github.jqssun.displaymirror/),
  [jqssun/android-display-mirror](https://github.com/jqssun/android-display-mirror)). The
  `playfair` code in UxPlay carries no licence header and no comment on where it came from.
  airkast takes facts, never code, from GPL sources, and a rewrite would be a re-implementation of
  Apple's key wrapping, which is not a thing to put on Maven Central under Apache-2.0.
- **Even working, it is the worst way to show a film.** The phone decodes, encodes again and runs
  the encoder for the whole item; the picture loses a generation; lip sync between a type-110
  video stream and a type-96 audio stream is the sender's to keep; and a protected stream on
  Android renders to a secure surface that an encoder cannot read.

So the receiver's player stays the decoder, and the sender's job is to hand it something it
reads. When the phone does have to decode and encode, for a codec no receiver takes, the result
goes out over the same HTTP path, as fragmented MP4 HLS the phone writes, not over mirroring.

## Formats

What each kind of media needs, and how far the receiver's player carries it. "Translate" means
the phone rewrites the manifest and serves it; the segments stay as they are. "Remux" means the
phone re-wraps the same compressed frames in fragmented MP4. "Transcode" means it decodes and
encodes.

| What the app has | How | Who decodes | Notes |
| --- | --- | --- | --- |
| DASH, clear, fragmented MP4 (CMAF) segments, H.264 or HEVC with AAC, AC-3 or E-AC-3 | Translate the MPD to an HLS master and media playlists with `EXT-X-MAP`, served by the phone | The receiver | The main case. CMAF is one set of segments that both DASH and HLS reference; the manifest is the only difference ([CTA-5005-B](https://cdn.cta.tech/cta/media/media/resources/standards/cta-5005-b-final.pdf)). In manifest-only mode the receiver fetches the segments from the CDN itself, which needs the CDN to send CORS headers for the LG; Shaka's, Apple's and DASH-IF's test servers do (read 2026-10-19) |
| DASH live (`type="dynamic"`) | Translate, with a media playlist rebuilt on each request from the wall clock and the MPD, refreshed at `minimumUpdatePeriod` | The receiver | `EXT-X-MEDIA-SEQUENCE`, `EXT-X-PROGRAM-DATE-TIME`, `UTCTiming`. DASH-IF's `livesim2/testpic_2s` is the test stream: `SegmentTemplate` with `$Number$`, two-second segments, `UTCTiming` over HTTP |
| DASH whose server wants the app's headers, or sends no CORS headers | Translate, and proxy the segments through the phone with the app's headers; the phone adds CORS | The receiver | Costs the phone the stream's bitrate twice, down and up |
| HLS or a file the receiver cannot fetch (headers, no CORS, a private server) | Proxy: the playlist rewritten to the phone's origin, segments passed through | The receiver | The same server, no translation |
| A file on the phone (`file:`, `content:`): MP4, MOV, M4A, MP3, AAC | Serve it with range requests as a `file` item | The receiver | The codecs are the receiver's. MP4 and MP3 play on the LG CX and the Mac from a CDN today ([compatibility](../compatibility.md#checked)); from the phone is step 2 below |
| DASH subtitles | Sidecar WebVTT: a one-segment subtitle playlist. IMSC1 in fragmented MP4 (`stpp`): passed through; hls.js takes IMSC1's text profile, and HLS allows IMSC1 in fMP4. WebVTT packed in fragmented MP4 (`wvtt`, as Shaka's Angel One has): HLS carries WebVTT only as text segments, so the phone unpacks it | The receiver | Sidecar TTML: dropped, or converted later |
| DASH with WebM segments (VP9, AV1 in WebM, Opus, Vorbis) | Dropped when the MPD offers an MP4 alternative; otherwise transcode | The phone, if at all | HLS has no WebM container, and Apple's players take no VP9 or Opus. Shaka's Angel One offers both `avc1` and `vp09`, `mp4a.40.2` and `opus`, so it plays from its MP4 representations |
| DASH with `ContentProtection` (Widevine, PlayReady) | Nothing | Nobody | The phone never holds clear frames: a protected stream decodes in a secure path the app cannot read, and its licence forbids copying the output. The LG's hls.js receiver page has no key system a third-party sender drives, and FairPlay HLS needs an Apple receiver and the service's own key exchange. `load` says so at once, from the MPD |
| MKV with H.264 or HEVC and AAC or AC-3 | Remux to fragmented MP4 HLS on the phone | The receiver | No decoding, but a muxer: an Android module over media3's extractors and its fragmented MP4 muxer. Later |
| Codecs no receiver takes: VP9 or AV1 on a TV without them, DTS, Opus, Vorbis, MPEG-2 | Transcode on the phone into fragmented MP4 HLS | The phone | A hardware encode of 1080p is within a phone's reach, at a cost in battery, heat and picture, and a few seconds of latency. Only if someone needs it |
| Smooth Streaming | Remux class: its fragments are fragmented MP4, but there is no init segment to point at; one has to be built from the manifest | The receiver | Not planned |
| Audio-only DASH, podcasts, local music | `AudioStream` today, on any receiver with `isAudioSupported`; or translated like video | The phone (stream) or the receiver (translated) | Nothing new needed |

Which codecs the receivers' HLS players read, as far as published sources say:

- Apple's HLS authoring specification: video "MUST be encoded using H.264/AVC, HEVC/H.265, Dolby
  Vision, or AV1"
  ([Apple](https://developer.apple.com/documentation/http-live-streaming/hls-authoring-specification-for-apple-devices)).
  For audio a secondary summary lists AAC-LC, HE-AAC v1 and v2, xHE-AAC, Apple Lossless, FLAC,
  AC-3 and E-AC-3 ([Radiant Media Player, end of 2024](https://www.radiantmediaplayer.com/blog/an-update-to-apple-hls-best-practices-end-2024.html));
  the page itself could not be read in this session, so check it before writing the profile.
- hls.js: fragmented MP4 with H.264, and HEVC, AV1 and VP9 "subject to runtime support"; AAC,
  MP3, AC-3, and E-AC-3, FLAC, Opus and ALAC likewise; WebVTT, IMSC1 (text profile) and CEA-608/708;
  `EXT-X-MAP`, `EXT-X-BYTERANGE`, `EXT-X-DISCONTINUITY`, live and DVR playlists; and "All HLS
  resources must be delivered with CORS headers permitting GET requests"
  ([hls.js README](https://github.com/video-dev/hls.js/blob/master/README.md)). What the LG CX's
  build of it decodes through webOS is not published; the probes in step 8 find out.

The first profile is the intersection: H.264, HEVC, AAC-LC, HE-AAC, AC-3, E-AC-3, WebVTT and
IMSC1. Anything else is dropped from the translation, and the LG and a Mac get the same playlist
until a receiver proves to take more (CONTRIBUTING's rule: the choice is made once, from the TXT
record, never by retrying).

## Architecture

The session and the `/command` protocol do not change: the receiver sees an HLS stream or a
file. What is new is a step before `load` sends the item, and an HTTP server behind it.

1. An **origin** in `:core`: a small HTTP/1.1 server the receiver fetches from.
2. A **resolver** list on the client: each turns a `Media` the receiver cannot fetch into one it
   can, publishing what it needs on the origin. `load` runs them.
3. `MediaFormat.Dash`, and local files as `Media`.
4. The **DASH translator**, a resolver.
5. Later, Android modules that remux or transcode, as resolvers an app adds.

[architecture.md](../architecture.md#seams) lists "a local file or DASH stream the sender serves
over HTTP" under the `AirkastSession` seam, as another session implementation picked in
`connect`. This design departs from that: the protocol to the receiver is the same AirPlay video
v2 session, only the item changes, and the choice depends on the item, which `connect` does not
see. The passage changes with the first PR, as CONTRIBUTING asks.

### Level 1: `Media`, `Origin`, `MediaResolver`

```kotlin
public enum class MediaFormat(internal val wire: String?) {
    Hls("streaming"),
    File("file"),

    /**
     * An MPEG-DASH presentation (`.mpd`, `application/dash+xml`). No receiver loads one: a
     * resolver on the client ([Airkast.Builder.resolvers]) turns it into [Hls] that the sender
     * serves. Without one, `load` throws [AirkastException.UnsupportedMedia] at once.
     */
    Dash(null),
}
```

`MediaFormat.of` reads `.mpd` and `application/dash+xml` as `Dash`. A `file:` or `content:`
URL keeps the format its extension or MIME type gives, and a resolver serves it.

```kotlin
/**
 * What a receiver fetches from the sender: an HTTP server on the address the control connection
 * uses, started when something is first published and stopped when nothing is. Every published
 * path sits under a random prefix, so another device on the network cannot guess it.
 */
public interface Origin {
    /** Publishes [resource] under [name] and returns the URL the receiver fetches it at. */
    public fun publish(name: String, resource: Resource): String

    public fun withdraw(url: String)
}

/** Bytes the origin serves. [open] runs on every request, so a live playlist can change between two. */
public interface Resource {
    public val contentType: String

    /** The length in bytes, or null when it is not known ahead, as for a proxied stream. */
    public val length: Long?

    /** The bytes, or the [range] of them the receiver asked for, which a file and a byte-range segment need. */
    public fun open(range: LongRange?): InputStream

    public companion object {
        public fun text(contentType: String, text: () -> String): Resource
        public fun file(file: File, contentType: String): Resource
        public fun proxy(url: String, headers: Map<String, String> = emptyMap()): Resource
    }
}

/** Turns a [Media] the receiver cannot fetch into one it can. Null when it does not handle [media]. */
public interface MediaResolver {
    public suspend fun resolve(media: Media, receiver: Receiver, origin: Origin): ResolvedMedia?
}

/** What the receiver loads in place of what the app gave, and what goes with it. Closed when the item is replaced or the session ends. */
public interface ResolvedMedia : AutoCloseable {
    public val media: Media

    /** The renditions of a master the resolver wrote, with the ids the receiver will use. Empty when it wrote none. */
    public val renditions: List<Rendition>
}
```

`load(item)` asks each resolver in order and takes the first `ResolvedMedia`; the receiver gets
its `media`. The session keeps it with its `Loaded` state and closes it on the next `load`, on
`stop` and on `close`. `renditions()` returns the resolver's list when it has one, and reads the
master over HTTP as today otherwise; the ids are right because airkast wrote the master in the
order `HlsMaster.renditions` counts, so `selectTrack` and the media3 track dialogs switch a DASH
stream's audio and subtitles as they switch an HLS stream's. A `Dash` item with no resolver, an
MPD with `ContentProtection`, or one with no representation in the receiver's profile fails
with `AirkastException.UnsupportedMedia`, whose message says which; an MPD or `sidx` that cannot
be read fails with `AirkastException.ManifestUnreadable`. Both come out of `load`, so
`Event.LoadFailed` reports them. A resolve may fetch: the MPD, then one `sidx` box per
representation for `SegmentBase` presentations. Each fetch takes `requestTimeout`, as the master
fetch does, and the time counts in `Event.Loaded.took`.

The origin serves in the clear, over HTTP on the local network, as a `file` item from an `http`
CDN does. The random prefix keeps a path from being guessed, not from being read off the air; the
guide says so. It answers only published paths, with 404 for the rest, sends
`Access-Control-Allow-Origin: *` on everything, answers `OPTIONS`, `HEAD` and `Range` (206 and
416), and takes several connections at once, since AVFoundation and hls.js open several. It is
bound on every interface and the URL carries the control connection's local address, the one the
receiver can reach; an app whose phone has two networks gets the right one without a bind.

### Level 2: `Airkast`

```kotlin
public class Builder {
    /** Run by `load`, in order, for an item the receiver cannot fetch as given. */
    public var resolvers: MutableList<MediaResolver> = mutableListOf(DashResolver(), LocalFileResolver())
}
```

`Airkast(context)` adds a resolver for `content:` URIs, which needs a `ContentResolver`, as it
adds the socket factory and the credential store. `copy { }` copies the list. The origin is the
client's: one server, started on the first publish, stopped once the last `ResolvedMedia`
withdraws, so a client with no served item holds no port. No `Airkast` lifecycle call is added
for it.

Where the translator lives is the main open decision. The MPD parser needs XML, and
`javax.xml.parsers` (DOM) is in the JDK and in Android since API 1, so a translator in `:core`
needs no dependency, which CONTRIBUTING requires of `airkast-core`, and every app gets DASH with
no line of setup, which is the point of the three levels. Against it: `:core` is "the protocol",
and a translator with live and multi-period handling is the largest piece of code outside the
session. A `:dash` artifact (plain JVM, depending on `:core`) keeps `:core` small at the price of
one line in every app and a default that `Airkast(context)` cannot add, since modules depend only
on `:core`. The remux and transcode resolvers need media3 and MediaCodec, so they are an Android
module either way, added by the app as `AirkastAudioSink` is.

### Level 3: `AirkastPlayer`

No API change. A `MediaItem` with a `.mpd` URI or `application/dash+xml` MIME type becomes a
`Dash` `Media` and resolves at load. With a `localPlayer`, ExoPlayer plays the DASH itself (the
app adds `media3-exoplayer-dash`), and the TV gets the translated HLS at the same position; a
`content:` item plays on the phone and is served to the TV the same way. `ReceiverTracks` reads
`renditions()`, which the resolver fills, so track selection works unchanged. The player already
holds the wake and Wi-Fi locks while an item is on the receiver, which the origin needs for as
long as the TV fetches; an app that drives a session itself holds them itself, as the guide says
for the keepalive.

### The translator

DASH to HLS, as the HLS playlists Apple publishes are shaped (its `img_bipbop_adv_example_fmp4`
example: a master with `EXT-X-INDEPENDENT-SEGMENTS`, `CODECS`, `RESOLUTION`, `FRAME-RATE`,
`AUDIO` and `SUBTITLES` groups; media playlists at `EXT-X-VERSION:7` with
`EXT-X-MAP:URI=…,BYTERANGE=…` and `EXT-X-BYTERANGE` per segment; read 2026-10-19).

- **Master.** One `EXT-X-STREAM-INF` per video representation kept, with `BANDWIDTH`, `CODECS`
  (the video codec and the audio group's), `RESOLUTION`, `FRAME-RATE`, and the audio and subtitle
  group ids. One `EXT-X-MEDIA` per audio and subtitle adaptation set, with `LANGUAGE`, `NAME`,
  `DEFAULT` and `AUTOSELECT` from `Role main`, `FORCED` from `Role forced-subtitle`, and
  `CHARACTERISTICS` from `Accessibility`. Audio sets with different codecs become different
  groups, as Apple's `aud1` (AAC) and `aud2` (AC-3) are.
- **Media playlists.** From `SegmentTemplate` with `duration` or a `SegmentTimeline`
  (`S@t`, `@d`, `@r`), from `SegmentList`, or from `SegmentBase`, whose `sidx` the phone fetches
  once per representation and turns into byte ranges. `$RepresentationID$`, `$Number$`, `$Time$`
  and `$Bandwidth$` with their format widths. `BaseURL` at the MPD, period, adaptation set and
  representation levels. `presentationTimeOffset`. A new period is an `EXT-X-DISCONTINUITY` with
  a new `EXT-X-MAP`. Segment URLs are the CDN's in manifest-only mode and the origin's in proxy
  mode.
- **Live.** The available segments come from `availabilityStartTime`, `timeShiftBufferDepth`,
  `suggestedPresentationDelay` and the clock, corrected by `UTCTiming` when the MPD names one;
  the playlist is rebuilt per request, `EXT-X-MEDIA-SEQUENCE` is the first segment's number,
  `EXT-X-PROGRAM-DATE-TIME` lets the receiver seek by time, the MPD is fetched again every
  `minimumUpdatePeriod`, and `EXT-X-ENDLIST` closes it when the MPD turns static. Low-latency
  DASH is served as ordinary live.
- **Modes.** Manifest-only when every segment URL is `http` or `https` and the item carries no
  headers; proxy when it does, or when the resolver is told to. Whether a CDN sends CORS headers
  cannot be known ahead, so an app that knows its CDN does not sets proxy mode.
- **Dropped.** Representations outside the profile, trick-mode and thumbnail adaptation sets,
  event streams, `xlink` periods. `ContentProtection` fails the load, since a stream that is
  protected in one representation is protected in all.

Shaka's Angel One (`shaka-demo-assets/angel-one/dash.mpd`, read 2026-10-19) is the fixture that
exercises most of this at once: one period, `SegmentBase` with `indexRange` everywhere, H.264
and VP9 video, AAC and Opus audio in several languages, `wvtt` subtitles in MP4, and a CDN that
sends `access-control-allow-origin: *`. The owner already used its HLS packaging for the media3
tracks check, so the renditions the translation lists can be compared against those.

### Threads, failure and the network

- The origin runs one accepting thread and one per connection, all daemon, blocking I/O only.
  Nothing it does reaches the caller's thread, and nothing the receiver sends it reaches the
  session: a bad request gets a 4xx and the connection closes.
- A resolver runs under `load`'s `withContext(Dispatchers.IO)`, as the master fetch does. What it
  throws is an `AirkastException`.
- The phone has to stay reachable for the whole item. A phone that leaves the Wi-Fi or sleeps
  stalls the TV in the middle of a segment; what the LG's hls.js then reports, and after how
  long, is a thing to observe (step 6), and the player treats it as it treats any
  `StateChanged`.
- Manifest-only mode costs the phone a few kilobytes per playlist. Proxy mode costs the stream's
  bitrate down from the CDN and up to the TV at once; a local file costs its bitrate up. A
  1080p stream at 8 Mbit/s both ways is within Wi-Fi 5, and worth a note in the guide for a
  crowded 2.4 GHz network.

### Tests

- **Origin.** A loopback client fetches text, a file with ranges (206, 416), an unpublished path
  (404), sees the CORS headers, runs two connections at once, and finds the server gone once the
  last resource is withdrawn.
- **Translator.** Saved MPDs as fixtures, with the playlists expected from each, compared as text:
  Angel One (`SegmentBase`, mixed codecs, `wvtt`), DASH-IF `testpic_2s` (`SegmentTemplate`,
  dynamic), a `SegmentTimeline` sample and a multi-period sample. `HlsMaster.renditions` on the
  master written gives the ids the resolver reports. Template substitution and `BaseURL`
  resolution case by case. A `sidx` parsed against bytes saved from Angel One. `ContentProtection`
  refused.
- **Session.** `FakeReceiver` sees `insertPlayQueueItem` with `streaming` and a `Content-Location`
  on the origin, `renditions()` answers from the resolver, and the resolved item closes on the
  next load.
- **Live.** On a real receiver only (CONTRIBUTING, "A change to what goes over the wire").

## What to verify

In this order. Steps 1 and 3 need no new code on the phone side beyond a test server.

1. **Fragmented MP4 HLS on the LG CX and the Mac.** Cast Apple's
   `img_bipbop_adv_example_fmp4/master.m3u8` from the sample app. If the LG does not play it,
   the translation serves Macs and Apple TVs only, and the LG needs a remux to MPEG-TS on the
   phone, a different and heavier job.
2. **Serving from the sender.** A hand-written playlist, and an MP4 as a `file` item, served from
   the phone over plain HTTP with `Access-Control-Allow-Origin: *`. Does the LG's hls.js fetch
   from an `http://` origin, and seek through the file with ranges; does the Mac. From a desktop
   JVM under WSL this needs the Hyper-V inbound TCP rule, so the phone is the easier sender.
3. **Angel One, manifest-only.** Play, seek, switch audio language and subtitles; compare the
   renditions with the HLS packaging's. Then the same with the segments proxied through the phone.
4. **Live.** DASH-IF `livesim2/testpic_2s`: the live window, and what the LG does on pause and
   seek in a live playlist.
5. **A Mac's strictness.** `mediastreamvalidator` on the playlists written, on the owner's Mac.
6. **A whole episode from the phone**, proxied, with the screen off: battery, heat, stalls, and
   what the receiver reports when the phone drops off the network.
7. **The mirroring probe**, optional, the one measurement that could reopen mirroring: pair, base
   SETUP, then a SETUP with a type-110 stream and no `fp-setup`, no `ekey`, on the LG CX and the
   Mac. A reply with a `dataPort` keeps the door open; an error, or no stream in the reply,
   closes it, and the README keeps saying airkast does not mirror.
8. **Codec probes on the LG**, through translated manifests: HEVC, E-AC-3, and AV1, which the CX
   decodes in its own apps but may not through its AirPlay receiver's player.

## Decisions

Proposed, for the owner to take:

1. **Mirroring is out** until step 7 says otherwise.
2. **The origin and the DASH translator go in `:core`**, with no new dependency; remux and
   transcode go in an Android module later, as resolvers an app adds.
3. **Resolvers run inside `load`**, not in `connect`, and the architecture page's seam text
   changes to match.
4. **A `.mpd` fails fast first**: `MediaFormat.Dash` and `UnsupportedMedia` can land as a small
   PR before the translator, so a DASH item fails at `load` instead of at `loadTimeout`.

Open:

5. **Names**: `Origin`, `Resource`, `MediaResolver`, `ResolvedMedia`, `UnsupportedMedia`,
   `ManifestUnreadable`, and whether `MasterUnreadable` becomes the latter, since both say the
   same thing about a different manifest.
6. **`Media.headers`** for a stream the app fetches with a token, used by the resolvers in proxy
   mode and by the master fetch, which ignores the app's headers today.
7. **The default mode**: manifest-only, cheaper and blind to a CDN without CORS; or proxy, sure
   and twice the bandwidth.
8. **One origin per client or per session**: one per client is less to start and stop; one per
   session would let each bind to its receiver's interface alone.
9. **A codec profile per receiver family now**, from `model` and `srcvers`, or one common profile
   until step 8 shows a difference.

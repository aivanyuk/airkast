# Multi-room audio on the realtime path

A design draft, 2026-10-17. Nothing here is built or verified yet; each claim about a receiver
names where it comes from, and [What to verify](#what-to-verify) lists what has to be measured
before the design is settled. Read [architecture.md](../architecture.md#one-audio-stream) for how
one audio stream works today; this page builds on it.

## What it is for

An app plays one track on several AirPlay receivers at once, in step, the way the iPhone's AirPlay
picker lets a user tick a kitchen speaker and a living-room soundbar together. The user adds a
receiver while the music plays, takes one away, and sets each one's volume; the app's own player
keeps decoding, as it does for one speaker today.

Receivers to build and test against: the Samsung HW-Q990B (plays airkast's stream today), the LG CX
(advertises AirPlay audio; airkast has not streamed to it yet) and a Mac (`NeedsPassword`; not
streamed to yet either).

## Why the realtime path

AirPlay 2 has two ways to send audio:

| | Realtime (type 96) | Buffered (type 103) |
| --- | --- | --- |
| Transport | RTP over UDP, about `audioLatency` (1 s) ahead | TCP, tens of seconds ahead |
| Timing | NTP: the receiver asks the sender for the time over UDP | PTP: the sender is a PTP clock, on UDP ports 319 and 320 |
| What airkast has | `DefaultAudioStream`, playing on the Samsung HW-Q990B | Nothing |
| What an iPhone (iOS 27.0.1) sent to a receiver that offered both | Not used | Both Apple Music and YouTube: AAC-LC, 1024 frames a packet, PTP, the iPhone's own clock as the timeline (captured 2026-10-17 with openairplay/airplay2-receiver) |

Apple's own multi-room runs on the buffered path. airkast cannot follow it on Android yet: a PTP
clock listens on ports 319 and 320, which an app cannot bind without root, and the sender is the
clock the receivers follow. The realtime path has what a group needs already, as the next section
says, and owntone streams realtime audio to several AirPlay receivers at once this way. The group
API below does not depend on the path, so a buffered implementation later changes how packets go
out, not what the app calls.

## How the receivers stay in step

Each stream today maps its RTP clock to the sender's NTP clock with a sync packet every second
(`Rtp.sync`): the frame at RTP time *P* plays at NTP time *T*. The receiver sets its clock from the
sender's `TimingResponder` over UDP, so it plays frame *P* at the sender's *T*, not at its own idea
of it.

Two facts make a group out of that:

- Every `TimingResponder` answers from one clock. `ntpNow` and `ntpAt` read a wall-clock epoch
  taken when the class loaded, moved on by `System.nanoTime()`, as companion-level constants. Two
  responders on two ports give two receivers the same time.
- The sync packets of every member can carry the same mapping. One timeline, one `anchor`, one
  `rtpAt(now)`, sent to each receiver with its own key and SSRC: each receiver plays frame *P* at
  the same *T*.

So the receivers of a group are in step as far as each one follows its sync packets, and as far as
its own output pipeline adds the same delay as the others'. The second part is the unknown. A
receiver's pipeline, such as a TV's processing or a soundbar's DSP, adds a delay after "play frame
*P* now" that the sync packet knows nothing about. AirPlay gives a receiver two places to report
it:

- `audioLatencies` in its `GET /info` answer. The LG CX (377.25.06) reports entries for types 100,
  101 and 102, all with `outputLatencyMicros` 0. The Samsung HW-Q990B (366.0) reports none. Both
  read by `AudioProbeTest` on 2026-10-17. The Python receiver the iPhone capture ran against
  reported 400 000 µs, and the iPhone asked for it, so Apple's sender reads it. What the type
  numbers mean, and whether 0 means "none" or "not measured", is not known.
- An `Audio-Latency` header on the RECORD reply, in frames, which shairport-sync sends and
  owntone's sender reads. Neither the Samsung nor the LG sends one: the probe read both RECORD
  replies on 2026-10-17, and each carries `Server` and `CSeq` alone. Nor does either stream SETUP
  reply say more than the two ports.

So neither receiver in the house tells the sender what its output adds. The design gives each
member an **offset** the app sets: how much earlier than the rest that receiver is told to play,
positive for one heard late. The sync packet for that member says frame *P* plays at
*T − offset*; its packets still arrive `audioLatency` ahead, so an offset up to half of that is
safe. What a receiver reports, when one does, is exposed next to it as a starting value, not
applied by itself until the mapping above is understood.

## The API

The three levels keep their roles ([architecture](../architecture.md#three-levels)). A group is an
`AudioStream` with more than one member, not a new type: `AirkastAudioSink` and any other
`AudioRoute` write to it unchanged, and `AirkastPlayer.Connection.Streaming` still holds one
stream.

### Level 1: `AudioStream`

```kotlin
public interface AudioStream : AutoCloseable {
    /** The receiver the stream was opened to. [members] lists the receivers it plays on now. */
    public val receiver: Receiver

    /** The receivers playing this stream, in the order they joined. Empty once the stream ended. */
    public val members: StateFlow<List<Member>>

    /**
     * Pairs with [receiver] as `Airkast.connectAudio` does, with the same [ask] prompt and the
     * same `Airkast.Event`s, and plays this stream on it from the frame playing now. Throws what
     * `connectAudio` throws; the stream and its other members play on.
     */
    public suspend fun add(receiver: Receiver, ask: (suspend (Secret) -> String)? = null): Member

    /** Stops playing on [receiver] and closes its connections. The stream ends when its last member leaves. */
    public fun remove(receiver: Receiver)

    // format, latency, events, isPlaying, position, buffered, write, offer, play, pause, flush,
    // setVolume, volume, setNowPlaying, close: as today. setVolume sets every member's; volume()
    // reads them and returns their mean, or null when none answers.

    public interface Member {
        public val receiver: Receiver

        /**
         * How much earlier than the other members this receiver is told to play: positive for one
         * heard late. Zero by default. Takes effect from the next sync packet, within a second.
         */
        public var offset: Duration

        /** What the receiver says its output adds, from `audioLatencies` or RECORD's `Audio-Latency`, or null. */
        public val reportedLatency: Duration?

        /** This receiver's own events: its remote's buttons, its volume keys, and `Disconnected` when it leaves. */
        public val events: Flow<ReceiverEvent>

        public suspend fun setVolume(volume: Double)
        public suspend fun volume(): Double?
    }
}
```

`Airkast.connectAudio(receiver, format, ask)` is unchanged and returns a stream with one member.
A group starts as one stream plus `add`:

```kotlin
val stream = airkast.connectAudio(soundbar)
stream.add(kitchen)          // suspends while it pairs; the timeline has not started
stream.play()                // both start together, latency from now
stream.add(bedroom)          // later, while playing: joins at the frame playing now
stream.members.value.first { it.receiver == tv }.offset = 120.milliseconds
stream.remove(kitchen)
```

Nothing plays before `play()`, so members added before it start together. One added while the
stream plays joins after its connect (1.2 s to the Samsung from the sample app, compatibility.md)
and is heard once its first packets are due. The stream's own `events` carry every member's `RemoteCommand`
and `VolumeChanged`, so a button on any speaker reaches the app or `AirkastPlayer` as today, and
end with `Disconnected` when the last member has left or the app closed the stream. Why one
member left is on that member's `events`.

No overload takes a list of receivers. Opening several at once is `coroutineScope` and `async`
over `add` in the app, and the player does it for the app (below); a list on `connectAudio` would
have to answer what to return when one of three fails, and `add` answers it per receiver.

### Level 2: `Airkast`

No new builder option. `add` runs the same private `open` flow as `connectAudio`: describe a
receiver typed in by hand, look up credentials, pair, answer a Digest challenge, report
`Event.Connected` or `Event.ConnectFailed`. `Airkast` hands the stream a joiner function for it
when it opens the stream, so `:core`'s stream never holds the client. `Event.SessionEnded` is
reported per member, as it is per stream today.

### Level 3: `AirkastPlayer`

```kotlin
public fun connect(receivers: List<Receiver>, pairWith: Secret? = null)   // all before play
public fun addReceiver(receiver: Receiver, pairWith: Secret? = null)      // while streaming
public fun removeReceiver(receiver: Receiver)
```

`Connection.Streaming(stream)` stays; the UI reads `stream.members`. `Event.CastStarted` and
`CastEnded` keep naming `stream.receiver`, and two events join them: `ReceiverJoined(receiver)`
and `ReceiverLeft(receiver, failure)`. The device volume the player reports follows the last
`VolumeChanged` from any member, as it follows the one speaker today; a group slider that moves
every member is the app's, over `members`.

A receiver added while streaming that asks for a PIN or password needs a prompt while `connection`
is `Streaming`, which the five states cannot show. First cut: `addReceiver` joins receivers that
are paired or pair transiently, and reports `CastFailed(receiver)` for one that asks; the prompt
for a joining receiver is a later change to `Connection`.

## Inside the stream

`DefaultAudioStream` keeps its name and becomes the group. What is one receiver's moves to an
internal `AudioMember`; what is the timeline's stays.

| Stays in the stream, once | Moves to `AudioMember`, one per receiver |
| --- | --- |
| `PcmBuffer`, the written frames | Control connection, event channel, `TimingResponder` and its `reach` |
| The timeline: `anchor`, `nextRtp`, `nextSeq`, `nextFrame`, `rtpAt`, `playedFrame` | Key, SSRC, data and control sockets |
| The sending thread and its wake-up | The control reader that serves retransmit requests |
| The ring of the last 1024 packets, as ALAC frames in the clear with their `seq`, `rtp`, frame and count | The keepalive (`POST /feedback` every two seconds) |
| `play`, `pause`, `flush`, `position`, `buffered` | `offset`, `reportedLatency`, volume, this member's events |

- **Packets.** The sending thread builds each ALAC frame once and seals it once per member, with
  that member's key and the same sequence number and RTP time (`Rtp.audio`). Sealing 1.4 KB with
  ChaCha20-Poly1305 a few hundred times a second is not a cost worth sharing. A sync goes to each
  member's control port, with its `offset` taken off the NTP time.
- **Retransmits.** The ring holds the frame in the clear, and a member's control reader seals it
  again for its own key when its receiver asks. The result is the packet that was sent, since the
  nonce is the sequence number and the header the AAD.
- **Joining while playing.** A member's RECORD names the first packet it will get. For a joiner
  that is the packet at the playhead, not the next one made: after its stream SETUP, the stream
  sends it the first sync, with the extension bit, and then the ring's packets from the playhead to
  `nextRtp`, up to `audioLatency` of audio, the first with the marker bit. It then has what the
  others have, and plays in step from its first packet. Whether the Samsung and the LG take a burst
  of about 125 packets at once is the first thing to verify; the fallback is to start a joiner at
  `nextRtp`, which leaves it silent for `audioLatency`.
- **Pause and flush.** FLUSH goes to every member, and the next timeline starts when the last has
  answered. The `flushes` count becomes the number of answers outstanding. A member whose FLUSH
  fails or times out leaves, so a slow receiver holds the others for at most `requestTimeout`.
- **Volume and now playing.** `setVolume` and `setNowPlaying` fan out in parallel, each member
  getting only what its feature bits say it shows, as today. `volume()` reads every member.
- **Leaving.** An I/O failure on a member's connection, its receiver closing, or `remove` ends
  that member: its sockets close, TEARDOWN goes out when the app removed it, its `events` end with
  `Disconnected` and its reason, `Event.SessionEnded` names it, and `members` drops it. The stream
  plays on for the rest. When the last member leaves, the stream ends as a stream ends today, with
  that member's reason; `close` ends every member with `ClosedByApp`.
- **Threads.** One sending thread per stream, as today; per member, one event-channel reader, one
  control reader and one timing responder, as one stream has today. Sharing one `TimingResponder`
  among members would save a thread and a port each, and needs it to answer several receiver
  addresses; left for later.
- **Network.** Each member costs about 1.45 Mbit/s up from the phone (stereo 16-bit 44.1 kHz ALAC
  in the clear plus RTP and the tag) and one firewall pinhole from `reach`. Three speakers are
  under 5 Mbit/s.
- **Tests.** `AudioStreamTest` gets cases with two `FakeReceiver`s and their `FakeAudio`s: the
  same PCM reaches both, each packet under the same sequence number and RTP time; both get syncs
  that place the same RTP time at the same NTP time, apart by exactly one member's `offset`; a
  joiner receives the ring from the playhead; a member's socket closed mid-stream leaves it and
  the other plays on; FLUSH waits for both.

## What to verify

In this order, each on the desktop JVM against the receivers in the house, with the volume at 0.2.

1. **What the receivers report. Done 2026-10-17.** `AudioProbeTest` in `:core`'s tests pairs, sets
   up a session and a stream without sending a packet, and prints `GET /info`'s `audioLatencies`,
   RECORD's reply headers and the stream SETUP's reply, then tears down:
   `AIRKAST_PROBE_RECEIVERS=host:port,host:port ./gradlew :core:test --tests '*AudioProbeTest'`.
   On the Samsung HW-Q990B and the LG CX: no `Audio-Latency` on RECORD, the LG's `audioLatencies`
   all zero and the Samsung's absent, and the stream SETUP replies holding only `type`, `dataPort`
   and `controlPort`. The LG's base SETUP reply adds `uiPreloaded: true`. Each receiver asked the
   sender's timing port five times in the second before it answered the stream SETUP, and once
   more in the two seconds after, which says nothing yet about how often it asks while playing.
2. **The LG CX and a Mac as stream receivers.** Neither has played airkast's realtime stream.
   `LiveAudioTest` against each, alone. The LG's `audioLatencies` of zero against what is heard is
   the first offset measurement.
3. **Two receivers, one timeline.** The Samsung and the LG from one stream, a test tone with a
   click each second. By ear from between them: an echo means an offset is needed, and the offset
   that removes it is the LG's number. A recording of both would give the number in milliseconds,
   if wanted.
4. **A joiner's burst.** Add the second receiver while the first plays, and listen for whether it
   comes in on the beat, late by `audioLatency`, or not at all.
5. **An hour.** Whether two receivers drift apart over an hour, which says whether the receivers
   keep following the syncs or need something more.

## Later: buffered audio and PTP

Apple's groups run on buffered audio with PTP, and the capture shows every iPhone app getting
it. What carries over from this design when that path comes: `members`, `add`, `remove`, `offset`,
the per-member events and the player's API. What changes: the packets (AAC or ALAC over TCP, far
ahead), the clock (a PTP master, which on Android needs a way around ports 319 and 320, or a
receiver as the master) and `SETRATEANCHORTIME` in place of the sync packets. The capture's
details are in [compatibility.md](../compatibility.md#audio-streams), when they land there.

## Decisions

Taken on 2026-10-17:

1. **`AudioStream.receiver` stays** as "the receiver the stream was opened to", unchanging, so the
   player's events have a name for the cast.
2. **`volume()` on a group** is the mean of the members that answer.
3. **A PIN prompt for a receiver joining a cast** is out of the first cut; it needs a `Connection`
   state that shows a prompt while streaming goes on.

Still open:

4. **Offsets applied by default.** Zero until the mapping of `audioLatencies` and `Audio-Latency`
   to what is heard is understood; then `reportedLatency` could become the default.
5. **A joiner's start.** From the playhead with a burst, if the receivers take it; else from the
   next packet, `audioLatency` late.

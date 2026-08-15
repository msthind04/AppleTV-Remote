# Apple TV Remote for Android

A native Android remote for modern Apple TVs, speaking Apple's **Companion Link**
protocol directly. No companion server, no Python bridge — the protocol is
implemented in Kotlin and runs on the phone.

Verified against a real **Apple TV 4K (AppleTV14,1) running tvOS 26.6**.

## Why this is non-trivial

Apple publishes no API for controlling an Apple TV. Since tvOS 15 the legacy
DMAP/DAAP protocol is gone entirely, and the only route in is Companion Link:

1. **Discovery** — mDNS on `_companion-link._tcp`.
2. **Pair-setup** — HomeKit-style SRP-6a (3072-bit, SHA-512) keyed by the 4-digit
   PIN the TV displays, ending in an exchange of Ed25519 long-term keys.
3. **Pair-verify** — Curve25519 ECDH plus Ed25519 signatures on every connect,
   deriving per-session ChaCha20-Poly1305 keys.
4. **Commands** — OPACK-serialized frames over the encrypted channel.

## Layout

| Module | What it is |
|---|---|
| `protocol/` | Pure Kotlin/JVM. No Android dependencies. All crypto and wire format. |
| `cli/` | Desktop harness for pairing and testing against a real device. |
| `app/` | Android app (Jetpack Compose, Material 3). |

The protocol lives in a plain JVM module on purpose: it makes the risky part —
crypto and byte framing — testable from a terminal against real hardware,
instead of only through an emulator.

### Inside `protocol/`

```
opack/Opack.kt          Apple's OPACK binary serialization (encoder + decoder)
hap/Tlv8.kt             TLV8 records used by the pairing handshakes
hap/Credentials.kt      Long-term pairing keys; serialize/parse
crypto/Srp.kt           SRP-6a client (3072-bit group, SHA-512)
crypto/Primitives.kt    HKDF, Ed25519, X25519, ChaCha20-Poly1305
companion/CompanionConnection.kt   TCP framing + transparent encryption
companion/CompanionClient.kt       Pair-setup, pair-verify, request/response
companion/AppleTvRemote.kt         High-level commands
discovery/Discovery.kt             Platform-neutral device model
```

## Correctness

The crypto is validated against vectors generated from
[pyatv](https://github.com/postlund/pyatv), a reference implementation known to
interoperate with real devices. `protocol/src/test/` asserts byte-for-byte
equality for OPACK, TLV8, HKDF, ChaCha20-Poly1305 and the full SRP exchange.

```bash
./gradlew :protocol:test
```

This caught three bugs that would have been miserable to debug against live
hardware:

- **Nonce truncation.** Kotlin masks `Long` shifts to 6 bits, so building a
  12-byte counter nonce with `counter shr (8 * i)` wrapped at `i == 8` and
  leaked counter bytes into the high nonce bytes. Only the very first message
  of a session was unaffected.
- **SRP salt encoding.** The salt must be hashed as the raw bytes the device
  sent. Round-tripping it through an integer drops a leading zero byte and
  breaks the proof — for roughly 1 in 256 pairings.
- **Empty TLV8 values** emit no record at all rather than a zero-length one.

## Building

Requires JDK 21 (Android Gradle Plugin does not support JDK 25) and the Android
SDK with platform 35.

```bash
./gradlew :app:assembleDebug      # installable debug APK
./gradlew :app:assembleRelease    # minified, ~3 MB (unsigned)
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## CLI usage

Useful for testing the protocol without a phone.

```bash
./gradlew :cli:installDist

./cli/build/install/cli/bin/cli scan
./cli/build/install/cli/bin/cli pair 192.168.2.188 49153
./cli/build/install/cli/bin/cli apps 192.168.2.188 49153
./cli/build/install/cli/bin/cli key  192.168.2.188 49153 home
./cli/build/install/cli/bin/cli volume 192.168.2.188 49153
```

Set `ATV_DEBUG=1` to log every frame in both directions — protocol faults are
otherwise silent, since a frame that fails to decode simply never resolves its
waiter.

Credentials are written to `~/.config/appletv-remote/`.

## Pairing notes

The PIN expires quickly. If pair-setup stalls between the M2 and M3 messages for
more than a minute or so, the device stops responding and the attempt times out;
start over to get a fresh PIN.

Credentials grant full control of the Apple TV. On Android they live in
app-private storage with backups disabled; on desktop they are a plain file in
`~/.config`.

## Status

Working:

- Device discovery (NsdManager on Android, jmDNS on desktop)
- Pairing and reconnect
- D-pad, select, menu, home, and a play/pause button that reflects the
  current playback state
- A touch surface switchable between D-pad taps and trackpad swiping
- App listing and launching
- Text entry, with the keyboard auto-presenting when the TV focuses a field
- Now-playing: title, artist, album, artwork, playback state and active app,
  with 10-second skip back / forward controls

### Touch surface

The central pad works in one of two modes, chosen with the toggle above it:

- **D-pad** — a tap in each quadrant sends a direction, a tap in the centre
  selects.
- **Swipe** — dragging sends interpolated touch samples so tvOS lists scroll
  with momentum; a tap still selects.

They are deliberately not combined. Compose's tap and drag detectors compete
for the same pointer stream, so with both active a slow swipe often registered
as a directional press instead.

### Volume

Volume controls are hidden unless the Apple TV affirmatively reports the
capability, via the `Volume` bit (`0x0100`) of the `_mcF` field in an `_iMC`
event. This mirrors pyatv, which likewise treats controls as unavailable until
the device says otherwise.

Two consequences worth knowing:

- An idle Apple TV often sends no `_iMC` event at all, so volume stays hidden
  until something is playing.
- On a setup where the Siri Remote drives volume over **infrared**, the remote
  emits IR itself and the Apple TV is never in the path. No network client can
  change the volume, and the device correctly never advertises the capability.
  `_mcc GET_VOLUME` still returns a number in that case, but it is the Apple
  TV's internal level and changing it does nothing audible — which is why the
  UI trusts the capability flag rather than the value.

Volume is expected to work on HDMI-CEC setups, where the Apple TV can relay it.

### Text entry

The RTI channel does not use OPACK. Its payloads are NSKeyedArchiver archives —
binary property lists with UID cross-references — so `plist/BinaryPlist.kt`
implements a reader and writer for the `bplist00` format, and
`companion/RtiPayloads.kt` builds the two archives tvOS expects.

The keyboard presents itself automatically when the Apple TV focuses a text
field, matching the first-party remote. The device pushes `_tiStarted` and
`_tiStopped` events; focus is indicated by the presence of a `_tiD` archive in
the payload. A field that is already focused when the app connects produces no
event at all, so the initial state is seeded from the `_tiStart` response
instead.

Two subtleties this has to handle:

- Sending text itself performs a `_tiStop`/`_tiStart` round trip, which makes
  the device emit focus events. Those are echoes of our own request rather than
  real focus changes, so they are suppressed while a text operation is in
  flight; otherwise the keyboard would dismiss itself on every send.
- The panel pre-populates with whatever the field already contains, read from
  the device rather than tracked locally.

Sending text is a three-step exchange: restart the RTI session with
`_tiStop`/`_tiStart`, read the session UUID and existing contents out of the
`_tiD` archive the device returns, then push a `_tiC` event carrying an
insertion or clear archive. Because each send re-reads authoritative state,
text is pushed on submit rather than per keystroke.

One non-obvious encoding detail: the plist writer must deduplicate equal
scalars, collapsing repeats such as the three `"NSObject"` strings into a
single object. Without that the archive is structurally valid but byte-different
from what CoreFoundation produces, and tvOS rejects it silently.

### Now playing

From tvOS 15 onward, now-playing metadata is only available by tunnelling the
Media Remote Protocol through AirPlay. That means a second, independent HAP
pairing on port 7000 with its own PIN — the app offers it as an optional extra
step rather than requiring it up front.

Bringing the tunnel up is a fixed sequence:

1. Pair-verify on the AirPlay control connection (port 7000), after which the
   whole connection is wrapped in HAP block encryption.
2. `SETUP` with `isRemoteControlOnly` to obtain an event port, then connect the
   event channel. Nothing useful arrives on it, but the receiver will not
   proceed without it, and it must answer every request with a 200.
3. `RECORD`.
4. `SETUP` again with a stream descriptor to obtain a data port, then connect
   the data channel. A random 64-bit seed is echoed into the key-derivation
   salt so the keys are bound to that stream.
5. MRP handshake over the data channel: `DEVICE_INFO` first — the device stays
   completely silent until it arrives — then `SET_CONNECTION_STATE` and
   `CLIENT_UPDATES_CONFIG`.

Seeking uses the Companion media-control channel (`_mcc` with `SkipBy`) rather
than MRP, since that path is already open and authenticated.

One trap: OPACK integers are unsigned, so a negative offset must be sent as a
double. Encoding `-10` as an integer silently wraps to `246` and seeks the
wrong way by four minutes. `Opack.pack` now rejects negative integers outright
instead of corrupting them.

The data channel nests three framings: a 32-byte big-endian header, a binary
plist, and finally `params.data` holding varint-length-prefixed protobufs.

`mrp/Protobuf.kt` is a generic wire-format codec rather than generated sources.
MRP defines dozens of message types and now-playing touches about six fields
across four of them, so hand-reading the wire format avoids putting protoc into
an Android build and tolerates unknown fields from newer tvOS revisions.

Two things that are easy to get wrong here:

- **Track metadata is not in `nowPlayingInfo`.** That field is essentially
  always absent in practice. Real titles and artists arrive in
  `SetStateMessage.playbackQueue.contentItems`, indexed by the queue's
  `location`.
- **`PlayerPath.client` is field 2, not 1** — field 1 is `origin`. Reading
  field 1 yields the device's own name for every app, which silently collapses
  all players into a single entry and makes one app's metadata leak onto
  another. State is tracked per bundle identifier, and only the client named by
  `SET_NOW_PLAYING_CLIENT` is published.

## Legal

Companion Link is an undocumented Apple protocol; this is a clean-room-style
implementation built from public reverse-engineering work. It controls hardware
you own, on your own network.

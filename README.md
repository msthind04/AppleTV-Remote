# Remote for Apple TV — Android

An open-source **Apple TV remote for Android**. It speaks Apple's Companion
Link protocol natively in Kotlin, so there is no companion server, no Python
bridge, and nothing to run on a Raspberry Pi — just an APK on your phone.

Works with modern Apple TVs, where older DMAP-based remote apps no longer do.

> **Not affiliated with Apple Inc.** "Apple", "Apple TV", "AirPlay" and "Siri"
> are trademarks of Apple Inc., registered in the U.S. and other countries.
> They are used here solely to describe compatibility.

## Features

- **Discovery** — finds Apple TVs on your network automatically
- **D-pad** and a switchable **trackpad** for swipe scrolling
- **Menu, Home, Play/Pause** — the play button reflects real playback state
- **App launcher** — list and open any installed tvOS app
- **Text entry** — type into search boxes from your phone's keyboard, which
  appears automatically when the TV focuses a text field
- **Now playing** — title, artist, album, artwork and playback state, with
  10-second skip controls

## Install

Download the latest APK from [Releases](../../releases) and install it. You
will need to allow installation from unknown sources.

Requires **Android 8.0 (API 26)** or newer, on the same Wi-Fi network as the
Apple TV.

## Pairing

1. Open the app and tap your Apple TV.
2. Enter the 4-digit code shown on the TV. **The code expires quickly** — if
   pairing stalls, cancel and try again for a fresh code.
3. Optionally tap **Show what's playing** to enable now-playing, which needs a
   second, independent pairing with its own code.

Credentials are encrypted with a key held in the Android Keystore. They grant
full control of the Apple TV, so treat them like a password.

## Compatibility

Verified end-to-end against an **Apple TV 4K (AppleTV14,1) running tvOS 26.6**.

The Companion Link protocol has been stable since tvOS 13, so other models and
versions from that era onward are expected to work — but they are untested. If
you try one, please open an issue either way: reports of what works are as
useful as bug reports.

Apple TV 3rd generation and earlier are **not** supported. They use the older
DMAP protocol, which modern devices have dropped entirely.

### Volume

Volume controls appear only when the Apple TV reports it can route volume, via
the `Volume` bit of the `_mcF` field in an `_iMC` event.

If your Siri Remote changes volume over **infrared**, the remote emits IR
itself and the Apple TV is never in the path — so no network client can change
the volume, and the device correctly never advertises the capability. Volume is
expected to work on HDMI-CEC setups. An idle Apple TV often sends no `_iMC`
event at all, so the controls may stay hidden until something is playing.

## Why this is non-trivial

Apple publishes no API for controlling an Apple TV. Since tvOS 15 the legacy
DMAP/DAAP protocol is gone entirely, and the only route in is Companion Link:

1. **Discovery** — mDNS on `_companion-link._tcp`.
2. **Pair-setup** — HomeKit-style SRP-6a (3072-bit, SHA-512) keyed by the
   4-digit PIN the TV displays, ending in an exchange of Ed25519 long-term keys.
3. **Pair-verify** — Curve25519 ECDH plus Ed25519 signatures on every connect,
   deriving per-session ChaCha20-Poly1305 keys.
4. **Commands** — OPACK-serialized frames over the encrypted channel.

Now-playing needs more still: a second pairing on port 7000 and an
MRP-over-AirPlay tunnel, described below.

## Layout

| Module | What it is |
|---|---|
| `protocol/` | Pure Kotlin/JVM. No Android dependencies. All crypto and wire format. |
| `cli/` | Desktop harness for pairing and testing against a real device. |
| `app/` | Android app (Jetpack Compose, Material 3). |

The protocol lives in a plain JVM module on purpose: it makes the risky part —
crypto and byte framing — testable from a terminal against real hardware,
instead of only through an emulator.

```
opack/Opack.kt          Apple's OPACK binary serialization
hap/Tlv8.kt             TLV8 records used by the pairing handshakes
hap/HapPairing.kt       Transport-agnostic pair-setup and pair-verify
crypto/Srp.kt           SRP-6a client (3072-bit group, SHA-512)
crypto/Primitives.kt    HKDF, Ed25519, X25519, ChaCha20-Poly1305
plist/BinaryPlist.kt    bplist00 reader/writer with UID support
companion/              Framing, session, commands, RTI text input
airplay/                HAP channels, AP2 session, MRP data stream
mrp/                    Protobuf wire codec and now-playing model
```

## Building

Requires **JDK 21** (Android Gradle Plugin does not support JDK 25) and the
Android SDK with platform 35.

```bash
./gradlew :app:assembleDebug      # installable debug APK
./gradlew :app:assembleRelease    # minified, ~3 MB
./gradlew :protocol:test          # conformance tests
```

To log every protocol frame while debugging:

```bash
./gradlew :app:assembleDebug -PwireLogging=true
```

This is off by default because it writes frame contents, including pairing
traffic, to logcat.

## CLI

Useful for testing the protocol without a phone.

```bash
./gradlew :cli:installDist

./cli/build/install/cli/bin/cli scan
./cli/build/install/cli/bin/cli pair          <host> 49153
./cli/build/install/cli/bin/cli pair-airplay  <host>
./cli/build/install/cli/bin/cli apps          <host> 49153
./cli/build/install/cli/bin/cli key           <host> 49153 home
./cli/build/install/cli/bin/cli text          <host> 49153 "search term"
./cli/build/install/cli/bin/cli nowplaying    <host>
```

Set `ATV_DEBUG=1` to log every frame in both directions. Credentials are
written to `~/.config/appletv-remote/`.

## Correctness

The crypto and serialization are validated byte-for-byte against vectors
generated from [pyatv](https://github.com/postlund/pyatv), a reference
implementation known to interoperate with real devices. Writing these layers
against a specification alone is how subtle interop bugs get shipped.

```bash
./gradlew :protocol:test
```

See [`tools/`](tools/) for regenerating the vectors.

This approach caught several bugs that would have been painful to diagnose
against live hardware:

- **Nonce truncation.** Kotlin masks `Long` shifts to 6 bits, so building a
  12-byte counter nonce with `counter shr (8 * i)` wrapped at `i == 8` and
  leaked counter bytes into the high nonce bytes. Only the very first message
  of a session was unaffected.
- **SRP salt encoding.** The salt must be hashed as the raw bytes the device
  sent. Round-tripping it through an integer drops a leading zero byte and
  breaks the proof — for roughly 1 in 256 pairings.
- **Non-canonical plist output.** CoreFoundation deduplicates equal scalars, so
  repeated strings such as `"NSObject"` collapse to one object. Without that the
  archive is structurally valid but byte-different, and tvOS rejects it silently.
- **Unsigned OPACK integers.** Encoding `-10` wraps it to `246`, which would
  seek four minutes the wrong way. `Opack.pack` now refuses negative integers.

## Protocol notes

### Text entry

The RTI channel does not use OPACK. Its payloads are NSKeyedArchiver archives —
binary property lists with UID cross-references — so `plist/BinaryPlist.kt`
implements the `bplist00` format and `companion/RtiPayloads.kt` builds the two
archives tvOS expects.

The keyboard presents itself automatically when the Apple TV focuses a text
field, via `_tiStarted` / `_tiStopped` events. A field already focused when the
app connects produces no event, so the initial state is seeded from the
`_tiStart` response instead. Sending text itself performs a `_tiStop`/`_tiStart`
round trip, and those echoes are suppressed so the keyboard does not dismiss
itself on every send.

### Now playing

From tvOS 15 onward, now-playing metadata is only available by tunnelling the
Media Remote Protocol through AirPlay:

1. Pair-verify on the AirPlay control connection (port 7000), after which the
   connection is wrapped in HAP block encryption.
2. `SETUP` with `isRemoteControlOnly` to get an event port, then connect the
   event channel. Nothing useful arrives on it, but the receiver will not
   proceed without one that answers.
3. `RECORD`.
4. `SETUP` again for a data port, with a random 64-bit seed folded into the
   key-derivation salt.
5. MRP handshake: `DEVICE_INFO` first — the device stays silent until it
   arrives — then `SET_CONNECTION_STATE` and `CLIENT_UPDATES_CONFIG`.

The data channel nests three framings: a 32-byte big-endian header, a binary
plist, and `params.data` holding varint-length-prefixed protobufs.

`mrp/Protobuf.kt` is a generic wire-format codec rather than generated sources.
MRP defines dozens of message types and now-playing touches about six fields
across four of them, so reading the wire format directly avoids putting protoc
into an Android build and tolerates unknown fields from newer tvOS revisions.

Two things that are easy to get wrong:

- **Track metadata is not in `nowPlayingInfo`.** That field is essentially
  always absent. Real titles and artists arrive in
  `SetStateMessage.playbackQueue.contentItems`, indexed by the queue's
  `location`.
- **`PlayerPath.client` is field 2, not 1** — field 1 is `origin`. Reading
  field 1 yields the device's own name for every app, silently collapsing all
  players into one entry so one app's metadata leaks onto another.

## Security

- Pairing credentials are encrypted with an AES-GCM key held in the Android
  Keystore, which on most devices is non-extractable. Backups are disabled,
  since Keystore-wrapped ciphertext cannot be restored onto other hardware.
- Wire logging is off unless explicitly built with `-PwireLogging=true`.
- The app requests only `INTERNET`, network and Wi-Fi state, and
  `NEARBY_WIFI_DEVICES` (required for local service discovery on Android 13+).
  There is no analytics, no account, and no network access beyond your LAN.

Found a security issue? Please open an issue.

## Contributing

Contributions are welcome — especially **compatibility reports** for models and
tvOS versions other than the one tested.

Please run `./gradlew :protocol:test` before opening a pull request. If you
change protocol behaviour, add a conformance vector rather than only a
round-trip test: self-consistency proves nothing about what a real device
accepts.

## Licence

Licensed under the [Apache License 2.0](LICENSE).

Protocol knowledge and test vectors are derived from
[pyatv](https://github.com/postlund/pyatv) (MIT). See
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Companion Link is an undocumented Apple protocol; this is a clean-room-style
implementation built from public reverse-engineering work. It controls hardware
you own, on your own network.

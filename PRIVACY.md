# Privacy Policy

**Last updated: 2026-10-08**

Puck is a remote control for Apple TV. This page explains what data it
handles, in plain terms, because that's what a privacy policy is actually
for — not because a form requires one.

## What Puck collects

Nothing. Puck has no account, no sign-in, no analytics, and no crash
reporting that leaves your device. There is no server Puck talks to that
collects anything about you or how you use the app.

## What Puck stores, and where

- **Pairing credentials** for each Apple TV you connect to, encrypted with a
  key held in the Android Keystore (non-extractable on most devices) and
  stored only in the app's private storage on your phone. They never leave
  your device. Backups are disabled in the app, since Keystore-wrapped
  ciphertext can't be restored onto different hardware anyway.
- **Device names and addresses** for TVs you've paired with, so the app can
  show your device list and reconnect without rescanning — stored the same
  way, locally only.
- Nothing is stored or transmitted to any server Puck controls, because
  there is no such server.

## Network activity

- **Your local network only, for remote control.** Discovering and
  controlling your Apple TV happens entirely over your Wi-Fi network, device
  to device. This traffic never reaches the internet.
- **One exception: app icons.** The Apple TV doesn't send Puck any artwork
  for the apps installed on it — only a name and a bundle identifier. To
  show real icons in the app drawer, Puck looks each one up against Apple's
  public iTunes Search API (the same public endpoint the App Store's own
  search uses). This means **Apple's servers see which third-party app
  bundle IDs are installed on your Apple TV**, one request per app, cached
  on disk afterward so each app is only ever asked about once. Apple's own
  built-in tvOS apps (Settings, Music, and so on) are never looked up this
  way — their icons ship bundled with Puck instead, since they're not in any
  public catalogue to begin with.
- Nothing about your phone, your identity, or your general usage is sent
  anywhere as part of this lookup — just a bundle id and an app name, the
  same way a browser typing a search query would.

## Permissions

| Permission | Why |
|---|---|
| Internet, network/Wi-Fi state | Talk to your Apple TV on the local network, and the one iTunes lookup above |
| Notifications | The now-playing media notification and the keyboard reply prompt |
| Foreground service (media playback) | Keeps the connection to your Apple TV alive while the now-playing notification is shown |

## Third-party services

The only third party involved is Apple's public iTunes Search API, described
above. Puck has no other integrations, no ad networks, and no analytics
SDKs.

## Changes to this policy

If what Puck collects or sends ever changes, this file changes with it, and
the "last updated" date above will move. The history of this file is public
in this repository's commits.

## Contact

Questions about this policy, or a privacy concern to report, are both
welcome as a [GitHub issue](../../issues).

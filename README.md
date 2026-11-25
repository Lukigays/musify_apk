<div align="center">

<br/>

<img src="Logo.png" alt="Musify app icon" width="160" />

# Musify

### Native Android High-Res Music Client

<br/>

[![Latest release](https://img.shields.io/github/v/release/Lukigays/musify_apk?style=for-the-badge&labelColor=0d1117)](https://github.com/Lukigays/musify_apk/releases)
[![License](https://img.shields.io/github/license/Lukigays/musify_apk?style=for-the-badge&labelColor=0d1117)](https://github.com/Lukigays/musify_apk/blob/main/LICENSE)
[![Downloads](https://img.shields.io/github/downloads/Lukigays/musify_apk/total?style=for-the-badge&labelColor=0d1117)](https://github.com/Lukigays/musify_apk/releases)
[![Android Build](https://img.shields.io/github/actions/workflow/status/Lukigays/musify_apk/android.yml?branch=main&style=for-the-badge&labelColor=0d1117&label=Release%20CI)](https://github.com/Lukigays/musify_apk/actions/workflows/android.yml)

<br/>

[**Download**](#download) · [**Features**](#features) · [**Listen Together & Studio**](#musify-highlights) · [**Build from Source**](#build) · [**License & Attribution**](#license)

</div>

> [!IMPORTANT]
> Musify is an independent third-party audio player and client. It is not affiliated with, endorsed by, or connected to YouTube or Google in any way. Use it at your own discretion.

---

<h2 id="musify-highlights">Musify Highlights</h2>

- **Serverless Listen Together (Zero Login Required)** — Host or join live synced listening rooms directly over TLS MQTT (`broker.emqx.io:8883`) with `https://musify.app` / `musify://` invite links, automatic host-to-guest NTP clock drift compensation, member **Vote Queue**, and moderated **Party Chat**.
- **Poster Lagu Studio** — Create shareable song posters right from Now Playing or song menus with on-device ML Kit / saliency subject cutout, 7 poster styles, 12 vector stickers plus custom text/image stickers, 6 `ColorMatrix` filters, depth masking, and PNG export/share.
- **BitChord Native Audio Engine** — Full native C++/FFmpeg/ONNX playback pipeline (`com.lukyy.musify`) with lossless Hi-Res audio, bit-perfect USB DAC exclusive output, true gapless crossfade, Automix, Android Auto, Wear OS, and Android TV.

---

<h2 id="features">Features</h2>

<table>
  <tr>
    <td width="50%" valign="top">

#### Playback & Audio
- **Search, browse, and stream** music from YouTube Music and configured module sources.
- **Hi-Res lossless playback** — FLAC/ALAC support with bit-perfect USB exclusive DAC output.
- **Gapless playback & Crossfade** — adjustable 0–12s transitions.
- **Automix** — beat-matched and tempo-aligned transitions.
- **Offline downloads & Local Library** — save tracks with embedded metadata and scan device audio.
- **Stats for Nerds** — live codec, sample rate, bit depth, and hardware output diagnostics.

#### Social & Sharing
- **Serverless Listen Together** — create/join rooms with no account or central server required.
- **Vote Queue & Party Chat** — suggest tracks, upvote upcoming songs, and chat with automatic profanity/spam moderation.
- **Poster Lagu Studio** — custom subject cutout, styles, stickers, filters, and high-res PNG export.
- **Lyrics & Replay Share Cards** — export styled lyrics clips and listening recap cards.

    </td>
    <td width="50%" valign="top">

#### Experience & UI
- **Dynamic Artwork Theming** — Material 3 mesh palettes extracted from album art.
- **Frosted-Glass UI** — translucent bars powered by Haze.
- **Word-Synced Lyrics** — multi-provider word/syllable-synced lyrics and Apple-style animations.
- **Multi-Device Support** — Phone (`com.lukyy.musify`), Android TV (`Musify TV`), Wear OS companion, and Android Auto.

#### Controls & Integrations
- **Google Account & Spotify Import** (optional) — personalized recommendations and playlist access.
- **Last.fm & ListenBrainz Scrobbling** + Discord Rich Presence.
- **Per-Network Audio Quality**, playback speed (0.5×–2.0×), skip silence, sleep timer, and system equalizer.
- **In-App Updater** — checks [GitHub Releases](https://github.com/Lukigays/musify_apk/releases/latest) for new signed APKs automatically.

    </td>
  </tr>
</table>

---

<h2 id="download">Download</h2>

Grab the latest signed APK automatically published after each passing release build on the [**Releases**](https://github.com/Lukigays/musify_apk/releases/latest) page:

| Artifact | Target Device | Notes |
| --- | --- | --- |
| `Musify-v*.apk` | Android Phones & Tablets (Universal) | Recommended — supports `arm64-v8a`, `armeabi-v7a`, and `x86_64` |
| `Musify-v*_arm64-v8a.apk` | Modern 64-bit ARM Android Phones | Smaller download size |
| `Musify-v*_armeabi-v7a.apk` | 32-bit ARM Android Devices | Legacy ARM devices |
| `Musify-v*_x86_64.apk` | x86_64 Emulators / Chromebooks | Desktop & emulator builds |
| `MusifyTV-v*.apk` | Android TV / Google TV | Remote-first Leanback UI |
| `MusifyWear-v*.apk` | Wear OS Watches | Companion watch remote & speaker |

> Sideloading requires enabling **Install unknown apps** for your browser or file manager.

---

<h2 id="build">Build from Source</h2>

Requirements:
- **JDK 17** (Eclipse Temurin 17 recommended)
- **Android SDK** (`compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`)
- **Android NDK & CMake 3.22.1+** (for native audio DSP under `app/src/main/cpp`)

```bash
# Run unit tests across shared, sharedUi, and Android app modules
./gradlew :shared:jvmTest :sharedUi:jvmTest testDevDebugUnitTest

# Build debug APK
./gradlew assembleDevDebug

# Build signed production release APKs (Phone, TV, Wear OS)
./gradlew assembleProdRelease
```

---

<h2 id="disclaimer">Disclaimer</h2>

Musify is an independent, community-driven third-party audio player and client. It is **not** associated with Google LLC, YouTube Music, Deezer, or any of their parent companies.

* **No Media Hosting:** Musify does not host, upload, or store copyrighted music files. It scans local storage or streams directly from public or user-authenticated APIs.
* **User Responsibility:** Users are responsible for ensuring their usage complies with applicable copyright laws and third-party terms of service.

---

<h2 id="license">License & Attribution</h2>

Licensed under the **GNU General Public License v3.0 (GPLv3)**. See [LICENSE](LICENSE) for the full license text.

- Native Android playback engine and core UI architecture built upon [BitChord](https://github.com/kushagrasinghx/BitChord) (GPLv3).
- Credits to [binimum/am-lyrics](https://github.com/binimum/am-lyrics), [Mornye](https://github.com/mornyeapp/Mornye), and lyrics providers ([LRCLIB](https://lrclib.net), [BetterLyrics](https://github.com/better-lyrics/better-lyrics), [SimpMusic](https://github.com/maxrave-dev/SimpMusic), [LyricsPlus](https://github.com/ibratabian17/YouLyPlus), [PaxSenix](https://lyrics.paxsenix.org), [lrc.red](https://lrc.red), KuGou, Musixmatch, and Genius).

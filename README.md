# Sonofin

A fast, focused **Jellyfin music client** for Android, with full **Android Auto** support.

[![Build APK](../../actions/workflows/build.yml/badge.svg)](../../actions/workflows/build.yml)

Sonofin connects to your self-hosted [Jellyfin](https://jellyfin.org/) server and turns your music library into a clean listening app.

## What it does

- **Sign in to your server** — works with any Jellyfin instance (HTTPS or HTTP) using your username and password
- **Apple Music-style tabs** — Home, Library, and Search on the bottom bar
- **Home** — Top Picks for You (your most-played albums), Recently Played, and Made for You (genre/artist mixes plus a Discovery Mix generated daily from your library)
- **Library** — pinned items up top, then Playlists / Artists / Albums / Songs, with Recently Added below
- **Song long-press menu** — play next, pin to Library, add to playlist, download/remove download, go to album, mark played
- **Search** — find albums and songs by name from its own tab
- **Chromecast** — cast playback to a TV or speaker from the cast button in the top bar
- **Settings** — view your account, switch servers/users, and switch between music libraries on the server
- **Downloads & offline** — download tracks for offline listening (with live progress and speed in Settings → Downloads); the library, album track lists, and play state are cached so the app works without a connection and plays downloaded songs
- **Browse your music library** — album cover-art grid, track lists with track numbers and durations
- **Stream tracks** with background playback, media notification, lockscreen/Bluetooth controls
- **Android Auto** — browse albums and songs and control playback from your car
- **Playback speed** from 0.75x to 3x
- **Skip controls** — 30s forward, 10s back
- **Play-state sync** — played tracks are marked back to Jellyfin so your "played" badges and Recently Played stay in sync across devices

## Download

Grab the APK one of two ways:

1. **Releases** — each [release](../../releases) is built from a version tag (`vX.Y.Z`). Grab the newest one.
2. **Actions artifacts** — any manual CI run on the [Actions tab](../../actions) uploads the debug APK and release AAB artifacts.

Sideload it by enabling *Install unknown apps* for your browser/file manager, then opening the APK.

> All CI builds are signed with a shared key committed to the repo, so newer APKs install directly over older ones.

## Android Auto

Sonofin ships a Media3 `MediaLibraryService`, so it appears as a media app in Android Auto automatically once installed and signed in. The car browse tree mirrors the app:

- **Recently Played** tab — pinned songs first, then tracks you've played recently
- **Playlists** tab — Downloaded, your playlists, the Made for You mixes, and a Songs list
- **Artists** tab — artists, then their albums and tracks
- **Albums** tab — Top Picks for You up front, then the full cover grid
- **Collection queueing** — tapping a song in an album, playlist, or mix queues the rest of that collection so next/previous work
- **Search** — both the browse search UI and voice ("play *<album>* on Sonofin")
- **Resume** — Auto's resume card restores your last track even after the app was killed

Because sideloaded apps are hidden by default, enable developer mode in the Android Auto settings on your phone and check **"Unknown sources"**, then Sonofin will show up on the car launcher.

## Connecting to Jellyfin

1. Enter your server URL (e.g. `https://jellyfin.example.com`). If you omit the scheme, `https://` is assumed.
2. Enter your Jellyfin **username** and **password**.

Sonofin authenticates through Jellyfin's `Users/AuthenticateByName` endpoint and stores the returned access token. Each install generates a stable device id so the session shows up in Jellyfin's **Dashboard → Devices** as the "Sonofin" client.

If you have more than one music library, switch between them in **Settings**.

## Building locally

Requirements: JDK 17+ and the Android SDK (API 35).

```bash
./gradlew assembleDebug
# APK lands in app/build/outputs/apk/debug/app-debug.apk
```

## Architecture

- **Kotlin + Jetpack Compose** (Material 3, dark theme)
- **Media3 / ExoPlayer** for playback; a single `MediaLibraryService` powers the app UI, the media notification, and Android Auto
- **Retrofit + kotlinx.serialization** client for the Jellyfin REST API (`/Users/AuthenticateByName`, `/Users/{id}/Views`, `/Items`, `/Audio/{id}/stream`, `/Sessions/Playing/Progress`)
- **DataStore** for server credentials and the device id

> Internal package and class names still use the `shelfie` prefix — Sonofin started life as a fork of the Shelfie Audiobookshelf client. It ships as a brand-new Play listing, though, with its own application id (`app.sonofin`) and versioning starting at `0.1.0` (versionCode 1). The app maps Jellyfin's albums/tracks onto the older "podcast/episode" type names in the data layer; everything the user sees is music terminology.

## License

[MIT](LICENSE)

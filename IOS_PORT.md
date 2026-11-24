# ArchiveTune iOS Port

ArchiveTune for iOS is a Kotlin Multiplatform application built with Compose Multiplatform UI and an AVFoundation playback engine, sharing core domain SDK modules directly with ArchiveTune's Android/Desktop ecosystem. Android's Media3 engine and the desktop VLC/Graal production path remain separate.

## Source boundaries

The iOS target consumes ArchiveTune's Kotlin Multiplatform SDK modules directly:

- `ios-playback` (`commonMain` + `iosMain`): iOS `PlaybackController` (`IosPlaybackController`), `AvFoundationPlaybackEngine` (`AVPlayer`, `AVAudioSession`, `MPNowPlayingInfoCenter` with album + async lock-screen artwork, `MPRemoteCommandCenter`, `AVAudioTimePitchAlgorithmSpectral`, local `file:///` + HTTPS/HLS playback), source validation, queue JSON codec, and `createIosPlaybackSession`.
- Shared KMP SDK modules (`sdk-lifecycle`, `playback-core`, `discovery-sdk`, `lyrics-sdk`, `playlist-sdk`, `social-sdk`, `social-session`): lifecycle ownership, queue/sleep-timer/clicked-item policies, bounded discovery caches & fan-out, LRC + YouLyPlus syllable timeline parsing + single-flight lyrics cache, playlist sorting & CSV export, and Ktor Discord social auth/session/friends/chat/presence/statistics APIs.
- `shared/src/commonMain`: portable InnerTube catalog networking (`InnerTubeCatalog`, `CatalogParsing`, `KmpSha1` / `SAPISIDHASH` YouTube auth), backward-compatible JSON library/settings/downloads/statistics persistence (`MusicLibrary`, `MusicStatistics`, `MusicSettingsModels`), thin SDK coordinators (`MusicController`, `MusicSocialController`, `LyricsController`), and Compose UI surfaces matching Android 16's exact layout, typography, colors, menus, and settings.
- `shared/src/iosMain`: Darwin `HttpClient` wiring, Foundation file persistence (`IosMusicStore`), Security.framework Keychain (`IosKeychain`, `IosSocialSessionStore`), native platform bridge (`IosPlatformBridge`: UIKit sharing, clipboard, URL launching, wake lock, offline audio downloads, `UIDocumentPickerViewController` audio & JSON backup import, `WKWebView` YouTube Music sign-in, storage usage & cache clearing), `WKWebView` Discord OAuth (`IosSocialLogin`), and the `UIViewController` entry point (`MainViewController`).
- `iosApp`: native Swift entry (`ArchiveTuneApp.swift`), Xcode project (`ArchiveTune.xcodeproj`, version `15.2.0`, `Assets.xcassets/AppIcon.appiconset`, `archivetune://` URL scheme), and build/verification scripts (`build-simulator.sh`, `verify-resources.py`).
- `shared/src/commonTest` & `ios-playback/src/commonTest`: deterministic queue, catalog, parser, SHA-1/SAPISIDHASH, audible-time statistics, backup/settings persistence, local-file validation, and cancellation tests that run on both JVM and Apple `iosX64Test`.

## Build and verification

JDK 21 is required. From the repository root:

```sh
python3 iosApp/scripts/verify-resources.py
./gradlew -p shared jvmTest
```

On macOS with Xcode installed:

```sh
./iosApp/scripts/build-simulator.sh
./gradlew -p shared iosX64Test -PiosSimulatorId=<SIMULATOR_UDID>
```

`verify-resources.py` validates both vector dimensions (`dp`/`px`) and color attributes (`#RRGGBB`/`#AARRGGBB`) before Xcode builds so Apple's Compose Multiplatform resource loader never encounters Android-only `@android:color/...` or `dip` aliases.

## Key implemented parity capabilities

- **Playback & AVFoundation**: Live HLS/HTTPS streaming and offline `file:///` playback, clicked-song identity preservation, Lock Screen / Control Center metadata + artwork, shuffle/repeat/sleep timer/speed/volume, Tempo & Pitch controls, queue drag-reordering, save queue as playlist, auto-skip-on-error, auto-download-on-like, and persistent queue restore without auto-play.
- **Player & Menus**: ArchiveTune V4 player, Android `Standard` / `Wavy` / `Slim` sliders, animated Skia artwork backdrop with white foregrounds, expressive `AndroidPlayerMenuSheet`, `AndroidSongMenuSheet`, `AndroidBrowseMenuSheet`, and `AndroidAddToPlaylistSheet` bottom sheets.
- **Timed Lyrics**: LRCLIB & YouLyPlus word-by-word synced lyrics, configurable text size, line spacing, alignment, line blur, tap-to-seek, auto-scroll, timing offset (`±200ms`), version picker, and sharing.
- **Home, Search & Browse**: Pull-to-refresh, Home category chips, Speed Dial pins, Keep Listening shelf, Quick Picks modes (Hero Carousel, Compact Rows, Last Listened, Hidden), infinite-scroll Home continuation, Search Explore/Suggestions + grouped All-results sections with "See all" + offline Library search, and Artist/Album/Playlist/Podcast detail pages with Save/Subscribe and Download All.
- **Library**: Real Liked songs, Offline downloads, Cached tracks, Local Files (with iOS Files importer), dynamic `My top N` powered by audible listening stats, Playlists with tags, track reordering, and CSV export, plus Podcasts, Songs filters, derived Artists, and derived Albums.
- **Settings & Authoritative Statistics**: Full Android 16 segmented Settings hub and 11 sub-screens (Account with YouTube Music & Discord sign-in, Listening Stats with monotonic audible-only time and `min(30s, 50% duration)` play threshold, Appearance, Playback, Lyrics, Content, Behavior & Privacy, Integrations, Storage, Backup & Restore, About).
- **Friends**: Discord OAuth with Keychain session persistence, user search, incoming/outgoing friend requests, live listening presence, Direct Chat (text + interactive shared songs, queues, and playlists), and Friend Statistics.

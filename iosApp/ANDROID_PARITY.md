# Android Parity Inventory (iOS 15.2.0)

This tracks iOS parity against ArchiveTune Android 16 (version `15.2.0`). The goal is exact Android appearance, layout, and behavior — without Liquid Glass or an iOS-specific redesign.

## Shell & Theme
- Top app bar with ArchiveTune mark + History, News (with unread badge), New Release Albums, and Settings actions.
- Material 3 Expressive floating navigation toolbar (`Home`, `Search`, `Library`, `Friends`, 78 dp height, 32 dp standalone / 28+12 dp joined corners, double-tap Search, tap-active scroll-to-top).
- Poppins typography, Android 16 reference light/dark palettes, pure-black OLED mode, dynamic artwork color extraction, and Skia blurred artwork backdrop with white foregrounds.
- All Compose drawable vectors verified by `iosApp/scripts/verify-resources.py` for Apple loader dimension and `#RRGGBB`/`#AARRGGBB` hex color compatibility.

## Home
- Pull-to-refresh, category filter chips, Speed Dial pinned grid, Keep Listening history shelf, Quick Picks (Hero Carousel / Compact Rows / Last Listened / Hidden), featured playlist cards with preview tracks, mixed horizontal shelves, Home continuation infinite scroll, explicit filtering, and song/browse context menus.

## Search & Browse
- Explore (Mood & Genres cards with gradients & artwork) and Suggestions tabs with pull-to-refresh.
- Expanding SearchBar with persisted search history (+ Clear all), remote suggestions, categorized `All` search sections with `See all` jump-to-filter, offline `Library` search filter, real favorite/downloaded/explicit indicators, and context menus.
- Artist, Album, Playlist, and Podcast detail views with Save/Subscribe, Play/Shuffle, Download All, and per-track context menus.

## Library
- Five wired shortcuts: `Liked songs`, `Offline` (real local downloads + byte size), `Cached`, `Local Files` (iOS Files importer), and `My top N` (from authoritative audible-time statistics).
- Tabs for `Library`, `Playlists` (create/rename/delete, tags, track reorder, CSV export, saved online playlists), `Podcasts`, `Songs` (All/Liked/Downloaded/Local filters), `Artists`, and `Albums`.

## Player, Menus & Lyrics
- Full Player V4 with animated artwork backdrop, `Standard` / `Wavy` / `Slim` Android sliders, explicit badge, clickable artist navigation, optional volume bar, swipe-to-skip thumbnail, queue sheet (drag reorder, lock queue, save as playlist), and `AndroidPlayerMenuSheet` (Now Playing card, volume slider, expressive 3-col action grid, View Artist/Album, Download, Tempo & Pitch, Song Details).
- Timed lyrics (`AndroidTimedLyrics`, `NowPlayingLyricsPane`, `AndroidLyricsScreen`) with word-by-word sync, configurable text size, line spacing, alignment, line blur, tap-to-seek, auto-scroll, timing offset (`±200ms`), version finder, and share.

## Settings, Statistics & Friends
- Segmented Android 16 Settings hub (52 dp circular colored badges, 28 dp / 6 dp corners) with all 11 sub-screens: Account (YouTube Music WebKit + Keychain, Discord Friends), Listening Stats (monotonic audible-only time, `min(30s, 50% duration)` play threshold, range filters, charts, top rankings), Appearance, Playback, Lyrics, Content, Behavior & Privacy, Integrations, Storage, Backup & Restore, and About.
- Full Friends hub backed by `social-sdk` + `social-session` + Keychain: user search, incoming/outgoing friend requests, live listening presence, Direct Chat (text + tappable shared songs, queues, and playlists), and Friend Statistics.

## Packaging & Publish Readiness
- `iosApp/ArchiveTune/Assets.xcassets/AppIcon.appiconset` (opaque 1024×1024 RGB AppIcon).
- `MARKETING_VERSION = 15.2.0`, `archivetune://` URL scheme, `ITSAppUsesNonExemptEncryption = false`, background audio mode, iPhone + iPad orientations.
- Verified with `./gradlew -p shared jvmTest`, `./iosApp/scripts/build-simulator.sh`, `./gradlew -p shared iosX64Test`, and live iOS 26.5 Simulator execution.

# ArchiveTune iOS port

The iOS target is a Kotlin Multiplatform application with a Compose UI and an
AVFoundation player. Android's Media3 engine and the desktop VLC/Graal production
path remain separate. This is the first implementation slice, not full feature
parity with either existing application.

## Source boundaries

The iOS port now consumes ArchiveTune's Kotlin Multiplatform SDK modules directly:

- `ios-playback` (`commonMain` + `iosMain`): iOS `PlaybackController` (`IosPlaybackController`), `AvFoundationPlaybackEngine` (`AVPlayer`, `AVAudioSession`, `MPNowPlayingInfoCenter`, `MPRemoteCommandCenter`), source validation, queue JSON codec, and `createIosPlaybackSession`.
- Shared KMP SDK modules (`sdk-lifecycle`, `playback-core`, `discovery-sdk`, `lyrics-sdk`, `playlist-sdk`, `social-sdk`, `social-session`): lifecycle ownership, queue/sleep-timer/clicked-item policies, bounded discovery caches & fan-out, LRC + YouLyPlus syllable timeline parsing + single-flight lyrics cache, playlist sorting, and Ktor Discord social auth/session APIs.
- `shared/src/commonMain`: portable InnerTube catalog networking (`InnerTubeCatalog`, `CatalogParsing`), local library/settings persistence, thin SDK coordinators, and Compose music/lyrics UI surfaces.
- `shared/src/iosMain`: Darwin `HttpClient` wiring, Foundation file persistence (`IosMusicStore`), `WKWebView` OAuth modal (`IosSocialLogin`), share sheet (`IosShare`), and the `UIViewController` entry point (`MainViewController`) wired via `SdkLifecycle` and `AvFoundationPlaybackEngine`.
- `iosApp`: native Swift entry, Xcode project, signing/build instructions.
- `shared/src/commonTest`: deterministic queue, request, parser and cancellation
  tests that run on JVM without an Apple SDK.

The existing `core` and `stream-resolver` projects still depend on JVM libraries and
cannot be linked into an Apple framework as-is. The KMP `InnerTubeCatalog` path
uses the existing request/client conventions as a reference, without pretending
that NewPipe, JavaScript deciphering, yt-dlp, VLC, or a JVM is available on iOS.

The selected queue index and clicked video ID remain authoritative. Obsolete
stream resolution and searches are cancelled and generation-guarded. Signed URLs
are ephemeral, search caches are bounded, and changing a screen does not recreate
the application services or stop audio.

## Build and verification

JDK 21 is required. From the repository root:

```sh
./gradlew -p shared jvmTest
```

The standalone shared settings avoid configuring Android/Windows prerequisites
on a Mac. The root build also includes `:shared`; its focused verification task is
`./gradlew :shared:jvmTest --configure-on-demand`.

Apple targets (device ARM64, Apple Silicon simulator and Intel simulator) are configured on macOS. The Xcode application uses the Kotlin
framework `ArchiveTuneShared`. See `iosApp/README.md` for the simulator and device
commands. The iOS workflow runs shared tests and an unsigned simulator build;
no App Store credentials are needed for that check.

Use `./iosApp/scripts/build-simulator.sh` on a Mac for a local unsigned app and
ZIP. Intel simulator compatibility requires the iOS module's Compose 1.10.3 and
Coil 3.4 pins; desktop dependencies retain their own versions.

## Live transport check

The optional probe runs real search, resolves an explicit video, and reads only
an initial range of media bytes without logging signed URLs:

```sh
./gradlew -p shared catalogSmoke -PcatalogSmokeVideoId=jNQXAC9IVRw
```

On this Linux host, search returned songs and the explicit video resolved to
AAC/MP4; the range request returned HTTP 206 with 4096 bytes. Carrying the
anonymous visitor context from the search response into the player request was
necessary here. The cold queue path also passed after a visitor-context
bootstrap, without a search request. This proves transport only, not audio decoding or iPhone
background playback. Anonymous results can still vary by region/session/client.

## Device acceptance gates

A JVM test pass does not validate AVFoundation or an Apple framework. Before
calling this a working first playable release, build on a Mac and test a real
track on a physical iPhone, including:

1. The clicked song is the song heard, including rapid switching and duplicates.
2. Buffering/error UI follows actual playback, and seeking preserves identity.
3. Queue advance works while locked and remote play/pause/seek work correctly.
4. Audio survives locking/backgrounding; calls and unplugging headphones behave
   correctly and teardown removes observers/remote handlers.
5. Resolve a fresh source after expiration; unsupported/auth-required/ciphered
   sources fail clearly rather than playing a substitute song.
6. Saving songs/theme and backgrounding persists changes; reopening restores the
   selected queue index and duplicates without starting audio.
7. Lyrics follow the actual loaded track, stop obsolete lookups on song changes,
   and retain plain/overlapping/multilingual content correctly.

## Remaining migration work

Account sign-in/session renewal and Keychain integration, authenticated library
and playlist mutations, complete Home/browse parity, word-level lyrics/chord
presentation, downloads/local imports, statistics, Play Together, advanced
playback/audio/STEM inference, widgets and distribution remain required parity
work. Local favorites, queue metadata and theme persistence are implemented; a
restored queue does not auto-play. The player sheet presents timed LRCLIB lyrics
with overlapping lines, Unicode text, tap-to-seek and automatic scrolling. Plain
lyrics retain their untimed nature. Existing Android/desktop implementations are behavioral references. No
production desktop dependency, model runtime, updater identity, or version number
is changed for this target.

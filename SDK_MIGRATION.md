# SDK migration: working record

Baseline: `874e21d764eef9f9b95a6b64bc3c35afba637702` on `main`; worktree clean before migration. Completed milestones are committed as scoped checkpoints; do not push unless explicitly authorized. This is an incremental migration, not a claim of feature parity or a stable SDK release. See `SDK_ANDROID_INVENTORY.md` and `SDK_DESKTOP_INVENTORY.md` for static inventories and verification gaps; both require further feature-level review, including integrations and variants.

## Assessment

Desktop already uses the headless JVM `playback-core` contract and has separate backend, playback, settings, downloads, statistics and integration modules. Many domain coordinators remain in `desktopApp`. Android directly uses `core`, `stream-resolver`, `playback-core` and other shared modules, but its Media3 playback service, database and much feature orchestration remain in `app`. Platform backends, storage formats, background services and behavior differ and must not be unified without characterization. Existing JVM modules are not automatically stable SDKs or iOS-compatible.

## Migration sequence

1. Complete inventories, dependency graphs, baseline tests, storage compatibility and device test matrices. Include CI, build variants, submodules and external integrations.
2. Establish narrow versioned feature contracts using existing modules; publish individual artifacts with transitive dependencies, without starting unrelated services. Characterize current behavior before changing call sites.
3. Extract independent domain policies and repositories where platform-independent; retain existing Android Media3 and desktop libVLC engines and their platform capabilities. Migrate one feature and its application consumers at a time, preserving storage formats and ownership.
4. Add headless integration tests using real platform services where possible, plus lifecycle, cancellation and independent-initialization coverage. Verify packaged desktop/native and Android device behavior, including audio and background execution.
5. Provide Android artifacts and a thin optional JNI boundary only after Android feature extraction and Quest 2 resource measurements. Verify third-party licensing and service policies before distribution.

Do not remove old paths until their clients, persistence upgrades and runtime behavior are verified. Do not introduce storage migrations or platform capability loss merely to fit a shared interface.

## Milestone: publish existing playback contracts (in progress)

`playback-core` now publishes an experimental local Maven artifact, `moe.rukamori.archivetune:playback-core:0.1.0-SNAPSHOT`. It contains existing queue, models and controller interfaces, **not** a player implementation, stream resolver, authentication or an Android adapter. Desktop uses the playback contracts; Android consumes selected shared playback policies but has not been adapted to the controller. The artifact is not a promise of API stability, nor a complete SDK.

From the repository root with JDK 21:

```powershell
.\gradlew.bat :playback-core:test :playback-core:publishToMavenLocal :audio-analysis-sdk:test :audio-analysis-sdk:publishToMavenLocal --no-daemon
.\gradlew.bat -p examples/headless-playback run --no-daemon
```

The separate Java example resolves the published playback and audio-analysis artifacts from Maven Local and exercises the real queue and beat timeline without a UI; it does not play audio. Clients using `PlaybackController` must obtain a platform implementation, collect its state/events, serialize calls as required by the implementation, and close the owned controller. `PlaybackQueue` itself requires serialized access. Desktop runtime construction and Android integration are not yet independent SDK entry points.

Validation on this checkout: the baseline test using the default project cache failed after tests ran because Windows reported corrupted/unreadable Gradle cache files. Using a separate `--project-cache-dir` outside the repository, `:playback-core:test :playback-core:publishToMavenLocal --rerun-tasks` passed and the independent example built and ran. This validates JVM queue consumption only; it does not establish audio, native, Android, storage or feature parity. Investigate the underlying filesystem/cache corruption before relying on the original cache.

## Milestone: desktop podcast repository extraction

`DesktopPodcastRepository` and its backend interface, observable library state, caching and save orchestration moved unchanged from `desktopApp` to `desktop-backend`. Desktop continues constructing and using the same implementation; its cache keys and serialized data are unchanged. The existing account-isolation, stale-account operation, remote failure and library reconciliation tests moved with it into the backend module so they run without application UI. `:desktop-backend:test --tests '*DesktopPodcastRepositoryTest'` and `:desktopApp:compileKotlin` passed using the separate Gradle project cache. Remote service behavior and packaged-app operation remain unverified. This is a desktop JVM feature boundary, not yet a stable cross-platform podcast SDK or independently published backend artifact.

## Milestone: playlist suggestions extraction

The desktop playlist-suggestions repository and its existing headless tests moved from `desktopApp` to `desktop-backend`. The desktop app continues to use the same repository and its account-scoped cache keys and data format are unchanged. The backend test suite and `:desktopApp:compileKotlin` passed; live YouTube mutations and packaged-app behavior remain unverified. This feature still depends on desktop backend and shared YouTube models, and is not yet an independently published API.

## Milestone: Explore extraction

`DesktopExploreRepository` and its observable cached state now live in `desktop-backend`, with an injectable headless backend interface and the existing `ArchiveTuneJvmClient` adapter. The desktop app continues to use the same repository. New backend tests cover fresh-content reuse and rejection of an in-flight response after an account switch; those tests and desktop compilation pass. Live service and packaged-app verification remain outstanding.

## Milestone: independently consumed desktop playback

`desktop-playback` now publishes an experimental local Maven artifact with a dependency on the published `playback-core` coordinates. `examples/headless-desktop` is an independent Gradle project that constructs a real VLC controller with an injected local-file resolver, observes its state without application UI, and closes it. It successfully reached playing state and advanced beyond 100 ms with a local WAV and the checked-out VLC runtime. This is not yet a complete streaming/account SDK: consumers must provide a resolver and a compatible libVLC runtime; the example does not package VLC. The direct-runtime test reported stale VLC plugin-cache timestamps.

```powershell
.\gradlew.bat :playback-core:publishToMavenLocal :desktop-playback:publishToMavenLocal
$env:ARCHIVETUNE_VLC_DIR = 'C:\path\to\vlc'
.\gradlew.bat -p examples/headless-desktop run --args='C:/path/to/audio.wav'
```

## Milestone: shared equal-power crossfade gains

The previously identical equal-power gain function is now owned by `playback-core` and used by both Android Media3 and desktop VLC; platform-specific handoff and audio-session logic remains separate. Android `gmsMobileUniversalDebug` Kotlin compilation and crossfade policy tests passed, as did the desktop policy tests and quick Graal native compile. A real libVLC test observed overlapping players during a slow next-source resolution, but did not measure mixed audio output. The checked-in VLC directory reported stale plugin-cache timestamps during this direct-runtime test; packaged startup behavior has not been verified.

## Milestone: playback prefetch extraction

`NextTrackPrefetcher` and its existing cancellation, queue identity and position-tick tests moved from `desktopApp` into `playback-core`. Desktop still constructs it with the same resolver and application scope. Its SDK contract requires serialized snapshot/close calls; it never creates a playback session and only pre-resolves the next deferred occurrence. `:playback-core:test`, desktop compilation, and quick Graal `nativeCheck -PnativeQuickBuild=true` pass; real streaming and installed-package operation remain unverified.

## Milestone: Android stream-resolution coordination extraction

Android's bounded in-memory stream cache, single-flight ownership, foreground/preload prioritization, expiry and invalidation now live in `stream-resolver` as `StreamResolutionCoordinator`; Android retains its Media3 blocking adapter, Android thread checks, authentication/PoToken preparation and Youtubei backend. The app uses the extracted implementation rather than a duplicate. `AudioQuality`, stream request/result models and priority moved into the shared JVM module without changing serialized enum names. The coordinator owns its coroutine scope and exposes terminal `close()`; signed URLs and headers remain memory-only.

The Android stream chunk-length policy, retry-attempt tracker and next-track pre-resolution policy also live in `stream-resolver`, with their existing tests. Headless SDK tests and Android wrapper characterization tests cover shared consumers, cancellation, preloading priority, invalidation, alternate playback/download reuse, metadata isolation and close behavior. `:stream-resolver:test`, Android focused tests and Android Kotlin compilation passed. Real authenticated resolution, expiring signed URLs, device playback, request latency and account-switch behavior still require end-to-end validation; this is not a standalone published streaming implementation yet.

## Milestone: desktop beat-analysis orchestration extraction

`DesktopAudioAnalysisRepository` moved into `desktop-audio-analysis-runtime`; the app supplies its existing cache root and a 44.1 kHz mono decoding callback instead of passing `AppPaths` and a concrete VLC controller. The compact v1 cache format and analyzer are unchanged. A headless regression test loads a pre-existing v1 cache without invoking decoding, the audio-analysis tests pass, and quick Graal `nativeCheck` passes. Real native analysis of newly decoded audio and packaged playback still need smoke testing after extraction.

## Milestone: Android Play Together protocol extraction

The existing Android party models, wire messages, permission decisions, guest-action planning, clock/synchronization policy, join links and desktop interoperability codec now live in the headless `together-sdk` JVM module. The direct WebSocket client and host server now live in the optional `together-transport` module, with an explicitly injected monotonic clock; Android supplies `SystemClock.elapsedRealtime`. The existing online-host and online HTTP API implementations also live in `together-transport`; Android retains endpoint discovery, foreground lifecycle, playback integration and storage. The direct client and online host have explicit terminal `close()` operations that release their owned HTTP clients and scopes, and Android calls them when leaving; the online HTTP API is `AutoCloseable`. Existing protocol, permissions, link and real-WebSocket interoperability tests moved with the implementation; SDK tests, a local online-API request test, lifecycle tests and Android compilation pass. Experimental Maven Local artifacts are available, and `examples/headless-together` exercised a real local host/guest connection using those artifacts. Desktop's separate host/session implementation has not been consolidated, and real multi-device playback, permissions and background operation still require verification.

## Milestone: shared SponsorBlock segment policy

Both Android and desktop now use `sponsorblock-core` for validation, category/action filtering, rounding, ordering and merging of skip ranges. Platform-specific requests, caching, settings and seek controllers remain unchanged. Shared policy tests, the desktop backend suite, desktop SponsorBlock coordinator tests, the full Android unit suite, and desktop and Android Kotlin compilation pass. Live SponsorBlock requests and actual playback skips on each platform remain unverified.

## Milestone: Last.fm service extraction

Desktop Last.fm authorization, playback observation and scrobbling moved into `desktop-social` with its existing eight tests. Application-generated API credentials are now injected by `DesktopMusicServices` instead of referenced from SDK code; the same secure store, settings and scope are supplied. The social tests and desktop compilation pass. Live authorization/scrobbling and packaged operation remain unverified; the existing shared Last.fm client remains a global and is not yet an isolated multi-client implementation.

## Milestone: independently consumed download engine

The existing `desktop-downloads` engine publishes an experimental Maven Local artifact. `examples/headless-download` resolves that artifact independently, starts a download, observes completion and closes the engine. A local HTTP test downloaded 705,644 bytes and `cmp` verified an exact match, exercising the full-response fallback; range behavior remains covered by the engine tests. Callers supply their own resolved URL and headers and must not persist credentials or signed URLs. This is not a YouTube resolution API.

```powershell
.\gradlew.bat :desktop-downloads:publishToMavenLocal
.\gradlew.bat -p examples/headless-download run --args='https://example.invalid/audio.wav C:/path/to/downloads wav'
```

## Milestone: desktop download orchestration extraction

The existing download orchestration, retry tracking, resolution queue and manager reconfiguration moved from `desktopApp` to `desktop-download-orchestration`. It now receives the existing settings store and default download path explicitly instead of requiring `DesktopRuntime`; the app supplies the same values, and the download engine and persisted formats are unchanged. Existing retry tests, download engine tests, a new headless directory-change/persistence test, desktop compilation and quick Graal `nativeCheck` pass. Live downloads, throughput and packaged-app behavior remain unverified. Its current `ArchiveTuneJvmClient` dependency still has global YouTube configuration and is not yet a fully isolated multi-client boundary.

## Milestone: Android playback session policies extraction

The pure-JVM policies consumed by Android's Media3 service now live in `playback-core` under `playback.api`: `AudioOutputPolicy` (stable output-device ids, deduplication, runtime resolution), `PlaybackParameterPersistence` (speed/pitch bounds and semitone conversion), `playbackLoudnessNormalizationFactor` (perceptual loudness gain with caps), and the crossfade handoff helpers (drift/seek checks and shared audio-session id). `MusicService` and `AudioOutputResolver` use the shared implementations; no persisted format, ID prefix, display-name rule or gain cap changed. All original Android unit-test cases moved into headless `playback-core` tests under the `api` package. `:playback-core:test` passes. Android `gmsMobileUniversalDebug` compilation and the full debug unit suite pass. Media3 device behavior, actual audio output and output-device switching remain unverified.

## Milestone: Android headless lyrics timeline

Existing lyric entries, prepared models, active-line selection, text/word/grapheme timing, instrumental-gap insertion and prepared-entry adapters moved to the optional `lyrics-timeline` JVM module. Android retains provider fetching, preparation, romanization, translation and rendering. Grapheme segmentation is shared with the existing Android motion renderer, preserving its prior algorithm; nullable word reads were adapted for Kotlin cross-module smart-cast rules without changing the guarded rendering paths. The moved timing tests and full Android `gmsMobileUniversalDebug` unit suite pass; an independently resolved Maven Local example prepared a multilingual timed line. Rendering on a device, provider behavior and other build variants remain unverified. The existing model API exposes Guava and coroutine state; consumers that do not need lyrics need not include the module.

## Milestone: shared Songsterr chord client

The Android and desktop Songsterr clients were identical apart from their User-Agent. They now consume one `chords-sdk` implementation, retaining each platform's original User-Agent and the Android platform JSON implementation. Shared parser tests replace the identical duplicated test files. The neural analyzer now depends on the shared chord types instead of the entire desktop backend, and its artifact plus `chords-sdk` are published experimentally to Maven Local; an independent example resolved the full dependency graph and constructed the analyzer. Android unit tests and debug APK assembly, desktop backend/lyrics/neural tests, desktop compile and quick Graal compile pass. Live Songsterr, Android device behavior and actual packaged inference remain unverified.

## Milestone: neural worker cancellation

The extracted chord runner now has a child cancellation monitor that terminates its process even when the parent is blocked reading output; the previous completion callback could wait for that read to finish. Cancellation is rethrown rather than reported as an ordinary failure if terminating the process breaks its stream. A regression test starts a real long-running subprocess, blocks reading its output and verifies cancellation terminates it promptly. The test and quick Graal native compile pass; real GPU inference cancellation remains unverified.

## Milestone: desktop neural chord analysis boundary

The existing local neural chord analyzer moved into `desktop-neural-chords`, with injected cache/model paths, manifest URL, audio decoding and diagnostics. Desktop supplies its existing VLC decoder and logger; Songsterr priority stays in the lyrics presenter. The cached-result test, desktop compilation and quick Graal native compile pass. Chord types have subsequently moved to `chords-sdk`, and the module is now experimentally published to Maven Local. Real runner inference, cancellation and installed-package operation remain unverified.

## Milestone: optional desktop model runtime

Pinned STEM/chord manifest handling, verified model/asset download and Windows runner lookup moved from `desktopApp` into `desktop-model-runtime`; the app passes its existing model directory and manifest URL and continues using the same runner and cache paths. The module is published experimentally to Maven Local. The previously process-global verified-path cache was removed: each lookup already rehashed files, and the cache could return another caller's directory. Bounded striped locks preserve same-directory/model download coalescing without an unbounded lock map. Existing explicit model environment overrides retain their previous behavior.

Manifest tests plus new local-HTTP coalescing, directory isolation and tamper/partial-file cleanup tests pass, as do desktop app tests, Rust tests and a quick Graal native compile. An independent example validates the checked-in manifest. Packaged runner inference and installed-app behavior remain unverified.

## Milestone: desktop Spotify importer boundary

The existing real Spotify playlist importer and its link/batching tests moved from `desktop-backend` to `desktop-spotify-import`, which depends on the existing backend and Spotify client; `desktopApp` consumes it directly. This preserves the current matching, creation, serial ordered duplicate-preserving append/retry and cover-copy implementation. Moved tests, desktop-backend tests, desktop compilation and quick Graal native compilation pass. The module is not independently published because its backend/provider graph is not yet publishable; live imports and installed-package behavior remain unverified.

## Milestone: playlist import matching and resolution

Android's existing fuzzy matching and ordered, bounded concurrent import-song resolution now live in `playlist-import-sdk`, parameterized by caller models and a YouTube search callback. Android retains its real YouTube adapter, Room song models, UI, and playlist writes. Resolution preserves duplicates and input order and propagates cancellation; it does not perform Spotify fetching or playlist mutation. Existing Android matcher, replacement-search and resolver tests and the full Android unit suite pass; SDK tests and a separate Maven Local local-match example cover headless use. Live providers and device import remain unverified.

## Milestone: playback resumption selection

The generic queue selection and index/position clamping used by Android's Media3 session resumption now live in `playback-core`. The Android session still loads persisted data and builds Media3 items itself; this does not extract persistence or the session. New tests cover persisted versus current queue selection, matching single-item recovery, browsing and malformed positions. Android compilation and playback-core tests passed; device resumption and storage upgrades have not been verified.

## Milestone: headless sleep timer

Android's existing sleep timer scheduling and transition policy now use `SleepTimerController` in `playback-core`; the Media3 listener, Compose state and actual service pause remain in the app. The controller takes a caller-owned serialized coroutine scope, a pause callback and an optional clock/state observer; closing it cancels its pending timer. Tests cover deadline, replacement/cancellation, end-of-track and reentrant clear behavior, including the existing state when switching modes. Playback-core tests and Android/desktop compilation pass; Android background and real-device behavior remain unverified.

## Milestone: Android lyrics request cache extraction

The bounded, single-flight lyrics request cache and provider-order selection moved from Android `app` to `lyrics-sdk`; the Android lyrics helper still uses the same implementation and no persistent data format changed. Existing tests for coalescing, invalidation, TTL, cache bounds and provider ordering moved with it and pass in the headless JVM module. The experimental artifact publishes to Maven Local and Android `gmsMobileUniversalDebug` Kotlin compilation passes. Provider integrations, rendered lyrics and device behavior have not been verified by these tests.

## Milestone: desktop lyrics engine extraction

The playback-observing lyrics/chord presenter and line/character timeline parsing moved from the Compose UI module to `desktop-lyrics-engine`. The existing Compose surface continues rendering those same timelines and visual effects; a nullable translation is captured locally to allow cross-module smart casting. Presenter and parser tests moved into the headless engine. Both engine and remaining UI tests plus desktop compilation pass. Actual rendered lyrics, service-backed providers and packaged-app behavior still require verification.

## Milestone: desktop lyrics translation extraction

The desktop translation service, credential handling and settings/cache orchestration moved to `desktop-lyrics-translation`. Its translation interface now resides in `desktop-backend`, so the service no longer depends on the lyrics UI module. The desktop app still supplies the same settings and secure store. A headless credential test, the existing lyrics presenter suite and desktop compilation pass. Live translation providers and packaged credential integration remain unverified.

## Milestone: desktop Canvas extraction

Desktop Canvas provider lookup, cancellation, video caching and settings integration now live in `desktop-canvas`; the desktop app uses the same implementation and cache path. Existing tests moved into the headless module, and `:desktop-canvas:test` plus desktop compilation passed. Live providers, packaged app and actual video output remain unverified.

## Milestone: desktop browse extraction

The account-scoped browse repository, bounded page cache, route models and existing invalidation tests moved from `desktopApp` to `desktop-backend`; the app continues to use them for album, artist, playlist and podcast navigation. The backend suite and desktop compilation pass; live service and packaged-app verification remain outstanding.

## Milestone: desktop album index extraction

The song-to-album metadata index moved from `desktopApp` to `desktop-library`, preserving its original JSON path and format and its close-time flush. Desktop continues using it through the same package. A new headless test verifies persistence across close/reopen and rejection of unbrowseable albums; it and desktop compilation pass. Packaged-app behavior remains unverified.

## Milestone: Android beat analysis extraction

The UI-independent PCM beat tracker, beat-timeline sampling and energy detector now live in `audio-analysis-sdk`; Android uses those implementations while retaining MediaCodec decoding, playback coordination, Visualizer ownership and the existing cache format. Their existing tests moved into the JVM module. `:audio-analysis-sdk:test` passed; the full `:desktop-backend:test` suite also passed after the podcast extraction. Android `:app:compileGmsMobileUniversalDebugKotlin`, the full `:app:testGmsMobileUniversalDebugUnitTest` suite and `:app:assembleGmsMobileUniversalDebug` passed using the SDK at `C:\Users\mint\Android\Sdk` and HotSpot JDK 21; device behavior remains unverified. The debug APK is `app/build/outputs/apk/gmsMobileUniversal/debug/app-gms-mobile-universal-debug.apk`. Neither this extraction nor the module name implies a complete Android SDK or audio playback integration.

## Milestone: Android statistics domain extraction

The existing Android audible-time tracker and statistics models now live in `statistics-sdk`; Android supplies its monotonic clock adapter and continues to own Room persistence. Existing tracker/model tests pass headlessly and the experimental `moe.rukamori.archivetune:statistics-sdk:0.1.0-SNAPSHOT` publishes to Maven Local. Compose stability annotations are compile-only so headless runtime consumers do not pull in Compose. The desktop statistics implementation remains separate: equivalence has not been assumed or tested. Android `gmsMobileUniversalDebug` Kotlin compilation passed using the SDK at `C:\Users\mint\Android\Sdk` and HotSpot JDK 21. Device behavior and persistence upgrades remain unverified.

## Cross-platform suite result

A full `:desktopApp:test` run initially failed one Linux-path test on Windows because literal `/home/...` paths were interpreted using Windows `Path` semantics. The test now uses host-native absolute fixture paths while still asserting exact fallback paths for empty/relative XDG values and an exact custom absolute cache path; production path logic was not changed. The full desktop application suite and affected backend, download, lyrics, social, Canvas and library suites subsequently passed on Windows. Linux runtime behavior still requires Linux validation.

## Remaining verification boundaries and risks

- Android (`app`) and Desktop (`desktopApp`) consume the extracted SDK modules (`playback-core`, `android-playback`, `desktop-playback`, `stream-resolver`, `discovery-sdk`, `playlist-sdk`, `playlist-import-sdk`, `stems-sdk`, `desktop-stems`, `update-sdk`, `social-sdk`, `social-session`, `social-realtime`, `social-login`, `account-sdk`, `statistics-sdk`, `audio-analysis-sdk`, `chords-sdk`, `chord-neural-core`, `lyrics-sdk`, `lyrics-timeline`, `local-media-sdk`, `download-sdk`, `equalizer-sdk`, `backup-sdk`, `sponsorblock-core`, `together-sdk`, `together-transport`, `sdk-lifecycle`), while platform services (`MusicService` MediaLibraryService/ObjectOutputStream upgrades, Room, DataStore, MediaCodec, DPAPI, libVLC, Rust+DirectML) remain behind explicit host adapters.
- `android-playback` (`createMedia3PlaybackSession`, `Media3PlaybackController`, `AndroidPlaybackQueueStore`, `resolvingPlaybackSourceUpstream`) and `desktop-playback` (`createDesktopPlaybackSession`) publish turnkey session entry points to Maven Local, and `SDK_ARCHITECTURE.md` documents the thin Kotlin/JNI + C# `AndroidJavaObject` bridge for Unity 6.6 on Meta Quest 2.
- Shared `core` (`YouTube`) and `lastfm` clients still hold process-scoped configuration rather than isolated multi-account instances, and artifacts are published to `mavenLocal()` (`0.1.0-SNAPSHOT`) rather than a remote public Maven repository.
- Physical Android device execution (connected `DeferredQueueDeviceTest`, Bluetooth/audio focus, process-death resumption, Room/ObjectOutputStream upgrades on device), live authenticated YouTube/Spotify/Discord/Last.fm network mutations, Linux runtime validation, and physical Meta Quest 2 memory profiling remain external environment checks.

## Milestone: Android neural chord core extraction

The Android chord model asset verification, DSP and streaming resampler now live in the headless `chord-neural-core` JVM module. Android retains MediaCodec decoding and ONNX execution and consumes the same implementation; model files and cache formats are unchanged. Existing DSP and model-store tests and the Android debug unit suite passed, as did the debug APK assembly. The published Maven Local artifact is exercised by the independent headless chord example. Real device inference and audio decoding remain unverified.

## Milestone: desktop STEM orchestration extraction

`DesktopStemCoordinator` and its six-stem control/state types moved from `desktopApp` to the headless `desktop-stems` module. The app now supplies narrow seams instead of `DesktopRuntime`/`VlcjPlaybackController`: the application scope, resolved model and stem directories and manifest URL, a diagnostics callback, and a small `StemPlaybackBridge` adapter over the existing VLC controller. Generation/renderer cancellation and epochs, per-track controls, the persisted cache key layout and close-time VLC audio restoration are unchanged; the existing pinned-manifest tests moved with the code. New headless tests cover six-stem worker order, mute/solo and gain sanitization, cache-layout stability, startup cache indexing, per-track control scoping and shutdown audio restoration. `:desktop-stems:test`, desktop compilation and quick Graal `nativeCheck` pass after integration. Real worker inference, live renderer behavior and packaged/native operation remain unverified.

## Milestone: Android local-media format policies extraction

The local library's embedded-lyrics tag parser and supported-audio format policy now live in the headless `local-media-sdk` JVM module. `EmbeddedLyricsParser` preserves the original ID3v2.2/2.3/2.4 (USLT/SYLT, unsynchronization), MP4 `©lyr`, FLAC and Ogg/Opus Vorbis-comment parsing and emitted LRC text verbatim; the caller now injects a fresh stream opener and an error reporter instead of a `ContentResolver`, so ContentProvider access and Timber logging stay in `LocalSongScanner`. `SupportedLocalAudio` moved with the same fully qualified name, so the scanner and library screen call sites are unchanged. No on-disk format, cache key or path changed. New headless tests pin each stored tag variant, ordering, cancellation propagation and error reporting, plus extension/MIME precedence.

The module has no runtime dependencies beyond the Kotlin stdlib. Its tests, Android debug compilation and the full Android debug unit suite pass; device scanning remains unverified.

## Milestone: desktop SponsorBlock feature extraction

The SponsorBlock HTTP client, segment cache and playback coordinator now live together in `desktop-sponsorblock`; the desktop application still supplies settings and party seek authority. Existing client and coordinator tests pass in the feature module, and desktop application compilation passes. Live service and packaged-app behavior remain unverified.

## Milestone: saved accounts and social client

Android's saved-account JSON model and codec moved unchanged to `account-sdk`. Its JVM compatibility tests and independently resolved Maven Local example pass. Account login, session management and DataStore remain Android-owned; the serialized account contains credentials and requires protected storage. The Android social HTTP client and wire models moved to `social-sdk`; Android retains sign-in, session storage, realtime and UI integration. A local HTTP test exercises authenticated retrieval. Android debug compilation and the full debug unit suite pass; live account and social workflows remain unverified.

## Milestone: optional lifecycle helper

`sdk-lifecycle` provides a caller-owned scope and synchronous `AutoCloseable` cleanup registry with deterministic order, parent cancellation and aggregated failures. Tests cover shutdown, failures and concurrency; an independently resolved download example used it to download a local fixture with matching SHA-256. Applications do not yet use this helper, and suspending shutdown remains feature-owned; it is not a complete SDK session.

## Milestone: social and discovery boundaries

Social session orchestration and realtime transport now use headless JVM modules; Android supplies browser login, DataStore session storage and lifecycle scope. The WebSocket and session tests, Android debug compilation and Android debug unit suite pass. The session depends only on the transport interface, so alternate transports do not require Ktor. Live authentication and realtime service behavior remain unverified.

Android discovery's batching and list selection now use `discovery-sdk`; the existing search view-model cache behavior remains in place. The module also contains an experimental bounded cache with single-flight and invalidation tests, but the app does not yet use it. Playlist CSV export uses a headless coordinator and retains order, duplicate entries, CSV escaping and Android storage. Module tests and the Android debug unit suite pass; actual ContentResolver export and live discovery remain unverified.

## Milestone: lyrics and desktop resource orchestration

Android lyrics provider ordering, concurrency, timeouts and result updates now use `lyrics-sdk`; Android still owns preferences, connectivity, provider implementations and rendering. Headless provider tests pass. The existing desktop resource coordinator and its tests moved unchanged to `desktop-resource-core`, which the desktop backend consumes through its public dependency. Resource and backend tests and desktop compilation pass; callers remain responsible for account-scoped keys and safe cache contents. The Android debug APK was assembled after the preceding account, discovery, playlist and social changes; it does not validate subsequent lyrics changes or real device behavior.

## Android integration milestone

The app adapters for audio-focus/crossfade/persistence coordination, equalizer preferences, download-library aggregation, local scan policies and YouTube login now delegate to SDK modules. Platform Media3 calls, DataStore, network and audio effects remain Android-owned. The download adapter maps Media3 constants to SDK states explicitly; it does not assume matching numeric values. Focused SDK tests and `:app:testGmsMobileUniversalDebugUnitTest` pass with these integrations. Device audio, background playback, account, download and storage-upgrade testing remain outstanding.

The independently built `examples/headless-equalizer` resolves `equalizer-sdk` from Maven Local and exercises its preferences API with an in-memory adapter. It does not exercise an audio backend.

Android's SponsorBlock monitor now uses the headless skip planner for range activation, repeat suppression and duration-clamped seek targets. SponsorBlock core and Android debug unit tests pass. Device playback testing remains outstanding.

Android statistics overview queries, time bucketing and trend construction now use a headless query service. The existing Room store supplies its query interface and owns persistence. The statistics SDK and Android debug unit tests pass. Database and device verification remain outstanding.

Android queue drag reorder planning now lives in `playback-core`, using window identity to distinguish duplicate tracks and preserving the existing shuffled traversal ordering. Focused planner tests pass; Media3 UI and device verification remain outstanding.

Desktop playlist CSV export, picker state, ordering and local filtering now use `playlist-sdk` through app adapters. SDK tests and desktop application and test-source compilation pass; actual desktop UI and exported files remain to be exercised.

Desktop settings normalization and mutations now use a headless policy and session in `desktop-settings`. The desktop app supplies its shared store, which still owns the existing Properties format and coalesced writes. Module and desktop app suites passed after integration. Live audio, keybinds, packaged behavior and upgrades remain to be exercised.

Android manual and scheduled backups now use `backup-sdk` for archive handling, settings XML, validation and scheduling. The app supplies Android storage, database and restart adapters, with restore and restart in the same cancellation-protected section. Headless backup, restore, Unicode and scheduling tests and the Android debug unit suite pass. Restoration of existing backups on a device remains to be verified.

Desktop queue checkpoint timing now comes from `playback-core`. Its existing tests moved with it and cover periodic checkpoints, queue changes, pause and seek, and forced shutdown saves. Desktop continues using its existing queue store and file format.

The Android app now uses `android-playback` for its Media3 queue traversal, transport helpers and observable player state. The app supplies its existing player and owns its background service, stream resolution wiring and guest controls. All 16 adapter tests and the Android debug unit suite pass. The release AAR publishes locally. Playback on a device remains to be verified.

Desktop playlist creation, rename, delete and track mutations now use the headless coordinator in `desktop-backend`. Rename rollback retains the full original playlist record and the coordinator checks the active session revision before applying cache effects. Desktop auth, backend and app suites pass. Live YouTube mutations still require verification, and the underlying YouTube client continues to use shared session configuration.

The separate Android consumer assembled against the published Media3 AAR from Maven Local, with Kotlin 2.4.10 matching the SDK metadata. The independent desktop settings consumer wrote and verified its settings file using the published `desktop-settings` artifact.

Desktop stream selection, completed-download fallback, signed-source memory caching and HTTP recovery now live in `desktop-stream-playback`. The desktop app supplies the same authenticated backend and logger. The module and desktop app tests pass, including checks for cache scope, expiry and retry decisions. Live streaming and packaged playback remain to be verified. Separate Maven publication depends on publishing the desktop backend and its transitive modules.

The Android next-stream preloader now uses `stream-resolver` for target deduplication, cancellation and retry decisions. The app supplies the existing Media3 cache probes and stream resolver. Module tests cover superseded targets, cached sources and retry after failure. All 137 Android debug unit tests and 110 desktop app tests passed with the recent migrations. Running Android symbol processing and Kotlin compilation in separate Gradle invocations kept memory use within the Windows build host's available commit limit.

Superseded exploration draft folders (`android-playback/drafts/`, `backend-sdk/`, `examples/headless-backend/` and `library-sdk/`) were removed after promoting the production implementations into `android-playback`, `desktop-resource-core` and `playlist-sdk`.

The integrated Android GMS mobile universal debug APK assembled at `app/build/outputs/apk/gmsMobileUniversal/debug/app-gms-mobile-universal-debug.apk`. Assembly excluded the root `spotlessApply` task to avoid applying changes to unrelated source files. The Android debug unit suite, the Media3 adapter tests and the independent consumer build all passed.

## Milestone: Android deferred playback data sources, controller and turnkey session

The Media3 deferred source-opening chain, chunked HTTP reader and playback/download cache routing live in `android-playback` (`PlaybackDataSources` and `ChunkedDataSource`), alongside `Media3PlaybackController`, `AndroidPlaybackQueueStore` and `createMedia3PlaybackSession`. `MusicService`, `MediaItemExt` and `NextStreamPreloader` delegate to `setPlaybackAddress`, `resolvingPlaybackUpstream`, `cachedPlaybackDataSourceFactory`, `isFullyCached`, `bypassesPlaybackCache` and `bypassesDeferredPlaybackResolution`, while `app` retains its authenticated YouTube stream resolver, format metadata lookup, MediaLibraryService lifecycle and legacy `ObjectOutputStream` queue upgrades. `Media3PlaybackController` supports load-time deferred source resolution via `resolvingPlaybackSourceUpstream` so Media3 queue auto-advancement resolves deferred sources on its loading thread without blocking the main thread, and `AndroidPlaybackQueueStore` persists JSON queue checkpoints atomically while stripping transient request headers. All 33 `android-playback` unit tests pass, the AAR publishes to Maven Local, and `examples/android-playback-consumer` (`assembleDebug` and `assembleDebugAndroidTest`) builds against the published artifact. Live connected-device execution of `DeferredQueueDeviceTest` requires an attached Android device or emulator.

## Milestone: shared session host, clicked-item queue policy and desktop session wiring

`playback-core` now provides `PlaybackSessionHost` (`PlaybackQueuePersistence`, `PersistedPlaybackQueue`) and `ClickedItemQueuePolicy` (`selectClickedItemQueue`, `fallbackClickedItemQueue`, `requestedQueueItemIndex`). `desktop-playback` exposes `createDesktopPlaybackSession` and `PlaybackQueueStore.asQueuePersistence()`, and `examples/headless-desktop` exercises real libVLC WAV playback, atomic queue checkpoint persistence and session restoration headlessly against Maven Local artifacts. `DesktopRuntime` uses `SdkLifecycle` for deterministic scope/resource cleanup, `DesktopMusicServices` delegates play-history/recommendation personalization to `DesktopPersonalizationCoordinator` in `desktop-backend`, and `DesktopMusicSurface` delegates clicked-song `Up Next` resolution and local library filtering to `DesktopUpNextQueueResolver` and `playlist-sdk`.

## Milestone: Android discovery, playlist-tag, top-mix and AI content-filter SDK migrations

`discovery-sdk` now owns `QuickPickSeedPolicy`, `RelatedSongsResolver`, `DiscoverySuggestionsPolicy`, `RadioContinuationPolicy` and `PodcastLibraryPolicy`, consumed by Android's `HomeRepository`, `HomeUseCases`, `BrowseRepository` (with account/locale-scoped `DiscoveryContentCache`), `SearchDiscoveryRepository`, `LocalMixQueue`, `LocalAlbumRadio`, `YouTubeAlbumRadio` and `PodcastRepository`, and verified headlessly via `examples/headless-discovery`. `playlist-sdk` now owns `PlaylistTagPolicy`, `LibraryTopMixPolicy` and `AiContentFilterMatcher`, consumed by Android's `PlaylistTagsRepository`, `LibraryTopMixRepository`, `AiContentFilterRepository` and `AiContentFilterUseCases`, and verified headlessly via `examples/headless-playlist`.

## Milestone: portable 6-stem SDK, social presence/scrobbling, shared R2 updater and stream-resolution fast-path

- **`stems-sdk`**: Portable 6-stem (`drums, bass, other, vocals, guitar, piano`) controls, `StemMixStore`, 6-file `StemCache`, generic `StemBatchQueue`, `StemModelStore`, `StemStorage`, and pure-Kotlin `DemucsDsp` + `CanonicalStemWav` now live in `stems-sdk` and publish to Maven Local. Android (`app/.../stems/`, `AndroidModelStore`) and `desktop-stems` consume it while retaining platform MediaCodec/ONNX/AudioTrack and Rust/DirectML execution, and `examples/headless-stems` verifies 6-stem guitar/piano solo/mute, mix persistence, WAV cache install and STFT/iSTFT round-trip headlessly.
- **`social-sdk` presence & scrobble policies**: Platform-neutral Rich Presence visibility/hold timeout state machine (`PresencePolicy`) and scrobble threshold/pause/resume coordinator (`ScrobbleSessionCoordinator`) now live in `social-sdk`. Android's `DiscordPresencePolicy` and `ScrobbleManager` delegate to them, and `examples/headless-social` exercises them alongside the social HTTP client.
- **`update-sdk`**: Shared SemVer pre-release comparison, R2 `latest.json` Android/Desktop manifest parsing, universal ABI fallback (`gms-mobile-arm64` -> `gms-mobile-universal`), SHA-256 validation, Desktop `exeSha256` staging checks and GitHub commit parsing now live in `update-sdk`. Android's `Updater` and Desktop's `DesktopUpdateCoordinator` delegate to it, and `examples/headless-updater` exercises both manifest schemas headlessly.
- **`stream-resolver` desktop fast-path policy**: `DesktopStreamResolutionMode`, `configuredDesktopStreamResolutionMode`, `resolveDesktopStream`, `allowsDirectAudioFastPath` and `directAudioFastPathUrl` moved into `stream-resolver` with JUnit 5 tests, completing the extraction noted in `SDK_DESKTOP_INVENTORY.md`.

## Milestone: full packaged Android APK, GraalVM Native Image MSI and live machine verification

- **Automated unit & sidecar suites**: 1,446 JVM and Android unit tests passed across all SDK and application modules (`:app:testGmsMobileUniversalDebugUnitTest` 142 tests, `:desktopApp:test` 110 tests, `:desktop-backend:test` 123 tests, `:stems-sdk:test` 21 tests, `:social-sdk:test` 18 tests, `:update-sdk:test` 14 tests, `:discovery-sdk:test` 43 tests, `:stream-resolver:test` 57 tests), plus 7 Rust tests in `stem-worker` (`cargo test --locked` and `cargo build --release --locked`).
- **Standalone Maven Local consumers**: All 19 standalone Gradle consumer projects under `examples/*` resolved `moe.rukamori.archivetune:<artifact>:0.1.0-SNAPSHOT` from `mavenLocal()` without ArchiveTune UI dependencies.
- **Android packaged debug APK**: Assembled `app/build/outputs/apk/gmsMobileUniversal/debug/app-gms-mobile-universal-debug.apk` (`151,173,841` bytes / `144.17 MiB`, SHA-256 `0f9fd8687187acd275ebcfb4ee9069e54b51bf847c737dfe7ca8bb31d9e8145d`).
- **Desktop production GraalVM Native Image & Windows MSI**: Built via `msiNative` (`NativeImageOptimization.SIZE`, `-PnativeImageBuildHeap=6g`, Essentia `waf` bounded to 4 default jobs unless `ARCHIVETUNE_ESSENTIA_JOBS` is set), producing `desktopApp/build/compose/binaries/main-native/app/ArchiveTune/ArchiveTune.exe` (`162,988,032` bytes / `155.44 MiB`, SHA-256 `7314ecd8b63a697a98df738147f8664a4685c79fde0f5c0c8f10771fe8fe998e`) and `desktopApp/build/compose/binaries/main-native/msi/ArchiveTune-15.10.0.msi` (`144,167,093` bytes / `137.49 MiB`, SHA-256 `7b1c7684befa1ecffee300781463e9562b99a2892ec55194f9a80195b4f536f3`).
- **Live Windows Native Image runtime smoke**: Executed the staged `ArchiveTune.exe` on Windows 11 x64 (`Substrate VM 25.0.3+9`, bundled libVLC `3.0.21 Vetinari`, Essentia audio-analysis DLL, isolated `ARCHIVETUNE_HOME`). Verified Compose/Skiko window creation (`window-ready`), anonymous Home feed/continuation fetch, local WAV playback, seek to 30s, pause/resume, atomic settings/home/queue persistence, deterministic `SdkLifecycle` shutdown, and `ExitCode=0` (`{"event":"complete","success":true,"local_playback":true,"seek":true,"pause_resume":true}`).

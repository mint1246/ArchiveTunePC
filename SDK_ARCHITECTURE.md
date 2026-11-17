# ArchiveTune SDK Architecture & Integration Guide

> **Status:** Experimental incremental SDK extraction (`0.1.0-SNAPSHOT` local Maven artifacts).  
> The Android (`app/`) and Desktop (`desktopApp/`) applications remain the behavioral reference. Publishing or resolving an individual module does not start background services, network connections, or playback sessions on its own.

---

## 1. Architectural Overview

ArchiveTune separates platform-independent domain policies, state machines, and media contracts from platform-specific audio engines, storage, and UI shells.

```text
+-------------------------------------------------------------------------+
|                           Host Applications                             |
|   Android App (app/)  |  Desktop App (desktopApp/)  |  Headless / Unity |
+-------------------------------------------------------------------------+
                                    |
                                    v
+-------------------------------------------------------------------------+
|                            Public SDK API                               |
|  PlaybackController, PlaybackSessionHost, SdkLifecycle,                 |
|  StreamResolutionCoordinator, YouTubeAccountCoordinator,                |
|  PlaylistTagPolicy, LibraryTopMixPolicy, AiContentFilterMatcher, ...    |
+-------------------------------------------------------------------------+
                                    |
                                    v
+-------------------------------------------------------------------------+
|                         Shared Feature Engine                           |
|  Queue ordering/resumption, crossfade math, sleep timer,                |
|  single-flight caches, fuzzy matching, beat/chord DSP, sync policies    |
+-------------------------------------------------------------------------+
                                    |
                                    v
+-------------------------------------------------------------------------+
|                         Platform Abstractions                           |
|  PlaybackSourceResolver, PlaybackQueuePersistence, YouTubeAccountStore, |
|  EqualizerPreferenceReader/Writer, TogetherClock, monotonic timers      |
+-------------------------------------------------------------------------+
                                    |
                                    v
+-------------------------------------------------------------------------+
|                        Platform Implementations                         |
|  Android: Media3 AAR (android-playback), DataStore, Room, MediaCodec    |
|  Desktop: libVLC/VLCJ (desktop-playback), DPAPI, Rust+DirectML runner   |
+-------------------------------------------------------------------------+
```

### Design Principles

1. **Opt-in modules:** Consumers depend only on the modules they use (`mavenLocal()` coordinates under `moe.rukamori.archivetune:<artifact>:0.1.0-SNAPSHOT`).
2. **Caller-owned I/O & secrets:** Headless SDK modules never hardcode DataStore, Room, DPAPI, or `ContentResolver`. Callers inject storage, HTTP/YouTube backends, and clocks.
3. **No implicit background threads:** Components do not spawn background services or global singletons at class-load time; scopes and `AutoCloseable` resources are explicitly owned and closed.
4. **Honest capability boundaries:** Kotlin/JVM reuse between Android and Desktop does not imply iOS/Native compatibility or GraalVM Native Image readiness without explicit verification.

---

## 2. Module Catalog & Dependency Tiers

All modules are configured in `settings.gradle.kts` and target **JDK 21** / **Kotlin 2.4.10** (Android AARs target `compileSdk = 37` minor `2`, `minSdk = 26`).

### Tier 1 — Foundation & Lifecycle

| Module | Artifact / Role | Key Types & Boundaries |
| --- | --- | --- |
| `playback-core` | Published JVM (`playback-core`) | `PlaybackController`, `PlaybackSessionHost`, `PlaybackQueue`, `PlaybackSnapshot`, `PlaybackFailure`, `NextTrackPrefetcher`, `SleepTimerController`, `equalPowerCrossfadeGains`, `AudioOutputPolicy`, `PlaybackParameterPersistence`, `QueueReorderPlanner`, `PlaybackResumptionPlanner`. Zero player implementation. |
| `sdk-lifecycle` | Published JVM (`sdk-lifecycle`) | `SdkLifecycle` — deterministic child `SupervisorJob` `CoroutineScope`s, named `onClosing` hooks, and reverse-order `AutoCloseable` cleanup. |
| `desktop-resource-core` | Desktop JVM | Bounded content cache and single-flight request coordinator for desktop repositories. Callers own account-scoped keys. |
| `core` | Git submodule (`core/`) | Shared InnerTube/YouTube models, page parsers, and HTTP client (`YouTube`). Not yet an isolated multi-client public SDK. |

### Tier 2 — Streaming, Playback & Audio

| Module | Artifact / Role | Key Types & Boundaries |
| --- | --- | --- |
| `stream-resolver` | JVM | `StreamResolutionCoordinator` (bounded in-memory cache, single-flight coalescing, priority, expiry), `NextStreamPreloadCoordinator`, `PlaybackStreamRecoveryTracker`, `StreamChunkResolver`, `AudioQuality`, `PlaybackStreamResolver`. |
| `android-playback` | Published Android AAR (`android-playback`) | `Media3PlaybackSession` (`createMedia3PlaybackSession`), `Media3PlaybackController`, `AndroidPlaybackQueueStore`, `resolvingPlaybackSourceUpstream`, `resolvingPlaybackUpstream`, `cachedPlaybackDataSourceFactory`, `setPlaybackAddress`, `ChunkedDataSource`, `Media3PlayerStateObserver`, `Media3PlaybackMappings`. |
| `desktop-playback` | Published JVM (`desktop-playback`) | `createDesktopPlaybackSession`, `VlcjPlaybackController`, `DesktopCrossfade`, `DesktopAudioEffects` (libVLC live `aout` pitch/speed), `DesktopAudioOutput`, `PlaybackQueueStore`, `VlcRuntime`. Requires caller-supplied `PlaybackSourceResolver` and compatible libVLC runtime. |
| `desktop-stream-playback` | Desktop JVM | Desktop stream selection, completed-download fallback, signed-source memory caching, and HTTP recovery. |
| `equalizer-sdk` | Published JVM (`equalizer-sdk`) | `EqualizerPreferences`, `EqualizerPreferenceReader`/`Writer`, `EqualizerProfileIds`. |
| `audio-analysis-sdk` | Published JVM (`audio-analysis-sdk`) | UI-independent PCM beat tracker, beat-timeline sampling, and energy detector. |
| `desktop-audio-analysis-runtime` | Desktop JVM | `DesktopAudioAnalysisRepository` with v1 cache format and 44.1 kHz mono decode callback. |
| `chords-sdk` | Published JVM (`chords-sdk`) | Shared Songsterr authored-chord client and models. |
| `chord-neural-core` | Published JVM (`chord-neural-core`) | Android/headless chord model asset verification, DSP, and streaming resampler. |
| `desktop-neural-chords` | Desktop JVM | Local neural chord analyzer delegating to the Rust/DirectML runner with cancellation monitoring. |
| `desktop-model-runtime` | Published JVM (`desktop-model-runtime`) | Verified ONNX/DirectML model manifest handling, SHA-256 download coalescing, and Windows runner lookup (`archivetune-model-runner.exe`). |
| `desktop-stems` | Desktop JVM | `DesktopStemCoordinator` for 6-stem (`drums, bass, other, vocals, guitar, piano`) orchestration and `StemPlaybackBridge`. |
| `sponsorblock-core` | JVM | Segment validation, category/action filtering, rounding, ordering, and skip planning shared by Android and Desktop. |
| `desktop-sponsorblock` | Desktop JVM | SponsorBlock HTTP client, segment cache, and desktop playback coordinator. |

### Tier 3 — Discovery, Library, Playlists & Downloads

| Module | Artifact / Role | Key Types & Boundaries |
| --- | --- | --- |
| `discovery-sdk` | JVM | `DiscoveryContentCache`, `DiscoveryFanOut`, `DiscoveryKeys`, `DiscoveryLists`, `DiscoverySuggestionsPolicy`, `QuickPickSeedPolicy`, `RelatedSongsResolver`. |
| `playlist-sdk` | Published JVM (`playlist-sdk`) | `PlaylistCsvExporter`, `PlaylistOrdering` (`playlistSongOccurrences`, `sortPlaylistSongOccurrences`, `filterLibraryContent`), `AddToPlaylistPickerSession`, `PlaylistTagPolicy`, `LibraryTopMixPolicy`, `AiContentFilterMatcher`. |
| `playlist-import-sdk` | JVM | Fuzzy matching and ordered, bounded-concurrency import song resolution (preserves duplicates and order). |
| `desktop-spotify-import` | Desktop JVM | Real Spotify playlist importer wired to `desktop-backend` and `:spotifycore`. |
| `local-media-sdk` | JVM | `EmbeddedLyricsParser` (ID3v2, MP4 `©lyr`, FLAC, Ogg/Opus) and `SupportedLocalAudio` policy. |
| `desktop-library` | Desktop JVM | `DesktopLibrary` local scanner and song-to-album metadata index. |
| `download-sdk` | JVM | Download grouping, cache policies, and platform-neutral download state models. |
| `desktop-downloads` | Published JVM (`desktop-downloads`) | `DesktopDownloadManager` (512 KiB range bursts, 64 KiB buffer, full-response fallback). |
| `desktop-download-orchestration` | Desktop JVM | Retry tracking, resolution queue, and download directory reconfiguration. |

### Tier 4 — Lyrics, Accounts, Social, Party, Statistics & Backup

| Module | Artifact / Role | Key Types & Boundaries |
| --- | --- | --- |
| `lyrics-sdk` | Published JVM (`lyrics-sdk`) | Bounded single-flight lyrics request cache and provider-order selection. |
| `lyrics-timeline` | JVM | Prepared lyric models, word/grapheme timing, and instrumental-gap insertion. |
| `desktop-lyrics-engine` | Desktop JVM | Playback-observing lyrics/chord presenter and timeline parsing. |
| `desktop-lyrics-translation` | Desktop JVM | Desktop lyrics translation service and credential/cache orchestration. |
| `desktop-canvas` | Desktop JVM | Canvas provider lookup, cancellation, and video caching. |
| `account-sdk` | Published JVM (`account-sdk`) | `SavedAccount` JSON codec, `YouTubeCookieJsonImporter`, `YouTubeAccountCoordinator` (verify -> lookup -> `DataSyncId` -> persist). |
| `desktop-auth` | Desktop JVM | `SecureSessionStore` (Windows DPAPI), `DesktopSessionManager`, `ManagedChromiumLogin`, `DesktopBotGuardPoTokenProvider`. |
| `social-sdk` | Published JVM (`social-sdk`) | Social HTTP client and wire models (caller supplies token). |
| `social-session` | JVM | Social session orchestration with caller-injected login, storage, and transport. |
| `social-realtime` | JVM | Optional WebSocket client (`close()` owned by caller). |
| `social-login` | JVM | Optional nonce-checked loopback OAuth callback flow. |
| `desktop-social` | Desktop JVM | Desktop Last.fm authorization, playback observation, and scrobbling. |
| `together-sdk` | Published JVM (`together-sdk`) | Play Together models, wire messages, permission decisions, `TogetherGuestPlaybackPlanner`, `TogetherPlaybackSync`, `TogetherLink`, `TogetherDesktopPartyProtocol`. |
| `together-transport` | Published JVM (`together-transport`) | Direct WebSocket client/host server and online HTTP API (`AutoCloseable`). |
| `desktop-play-together` | Desktop JVM | `PlayTogetherManager` desktop party host/guest orchestration. |
| `statistics-sdk` | JVM | Audible-time listening tracker and overview/trend query service (monotonic elapsed deltas, `min(30s, 50% duration)` play-count threshold). |
| `desktop-statistics` | Desktop JVM | `DesktopStatisticsStore` and `DesktopListenTracker` (compact aggregate persistence). |
| `backup-sdk` | JVM | Archive handling, settings XML, validation, and backup schedule policy. |
| `desktop-settings` | Published JVM (`desktop-settings`) | Desktop settings normalization, keybinds, and `Properties`-backed session. |

---

## 3. Public API Contracts: Lifecycle, State & Error Handling

### Initialization & Deterministic Shutdown (`SdkLifecycle` & `PlaybackSessionHost`)

- **`SdkLifecycle` (`sdk-lifecycle`)**:
  - Create via `SdkLifecycle(parentContext)` or `SdkLifecycle.forScope(parentScope)`.
  - Obtain isolated child scopes via `lifecycle.scope()` (each child has its own `SupervisorJob` so one component failure does not cancel siblings).
  - Register `AutoCloseable` instances via `lifecycle.register(component)` and idempotent hooks via `lifecycle.onClosing(name) { ... }`.
  - `lifecycle.close()` is idempotent and thread-safe: it cancels child scopes first, runs `onClosing` hooks in reverse order, closes registered components in reverse order, and aggregates any exceptions via `addSuppressed` without aborting remaining cleanups.
- **`PlaybackSessionHost` (`playback-core`)**:
  - Coordinates any `PlaybackController` with opt-in `PlaybackQueuePersistence` and `NextTrackPrefetcher` (`prefetchResolver`).
  - Passing `null` for `queuePersistence` or `prefetchResolver` starts zero corresponding observers or background work.
  - `close()` cancels its `sessionScope`, closes the prefetcher, flushes a final queue checkpoint if configured, and closes `controller` when `ownsController == true`.

### State Observation & Error Handling

- **Observable state:** `PlaybackController` exposes `val state: StateFlow<PlaybackSnapshot>` and `val events: SharedFlow<PlaybackEvent>` (`MediaTransition`, `QueueChanged`, `Failure`).
- **Structured errors:** Playback errors surface as `PlaybackFailure(message: String, kind: PlaybackFailure.Kind, recoverable: Boolean)` where `Kind` is one of `ENGINE_UNAVAILABLE`, `SOURCE_RESOLUTION`, `NETWORK`, `UNSUPPORTED_FORMAT`, `LOCAL_FILE_MISSING`, `ENGINE`, `UNKNOWN`.
- **Result & Cancellation contract:**
  - `PlaybackSourceResolver.resolve(item)` and `YouTubeAccountCoordinator` methods return `kotlin.Result<T>`.
  - `kotlinx.coroutines.CancellationException` is **never** swallowed into `Result.failure(...)` or treated as an ordinary error; SDK coordinators (`StreamResolutionCoordinator`, `YouTubeAccountCoordinator`, `PlaybackSessionHost`, `SdkLifecycle`) rethrow `CancellationException` to preserve structured concurrency.

---

## 4. Authentication & Security Boundaries

1. **No secret persistence in caches or queues:**
   - `PlaybackSource.Deferred(key)` keeps only the stable media ID in the queue; signed stream URLs and request headers are resolved immediately before playback.
   - `PlaybackQueuePersistence.save(snapshot)` implementations **must** strip transient credentials or request headers before writing to disk.
   - `StreamResolutionCoordinator` holds resolved `ResolvedAudioStream` URLs and headers **in memory only** and keys its cache by `authFingerprint`.
2. **Caller-owned protected storage:**
   - `SavedAccount` JSON (`account-sdk`) contains `innerTubeCookie`, `visitorData`, and `dataSyncId` and **must** be stored in caller-owned protected storage (e.g., encrypted Android `DataStore` or Windows DPAPI `SecureSessionStore` in `desktop-auth`).
   - Never log cookies, `Authorization` headers, PoTokens, or signed `googlevideo.com` URLs.
3. **Account-scoped caching:**
   - `desktop-resource-core` and `discovery-sdk` caches do not automatically infer the active user; callers must include an account/session key in cache keys and invalidate on account switch.

---

## 5. Minimal Working Examples

### (a) Headless JVM Client (`playlist-sdk` + `SdkLifecycle`)

See [`examples/headless-playlist/src/main/kotlin/example/HeadlessPlaylist.kt`](examples/headless-playlist/src/main/kotlin/example/HeadlessPlaylist.kt) for the full runnable example.

```kotlin
import kotlinx.coroutines.Dispatchers
import moe.rukamori.archivetune.sdk.lifecycle.SdkLifecycle
import moe.rukamori.archivetune.sdk.playlist.AiContentFilterMatcher
import moe.rukamori.archivetune.sdk.playlist.PlaylistCsvExporter
import moe.rukamori.archivetune.sdk.playlist.PlaylistCsvSong

fun main() {
    SdkLifecycle(Dispatchers.Default).use { lifecycle ->
        val blocked = AiContentFilterMatcher.parseChannelList("@AI_Slop_Channel\n")
        val songs = listOf(PlaylistCsvSong(id = "v1", title = "Track", artists = listOf("Artist")))
        val filtered = AiContentFilterMatcher.filterItems(songs, enabled = true, blockedChannelKeys = blocked) {
            it.artists.asSequence()
        }
        val csvBytes = PlaylistCsvExporter.encode(PlaylistCsvExporter.records(filtered))
        check(csvBytes.isNotEmpty())
    }
}
```

### (b) Desktop VLC Session (`VlcjPlaybackController` + `PlaybackSessionHost`)

See [`examples/headless-desktop/src/main/kotlin/example/HeadlessDesktop.kt`](examples/headless-desktop/src/main/kotlin/example/HeadlessDesktop.kt) for the standalone Gradle project.

```kotlin
import java.nio.file.Path
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.playback.api.*
import moe.rukamori.archivetune.playback.desktop.VlcjPlaybackController
import moe.rukamori.archivetune.playback.session.PlaybackSessionHost

fun main(args: Array<String>) = runBlocking {
    val path = Path.of(args.single()).toAbsolutePath().normalize()
    val resolver = PlaybackSourceResolver { item ->
        val file = item.source as? PlaybackSource.LocalFile
            ?: return@PlaybackSourceResolver Result.failure(IllegalArgumentException("Expected LocalFile"))
        Result.success(ResolvedPlaybackSource(uri = Path.of(file.path).toUri().toString()))
    }

    PlaybackSessionHost(
        controller = VlcjPlaybackController(resolver),
        ownsController = true,
    ).use { session ->
        val item = PlaybackItem(
            metadata = PlaybackMetadata(id = path.toString(), title = path.fileName.toString()),
            source = PlaybackSource.LocalFile(path.toString()),
        )
        session.replaceQueue(listOf(item))
        val playing = withTimeout(20_000) {
            session.state.first { it.isPlaying || it.status == PlaybackStatus.ERROR }
        }
        check(playing.isPlaying) { "Playback failed: ${playing.error}" }
    }
}
```

### (c) Android Media3 AAR Session (`android-playback`)

See [`examples/android-playback-consumer/`](examples/android-playback-consumer/) for the complete standalone Android consumer.

```kotlin
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import moe.rukamori.archivetune.playback.android.Media3PlayerStateObserver
import moe.rukamori.archivetune.playback.android.resolvingPlaybackUpstream
import moe.rukamori.archivetune.playback.android.setPlaybackAddress

fun buildDeferredMedia3Player(
    context: Context,
    resolveStreamUri: (mediaId: String) -> String,
): Pair<ExoPlayer, Media3PlayerStateObserver> {
    val upstream = resolvingPlaybackUpstream(
        context = context,
        httpFactory = DefaultHttpDataSource.Factory(),
        chunkLength = 512L * 1024L,
    ) { request, mediaId ->
        request.buildUpon().setUri(resolveStreamUri(mediaId)).build()
    }

    val player = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(upstream))
        .build()

    val item = MediaItem.Builder()
        .setPlaybackAddress(mediaId = "videoId123", cacheKey = "videoId123")
        .build()
    player.setMediaItem(item)
    player.prepare()

    return player to Media3PlayerStateObserver(player)
}
```

---

## 6. RepShift / Unity 6.6 (Meta Quest 2) Integration Guide

To embed ArchiveTune audio playback, discovery, and playlist logic into **Unity 6.6 on Meta Quest 2** (Android arm64, API 29+/32), use a thin **Android Kotlin library bridge** compiled into an `.aar` for Unity's `Assets/Plugins/Android/` directory.

### Recommended Module Subset

Include **only** the headless/playback artifacts required:

- `moe.rukamori.archivetune:playback-core:0.1.0-SNAPSHOT`
- `moe.rukamori.archivetune:android-playback:0.1.0-SNAPSHOT`
- `moe.rukamori.archivetune:playlist-sdk:0.1.0-SNAPSHOT`
- `moe.rukamori.archivetune:account-sdk:0.1.0-SNAPSHOT`
- `:stream-resolver` & `:discovery-sdk` (published locally as needed)

**Explicitly exclude** from the Unity Android build:
- Jetpack Compose UI (`compose-*`, `material3`, `accompanist-*`)
- Room, WorkManager, Hilt (unless your bridge uses Hilt independently)
- `:lastfm`, `:social-*`, `:shazamkit`, `:desktop-*`, ONNX Runtime / STEM separation, and embedded WebViews

### Thin Kotlin Wrapper + Unity C# `AndroidJavaObject` Bridge

1. **Kotlin Wrapper (`RepShiftAudioBridge.kt`):**
   - Owns a single `SdkLifecycle(Dispatchers.Main.immediate)` and `ExoPlayer` wired with `resolvingPlaybackUpstream` + `Media3PlayerStateObserver`.
   - Exposes simple primitive/JSON methods callable from JNI (`playTrack(videoId: String)`, `pause()`, `resume()`, `seekTo(positionMs: Long)`, `getStateJson(): String`, `release()`).
   - Ties `onPause()` / `onResume()` / `onDestroy()` to Unity's `UnityPlayer.currentActivity` lifecycle so audio pauses or releases cleanly when the Quest headset is removed or the app backgrounds.
2. **Unity C# Bridge (`ArchiveTuneBridge.cs`):**

```csharp
using UnityEngine;

public sealed class ArchiveTuneBridge : MonoBehaviour
{
    private AndroidJavaObject _bridge;

    void Awake()
    {
        using var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer");
        var activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity");
        _bridge = new AndroidJavaObject("com.repreview.audio.RepShiftAudioBridge", activity);
    }

    public void PlayTrack(string videoId) => _bridge?.Call("playTrack", videoId);
    public void Pause() => _bridge?.Call("pause");
    public void Resume() => _bridge?.Call("resume");

    void OnApplicationPause(bool paused)
    {
        if (paused) _bridge?.Call("onHostPause");
    }

    void OnDestroy()
    {
        _bridge?.Call("release");
        _bridge?.Dispose();
        _bridge = null;
    }
}
```

### Quest 2 Memory & Resource Guidelines

- **6 GB unified RAM budget:** Quest 2 shares memory between CPU, GPU, and VR compositor. Keep Media3 `SimpleCache` bounded (e.g., `<= 64–128 MiB` or disabled for pure streaming) and avoid keeping large album-art bitmaps in Java heap.
- **Zero idle background services:** Do not register Android `MediaLibraryService` / foreground services unless your VR experience intentionally supports background audio outside the app.
- **Bounded coroutine scopes:** Cancel `SdkLifecycle` in `OnDestroy()` / scene teardown so no OkHttp pools, `StreamResolutionCoordinator` jobs, or `ExoPlayer` decoders leak across Unity domain reloads.
- **Baseline:** No Quest 2 physical-device memory baseline has been recorded in this repository yet; profile with Android Studio Profiler / `adb shell dumpsys meminfo` before shipping.

### Third-Party Policy & Licensing Considerations

- **GPL-3.0 License:** ArchiveTune modules carry GPL-3.0 headers (`Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5`). Linking GPL-3.0 libraries into a distributed application triggers GPL-3.0 copyleft obligations for the combined work unless separate licensing arrangements exist.
- **YouTube Terms of Service & InnerTube:** Direct InnerTube / stream extraction is subject to YouTube's Terms of Service, rate limits, PoToken/BotGuard requirements, and regional/account schema variance. Do not ship unvetted automated scraping or credential-sharing flows in commercial third-party apps.

---

## 7. Build, Publication & Native Image Verification

### Publish Experimental Artifacts to Maven Local

```powershell
.\gradlew.bat `
  :playback-core:publishToMavenLocal `
  :sdk-lifecycle:publishToMavenLocal `
  :playlist-sdk:publishToMavenLocal `
  :account-sdk:publishToMavenLocal `
  :android-playback:publishToMavenLocal `
  :desktop-playback:publishToMavenLocal `
  --no-daemon
```

### Run Standalone Consumer Examples

```powershell
.\gradlew.bat -p examples/headless-playlist run --no-daemon
.\gradlew.bat -p examples/headless-equalizer run --no-daemon
.\gradlew.bat -p examples/headless-playback run --no-daemon
```

### GraalVM Native Image Constraints (Desktop)

- Production Windows builds target **GraalVM Native Image** (`.\gradlew.bat msiNative --no-daemon`, or `.\gradlew.bat nativeCheck -PnativeQuickBuild=true --no-daemon` for fast iteration).
- Pure-JVM SDK modules (`playlist-sdk`, `playback-core`, `sdk-lifecycle`, `equalizer-sdk`, `discovery-sdk`, etc.) avoid uncontrolled runtime reflection and keep Compose annotations `compileOnly` so headless/native consumers do not pull UI dependencies.
- Any module touching JNA, libVLC, or Windows APIs (`desktop-playback`, `desktop-auth`, `windows-media-integration`) must be verified with `nativeCheck` because SubstrateVM rejects invalid JNA `Structure`/`Union` layouts at runtime even when JVM tests pass.

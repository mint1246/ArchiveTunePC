# Android Media3 playback adapters

`android-playback` exposes the Media3 `PlaybackController` adapter, turnkey `Media3PlaybackSession`, durable JSON queue persistence, queue traversal/transport helpers, state observer, and playback data source factories used by the Android app and standalone consumers.

## Key components

- **`createMedia3PlaybackSession` / `Media3PlaybackSession`**: Turnkey standalone session wiring `ExoPlayer`, `DefaultMediaSourceFactory(resolvingPlaybackSourceUpstream(...))`, `Media3ShuffleOrderCapability`, `Media3PlaybackController` (with `useDataSourceDeferredResolution = true`), optional `AndroidPlaybackQueueStore`, and optional next-track prefetching via `PlaybackSessionHost`. Call `close()` to flush the queue checkpoint, detach listeners, and release the owned `ExoPlayer`.
- **`Media3PlaybackController`**: Implements `PlaybackController` on top of a caller-supplied Media3 `Player`. Set `useDataSourceDeferredResolution = true` when the player uses `resolvingPlaybackUpstream` / `resolvingPlaybackSourceUpstream` so `PlaybackSource.Deferred(key)` items resolve lazily on Media3's loading thread (supporting automatic queue advancement, gapless pre-buffering, and duplicate queue entries). When `useDataSourceDeferredResolution = false`, `Media3PlaybackController` resolves deferred items eagerly on user commands and asynchronously on automatic transitions when a `progressSamplingScope` or `playerDispatcher` is provided.
- **`resolvingPlaybackSourceUpstream` / `resolvingPlaybackUpstream`**: Adapts a `PlaybackSourceResolver` (or raw `DataSpec` resolver) onto Media3's `ResolvingDataSource.Factory` so deferred items resolve on Media3's loading thread, including during automatic queue advancement. Use `cachedPlaybackDataSourceFactory` if the host also supplies Media3 playback and download caches. Local content and direct HTTP URLs keep their existing URI.
- **`AndroidPlaybackQueueStore`**: Implements `PlaybackQueuePersistence` using atomic temp-file writes, a 2,000-item cap, and automatic stripping of `PlaybackSource.Remote.headers` so transient credentials are never written to disk.
- **`Media3PlayerStateObserver`**: Lightweight observer exposing `playbackState`, `queueWindows`, and transport state flows for an existing `Player`.

## Building & publishing

Build using JDK 21, Android SDK 37.2, and Kotlin 2.4.10. Publish the AAR and the JVM playback contracts to Maven Local for use in a separate Android project:

```powershell
.\gradlew.bat :playback-core:publishToMavenLocal :android-playback:publishToMavenLocal
```

Use `implementation("moe.rukamori.archivetune:android-playback:0.1.0-SNAPSHOT")` in the consumer and enable `google()`, `mavenCentral()`, and `mavenLocal()` for dependency resolution.

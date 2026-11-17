# Android Media3 playback adapters

`android-playback` exposes the queue traversal, transport helpers, state observer, and playback data source factories used by the Android app. It accepts a caller-supplied Media3 `Player`, including an existing `ExoPlayer`. Construct and close `Media3PlayerStateObserver` on the player's application thread. The observer removes its listener on close. The caller owns the player and any background media session.

For a deferred queue, build each `MediaItem` with `setPlaybackAddress(mediaId, cacheKey = mediaId)` and install `resolvingPlaybackUpstream` in your Media3 media source factory. The caller supplies a resolver that updates the `DataSpec` with a playable URI and any required headers. Media3 invokes it on its loading thread when opening each keyed track, including during automatic queue advancement. Use `cachedPlaybackDataSourceFactory` if the host also supplies Media3 playback and download caches. Local content and direct HTTP URLs keep their existing URI.

Build using JDK 21, Android SDK 37.2 and Kotlin 2.4.10. Publish the AAR and the JVM playback contracts to Maven Local for use in a separate Android project:

```powershell
.\gradlew.bat :playback-core:publishToMavenLocal :android-playback:publishToMavenLocal
```

Use `implementation("moe.rukamori.archivetune:android-playback:0.1.0-SNAPSHOT")` in the consumer and enable `google()`, `mavenCentral()`, and `mavenLocal()` for dependency resolution. Read `playbackState`, `queueWindows`, or the other flows from `Media3PlayerStateObserver`, alongside your player's own media setup. Authenticated YouTube resolution, ArchiveTune's Media3 service, and process restoration currently require app wiring.

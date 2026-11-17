# Independent Android playback consumer

This Android library resolves the published ArchiveTune Media3 AAR from Maven Local. It prepares a caller-supplied audio URI and exposes the same state observer and turnkey `Media3PlaybackSession` used by the ArchiveTune Android app.

`prepareDeferredSession` shows how to create a `Media3PlaybackSession` (backed by `ExoPlayer`, `Media3PlaybackController` with `useDataSourceDeferredResolution = true`, `resolvingPlaybackSourceUpstream`, and `AndroidPlaybackQueueStore`) for a list of `PlaybackItem`s with `PlaybackSource.Deferred` keys. `prepareDeferredQueue` also shows the lower-level `resolvingPlaybackUpstream` wiring directly on `ExoPlayer`. Close the session or release the player on the main thread when finished.

Use JDK 21 and Android SDK 37.2. The example sets Kotlin 2.4.10 to match the SDK's metadata. Run these commands from the ArchiveTune checkout:

```powershell
.\gradlew.bat :playback-core:publishToMavenLocal :android-playback:publishToMavenLocal
.\gradlew.bat -p examples/android-playback-consumer assembleDebug
```

The assembled example checks external dependency resolution and compilation. With an Android device or emulator attached, run `:connectedDebugAndroidTest` in this example to verify automatic advancement between deferred tracks and persisted queue restoration. The test generates its own audio and uses a local resolver, so it requires no YouTube account or network access.

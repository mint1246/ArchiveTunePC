# Independent Android playback consumer

This Android library resolves the published ArchiveTune Media3 AAR from Maven Local. It prepares a caller-supplied audio URI and exposes the same state observer used by the ArchiveTune Android app. The host supplies its Media3 player, starts playback, and closes the observer and player on the player's application thread.

`prepareDeferredQueue` also shows how to build an ExoPlayer for a list of stable track IDs. The caller supplies a stream resolver. The SDK's data source calls it as Media3 opens requests, so queued items do not need their URLs fetched before playback. The caller owns and releases the returned player.

Use JDK 21 and Android SDK 37.2. The example sets Kotlin 2.4.10 to match the SDK's metadata. Run these commands from the ArchiveTune checkout:

```powershell
.\gradlew.bat :playback-core:publishToMavenLocal :android-playback:publishToMavenLocal
.\gradlew.bat -p examples/android-playback-consumer assembleDebug
```

The assembled example checks external dependency resolution and compilation. With an Android device or emulator attached, run `:connectedDebugAndroidTest` in this example to verify automatic advancement between two deferred tracks. The test generates its own audio and uses a local resolver, so it requires no YouTube account or network access.

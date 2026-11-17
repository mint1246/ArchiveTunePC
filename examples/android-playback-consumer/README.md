# Independent Android playback consumer

This Android library resolves the published ArchiveTune Media3 AAR from Maven Local. It prepares a caller-supplied audio URI and exposes the same state observer used by the ArchiveTune Android app. The host supplies its Media3 player, starts playback, and closes the observer and player on the player's application thread.

Use JDK 21 and Android SDK 37.2. The example sets Kotlin 2.4.10 to match the SDK's metadata. Run these commands from the ArchiveTune checkout:

```powershell
.\gradlew.bat :playback-core:publishToMavenLocal :android-playback:publishToMavenLocal
.\gradlew.bat -p examples/android-playback-consumer assembleDebug
```

The assembled example checks external dependency resolution and compilation. Real playback requires an Android host with a playable URI.

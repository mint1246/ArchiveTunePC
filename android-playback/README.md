# Android Media3 playback adapters

`android-playback` exposes the queue traversal, transport helpers, and state observer used by the Android app. It accepts a caller-supplied Media3 `Player`, including an existing `ExoPlayer`. Construct and close `Media3PlayerStateObserver` on the player's application thread. The observer removes its listener on close. The caller owns the player, media item resolution, and any background media session.

Build using JDK 21 and Android SDK 37.2. Publish the AAR and the JVM playback contracts to Maven Local for use in a separate Android project:

```powershell
.\gradlew.bat :playback-core:publishToMavenLocal :android-playback:publishToMavenLocal
```

Use `implementation("moe.rukamori.archivetune:android-playback:0.1.0-SNAPSHOT")` in the consumer and enable `google()`, `mavenCentral()`, and `mavenLocal()` for dependency resolution. Read `playbackState`, `queueWindows`, or the other flows from `Media3PlayerStateObserver`, alongside your player's own media setup. YouTube stream resolution, ArchiveTune's Media3 service, and process restoration currently require additional app wiring.

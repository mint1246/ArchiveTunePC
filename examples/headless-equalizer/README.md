# Headless equalizer example

Publish `equalizer-sdk` to Maven Local from the repository root, then run this
standalone Gradle project with JDK 21:

```powershell
.\gradlew.bat :equalizer-sdk:publishToMavenLocal
.\gradlew.bat -p examples/headless-equalizer run
```

The example supplies an in-memory preferences adapter and reads back the band
levels using the SDK. It does not create an audio effect or play audio; a real
application provides persistent storage and its platform audio backend.

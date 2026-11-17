# Headless desktop settings

Publish the JVM artifact to Maven Local, then run the independent example using JDK 21.

```powershell
.\gradlew.bat :desktop-settings:publishToMavenLocal
.\gradlew.bat -p examples/headless-desktop-settings run
```

The example updates crossfade preferences through `DesktopSettingsSession` and verifies the persisted settings file. A session created with `forStore` borrows the store. Its owner closes the store to flush pending writes. Clients that only need temporary settings can use `inMemory()`.

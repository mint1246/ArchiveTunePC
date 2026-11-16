# SponsorBlock segment policy

This UI-independent JVM module validates and merges SponsorBlock skip ranges. Both Android and desktop retain their own request, authentication, lifecycle, and preferences integrations, and pass selected category API values to `normalizeSponsorBlockSegments`. It does not make network requests or start playback.

The API accepts raw start/end values in seconds, category, and action type; it returns sorted, merged millisecond ranges. Invalid, unselected, non-skip and sub-100 ms ranges are discarded. Callers own seeking and cancellation.

Build and publish the experimental local artifact with:

```powershell
.\gradlew.bat :sponsorblock-core:test :sponsorblock-core:publishToMavenLocal
```

Coordinates: `moe.rukamori.archivetune:sponsorblock-core:0.1.0-SNAPSHOT`. Desktop and Android use this module as a project dependency; a published desktop backend artifact is not yet available.

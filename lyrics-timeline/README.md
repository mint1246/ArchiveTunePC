# Lyrics timeline module

This JVM module contains the existing Android lyric entries, prepared-line models, synchronized active-line policy, provider-word-to-line mapping, UTF-16 grapheme segmentation, instrumental-gap insertion, and conversion from prepared lyrics to entries. It does not initialize Android, a UI, network providers, or a playback service. Android still owns lyric provider selection, preparation, translation/romanization, persistence, and rendering.

`lyricsGraphemeRanges` preserves the existing motion renderer's handling of combining marks, emoji modifiers, regional indicators and ZWJ sequences. It is not a claim of complete Unicode grapheme conformance. Android's animated renderer uses the same function.

Publish and run the independent example with JDK 21:

```powershell
.\gradlew.bat :lyrics-timeline:test :lyrics-timeline:publishToMavenLocal
.\gradlew.bat -p examples/headless-lyrics run
```

Experimental coordinates: `moe.rukamori.archivetune:lyrics-timeline:0.1.0-SNAPSHOT`. The current prepared-line API exposes Guava `ImmutableList` and `LyricsEntry` exposes coroutine state; these dependencies are included for compatibility with the existing application. Compose stability annotations are compile-only. This module is optional and should not be initialized for clients that do not need lyrics.

The moved timing tests and the full Android `gmsMobileUniversalDebug` unit suite pass; visual rendering, real providers, non-primary variants and Quest device behavior remain unverified.

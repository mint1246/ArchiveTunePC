# Android beat-analysis integration

The Android app currently integrates this module as a Gradle project dependency:

```kotlin
implementation(project(":audio-analysis-sdk"))
```

Its `AndroidBeatAudioDecoder` decodes and resamples media to mono PCM at `BeatTracker.SAMPLE_RATE`. The caller runs `BeatTracker.analyze(samples)` away from the main thread, persists the result in the existing Android cache, and cancels obsolete work on track changes. A UI can sample `sampleBeatTimeline(analysis.beatsSeconds, positionMs.toDouble())` at the actual playback position; it should not advance a synthetic clock while playback is paused.

`BeatEnergyDetector` can also accept FFT data and monotonic time. `AndroidBeatSyncController` owns the Android `Visualizer` and releases it independently of this module. No Android context or UI is initialized by constructing these analysis objects.

The existing Android app is the integration reference. The `gmsMobileUniversalDebug` Kotlin build and unit tests passed with Android SDK 37.2 and HotSpot JDK 21; device-specific Visualizer permissions, audio sessions, timing and output have not been verified in this migration.

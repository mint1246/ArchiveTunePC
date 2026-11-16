# ArchiveTune Audio Analysis SDK

This JVM module contains application-independent beat analysis primitives used by ArchiveTune's Android player visuals. It has no Android, Media3, file storage, or playback lifecycle dependency.

## API

- `BeatTracker.analyze(samples)` analyzes mono PCM samples at `BeatTracker.SAMPLE_RATE` (11,025 Hz) and returns a timestamp grid, estimated BPM, and analyzed duration.
- `BeatEnergyDetector` detects transient energy peaks; callers provide energy or Android Visualizer-compatible FFT data and a monotonic timestamp.
- `sampleBeatTimeline(beatsSeconds, positionMs)` samples the grid at a playback position for pulse, phase, interval, and local BPM.

The Android app retains audio decoding/resampling, cache format, prefetch/cancellation, and Visualizer lifecycle. Extraction preserves those boundaries and existing cache bytes and analysis algorithms.

Run JVM tests and publish an experimental local artifact with:

```powershell
.\gradlew.bat :audio-analysis-sdk:test :audio-analysis-sdk:publishToMavenLocal
```

A JVM Gradle consumer can then use `mavenLocal()` and depend on `moe.rukamori.archivetune:audio-analysis-sdk:0.1.0-SNAPSHOT`. The API is experimental; Android device compatibility has not yet been verified. This module does not decode audio or start a playback session.

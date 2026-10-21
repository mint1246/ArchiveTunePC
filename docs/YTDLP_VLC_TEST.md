# yt-dlp → libVLC playback integration test

`desktop-playback/src/test/kotlin/moe/rukamori/archivetune/playback/desktop/YtDlpPlaybackIntegrationTest.kt`

## What it proves

`YtDlpPlaybackIntegrationTest` is an **opt-in live** integration test for the desktop playback
path. It exercises the real production code, not a mock:

1. `ArchiveTuneJvmClient.resolveAudioStream(...)` (desktop backend, `YtDlpDesktopStreamExtractor`
   → `YtDlpStreamResolver` → a real `yt-dlp` + `quickjs-ng` subprocess) resolves a public video
   into a direct CDN audio URL plus the headers the CDN requires.
2. The resolved URL and headers are handed to the **existing** ranged callback transport
   `VlcjRangeHttpTransport` / `VlcjRangeCallbackMedia`.
3. **Real libVLC** (the system `libvlc.so`, loaded through a plain
   `MediaPlayerFactory("--no-video", "--quiet", "--aout=dummy")`) plays the callback media:
   it reaches the Playing state, advances its clock, seeks deep into the music video, pauses,
   resumes and is torn down again.

Assertions covered by the live test:

| Assertion | Where |
| --- | --- |
| the resolver returns a direct, unexpired, audio-only CDN URL | `yt-dlp resolved audio plays through the ranged callback transport` |
| the first **and** the tail byte range of the object are accepted by the CDN with the returned headers | `probeResolvedAudioStream(resolved)` → HTTP 206, bytes read, ranged response |
| every request the transport sends carries the required headers | `RangeRequestCapture` OkHttp interceptor (records header *names* only) |
| the transport really uses byte ranges instead of one full download | captured `Range` starts, including a range ≥ 1 MB inside the object |
| libVLC plays, advances, seeks, pauses and resumes | `PlaybackEvents` + `player.status()` |

A second, **hermetic** test in the same class
(`the ranged callback transport plays local ranged audio through libVLC`) proves the same
transport/libVLC contract against a local `HttpServer` that serves a synthetic WAV with
`Range` support. It needs no network and no YouTube, so the transport contract is still covered
when the live resolver is unavailable.

## Running it

The live test is opt-in and is **skipped** (JUnit assumption, not a silent pass) unless the
opt-in is present:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export ARCHIVETUNE_LIVE_YTDLP=1
export ARCHIVETUNE_YTDLP=/path/to/yt-dlp
export ARCHIVETUNE_QUICKJS=/path/to/qjs
./gradlew :desktop-playback:test --tests '*YtDlpPlaybackIntegrationTest*'
```

* `ARCHIVETUNE_LIVE_YTDLP=1` — enables the live test.
* `ARCHIVETUNE_YTDLP` / `ARCHIVETUNE_QUICKJS` — the resolver binaries. The matching system
  properties `archivetune.ytdlp` / `archivetune.quickjs` work as an alternative, e.g.
  `./gradlew :desktop-playback:test -Darchivetune.ytdlp=/path/to/yt-dlp`.
* `ARCHIVETUNE_LIVE_YTDLP_VIDEO` — optional video id override (default `dQw4w9WgXcQ`).

`desktop-playback/build.gradle.kts` forwards these variables into the test JVM and adds them to
the task inputs, so a cached result of a non-opt-in run is never reused for an opt-in run.

Use the **root** Gradle build. The standalone `desktop-playback/settings.gradle.kts` does not
include `:desktop-backend`, which the test needs:

```bash
./gradlew :desktop-playback:test        # root build (required)
./gradlew -p desktop-playback test       # standalone: core tests; live class excluded
```

The hermetic test only needs a system libVLC (`/usr/lib/libvlc.so` and friends) and is skipped
with an assumption when no libVLC is installed. Once live mode is explicitly enabled,
missing executable paths fail the live test instead of silently skipping it.

## Linux notes

* This test was added before Linux runtime discovery was integrated. The production locator
  now supports system Linux libVLC and gates Windows audio options by platform. The test still
  creates its own `MediaPlayerFactory("--no-video", "--quiet", "--aout=dummy")` through
  JNA/ldconfig, so it verifies the resolver/callback contract independently of app startup.
* `VlcjRangeCallbackMedia` and `VlcjRangeHttpTransport` are used exactly as production uses them;
  the test does not patch production runtime discovery.
* The test never touches an account session: the client is created with an empty
  `PlaybackAuthState()` and no cookie is written or forwarded.

## Output hygiene

* The signed URL, header values, cookies and raw resolver diagnostics are never written to the
  test log; assertion messages only carry byte offsets and header *names*.
* libVLC runs with `--quiet`, so it does not print the media URL.
* Gradle's HTML/XML test reports live under `desktop-playback/build/` (git-ignored). Do not
  commit them.

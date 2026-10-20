# ArchiveTune Linux whole-application benchmark: legacy resolver vs yt-dlp

This report measures the real Linux desktop application, not a resolver-only JVM
harness. It compares the exact baseline legacy resolver with the production
yt-dlp resolver on the final `feature/ytdlp-resolver` candidate. It covers the
Compose/AWT window, UI/services/home startup, idle footprint, six real
audio-stream resolutions, post-extraction idle, and every observed child
process.

## Result summary

| Metric | Baseline `8e4bfb314` (legacy) | Final `dedcb8aa7` (yt-dlp) |
| --- | ---: | ---: |
| Successful resolutions | 0 / 6 | **6 / 6** |
| Idle max RSS (MiB) | 314.78 | 321.54 |
| Extraction peak, simultaneous tree RSS (MiB) | 318.64 | **670.42** |
| Extraction peak PSS (MiB) | 290.29 | **632.59** |
| Post-idle max RSS (MiB) | 312.72 | 321.00 |
| Median resolution latency (ms) | 888.0 (failed attempts) | 2361.0 |
| Max resolution latency (ms) | 1095 (failed attempt) | 7778 |
| Max simultaneous processes | 1 | 4 |

The extraction number is the maximum of **simultaneous process-tree totals**,
not a sum of per-process peak maxima. Baseline failures are not evidence of faster
successful extraction or lower memory for successful playback. The labeled interim
run is retained in a separate artifact; the table uses the final run's paired baseline.
The final candidate's peak sample decomposed as:

| Process | Category | RSS at peak sample (MiB) |
| --- | --- | ---: |
| app JVM (`java`) | jvm | 320.25 |
| `yt-dlp` (session leader) | yt_dlp | 2.41 |
| `yt-dlp` (embedded CPython worker) | yt_dlp | 102.69 |
| `qjs` (QuickJS) | quickjs | 245.07 |
| **Simultaneous total** | | **670.42** |

A separate PSS sample at the same four-process shape measured 663.48 MiB RSS
and 632.59 MiB PSS. The RSS peak and PSS peak are not the same sample because
PSS is collected every 100 ms rather than every 25 ms.

## Candidate and baseline revisions

| Role | Revision | Notes |
| --- | --- | --- |
| Baseline | `8e4bfb3140c81ea4b968a8f4ca4bf866603e0de3` | Original constructor path; legacy resolver. |
| Final candidate | `dedcb8aa73108bd6389f386f094620ea1760f663` | Current `feature/ytdlp-resolver` HEAD when the final run started. Includes the VISIONOS-first anonymous metadata fallback; runtime extraction remains yt-dlp. |
| Interim candidate | `87e4e1446f6b64a8cfa6fdf81ab292246d44a302` | Older labeled candidate, retained for comparison. Superseded by `dedcb8aa7`. |

The final run did **not** hardcode the candidate SHA. The harness ran with
`--candidate-ref feature/ytdlp-resolver`, resolved the ref immediately before
checkout/build, and recorded both `candidate_ref` and the exact
`candidate_commit` in `packaging/resolver/validation/linux-full-application.json`.
The interim result is a separate file and is labeled by its filename,
`candidate_label: interim`, and its `candidate_commit` field. The final JSON
uses `candidate_label: final`.

## Process categories and browser observation

Final candidate categories observed across the whole run:

| Category | Executable names | Max observed per-process RSS (MiB) |
| --- | --- | ---: |
| jvm | `java` | 321.54 |
| yt_dlp | `yt-dlp` | 114.34 |
| quickjs | `qjs` | 245.07 |
| media | `ffmpeg`, `ffprobe` | 14.87 |
| browser | **none** | — |
| separately named Python executable | **none** | — |

Baseline observed only the app JVM:

| Category | Executable names |
| --- | --- |
| jvm | `java` |
| browser | **none** |
| separately named Python executable | **none** |

Both the baseline and the final candidate observed **zero browser processes**
for the anonymous run. No sign-in flow or browser-backed authentication was
started. No separately named `python` executable appeared. The standalone yt-dlp
worker **does embed CPython**, and its entire RSS is included under `yt_dlp`;
this is not a Python-free extraction. The benchmark's own Python supervisor and
Xvfb are external measurement infrastructure, not app-created processes. The
interim candidate observed the same executable categories as the final candidate.

The per-category RSS values above are maxima observed for individual processes
in each category across the run. They are not simultaneous totals and must not
be added together. The extraction peak table is the simultaneous measurement.

## Detached resolver groups and the PID registry

The final candidate starts its own `setsid` process group/session per
extraction. The sampler does not rely solely on the application's original
process group. It tracks:

1. the application PID;
2. the application's session and process-group members;
3. the full descendant closure; and
4. a persistent `(pid, starttime)` registry for descendants that detach or are
   reparented.

The final run observed the new lifecycle directly. During extraction the
sampler saw up to **3 tracked processes at once outside the application's own
session/process group**, with these names across the extraction phase:
`ffmpeg`, `ffprobe`, `qjs`, and `yt-dlp`. At the RSS peak sample:

| Process | PID | Session / process group | Detached from app group? |
| --- | ---: | ---: | --- |
| app JVM | 113298 | 113298 | no |
| `yt-dlp` worker | 113454 | 113453 | yes |
| `qjs` | 113460 | 113453 | yes |
| `yt-dlp` session leader | 113453 | 113453 | yes |

No process with a non-tracked parent was recorded in this particular run, so
`reparented_process_names` is empty. The registry is still part of the
inclusion path and prevents a later reparent from dropping an already-observed
`(pid, starttime)` descendant. The important observed evidence is that the
yt-dlp/QuickJS group had a different session and process group from the app and
was still included in the totals.

Cleanup was complete for both runs: all post-exit samples had zero tracked
processes, no group kill was needed because the app exited normally, and Xvfb
exited with status 0. The final candidate app exited 0 and did not hit the
240-second harness timeout.

## Resolution behavior

All six final-candidate requests succeeded through the production yt-dlp path.
Each returned an `audio/webm; codecs="opus"` stream at itag 251, with
`client_name: YT_DLP` and `client_version: 2026.08.19`:

| Video ID | Attempt | Latency (ms) | Bitrate |
| --- | ---: | ---: | ---: |
| `jNQXAC9IVRw` | 0 | 2711 | 106064 |
| `jNQXAC9IVRw` | 1 | 1907 | 106064 |
| `dQw4w9WgXcQ` | 0 | 7778 | 128930 |
| `dQw4w9WgXcQ` | 1 | 7511 | 128930 |
| `aqz-KE-bpKQ` | 0 | 2011 | 128612 |
| `aqz-KE-bpKQ` | 1 | 1874 | 128612 |

The baseline legacy path failed all six requests with
`StreamResolutionException` and produced no resolver child processes. Its
extraction-phase peak therefore reflects failed legacy attempts, not a
successful legacy extraction. The candidate peak is the relevant successful
whole-application worst case.

This benchmark measures stream resolution, not playback. The candidate's
separate backend tests covered loudness metadata; this run does not claim a
loudness value because the application instrumentation did not record it.
The production VLC runtime locator requires a Windows layout and did not initialize
playback on this Linux host. System libVLC is installed and passed the separate
backend-to-callback integration test, including seek and pause/resume. Full desktop
application playback was not exercised here.

## Method

### Application and build

- Built the real `:desktopApp` module through a temporary Gradle init script
  that exports the runtime classpath, then launched
  `moe.rukamori.archivetune.desktop.MainKt` directly with that classpath.
- Both runs used the same JVM flags:
  `-Xms32m -Xmx768m -XX:ReservedCodeCacheSize=128m
  -XX:ParallelGCThreads=4 -XX:ConcGCThreads=2
  -XX:G1ConcRefinementThreads=4 -Dfile.encoding=UTF-8
  -Dsun.java2d.dpiaware=true -Djava.awt.headless=false`.
- The app ran on a private, locally extracted Xvfb 21.1.25-2 display at
  1440x900x24 with software Skia. The benchmark did not use the user's live
  desktop display.
- Runs were serialized: baseline first, then candidate. Each run used a
  disposable `ARCHIVETUNE_HOME`, `HOME`, XDG directories, and `TMPDIR`. The
  user's profiles, cookies, settings, and tokens were not read or written.
- The resolver binaries were the verified standalone tools: yt-dlp 2026.08.19
  and quickjs-ng `qjs` 0.17.0. The candidate received them through
  `ARCHIVETUNE_YTDLP` and `ARCHIVETUNE_QUICKJS`; the baseline used its original
  legacy constructor path.

### Temporary instrumentation

A temporary `Main.kt` block emits benchmark markers and calls the real
`DesktopMusicServices.backend.resolveAudioStream` for three public video IDs,
twice each. The block was applied only inside disposable detached checkouts
under `/tmp/opencode/archivetune-bench`; it was never committed to the feature
branch, the baseline, or any production source/Gradle file.

For the final run, the baseline and candidate instrumented `Main.kt` files had
the identical SHA-256:

```
1d07cd0deeb54b31493c8207d33ccaa7f9844c69dde56db5ef229c7f53e4983f
```

The final candidate checkout was fresh at the resolved commit and received the
injection; the baseline checkout was the already-verified disposable checkout
with the same hook. Both builds report `build_status: built`. The hook emits
`window-visible`, `idle-settled`, `extraction-begin`, per-request `resolve`,
`extraction-end`, `benchmark-complete`, `shutdown-begin`, and
`shutdown-complete`.

### Sampling and phase windows

- RSS sampling every 25 ms; PSS sampling every 100 ms when
  `/proc/<pid>/smaps_rollup` is available.
- The tracked set is the app PID, its session, its process group, the full
  descendant closure, and the persistent `(pid, starttime)` registry.
- No full command line/argv is read or stored.
- `idle` is the 8-second window from 2 seconds after the visible window to
  `extraction-begin` (the app waits 10 seconds after visibility before
  extracting). `post_idle` is the 8-second window from 2 seconds after
  `extraction-end` to `benchmark-complete`.
- The reported extraction peak is the maximum simultaneous tree total. PSS
  values are `null`, not zero, when a process exits before a PSS read; this is
  why short-lived `ffmpeg`/`ffprobe` helpers may lack PSS.

### Environment

The final run was recorded on 2026-10-20 (UTC) on Linux 7.2.9-2-cachyos,
x86_64, 16 CPUs, Python 3.14.8, Xvfb 21.1.25-2. The host was shared with the
user's desktop applications. Processes outside the tracked app tree were not
counted, but they can affect scheduling and page-cache pressure.

## Limitations

- One run per version, three public video IDs, and two sequential repeats per
  video. This is a small sample; latency and peak memory will vary with network
  and cache state.
- The baseline legacy path failed every request in this environment, so the
  baseline extraction peak is not a successful-extraction footprint.
- Anonymous startup produced a visible window and loaded services/home, but
  this environment's anonymous home/library requests returned HTTP 400. The UI
  idle footprint is real, while server-content-dependent memory may differ from
  a signed-in session.
- No sign-in flow was triggered. Browser/WebView memory for authentication is
  unmeasured, and anonymous runs observed zero browser processes.
- The production VLC runtime locator is Windows-only and did not initialize the
  installed system libVLC. Full application playback was not exercised; separate
  live libVLC integration tests passed. This is a resolution-and-application benchmark.
- Polling can miss processes that start and exit between samples. RSS can count
  shared pages more than once; PSS reduces that duplication. PSS sums can miss processes that exit between reads, especially short-lived
  `ffmpeg`/`ffprobe` helpers. RSS is the primary reported number.
- The benchmark is offscreen with software Skia; GPU-backed rendering may
  differ.

## Repeatable command

The harness resolves the candidate ref immediately before checkout and build.
Omitting `--candidate-commit` is intentional: it uses the current
`feature/ytdlp-resolver` HEAD and records the exact SHA.

```bash
python3 scripts/benchmark-application.py \
  --candidate-ref feature/ytdlp-resolver \
  --work-root /tmp/opencode/archivetune-bench/final-run \
  --output packaging/resolver/validation/linux-full-application.json \
  --xvfb /tmp/opencode/archivetune-bench/tools/usr/bin/Xvfb \
  --ytdlp /home/mint/.t3/worktrees/ArchiveTuneLinux/t3-e22b0ea4/.tools/resolver/yt-dlp \
  --quickjs /home/mint/.t3/worktrees/ArchiveTuneLinux/t3-e22b0ea4/.tools/resolver/qjs \
  --keep-worktrees
```

This creates fresh disposable baseline and candidate checkouts, applies the
identical temporary hook to each, builds both, and runs both. Use a fresh
`--work-root` for a clean run. If a verified
instrumented baseline checkout already exists, add
`--baseline-dir /path/to/disposable/baseline` to reuse it. Do not use
`--skip-build` for a fresh checkout; its classpath file will not exist yet.

To reproduce the older labeled interim result, pin the older commit and write a
separate output:

```bash
python3 scripts/benchmark-application.py \
  --candidate-commit 87e4e1446 \
  --candidate-ref feature/ytdlp-resolver \
  --candidate-label interim \
  --work-root /tmp/opencode/archivetune-bench/interim-run \
  --output packaging/resolver/validation/linux-full-application-87e4e1446.json \
  --xvfb /tmp/opencode/archivetune-bench/tools/usr/bin/Xvfb \
  --ytdlp /home/mint/.t3/worktrees/ArchiveTuneLinux/t3-e22b0ea4/.tools/resolver/yt-dlp \
  --quickjs /home/mint/.t3/worktrees/ArchiveTuneLinux/t3-e22b0ea4/.tools/resolver/qjs \
  --keep-worktrees
```

## Artifacts

- `scripts/benchmark-application.py` — harness.
- `packaging/resolver/validation/linux-full-application.json` — final
  candidate `dedcb8aa73108bd6389f386f094620ea1760f663`.
- `packaging/resolver/validation/linux-full-application-87e4e1446.json` —
  labeled interim candidate `87e4e1446f6b64a8cfa6fdf81ab292246d44a302`.
- `YT_DLP_APPLICATION_BENCHMARK.md` — this report.

# Linux JVM vs GraalVM native-image comparison

## Measured result — 21 October 2026

Three measured runs per runtime, plus one discarded warm-up each; all eight
runs exited successfully after local Opus playback, seek, pause/resume and stop.
Identical source snapshot; i9-9900K, 16 logical CPUs, 32 GB RAM, CachyOS Linux.

| Metric | Standard JVM | GraalVM native |
| --- | ---: | ---: |
| Startup to first frame callback | 1.999 s | 0.190 s |
| Idle CPU (one core = 100%) | 1.51% | 0.30% |
| Playback CPU (one core = 100%) | 3.22% | 1.45% |
| Playback RAM, PSS | 372.2 MiB | 192.4 MiB |
| Playback RAM, RSS | 382.6 MiB | 203.7 MiB |
| Peak RAM, PSS | 384.9 MiB | 199.3 MiB |

Native startup was **10.5× faster**, playback PSS was **48% lower**, and
playback CPU was **55% lower** in this workload. CPU is the median across runs
of each phase's mean CPU usage; RAM is the median across runs of phase medians.
Startup ranges: JVM 1995–2004 ms; native 188–195 ms.

These are warm host-cache starts with fresh profiles, software rendering in
Xvfb, 10 seconds idle, 20 seconds muted local playback, and 5 seconds paused.
They use the unpacked launchers and exclude AppImage mounting/extraction time.
They do not establish GPU frame rate, long-session memory or network playback
performance. Anonymous background maintenance still runs. The audio server and
session bus are shared with the desktop, and server CPU is outside the app tree.

The JVM uses OpenJDK 21.0.12.1, G1, `-Xms32m -Xmx768m`; native uses GraalVM
Community Java 25.0.3, SIZE optimization, Serial GC and a 128 MiB heap limit.
This compares the packaged configurations, rather than equal heap/GC settings.

Native checks also passed external MPRIS Root/Player `GetAll`, synthetic keyring
write/read/delete, bundled resolver version checks, and disposable Brave
HTTP/WebSocket proof-token minting. Real-account native sign-in, native GNOME
shortcut interaction, legacy Rhino resolution and the Windows MSI were not
validated. Linux ARM and macOS releases are outside this validation.

The compact results and source hashes are in
[`packaging/linux/validation/graal-runtime-comparison.json`](packaging/linux/validation/graal-runtime-comparison.json).
Raw samples and per-run markers are in `build/reports/linux-runtimes/report.json`.

This document describes how `scripts/benchmark-linux-runtimes.py` measures the
ArchiveTune Linux desktop application running on a normal JVM and the same
application running as a GraalVM native image.  It is a **process-level**
measurement: it says how much CPU and RAM each runtime needs and how long it
takes to show a window.  It does not measure in-app latency or frame rate.

## The two arms

Both arms are the *same source* and the *same* `Main`, with different runtime releases, garbage collectors and packaged heap limits:

| arm | command | notes |
| --- | --- | --- |
| JVM | `java <exact flags> -cp <classpath> moe.rukamori.archivetune.desktop.MainKt` | packaged launcher and flags recorded in report notes |
| native | `<native executable>` | the image is built with the production `-XX:MaxHeapSize=128m` |

The harness never rewrites a command line. It extracts heap flags present on
the command line; for packaged launchers or baked-in native heap limits, record
the build configuration explicitly with `--note`.  Both arms must be built from the same source snapshot.

## The application contract

The measured application runs an opt-in benchmark mode.  The harness exports:

| variable | meaning |
| --- | --- |
| `ARCHIVETUNE_RUNTIME_BENCHMARK=1` | enables the benchmark mode |
| `ARCHIVETUNE_BENCHMARK_ROOT` | the isolated root of this run |
| `ARCHIVETUNE_BENCHMARK_AUDIO` | the deterministic local media file |
| `ARCHIVETUNE_BENCHMARK_IDLE_SECONDS` | idle seconds before the playback phase |
| `ARCHIVETUNE_BENCHMARK_PLAY_SECONDS` | playback seconds |

and writes one marker line per event on stdout:
`ARCHIVETUNE_RUNTIME_BENCHMARK\t` followed by a JSON object.

| event | meaning |
| --- | --- |
| `window-ready` | the first Compose frame callback has run; the startup phase ends here |
| `phase-start` / `phase-end` with `phase` = `idle`, `playback`, `paused` | the phase boundaries |
| `complete` with `success` | every check passed and the application exits by itself |
| `failure` with `category` | the run failed; the exception class is recorded |

The phases are therefore measured by the application itself, not guessed by the
harness: the CPU and memory timeline and the marker timeline are the same
timeline.

## What the numbers mean

* **Startup** is the wall time from `Popen` to the `window-ready` marker.
* **CPU** is `utime+stime` deltas from `/proc/<pid>/stat`.  The unit is
  phase CPU seconds divided by phase duration, expressed as
  *percent of one logical core*: 100% means one fully busy core, not one fully
  busy machine.  Divide by the logical core count for the machine-wide share.
* **RAM** is reported three ways on purpose:
  * `rss_mib` -- the sum of the resident sets.  A shared page is counted once
    per process, so this **overstates** the physical RAM the tree occupies.
  * `pss_mib` -- the sum of the proportional set sizes.  Shared pages are
    divided among their sharers, so this is the fair share of physical RAM.
  * `uss_mib` -- the sum of `Private_Clean + Private_Dirty`: the RAM that
    would be released if the tree died right now.
  Peak RAM covers the entire run, including startup.
  The host page cache (`Cached` from `/proc/meminfo`) is reported separately in
  `host.page_cache_mib`, so "cache" and "total" are never conflated.
* All memory numbers are **simultaneous sums** at one sampled instant, never a
  sum of per-process maxima.

## Sampling and the run plan

* The tree is sampled every `--sample-interval-ms` (default 100 ms);
  `smaps_rollup` is read every `--pss-interval-ms` (default 1000 ms) because
  it is much more expensive than `stat`.
* The tracked set is the launched PID plus its descendants, extended by a
  persistent `(pid, starttime)` registry so a child that double-forks or calls
  `setsid()` stays attached to the run.  Compilers and desktop services are
  therefore never part of a measurement.
* `--warmup` rounds run first and are excluded from the summary, to warm shared host caches. Every run still gets a fresh profile; these are
  warm host-cache starts, not cold-boot measurements.
* The measured rounds **alternate the order** of the two arms, so a slow drift
  of the host cannot favour one arm.

## Isolation and safety

* Every run gets a private `HOME`, `XDG_CONFIG_HOME`, `XDG_DATA_HOME`,
  `XDG_CACHE_HOME`, `XDG_STATE_HOME`, `XDG_RUNTIME_DIR` and `TMPDIR` below the
  run root (mode `0700`).  The user's own ArchiveTune instance and the user's
  XDG directories are untouched, so a normal instance may keep running next to
  the benchmark.
* The application starts on the settings page
  (`navigation.lastDestination=SETTINGS`), so the benchmark never loads the
  network home.
* The child environment is built from an allow-list; variables whose name looks
  like a credential are dropped even when they are inherited.  No account
  secret is read or forwarded.
* Cleanup signals **exact PIDs**, each re-validated against its recorded
  `/proc/<pid>/stat` start time, so a recycled PID is never hit.  There is no
  process-group kill and no name-based matcher: a `java` or `ArchiveTune`
  process the user started is never touched.
* Both arms render with the software renderer (`SKIKO_RENDER_API=SOFTWARE`),
  so this is not a comparison of two different rasterisers.
* Log files and the JSON report are redacted: token, cookie and authorization
  style values are replaced before anything is written to disk.

## Running it

```sh
scripts/benchmark-linux-runtimes.py \
    --variant "jvm=/path/to/jvm-image/bin/ArchiveTune" \
    --variant "native=/path/to/native-image/ArchiveTune" \
    --xvfb /tmp/opencode/archivetune-hybrid-final/xvfb/usr/bin/Xvfb \
    --rounds 3 --warmup 1 \
    --out build/reports/linux-runtimes/report.json
```

The harness starts its own private Xvfb display (`--xvfb` accepts a path that
is not on `PATH`), or uses `--display` for an existing one.  `--audio` replaces
the generated WAV with an existing local fixture, for example a deterministic
Opus file, so the real decoder path is measured instead of the WAV path.

The report contains the host description, the exact commands, the heap flags,
the version of each executable, every run with its raw samples and markers, the
aggregated summary, the field-by-field comparison and the limitations below.

## Limitations

* CPU comes from polled deltas: a helper that lives for less than one sample
  interval can be missed, and the last CPU increment before an exit can be missed.
* The percentages are relative to one logical core.
* PSS/USS need `/proc/<pid>/smaps_rollup`; without that file the report
  carries null values instead of a partial total.
* The user's own instance and the rest of the desktop are excluded from the
  totals, but they still consume host resources.
* Per-process breakdowns are retained in raw samples and the peak sample.
  The executable hash identifies the launched file, which may be a launcher;
  retain a source manifest and build configuration alongside the report.
* This measures the process, not the perceived responsiveness of the UI.

## Developer checks

```sh
python3 scripts/tests/test_benchmark_linux_runtimes.py
```

The checks exercise the `/proc` sampler, the marker protocol, the isolation
rules and the cleanup rules against real `/proc` data and small helper
commands.  They never start the real application and never touch the network.

The full application may still perform anonymous background maintenance. Settings
startup avoids loading Home, but this is not a network-isolated benchmark. The
local fixture requires no login, resolver or browser token minting.

Only successful measured runs contribute metrics; any failed measured run makes
the harness exit nonzero and suppresses comparison ratios. An explicit `--display`
is required to use a live display if Xvfb is unavailable. The session D-Bus and
audio server remain shared with the desktop, while application files are isolated.
Short-lived helpers that escape between polls can be missed.

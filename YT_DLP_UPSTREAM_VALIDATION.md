# ArchiveTune — yt-dlp + QuickJS-NG upstream validation (Linux)

Status: **verified against live YouTube** on 2026-10-21. This document records the
upstream artifact provenance, the JS-challenge proof, the live playback proof and the
Linux-specific findings that the desktop resolver work has to account for.

Scope note: `scripts/benchmark-ytdlp.py` is a **developer-only benchmark harness**.
It is Python on purpose and it is not part of the shipped application. Nothing in the
runtime depends on Python or on the harness; the app must invoke the pinned
`yt-dlp` and `qjs` binaries directly.

---

## 1. Upstream artifacts

Both artifacts were downloaded fresh from their official upstream release URLs into an
ignored build location (`build/tools/resolver/`) and verified by SHA256.

| Artifact | Version | Source | SHA256 | Verified |
| --- | --- | --- | --- | --- |
| `yt-dlp_linux` | `2026.08.19` | `github.com/yt-dlp/yt-dlp` release `2026.08.19` | `58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a` | yes |
| `qjs-linux-x86_64` | `0.17.0` | `github.com/quickjs-ng/quickjs` release `v0.17.0` | `0bfc02511a9f549c28b53880d988fc7cd5d361e90c5e8afdfcd7dc6774ceace5` | yes |

Both hashes match the expected values exactly, in this worktree and in the copies the
parent branch keeps at `.tools/resolver/`. The two copies are byte-identical, so the
parent's existing tool paths can be used directly (read-only) without re-downloading.

`yt-dlp --version` reports `2026.08.19`; `qjs --version` reports `QuickJS-ng version 0.17.0`.

---

## 2. Resolver invocation

The validated flag set is exactly:

```
--ignore-config --no-plugin-dirs --no-remote-components --no-playlist
--skip-download --dump-single-json
-f bestaudio[protocol=https]/bestaudio[protocol=http]
--no-js-runtimes --js-runtimes quickjs:<ABSOLUTE_PATH>/qjs
```

`--no-js-runtimes` clears yt-dlp's default runtime set (deno is the only default) and
`--js-runtimes quickjs:<abs path>` registers QuickJS-NG explicitly. The path must be
absolute: yt-dlp resolves a bare `quickjs` name against `PATH`, which is not something a
packaged desktop app can rely on.

(The harness additionally passes `-v`. That is a harness-only diagnostic flag: yt-dlp
writes it to stderr, so the JSON on stdout stays clean, and it is what makes the
per-run JS challenge evidence in section 3.3 observable.)

Verified debug output from the pinned binary:

```
[debug] yt-dlp version stable@2026.08.19 from yt-dlp/yt-dlp (linux_exe)
[debug] Optional libraries: ... curl_cffi-0.16.0 ... yt_dlp_ejs-0.8.0
[debug] JS runtimes: quickjs-ng-0.17.0
[debug] [youtube] [jsc] JS Challenge Providers: bun (unavailable), deno (unavailable), node (unavailable), quickjs
```

### Bundled `yt_dlp_ejs-0.8.0`

The official standalone binary **already bundles** `yt_dlp_ejs-0.8.0` (the EJS challenge
solver distribution: `yt/solver/core.min.js`, `yt/solver/lib.min.js`). This is what makes
`--no-remote-components` safe: yt-dlp is forbidden from fetching the solver script from
GitHub, and it still works, because the solver ships inside the executable.

No `yt_dlp_ejs` wheel needs to be installed into a Python environment. There is no
system Python dependency at all in the desktop app.

### 2.1 The desktop resolver's own flag set was validated verbatim

The resolver implementation in the parent branch
(`stream-resolver/.../YtDlpStreamResolver.kt`) builds this argument vector:

```
<yt-dlp> --ignore-config --no-plugin-dirs --no-remote-components
         --no-js-runtimes --js-runtimes quickjs:<ABSOLUTE>/qjs
         --no-playlist --no-progress --no-cache-dir --socket-timeout 12
         --retries 1 --extractor-retries 1 --skip-download --dump-single-json
         --format <selector> -- https://www.youtube.com/watch?v=<id>
```

That exact vector was executed against the pinned binaries and it works: exit code 0,
24 formats for `jNQXAC9IVRw`, format `251` present with the four `http_headers`, and a
usable media URL. The `--` separator before the URL is accepted by yt-dlp's option parser.

Two additional measurements on that vector:

* `--no-cache-dir` has **no** measurable latency cost (2069 / 2104 / 1934 ms with it
  versus 1941 / 2034 ms without it, each with a fresh `HOME`). The flag is safe to keep.
* The `--` separator, `--retries 1 --extractor-retries 1` and `--socket-timeout 12` all
  behave as intended.

The `--format` selector used by the resolver (`bestaudio[protocol=https][abr<=N]/...` for
the capped qualities, plain `bestaudio[protocol=https]/bestaudio[protocol=http]` otherwise)
is the selector validated throughout this document.

---

## 3. The YouTube "n" challenge really is solved by QuickJS

This is the part that matters most, and it was proven with a **negative control** rather
than by reading a log line.

### 3.1 The challenge is real

With `youtube:player_client=web_embedded` the EJS solver invokes the registered runtime
with a script:

```
QJS INVOKED args=[--help]                       <- runtime capability probe
QJS INVOKED args=[--script /tmp/tmpXXXX.js]     <- real challenge execution
```

The same is observed for `mweb` and `tv_simply` (3/3 runs each).

### 3.2 The runtime is load-bearing

Running the exact same extraction with a **deliberately broken** runtime (a shim that
exits non-zero for `--script`) changes the outcome:

```
WARNING: [youtube] [jsc] Error solving n challenge request using "quickjs" provider:
    Error running QuickJS process (returncode: 3): ARCHIVETUNE_SENTINEL_RUNTIME_FAILURE.
         input = NChallengeInput(player_url='...', challenges=[<redacted>])
WARNING: [youtube] ...: n challenge solving failed: Some formats may be missing.
ERROR: [youtube] ...: No video formats found!
```

And with **no runtime at all** (`--no-js-runtimes` and no `--js-runtimes`):

```
WARNING: [youtube] ...: n challenge solving failed: ...
ERROR: [youtube] ...: No video formats found!
```

Both negative controls fail, the real runtime succeeds. The QuickJS-NG binary is therefore
genuinely executing the challenge script, and the bundled `yt_dlp_ejs-0.8.0` is genuinely
solving the challenge. `scripts/benchmark-ytdlp.py` automates exactly this proof
(`--skip-challenge-proof` turns it off).

### 3.3 The challenge hits the *default* client too — it is video dependent

This is the most important finding, and it was initially wrong.

The resolved format URLs for the default player client contain `sig=` but no `n=`
parameter, so a first look suggests that no JS challenge is executed on the default path.
That is **not** the general case: the challenge is applied **per video**.

`dQw4w9WgXcQ` (Rick Astley, 4K remaster) is challenged on the default client:

```
[debug] [youtube] [jsc:quickjs] Solving JS challenges using quickjs
[debug] [youtube] [jsc:quickjs] Using challenge solver lib script v0.8.0 (source: python package, variant: minified)
[debug] [youtube] [jsc:quickjs] Using challenge solver core script v0.8.0 (source: python package, variant: minified)
[debug] [youtube] [jsc:quickjs] Running QuickJS: <abs>/qjs --script /tmp/tmpys1esyyd.js
```

Measured over the six benchmark videos, with the **default** client:

| Video | Challenge solved | Latency | Source of the solver |
| --- | --- | --- | --- |
| `dQw4w9WgXcQ` | **yes** | 7.5-7.9 s | `python package` (bundled `yt_dlp_ejs-0.8.0`) |
| `jNQXAC9IVRw` | no | ~2.1 s | - |
| `kJQP7kiw5Fk` | no | ~2.2 s | - |
| `9bZkp7q19f0` | no | ~2.2-3.3 s | - |
| `fJ9rUzIMcZQ` | no | ~2.8-2.9 s | - |
| `YQHsXMglC9A` | no | ~2.4-2.9 s | - |

Measured effect of **removing** the runtime, per client, on the challenged video
`dQw4w9WgXcQ`:

| Client | Without the QuickJS runtime |
| --- | --- |
| default (visionos) | extraction still succeeds, but the warning `No supported JavaScript runtime could be found ... some formats may be missing` appears and **format `18` (AAC audio) is silently dropped**: 49 -> 48 usable formats, audio set `{139, 140, 18, 249, 250, 251}` -> `{139, 140, 249, 250, 251}` |
| `web_embedded` / `mweb` / `tv_simply` | **hard failure**: `n challenge solving failed` -> `ERROR: No video formats found!` (exit 1) |

`source: python package` is the direct proof that the solver script came from the
**bundled `yt_dlp_ejs-0.8.0`** inside the official executable and not from a remote
component fetch, which is what makes `--no-remote-components` safe.

Consequence for the runtime implementation: **the QuickJS runtime is a required
dependency, not optional insurance.** Without it the resolver does not fail loudly and
consistently; it *silently degrades* on the default client (losing formats) and fails
catastrophically on the challenged clients. Because the trigger is **per video**, this
presents in production as an intermittent, video-dependent bug rather than as a missing
dependency. The runtime must be validated at startup (the resolver already parses
`JS runtimes: ... quickjs-ng-<version>` and `yt_dlp_ejs-<version>` out of the yt-dlp
diagnostics) and it must never be silently skipped.

The harness reports this per run: `js_challenge_solved`, `js_challenge_attempted`,
`js_challenge_failed`, `js_runtime_reported`, `ejs_version_reported` and
`js_solver_source`.

---

## 4. Live playback verification

Extraction succeeding is not the same as the audio playing. Both were verified against the
live network.

### 4.1 HTTP range probe

Every resolved URL was probed with the exact header set yt-dlp returned
(`Accept`, `Accept-Language`, `Sec-Fetch-Mode`, `User-Agent`), using a short ranged GET at
the **head and at the tail** of the object. The tail probe matters because libVLC seeks
while opening a WebM/MP4 stream — a URL that serves byte zero but rejects a later range
can start playing and then fail a moment later.

Result for all 12 runs: HTTP `206`, 65536 bytes at head and tail, `Content-Type:
audio/webm`, EBML magic `1a45dfa3`.

### 4.2 libVLC actually plays the resolved URL

The resolved URL was opened with libVLC (`libvlc.so.5`, VLC 3.0.24) with the yt-dlp
`User-Agent` applied as `:http-user-agent=`:

```
state path: NothingSpecial->Opening->Playing      (media time advanced past 0)
VLC demux log: Track CodecId=A_OPUS, afreq=48000, achan=2, Duration=19021
               connected to sink alsa_output..., output 'f32l' 48000 Hz Stereo
```

### 4.3 Independent PCM decode

As an engine-independent cross-check the same URL was decoded to PCM with ffmpeg
(48 kHz mono float). Every video produced 240000 frames (5 s) with non-zero audio:

| Video | Title | RMS | Peak |
| --- | --- | --- | --- |
| `jNQXAC9IVRw` | Me at the zoo | 0.0729 | 0.6049 |
| `dQw4w9WgXcQ` | Rick Astley — Never Gonna Give You Up | 0.1692 | 1.0247 |
| `kJQP7kiw5Fk` | Luis Fonsi — Despacito | 0.0507 | 0.3142 |
| `9bZkp7q19f0` | PSY — Gangnam Style | 0.4794 | 1.3841 |
| `fJ9rUzIMcZQ` | Queen — Bohemian Rhapsody | 0.0543 | 0.4530 |
| `YQHsXMglC9A` | Adele — Hello | 0.0008 | 0.0048 |

> The probe seeks 5 s into the stream before decoding. Some tracks open with digital
> silence (the first 3 s of `9bZkp7q19f0` decode to exact zeros), and an input-side seek
> also exercises the CDN range request that libVLC performs.

---

## 5. Benchmark results

Host: `Linux-7.2.9-2-cachyos-x86_64-with-glibc2.44`, 16 CPUs, Python 3.14.8.
6 distinct public videos x 2 repeats = **12 runs, 12/12 successful**.

| Metric | Value |
| --- | --- |
| Success rate | 12/12 runs, 6/6 videos |
| Failure classes observed | none |
| Latency min / p50 / p95 / max | 2037 / 2842 / 7482 / 7881 ms |
| Peak process-tree RSS median | 97.9 MiB |
| Peak process-tree RSS max | 382.3 MiB |
| JS challenge runs solved / failed | 2 / 0 (both on `dQw4w9WgXcQ`) |
| JS runtime reported by yt-dlp | `quickjs-ng-0.17.0` (all runs) |
| Bundled EJS solver reported by yt-dlp | `yt_dlp_ejs-0.8.0` (all runs) |
| Solver source | `python package` (never a remote fetch) |
| Live playback verdicts | `LIVE_PLAYBACK_CONFIRMED` x12 |
| Format selected | `251` (webm / opus, `https` protocol) |

Two latency clusters are visible and are **not** noise. They track the JS challenge
exactly:

* **~2.0-3.3 s / ~85-110 MiB** — no challenge, normal path.
* **~7.5-7.9 s / ~350-365 MiB** — the challenged path (`dQw4w9WgXcQ`). The extra ~5 s
  and ~270 MiB are the YouTube **player JS download plus the QuickJS interpreter**
  running the EJS solver, not a regression in the resolver.

The peak RSS is therefore bimodal for a structural reason: the challenge path pulls in
the player JS (~1.7 MB of JavaScript) and parses/executes it in the QuickJS interpreter.
A resolver that has to survive both paths needs a budget for **~380 MiB** of transient
process-tree RSS, not ~90 MiB.

### Memory measurement method and its limits

Peak RSS is sampled from `/proc` by the harness: every 20 ms the harness walks the
process tree (`/proc/<pid>/task/*/children`) and sums `VmRSS`/`statm` for every process in
it, keeping the maximum of those sums. **Stated limitations:**

* It is a **polling** sampler. A spike that lives entirely between two samples is not
  observed, so the reported peak is a **lower bound**.
* Pages shared between processes (the mapped yt-dlp executable, for example) are counted
  once per mapping, so the tree sum **overstates** the real physical footprint.
* GNU `time -v` was deliberately **not** used: it reports the maximum RSS of the *single*
  largest process, not of the whole tree, and it cannot see the QuickJS child.

If a hard upper bound is required, the right instrument is cgroup v2 `memory.peak` around
the whole invocation, which is a packaging concern rather than a harness concern.

---

## 6. Failure taxonomy observed

`scripts/benchmark-ytdlp.py` maps yt-dlp diagnostics onto a coarse failure class
(`JS_CHALLENGE_SOLVER_FAILED`, `JS_RUNTIME_MISSING`, `VIDEO_UNAVAILABLE`,
`FORMAT_UNAVAILABLE`, `NO_FORMATS_FOUND`, `LOGIN_REQUIRED`, `HTTP_403`, `PRIVATE_VIDEO`,
`TIMEOUT`, `EXTRACTOR_ERROR`, ...). Measured live:

* `BaW_jenozKc` -> `VIDEO_UNAVAILABLE` ("This video is unavailable"). Confirmed directly;
  it is excluded from the benchmark set.
* `dQw4w9WgXc` -> `UNSUPPORTED_URL`. **This id in the task brief is truncated.** The real
  video id is `dQw4w9WgXcQ` (11 characters); yt-dlp rejects the 10-character form with
  `Incomplete YouTube ID ... looks truncated`. The harness now expands any 10-12 character
  token to a watch URL and warns when the length is not 11.
* `youtube:player_client=tv` -> "The page needs to be reloaded"; `web` / `ios` ->
  "No video formats found" (SABR / missing PO token). These are client-side issues and
  are unrelated to the JS runtime.
* `--no-js-runtimes` with no `--js-runtimes` -> `JS_RUNTIME_MISSING`. On the default
  client this is a **warning, not an error** ("some formats may be missing"), which is
  exactly why it is dangerous: the resolver can look healthy while quietly returning
  fewer formats. The failure taxonomy should treat "runtime reported as unavailable" as
  fatal for the resolver, not as a per-request warning.

---

## 7. Linux findings that the runtime work must account for

These are observations about the existing code. **No Kotlin or Gradle file was modified**
by this validation pass.

### 7.1 `VlcRuntimeLocator` cannot find a libVLC on Linux (blocker)

`desktop-playback/.../VlcRuntime.kt`:

```kotlin
private fun isLayoutRoot(path: Path): Boolean =
    Files.isRegularFile(path.resolve("libvlc.dll")) &&
        Files.isRegularFile(path.resolve("libvlccore.dll")) &&
        Files.isDirectory(path.resolve("plugins"))
```

The locator only recognises a **Windows DLL** layout, and `isWindows()` gates the system
VLC paths. On Linux `find()` therefore always returns `null`, so:

* `VlcjLocalPlaybackIntegrationTest` is runtime-gated on `VlcRuntimeLocator.find()` and
  **silently no-ops on Linux** — every playback test in that class is skipped today.
* The packaged app has no way to reach the system libVLC.

This machine *does* have a working Linux libVLC: `libvlc.so.5` (VLC 3.0.24) in `/usr/lib`
plus the plugin tree in `/usr/lib/vlc/plugins`. A `.so` branch in `isLayoutRoot`/`layout`
plus a Linux candidate path would make the existing integration tests actually run here.

### 7.2 `mediaPlayerFactoryOptions()` hard-codes a Windows audio output

```kotlin
internal fun VlcRuntimeLayout.mediaPlayerFactoryOptions(): Array<String> = arrayOf(
    "--no-video", "--quiet", "--aout=directsound",
)
```

`--aout=directsound` is a Windows-only VLC audio output module. On Linux there is no
`directsound` module, so this option must be made platform-conditional (or dropped and
left to VLC's default `auto` selection) before the desktop player can open audio on Linux.
For reference, libVLC on this machine selected `alsa_output` on its own and produced
48 kHz stereo output without any explicit `--aout`.

### 7.3 Preserve the full header map through ArchiveTune's callback transport

The generic libVLC HTTP option mapping (`toVlcMediaOptions`) supports User-Agent and
Referer. For Googlevideo, ArchiveTune already uses `VlcjRangeHttpTransport` callback
media, which receives the full `ResolvedPlaybackSource.headers` map. The integrated
resolver uses that existing path; additional VLC options are unnecessary.

The live upstream probe measured HTTP 206 with the full four-header set and with
reduced headers. The runtime still forwards the full required set and filters login
credentials rather than relying on the CDN's current acceptance of reduced headers.

### 7.4 The desktop-backend already has the right probe

`desktop-backend/.../ResolvedAudioStreamProbe.kt` implements exactly the head+tail ranged
probe described in section 4.1, with a 5 s call timeout and no redirect following, and it
deliberately keeps the signed URL and headers out of exception messages. The harness
mirrors that behaviour, and the resolver should reuse it rather than adding a second
probe.

---

## 8. Reproducing

```bash
# 1. Fetch and verify the pinned upstream artifacts into an ignored build location
mkdir -p build/tools/resolver && cd build/tools/resolver
curl -sSL -o yt-dlp https://github.com/yt-dlp/yt-dlp/releases/download/2026.08.19/yt-dlp_linux
curl -sSL -o qjs    https://github.com/quickjs-ng/quickjs/releases/download/v0.17.0/qjs-linux-x86_64
chmod +x yt-dlp qjs
sha256sum yt-dlp qjs
# expect 58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a  yt-dlp
# expect 0bfc02511a9f549c28b53880d988fc7cd5d361e90c5e8afdfcd7dc6774ceace5  qjs

# 2. Run the benchmark (6 videos x 2 repeats, live playback on every run)
python3 scripts/benchmark-ytdlp.py \
    --yt-dlp build/tools/resolver/yt-dlp \
    --qjs    build/tools/resolver/qjs \
    --videos jNQXAC9IVRw,dQw4w9WgXcQ,kJQP7kiw5Fk,9bZkp7q19f0,fJ9rUzIMcZQ,YQHsXMglC9A \
    --repeats 2 \
    --live-playback on \
    --out-dir build/reports/ytdlp
```

The harness exits `0` only when every run succeeded and at least three distinct videos
worked. Reports are written to `ytdlp-benchmark-report.json` and
`ytdlp-benchmark-runs.csv`.

Useful flags: `--live-playback off|on|first`, `--live-retries N`,
`--skip-challenge-proof`, `--timeout`, `--sample-interval`, `--probe-timeout`.

### Safety contract of the harness

* No cookies are requested, read or written.
* The `--dump-single-json` payload is parsed in memory and discarded; it is never written
  to disk.
* Signed media URLs are never printed and never persisted. yt-dlp's own diagnostics are
  redacted before they are stored (URLs, `sig`/`n`/`lsig`/`pot`/`ip`/`ei` query values and
  challenge token lists).
* The libVLC probe runs in a child process with its stderr discarded, because libVLC
  prints the full signed URL in its "unable to open the MRL" diagnostic. The URL is handed
  to the child on stdin so it never appears on a command line either.
* The report contains only video ids, titles, format ids/counts, codecs, protocols,
  timings, byte counts and coarse failure classes.
* Child processes run in their own process group and the whole group is killed on
  timeout, so no strays survive the harness.
* yt-dlp runs with a private `HOME`/`XDG_*` so it can neither read nor write global
  user configuration.

---

## 9. Integration status after the upstream validation

1. `VlcRuntimeLocator` and the DirectSound option remain Linux port acceptance items;
   this resolver branch does not modify that agent's playback implementation.
2. quickjs-ng and bundled EJS are required and checked by the runtime resolver.
3. Per-video challenge solving has been proven with negative controls. Missing runtime
   diagnostics are rejected even if yt-dlp otherwise exits successfully.
4. The full required `http_headers` set goes through ArchiveTune's Googlevideo callback
   transport. Credential headers are filtered before media handoff.
5. The backend retains head/tail CDN validation and adds cancellation to that probe.
6. Windows/Linux sidecars are pinned, staged and verified by Gradle. `.tools/` is ignored.
7. The resolver uses a 45-second deadline, IO dispatchers and short-lived processes.
   See [YT_DLP_RESOLVER.md](YT_DLP_RESOLVER.md) for actual application API benchmarks,
   authentication lifecycle and remaining platform acceptance work.

### Things that turned out to be fine

* The `--` separator and the full desktop argument vector (section 2.1).
* `--no-cache-dir` (no latency cost).
* The four yt-dlp `http_headers` are accepted by the googlevideo CDN even when reduced to
  `User-Agent` only, or sent with no headers at all. Forwarding the full set is still
  the right default, but it is not currently a blocker.
* The googlevideo URLs are bound to an `spc` streaming-context parameter, but that does
  not translate into a header whitelist at the CDN: measured `206` with the full header
  set, with `User-Agent` only, and with no headers.

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

### 3.3 Important nuance: the default client is currently *not* challenged

With the default player client the resolved format URLs contain `sig=` but **no `n=`
parameter** and no `signatureCipher`, so no JS challenge is executed for those requests
(`quickjs_script_invocations == 0`). The runtime is therefore *insurance* for the clients
that do get challenged (`web_embedded`, `mweb`, `tv_simply`) rather than a hard
requirement for the default path.

Consequence for the runtime implementation: **do not make the QuickJS runtime optional
in the shipped resolver.** The moment YouTube applies the `n` challenge to the default
client, or the resolver switches to a challenged client, every extraction fails without it.

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
| Latency min / p50 / p95 / max | 1640 / 2173 / 7077 / 7527 ms |
| Peak process-tree RSS median | 86.7 MiB |
| Peak process-tree RSS max | 361.0 MiB |
| Live playback verdicts | `LIVE_PLAYBACK_CONFIRMED` x12 |
| Format selected | `251` (webm / opus, `https` protocol) |

Two latency clusters are visible and are not noise:

* **~1.6-2.7 s** — the normal path.
* **~7.1-7.5 s and ~360 MiB** — runs where yt-dlp additionally downloaded the YouTube
  player JS and executed the challenge path. The extra ~5 s and ~275 MiB are the player JS
  fetch plus the QuickJS interpreter, not a regression in the resolver.

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

### 7.3 Only `User-Agent` and `Referer` are forwarded to VLC

`VlcjPlaybackController.toVlcMediaOptions()` maps just those two headers to
`:http-user-agent=` / `:http-referrer=`, while yt-dlp returns
`Accept`, `Accept-Language`, `Sec-Fetch-Mode`, `User-Agent`.

Measured on the live CDN: the googlevideo URL served `206` with the **full** header set,
with `User-Agent` only, and with **no** headers at all, so this is **not currently a
blocker**. It is still worth forwarding the whole set, because the googlevideo URL
carries an `spc` (streaming playback context) parameter that is bound to the request
context; forwarding exactly what yt-dlp asked for is the safe default.

The plumbing already exists: `PlaybackSource.Remote` carries
`headers: Map<String, String>`, so the resolver can hand yt-dlp's `http_headers` straight
through to the player.

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

## 9. Open items for the runtime and packaging work

1. **`VlcRuntimeLocator` needs a Linux (`.so`) branch.** Until it exists, no desktop
   playback path can run on Linux, and `VlcjLocalPlaybackIntegrationTest` is a no-op.
2. **`--aout=directsound` must become platform-conditional.**
3. **The QuickJS runtime must not be optional** in the shipped resolver (section 3.3).
4. **Forward the full `http_headers` set** from yt-dlp to the player, not only
   `User-Agent`/`Referer` (section 7.3).
5. **Reuse `probeResolvedAudioStream`** rather than adding a second range probe.
6. **Pin and stage the upstream binaries** with the SHA256 values in section 1, and keep
   them out of version control. Note that the parent worktree's `.tools/` directory is
   currently *not* matched by `.gitignore` (`build/` and `docs/` are), so the packaging
   side should either ignore `.tools/` or stage the binaries under `build/`.
7. **Decide on the extraction timeout budget.** The slow path (player JS + challenge)
   takes ~7.5 s; a desktop resolver that blocks the UI thread on that would stall
   playback start. The Android code path already models a per-client resolution race,
   and the same race is the right shape here.

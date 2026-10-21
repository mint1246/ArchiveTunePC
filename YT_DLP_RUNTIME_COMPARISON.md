# yt-dlp EJS JS runtime comparison: is QuickJS-NG the cheapest way?

This initial comparison measured **pre-Rust Bun 1.3.14**. The newer
[Bun 1.4.3 comparison](YT_DLP_RUST_BUN_COMPARISON.md) confirms that Rust-based
Bun works with the same yt-dlp CLI despite its warning, and uses less memory
than the old build. Do not apply this document's old-Bun numbers to current Bun.

**Question.** Is QuickJS-NG the cheapest JS runtime for the yt-dlp EJS challenge
solver that the desktop resolver ships, or is a heavier runtime (Node / Deno /
Bun) cheaper to *run*?

**Answer, measured.** There is no single cheapest runtime across all costs.
QuickJS-NG has the smallest binary (2.44 MiB) and lowest measured peak RAM,
but challenged extraction took **2.5x the wall time** and **3.3x the CPU** of
Node. Node reduced latency and CPU while using about **48 MiB more peak RAM**.
The decision depends on package size, temporary memory and playback startup delay.

Everything below is a measurement of the pinned tools in this repository. No
engine claims are used as evidence.

## Measured results

Cold, serialized extractions of the **challenged** video `dQw4w9WgXcQ` with
`player_client=web_embedded` (the runtime is load-bearing here), three
successful extractions per runtime, same machine, same pinned yt-dlp:

| Runtime | Version | Binary size | Wall time (median) | CPU time (median) | Peak tree RSS (max) | Runtime child RSS (sampled max) | Runtime child CPU (sampled max) |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| **QuickJS-NG** | 0.17.0 | **2.44 MiB** | **7541 ms** | **5.97 s** | **359.0 MiB** | 256.6 MiB | 5.30 s |
| Node | 26.11.1 | 52.78 MiB | 2988 ms | 1.79 s | 407.3 MiB | 302.1 MiB | 0.87 s |
| Deno | 2.9.7 | 91.39 MiB | 2929 ms | 2.00 s | 423.8 MiB | 318.3 MiB | 1.07 s |
| Bun | 1.3.14 | 88.46 MiB | 2737 ms | 2.03 s | 474.3 MiB | 368.1 MiB | 1.10 s |

Incremental cost of the challenge over the unchallenged control
(`jNQXAC9IVRw`, default player client, runtime only probed for its version,
no solver script executed):

| Runtime | Δ wall | Δ CPU | Δ peak tree RSS |
| --- | ---: | ---: | ---: |
| **QuickJS-NG** | **+5483 ms** | **+5.21 s** | **+272.7 MiB** |
| Node | +928.5 ms | +1.00 s | +321.0 MiB |
| Deno | +915 ms | +1.21 s | +337.6 MiB |
| Bun | +750.5 ms | +1.19 s | +365.1 MiB |

The control measurements are similar across runtimes (1910–2176 ms wall,
0.76–0.90 s CPU, 85.5–86.3 MiB peak tree RSS in seven of eight runs; one
Bun run peaked at 109.2 MiB because a third process was alive at that
sample). The solver-child CPU attribution supports a runtime cost difference;
wall time still includes network variation. These controls do not prove that
all wall-time differences come from the engine.

### Reading the table

* **CPU and latency:** QuickJS-NG took substantially more total CPU and wall
  time in the challenged case. The solver child reached 5.30 s sampled CPU
  versus 0.87 s for Node; those child figures are maxima, not medians.
* **Peak RSS:** QuickJS-NG was lowest by 48–115 MiB (11–24%) in this sample.
  This is a real memory advantage alongside its slower execution.
* **Ship cost:** QuickJS-NG's binary was 22–37x smaller than the alternatives.
  Binary sizes here are installed executables, not compressed installer sizes.
* **Unchallenged control:** No solver script executed, and measurements were
  similar across runtimes. This does not establish how often real users will
  encounter challenged videos.

### Where the memory actually goes

The largest sampled per-process-class values across challenged runs are shown
below. They can come from different samples and must not be summed to reconstruct
a simultaneous peak; use the whole-tree figures above for that.

| Process class | QuickJS-NG | Node | Deno | Bun |
| --- | ---: | ---: | ---: | ---: |
| runtime child | `qjs` 256.6 MiB | `node-MainThread` 302.1 MiB | `deno` 318.3 MiB | `bun` 368.1 MiB |
| frozen `yt-dlp` (bootloader + embedded CPython) | 101.2 MiB | 103.7 MiB | 103.3 MiB | 103.7 MiB |

The frozen yt-dlp **does embed CPython**. The PyInstaller one-file binary
forks into two processes that both report the comm `yt-dlp`: the bootloader
(~2.6 MiB) and the CPython worker (~100 MiB). The absence of a separately
named `python` process does **not** mean "no Python runtime" — the runtime
child is a third process, and the peak sample contains three processes.

All app-created processes observed during a challenged extraction are listed
in the JSON artifact under `runs[].distinct_processes`. Besides the two
`yt-dlp` processes and the runtime child, yt-dlp spawns `ffmpeg`/`ffprobe`
(~15 MiB each) to probe the resolved formats and, occasionally, short-lived
helpers such as `ldconfig`, `rtmpdump` or `uname`.

## Method

### Tools and versions (verified)

| Tool | Version | SHA-256 | Verified against |
| --- | --- | --- | --- |
| yt-dlp (frozen Linux executable) | 2026.08.19 | `58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a` | pinned SHA-256 in `scripts/benchmark-ytdlp.py` |
| yt-dlp-ejs (bundled in the executable) | 0.8.0 | n/a | `yt-dlp -v` reports `yt_dlp_ejs-0.8.0` |
| QuickJS-NG | 0.17.0 | `0bfc02511a9f549c28b53880d988fc7cd5d361e90c5e8afdfcd7dc6774ceace5` | pinned SHA-256 in `scripts/benchmark-ytdlp.py` |
| Node | 26.11.1 | `ebafec51df89974c09045e125b4408f822cc6814d3941836d742937256dbc61a` | pre-installed system binary |
| Deno | 2.9.7 | `ce6a052beb97c2b92de67077e3f3924ba7c9661ede0d8dc2f66a116a3c841f21` | official release ZIP checksum `c6527f24f4b16031d3ae4fa9f658d5f11534c8d84ce7dc8502420280919c3490` |
| Bun | 1.3.14 | `9fd36f87e4b90b07632b987a2e4ec81ca15a62c81bf983190cea6d715be2ad74` | pre-installed binary |

The pinned yt-dlp executable and the pinned QuickJS-NG binary are the verified
tools from `.tools/resolver/`. Deno was downloaded from the official GitHub
release into the git-ignored `build/tools/runtimes/deno/` and verified against
the official `deno-x86_64-unknown-linux-gnu.zip.sha256sum` file. No global
install, no daemon and no worker was started.

### Does the pinned yt-dlp support these runtimes?

Yes, and it exercises them. `yt-dlp --help` for the pinned 2026.08.19 build
lists the supported runtimes **in priority order: deno, node, quickjs, bun**
and states that "Only *deno* is enabled by default". At runtime the pinned
executable reports:

```
[debug] Optional libraries: ... yt_dlp_ejs-0.8.0
[debug] JS runtimes: node-26.11.1
[debug] [youtube] [jsc] JS Challenge Providers: bun (unavailable), deno (unavailable), node, quickjs (unavailable)
[youtube] [jsc:node] Solving JS challenges using node
[debug] [youtube] [jsc:node] Using challenge solver lib script v0.8.0 (source: python package, variant: minified)
[debug] [youtube] [jsc:node] Using challenge solver core script v0.8.0 (source: python package, variant: minified)
```

All four runtimes solve the challenge with the **same** bundled
`yt_dlp_ejs-0.8.0` minified scripts from the Python package, so the
comparison isolates the JavaScript engine, not the solver script.

### Production-faithful flags

The harness reuses the resolver flag set of `YtDlpStreamResolver.kt`:

```
--ignore-config --no-plugin-dirs --no-remote-components --no-playlist
--skip-download --dump-single-json
-f bestaudio[protocol=https]/bestaudio[protocol=http]
--no-cache-dir --no-js-runtimes --js-runtimes <name>:<path>
```

`--no-cache-dir` is added on top of `scripts/benchmark-ytdlp.py` so no
extraction can reuse a cached player response from an earlier request. The
runtime is selected explicitly with `--no-js-runtimes --js-runtimes <name>:<path>`
exactly as the production resolver does it, so yt-dlp's own default (deno)
never silently wins.

### Coldness, isolation and fairness

* Every extraction is a **fresh yt-dlp process** with a private `HOME`,
  `XDG_CACHE_HOME`, `XDG_CONFIG_HOME`, `XDG_DATA_HOME` and `TMPDIR`.
* No cookie file is requested, read or written. No account profile, no user
  configuration and no system configuration is touched. All runs are
  anonymous.
* Runs are **serialized** — exactly one extraction at a time, so no runtime
  competes with another for CPU or page cache.
* The same machine, the same pinned yt-dlp and the same video are used for
  every runtime.

### Evidence that the challenge really ran

Four independent signals per runtime, all recorded in the JSON artifact:

1. `challenge_solved` — yt-dlp printed `Solving JS challenges using <runtime>`.
2. `runtime_script_invocations` — a shim wrapper around the runtime binary
   counted the invocations that executed a solver script. The version probe
   (`node --version`, `bun --version`, `deno --version`, `qjs --help`) is
   counted separately and excluded.
3. `runtime_command_lines` — the exact runtime command line from yt-dlp, e.g.
   `Running node: <shim> --permission -` and
   `Running QuickJS: <shim> --script /tmp/....js`.
4. **Negative control** — a deliberately broken runtime makes the challenged
   extraction fail with `JS_CHALLENGE_SOLVER_FAILED` for all four runtimes,
   and an extraction with **no** runtime at all fails the same way. The
   runtime is therefore load-bearing for this scenario, not decorative.

Every successful extraction was additionally verified by a ranged HTTP GET
against the resolved media URL: all 24 successful runs returned HTTP 206 with
65536 bytes read at the head and 65536 bytes at the tail,
`Content-Type: audio/webm`, format id 251 (`opus`, `https`).

### Scenarios

| Scenario | Video | Player client | Runs per runtime | Purpose |
| --- | --- | --- | --- | --- |
| `challenged` | `dQw4w9WgXcQ` | `web_embedded` | 3 | primary; runtime is mandatory |
| `default_client_same_video` | `dQw4w9WgXcQ` | default | 1 | production flag set; runtime invoked but not required |
| `control_unchallenged` | `jNQXAC9IVRw` | default | 2 | baseline without a challenge |

`web_embedded` was selected because it is the challenged client that reliably
exposes an audio format: with `mweb` and `tv_simply` the challenge is solved
but the requested format stays unavailable, and `tv` fails with a different
error. A run that failed with `FORMAT_UNAVAILABLE` was retried up to 5 times
per runtime until 3 successful extractions were collected; the attempt count
is recorded.

## Supported-runtime facts (primary upstream sources)

These are quoted from upstream, not from this repository.

| Fact | Source |
| --- | --- |
| Minimum supported versions: deno ≥ 2.3.0, node ≥ 22.0.0, bun ≥ 1.2.11, quickjs ≥ 2023-12-9; **all** quickjs-ng versions are supported | [`yt_dlp/utils/_jsruntime.py` @ 2026.08.19](https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/yt_dlp/utils/_jsruntime.py) |
| Provider preference: deno 1000, node 900, quickjs 850, bun 800 | [`yt_dlp/extractor/youtube/jsc/_builtin/`](https://github.com/yt-dlp/yt-dlp/tree/2026.08.19/yt_dlp/extractor/youtube/jsc/_builtin) |
| **Bun is deprecated**: versions above 1.3.14 are not supported; bun support may be dropped entirely | [`bun.py` @ 2026.08.19](https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/yt_dlp/extractor/youtube/jsc/_builtin/bun.py) and the [EJS wiki](https://github.com/yt-dlp/yt-dlp/wiki/EJS) |
| Deno is the recommended runtime and is the only one enabled by default | [EJS wiki](https://github.com/yt-dlp/yt-dlp/wiki/EJS) |
| QuickJS-NG below 0.12.0 (and quickjs below 2025-04-26) is missing optimizations and can take **minutes** per challenge | [EJS wiki](https://github.com/yt-dlp/yt-dlp/wiki/EJS), [`quickjs.py` @ 2026.08.19](https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/yt_dlp/extractor/youtube/jsc/_builtin/quickjs.py) |
| Node runs the solver under `--permission` (some permissions restricted); QuickJS receives the script through a temporary file; Bun has **no** permission restrictions (full filesystem and network access) | [`node.py`](https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/yt_dlp/extractor/youtube/jsc/_builtin/node.py), [`quickjs.py`](https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/yt_dlp/extractor/youtube/jsc/_builtin/quickjs.py), [EJS wiki](https://github.com/yt-dlp/yt-dlp/wiki/EJS) |
| `qjs --script <tempfile>` is used because QuickJS cannot read the script from stdin | [`quickjs.py` @ 2026.08.19](https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/yt_dlp/extractor/youtube/jsc/_builtin/quickjs.py) |

Every runtime measured here is inside the supported window of the pinned
yt-dlp: Node 26.11.1 ≥ 22.0.0, Deno 2.9.7 ≥ 2.3.0, Bun 1.3.14 (exactly the
last supported Bun version), QuickJS-NG 0.17.0 ≥ 0.12.0.

The upstream EJS wiki also lists Bun as having "no permission restrictions"
and full filesystem/network access for the solver script. QuickJS and Node
are more restricted. That is a security argument, not a performance one, and
it is a real factor for a shipped desktop application.

## Recommendation

* **Keep QuickJS-NG as the shipped default, but stop treating it as the
  cheapest-performing option — and do not read the 245 MiB `qjs` peak as the
  whole story.**

  The 245.31 MiB figure in `YT_DLP_APPLICATION_BENCHMARK.md` is the **runtime
  process alone** at the peak sample of a whole-application extraction; the
  resolver-only measurement here puts the same process at 256.6 MiB and the
  whole three-process tree at 359.0 MiB. The runtime is a large part of the
  resolver's footprint, but it is not the whole of it, and it is not the
  dominant part of the application's footprint.

Constrained to this evidence:

1. **Keep the current experiment's QuickJS default while evaluating the tradeoff.**
   It remains the smallest packaged runtime and lowest measured peak-RAM option.
   These measurements do not justify calling it cheapest for CPU or latency.
2. **This Bun row covers 1.3.14 only.** It had the highest peak RSS in this
   sample. The upstream support warning does not prevent Bun 1.4.3 from
   running; the separate newer comparison should inform any current choice.
3. **For lower CPU and faster challenged extraction, evaluate Node.** It had the
   lowest total CPU (1.79 s), second-lowest peak RSS (407.3 MiB), and smallest
   non-QuickJS binary (52.78 MiB). Deno is also viable: it had similar timings,
   a 423.8 MiB peak and 91.39 MiB binary, and is recommended upstream. Both
   used bundled EJS successfully with remote components disabled; npm support
   is not needed for either measured path.
4. **Challenge frequency matters.** QuickJS's 2.5x wall-time / 3.3x total-CPU
   penalty versus Node appeared on challenged extraction. The unchallenged
   controls were similar. This sample does not estimate challenge frequency
   across a user's library, account or network.
5. **Ship-time caveat.** Replacing the bundled `qjs` with Node or Deno means
   shipping a 53–91 MiB runtime next to the 38.6 MiB frozen yt-dlp, and it
   must be a runtime the target machines actually have or a sidecar the
   installer provisions. That is a packaging decision that this benchmark
   does not make.

## Limitations

* **One machine, one network, one day.** Linux 7.2.9-2-cachyos x86_64,
  16 CPUs, Python 3.14.8. The host was shared with the user's desktop
  applications; processes outside the measured tree were not counted but can
  affect scheduling and page-cache pressure.
* **Small sample.** Three successful challenged extractions per runtime and
  two controls. The absolute latencies vary with network state (the QuickJS
  challenged runs ranged 7162–10097 ms), but the large QuickJS gap was present in the repeats. The smaller differences
  among Node, Deno and Bun should not be treated as statistically established.
* **Sampled RSS is approximate.** Polling every 20 ms can miss spikes, while
  RSS also counts shared pages in each process. It is not a strict lower bound
  on unique physical memory. No PSS comparison was measured here.
* **Peak RSS is not a steady-state footprint.** These are the maxima of a
  short-lived solver process, not the resident set of an idle runtime.
* **Deno was measured with the bundled EJS variant** (`--no-npm
  --cached-only`), i.e. the same minified script as the other runtimes. Its
  npm-capable variant could behave differently, but the production resolver
  forbids `--remote-components`, so the bundled variant is the relevant one.
* **The old legacy-browser benchmark is not a valid comparison.**
  `YT_DLP_APPLICATION_BENCHMARK.md` compares the legacy resolver against
  yt-dlp, but every anonymous legacy resolution in that run **failed**; its
  headless-browser PoToken path was never entered, so its "peak RSS" is the
  footprint of a failure and its "latency" is the latency of a failure. It
  cannot be used to argue that any runtime, QuickJS included, is cheaper than
  the legacy path. A paired **authenticated** application benchmark with an
  equivalent account state is required for that question and is out of scope
  here; it is handled separately and must not be inferred from this report.
* **No playback was measured.** This is a resolution and challenge-cost
  benchmark. It says nothing about decode cost or playback latency.

## Reproducing

```bash
# Deno (official release, verified against the published checksum), into the
# git-ignored build/tools tree:
mkdir -p build/tools/runtimes/deno
curl -L -o build/tools/runtimes/deno/deno.zip \
  https://github.com/denoland/deno/releases/download/v2.9.7/deno-x86_64-unknown-linux-gnu.zip
curl -L -o build/tools/runtimes/deno/deno.zip.sha256sum \
  https://github.com/denoland/deno/releases/download/v2.9.7/deno-x86_64-unknown-linux-gnu.zip.sha256sum
(cd build/tools/runtimes/deno && \
  mv deno.zip deno-x86_64-unknown-linux-gnu.zip && \
  sha256sum -c deno.zip.sha256sum && \
  unzip -o deno-x86_64-unknown-linux-gnu.zip && chmod +x deno)

python3 scripts/benchmark-ytdlp-runtimes.py \
  --yt-dlp .tools/resolver/yt-dlp \
  --qjs    .tools/resolver/qjs \
  --node   /usr/bin/node \
  --bun    ~/.bun/bin/bun \
  --deno   build/tools/runtimes/deno/deno \
  --repeats 3 \
  --out-dir build/reports/ytdlp-runtimes \
  --out-json packaging/resolver/validation/linux-runtime-comparison.json
```

## Artifacts

* `scripts/benchmark-ytdlp-runtimes.py` — the harness. It imports the
  existing `scripts/benchmark-ytdlp.py` and reuses its process-tree RSS
  sampler, hermetic environment builder, URL redaction, failure classifier
  and HTTP probe, and adds CPU accounting, per-process attribution, runtime
  shims and the negative controls. Aggregate medians and attempt counts were
  corrected from the unchanged raw runs: even sample sizes average their two
  middle values, and attempts are counted once each.
* `packaging/resolver/validation/linux-runtime-comparison.json` — the
  machine-readable result of the run described above.
* `YT_DLP_RUNTIME_COMPARISON.md` — this report.

The JSON artifact contains runtime names and versions, binary sizes and
SHA-256 values, public video IDs, timings, CPU seconds, RSS/PSS-class byte
counts, process names, PIDs, format ids, codecs and a coarse failure class.
It contains **no credentials, no cookies, no signed media URLs and no
command lines of the resolver process**; the only URLs it stores are the
upstream documentation links.

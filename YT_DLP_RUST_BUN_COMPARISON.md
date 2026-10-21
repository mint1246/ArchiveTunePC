# yt-dlp EJS JS runtime comparison: the Rust-rewrite Bun (1.4.x)

**Question.** The runtime comparison in `YT_DLP_RUNTIME_COMPARISON.md` measured
**Bun 1.3.14** — the last Bun version the pinned yt-dlp supports. Bun 1.4
rewrote the runtime in Rust, so 1.3.14 predates the rewrite. Does the Rust
rewrite change the cost of the yt-dlp EJS challenge solver, and does the
pinned yt-dlp accept it at all?

**Answer, measured.**

1. **The pinned yt-dlp 2026.08.19 accepts Bun 1.4.3 with the *unchanged* CLI.**
   No version-check bypass, no wrapper, no spoofed `--version` output was used
   (`version_gate.cli_gate_bypassed = false` in the JSON artifact). Every
   challenged extraction succeeded and the challenge was solved by the `bun`
   provider. yt-dlp does print a warning
   (`bun version 1.4.3 is not supported! 1.3.14 is the last supported bun
   version.`), but it **executes the runtime anyway** — the upstream gate is a
   warning, not a refusal.
2. **Upstream still calls it unsupported.** The same pinned source declares
   `_BUN_MAX_SUPPORTED_VERSION = (1, 3, 14)`. "Works with the pinned build"
   is a technical result, not an upstream support statement, and this report
   does not upgrade it into one.
3. **The stable, repeatable difference is memory, not speed.** Across six
   independent repetitions of the whole benchmark, Bun 1.4.3's peak process-tree
   RSS was **lower in every single repetition**, by 57–82 MiB (12–17%; pooled
   medians 400.0 MiB vs 472.1 MiB).
4. **The CPU difference is not stable.** Bun 1.4.3's solver child is
   *bimodal*: 9 of 18 challenged runs used ~0.90–0.97 s of child CPU (better
   than Bun 1.3.14) and 9 of 18 used ~1.54–1.62 s (worse). A three-sample
   median therefore lands anywhere between 1.84 s and 2.50 s of total CPU
   depending on which mode dominates. This is a **three-run, single-workload
   observation**, not a claim about the Rust JIT in general.
5. **Wall time is not separated.** Bun 1.3.14 and Bun 1.4.3 overlap
   completely (2526–3411 ms vs 2656–3107 ms over 18 runs each).
6. **Nothing changes in production.** The shipped runtime stays QuickJS-NG.
   Bun is deprecated upstream, 1.4.3 is above the supported ceiling, and the
   one solid win (memory) does not outweigh an unsupported, unpermissioned
   runtime. **No production runtime, Gradle task, pinned manifest or global
   installation was modified by this work.**

Everything below is a measurement of the pinned tools in this repository. No
engine marketing claims are used as evidence.

## What was measured

| Runtime | Version | Role | Binary size | SHA-256 of the binary |
| --- | --- | --- | ---: | --- |
| **QuickJS-NG** | 0.17.0 | shipped resolver runtime | 2.44 MiB | `0bfc02511a9f549c28b53880d988fc7cd5d361e90c5e8afdfcd7dc6774ceace5` |
| **Node** | 26.11.1 | reference runtime | 52.78 MiB | `ebafec51df89974c09045e125b4408f822cc6814d3941836d742937256dbc61a` |
| **Bun (legacy)** | 1.3.14 | previously measured baseline | 88.46 MiB | `9fd36f87e4b90b07632b987a2e4ec81ca15a62c81bf983190cea6d715be2ad74` |
| **Bun (Rust)** | 1.4.3 | **the new artifact under test** | 84.86 MiB | `7ce7d6b654eddeec20afddf2396317fba6dc19ab52788a27e894e1806bf8f24b` |

The legacy Bun is the **pre-installed** `~/.bun/bin/bun`. It was **not**
upgraded, replaced or moved; the Rust build lives in a separate, git-ignored
directory and is referenced by an absolute path.

### Provenance of the new artifact

The version was **not assumed**. It was resolved from the primary upstream
release feed and then verified end to end:

| Step | Result |
| --- | --- |
| Latest stable release of `oven-sh/bun` | `bun-v1.4.3`, published 2026-10-10T05:24:54Z, `prerelease=false`, `draft=false` |
| Release page | <https://github.com/oven-sh/bun/releases/tag/bun-v1.4.3> |
| Archive | `bun-linux-x64.zip`, 42 015 139 bytes, `sha256:3e730528d89775f03f87f05c06787c4df4099d0a37f3f252e7b829cb23816e18` |
| GitHub release-API asset digest | identical to the locally computed digest |
| `SHASUMS256.txt` published in the release | lists the same digest for `bun-linux-x64.zip` — verified with `sha256sum -c` |
| `SHASUMS256.txt.asc` | **clearsigned, good signature** from key `F3DCC08A8572C0749B3E18888EAB4D40A7B22B59` ("Robobun &lt;robobun@oven.sh&gt;"), fetched from <https://keys.openpgp.org/vks/v1/by-fingerprint/F3DCC08A8572C0749B3E18888EAB4D40A7B22B59> |
| Clearsigned payload | byte-identical to the separately downloaded `SHASUMS256.txt`, so the checksum list that was verified is provably the signed one |
| Extracted binary | reports `bun --version` → `1.4.3`, `bun --revision` → `1.4.3+c6da4a4d3` |
| New artifact version SHA-256 (binary) | `7ce7d6b654eddeec20afddf2396317fba6dc19ab52788a27e894e1806bf8f24b` |

The whole chain is reproducible with the new developer helper
`scripts/fetch-bun-release.py`, which writes the machine-readable provenance
that the benchmark embeds as `artifact_provenance`:

```
scripts/fetch-bun-release.py \
    --dest-dir build/tools/runtimes \
    --expect-version 1.4.3 \
    --out-json build/tools/runtimes/bun-1.4.3-provenance.json
```

`build/tools/runtimes/` is git-ignored. Nothing was installed system-wide, no
`PATH` entry was added, and no existing Bun installation was touched.

### "Rust rewrite" — the upstream statement

Bun 1.4 is the Rust rewrite, quoted from upstream rather than inferred:

> "We rewrote Bun in Rust - and this is the first release"

— [Bun v1.4 release blog](https://bun.com/blog/bun-v1.4). The Bun repository
README likewise describes the runtime as "written in Rust and powered by
JavaScriptCore under the hood". Bun 1.4.3 is the third patch release of that
line, so it is a post-rewrite build.

## Support status under the pinned yt-dlp

### What the pinned source actually does

The relevant pinned sources are `yt_dlp/extractor/youtube/jsc/_builtin/bun.py`
and `yt_dlp/utils/_jsruntime.py`, both at tag `2026.08.19`:

* `bun.py` sets `_BUN_MAX_SUPPORTED_VERSION = (1, 3, 14)` and
  `_BUN_DEPRECATION_URL = 'https://github.com/yt-dlp/yt-dlp/issues/16766'`.
* In `_run_js_runtime`, the unsupported branch only *logs*:

  ```python
  is_unsupported_version = self.runtime_info.version_tuple > self._BUN_MAX_SUPPORTED_VERSION
  if is_unsupported_version:
      self.logger.warning(
          f'bun version {".".join(...)} is not supported! '
          f'{".".join(...)} is the last supported bun version. '
          f'{... "DO NOT" ...} open a bug report even if you encounter any errors!',
          once=True)
  ```

  and then falls through into the *same* `bun --bun run ...` invocation. The
  supported branch logs the deprecation notice instead. `is_unsupported_version`
  is used for exactly one other thing: the `expected=` flag of the provider
  error, i.e. it changes how a *failure* is reported, not whether the runtime
  runs.
* `yt_dlp/utils/_jsruntime.py` sets `BunJsRuntime.MIN_SUPPORTED_VERSION = (1, 2, 11)`
  and `JsRuntimeInfo.supported = vt >= MIN_SUPPORTED_VERSION`. This is the only
  value that makes a runtime *unavailable*: `EJSBaseJCP.runtime_info` returns
  `None` when `not runtime.info.supported`. Bun 1.4.3 ≥ 1.2.11, so it stays
  available.

### What was observed

`version_gate` in the JSON artifact, straight from yt-dlp's own diagnostics:

| Runtime | Version | Above the pinned 1.3.14 ceiling | yt-dlp message | Outcome | Challenge solved | CLI gate bypassed |
| --- | --- | --- | --- | --- | --- | --- |
| QuickJS-NG | 0.17.0 | n/a | none | `accepted` | 3/3 | `false` |
| Node | 26.11.1 | n/a | none | `accepted` | 3/3 | `false` |
| Bun | 1.3.14 | no | `bun support has been deprecated. See https://github.com/yt-dlp/yt-dlp/issues/16766 for details` | `accepted_with_warning` | 3/3 | `false` |
| **Bun (Rust)** | **1.4.3** | **yes** | `WARNING: … bun version 1.4.3 is not supported! 1.3.14 is the last supported bun version. DO NOT open a bug report even if you encounter any errors!` | **`accepted_with_warning`** | **3/3** | **`false`** |

**Unchanged CLI vs. bypass: unchanged CLI was sufficient, so no bypass was
built or used.** The task anticipated a "version-check-bypass wrapper" that
reports `1.3.14` for the `--version` probe while executing the verified 1.4.3
binary. That contingency was **not needed**: yt-dlp 2026.08.19 does not
reject a newer Bun, it warns and runs it. Consequently there is no
experimental bypass to compare against a wrapper-overhead baseline, and none
was fabricated. Every number in this report comes from the real Bun 1.4.3
binary reporting its real version.

**Support status, stated plainly:** Bun 1.4.3 is *officially unsupported* by
the pinned yt-dlp (the upstream source says so and the runtime's own
diagnostics say so) while being *technically functional* with that same
pinned build. Nothing here turns the first fact into the second.

## Measured results

Cold, serialized extractions of the **challenged** video `dQw4w9WgXcQ` with
`player_client=web_embedded` (the runtime is load-bearing here), three
successful extractions per runtime, same machine, same pinned yt-dlp. These
are the numbers of the published artifact
(`packaging/resolver/validation/linux-rust-bun-comparison.json`):

| Runtime | Version | Binary size | Wall time (median) | CPU time (median) | Peak tree RSS (max) | Runtime child RSS (max) | Runtime child CPU (max) |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| **bun** | 1.3.14 | 88.46 MiB | **2685 ms** | **2.092 s** | **485.8 MiB** | bun 380.7 MiB | bun 1.18 s |
| **bun-rust** | 1.4.3 | 84.86 MiB | **2669 ms** | **1.843 s** | **404.0 MiB** | bun 298.8 MiB | bun 1.61 s |
| **node** | 26.11.1 | 52.78 MiB | **2898 ms** | **1.828 s** | **395.8 MiB** | node-MainThread 296.9 MiB | node-MainThread 0.91 s |
| **quickjs-ng** | 0.17.0 | 2.44 MiB | **7389 ms** | **5.923 s** | **358.9 MiB** | qjs 255.0 MiB | qjs 5.01 s |

Relative to QuickJS-NG (the shipped runtime):

| Runtime | Wall vs quickjs-ng | CPU vs quickjs-ng | Peak RSS vs quickjs-ng |
| --- | ---: | ---: | ---: |
| bun | 0.36x | 0.35x | 1.35x |
| bun-rust | 0.36x | 0.31x | 1.13x |
| node | 0.39x | 0.31x | 1.10x |
| quickjs-ng | 1.00x | 1.00x | 1.00x |

Incremental cost of the challenge over the *clean* unchallenged control
(`jNQXAC9IVRw`, default player client, no solver script executed):

| Runtime | Clean control wall (median) | Clean control CPU (median) | Δ wall (challenged − clean control) | Δ CPU (challenged − clean control) | Δ peak tree RSS |
| --- | ---: | ---: | ---: | ---: | ---: |
| bun | 2017.0 ms | 0.750 s | +668 ms | +1.343 s | 395.6 MiB |
| bun-rust | 2099.0 ms | 0.750 s | +570 ms | +1.093 s | 318.0 MiB |
| node | 2006.0 ms | 0.756 s | +892 ms | +1.072 s | 309.7 MiB |
| quickjs-ng | 2116.5 ms | 0.750 s | +5272 ms | +5.173 s | 258.1 MiB |

The control video is not guaranteed to stay unchallenged — YouTube sometimes
challenges it too. The "clean control" column therefore uses only the control
runs that executed **no** solver script at all
(`per_runtime.<key>.control_unchallenged_clean`); the uncleaned numbers are
kept in the JSON as `control_unchallenged`.

### Per-run detail of the published artifact

| Runtime | Run | Wall (ms) | Total CPU (s) | Peak tree RSS | Runtime child CPU ticks | Challenge solved | Solver script invocations | HTTP head | HTTP tail |
| --- | ---: | ---: | ---: | ---: | ---: | --- | ---: | --- | --- |
| quickjs-ng | 1 | 7225 | 5.945 | 358.5 MiB | qjs 501 | yes | 1 | 206 / 65536 bytes | 206 / 65536 bytes |
| quickjs-ng | 2 | 7389 | 5.923 | 343.3 MiB | qjs 500 | yes | 1 | 206 / 65536 bytes | 206 / 65536 bytes |
| quickjs-ng | 3 | 7489 | 5.914 | 358.9 MiB | qjs 499 | yes | 1 | 206 / 65536 bytes | 206 / 65536 bytes |
| node | 1 | 2898 | 1.825 | 389.8 MiB | node-MainThread 90 | yes | 1 | 206 / 65536 bytes | 206 / 65536 bytes |
| node | 2 (failed) | 2997 | 1.776 | 393.8 MiB | node-MainThread 88 | yes | 1 | none | none |
| node | 2 | 2911 | 1.846 | 395.5 MiB | node-MainThread 91 | yes | 2 | 206 / 65536 bytes | 206 / 65536 bytes |
| node | 3 (failed) | 3129 | 1.752 | 402.3 MiB | node-MainThread 84 | yes | 1 | none | none |
| node | 3 | 2787 | 1.828 | 395.8 MiB | node-MainThread 87 | yes | 2 | 206 / 65536 bytes | 206 / 65536 bytes |
| bun | 1 | 2807 | 2.092 | 485.8 MiB | bun 115 | yes | 1 | 206 / 65536 bytes | 206 / 65536 bytes |
| bun | 2 | 2680 | 2.107 | 484.4 MiB | bun 118 | yes | 1 | 206 / 65536 bytes | 206 / 65536 bytes |
| bun | 3 | 2685 | 1.995 | 466.6 MiB | bun 106 | yes | 1 | 206 / 65536 bytes | 206 / 65536 bytes |
| bun-rust | 1 | 2856 | 2.541 | 404.0 MiB | bun 161 | yes | 1 | 206 / 65536 bytes | 206 / 65536 bytes |
| bun-rust | 2 | 2669 | 1.843 | 380.0 MiB | bun 91 | yes | 1 | 206 / 65536 bytes | 206 / 65536 bytes |
| bun-rust | 3 | 2559 | 1.833 | 384.6 MiB | bun 90 | yes | 1 | 206 / 65536 bytes | 206 / 65536 bytes |

Two of Node's five challenged attempts ended in `FORMAT_UNAVAILABLE`: the
challenge *was* solved (`challenge_solved=true`, one solver-script invocation)
but YouTube returned no formats on that attempt, so the requested audio
format was missing. The harness retried until three successful extractions
were collected and records the attempt count
(`attempts_total = len(runs)`); Node therefore shows
`attempts_total = 5, successful = 3`. The failed attempts are **not**
discarded from the artifact — they are in `runs[]` with their own wall time,
CPU and RSS.

### Repetition: why a three-run median is not enough here

The whole benchmark was repeated **six times** on the same machine, same
pinned yt-dlp, same videos (18 successful challenged extractions per runtime
in total; Node needed 20 attempts to get its 18 successes because of the two
retries described above). The five earlier repetitions are attached to the
published artifact under `repetitions`.

Median of the **successful** challenged runs, per repetition (the medians are
taken over successful extractions only; the attempt count including the two
Node retries is `attempts_total` in the artifact):

| Repetition | Runtime | Wall median (ms) | CPU median (s) | Peak tree RSS max (MiB) |
| --- | --- | ---: | ---: | ---: |
| this run | bun | 2685 | 2.092 | 485.8 |
| this run | bun-rust | 2669 | 1.843 | 404.0 |
| this run | node | 2898 | 1.828 | 395.8 |
| this run | quickjs-ng | 7389 | 5.923 | 358.9 |
| run 1 | bun | 2844 | 2.029 | 475.8 |
| run 1 | bun-rust | 2693 | 1.917 | 412.3 |
| run 1 | node | 2908 | 1.842 | 408.5 |
| run 1 | quickjs-ng | 7576 | 5.974 | 358.8 |
| run 2 | bun | 2760 | 2.007 | 472.1 |
| run 2 | bun-rust | 2690 | 1.902 | 403.4 |
| run 2 | node | 2873 | 1.847 | 410.4 |
| run 2 | quickjs-ng | 7430 | 6.025 | 359.1 |
| run 3 | bun | 2821 | 2.022 | 474.0 |
| run 3 | bun-rust | 2823 | 2.480 | 406.9 |
| run 3 | node | 2805 | 1.849 | 409.6 |
| run 3 | quickjs-ng | 7334 | 5.970 | 358.9 |
| run 4 | bun | 2886 | 2.004 | 474.0 |
| run 4 | bun-rust | 2876 | 2.503 | 414.6 |
| run 4 | node | 2948 | 1.831 | 393.8 |
| run 4 | quickjs-ng | 7172 | 5.902 | 358.7 |
| run 5 | bun | 2852 | 2.025 | 473.2 |
| run 5 | bun-rust | 2787 | 1.849 | 416.0 |
| run 5 | node | 2898 | 1.841 | 393.9 |
| run 5 | quickjs-ng | 7273 | 5.920 | 358.8 |

Pooled over all repetitions, the **solver child's own CPU** (sampled ticks at
100 Hz, one value per challenged run):

| Runtime | Solver-child CPU ticks across all challenged runs | Interpretation |
| --- | --- | --- |
| quickjs-ng | 493, 494, 496, 497, 498, 499, 499, 499, 500, 500, 501, 503, 503, 504, 506, 509, 513, 514 | stable, ~4.9–5.1 s |
| node | 84, 86, 87, 87, 88, 88, 89, 89, 90, 90, 90, 90, 90, 90, 91, 91, 91, 91, 91, 92 | stable, ~0.84–0.92 s |
| bun 1.3.14 | 106, 106, 106, 106, 107, 107, 108, 108, 108, 108, 108, 109, 109, 109, 112, 112, 115, 118 | stable, ~1.06–1.18 s |
| **bun 1.4.3** | **90, 91, 91, 91, 91, 91, 92, 95, 97** ‖ **154, 155, 156, 156, 156, 158, 159, 161, 162** | **bimodal: ~0.90–0.97 s or ~1.54–1.62 s** |

This is the most important technical result of the comparison, and it is a
**negative** one for the Rust rewrite as a *performance* argument:

* QuickJS-NG, Node and Bun 1.3.14 are each **unimodal**. Their solver-child
  CPU is reproducible to within a few percent across 18–20 runs.
* Bun 1.4.3's solver child is **bimodal**: 9 of 18 runs landed in a fast mode
  (~0.90–0.97 s, i.e. on par with Node and *better* than Bun 1.3.14) and 9 of
  18 in a slow mode (~1.54–1.62 s, i.e. clearly *worse* than Bun 1.3.14).
* Consequently Bun 1.4.3's three-run CPU median lands anywhere between
  **1.843 s and 2.503 s** depending on the mix, while its true pooled median
  is 1.255 s of child CPU — *higher* than Bun 1.3.14's pooled 1.080 s,
  because the slow mode occurred exactly as often as the fast one. Any single
  three-run median of Bun 1.4.3 is therefore an unreliable statistic, and a
  report that quoted only the favourable repetition would be cherry-picking.

**This is a three-run, single-workload observation.** It is **not** a claim
about the Rust JIT, about Bun 1.4 in general, or about any other workload.
The cause of the bimodality was not investigated; it could be tiering,
garbage-collection timing, or something about how the EJS solver script
triggers the engine. Establishing a cause needs a dedicated experiment that
this task did not perform.

### Where the memory goes

Largest sampled per-process-class values across the challenged runs of the
published artifact. They can come from different samples and must not be
summed to reconstruct a simultaneous peak; use the whole-tree figures above
for that.

| Process class | QuickJS-NG | Node | Bun 1.3.14 | Bun 1.4.3 |
| --- | ---: | ---: | ---: | ---: |
| runtime child | `qjs` 255.0 MiB | `node-MainThread` 296.9 MiB | `bun` 380.7 MiB | `bun` 298.8 MiB |
| frozen `yt-dlp` (bootloader + embedded CPython) | 101.3 MiB | 103.9 MiB | 103.5 MiB | 102.7 MiB |

The frozen yt-dlp **does embed CPython**. The PyInstaller one-file binary
forks into two processes that both report the comm `yt-dlp`: the bootloader
(~2.6 MiB) and the CPython worker (~100 MiB). The absence of a separately
named `python` process does **not** mean "no Python runtime".

**The one clear, repeatable win of the Rust rewrite is memory.** Across the six
repetitions, Bun 1.4.3's solver child peaked at **298–311 MiB** while
Bun 1.3.14's peaked at **366–381 MiB** — roughly **56–83 MiB (15–22%) less**
— and the whole-tree peak was correspondingly 57–82 MiB lower every time.
This matches the direction of Bun's own upstream claim that 1.4 "reduces
memory usage by up to 35%", but the measured effect here is specific to this
workload and this machine.

### Evidence that the challenge really ran

Five independent signals, all recorded in the JSON artifact:

1. `challenge_solved` — yt-dlp printed `Solving JS challenges using bun`.
2. `challenge_provider` — the provider was `bun` for every Bun run, `node`
   for Node and `quickjs` for QuickJS-NG, i.e. each runtime solved **its own**
   challenge.
3. `runtime_script_invocations` — a shim wrapper around the runtime binary
   counted the invocations that executed a solver script. The version probe
   (`bun --version`, `node --version`, `qjs --help`) is counted separately and
   excluded. Every challenged run shows ≥ 1 solver-script invocation.
4. `runtime_command_lines` — the exact runtime command line from yt-dlp, e.g.
   `Running bun: <shim> --bun run --no-addons --prefer-offline --no-install -`.
   This is the command the *new* Bun 1.4.3 really executed.
5. **Negative controls** — a deliberately broken runtime makes the challenged
   extraction fail with `JS_CHALLENGE_SOLVER_FAILED` for all four runtimes,
   and an extraction with **no** runtime at all fails the same way. The
   runtime is therefore load-bearing for this scenario, not decorative.

Every successful extraction was additionally verified by a ranged HTTP GET
against the resolved media URL — a **head** range and a **tail** range, because
a URL that serves byte zero but rejects a later range can start playing and
then fail. All 24 successful runs of the published artifact returned HTTP
**206** with **65 536 bytes** at the head *and* **65 536 bytes** at the tail,
`Content-Type: audio/webm`, format id 251 (`opus`, `https`).

## Method

### Tools and versions (verified)

| Tool | Version | SHA-256 | Verified against |
| --- | --- | --- | --- |
| yt-dlp (frozen Linux executable) | 2026.08.19 | `58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a` | pinned SHA-256 in `scripts/benchmark-ytdlp.py` |
| yt-dlp-ejs (bundled in the executable) | 0.8.0 | n/a | `yt-dlp -v` reports `yt_dlp_ejs-0.8.0` |
| QuickJS-NG | 0.17.0 | `0bfc02511a9f549c28b53880d988fc7cd5d361e90c5e8afdfcd7dc6774ceace5` | pinned SHA-256 in `scripts/benchmark-ytdlp.py` |
| Node | 26.11.1 | `ebafec51df89974c09045e125b4408f822cc6814d3941836d742937256dbc61a` | pre-installed system binary |
| Bun (legacy) | 1.3.14 | `9fd36f87e4b90b07632b987a2e4ec81ca15a62c81bf983190cea6d715be2ad74` | pre-installed `~/.bun/bin/bun`, **not upgraded** |
| Bun (Rust) | 1.4.3 | `7ce7d6b654eddeec20afddf2396317fba6dc19ab52788a27e894e1806bf8f24b` | official release ZIP checksum **and** clearsigned `SHASUMS256.txt` (see provenance table) |

The pinned yt-dlp executable and the pinned QuickJS-NG binary are the verified
tools of `.tools/resolver/`; this run used the read-only copies of the same
two binaries (identical SHA-256) from the resolver worktree. Bun 1.4.3 was
downloaded from the official GitHub release into the git-ignored
`build/tools/runtimes/` and verified against the official signed checksum
list. No global install, no daemon and no worker was started.

### Production-faithful flags

The harness reuses the resolver flag set of `YtDlpStreamResolver.kt`:

```
--ignore-config --no-plugin-dirs --no-remote-components --no-playlist
--skip-download --dump-single-json
-f bestaudio[protocol=https]/bestaudio[protocol=http]
--no-cache-dir --no-js-runtimes --js-runtimes <name>:<path>
```

The runtime is selected explicitly with
`--no-js-runtimes --js-runtimes <name>:<path>` exactly as the production
resolver does it, so yt-dlp's own default (deno) never silently wins. The
runtime is invoked through the **unchanged** yt-dlp command line — no
wrapper, no shim that changes behaviour. The shim used for invocation
counting only appends a line to a log file and then `exec`s the real binary.

### Coldness, isolation and fairness

* Every extraction is a **fresh yt-dlp process** with a private `HOME`,
  `XDG_CACHE_HOME`, `XDG_CONFIG_HOME`, `XDG_DATA_HOME` and `TMPDIR`.
* No cookie file is requested, read or written. No account profile, no user
  configuration and no system configuration is touched. All runs are
  **anonymous**. (The authenticated full-application benchmark is a separate
  artifact and is not part of this engine comparison.)
* Signed media URLs are never printed and never written to disk. The
  `--dump-single-json` payload is parsed in memory and discarded; only safe
  derived fields (ids, codecs, protocols, timings, byte counts) are stored.
* Runs are **serialized** — exactly one extraction at a time, so no runtime
  competes with another for CPU or page cache.
* The same machine, the same pinned yt-dlp, the same video and the same
  solver scripts are used for every runtime.

### CPU accounting

Total CPU is a `getrusage(RUSAGE_CHILDREN)` delta taken around each
extraction. On Linux this is **transitive**, so the number includes the
frozen yt-dlp bootloader, the embedded **CPython** worker, the **JS runtime
child** and the short-lived helpers yt-dlp spawns (`ffmpeg`, `ffprobe`, and
occasionally `file`, `uname`, `rtmpdump`). The per-process attribution in
`runs[].cpu_ticks_by_comm` splits that total by process class; the
`Runtime child CPU` column is the **maximum** observed per process class over
the challenged runs, never a median.

### Zero resident worker

Every extraction is a short-lived process tree. After the last run the
harness scans `/proc` for any process still using one of the measured
executables. Processes that were already running **before** the benchmark
started are excluded (they are not the benchmark's residue) and their count is
recorded. The published artifact records:

```json
"resident_worker_check": {
  "preexisting_processes_excluded": 2,
  "resident_leftover_count": 0,
  "no_resident_worker": true
}
```

### Scenarios

| Scenario | Video | Player client | Runs per runtime | Purpose |
| --- | --- | --- | --- | --- |
| `challenged` | `dQw4w9WgXcQ` | `web_embedded` | 3 successful (≤5 attempts) | primary; the runtime is mandatory |
| `default_client_same_video` | `dQw4w9WgXcQ` | default | 1 | production flag set; runtime invoked but not required |
| `control_unchallenged` | `jNQXAC9IVRw` | default | 2 | baseline without a challenge |

`web_embedded` was selected because it is the challenged client that reliably
exposes an audio format.

## Upstream facts (primary sources)

These are quoted from upstream, not from this repository.

| Fact | Source |
| --- | --- |
| `bun.py` keeps `_BUN_MAX_SUPPORTED_VERSION = (1, 3, 14)` and warns — but still executes — a newer bun | [`bun.py` @ 2026.08.19](https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/yt_dlp/extractor/youtube/jsc/_builtin/bun.py) |
| `BunJsRuntime.MIN_SUPPORTED_VERSION = (1, 2, 11)`; `supported = vt >= MIN_SUPPORTED_VERSION` is the only availability gate | [`_jsruntime.py` @ 2026.08.19](https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/yt_dlp/utils/_jsruntime.py) |
| "bun support has been deprecated", pointing at the upstream issue | [`bun.py` @ 2026.08.19](https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/yt_dlp/extractor/youtube/jsc/_builtin/bun.py), [yt-dlp issue 16766](https://github.com/yt-dlp/yt-dlp/issues/16766) |
| Provider preference: deno 1000, node 900, quickjs 850, bun 800 | [`jsc/_builtin/` @ 2026.08.19](https://github.com/yt-dlp/yt-dlp/tree/2026.08.19/yt_dlp/extractor/youtube/jsc/_builtin) |
| Bun 1.4 is the Rust rewrite: "We rewrote Bun in Rust - and this is the first release" | [Bun v1.4 release blog](https://bun.com/blog/bun-v1.4) |
| The latest stable upstream release is `bun-v1.4.3` (2026-10-10), signed with `SHASUMS256.txt.asc` | [release `bun-v1.4.3`](https://github.com/oven-sh/bun/releases/tag/bun-v1.4.3) |

The deprecation *motivation* is deliberately not asserted here. The upstream
issue exists and the pinned code links to it, but this report does not
characterise why upstream deprecated Bun, because it does not read the issue
as evidence for a performance claim.

## Recommendation

**No production change. Keep QuickJS-NG as the shipped resolver runtime.**

Constrained to this evidence:

1. **Bun 1.4.3 is technically usable with the pinned yt-dlp, but it is not
   supported by it.** The pinned source caps Bun at 1.3.14 and says so in the
   runtime's own diagnostics. Shipping an unsupported runtime for a desktop
   application that resolves signed media URLs is a worse trade than the
   memory saving is worth.
2. **The Rust rewrite's memory win is real but small relative to the risk.**
   57–82 MiB less peak tree RSS on a ~360–490 MiB tree, with no measured
   latency win, bought at the cost of an unsupported, unpermissioned runtime
   (Bun gives the solver script full filesystem and network access, while
   QuickJS and Node are restricted).
3. **The CPU result does not support switching.** Bun 1.4.3's solver child is
   bimodal, so its median CPU over three runs is unstable; on the pooled data
   its median child CPU is actually *higher* than Bun 1.3.14's (1.255 s vs
   1.080 s).
4. **QuickJS-NG remains the smallest artifact by a wide margin** (2.44 MiB vs
   84.86 MiB) and the lowest measured peak RSS, at the cost of ~2.6x Node's
   wall time and ~3.2x Node's total CPU on challenged extraction. That
   trade-off is unchanged by this report.
5. **If the upstream bun support situation changes**, this benchmark can be
   re-run unchanged: `--bun-new` measures a second Bun build with the same
   unchanged CLI, and `scripts/fetch-bun-release.py` re-provisions and
   re-verifies the artifact.

### What was explicitly *not* changed

* No Kotlin/Gradle/production source file was touched.
* No pinned manifest, no default runtime, no resolver flag set was changed.
* The pre-installed `~/.bun/bin/bun` (1.3.14) was **not** upgraded, replaced
  or removed, and nothing was added to `PATH`.
* The new Bun was provisioned into the git-ignored `build/tools/runtimes/`.
* `YT_DLP_RUNTIME_COMPARISON.md`, `YT_DLP_RESOLVER.md`,
  `YT_DLP_APPLICATION_BENCHMARK.md` and the existing
  `packaging/resolver/validation/linux-runtime-comparison.json` were left
  untouched.

## Limitations

* **Three runs per repetition, one workload, one machine, one day.** Linux
  7.2.9-2-cachyos x86_64, 16 CPUs, Python 3.14.8. The host was shared with
  the user's desktop applications; processes outside the measured tree were
  not counted but can affect scheduling and page-cache pressure.
* **The CPU bimodality of Bun 1.4.3 is unexplained.** This report documents
  it and refuses to summarise it as an improvement. It is *not* a general
  statement about the Rust JIT, about Bun 1.4, or about any workload other
  than the yt-dlp EJS challenge solver.
* **Sampled RSS is approximate.** Polling every 20 ms can miss spikes, and
  RSS counts shared pages in each process. The whole-tree peak is therefore
  approximate, and the frozen yt-dlp executable's pages are counted once per
  process that maps them.
* **Wall time includes the network.** The challenge itself is only part of
  each extraction; the clean control is what isolates it, and even the Δ
  values carry network variance.
* **Challenge frequency is not estimated.** The unchallenged controls were
  similar across runtimes; this says nothing about how often real users
  encounter challenged videos.
* **The upstream support status of Bun 1.4.x can change at any time.** The
  pinned yt-dlp is 2026.08.19; a later yt-dlp may hard-reject a newer Bun, in
  which case the "accepted_with_warning" outcome recorded here no longer
  applies.
* **A single video is challenged.** `dQw4w9WgXcQ` with `web_embedded` is one
  challenge shape; other videos may stress the engine differently.

## Reproducing this

```bash
# 1. provision and verify the Bun artifact (git-ignored destination)
scripts/fetch-bun-release.py \
    --dest-dir build/tools/runtimes \
    --expect-version 1.4.3 \
    --out-json build/tools/runtimes/bun-1.4.3-provenance.json

# 2. run the comparison (unchanged yt-dlp CLI, no gate bypass)
scripts/benchmark-ytdlp-runtimes.py \
    --yt-dlp  <repo>/.tools/resolver/yt-dlp \
    --qjs     <repo>/.tools/resolver/qjs \
    --node    /usr/bin/node \
    --bun     ~/.bun/bin/bun \
    --bun-new build/tools/runtimes/bun-1.4.3/bun-linux-x64/bun \
    --repeats 3 \
    --baseline-runtime quickjs-ng \
    --out-dir build/reports/ytdlp-runtimes-rust-bun \
    --out-json build/reports/ytdlp-runtimes-rust-bun/linux-rust-bun-comparison.raw.json \
    --report-schema archivetune.ytdlp.rust-bun-comparison/1 \
    --artifact-provenance build/tools/runtimes/bun-1.4.3-provenance.json

# 3. regenerate the tables of this report from the artifact
scripts/summarize-ytdlp-runtimes.py \
    packaging/resolver/validation/linux-rust-bun-comparison.json
```

## Artifacts

| Artifact | Content |
| --- | --- |
| `YT_DLP_RUST_BUN_COMPARISON.md` | this report |
| `packaging/resolver/validation/linux-rust-bun-comparison.json` | machine-readable results: `runs[]`, `per_runtime`, `headline_comparison`, `version_gate`, `negative_controls`, `missing_runtime_controls`, `repetitions`, `artifact_provenance`, `resident_worker_check`, `tools`, `upstream_sources` |
| `scripts/benchmark-ytdlp-runtimes.py` | the harness (extended with `--bun-new`, `--artifact-provenance`, `--report-schema`, `--baseline-runtime`, `--repetition-json`) |
| `scripts/fetch-bun-release.py` | downloads the official Bun release and verifies checksum + OpenPGP signature |
| `scripts/summarize-ytdlp-runtimes.py` | renders this report's tables from the JSON artifact |

# ArchiveTune Linux: authenticated whole-application benchmark (Bun 1.4.3)

**Status:** complete (baseline + candidate)  
**Measured:** 2026-10-21  
**Safe JSON:** [`linux-bun-authenticated-full-application.json`](linux-bun-authenticated-full-application.json)

This run compares the legacy browser/BotGuard playback path against the
production yt-dlp resolver driven by **Bun 1.4.3** (the Rust rewrite) in the
real `:desktopApp` JVM, with a real restored YouTube session, a real Compose
window, real network stream resolution, and the real VLC playback controller.
Both arms use the identical disposable instrumentation, the identical `core`
gitlink, the same machine and the same three public videos.

| Arm | Commit | Core gitlink | Backend |
| --- | --- | --- | --- |
| Baseline | `0817a502413c6e77871eb161ee5c18ad6c4486da` (`0817a5024`) | `144fe5c33c9e2f1f8a6c8c84bb03a34c3c4d7b3c` | legacy desktop resolver + Chromium BotGuard |
| Candidate | `04232ffa7d2a9fc141789829975ab9d6183f371e` (`04232ffa7`) | `144fe5c33c9e2f1f8a6c8c84bb03a34c3c4d7b3c` | frozen yt-dlp `2026.08.19` + Bun `1.4.3` |

Both arms were built in separate detached checkouts under
`/tmp/opencode/archivetune-auth-bench-bun/`. The main checkout and the
`linux-port` branch were not modified. Only the harness, this report and the
safe JSON are committed; no production Kotlin or Gradle file is changed.

The historical quickjs-ng comparison is preserved unchanged in
[`linux-authenticated-full-application.json`](linux-authenticated-full-application.json)
and its Markdown companion.

## Resolver selection

The candidate arm publishes exactly one JavaScript runtime to the application:

| Item | Value |
| --- | --- |
| Engine | `bun` |
| yt-dlp | `2026.08.19` (`/home/mint/.t3/worktrees/ArchiveTuneLinux/t3-e22b0ea4/.tools/resolver/yt-dlp`) |
| Bun | `1.4.3` (`/home/mint/.t3/worktrees/ArchiveTuneLinux/t3-e22b0ea4/.tools/resolver/bun`) |
| Bun sha256 | `7ce7d6b654eddeec20afddf2396317fba6dc19ab52788a27e894e1806bf8f24b` |
| Published through | `ARCHIVETUNE_BUN` and `-Darchivetune.bun` |
| Removed from the child environment | `ARCHIVETUNE_QUICKJS` |

`ARCHIVETUNE_QUICKJS` is removed so a stale system-wide runtime can never be
discovered instead of the verified binary, and `BUN_OPTIONS`, `DO_NOT_TRACK`
and `NODE_OPTIONS` are stripped from the benchmarked process. The version
assertion is fail-closed: the run aborts unless Bun reports `1.4.3`.

The old quickjs mode is unchanged and still supported: `--quickjs`,
`ARCHIVETUNE_QUICKJS` and `archivetune.quickjs` behave exactly as before.

## Session isolation

The live session entry was only ever read. Each arm received a disposable
Secret Service entry keyed by that run's isolated
`$ARCHIVETUNE_HOME/data/youtube-session.dpapi` path; the payload was passed on
stdin, and the clone was cleared after the run. Token refreshes therefore went
to the clone, not the live entry.

| Check | Result |
| --- | --- |
| Source Secret Service entry present | yes |
| Source codec version | v4 |
| Source payload unchanged before/after | **yes** |
| Session writes confined to disposable clone | yes |
| Live browser profile used | no |
| Keyring clone cleared after run | yes |
| Secret values in report | no |

## Instrumentation parity

Both arms received the **same** temporary `Main.kt` hook, so the instrumented
file hashes match exactly:

| Arm | Instrumented `Main.kt` sha256 |
| --- | --- |
| Baseline | `87bd86847190ec96e9eb27836c46a473f5a7ab240f1e208a4787bb8403b4a00e` |
| Candidate | `87bd86847190ec96e9eb27836c46a473f5a7ab240f1e208a4787bb8403b4a00e` |

The delegated session provider hides the PO-token bindings in both arms, so a
mint through the real headless Chromium provider is possible whenever the
production code asks for one. The harness does not force and does not suppress
any helper: the Browser counts below are what the production code actually
started.

## Authentication and Browser proof

| Check | Baseline | Candidate |
| --- | --- | --- |
| Login cookie restored | yes | yes |
| DataSync restored | yes | yes |
| Account request verified | yes | yes |
| Cold headless-Browser mint | 1/1 (688 ms) | 1/1 (628 ms) |
| Browser teardown observed | yes | yes |
| Application-side mints during extraction | 6 | **0** |
| Browser processes observed | `brave` | `brave` |

The candidate's cold Browser proof is the control measurement. During the
production extraction the candidate started **no** Browser process: the yt-dlp
path never asks the account manager for a PO token, so the delegated provider
is simply unused. The baseline, in contrast, minted six times and therefore
carries nine Chromium processes through its extraction phase.

## Extraction results

Six requests per arm (three public video IDs, two sequential repeats each).

| Metric | Baseline | Candidate |
| --- | --- | --- |
| Attempts / successes | 6 / 6 | 6 / 6 |
| Failures | 0 | 0 |
| Median latency | 395 ms | 2939 ms |
| Min / max latency | 129 ms / 1087 ms | 2747 ms / 3863 ms |

The candidate resolves every request through yt-dlp with Bun 1.4.3 as the
challenge runtime. It is markedly slower than the legacy in-app path, and it
is the slower path that removes the Browser from the extraction phase.

## Playback controller

A real resolved remote stream was loaded into the real `VlcjPlaybackController`
at volume 0.

| Check | Baseline | Candidate |
| --- | --- | --- |
| Started / progressed | yes | yes |
| Seeked | yes | yes |
| Paused | yes | yes |
| Resumed | yes | yes |
| Stopped | yes | yes |

All controller checks passed in both arms, including the real seek, pause and
resume round trip.

## Wall time and memory

Memory is the maximum of **simultaneous** process-tree RSS/PSS at one sampled
instant, never a sum of per-process maxima.

| Phase | Baseline RSS max | Candidate RSS max | Baseline PSS max | Candidate PSS max |
| --- | --- | --- | --- | --- |
| Startup | 340.28 MiB | 365.09 MiB | 309.05 MiB | 329.53 MiB |
| Idle | 404.48 MiB | 402.21 MiB | 372.90 MiB | 366.31 MiB |
| Account verification | 409.61 MiB | 403.09 MiB | 372.93 MiB | 367.26 MiB |
| Cold Browser proof | 1267.49 MiB | 1259.33 MiB | 600.77 MiB | 586.64 MiB |
| **Extraction** | **1281.14 MiB** | **826.13 MiB** | 610.82 MiB | **747.01 MiB** |
| Playback | 450.99 MiB | 426.69 MiB | 419.29 MiB | 390.31 MiB |
| Post idle | 450.99 MiB | 426.82 MiB | 419.08 MiB | 390.86 MiB |
| Shutdown | 443.22 MiB | 412.96 MiB | 414.10 MiB | 382.02 MiB |

The candidate's extraction phase is 455 MiB lower in simultaneous RSS than the
baseline, because the nine Chromium helpers are gone. Its simultaneous PSS is
136 MiB higher, because Bun's 310 MiB resident set is largely private (260 MiB
PSS) while Chromium's 857 MiB is largely shared (218 MiB PSS).

### Helper accounting (simultaneous, per category)

| Category | Arm | Max simultaneous processes | Max simultaneous RSS | Max simultaneous PSS |
| --- | --- | --- | --- | --- |
| Browser | Baseline | 9 | 857.40 MiB | 217.55 MiB |
| Browser | Candidate | 9 | 851.67 MiB | 214.97 MiB |
| Bun | Candidate | 1 | 310.20 MiB | 260.34 MiB |
| yt-dlp (bootloader + embedded CPython) | Candidate | 2 | 116.95 MiB | 120.02 MiB |
| Media (`ffmpeg`, `ffprobe`) | Candidate | 1 | 14.11 MiB | 3.03 MiB |

The yt-dlp category holds **two** simultaneous processes with the same
executable name: the PyInstaller bootloader and its embedded CPython
interpreter. Both are counted and both are inside the simultaneous totals.
`ffmpeg`/`ffprobe` sidecars are counted as media.

### CPU (cumulative `utime+stime`, exited helpers retained)

| Metric | Baseline | Candidate |
| --- | --- | --- |
| Total tracked CPU | 13.39 s | 25.88 s |
| Application JVM CPU | 11.62 s | 13.50 s |

The CPU tracker keeps the maximum observed `utime+stime` per
`(pid, starttime)` and retains processes after they leave `/proc`, so helper
CPU is not lost when a short-lived process exits between samples.

## Helper lifecycle

| Check | Baseline | Candidate |
| --- | --- | --- |
| Browser processes in post idle | 0 | 0 |
| Browser processes after shutdown | none | none |
| Any helper in post idle | no | no |
| Any helper in shutdown phase | no | no |
| Any helper after shutdown | no | no |
| Final cleanup process names | none | none |

## What this run does and does not prove

* The signed-in **application state** is verified with a real account
  request, and the resolved streams are **public** videos.
* Cookie-only, age- or region-restricted media is **unverified**: the legacy
  VISIONOS player client does not send the login cookie, and no such stream
  was exercised in this run.
* The candidate's production extraction contains no Browser process because the
  production yt-dlp path does not request a PO token. That is the observed
  behaviour of the candidate, not a harness setting.

## Limitations

* One run per version; three public video IDs and two sequential repeats each.
* PSS and CPU are sampled estimates; short-lived helpers can exit between
  reads, and final CPU ticks between samples can be missed.
* The source keyring entry is read twice only to prove it is unchanged; its
  hash is never stored or logged.
* Raw application logs stay in a private 0700 work root and are not part of
  the report or the commit.
* The host is shared with the user's desktop applications, which are outside
  the tracked session.
* Offscreen software Skia; GPU-backed rendering may differ.
* Playback runs muted at volume 0 and does not verify audible output on the
  live user's device.

## Reproduction

```bash
python3 scripts/benchmark-authenticated-application.py \
  --baseline-commit 0817a502413c6e77871eb161ee5c18ad6c4486da \
  --candidate-commit 04232ffa7d2a9fc141789829975ab9d6183f371e \
  --work-root /tmp/opencode/archivetune-auth-bench-bun \
  --ytdlp /home/mint/.t3/worktrees/ArchiveTuneLinux/t3-e22b0ea4/.tools/resolver/yt-dlp \
  --bun /home/mint/.t3/worktrees/ArchiveTuneLinux/t3-e22b0ea4/.tools/resolver/bun \
  --resolver-engine bun \
  --expect-bun-version 1.4.3 \
  --candidate-label bun-1.4.3 \
  --app-timeout 480 \
  --output packaging/resolver/validation/linux-bun-authenticated-full-application.json
```

The historical quickjs-ng command stays supported and unchanged:

```bash
python3 scripts/benchmark-authenticated-application.py \
  --baseline-commit 0817a502413c6e77871eb161ee5c18ad6c4486da \
  --candidate-commit d3755de5f88c9358a685fce7cea0863dd922990c \
  --work-root /tmp/opencode/archivetune-auth-bench-new-run \
  --ytdlp /path/to/frozen/yt-dlp \
  --quickjs /path/to/qjs \
  --app-timeout 480 \
  --output packaging/resolver/validation/linux-authenticated-full-application.json
```

The harness reads the live Secret Service entry read-only, clones it per run,
and refuses to run if the entry is absent. It is expected to leave the live
entry and the live browser profile untouched.

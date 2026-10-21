# ArchiveTune Linux: authenticated whole-application benchmark

**Status:** complete (baseline + candidate)  
**Measured:** 2026-10-21  
**Safe JSON:** [`linux-authenticated-full-application.json`](linux-authenticated-full-application.json)

This run compares the legacy browser/BotGuard playback path against the frozen
yt-dlp/QuickJS resolver in the real `:desktopApp` JVM, with a real restored
YouTube session, a real Compose window, real network stream resolution, and the
real VLC playback controller. Both arms use the same disposable instrumentation,
the same core commit, the same machine and the same three public videos.

| Arm | Commit | Core gitlink | Backend |
| --- | --- | --- | --- |
| Baseline | `0817a502413c6e77871eb161ee5c18ad6c4486da` (`0817a5024`) | `144fe5c33c9e2f1f8a6c8c84bb03a34c3c4d7b3c` | legacy desktop resolver + Chromium BotGuard |
| Candidate | `d3755de5f88c9358a685fce7cea0863dd922990c` (`d3755de5f`) | `144fe5c33c9e2f1f8a6c8c84bb03a34c3c4d7b3c` | frozen yt-dlp `2026.08.19` + QuickJS |

Both were built in separate detached checkouts under
`/tmp/opencode/archivetune-auth-bench/`. The candidate worktree
(`t3-933158a7`) and the main checkout were not modified. Only the new harness,
this report, and the safe JSON are committed; no production Kotlin or Gradle
file is changed.

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
| Anonymous runs accepted | no (guard fails closed) |

## Authentication and Browser proof

Both arms restored a signed-in session from secure storage with a login cookie,
visitor data, and DataSync. The test hook retained those values but hid every
PO-token binding from the backend, so the real account manager had to mint
again through its real Chromium provider.

| Check | Baseline | Candidate |
| --- | --- | --- |
| Signed in / restored from secure storage | yes / yes | yes / yes |
| `hasLoginCookie` | true | true |
| `hasVisitorData` | true | true |
| `hasDataSync` | true | true |
| Bound PO token present before stripping | true | true |
| Account request verified | true | true |
| Authenticated stream attempts | 6/6 | 6/6 |
| Cold Chromium proof (probe `BaW_jenozKc`) | 1/1 minted | 1/1 minted |
| Browser process observed | `brave`, 9 helpers | `brave`, 9 helpers |
| BotGuard browser teardown observed | yes | yes |
| Browser helpers in post-idle / shutdown | 0 / 0 | 0 / 0 |

The cold proof is deliberately separate from the extraction metrics. The
baseline's extraction phase additionally launches Chromium for each video that
is not already in the provider's one-entry cache; that is inherent to the
legacy path and is visible in the baseline extraction peak below.

## Extraction results

All six attempts in each arm succeeded with a real signed remote stream. There
were no failures, no anonymous fallbacks, and no unresolved placeholders.

| Arm | Min | Median | Max | Success |
| --- | ---: | ---: | ---: | ---: |
| Baseline | 142 ms | 491 ms | 944 ms | 6/6 |
| Candidate | 7,288 ms | 7,697 ms | 8,476 ms | 6/6 |

| Video | Iteration | Baseline client / itag / latency | Candidate client / itag / latency |
| --- | ---: | --- | --- |
| `jNQXAC9IVRw` | 0 | VISIONOS / 140 / 944 ms | YT_DLP / 251 / 8,476 ms |
| `jNQXAC9IVRw` | 1 | VISIONOS / 140 / 142 ms | YT_DLP / 251 / 7,603 ms |
| `dQw4w9WgXcQ` | 0 | VISIONOS / 251 / 796 ms | YT_DLP / 251 / 7,894 ms |
| `dQw4w9WgXcQ` | 1 | VISIONOS / 251 / 186 ms | YT_DLP / 251 / 7,791 ms |
| `aqz-KE-bpKQ` | 0 | VISIONOS / 251 / 848 ms | YT_DLP / 251 / 7,288 ms |
| `aqz-KE-bpKQ` | 1 | VISIONOS / 251 / 166 ms | YT_DLP / 251 / 7,558 ms |

Baseline uses one cold Chromium/BotGuard mint per video and then serves its
second attempt from the provider's token cache. Candidate performs a full
yt-dlp extraction on every attempt; it has no equivalent warm path in this
measurement. The baseline median therefore mixes sub-200 ms warm resolutions
with 0.8–0.9 s cold ones.

## Playback controller

The first authenticated stream was passed directly to
`musicServices.playback.replaceQueue(...)` as
`PlaybackSource.Remote(url, mime, headers, expiry)`. Volume was set to `0f` for
both arms. All checks were asserted from `PlaybackSnapshot`:

| Check | Baseline | Candidate |
| --- | --- | --- |
| Queue load / playback started (`READY`) | true | true |
| Position advanced | +1,350 ms | +1,281 ms |
| Seek to 5,000 ms | true | true |
| Pause (`playWhenReady=false`) | true | true |
| Resume (`isPlaying=true`) | true | true |
| Stop (`IDLE`, position 0) | true | true |

`playback_engine_available=true`, `vlc_runtime_not_found=false`,
`out_of_memory_error=false`, and `uncaught_exception=false` in both arms. The
run was muted by design, so audible output on the user's device was not
verified.

## CPU, wall time, and memory

| Metric | Baseline | Candidate |
| --- | ---: | ---: |
| Wall time | 28,590 ms | 72,144 ms |
| Total tree CPU | 12.14 s | 22.45 s |
| App JVM CPU | 12.14 s | 17.81 s |
| Child-process CPU | 0 s | 4.64 s |

Phase memory is the maximum simultaneous sampled sum; RSS and PSS are reported
separately because the process composition differs.

| Phase | Baseline duration | Baseline RSS / PSS | Candidate duration | Candidate RSS / PSS | Candidate helpers |
| --- | ---: | ---: | ---: | ---: | --- |
| Startup | 1,948 ms | 345 / 313 MiB | 1,942 ms | 375 / 338 MiB | JVM |
| Idle | 8,000 ms | 394 / 362 MiB | 8,000 ms | 408 / 371 MiB | JVM |
| Auth verify | 754 ms | 394 / 362 MiB | 701 ms | 408 / 371 MiB | JVM |
| Cold proof | 624 ms | 1,268 / 572 MiB | 684 ms | 1,302 / 592 MiB | 9 browser |
| Extraction | 3,086 ms | 1,310 / 615 MiB | 46,613 ms | 795 / 739 MiB | 2 yt-dlp, 1 qjs, 1 ffmpeg/ffprobe |
| Playback | 1,613 ms | 458 / 427 MiB | 1,609 ms | 434 / 397 MiB | JVM |
| Post-idle | 8,001 ms | 459 / 427 MiB | 8,000 ms | 434 / 397 MiB | JVM |
| Shutdown | 564 ms | 459 / 422 MiB | 595 ms | 422 / 390 MiB | JVM |

Interpretation:

* The legacy path is much faster once its BotGuard token is warm, but every
  cold video needs a short-lived headless Chromium. That shows up as a
  **1.27–1.30 GiB** cold/extraction tail and 9 browser helper processes.
* The yt-dlp path is consistently slow per extraction (7.3–8.5 s) and uses more
  total CPU and wall time, but it never needs a browser. Its extraction RSS is
  lower (795 vs 1,310 MiB), while its summed PSS is higher (739 vs 615 MiB)
  because the tracked set is a few long-lived processes with less shared
  Chromium memory, not thousands of shared pages.
* Playback and post-idle memory are slightly lower for the candidate
  (434 vs 458 MiB RSS).

## Helper lifecycle

All helpers are counted, including browser renderer/GPU/network processes
(`brave`, max 9), yt-dlp's two same-named processes (PyInstaller bootloader +
embedded CPython), `qjs`, and yt-dlp's `ffmpeg`/`ffprobe` sidecars.

| Arm | Observed helpers | Post-idle helpers | Shutdown-phase helpers | After shutdown |
| --- | --- | --- | --- | --- |
| Baseline | `brave` (max 9) | none | none | none |
| Candidate | `brave` (max 9, cold proof only), `yt-dlp` (max 2), `qjs` (max 1), `ffmpeg`/`ffprobe` (max 1) | none | none | none |

The final cleanup sample contained no helper processes (`[]`), and
`no_helpers_in_post_idle`, `no_helpers_in_shutdown_phase`, and
`no_helpers_after_shutdown` are all true for both arms.

## Limitations

* One run per arm, three videos, two sequential attempts per video.
* PSS and CPU are sampled estimates; short-lived helpers can exit between reads.
* The host is shared with the user's desktop applications; only the tracked app
  session/process group and observed descendants are counted.
* Offscreen Xvfb with software Skia; GPU-backed rendering may differ.
* Playback was muted at volume 0; audible output and the user's real audio
  device were not exercised.
* Baseline's extraction phase includes real Chromium mints; its cold proof is
  reported separately so the two costs are visible.
* No account name, email, cookie value, signed URL, or keyring bytes are stored
  in the report. Raw logs remain in a private 0700 work directory.

## Reproduction

```bash
python3 scripts/benchmark-authenticated-application.py \
  --baseline-dir /tmp/opencode/archivetune-auth-bench/baseline \
  --candidate-commit d3755de5f88c9358a685fce7cea0863dd922990c \
  --candidate-dir /tmp/opencode/archivetune-auth-bench/candidate \
  --work-root /tmp/opencode/archivetune-auth-bench \
  --ytdlp /path/to/frozen/yt-dlp \
  --quickjs /path/to/qjs \
  --app-timeout 480 \
  --output packaging/resolver/validation/linux-authenticated-full-application.json
```

The harness reads the live Secret Service entry read-only, clones it per run,
and refuses to run if the entry is absent. It is expected to leave the live
entry and the live browser profile untouched.

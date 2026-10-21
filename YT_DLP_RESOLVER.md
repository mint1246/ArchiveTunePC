# Experimental desktop yt-dlp + Bun resolver

Implemented on `feature/ytdlp-resolver` in its own worktree. No merge is performed.
The Linux porting checkout and its uncommitted changes remain untouched. Its committed
Linux support updates are now incorporated into this experimental branch.

## Application integration

`ArchiveTuneJvmClient.resolveAudioStream` defaults to `YtDlpDesktopStreamExtractor`.
Both playback (`DesktopMusicServices`) and downloads (`DesktopDownloadServices`) already
use this API, including the metadata replacement path. Its `ResolvedAudioStream`
contract is unchanged: direct URL, audio format, HTTP headers, expiry and session
fingerprint. Existing queue preloading, completed-download lookup, URL-cache session
invalidation, expiry safety window and libVLC callback transport continue to use it.

The resolver selects audio-only progressive HTTP(S), excludes HLS/DASH manifests and
muxed video, and maps LOW/HIGH/HIGHEST quality preferences to yt-dlp format selectors.
HIGH prefers audio at or below 160 kbit/s; LOW prefers at or below 70 kbit/s. Each falls
back to the best available direct audio if its preferred tier is absent. Unknown
container types, malformed headers, expired URLs, multiple selected formats and
mismatched video IDs are rejected. URLs without an `expire` parameter receive a
conservative five-minute lifetime.

The backend validates a small head range and, when size is available, a tail range
before handing the stream to playback. Retryable CDN rejections trigger one fresh
extraction that excludes the rejected format; transient probe I/O also gets one retry.
Probes follow media redirects, matching the playback transport. Probe errors are
network failures rather than unavailable-video failures. Downloads
reuse this validation instead of probing the same yt-dlp URL again.

A parallel, best-effort player metadata request supplies YouTube loudness metadata to
the existing normalization controls. It has a two-second limit and performs no stream
extraction or deciphering; audio extraction remains solely yt-dlp. Anonymous metadata
uses the native client first, while signed-in metadata prefers the cookie-capable web
client. A live check for `dQw4w9WgXcQ` returned perceptual loudness **−13.01 dB**
through both old and new paths, preserving the existing gain calculation. This probe now has an asynchronous cancellable
variant: cancelling a queue load also cancels the HTTP call. The original synchronous
probe API remains available to its existing callers. Probes, Googlevideo callback
playback and downloads now share the same dynamic application proxy, proxy credentials
and DNS routing policy. Desktop currently exposes no proxy settings, so this route
is DIRECT unless configured programmatically. Explicit routing also suppresses ambient
system proxy defaults, consistently with the subprocess's explicit proxy option.

The existing libVLC Googlevideo callback transport receives the complete required
header map. Cookie, Authorization, Proxy-Authorization and transport-controlled headers
are excluded from media requests. No extractor is embedded in the playback controller.

Explicitly supplying `audioStreamExtractor = null` retains the legacy backend for
controlled comparisons. Existing custom `StreamUrlResolver` injections also retain
their legacy behavior. Production defaults do not silently fall back to NewPipe. After a media HTTP failure,
the existing transparent retry starts a fresh extraction; yt-dlp client selection
does not enter the legacy client-key backoff/proof-refresh machinery.
NewPipe remains a transitive shared-core dependency for Android and the comparison
path; this experiment does not modify submodule source or remove unrelated core APIs.

## Runtime and authentication lifecycle

The pinned toolchain is yt-dlp **2026.08.19**, bundled **yt_dlp_ejs 0.8.0**, and
**Bun 1.4.3** (the Rust rewrite). Startup diagnostics are checked once per resolver instance for
all three components, with other JS runtimes disabled. A missing/broken toolchain fails
with a redaction-safe installation error. Upstream yt-dlp validates the solver scripts'
version and hashes when it actually solves challenges. Live challenge tests provide the
execution check; an installed package's version string alone is not that proof.

Each request launches yt-dlp with `ProcessBuilder` arguments, without a shell. Bun
is selected explicitly through `--no-js-runtimes --js-runtimes bun:<path>`.
The app requires Bun 1.4.3 or newer, so an old PATH installation cannot silently
select pre-rewrite Bun. The pinned yt-dlp warns that Bun newer than 1.3.14 is
unsupported, but executes 1.4.3 without a bypass. This is an experimentally verified
combination, not an upstream support guarantee. It uses bundled EJS, `--no-install`
and no remote components. Bun has no sandbox; the request's private directory
prevents project `.env`/`bunfig.toml` discovery, but does not isolate the process
from the user's permissions. Inherited `BUN_OPTIONS` is removed so it cannot override
the working directory or add preloads, and `DO_NOT_TRACK=1` disables Bun's automatic
crash-report uploads.
User configuration, plugins and remote solver downloads are disabled. Official frozen
binaries bundle their own transient Python interpreter and EJS; system Python, Node
and Deno are unnecessary. No Python or JS worker stays alive after a request.

The desktop extractor allows two attempts of 20 seconds each, including extraction,
metadata handoff and CDN probes, inside the existing 45-second backend budget.
A timed-out first attempt is cancelled and cleaned up before the fresh second attempt.
The remaining budget provides cleanup headroom; the caller's overall deadline still
wins if cleanup itself stalls. The standalone resolver retains its 35-second deadline. Cancellation, timeout, failure and success all
terminate/reap the tracked child process tree and remove request temporary files.
Linux uses a dedicated process group when `setsid` and `kill` are available, including
children whose parent exits before discovery. Other platforms track process handles
and await their termination; Windows Job Object containment is still unverified.
Stdout and stderr drain concurrently with 16 MiB / 1 MiB limits. Raw JSON, diagnostic
text, URLs and cookie values are never attached to exceptions or logs. The successful
capability check is the only process-related state retained in memory.

The existing session provider is consulted and refreshed as before. Its cookie header
is converted to a secure `.youtube.com` Netscape cookie jar in an owner-only temporary
directory (POSIX 0700; Windows user-temp inherited ACLs). Selected account `dataSyncId`
is passed with yt-dlp's existing `youtube:data_sync_id` option. Cookies, account context
and proxy credentials stay out of argv. Cookie-jar mutations by yt-dlp are discarded;
login storage, login UI and session refresh logic are unchanged. Request directories
hold a live-JVM file lock. Acquisition failures close the channel and delete the
request directory before any cookie is written. On startup, unlocked abandoned
directories from the same user are swept; directories newer than one minute and
active locks are retained to avoid racing a peer acquiring its lock. Unmarked
legacy directories are retained for 24 hours.
Cleanup failure has a redacted I/O error rather than an unavailable-video classification.
Signed-in QuickJS application playback was exercised with isolated copies of the existing
account session; the Bun whole-application run is pending. Cookie-only/restricted media
remains unverified.

Public signature-cache data may be stored under the application's supplied cache
folder, in `yt-dlp/`. Credentials, signed stream URLs and temporary EJS scripts do not
go there. Upstream 2026.08.19 disables its preprocessed-player disk cache, so this
standard cache does not eliminate the expensive `n` challenge path. No upstream
solver internals are patched to change that policy.

## Integrated Linux support

The committed Linux authentication/playback update `070ec6914` and AppImage update
`0817a5024` are incorporated, with core pinned to `144fe5c`. This brings Linux Secret
Service storage and Chromium discovery, system libVLC discovery and audio output,
and the locale fix that sends `hl=en` with `gl=IL` instead of invalid `hl=en-IL`.
The login implementation is inherited from those commits; the resolver experiment
does not replace it. The original `linux-port` worktree and branch are untouched.

The integrated resolver keeps its yt-dlp default, private cookies, cancellation,
20-second attempt budgets, shared HTTP routing and complete media headers. Packaging
uses `lib/app/resources/resolver` on Linux and `app/resources/resolver` on Windows.
The AppImage wrapper rejects missing or non-Linux resolver sidecars. Linux staging,
checksum verification, AppImage build/extraction and anonymous live callback playback
were verified in the isolated integration worktree. Authenticated whole-application
playback and the comparison against the committed Linux baseline also passed,
with muted output; the measurements below supersede the anonymous comparison.

## JavaScript runtime cost

The [runtime comparison](YT_DLP_RUNTIME_COMPARISON.md) measures the same pinned
yt-dlp/EJS with four supported engines, including mandatory challenge execution
and negative controls. In three challenged runs per engine, QuickJS-NG had a
7.541-second median versus Node's 2.988 seconds; total CPU medians were 5.97 and
1.79 seconds. Peak resolver-tree RSS was 359.0 MiB for QuickJS versus 407.3 MiB
for Node. These measurements exclude the application JVM.

QuickJS has the smallest binary (2.44 MiB versus Node's 52.78 MiB) and lowest
measured peak RAM, while Node reduces CPU and startup delay. Deno had similar
latency to Node in that initial comparison. No engine is cheapest across all
costs. Following the runtime comparison and the user's choice, this branch now ships
Bun 1.4.3 to reduce challenge-solving latency. QuickJS measurements below describe
the earlier candidate. The sample does not establish challenge
frequency, Windows performance, or the cost of a persistent worker.

The [Rust Bun follow-up](YT_DLP_RUST_BUN_COMPARISON.md) tested Bun 1.4.3 with
unchanged yt-dlp arguments: the unsupported-version message is a warning, and
challenge solving worked without a bypass. Across 18 successes per engine,
Bun 1.4.3 and 1.3.14 had similar wall medians (2806 / 2787 ms), while median
per-run peak RSS fell from 472.1 to 400.0 MiB. New Bun's CPU results split into
two groups; Node had the lowest pooled total CPU. This establishes a memory
improvement for this workload, not a general speedup from the Rust rewrite.

## Provisioning and packages

See [packaging/resolver/README.md](packaging/resolver/README.md) for pinned hashes,
licenses, update procedure and staging tasks. Both Windows x86_64 and Linux x86_64
sidecars are downloaded from official release assets and checksum-verified.

Discovery order is the `archivetune.ytdlp` / `archivetune.bun` system properties,
`ARCHIVETUNE_YTDLP` / `ARCHIVETUNE_BUN`, packaged `resolver/` directories, the local
`.tools/resolver/` development directory, then PATH. Packaged JVM resources use
`app/resources/resolver/` on Windows and `lib/app/resources/resolver/` on Linux; GraalVM native distributions carry `resolver/` alongside the
executable (or its containing `bin/` directory). Discovery uses process metadata and
filesystem APIs, not reflection or embedded scripting engines.

Linux JVM staging/verification and native sidecar staging were run. Windows sidecars
were cross-staged and verified on Linux, including their `.exe` names and hashes.
The normal JVM desktop application compiles with the integrated resolver. A full
Windows MSI/GraalVM native image was not built or executed on this Linux host.
The Windows-only native/installer tasks remain platform-gated. The integrated Linux
JVM image and AppImage use system libVLC and the host desktop dependencies. macOS/ARM sidecars are not packaged here.

Development setup using the staged Linux tools:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :desktopApp:verifyBundledResolverSidecars
export ARCHIVETUNE_YTDLP="$PWD/desktopApp/build/compose/binaries/main/app/ArchiveTune/lib/app/resources/resolver/yt-dlp"
export ARCHIVETUNE_BUN="$PWD/desktopApp/build/compose/binaries/main/app/ArchiveTune/lib/app/resources/resolver/bun"
```

## Bun package and playback verification

Runtime candidate `04232ffa7` includes the Bun switch, private working directory,
absolute temporary paths, inherited-option removal, and verified package sidecars.
The final Linux AppImage was built from that exact commit and extracted successfully:

- Artifact: `/tmp/opencode/appimage-bun-final/ArchiveTune-15.10-x86_64.AppImage`
- Size: 221,383,160 bytes (211.13 MiB).
- SHA-256: `25a2e57900897279e3f84306226f514e98d64f933ba0ca5981d5b3ee75b956fa`.
- Packaged tools: yt-dlp 2026.08.19 and Bun 1.4.3; no QuickJS files.
- Packaged EJS challenge smoke: Bun executed and solved the challenge without a
  version bypass; yt-dlp's unsupported-version warning was expected.

The Linux and Windows sidecar sets passed staging/hash verification; Windows was
cross-staged on Linux and was not executed. Graal native sidecar staging passed,
without a full native image or MSI build. The AppImage was not installed and did
not replace the running user application.

The integrated branch passed 26 resolver tests, 94 backend tests (one opt-in skip),
53 playback tests (one opt-in skip), and desktop compilation. The resolver tests
also passed with inherited `BUN_OPTIONS=--cwd=/nonexistent-parent-project` and
`DO_NOT_TRACK=0`: subprocess fixtures require the options to be removed and tracking
to be disabled. The real Bun/libVLC callback class passed both tests, including
ranged playback, advancing time, seek, pause and resume. Signed-in Bun whole-app
results are recorded separately when that run completes.

## Historical authenticated QuickJS application comparison

The [authenticated report](packaging/resolver/validation/linux-authenticated-full-application.md)
compares Linux baseline `0817a5024` with the integrated yt-dlp candidate `d3755de5f`;
both use core `144fe5c` and identical temporary instrumentation. The candidate's
production sources predate the Bun runtime switch. The current account was verified
through a real request. Each run restored an isolated copy of the saved keyring
entry; the original entry was unchanged, and both copies were removed afterward.

Both signed-in applications resolved **6/6** public streams and passed the real application
controller's playback, advancing position, seek, pause, resume and stop checks.
Output was muted, so audible playback was not verified. The legacy VISIONOS player
client does not send cookies; these public-video tests do not establish playback
of cookie-only/restricted content. The baseline really launched
headless Brave, including renderer/GPU/network helpers, during extraction. A separate
forced browser-proof control ran in both arms and is excluded from extraction costs.

| Measurement | Linux legacy | yt-dlp + QuickJS |
| --- | ---: | ---: |
| Resolution median | 491 ms | 7697 ms |
| First request per video, median | 848 ms | 7894 ms |
| Repeat request per video, median | 166 ms | 7603 ms |
| Extraction peak RSS | 1309.65 MiB | 794.54 MiB |
| Extraction peak PSS | 615.14 MiB | 738.68 MiB |
| Playback peak RSS | 458.28 MiB | 434.30 MiB |

The legacy backend's proof cache accelerates repeats. These calls bypass the
application's resolved-URL cache in both arms, so repeat timings are not repeated
playback latency. The candidate had lower RSS but higher PSS: RSS repeats shared
Chromium pages across processes, while PSS apportions them. This is not evidence
of a physical-memory improvement. CPU comparisons from this whole-app run are
excluded because its optional tracker lost exited helpers; the recorded totals
cannot be repaired, and the harness is fixed for future runs. The independent
runtime comparison uses separate CPU accounting.

No helper processes remained during post-idle or after shutdown. This is one paired
run on three public videos, with software rendering and the user's shared host.
The result supports a working experiment, not a demonstrated speed or physical-memory
advantage over the now-fixed Linux legacy implementation. No merge is performed.

## Anonymous application measurements (runtime candidate `5e5ecb786`)

The [full-application benchmark](YT_DLP_APPLICATION_BENCHMARK.md) compares the exact
legacy baseline with the integrated Compose desktop app in isolated profiles. It
counts the JVM and every observed app-created helper, including yt-dlp's embedded
CPython worker, QuickJS, ffmpeg/ffprobe, and detached resolver process groups.

The candidate resolved **6/6** requests; baseline **0/6**. Candidate idle / extraction
peak / post-extraction RSS were **319.01 / 654.47 / 310.54 MiB**, compared with
**314.20 / 319.75 / 319.88 MiB** for baseline. Extraction peak PSS was **599.13 MiB**.
Candidate median / maximum latency was **2.508 / 7.678 seconds**. Baseline failed
attempts cannot establish latency or memory for successful legacy extraction.
This is not a valid comparison of successful old and new playback: anonymous state
bypasses the legacy headless PoToken browser before minting, so its browser cost was
never measured. The historical authenticated comparison above supplies the successful Linux baseline
and confirms browser execution; it must be used instead of this failed baseline.

No browser was launched during these anonymous runs, and no tracked process remained
after shutdown. The temporary helpers account for most of the extraction peak;
post-extraction memory returns near idle. Full application playback, authentication
browser memory, and signed-in browsing were not measured. Those measurements predate the integrated Linux update and used the old Windows-only
VLC locator. The current branch has Linux VLC discovery; the installed system library
also passed independent live callback tests. Anonymous home/library requests returned HTTP 400. See the report for the
sampling limits, process breakdown, exact revisions, raw artifacts and repeat command.

## Initial resolver-only measurements (2026-10-21, candidate c5cb2e578)

The actual Kotlin `ArchiveTuneJvmClient` API was measured in separate Java 21 JVMs
(`-Xms32m -Xmx512m`), anonymous, HIGH quality, two sequential requests per video.
Both backends include their CDN validation. First requests include initialization;
second requests reuse their backend instance. The final run used the application's
new yt-dlp cache configuration and a fresh cache folder per video.

| Public video ID | Legacy cold / repeat | yt-dlp cold / repeat | yt-dlp peak tree RSS |
| --- | --- | --- | --- |
| `jNQXAC9IVRw` | unavailable / unavailable | 3250 / 1976 ms | 204.39 MiB |
| `dQw4w9WgXcQ` | 831 / 133 ms | 8930 / 7589 ms | 467.74 MiB |
| `aqz-KE-bpKQ` | unavailable / unavailable | 3327 / 1837 ms | 204.95 MiB |

Reliability in this small sample: **yt-dlp 6/6**, **legacy 2/6**. New latency range:
**1.837–8.930 seconds**, median **3.289 seconds**. Legacy successful requests were
faster. This is a reliability improvement with higher extraction cost, not a general
speed or memory improvement. Failing legacy calls took 0.759–1.552 seconds; failure
latency is not comparable to successful playback resolution.

Legacy peak JVM RSS ranged **141.71–170.52 MiB**. yt-dlp peak sidecar-tree RSS ranged
**85.05–360.04 MiB**; peak JVM RSS was **119.34–126.54 MiB**. The highest combined peak
was **467.74 MiB** on a challenge-solving video. The first uncached comparison had a
similar 7.596-second repeat and 492.72 MiB combined peak for that video. Disk caching
therefore did not provide a material improvement in this test. **All 12 backend calls
reported zero remaining descendant processes** after resolution.

RSS is the maximum observed sum of `/proc` resident pages for the measured JVM and its
children, sampled every 20 ms. Shared pages may be counted more than once, and spikes
between samples may be missed. Peak JVM and child values can occur at different times
and should not be added to reconstruct the combined maximum. Other Gradle daemons and
applications are excluded. This is a small public-video sample, not a reliability SLA.

The one-shot design is retained. There is no measured justification here for keeping a
Python or JavaScript worker resident. In particular, a resident Python worker would
not by itself remove the separate JavaScript challenge-solving cost. Future optimization should
measure that distinction rather than infer it from process startup overhead.

The following command runs the API harness against the current checkout and its
configured tools; checking out `c5cb2e578` is necessary to reproduce the historical
QuickJS candidate. Prefer the full-application harness for current resource totals,
including helpers that detach from their initial parent.

Run the resolver-only comparison:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :desktop-backend:writeResolverBenchmarkClasspath
python3 scripts/benchmark-desktop-resolver.py
```

The [committed Linux comparison](packaging/resolver/validation/linux-desktop-comparison.json)
contains safe metadata and measurements; new runs write reports under
`build/resolver-validation/`. The Python scripts are developer tools and are not shipped with the app.

## Verification and remaining acceptance work

Resolver tests exercise format/metadata mapping, HTTP header handling, quality policy,
expiry rejection, runtime/EJS detection, cookie conversion, private request options,
concurrent requests, output limits, errors, deadlines, process descendants and temporary
credential cleanup. Backend tests exercise the existing return contract, refreshed
session snapshots, failure classification, proxy encoding, exact head/tail probe
headers and cancellable probes. POSIX process fixtures explicitly skip on non-POSIX
hosts; JSON/policy/backend contract tests remain portable.

The resolver suite passes 26 tests (19 new process/policy tests plus 7 shared-policy
tests); the integrated backend suite passes 94 tests (one opt-in skip). Targeted caller tests for queue preloading,
cache-session changes, completed-download preference, download attempts and queue
persistence pass 24 tests; the download manager suite passes 14. The existing
playback-core suite passed 22 tests. After integrating the Linux fixes, the
`desktop-auth` suite passed 97 tests (three opt-in skips), core passed 15 tests,
and desktop-playback passed 53 tests (one opt-in skip). Running its opt-in live
callback class separately passed both tests, including real playback, deep seek,
pause/resume and header delivery; see [the test notes](docs/YTDLP_VLC_TEST.md).
The authenticated whole-application benchmark also passed both arms' playback
checks, as described above; it uses isolated copies of the stored account.

Independent final live tests also passed for `jNQXAC9IVRw`, `dQw4w9WgXcQ` and
`aqz-KE-bpKQ`: all three returned head/tail HTTP 206, reached Playing in system
libVLC with advancing time, and decoded 240,000 non-silent PCM frames. One run
explicitly reported a QuickJS challenge and the bundled EJS source.

Merge remains deferred until the application is exercised on its supported desktop
runtime: local playback, resolved YouTube playback, seek/pause/resume, next-track
preloading/cancellation, downloads and signed-in cookie authentication. Windows/GraalVM
execution remains unverified. Authenticated full-application playback passed with
muted output and isolated session copies; audible output, packaged signed-in UI flows,
live queue preloading/cancellation and live downloads remain separate acceptance checks.

Public sources: [yt-dlp EJS setup](https://github.com/yt-dlp/yt-dlp/wiki/EJS),
[yt-dlp 2026.08.19](https://github.com/yt-dlp/yt-dlp/releases/tag/2026.08.19),
[Bun 1.4.3](https://github.com/oven-sh/bun/releases/tag/bun-v1.4.3),
[yt-dlp Bun invocation](https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/yt_dlp/extractor/youtube/jsc/_builtin/bun.py),
[upstream EJS cache policy](https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/yt_dlp/extractor/youtube/jsc/_builtin/ejs.py),
[upstream selected-account option](https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/yt_dlp/extractor/youtube/_base.py).

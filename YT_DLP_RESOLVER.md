# Experimental desktop yt-dlp + quickjs-ng resolver

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
**quickjs-ng 0.17.0**. Startup diagnostics are checked once per resolver instance for
all three components, with other JS runtimes disabled. A missing/broken toolchain fails
with a redaction-safe installation error. Upstream yt-dlp validates the solver scripts'
version and hashes when it actually solves challenges. Live challenge tests provide the
execution check; an installed package's version string alone is not that proof.

Each request launches yt-dlp with `ProcessBuilder` arguments, without a shell. QuickJS
is selected explicitly through `--no-js-runtimes --js-runtimes quickjs:<path>`.
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
Cleanup failure has a redacted I/O error rather than an unavailable-video classification. Signed-in live tests
were not run: no existing account session was accessed.

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
testing is being measured separately against the committed Linux baseline.

## JavaScript runtime cost

The [runtime comparison](YT_DLP_RUNTIME_COMPARISON.md) measures the same pinned
yt-dlp/EJS with four supported engines, including mandatory challenge execution
and negative controls. In three challenged runs per engine, QuickJS-NG had a
7.541-second median versus Node's 2.988 seconds; total CPU medians were 5.97 and
1.79 seconds. Peak resolver-tree RSS was 359.0 MiB for QuickJS versus 407.3 MiB
for Node. These measurements exclude the application JVM.

QuickJS has the smallest binary (2.44 MiB versus Node's 52.78 MiB) and lowest
measured peak RAM, while Node reduces CPU and startup delay. Deno had similar
latency to Node; Bun is deprecated upstream. No engine is cheapest across all
costs. This branch retains the requested QuickJS-NG default; changing it needs
an explicit packaging and product choice. The sample does not establish challenge
frequency, Windows performance, or the cost of a persistent worker.

## Provisioning and packages

See [packaging/resolver/README.md](packaging/resolver/README.md) for pinned hashes,
licenses, update procedure and staging tasks. Both Windows x86_64 and Linux x86_64
sidecars are downloaded from official release assets and checksum-verified.

Discovery order is the `archivetune.ytdlp` / `archivetune.quickjs` system properties,
`ARCHIVETUNE_YTDLP` / `ARCHIVETUNE_QUICKJS`, packaged `resolver/` directories, the local
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
export ARCHIVETUNE_QUICKJS="$PWD/desktopApp/build/compose/binaries/main/app/ArchiveTune/lib/app/resources/resolver/qjs"
```

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
never measured. A successful legacy comparison with equivalent authentication and
confirmed browser execution remains open.

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
not by itself remove the separate QuickJS solver cost. Future optimization should
measure that distinction rather than infer it from process startup overhead.

Reproduce the initial resolver-only comparison:

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
These integration tests are distinct from the authenticated whole-application
benchmark; current results from that benchmark will be recorded separately.

Independent final live tests also passed for `jNQXAC9IVRw`, `dQw4w9WgXcQ` and
`aqz-KE-bpKQ`: all three returned head/tail HTTP 206, reached Playing in system
libVLC with advancing time, and decoded 240,000 non-silent PCM frames. One run
explicitly reported a QuickJS challenge and the bundled EJS source.

Merge remains deferred until the application is exercised on its supported desktop
runtime: local playback, resolved YouTube playback, seek/pause/resume, next-track
preloading/cancellation, downloads and signed-in cookie authentication. Windows/GraalVM
execution remains unverified. Authenticated full-application playback is being checked
with isolated copies of the user's stored session, without modifying the live app.

Public sources: [yt-dlp EJS setup](https://github.com/yt-dlp/yt-dlp/wiki/EJS),
[yt-dlp 2026.08.19](https://github.com/yt-dlp/yt-dlp/releases/tag/2026.08.19),
[QuickJS-NG v0.17.0](https://github.com/quickjs-ng/quickjs/releases/tag/v0.17.0),
[upstream EJS cache policy](https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/yt_dlp/extractor/youtube/jsc/_builtin/ejs.py),
[upstream selected-account option](https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/yt_dlp/extractor/youtube/_base.py).

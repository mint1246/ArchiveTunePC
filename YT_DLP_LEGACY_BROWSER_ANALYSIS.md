# Why legacy extraction starts nine Chromium helpers

**Status:** initial causal and instrumentation analysis. No new live browser run was
performed; live confirmation is deliberately held until the parent gives GO so it
cannot overlap yt-dlp profiling.

**Scope:** read-only analysis of the committed benchmark, the desktop auth/backend
code, and the safe authenticated report. No production Kotlin, Gradle, login flow,
challenge code, user application, user profile, or session was modified. Raw run
logs and run roots stay private; this document contains no signed URL, cookie,
token, account, profile path, or argv value.

**Safe machine-readable companion:**
[`linux-legacy-browser-analysis.json`](packaging/resolver/validation/linux-legacy-browser-analysis.json)

## Short answer

Nine helpers are **one Chromium invocation**, not nine launches. The legacy
signed-in resolver performs an eager per-video BotGuard mint; each cache miss
starts one headless Brave/Chromium instance, and Chromium's normal multi-process
model fans that one instance out to nine OS processes. All nine share the
executable basename `brave`, which is why the benchmark's name-only inventory
collapses them to one name and only the simultaneous count reveals the fan-out.

The baseline extraction made **three** such invocations for three unique videos,
not six. Six `ensurePlaybackAuth` calls returned a token, but three of them were
single-entry provider-cache hits (0 ms) and three were cold mints
(486 ms, 477 ms, 513 ms). One invocation is therefore nine processes; three
sequential invocations produce the same nine-process peak because they never
overlap. Including the forced cold-proof control, the whole baseline run started
four browser trees, not six or nine.

Those browser processes are **transient, not persistent and not unrelated**.
They exist only during each mint and are gone before post-idle. They are also
**not required by the observed stream resolution**: all six baseline requests
succeeded through the `VISIONOS 0.1` client, and the Bun/yt-dlp candidate
resolved the same six public videos with zero browser processes. The browser is
required only by the current eager Web-po-token policy, not by extraction,
playback, login, or the VLC controller.

## Evidence

The authenticated report is the source of truth for counts and phases:
[`linux-bun-authenticated-full-application.md`](packaging/resolver/validation/linux-bun-authenticated-full-application.md)
and its safe JSON. The private baseline `application.log` was read only for the
`ARCHIVETUNE_APP_BENCHMARK` marker lines; no raw line is reproduced.

| Fact | Baseline | Candidate |
| --- | --- | --- |
| Instrumented `Main.kt` sha256 | `87bd8684…a00e` | identical |
| Resolve attempts / successes | 6 / 6 | 6 / 6 |
| Playback-auth calls returning a token | 6 | 0 |
| Non-zero mint calls in extraction | 3 | 0 |
| Inferred Chromium invocations in extraction | 3 | 0 |
| Max simultaneous browser processes in extraction | 9 | **0** |
| Max simultaneous browser processes in forced cold proof | 9 | 9 |
| Successful client | `VISIONOS 0.1` | `YT_DLP` |
| Browser processes in post-idle / after shutdown | 0 / none | 0 / none |

The extraction resolve timings show the same three misses:

| Video | Iteration | Resolve ms | Delegated mint ms |
| --- | --- | ---: | ---: |
| `jNQXAC9IVRw` | 0 | 934 | 486 |
| `jNQXAC9IVRw` | 1 | 141 | 0 |
| `dQw4w9WgXcQ` | 0 | 628 | 477 |
| `dQw4w9WgXcQ` | 1 | 129 | 0 |
| `aqz-KE-bpKQ` | 0 | 1087 | 513 |
| `aqz-KE-bpKQ` | 1 | 162 | 0 |

The forced cold-proof control is a different video (`BaW_jenozKc`) and took
688 ms. That control also explains why the candidate's whole-run browser union
contains `brave` even though candidate extraction had no browser at all.

## Causal call chain

### 1. Legacy resolution decides to prepare proof before it knows the winner

`ArchiveTuneJvmClient.resolveAudioStream()` calls `prepareProvidedSession()`, then
tries an optional `audioStreamExtractor`; the baseline has none, so it enters
`resolveAudioStreamOrThrow()`
(`desktop-backend/src/main/kotlin/moe/rukamori/archivetune/innertube/ArchiveTuneJvmClient.kt:662-681`,
`:741-757`):

```text
ensureSessionAuthContext(...)
preparePlaybackAuth(videoId, authState)   // eager, before client selection
resolveSignatureTimestamp(...)
orderedStreamClients(...)
resolveStreamClientWave(...)
```

`preparePlaybackAuth()` wraps the session provider in a 15 s timeout
(`:783-794`). The provider contract is in `ArchiveTuneJvmClient.kt:1213-1245`;
the desktop implementation is `DesktopSessionManager`.

The client order is decided later (`PlaybackStreamResolver.orderClients()`,
`stream-resolver/src/main/kotlin/moe/rukamori/archivetune/stream/PlaybackStreamResolver.kt:76-100`):
signed-in playback prefers `WEB_REMIX`, then the native fallback list starting
with `VISIONOS`. The proof is prepared for the preference, but the wave can still
be won by a client that never uses Web proof — which is what happened in all six
baseline rows.

### 2. The account manager gates the browser on signed-in playback context

`DesktopSessionManager.ensurePlaybackAuth()`
(`desktop-auth/src/main/kotlin/moe/rukamori/archivetune/desktopauth/DesktopSessionManager.kt:395-425`):

```text
if (!initial.hasPlaybackLoginContext || videoId.isBlank()) return initial
if (webClientPoTokenEnabled && poTokenGvs != null && poTokenGvsVideoId == videoId) return initial
sessionId = initial.sessionId ?: return initial
minted = poTokenProvider.mint(videoId, sessionId) ?: return initial
return initial.copy(poTokenGvs = playerToken, poTokenGvsSession = sessionToken, ...)
```

The first guard is why the anonymous baseline (`8e4`) never opened a browser:
without a login cookie and DataSync/channel context there is no
`hasPlaybackLoginContext`, so the method returns before the provider. The
restored signed-in baseline satisfies it, so the second guard decides between a
warm fastpath and a mint.

### 3. The provider mints one token pair through one throwaway browser

`ChromiumBotGuardPoTokenProvider.mint()`
(`desktop-auth/src/main/kotlin/moe/rukamori/archivetune/desktopauth/DesktopBotGuardPoTokenProvider.kt:58-104`):

- a mutex serializes mints;
- a single-entry cache keyed by `(videoId, sessionId)` is checked first
  (`:71-77`);
- on a miss it runs `mintOneShot()` under a 45 s provider budget;
- a successful mint is stored in that single cache entry.

`mintOneShot()` (`:106-202`) does exactly one `ProcessBuilder` start:

```text
profile = Files.createTempDirectory(tempRoot, "archivetune-potoken-")   // 0700 on Linux
ProcessBuilder(botGuardBrowserCommand(browser, profile)).start()
waitForDevTools(profile) -> waitForPageTarget(port)
Page.setDocumentContent(/po_token.html)
Runtime.evaluate(runBotGuard(challenge))
POST GenerateIT
Runtime.evaluate(createPoTokenMinter)
mint session token, mint player token
finally: close CDP, stopManagedBrowser(process, profile), delete profile
```

The command (`:444-474`) is a normal headless Chromium launch, not a
single-process one:

```text
brave --headless=new
      --user-data-dir=<archivetune-potoken-*>
      --profile-directory=Default
      --remote-debugging-port=0
      --remote-debugging-address=127.0.0.1
      --disable-sync --disable-background-mode
      --no-first-run --no-default-browser-check
      --disable-features=EnableBoundSessionCredentials
      --disable-dev-shm-usage --disable-gpu --password-store=basic
      --user-agent=<fixed BotGuard UA>
      about:blank
```

`--disable-gpu` removes the GPU process but does not collapse the rest of
Chromium's process model. `--single-process` and `--no-zygote` are absent.

### 4. Cleanup kills the whole lineage

`stopManagedBrowser()` (`:347-386`) combines:

- the root process and its `descendants()`;
- on Linux, every live process whose exact `--user-data-dir=<profile>` argument
  matches the throwaway profile (to catch reparented helpers);
- `destroy()` (SIGTERM), a 2 s grace, then `destroyForcibly()` (SIGKILL);
- profile deletion in up to 20 × 100 ms attempts (`:388-399`).

The report's zero browser processes in post-idle and after shutdown confirm the
cleanup in this run. The Linux profile scan is a backstop; the primary reason
the accounting is correct is the benchmark's session/process-group tracking,
described below.

## Why six calls did not mean six browser launches

Three separate effects are easy to conflate:

1. **Six `ensurePlaybackAuth` calls.** The delegated wrapper emits one event per
   resolution attempt, including cache hits.
2. **Three cache misses / browser launches.** The provider cache is a single
   entry keyed by video and session. The sequence is A, A, B, B, C, C, so the
   second A/B/C each hit the cache at 0 ms. The non-zero 486/477/513 ms values
   are the three misses, and each miss performs exactly one `ProcessBuilder`
   start.
3. **Nine simultaneous processes.** One Chromium invocation fans out to nine
   processes. Because the three mints are sequential (the harness awaits each
   resolve and the provider serializes with a mutex), the peak stays at nine
   rather than 27.

So the correct reading is **3 invocations × 9 processes, sequential**, not
6 launches, not 9 launches, and not 54 processes.

The forced cold-proof control adds one more invocation before extraction. It is
a different video, so it does not warm the per-video token cache. It does warm
the browser binary and OS page cache: the first mint took 688 ms, while the
extraction mints took 486–513 ms. Extraction timings therefore slightly
understate a true first-use mint on this host.

## Why one invocation has nine processes

A Chromium browser is a process tree, not a process. On Linux the stable roles
are the browser root, one or more zygotes, renderers, out-of-process utility
services (network, storage, audio, data decoder, etc.), an optional GPU process,
and crashpad. The project's own command disables GPU on Linux, so the expected
tree is roughly:

| Role | Expected | How it is identified |
| --- | --- | --- |
| `browser` | 1 | no `--type=` on the root |
| `zygote` | 1–2 | `--type=zygote`, optionally `--no-zygote-sandbox` |
| `renderer` | 1–2 | `--type=renderer` (the `about:blank` page and possibly a spare) |
| `utility:network` | 1 | `--type=utility --utility-sub-type=network.mojom.NetworkService` |
| `utility:storage` | 0–1 | `--type=utility --utility-sub-type=storage.mojom.StorageService` |
| `crashpad` | 0–1 | executable basename `chrome_crashpad_handler` |
| `gpu` | 0 | `--disable-gpu` is passed |

Nine is within the normal range for this shape. The benchmark's nine is the
**browser-category total, root included**; if the root is counted separately,
that is eight child helpers plus the browser. The exact root/helper split is
pending role tracing. The benchmark **cannot** confirm the mix:
`scripts/benchmark-application.py:577-590` resolves `/proc/<pid>/exe` and keeps
only the basename, so every helper becomes `brave`; `classify_process()`
(`:522-538`) assigns the category from name tokens and never reads `--type`.
The report's `process_names: ["brave"]` is therefore expected and is not role
evidence.

On this host `/usr/bin/brave` is a small wrapper that `exec`s
`/opt/brave-bin/brave`; Chromium's crashpad binary is
`/opt/brave-bin/chrome_crashpad_handler`, so a crashpad process would have a
distinct basename. The benchmark's name set contains only `brave`, which means
no crashpad process was alive at its samples; the tracer will record whether it
appears earlier in the launch window.

Evidence that goes beyond the name count:

- the peak is exactly nine in three independent situations (baseline cold
  proof, baseline extraction, candidate cold proof);
- the total process count at those instants is ten (one JVM + nine browser
  processes), and the extraction distribution is median 1, p95 10, max 10 —
  i.e. the browser tree is present only for a short window, not persistent;
- three non-zero mint durations independently prove three launches;
- no browser process is detached from the app session/process group;
- no browser process appears in startup, idle, auth, playback, post-idle, or
  shutdown.

What is still a hypothesis is the **per-role composition**. That is exactly what
the tracer in `scripts/trace-legacy-browser.py` resolves, using only
`--type`, `--utility-sub-type`, and the crashpad executable name.

## Required, transient, persistent, unrelated

| Question | Answer |
| --- | --- |
| Required for legacy extraction? | Not for the observed resolution. All six requests succeeded through `VISIONOS 0.1`, which does not use Web proof. The mint is required only by the current eager Web-proof policy. |
| Required for Web clients? | Conditionally. `WEB_REMIX` is preferred when signed in and may need the per-video GVS proof; this run does not prove it needed the mint, because it lost the race to `VISIONOS`. |
| Required for login? | No. Login uses the separate managed interactive profile (`ManagedChromiumLogin`), not the BotGuard provider. |
| Required for playback? | No. The VLC controller played an already-resolved stream; the browser is gone before playback. |
| Transient? | Yes. One invocation lasts roughly the mint window (~0.5–0.7 s here) plus teardown; it does not survive the phase. |
| Persistent? | No process persists. The throwaway profile is deleted. The minted token persists in memory and, for the last video, in the secure store. |
| Unrelated user Brave? | No. The user's browser lives in the desktop login session; the benchmark app is a new session and the tracer additionally requires the `archivetune-potoken-` profile prefix and browser-lineage descent. |

The candidate arm is the clean control for “unrelated”: it has zero browser
processes in production extraction, and its only browser tree is the deliberate
cold-proof control that the harness requests directly, outside the extraction
measurement.

## Account/token cache semantics

The browser cost is a cache-miss cost, not a per-request cost.

- **Provider cache:** one entry, keyed by `(videoId, sessionId)`, TTL from the
  GenerateIT lifetime minus a 10-minute safety margin
  (`DesktopBotGuardPoTokenProvider.kt:71-97`, `:401-406`, `:435-436`).
- **Desktop fastpath:** if `webClientPoTokenEnabled`, `poTokenGvs` is non-blank,
  and `poTokenGvsVideoId == videoId`, no mint
  (`DesktopSessionManager.kt:401-406`).
- **Persisted token:** secure-store v4 persists the last bound token and its
  video id. The stored session has an expiry for session refresh, but the
  PO-token binding has no separate expiry and the fastpath does not check one
  (`SecureSessionStore.kt:261-316`). A restored token can satisfy the fastpath,
  but only for that last video.
- **Cross-video session token:** `PlaybackAuthState.resolveGvsPoToken()`
  (`core: src/main/kotlin/moe/rukamori/archivetune/innertube/PlaybackAuthState.kt:96-120`)
  falls back to `poTokenGvsSession` for non-`WEB_CREATOR` clients that use Web
  proof (`WEB`, `WEB_REMIX`, `MWEB`). Native clients such as `VISIONOS` do not
  use Web proof at all. `DesktopSessionManager` nevertheless mints for every new
  video because its early return requires the video-bound token.
- **Single-entry thrash:** A, B, A causes three mints. The benchmark's A, A, B,
  B, C, C pattern hides this.
- **Forced cold wrapper:** the authenticated harness replaces the real provider
  with a view that nulls every token binding and sets
  `webClientPoTokenEnabled = false` on every `currentAuthState()`
  (`scripts/benchmark-authenticated-application.py:147-181`). The restored
  session had a bound token before stripping, but neither arm can use it.

This is the key distinction the parent asked for: **normal production warm-token
caching** may skip a mint when the requested video already has a bound token;
the **forced cold bindings wrapper** guarantees a mint for each unique video.
The benchmark measures the latter.

## Instrumentation bias and accidental inclusion

The existing benchmark does **not** accidentally include the user's unrelated
Brave. It also does not inflate six calls into six launches. It does inflate
legacy browser usage in one specific, important way.

| Bias | Direction | Severity | Why |
| --- | --- | --- | --- |
| Delegated provider strips all bindings on every read | Inflates cold mints | High | A warm persisted token for the same video cannot satisfy the fastpath. |
| Provider single-entry cache still works | Prevents over-counting launches | High | 6 calls → 3 launches, not 6. |
| Cold proof runs before extraction | Understates first-use mint latency | Medium | 688 ms cold proof vs 486–513 ms warmed extraction mints. |
| Candidate gets the same forced cold proof | Adds a non-production browser tree to candidate whole-run stats | High | Candidate extraction is 0; the 9 belongs to the control. |
| `browser_processes_observed` is a whole-run name union | Can be misread as phase cost | Medium | Candidate reports `brave` despite zero extraction browsers. |
| Cold-proof teardown check is cmdline-based | Could bleed a helper into extraction | Low | Teardown-settled and extraction-begin share the same marker millisecond; three non-zero mints prove new launches; p95 10 / median 1 shows transient presence. |
| Name-only process accounting | Hides roles | Medium | All helpers are `brave`; no `--type` is retained. |
| 25 ms sampling | Can miss a very short detached helper | Low | Three independent peaks are stable at 9; baseline detached count is 0. |

One additional caveat: the cold-proof teardown check scans `/proc/*/cmdline` for
the `archivetune-potoken-` prefix. Chromium children can rewrite their argv, so
that check is not a proof that every helper is gone. The process sampler's
session/process-group closure is the stronger evidence, and the three non-zero
mints independently prove that extraction did start new browsers.

## Scope and registry correctness

The benchmark launches the JVM with `start_new_session=True`
(`scripts/benchmark-application.py:1366`,
`scripts/benchmark-authenticated-application.py:913`). The sampler then tracks:

- the application PID's session and process group;
- iterative descendants of tracked processes;
- a persistent `(pid, starttime)` registry for processes that reparent or call
  `setsid()`.

Baseline results:

- `detached_from_app_group_names: []`
- `max_detached_from_app_group_count: 0`
- browser peak only in `cold_proof` and `extraction`
- zero browser processes in post-idle and after shutdown

So all nine helpers stayed inside the app's session/process group. That is why
the count is trustworthy even though `stopManagedBrowser`'s profile scan may not
see children that dropped the profile argument. It also proves the user's Brave
was not folded in: a desktop-session Brave would not share the app's private
session.

The residual accounting risk is a helper that calls `setsid()` and exits before
the first 25 ms sample. The stable nine across three launches makes a systematic
miss unlikely, but the tracer's 20 ms interval and `(pid, starttime)` registry
are the right confirmation tool.

## Architectural reading for the parent

The browser cost is real but **conditional**, not intrinsic to extraction:

1. **Keep the existing warm fastpath.** `DesktopSessionManager` already avoids
   the browser when a valid video-bound token exists. A resolver change must not
   clear or bypass that state.
2. **Prefer lazy proof.** `prepareStreamHttpRecovery()`
   (`ArchiveTuneJvmClient.kt:1058-1095`) already mints after a concrete 403 and
   retries the same Web client. Since the observed resolutions succeeded through
   `VISIONOS`, eager minting may be unnecessary for many tracks. The tradeoff is
   one extra failed request when a Web client truly needs proof.
3. **Consider session-token reuse.** `resolveGvsPoToken()` already accepts
   `poTokenGvsSession` for non-`WEB_CREATOR` clients that use Web proof. The
   manager could avoid a new per-video mint when the chosen Web client accepts
   session proof, instead of requiring `poTokenGvsVideoId == videoId`. This
   needs a measured 403-rate check because YouTube's binding rules are not
   public, and it does not help native clients such as `VISIONOS`.
4. **Bound the cache.** A small multi-entry cache avoids A/B/A thrash and
   preloader churn; it does not reduce the one-mint-per-new-track cost.
5. **Do not eager-mint in the preloader** unless the selected client needs it;
   a queue can otherwise start a browser for the next track while the current
   one plays. This is outside the measured extraction phase and remains an open
   question.

No production change is proposed here. The parent owns the architecture
decision; this analysis only identifies which costs are necessary, transient,
and avoidable.

## Targeted confirmation (held for GO)

The new script is safe by construction:

- attach mode is read-only and selects only roots with the managed profile
  prefix inside the app session/lineage;
- launch mode uses a fresh private profile under `/tmp/opencode`, passes no
  cookie/session/token, opens `about:blank`, and signals only tracked pids from
  the lineage it created;
- it never emits argv, URLs, cookies, profile paths, or environment values;
- role parsing reads only `--type`, `--utility-sub-type`, and
  `--no-zygote-sandbox`;
- `--self-test` uses synthetic rows and has already passed.

Commands for the coordinated window:

```bash
# safe, no browser, no /proc scan
python3 scripts/trace-legacy-browser.py --self-test

# controlled launch: one exact production-shaped invocation
python3 scripts/trace-legacy-browser.py \
  --launch --hold-ms 1500 \
  --output /tmp/opencode/legacy-browser-fanout.json

# read-only attach during one real mint in the full application
python3 scripts/trace-legacy-browser.py \
  --app-pid <benchmark-app-pid> --timeout 30 \
  --output /tmp/opencode/legacy-browser-attach.json
```

Acceptance criteria: about nine simultaneous processes for one invocation; one
browser root with zygote/renderer/utility ancestry edges; no GPU process; crashpad
recorded as present or absent rather than assumed; zero leftovers; profile
removed; no raw argv in the JSON.

Do not run these until the parent gives GO, to avoid CPU/network interference
with yt-dlp profiling.

## Open questions

- Exact per-role counts for Brave 155 on this host.
- Whether crashpad starts in this headless configuration or exits before the
  first sample.
- Whether production warm policy would skip zero, one, or three of the observed
  mints; the restored session's bound token's video is unknown.
- Whether `WEB_REMIX` needed the minted proof or simply lost the race to
  `VISIONOS`.
- Whether next-stream preloading triggers additional mints during playback.

## Sources

Repository paths:

- `desktop-auth/src/main/kotlin/moe/rukamori/archivetune/desktopauth/DesktopBotGuardPoTokenProvider.kt`
- `desktop-auth/src/main/kotlin/moe/rukamori/archivetune/desktopauth/DesktopSessionManager.kt`
- `desktop-auth/src/main/kotlin/moe/rukamori/archivetune/desktopauth/ManagedChromiumLogin.kt`
- `desktop-auth/src/main/kotlin/moe/rukamori/archivetune/desktopauth/SecureSessionStore.kt`
- `desktop-backend/src/main/kotlin/moe/rukamori/archivetune/innertube/ArchiveTuneJvmClient.kt`
- `stream-resolver/src/main/kotlin/moe/rukamori/archivetune/stream/PlaybackStreamResolver.kt`
- `core` submodule, `src/main/kotlin/moe/rukamori/archivetune/innertube/PlaybackAuthState.kt`
  (commit `144fe5c33c9e2f1f8a6c8c84bb03a34c3c4d7b3c`)
- `scripts/benchmark-application.py`
- `scripts/benchmark-authenticated-application.py`
- `packaging/resolver/validation/linux-bun-authenticated-full-application.{md,json}`

Primary documentation:

- Chromium process model and site isolation:
  https://chromium.googlesource.com/chromium/src/+/main/docs/process_model_and_site_isolation.md
- Chromium Linux zygote:
  https://chromium.googlesource.com/chromium/src/+/main/docs/linux/zygote.md
- Chromium headless:
  https://chromium.googlesource.com/chromium/src/+/main/headless/README.md
- Chrome DevTools Protocol:
  https://chromedevtools.github.io/devtools-protocol/
- Crashpad:
  https://chromium.googlesource.com/crashpad/crashpad/+/master/README.md
- Linux `/proc`:
  https://man7.org/linux/man-pages/man5/proc.5.html
- Chromium command-line switches (`--type`, `--utility-sub-type`):
  https://chromium.googlesource.com/chromium/src/+/main/content/public/common/content_switches.cc
- Ecosystem context for session vs video-bound PO tokens (not authoritative for
  YouTube's current rules):
  https://github.com/yt-dlp/yt-dlp/wiki/PO-Token-Guide

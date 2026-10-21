# Why legacy extraction starts nine Chromium helpers

**Status:** completed causal, count, role-mix, ancestry, and teardown analysis,
plus a read-only matrix harness path-length finding. The controlled
private-profile confirmation was run after the parent's GO, not against the user
application. No new benchmark ran while the worker timing slot was active.

**Scope:** read-only analysis of the committed benchmark, the desktop auth/backend
code, and the safe authenticated report, plus controlled launches of the exact
BotGuard browser command with fresh private profiles and read-only inspection of
one excluded matrix arm. No production Kotlin, Gradle, login flow, challenge
code, user application, user profile, auth session, or keyring was modified or
accessed. No BotGuard JavaScript was executed and no token was minted. Raw run
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
Three controlled launches of the exact command resolved the same nine-process
tree every time: **1 browser, 3 zygotes, 2 renderers, 1 GPU process, 1 network
utility, and 1 storage utility**, with no crashpad. The GPU process is present
even though `--disable-gpu` is passed; that flag selects software rendering, it
does not remove the process.

A separate legacy matrix arm started only a **partial four-process tree** and is
**excluded** from the role and timing evidence. Its harness placed the throwaway
Chromium profile under a long run root, pushing Chromium's `SingletonSocket` path
to ~171 bytes against the 108-byte UNIX limit. The controlled proof used a short
profile whose socket path was ~89 bytes and reached nine processes every time.
This is a benchmark-harness defect, not a stream-resolver, NewPipe, STS, login,
or product defect; details and disclosures are in the matrix exclusion section.

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

`--disable-gpu` does not remove the GPU process; it selects software rendering
while Chromium still starts the GPU service. It also does not collapse the rest
of the process model. `--single-process` and `--no-zygote` are absent.

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
services (network, storage, audio, data decoder, etc.), a GPU service, and
optionally crashpad. Three controlled launches of the exact project command
measured this tree:

| Role | Observed | 3/3 launches | How it is identified |
| --- | ---: | :---: | --- |
| `browser` | 1 | yes | no `--type=` on the root |
| `zygote` | 3 | yes | `--type=zygote`, optionally `--no-zygote-sandbox` |
| `renderer` | 2 | yes | `--type=renderer` (the `about:blank` page plus a spare) |
| `utility:network` | 1 | yes | `--type=utility --utility-sub-type=network.mojom.NetworkService` |
| `utility:storage` | 1 | yes | `--type=utility --utility-sub-type=storage.mojom.StorageService` |
| `gpu` | 1 | yes | `--type=gpu-process`; still present with `--disable-gpu` |
| `crashpad` | 0 | not seen | executable basename `chrome_crashpad_handler` |

That is the complete nine-process peak. Every launch had identical role counts;
no crashpad process was observed in any of the three runs. The measured ancestry
per launch was:

```text
external -> browser                 1
browser  -> zygote                  2
browser  -> utility:network         1
zygote   -> zygote                  1
zygote   -> renderer                2
zygote   -> gpu                     1
zygote   -> utility:storage         1
```

One browser child zygote spawns the second zygote; the network service is a
direct browser child, while renderers, GPU, and storage descend through a
zygote. Teardown took 39, 38, and 38 ms, left zero processes behind, and removed
the private profile after every run. DevTools became ready in all three runs.

The benchmark itself still **cannot** confirm roles:
`scripts/benchmark-application.py:577-590` resolves `/proc/<pid>/exe` and keeps
only the basename, so every helper becomes `brave`; `classify_process()`
(`:522-538`) assigns the category from name tokens and never reads `--type`.
The report's `process_names: ["brave"]` is therefore expected and is not role
evidence. The controlled proof supplements it.

On this host `/usr/bin/brave` is a small wrapper that `exec`s
`/opt/brave-bin/brave`; Chromium's crashpad binary is
`/opt/brave-bin/chrome_crashpad_handler`, so a crashpad process would have a
distinct basename. The benchmark's name set contains only `brave`, consistent
with the controlled runs' zero crashpad observations; it is not independent
proof for a real mint.

Evidence that goes beyond the name count:

- the peak is exactly nine in three independent benchmark situations (baseline
  cold proof, baseline extraction, candidate cold proof) and in three controlled
  role-proof launches;
- the total process count at those instants is ten (one JVM + nine browser
  processes), and the extraction distribution is median 1, p95 10, max 10 —
  i.e. the browser tree is present only for a short window, not persistent;
- three non-zero mint durations independently prove three launches;
- no browser process is detached from the app session/process group;
- no browser process appears in startup, idle, auth, playback, post-idle, or
  shutdown.

**Controlled-proof caveat:** the three role runs used the exact command with a
fresh private profile and `about:blank`, but did not execute BotGuard JavaScript
or mint a token. They prove the process shape of the command; the benchmark's
nine-process peak matches, but per-role counts during a real mint were not
sampled. A role added only by BotGuard JavaScript would not appear here.

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

## Controlled role-mix confirmation (completed)

The confirmation ran three times with the exact non-root command, a fresh
0700 private profile under `/tmp/opencode`, no cookies or session token, and
`about:blank`. It did not attach to the user application or access the user's
browser profile, auth session, or keyring. The script is safe by construction:

- attach mode is read-only and selects only roots with the managed profile
  prefix inside the app session/lineage;
- launch mode uses a fresh private profile, passes no cookie/session/token,
  opens `about:blank`, and signals only tracked pids from the lineage it created;
- it never emits argv, URLs, cookies, profile paths, or environment values;
- role parsing reads only `--type`, `--utility-sub-type`, and
  `--no-zygote-sandbox`; Chromium's space-joined process titles are normalized
  before parsing;
- `--self-test` uses synthetic rows, including a space-joined title, and passes.

Commands used:

```bash
# safe, no browser, no /proc scan
python3 scripts/trace-legacy-browser.py --self-test

# three controlled launches of the exact production-shaped invocation
python3 scripts/trace-legacy-browser.py \
  --launch --hold-ms 1200 \
  --output /tmp/opencode/legacy-browser-role-mix-2.json
# ... repeat with -3.json and -4.json

# aggregate only the safe per-launch JSON
python3 scripts/trace-legacy-browser.py \
  --aggregate \
  /tmp/opencode/legacy-browser-role-mix-2.json \
  /tmp/opencode/legacy-browser-role-mix-3.json \
  /tmp/opencode/legacy-browser-role-mix-4.json
```

Run 1 predated the space-joined-title parser fix; its role counts and one
zombie leftover are not part of the proof. The three clean runs are identical:
peak 9, the role counts in the table above, the same ancestry edges, GPU present,
crashpad absent, zero leftovers, and profile removed. Teardown was 39, 38, and
38 ms.

The remaining limitation is the controlled page: `about:blank` does not execute
the BotGuard challenge, so it cannot reveal a role that only the challenge
JavaScript starts. The benchmark's nine-process peak is the independent
real-application anchor; the per-role split is from the exact command.

## Matrix harness path-length exclusion (read-only)

The first legacy arm of the final resolver matrix is **not usable** as resolver
timing, browser-mint success, or process-role evidence. It was observed
read-only; no experiment was launched for this finding. Only safe marker values,
counts, and known error substrings were retained; no raw log line is reproduced.

| Observation | Long-path matrix arm | Controlled role proof |
| --- | --- | --- |
| Extraction | 12/12 succeeded, but every resolution was delayed | not applicable |
| Resolve timing | p50 15,203 ms; range 15,164–17,008 ms; phase 186,190 ms | not applicable |
| Successful clients | 11 `VISIONOS`, 1 `ANDROID_VR` | not applicable |
| Browser process peak | **4** | **9** |
| Renderers / DevTools | not ready | ready in 3/3 |
| Cold proof | 15,039 ms; `minted=false`; no failure class | no token mint (`about:blank`) |
| Profile / `SingletonSocket` path | ~125 / ~171 bytes | ~43 / ~89 bytes |

The harness set `HOME`, `XDG_CACHE_HOME`, and `TMPDIR` to long run-local paths.
`defaultBotGuardTempRoot()` selected `XDG_CACHE_HOME/ArchiveTune/botguard`
(94 bytes), so the generated profile was ~125 bytes. Chromium then tries to
create `<profile>/.org.chromium.Chromium.*/SingletonSocket`; at ~171 bytes it
exceeds the kernel's 108-byte UNIX socket path limit by ~63 bytes. Even the
`TMPDIR` fallback would have been ~143 bytes, so neither long path was safe.

The failure is consistent with the process shape: Chromium starts a partial
four-process tree, cannot create its singleton socket, and crashes before any
renderer or DevTools endpoint appears. `waitForDevTools()` then consumes its
15 s startup budget and the provider returns null without a failure class. The
outer `preparePlaybackAuth()` 15 s timeout cancels the delegated mint path before
a `delegated-playback-auth` marker can be emitted. The separate cold proof is
decisive because it exercises only the playback-auth browser mint, not signature
timestamp or NewPipe: it took 15,039 ms and minted nothing.

The browser's stderr is redirected to `DISCARD`, but Brave's crash reporter left
**13 completed crash dumps**, exactly one per browser attempt (12 extraction
mints + 1 cold proof). Every dump contains the known substrings `SingletonSocket`
and `too long` (39 and 52 total occurrences). This is the error counter, not a
raw log excerpt. The arm also showed only 1.56 s of sampled child CPU over
three minutes and zero browser helpers after cleanup, consistent with a browser
that never became useful.

The parent's harness fix is commit `29ed69003`, scope **benchmark harness only**:
an atomic 0700 short `HOME` under `/tmp`, `XDG_CACHE_HOME` set directly to that
`HOME`, the Java temp directory set to `HOME/t`, and the private `HOME` removed
after clone cleanup. That puts the profile root at 28 bytes, well below the
limit. A fresh four-round short-root rerun was active when this report was
prepared; its results are separate from this excluded arm. No product or login
change is required.

**Proof-control disclosure:** this arm had **0/1 cold-proof mints** and no
delegated extraction-mint markers, so extraction mint success is **0/12
inferred**. All **12/12 resolutions still succeeded**, the account verified
true, and the public streams were valid. The arm is excluded from resolver
timing and browser success claims; the 12 successes do not validate the browser
mint. The controlled about:blank proof likewise remains a process-shape proof,
not a real-mint role proof.

## Open questions

- Whether the fresh short-root matrix rerun restores the nine-process fan-out
  and a successful browser mint; the excluded arm does not answer this.
- Whether BotGuard JavaScript adds a role or changes the measured about:blank
  mix during a real mint. The benchmark peak matches, but per-role counts were
  not sampled inside the application.
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

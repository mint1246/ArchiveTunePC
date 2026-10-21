# ArchiveTune persistent yt-dlp worker evaluation

Developer-only evaluation of **persistent Python worker alternatives** for the
yt-dlp stream resolver. Nothing in this document changes production code, and
no upstream yt-dlp extraction or challenge patch is proposed or used.

* Harness: [`scripts/benchmark-ytdlp-worker.py`](scripts/benchmark-ytdlp-worker.py)
* Machine-readable result: [`packaging/resolver/validation/linux-ytdlp-worker-evaluation.json`](packaging/resolver/validation/linux-ytdlp-worker-evaluation.json)
* Pinned toolchain: yt-dlp **2026.08.19**, yt-dlp-ejs **0.8.0**, Bun **1.4.3**
* Scope: anonymous extractions only. The signed-in whole-application benchmark
  stays with the parent task; this evaluation is the anonymous lower bound of
  the resolver request path.

**Verdict in one paragraph.** A persistent Python worker removes up to ~1 s of
per-request overhead on the production-parity workload (measured wall p50
2302 ms for the shipped frozen control against 1311 ms for the reusing
worker), split into 448 ms of frozen-bundle start-up, 198 ms of Python
process start-up and 345 ms of object-level caches (player JS, signature
cache, HTTP connection pool). It does **not** remove the challenge solver:
Bun is started exactly once per challenged request in every configuration,
including every warm repeat inside the reusing worker, and that solver is the
largest remaining cost. The price is a resident process (92.8 MiB RSS /
77.6 MiB PSS idle), a changed cancel model and a credential lifecycle that
upstream does not provide. The gap to the native path therefore stays open,
and the measured numbers point at a hybrid rather than at a full replacement.

---

## 1. Question

The shipped resolver starts **one frozen yt-dlp process per request**. The
frozen executable is a self-extracting bundle, so every request pays an
interpreter start-up, an extractor registry import and an EJS package load
before a single byte is fetched.

The question this evaluation answers with measurements:

> Does keeping a Python interpreter - and optionally a `YoutubeDL` object -
> alive between requests beat the frozen CLI, and what does it cost in resident
> memory, in credential handling and in packaging?

Four request models are compared against the same videos with the same
production flag set:

| configuration | process model | `YoutubeDL` object |
|---|---|---|
| `frozen_cli` | one frozen yt-dlp process per request (the shipped control) | - |
| `fresh_python` | one new virtual-environment Python process per request | new per request |
| `persistent_python_new_ydl` | one long-lived Python process | new per request |
| `persistent_python_reuse_ydl` | one long-lived Python process | reused across requests |

---

## 2. Method

### 2.1 Environment

* Every request runs with a private `HOME`, `XDG_CACHE_HOME`, `XDG_CONFIG_HOME`,
  `XDG_DATA_HOME` and `TMPDIR`, so no runtime cache can touch the user profile.
* The virtual environment lives in the git-ignored `.tools/` directory. Nothing
  is installed globally and no daemon survives the harness.
* The environment contains the **upstream** yt-dlp 2026.08.19 release sources
  (`yt-dlp.tar.gz`, SHA-256 `072aad4f…`) plus `yt-dlp-ejs==0.8.0` (wheel
  SHA-256 `79300e5f…`) and upstream's own pinned dependency set
  (requests, urllib3, certifi, brotli, mutagen, pycryptodomex, websockets).
* The upstream `lazy_extractors` module was generated with upstream's own
  `devscripts/make_lazy_extractors.py`, exactly as the upstream release build
  does. A plain `pip install` does **not** ship it, which matters: see §6.1.
* The frozen control and the Bun sidecar are the checksum-verified release
  artifacts (`yt-dlp` SHA-256 `58162f9b…`, Bun 1.4.3 binary SHA-256
  `7ce7d6b6…`). The global Bun 1.3.14 installation is never used or modified.

### 2.2 Request model

The worker speaks a JSON-lines protocol over stdin/stdout and uses the
**public** `yt_dlp.YoutubeDL` API only. The CLI flags of the shipped resolver
are mapped onto public parameters:

| shipped flag | public parameter |
|---|---|
| `--ignore-config` | `ignoreconfig=True` |
| `--no-plugin-dirs` | `YTDLP_NO_PLUGINS=1` (the documented equivalent, `yt_dlp/plugins.py`) |
| `--no-remote-components` | `remote_components=[]` |
| `--no-js-runtimes --js-runtimes bun:<path>` | `js_runtimes={'bun': {'path': path}}` |
| `--no-cache-dir` | `cachedir=False` |
| `--no-playlist` | `noplaylist=True` |
| `--no-progress` | `noprogress=True` |
| `--socket-timeout 12` | `socket_timeout=12` |
| `--retries 1` / `--extractor-retries 1` | `retries=1` / `extractor_retries=1` |
| `--skip-download` | `skip_download=True` |
| `--format <selector>` | a **callable** format selector (§6.3) |
| `--extractor-args youtube:player_client=…` | `extractor_args={'youtube': {'player_client': [client]}}` |

The format selector is the one from `YtDlpStreamResolver.formatSelector`
(`bestaudio[protocol=https][abr<=160]/…` for `HIGH`), so the control and the
worker resolve the *same* format.

### 2.3 Workloads and scenarios

Two workloads are kept strictly apart and are never mixed in a result:

* **`default_client`** - the production-parity workload. The shipped resolver
  does not pass a player client, so the default client is used.
* **`forced_web_embedded`** - the explicit challenge control: the same
  challenged video with the `web_embedded` player client.

| scenario | video | player client | workload |
|---|---|---|---|
| `challenged_default` | `dQw4w9WgXcQ` | default | `default_client` |
| `challenged_web_embedded` | `dQw4w9WgXcQ` | `web_embedded` | `forced_web_embedded` |
| `simple_a` | `jNQXAC9IVRw` | default | `default_client` |
| `simple_b` | `aqz-KE-bpKQ` | default | `default_client` |

`dQw4w9WgXcQ` with the **default** client is the production-parity case: the
existing runtime comparison shows that yt-dlp still solves a challenge for that
video with the default client, so it belongs to the default-client workload
rather than to the forced control. The two simple videos are the unchallenged
control.

Requests are serialized (one at a time) and interleaved per configuration, so
network and machine drift hit every configuration equally. Every round contains
one cold request per scenario for the process-per-request configurations and a
cold request plus an immediate warm repeat per scenario for the persistent
workers.

### 2.4 Denominators

A retried request keeps **every** attempt. The latency, CPU and memory
distributions are computed over the attempt set, and the report states
`requests` and `attempts` separately, so a failure can never be hidden by a
later success. A request is retried up to three times (YouTube occasionally
answers without a usable format set); each attempt is a separate entry in the
report with its own attempt number.

### 2.5 What is measured

* **Latency**: wall time of the whole request (process spawn to process exit
  for the process models, request written to the response read for the
  persistent workers), split into *import*, *`YoutubeDL` construction* and
  *extraction* stages. p50/p90/p95/max over the runs.
* **CPU**: `getrusage` deltas. For the process models the harness delta is
  transitive on Linux and therefore includes the JavaScript runtime the child
  spawned. For the persistent workers the worker reports its own
  `RUSAGE_SELF` plus its `RUSAGE_CHILDREN` delta, so a runtime helper that has
  already exited is still counted.
* **Memory**: the maximum *simultaneous* sum of the process tree's RSS (from
  `/proc/<pid>/statm`) and PSS (from `/proc/<pid>/smaps_rollup`), plus the
  **resident idle memory** of a persistent worker between requests. Both
  samplers poll every 50 ms, so the peaks are lower bounds.
* **Solver invocations**: a shim in front of Bun logs every invocation;
  invocations that only probe the runtime version (`bun --version`) are
  excluded. yt-dlp's own `Solving JS challenges using …` diagnostics
  corroborate the count.
* **Upstream cache**: two frozen-CLI extractions against one shared
  `--cache-dir`, counting the solver invocations of each.
* **Reliability**: every failure and every retried attempt is kept in the
  report.
* **Cleanup**: after the run the harness verifies that no Bun or helper
  process survived and that the private temporary tree is gone.

### 2.6 Safety contract

* Cookies are never requested, never read and never written; the run is
  anonymous and the worker refuses a cookie file.
* Signed media URLs, raw `--dump-single-json` payloads and process argv are
  never written to disk and never printed. The report contains only redacted
  derived fields (ids, codecs, protocols, byte counts, timings).
* The range probe always runs **outside** the measured request window.

### 2.7 The offline fixture

`--offline-stub` makes **every** configuration answer from a local stub and a
local range-serving HTTP server on `127.0.0.1`, so a plumbing test of the
harness cannot contact YouTube at all. The run is refused unless the control
binary is a stub script, and the start-up and cache probes are switched off in
that mode. The fixture lives in the git-ignored `.tools/stub/` directory and
is not part of the repository. It exists because an earlier plumbing test of
the harness accidentally reached YouTube through the worker configurations
(15 requests, 2026-10-21T11:11:08Z to 11:11:39Z); the measurement reported
here does not come from that run.

---

## 3. Results

Every number in this section is taken from
[`packaging/resolver/validation/linux-ytdlp-worker-evaluation.json`](packaging/resolver/validation/linux-ytdlp-worker-evaluation.json).
Nothing is estimated and nothing is pooled across workloads: the two workloads
are reported separately because they answer different questions.

### 3.1 Provenance

| session | window (UTC) | configurations | attempts |
|---|---|---|---|
| main session | finished 2026-10-21T11:29:58Z, 154.11 s | `fresh_python`, `persistent_python_reuse_ydl` (and, invalid, `persistent_python_new_ydl`) | 72 |
| targeted re-measurement | 2026-10-21T11:40:04Z .. 11:41:34Z, 90.06 s | `frozen_cli`, `persistent_python_new_ydl` | 36 |

The main session measured a `persistent Python with a new YoutubeDL per
request` configuration that was **invalid**: the worker handle passed the
reuse flag as the string `"0"`, and a non-empty string is truthy, so that
configuration silently ran in reusing mode. The row is **excluded** and
superseded by the targeted re-measurement; the JSON records this in
`merge.superseded_configs`. The control was re-measured in the same session as
the re-measurement, so that the comparison against it is internally
consistent. `fresh_python` and `persistent_python_reuse_ydl` come from the
main session.

The mode is proven by the worker's own counters in the live run:

| configuration | attempts | `YoutubeDL` objects built |
|---|---|---|
| `persistent_python_new_ydl` | 24 | **24** (one per request) |
| `persistent_python_reuse_ydl` | 24 | **7** (only when the player client changes) |

`scripts/benchmark-ytdlp-worker.py --self-check` is the offline regression
fixture for this: it verifies the mode flag of both persistent workers, that
the new-object worker builds one object per request, that the reusing worker
keeps one object for two identical requests, that the reusing worker rebuilds
when the extractor arguments change, and that the harness reports a worker
that runs in the wrong mode (`WORKER_MODE_MISMATCH`). It never touches the
network. All 9 checks pass.

### 3.2 Latency, per workload

Wall time of the whole request. `frozen_cli` and `fresh_python` start a
process per request, so their number includes the process start-up; the
persistent workers do **not** include their own start-up in the request
window (it happens once, before the worker answers its first request - see
§3.4).

**Workload `default_client`** (production parity: the challenged video with the
default client plus the two unchallenged videos)

| configuration | attempts | ok | failed | wall p50 | p90 | p95 | max |
|---|---|---|---|---|---|---|---|
| `frozen_cli` | 9 | 9 | 0 | 2302 ms | 3233 ms | 3233 ms | 3233 ms |
| `fresh_python` | 9 | 9 | 0 | 1854 ms | 3371 ms | 3371 ms | 3371 ms |
| `persistent_python_new_ydl` | 18 | 18 | 0 | 1656 ms | 2429 ms | 2795 ms | 2795 ms |
| `persistent_python_reuse_ydl` | 18 | 18 | 0 | **1311 ms** | 2258 ms | 2662 ms | 2662 ms |

**Workload `forced_web_embedded`** (the explicit challenge control)

| configuration | attempts | ok | failed | wall p50 | p90 | p95 | max |
|---|---|---|---|---|---|---|---|
| `frozen_cli` | 3 | 3 | 0 | 2827 ms | 2905 ms | 2905 ms | 2905 ms |
| `fresh_python` | 3 | 3 | 0 | 2475 ms | 2665 ms | 2665 ms | 2665 ms |
| `persistent_python_new_ydl` | 6 | 6 | 0 | 2343 ms | 2399 ms | 2399 ms | 2399 ms |
| `persistent_python_reuse_ydl` | 6 | 6 | 0 | **2133 ms** | 4656 ms | 4656 ms | 4656 ms |

The forced control is dominated by the JavaScript runtime; the difference
between the configurations is much smaller there than on the default-client
workload.

### 3.3 First request versus repeats

The persistent workers are the only ones that can amortise anything, so their
first request and their repeats are separated. The first request of a
persistent worker does not contain the interpreter start-up, so the
*effective* first request is the measured first request plus the one-off
start-up of §3.4.

| configuration | kind | attempts | wall p50 | wall max | extract p50 |
|---|---|---|---|---|---|
| `frozen_cli` | cold (always a new process) | 12 | 2374 ms | 3233 ms | - |
| `fresh_python` | cold (always a new process) | 12 | 2007 ms | 3371 ms | 1634 ms |
| `persistent_python_new_ydl` | cold (first) | 1 | 2795 ms | 2795 ms | 2662 ms |
| `persistent_python_new_ydl` | new video | 6 | 1473 ms | 2343 ms | 1432 ms |
| `persistent_python_new_ydl` | warm same video | 17 | 2064 ms | 2429 ms | 2023 ms |
| `persistent_python_reuse_ydl` | cold (first) | 1 | 2662 ms | 2662 ms | 2531 ms |
| `persistent_python_reuse_ydl` | new video | 6 | 1387 ms | 2133 ms | 1347 ms |
| `persistent_python_reuse_ydl` | warm same video | 17 | 1520 ms | 4656 ms | 1520 ms |

The `cold` row of each persistent worker is a **single sample**; it is the
price of the very first request (the extractor registry, the extractor module
import, the player JS download and the first extraction) and it is not a
steady-state number.

### 3.4 Start-up cost (no network)

| measurement | wall p50 | import p50 | `YoutubeDL` construction p50 |
|---|---|---|---|
| frozen control start-up (`yt-dlp --version`) | 465 ms | - | - |
| Python start-up, upstream lazy extractor registry | 240 ms | 148 ms | 51 ms |
| Python start-up, eager extractors (plain `pip install`) | 426 ms | 148 ms | 203 ms |

The import cost is identical in both Python variants; the difference is the
extractor registry, which `YoutubeDL.__init__` imports
(`add_default_info_extractors`). The frozen control is a self-extracting
bundle and pays its own unpacking cost on every request.

### 3.5 CPU

`cpu_total` is the CPU of the whole request tree. For the process-per-request
configurations it is the harness `getrusage(RUSAGE_CHILDREN)` delta, which is
transitive on Linux and therefore includes the JavaScript runtime the child
spawned. For the persistent workers it is the worker's own `RUSAGE_SELF` plus
its `RUSAGE_CHILDREN` delta, so a runtime helper that has already exited is
still counted.

**Workload `default_client`**

| configuration | cpu total p50 | worker self p50 | exited helper p50 | cpu total max |
|---|---|---|---|---|
| `frozen_cli` | 0.85 s | 0.00 s | 0.85 s | 2.55 s |
| `fresh_python` | 0.55 s | 0.25 s | 0.30 s | 2.23 s |
| `persistent_python_new_ydl` | 0.19 s | 0.19 s | 0.00 s | 1.93 s |
| `persistent_python_reuse_ydl` | 0.15 s | 0.15 s | 0.00 s | 1.97 s |

For `fresh_python` the *worker self* covers only the extraction, because the
interpreter start-up and the import happen before the worker answers; the
difference between the total and the self value is the process start-up and
teardown, not a helper.

The exited-helper column is the challenge solver. On the challenged workload
the reaped JavaScript runtime costs 0.93 s (reusing worker, warm same video)
to 1.66 s (first request) of CPU per request, and it is paid in **every**
configuration.

### 3.6 Memory

| configuration | peak tree RSS p50 | peak tree RSS max | peak tree PSS p50 | peak tree PSS max | resident idle RSS p50 | resident idle PSS p50 |
|---|---|---|---|---|---|---|
| `frozen_cli` | 102.7 MiB | 428.0 MiB | 84.2 MiB | 426.4 MiB | - | - |
| `fresh_python` | 79.0 MiB | 422.4 MiB | 62.5 MiB | 407.3 MiB | - | - |
| `persistent_python_new_ydl` | 146.7 MiB | 495.2 MiB | 134.0 MiB | 479.9 MiB | 104.7 MiB | 92.0 MiB |
| `persistent_python_reuse_ydl` | 110.5 MiB | 431.5 MiB | 95.2 MiB | 413.8 MiB | **92.8 MiB** | **77.6 MiB** |

The idle column is the price of the resident worker: it stays alive between
requests and is paid for as long as the application runs. The reusing worker
is the cheaper of the two persistent variants (92.8 MiB against 104.7 MiB
RSS): it keeps one extractor instance, while the new-object worker allocates
and discards a `YoutubeDL` object per request and the allocator does not
return that memory to the operating system.

The peak columns are dominated by the JavaScript runtime child, which is
identical in all configurations.

### 3.7 Challenge solver invocations - the decisive measurement

Bun invocations, counted through a shim in front of Bun and corroborated by
yt-dlp's own `Solving JS challenges using …` diagnostics:

| configuration | challenged requests | Bun invocations | Bun invocations per challenged request |
|---|---|---|---|
| `frozen_cli` | 6 | 6 | 1 |
| `fresh_python` | 6 | 6 | 1 |
| `persistent_python_new_ydl` | 12 | 12 | 1 |
| `persistent_python_reuse_ydl` | 12 | 12 | 1 |

**Every challenged request starts Bun exactly once - including every warm
same-video repeat inside the reusing worker.** A persistent worker does *not*
avoid the challenge solver. This matches the upstream source (§4, points 2 and
3): the `n` challenge is submitted to the solver on every extraction and the
`n` result is cached in memory only.

### 3.8 The upstream on-disk cache

Two frozen-CLI extractions of the challenged video with one shared
`--cache-dir`:

| attempt | exit code | wall | Bun invocations | cache content |
|---|---|---|---|---|
| 1 | 0 | 3090 ms | 1 | `youtube-sigfuncs/67288f35-main-110.json` (458 bytes) |
| 2 | 0 | 2872 ms | 1 | `youtube-sigfuncs/67288f35-main-110.json` (458 bytes) |

The upstream cache holds the public signature functions and nothing else. It
does **not** remove the solver: the second extraction still starts Bun once.
This is the expected behaviour from the source (§4, point 6) and it confirms
the existing statement in `YT_DLP_RESOLVER.md` that the standard cache does
not eliminate the expensive `n` challenge path.

### 3.9 Reliability, failures and cleanup

* 72 attempts in the merged report, **0 failed**, no retries were needed.
* Every successful result passed the head/tail range probe (HTTP 206 at the
  head and at the tail with the headers yt-dlp returned).
* `cleanup.leftover_processes` is empty and the private temporary tree was
  removed: no Bun, no helper and no worker survived the harness.

### 3.10 Attribution of the wall-time gain

On the `default_client` workload (wall p50), the gain of the reusing worker
over the shipped control is 2302 ms -> 1311 ms, i.e. **991 ms**, and it splits
into three parts:

| step | wall p50 | delta | what it is |
|---|---|---|---|
| `frozen_cli` | 2302 ms | - | the shipped control |
| `fresh_python` | 1854 ms | **-448 ms** | the frozen bundle's per-request start-up (measured independently at 465 ms in §3.4) |
| `persistent_python_new_ydl` | 1656 ms | **-198 ms** | the interpreter start-up, the `yt_dlp` import and the extractor registry, which a long-lived process pays once |
| `persistent_python_reuse_ydl` | 1311 ms | **-345 ms** | object-level state: the reused extractor instance keeps the player JS (`_code_cache`), the signature/`n` cache (`_player_cache`) and the HTTP connection pool |

The last step is **not** CPU: the CPU of the reusing worker is only 0.04 s
lower than the new-object worker (0.15 s vs 0.19 s). It is waiting time, and
the upstream source shows what is being kept: the player JavaScript is
downloaded once per extractor instance, and the `urllib3` pool of the request
director is reused. This evaluation does not separate the two contributions
further, because upstream keeps them in the same object.

On the `forced_web_embedded` workload the same chain gives 2827 ms -> 2133 ms
(**-694 ms**): the absolute numbers are larger because the JavaScript runtime
dominates, and the part of the request that the worker can influence is
smaller.

### 3.11 Verdict

**The persistent worker materially improves the resolver request path, but it
does not close the gap to the native path.**

* It removes up to ~1 s of per-request overhead on the default-client
  workload (991 ms of the measured 2302 ms), split between the frozen bundle's
  start-up, the Python process start-up and the object-level caches.
* It does **not** remove the challenge solver: one Bun process per challenged
  request in every configuration, 0.93-1.66 s of reaped helper CPU and the
  largest single contributor to the peak memory (the Bun child dominates the
  428-495 MiB peak tree RSS).
* On the challenged workload the best measured configuration is 2133 ms
  (reusing worker) against a control of 2827 ms. The native/legacy path that
  the application replaced was measured at ~395 ms. The gap is therefore
  still dominated by the challenge solve, which upstream does not allow to be
  cached.
* The price is a resident process: 92.8 MiB RSS / 77.6 MiB PSS idle for the
  reusing worker, plus a changed cancel model (§5.3) and a credential
  lifecycle (§5.2).

A hybrid therefore remains the most likely shape: keep the shipped frozen
executable for the challenged path (where the Bun solve dominates and the
start-up is irrelevant), and use a persistent worker for the unchallenged
path, where the measured gain is largest relative to the request cost. A
cheaper alternative for the same effect is to make the *control* cheaper
(the upstream unpacked release file exists and avoids the self-extracting
bundle), which would recover the 448 ms of the frozen bundle's start-up
without any resident process.

---

## 4. Upstream source corroboration

Every caching claim in this document is anchored in the upstream 2026.08.19
sources.

1. **A reused `YoutubeDL` really does keep the extractor's caches.**
   `YoutubeDL.get_info_extractor` stores the extractor instance in
   `self._ies_instances`, so one `YoutubeDL` object reuses one `YoutubeIE`
   instance - and with it `YoutubeIE._code_cache` (the downloaded player JS)
   and `YoutubeIE._player_cache` (`n`, `sigfuncs`, `sts`).
   <https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/yt_dlp/YoutubeDL.py>

2. **The `n` challenge is nevertheless re-solved on every request.**
   `solve_js_challenges()` builds its request list from the raw player
   response and submits *all* collected `n` challenges. Only the `s`/signature
   challenges consult the cache first
   (`_load_player_data_from_cache('sigfuncs', …, use_disk_cache=True)`), and the
   `n` result is stored with `use_disk_cache=False`, i.e. **in memory only**.
   A warm in-memory hit therefore does not remove the solver call.
   <https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/yt_dlp/extractor/youtube/_video.py>

3. **The solver spawns a runtime process per solve.**
   `BunJCP._run_js_runtime` runs
   `bun --bun run --no-addons --prefer-offline --no-install -` once per
   `bulk_solve` call, so one Bun process is started per extraction that carries
   a challenge.
   <https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/yt_dlp/extractor/youtube/jsc/_builtin/bun.py>

4. **A reused object freezes the format selector.**
   `YoutubeDL.__init__` builds `self.format_selector` once from
   `params['format']`. A callable `params['format']` is evaluated per request,
   which is the only public way to change the quality preference of a reused
   object.
   <https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/yt_dlp/YoutubeDL.py>

5. **A reused object also freezes the extractor arguments.**
   The extractor reads `extractor_args` from the `YoutubeDL` parameter
   snapshot (`InfoExtractor._configuration_arg` → `self._downloader.params`),
   so a reused object silently keeps the player client of its first request
   unless the worker rebuilds it or mutates the parameters in place.

6. **The upstream disk cache holds signature data, not `n` results.**
   `YoutubeIE._store_player_data_to_cache(…, 'sigfuncs', …, use_disk_cache=True)`
   writes public signature functions under `<cache>/youtube-sigfuncs/`, guarded
   by `min_ver='2025.07.21'`. `Cache.enabled` is false when `cachedir is
   False`, i.e. with `--no-cache-dir`.
   <https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/yt_dlp/cache.py>

7. **The lazy extractor registry is a build artefact.**
   `yt_dlp/extractor/extractors.py` uses `lazy_extractors.py` when it exists;
   the upstream release build generates it with
   `devscripts/make_lazy_extractors.py`. Without it, `YoutubeDL.__init__`
   imports every extractor module.
   <https://github.com/yt-dlp/yt-dlp/tree/2026.08.19/devscripts>

8. **Bun is deprecated upstream.**
   `_BUN_MAX_SUPPORTED_VERSION = (1, 3, 14)`; the shipped sidecar is 1.4.3, so
   yt-dlp reports it as unsupported (it still runs).

---

## 5. What a persistent worker would cost

### 5.1 Resident idle memory

A persistent worker is a process that stays alive between requests. Its idle
footprint is therefore paid for as long as the application runs, even when no
track is playing. The measured idle RSS/PSS is in §3.

### 5.2 Credential state reset

The shipped resolver passes the session as a Netscape cookie jar in a private
temporary directory plus `youtube:data_sync_id`, and never puts credentials in
argv. A persistent worker changes the lifecycle of that state:

* The worker tracks a **session fingerprint** (cookie jar path, size and
  modification time; proxy; account context; extractor arguments). A change of
  any of them rebuilds the `YoutubeDL` object, so a reused worker can never
  serve one account's session to another and can never keep a stale player
  client. In the measured run the reusing worker rebuilt its object 7 times
  for 24 requests, exactly at the player-client changes.
* The cookie values themselves are never hashed, copied or logged - only the
  jar's identity is compared.
* The cost of a reset is the `YoutubeDL` construction: 51 ms with the upstream
  lazy extractor registry, 203 ms without it (§3.4).
* The upstream `YoutubeDL` object keeps the cookie jar in memory. A credential
  reset therefore has to drop the whole object, not just the jar, to be free of
  residue.

### 5.3 Cancel and restart model

The shipped resolver cancels a request by killing the whole process group of
the yt-dlp process. With a persistent worker:

* yt-dlp has no public per-request cancellation hook. A cancelled request
  would either have to run to completion in the worker or the worker would have
  to be killed and restarted.
* Killing the worker costs a restart: interpreter start-up, import and
  `YoutubeDL` construction, i.e. 240 ms of start-up plus the first request's
  extraction (§3.3, the `cold` row).
* A worker that stays alive across a cancellation keeps the extractor
  instance, its player-JS cache and the HTTP connection pool, but it also
  keeps whatever the cancelled request left behind in memory.

### 5.4 Packaging implications

| option | Linux | Windows | size impact | risk |
|---|---|---|---|---|
| keep the frozen CLI (today) | shipped | shipped | one 40 MB file | none |
| persistent Python worker | needs a Python runtime + the yt-dlp and yt-dlp-ejs packages | same | runtime + site-packages on top of the current sidecar | a resident process, a new cancel model, a credential lifecycle |
| GraalVM native image of yt-dlp | not supported upstream | not supported upstream | would be small | yt-dlp is not Graal-friendly: the extractor registry is loaded dynamically, the EJS package is optional at runtime, and the upstream project does not ship or test a native image. Making it work would need upstream patches, which this evaluation does not do. |

The measured sizes are in §3 and in the JSON:

| artifact | size |
|---|---|
| frozen yt-dlp executable (the shipped sidecar) | 38.6 MiB |
| the pinned virtual environment | 61.9 MiB |
| of which the site-packages (yt-dlp, yt-dlp-ejs and the pinned deps) | 61.5 MiB |

The decisive point is that a Python worker does not replace the frozen
executable - it *adds* a Python runtime and the yt-dlp package tree to every
target, while the frozen executable already bundles its own transient
interpreter, the EJS package and the extractor registry in one
checksum-verified file. On a system that already has a Python runtime the
added size is smaller, but the pinned virtual environment is what this
evaluation measured.

An alternative that gets the 448 ms of the frozen bundle's start-up without
any resident process is to ship the **unpacked** upstream release file. The
shipped executable is a PyInstaller bundle (verified locally: it carries the
PyInstaller PKG archive markers), and the upstream release publishes a plain,
non self-extracting executable next to it; the parent task is evaluating that
file as a cheap start-up control. That is a packaging change only and needs no
worker; it was not measured here.

GraalVM native image of yt-dlp is not a viable option: yt-dlp is not
Graal-friendly (the extractor registry is loaded dynamically, the EJS package
is optional at runtime) and the upstream project neither ships nor tests a
native image. Making it work would need upstream patches, which this
evaluation does not do.

---

## 6. Two upstream-compatible details that decide the outcome

### 6.1 The lazy extractor registry

A plain `pip install` of yt-dlp does not contain `lazy_extractors.py`, so
`YoutubeDL.__init__` imports every extractor module. The upstream release
build contains it. The difference decides whether a "new `YoutubeDL` per
request" worker is cheap or expensive: the construction costs 51 ms with the
upstream lazy registry and 203 ms without it (§3.4). The measured
`persistent_python_new_ydl` configuration is the one that pays the
construction on every request, and it is still 198 ms faster than a fresh
process on the default-client workload - but a construction without the lazy
registry would add 152 ms to every request.

### 6.2 The `n` challenge is not cacheable upstream

Because `solve_js_challenges()` always submits the `n` challenges and the `n`
result is stored in memory only, a warm worker cannot skip the solver. The
measurement confirms it: one Bun start per challenged request in every
configuration, warm repeats included (§3.7). The only way to avoid the Bun
process would be an upstream change, which is out of scope for this
evaluation (no maintained custom yt-dlp extraction or challenge patch).

### 6.3 The format selector is frozen at construction

`YoutubeDL.__init__` builds the format selector once. A reused worker
therefore cannot simply change `params['format']`; the worker uses a callable
format selector (public API) that re-builds the selector for the quality of
the *current* request. This was verified against the shipped selector
semantics: `HIGH` selects the `abr<=160` audio format, `LOW` the `abr<=70`
one, `HIGHEST` the best one.

The same freeze applies to the **extractor arguments**: the extractor reads
`extractor_args` from the `YoutubeDL` parameter snapshot, so a reused object
silently keeps the player client of its first request. The worker's
invalidation fingerprint therefore covers the extractor arguments as well as
the credentials; in the measured run the reusing worker rebuilt its object 7
times for 24 requests, exactly at the player-client changes.

---

## 7. The application's own resolved-URL cache (a separate axis)

The application already caches resolved stream URLs, so the resolver request
path is only taken on a cache miss. That cache is independent of the worker
question and was not changed by this evaluation:

* Key: `mediaId`, `quality`, `networkMetered`, `purpose`
  (playback/download), `authFingerprint`, `pinnedFormatId`,
  `requiresSongMetadata`
  (`app/.../playback/stream/ResolveAudioStreamUseCase.kt`).
* Freshness: `expiresAtMs > now + 60 s` safety window; at most 256 entries,
  evicted by expiry.
* Boundaries, explicitly:
  * **expiry** - the signed URL's own `expire` parameter. Every resolved URL in
    this evaluation carried a lifetime of 21598-21600 s (about six hours), and
    the cache only serves it while more than a minute remains. A URL without
    an `expire` parameter receives the conservative five-minute lifetime that
    the resolver already applies.
  * **authentication** - `authFingerprint`; a different account or session
    never reuses another account's URL.
  * **quality** - the quality name is part of the key, so a `LOW` request
    never reuses a `HIGHEST` URL.
* The resolved URLs in this evaluation always selected format `251` (audio
  only, WebM/Opus) and carried the headers `accept`, `accept-language`,
  `sec-fetch-mode` and `user-agent`. 18 of the 72 resolved URLs carried the
  `n` parameter (the challenged workload); none carried a PO token.
* Consequence for this evaluation: a persistent worker only helps the
  *miss* path (first play of a track, after an expiry or after an
  invalidation). It cannot help a cached hit, because a cache hit never
  reaches the resolver at all.

---

## 8. Caveats

* Small sample. The rounds are few and the numbers are observational, not a
  statistical benchmark. Differences of a few dozen milliseconds are noise;
  the report states the sample size next to every percentile. The `cold` row
  of each persistent worker is a **single** sample and is not a steady-state
  number.
* Two measurement sessions. `frozen_cli` and `persistent_python_new_ydl` come
  from the targeted re-measurement (2026-10-21T11:40:04Z .. 11:41:34Z),
  `fresh_python` and `persistent_python_reuse_ydl` from the main session
  (finished 2026-10-21T11:29:58Z). The sessions are listed in the JSON with
  their provenance, and the control was re-measured inside the re-measurement
  session so that the comparison against it is internally consistent. The
  cross-session comparison of the two persistent workers with each other
  inherits that difference.
* One row of the main session was **invalid** and is excluded: the
  "new `YoutubeDL` per request" configuration ran in reusing mode because of a
  truthy string flag. It was superseded by the re-measurement, the bug is
  fixed, and the offline regression fixture `--self-check` now covers it.
* Anonymous extractions only. A signed-in session changes the client
  selection, the response size and possibly the challenge behaviour. The
  signed-in whole-application benchmark stays with the parent task.
* The media probes are ranged GETs of 64 KiB at the head and the tail of the
  object. They prove that the resolved URL serves real media bytes with the
  headers yt-dlp returned; they are not a full playback test.
* The memory samplers poll every 50 ms. A spike that lives entirely between
  two samples is not observed, so the reported peaks are lower bounds. Tree
  RSS also counts shared pages once per mapping process; PSS divides them.
* The challenge solver is counted through a shim, which adds one `sh` process
  per invocation to every configuration equally.
* Upstream marks Bun support as deprecated and reports versions above 1.3.14
  as unsupported. The shipped Bun 1.4.3 runs, but that is an experimentally
  verified combination, not an upstream support guarantee.
* The attribution in §3.10 separates the three cost sources that the
  configuration ladder exposes (bundle start-up, process start-up, object
  caches). It does **not** separate the player-JS download from the HTTP
  connection reuse inside the object-level part, because upstream keeps both
  in the same object.

---

## 9. Reproduction

```bash
# pinned, isolated environment (git-ignored, nothing installed globally)
python3 -m venv .tools/ytdlp-worker-venv
.tools/ytdlp-worker-venv/bin/python -m pip install yt-dlp-ejs==0.8.0
# upstream sources: https://github.com/yt-dlp/yt-dlp/releases/download/2026.08.19/yt-dlp.tar.gz
.tools/ytdlp-worker-venv/bin/python -m pip install ./yt-dlp-2026.08.19/yt-dlp
# upstream lazy extractor registry (as the upstream release build does)
cd yt-dlp-2026.08.19/yt-dlp && python devscripts/make_lazy_extractors.py \
    "$(../../ytdlp-worker-venv/bin/python -c 'import yt_dlp,os;print(os.path.dirname(yt_dlp.__file__))')/extractor/lazy_extractors.py"

# the measurement
scripts/benchmark-ytdlp-worker.py \
    --yt-dlp .tools/resolver/yt-dlp \
    --bun    .tools/resolver/bun \
    --venv   .tools/ytdlp-worker-venv \
    --repeats 3 \
    --out-json packaging/resolver/validation/linux-ytdlp-worker-evaluation.json

# re-measure a single configuration and merge it into the report
scripts/benchmark-ytdlp-worker.py \
    --yt-dlp .tools/resolver/yt-dlp \
    --bun    .tools/resolver/bun \
    --venv   .tools/ytdlp-worker-venv \
    --repeats 3 --configs frozen_cli,persistent_python_new_ydl \
    --session-id "rerun-..." --session-note "why this session exists" \
    --merge-into packaging/resolver/validation/linux-ytdlp-worker-evaluation.json \
    --out-json packaging/resolver/validation/linux-ytdlp-worker-evaluation.json

# offline regression fixture of the harness itself (never touches the network)
scripts/benchmark-ytdlp-worker.py --venv .tools/ytdlp-worker-venv --self-check

# plumbing test that cannot reach YouTube (every configuration is stubbed)
scripts/benchmark-ytdlp-worker.py \
    --yt-dlp .tools/stub/yt-dlp-stub.py \
    --bun    .tools/resolver/bun \
    --venv   .tools/ytdlp-worker-venv \
    --repeats 1 --offline-stub \
    --out-json /tmp/offline-report.json
```

The harness exits non-zero when an extraction fails, when a successful result
does not pass the head/tail range probe, or when a process is left behind.

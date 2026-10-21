# yt-dlp + Bun pipeline profile

Date: 2026-10-21  
Branch: `feature/ytdlp-pipeline-profile`  
Scope: developer-only measurement of the Linux resolver pipeline. No production Kotlin,
default resolver architecture, cookies, or user session state was changed.

Artifact: `packaging/resolver/validation/linux-ytdlp-pipeline-profile.json`  
Harness: `scripts/profile-ytdlp-pipeline.py`  
Pinned tool copies used for measurement live under the ignored `.tools/` directory.

## Decision summary

- **Keep the current production/default behavior until the product decision is explicit.**
  The measured fast path is promising, but this corpus is not proof for every video,
  region, client, or future YouTube response.
- **`skip=hls,dash` + `player_skip=configs` with the webpage kept is safe enough for a
  bounded anonymous “visionos fast path” only when every failure falls back upstream.**
  It passed 30/30 timed runs and 4/4 one-shot coverage runs with no challenge markers in
  this window. It is upstream-supported, but it is not a universal safety proof, and it
  changes which formats are available by skipping manifests.
- **Do not skip the webpage.** `player_skip=webpage` failed `jNQXAC9IVRw` and
  `aqz-KE-bpKQ` with `LOGIN_REQUIRED`; adding an explicit anonymous `visitorData` did
  not fix those videos. One of three tested videos succeeded with webpage skipped, so
  the failure is input-dependent, not a clean capability gap.
- **The cookie-capable path must remain the full upstream path.** Use
  `web_embedded,tv_downgraded,web` (or the application’s existing ordered client set),
  keep cookies untouched, and let upstream solve the real n challenge. Upstream
  `visionos` has no cookie support, so it cannot replace an authenticated request.
- **Compact `--print` is not the main win by itself.** The apparently large
  dumpjson-to-print difference is dominated by skipping HLS/DASH manifest requests and,
  on `dQw4w9WgXcQ`, avoiding the web-client JS challenge. Output/format-selection stages
  differ by only ~10–20 ms in the medians.
- **An empty `--ffmpeg-location` directory is worth a resolver-level retest, not a
  default change yet.** It preserved the URL-only output in every tested pair with no
  warning, and the paired median favored it by ~131 ms, but the aggregate p50 differed
  by only ~5 ms and the paired range crossed zero. It also hides ffmpeg/ffprobe from
  yt-dlp, so it is only acceptable for URL-only extraction with no merge/postprocess.
- **The upstream cache did not improve the measured wall time.** It was left enabled only
  as a measurement variant; it still solved the challenge on `dQw4w9WgXcQ` every repeat.
  Do not add cache maintenance on the basis of this profile.
- **No custom extraction, n/signature transform, challenge patch, or cookie stripping
  is justified by this evidence.**

## Toolchain and corpus

All timed CLI runs use the frozen Linux PyInstaller build and its staged Bun runtime.

| Component | Version | SHA-256 |
|---|---|---|
| Frozen `yt-dlp` | 2026.08.19 | `58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a` |
| Bundled Bun | 1.4.3 | `7ce7d6b654eddeec20afddf2396317fba6dc19ab52788a27e894e1806bf8f24b` |
| `yt-dlp-ejs` | 0.8.0 | pinned in the frozen build and the local venv |
| Local comparison venv | Python 3.14.7 + yt-dlp 2026.08.19 + ejs 0.8.0 | equivalent pinned source |

Pinned local venv used only for the frozen-vs-source comparison and the
developer API smoke. It is not part of the shipped resolver.

Corpus:

- Timed main corpus: `jNQXAC9IVRw`, `dQw4w9WgXcQ`, `aqz-KE-bpKQ`,
  `kJQP7kiw5Fk`, `OPf0YbXqDm0`.
- Additional coverage: `fJ9rUzIMcZQ`, `hTWKbfoikeg`, `YQHsXMglC9A`,
  `9bZkp7q19f0`.
- All requests were anonymous public watch URLs. No account cookies, age-restricted,
  members-only, premium, or regional-auth content was used.

## Method

### Stage boundaries

The frozen CLI emits verbose marker lines through the harness pipe. Timestamps are
taken when the line is read, not from yt-dlp’s own clock. The stage parser uses:

- process spawn to first `[debug] Command-line config:` line: **boot/PyInstaller**;
- first diagnostic to `Loaded N extractors`: **Python init/extractor imports**;
- extractor load to `Extracting URL:`: **dispatch**;
- `Extracting URL:` to format selection: **extraction**;
- format selection to `Downloading N format(s)`: **format selection**;
- after format selection to process exit: **output**.

Request windows start at the request marker (`Downloading webpage`, player API, m3u8,
etc.) and run until the next marker. They therefore **include parsing and solver startup**;
they are not pure socket time. `extraction_ms` is the wider
`Extracting URL` → format-selection partition.

The partitions do not overlap. Stage medians are separate per-stage medians, so adding
them does not necessarily reproduce the wall median exactly.

### Sampling and quantiles

- Timed matrix: 5 videos × 5 configs × 6 successful-block repeats = 150 rows.
- One additional clean rerun block of 50 rows replaced contaminated repeats.
- Every config/video pair has 6 attempted rows and an attempted/succeeded/failed
  denominator. The cookie-capable config has 3 real failures and is reported as 27/30,
  not silently reduced to a “successful samples” denominator.
- Quantiles are Hyndman–Fan type 7 over successful rows only. p50/p90/p95/max are
  reported, and `n` is always the successful count. With n=30, p95 is close to max and
  should be read as a small-sample upper-tail estimate, not a population guarantee.
- Config and video order were shuffled with seed `20261021`; the clean rerun used the
  same seed and its own batch label. The cache variant was warmed before timed blocks.
- Each CLI row is a **new process**. “Warm” means repeated runs with the OS page cache
  and network caches already populated, not an in-process object.

### Cold/warm definition

The cold block calls `posix_fadvise(DONTNEED)` on the frozen yt-dlp and Bun files before
the run, interleaved with warm runs of the same config. DNS, TLS session state, CDN
connection reuse, and the page cache for shared libraries are **not** evicted. This is a
best-effort binary-cold measurement, not a fresh-machine boot measurement.

### Contamination and rerun

The worker disclosed a live plumbing check during the first matrix:

- conservative overlap window: `2026-10-21T11:11:05.750Z..2026-10-21T11:11:39.236Z`;
- 15 live YouTube extractions and 13 Bun invocations, serialized; the worker discarded
  its numbers.

Original matrix repeats 0 and 1 were excluded **wholesale**, not row-by-row, because the
first harness revision did not store per-run UTC timestamps. A clean 2-repeat rerun
(50 rows, batch `rerun-clean`) replaced those repeats. Base rows from repeats 2–5 and all
cold/venv/coverage runs are retained; failed rows are never removed. The exact window,
reason, replacement policy, and retained-failure statement are in the artifact’s
`exclusions` field.

Per-run UTC timestamps are present on rerun rows. They are absent on the retained first-
revision matrix rows; those rows are not used to claim exact non-overlap. No timestamps
were fabricated.

### Redaction

No signed media URLs, cookies, visitor values, authorization headers, request arguments
containing secrets, or raw JSON bodies are stored. The artifact keeps stage labels,
times, format IDs, header names/counts, booleans for `n`/signature presence, and probe
status/magic only. The visitorData experiment passed the value through a mode-0600
temporary config file and deleted it; the value never entered argv or this report.

## Main results

### Wall time by config

All rows are 30 attempts per config, six per video. `p50`/`p90`/`p95`/`max` are over
successful rows; the attempted p50 is shown for comparison. Times are milliseconds.

| Config | Attempts | Succeeded | p50 | p90 | p95 | max | Attempted p50 |
|---|---:|---:|---:|---:|---:|---:|---:|
| `frozen_baseline_dump` | 30 | 30 | 2420 | 3126 | 3171 | 3173 | 2420 |
| `frozen_print_skip` | 30 | 30 | 1844 | 2520 | 2521 | 2620 | 1844 |
| `frozen_visionos_safe` | 30 | 30 | 1819 | 2025 | 2097 | 2120 | 1819 |
| `frozen_authed_clients` | 30 | 27 | 2921 | 3192 | 3222 | 3322 | 2921 |
| `frozen_print_skip_cache` | 30 | 30 | 1844 | 2575 | 2648 | 2871 | 1844 |

What the configs mean:

- `frozen_baseline_dump`: current dump-single-json shape, default clients, fetches
  HLS/DASH manifests.
- `frozen_print_skip`: compact `--print` plus `skip=hls,dash`.
- `frozen_visionos_safe`: `player_client=visionos`, webpage kept,
  `player_skip=configs`, `skip=hls,dash`.
- `frozen_authed_clients`: `web_embedded,tv_downgraded,web`, no cookies in this profile;
  upstream challenge solving active.
- `frozen_print_skip_cache`: same as `frozen_print_skip` with the standard upstream
  cache directory enabled.

The visionos path improves p50 by 25 ms over print+skip and improves p90 by 495 ms and
p95 by 424 ms because it removes the `dQw4w9WgXcQ` challenge tail. It is not a large
median win over print+skip on all videos.

### Per-video p50

| Video | Baseline dump | Print + skip | Visionos safe | Cookie-capable |
|---|---:|---:|---:|---:|
| `OPf0YbXqDm0` | 2370 | 1869 | 1868 | 3022 (6/6) |
| `aqz-KE-bpKQ` | 2395 | 1819 | 1743 | 2771 (5/6) |
| `dQw4w9WgXcQ` | 3146 | 2520 | 1894 | 2971 (5/6) |
| `jNQXAC9IVRw` | 2069 | 1769 | 1669 | 2621 (5/6) |
| `kJQP7kiw5Fk` | 2420 | 1844 | 1918 | 2946 (6/6) |

The cookie-capable values are p50 over successes only; the failed rows are counted in
the 5/6 entries. The three failures were all `FORMAT_UNAVAILABLE` on repeat 2 for
`dQw4w9WgXcQ`, `jNQXAC9IVRw`, and `aqz-KE-bpKQ`. They were not login/session failures and
were not relabeled as successes. A transient format response is possible; the profile
does not prove an account-level cause.

### Stage budget

Medians over successful rows. `init` is Python/extractor import; `dispatch` is extractor
load to extraction start; `request sum` is the sum of inclusive request windows.

| Config | boot | init | dispatch | extraction | request sum | format select | output |
|---|---:|---:|---:|---:|---:|---:|---:|
| Baseline dump | 419.5 | 156.9 | 31.7 | 1662.0 | 1661.9 | 22.4 | 104.1 |
| Print + skip | 421.0 | 157.5 | 32.0 | 1127.5 | 1127.3 | 15.1 | 93.3 |
| Visionos safe | 421.0 | 157.2 | 31.8 | 1101.5 | 1101.4 | 15.3 | 86.4 |
| Cookie-capable | 420.4 | 157.7 | 31.9 | 2195.9 | 2195.7 | 14.9 | 90.9 |
| Print + skip + cache | 420.0 | 157.2 | 31.7 | 1137.6 | 1137.4 | 15.1 | 87.6 |

The stable pre-network floor is roughly:

- frozen process/startup floor (`--version`, p50): **464.9 ms**;
- first verbose diagnostic marker: **~421 ms**;
- Python + extractor imports: **~157 ms**;
- extractor dispatch: **~32 ms**.

So roughly **610 ms** is spent before extraction begins. The frozen `--version` floor is
~300 ms above the local venv’s module startup, consistent with the PyInstaller one-file
bootstrap. `frozen_help` was 515.0 ms p50; `venv_python_pass` was 15.7 ms; importing
yt-dlp in the venv was 114.2 ms and `python -m yt_dlp --version` was 164.1 ms.

### Bun and challenge cost

The real challenge is present and was measured, not inferred from a synthetic shim.

- Baseline dump challenged `dQw4w9WgXcQ` in 6/6 runs; the other four videos had no
  challenge marker.
- Print+skip challenged `dQw4w9WgXcQ` in 6/6 runs.
- Visionos safe had **0 challenge markers in 30/30 runs**.
- Cookie-capable had challenge markers in 27/27 successful runs, plus the failed retries.
- Cache variant challenged `dQw4w9WgXcQ` in 6/6 and `kJQP7kiw5Fk` once in six.

For challenge-bearing runs:

| Config | Solve window p50 | Bun observed wall p50 | Bun CPU user p50 | Bun CPU sys p50 |
|---|---:|---:|---:|---:|
| Baseline dump (dQw4, n=6) | 600 ms | 476 ms | 1055 ms | 180 ms |
| Print+skip (n=6) | 560 ms | 431 ms | 860 ms | 60 ms |
| Cookie-capable (n=27) | 556 ms | 436 ms | 870 ms | 70 ms |
| Cache variant (n=7) | 580 ms | 511 ms | 1230 ms | 270 ms |

Bun’s own no-op startup is ~3.4 ms and `bun --version` ~1.4 ms p50, so the ~0.4–0.6 s
window is challenge execution, not Bun process startup. CPU time exceeding wall time is
consistent with sampled multi-threaded work and a separate process tree; these CPU
samples are not additive to wall time.

Peak tree RSS was ~108 MB for visionos, ~110 MB for baseline/print, and ~426 MB for the
cookie-capable challenge path. That is a material difference for an embedded desktop
resolver, independent of wall time.

### Frozen vs pinned local venv

Interleaved subset, 15 attempts per config (three videos).

| Config | p50 | p90 | max |
|---|---:|---:|---:|
| Frozen print+skip | 1769 | 2550 | 2721 |
| Venv print+skip | 1517 | 2400 | 2570 |
| Frozen visionos safe | 1819 | 2169 | 2220 |
| Venv visionos safe | 1468 | 1719 | 1768 |

The frozen PyInstaller package costs roughly 250–350 ms in p50 versus the equivalent
pinned source venv in this comparison. That is a packaging/startup cost, not a reason to
maintain custom extraction code.

### Cold binary block

Two videos, five runs each, interleaved warm/cold. Eviction succeeded in 2/2 cold runs
per video.

| Video | Warm p50 | Evicted-binary p50 | Difference |
|---|---:|---:|---:|
| `jNQXAC9IVRw` | 1719 | 1794 | +75 ms |
| `dQw4w9WgXcQ` | 2570 | 2595 | +25 ms |

This is a lower bound on cold-start cost: only yt-dlp/Bun file pages were evicted, not
DNS, TLS, CDN, shared libraries, or the parent OS state. It does not support a large
cold-start optimization claim.

### Cache variant

- Cache inventory after the block: 3 files, 1,355 bytes.
- Wall p50 unchanged from print+skip (1844 vs 1844); p90 slightly worse (2575 vs 2520).
- `dQw4w9WgXcQ` still challenged and solved on every repeat.
- The upstream cache stores solver results but disables the preprocessed-player disk
  cache in this version; repeated requests still hit YouTube network latency.
- No evidence justifies adding cache maintenance or assuming warm challenge reuse.

### Startup floor and instrumentation

The frozen build’s pre-network floor is measurable and stable; the extraction stage is
network-dominated. A separate developer-only API smoke on `dQw4w9WgXcQ` with the
visionos config (one sample, cProfile and wrapped `urlopen`) recorded:

- two network requests totaling **1069 ms**;
- `extract_info` cumulative **1541 ms** and `extract` cumulative **1475 ms** under
  cProfile;
- non-request extraction **472 ms** (~32% of the profiled extraction);
- JSON dump **2.1 ms**.

This smoke is not a distribution: cProfile and the wrapper inflate absolute times, and
one sample cannot separate parsing from solver startup robustly. It is included only to
show the rough network-vs-processing split; the CLI request windows remain inclusive of
parsing. Metadata/format processing in the CLI is small: format selection ~15–22 ms and
output ~86–104 ms; the large extraction values are network plus upstream parsing and
challenge work.

### Coverage and playable URL probes

Additional one-shot coverage with print+skip and visionos safe:

| Config | Attempts | Success | p50 |
|---|---:|---:|---:|
| Print+skip, 4 extra IDs | 4 | 4 | 1919 |
| Visionos safe, 4 extra IDs | 4 | 4 | 2295 |

The four IDs were `fJ9rUzIMcZQ`, `hTWKbfoikeg`, `YQHsXMglC9A`, and `9bZkp7q19f0`.
These are one sample each; the visionos p50 being higher than print+skip here is not
used as a ranking result. Both configs succeeded on all four.

Probe behavior: 25 first-success probes (one per video/config pair), all **25/25 ok**.
Each sent `Range: bytes=0-65535` head and a separate tail range, received HTTP **206**
with `Content-Range`, read 65,536 bytes at each end, and matched media magic
(`1a45dfa3…` WebM or `00000018…ftyp` MP4). Median probe duration was 66 ms. Media types
were `audio/webm` and `audio/mp4`; the selected format was 251 in the anonymous profile.
The probe proves a ranged response with media bytes, not audible playback.

In all 49 successful frozen-visionos rows (main, comparison, and coverage), the URL had
no `n` query parameter and did have a signature query; this is consistent with a direct
visionos URL path, but it is not proof that every future response can bypass upstream
transforms. Do not strip signature/query parameters or custom-transform URLs.

### Negative and positive controls

| Control | Result |
|---|---|
| Real positive challenge (`dQw4w9WgXcQ`, challenge-bearing config) | 2/2 succeeded; Bun present; solve windows 626/712 ms; EJS 0.8.0 |
| Broken solver shim | 2/2 failed as expected with `JS_CHALLENGE_SOLVER_FAILED`; 4 solver invocations |
| Missing JS runtime | 2/2 failed as expected with `JS_CHALLENGE_SOLVER_FAILED` |
| Loopback 403 fixture | HTTP 403, `http_error`, failed as expected; no external request |
| Visionos without webpage | 3 attempts: 1 success, 2 `LOGIN_REQUIRED`; unsafe default |

The 403 and solver controls show that the harness can observe real failures rather than
silently classifying all runs as successes. The visionos-without-webpage control is the
same failure pattern seen with explicit visitorData, below.

## visitorData experiment

Motivation: the application session may already have a visitorData value. If an explicit
anonymous visitor value made `player_skip=webpage,configs` reliable, the fast path could
avoid the webpage request without inventing data or stripping cookies.

Procedure:

1. Fetch the anonymous watch page in memory with a normal browser-like user agent and no
   cookies.
2. Read the page’s exact `visitorData` value; do not derive, synthesize, or modify it.
3. Write it to a mode-0600 temporary yt-dlp config loaded with
   `--config-locations`; delete the file in a `finally` block.
4. Run `player_client=visionos;player_skip=webpage,configs;skip=hls,dash` with no
   cookies.
5. Record only setup/extraction timings and outcome labels; never store the value.

| Video | Page setup | Extraction | Result |
|---|---:|---:|---|
| `jNQXAC9IVRw` | 773 ms | 1886 ms | `LOGIN_REQUIRED` |
| `aqz-KE-bpKQ` | 752 ms | 1474 ms | `LOGIN_REQUIRED` |
| `dQw4w9WgXcQ` | 942 ms | 1719 ms | success |

The explicit anonymous visitor value did **not** make webpage-skipping reliable: two of
three videos still hit the bot/login gate. The one success still cost ~2.66 s including
setup, slower than the 1.89 s warm visionos-safe path for the same video. If the app
already has visitorData, the setup term can be zero, but the reliability failure remains.

Implications:

- Keep the webpage in the anonymous visionos path.
- Do not pass visitorData with `player_skip=webpage` as a production shortcut.
- VisitorData is not a cookie substitute and does not make `visionos` cookie-capable.
- If the application forwards existing authenticated context, that belongs on the
  cookie-capable upstream path, not the anonymous visionos path; do not strip cookies to
  make the fast path look better.

## `--ffmpeg-location` experiment

Candidate: point yt-dlp at an **empty private directory** for URL-only progressive audio,
so it cannot discover ffmpeg/ffprobe and does not spend time on capability probes.

Test: 12 pairs (three videos × four repeats), random interleaved, visionos-safe flags,
URL-only `--print`, no download or postprocessing. Both arms succeeded 12/12 with identical
format `251` / `webm` / `https`.

| Arm | p50 | min | max |
|---|---:|---:|---:|
| Normal ffmpeg discovery | 1705 ms | 1565 ms | 2134 ms |
| Empty `--ffmpeg-location` | 1700 ms | 1201 ms | 1906 ms |

Aggregate p50 differs by only ~5 ms, but the paired difference (same video/repeat) had a
median of **130.6 ms** in favor of the empty location (mean 109.9 ms), with 9/12 pairs
positive and a range of −243 to +442 ms. The sign and magnitude are too noisy for a
production claim; network variance dominates.

Warnings/toolchain implications:

- No warning was emitted in these URL-only runs, and no ffmpeg/ffprobe version line
  appeared. That is expected but makes the debug report less diagnostic.
- Any future need to merge, remux, transcode, extract thumbnails, or postprocess will
  fail or behave differently when yt-dlp cannot find ffmpeg. Keep ffmpeg in the
  distribution and do not set this globally.
- Use it only on a URL-only extraction path that has no postprocessing; keep a separate
  explicit ffmpeg capability check for any download path.
- The empty directory must be private and actually exist. A nonexistent path can fall
  back to PATH discovery, defeating the experiment and making behavior path-dependent.
- Treat the ~131 ms as a hypothesis to confirm in the resolver’s paired harness, not as
  an established optimization.

## Compact-print projection and parser compatibility

The exact compact projection that was timed in the matrix is **pipe-delimited text**, not
a JSON info dict:

```text
%(id)s|%(format_id)s|%(ext)s|%(acodec)s|%(abr)s|%(protocol)s|%(http_headers)j|%(url)s
```

It contains `id`, `format_id`, `ext`, `acodec`, `abr`, `protocol`, `http_headers`, and
`url`. It does **not** contain `vcodec`, `title`, or `duration`. The current
`parseYtDlpAudio` requires `vcodec == "none"` and reads `title`/`duration` as optional
metadata, so this tested output is **not parser-compatible without an adapter**. It was
useful for timing the extraction shape, but it is not a drop-in replacement for
`--dump-single-json`.

A parser-compatible JSON projection is possible. A local-only check against a saved info
JSON, using `-f bestaudio[protocol=https]` and no network request, validated this
candidate template:

```text
{id,title,duration,format_id,ext,vcodec,acodec,protocol,http_headers,url,uploader,channel_id,abr,asr,audio_channels,filesize,format_note}
```

It produced a single JSON object with all keys present, selected format
`251 / webm / vcodec=none / opus / https`, and the metadata parser needs:

- `id`, `title`, `duration`;
- `format_id`, `ext`, `vcodec`, `acodec`, `protocol`, `http_headers`, `url`;
- optional `uploader`, `channel_id`, `abr`, `asr`, `audio_channels`, `filesize`,
  `format_note`.

The saved-info validation did **not** perform a live request, did **not** time the
projection, and did **not** run the JVM parser against it. It is a schema check, not a
production validation.

Key-absence semantics, which matter to the current parser:

- In the tested `--dump-single-json` output, `_type` was present as `"video"`.
- `requested_formats` and `entries` were **absent**, not present with `null` values.
- In the JSON-projection candidate, `_type`, `requested_formats`, and `entries` are all
  omitted entirely. They are absent, not null. `parseYtDlpAudio` defaults an absent
  `_type` to `"video"`, and its guard is `!has("requested_formats") && !has("entries")`,
  so omission satisfies it.
- Do not add `requested_formats` or `entries` as null to the projection; that would be a
  presence and would fail the current guard.

The measured gain from compact output by itself is small: the output stage was ~104 ms
for dumpjson and ~93 ms for print (+/-), while the 576 ms p50 improvement came mainly
from skipping HLS/DASH requests and the `dQw4w9WgXcQ` challenge rather than from JSON
serialization. **Keep dumpjson for now**; the current production `skip=hls,dash` change is
the measured, lower-risk optimization. If the JSON projection is adopted later, add a
resolver fixture/unit test for the exact key set and one live resolver validation with
the audio-only selector, and keep a dumpjson fallback.

## Flag and architecture assessment

| Change | Measured effect | Risk / caveat | Recommendation |
|---|---|---|---|
| `--print` instead of dumpjson | Output stage only ~11 ms lower; overall gain is mostly elsewhere | Changes output contract and parser requirements | Keep dumpjson unless resolver already needs only format/URL; do not switch for the headline number |
| `skip=hls,dash` | Removes m3u8 requests; part of 576 ms p50 drop | Removes HLS/DASH formats; can change available audio quality/format for some videos | Use only if progressive audio is acceptable and verified per video; keep manifests otherwise |
| `player_skip=configs` | No challenge on visionos corpus; small stage reduction | May omit client/config data needed in other regions or future responses | Allowed with webpage kept and any-error fallback; not a universal default |
| `player_skip=webpage` | Sometimes works (`dQw4`), fails others (`jNQ`, `aqz`) | Bot gate; explicit visitorData did not fix it | Reject |
| Visionos-only fast path | 30/30 success, 0 challenges, p90 2025 | Empirical corpus only; visionos cannot use cookies; direct URLs may evolve | Tier 1 anonymous fast path with full fallback; keep webpage |
| Cookie-capable clients | 27/30, p50 2921; real challenge CPU and ~426 MB RSS | Slower and memory-heavy; required for auth | Tier 2 fallback; no custom challenge patch; cookies untouched |
| Upstream cache | No wall-time benefit; 3 tiny files; challenge still solved | Adds state and cleanup without measured gain | Do not adopt on this evidence |
| Empty `--ffmpeg-location` | Paired median ~131 ms faster, aggregate ~5 ms; 12/12 valid | Hides ffmpeg; only URL-only; noisy | Resolver-level confirmation first; never for postprocessing |
| Custom n/signature/challenge code | Not needed; upstream solver works | Maintenance and correctness risk | Do not add |

### Direct-only VISIONOS path

A direct-only existing visionos path is **consistent** with the measurements: all
successful visionos rows used progressive HTTPS format 251, none carried an `n`
parameter, no challenge marker appeared, and the head/tail probes succeeded. That is
useful evidence for avoiding eager web-proof or custom cipher work.

It is not a proof that the path is always transform-free. The URL still carried a
signature query, and YouTube can change player responses by client, region, video, or
date. The safe design is therefore:

1. Try the existing upstream visionos extraction with the webpage kept.
2. Do not apply custom n/cipher transforms; preserve the upstream URL.
3. On any rejection, missing format, challenge marker, or unexpected error, fall back to
   the full upstream cookie-capable path.
4. Keep cookies, session context, and signed URL handling untouched in the fallback.

## Recommendation

**Adopt a two-tier design only after a product owner accepts the corpus limits:**

- **Tier 1 (anonymous):** upstream `player_client=visionos`, webpage kept,
  `player_skip=configs`, `skip=hls,dash`; no custom solve/transform code; strict timeout
  and any-error fallback.
- **Tier 2 (cookie-capable):** full upstream client order
  (`web_embedded,tv_downgraded,web` or the existing app order), cookies and session
  context unchanged, upstream challenge solving, full manifests unless the product
  explicitly accepts progressive-only audio.
- Keep dumpjson as the default until the resolver contract is checked. Compact print is
  a real optimization only if the consumer does not need the full JSON; it is not the
  dominant measured cost.
- Keep `player_skip=webpage` out of production.
- Do not add the cache, custom n/signature code, or automatic cookie stripping.
- Treat empty `--ffmpeg-location` as a separately gated URL-only optimization and
  re-measure it with the resolver’s real process launch and host conditions.

The direct answer to “is `skip=hls,dash` + `player_skip=configs` proven safe enough for
production?” is: **proven enough for a fallback-guarded anonymous experiment on this
corpus, not proven as a universal production default.** The 30/30 and 4/4 results are
useful, but the corpus is nine public videos, one host, one time window, and no
authenticated/regional/age-gated cases. The format-availability caveat of `skip=hls,dash`
is separate from the challenge question.

## Limitations

- No cookies, account context, age-restricted, members-only, premium, or regional videos.
- No JVM/application scheduling, libVLC playback, or parent whole-app budget.
- YouTube responses, bot checks, player JS, and CDN latency vary by request and window.
- Visionos has no cookie support upstream; it cannot stand in for authenticated
  extraction.
- The cache experiment exercises only upstream n/sig solver reuse; no player cache is
  enabled.
- The cold block evicts only two binaries; DNS/TLS/CDN and shared libraries remain warm.
- Small n makes p95 close to max. The 5-video main corpus is not a population sample.
- The visitorData experiment is three one-shot samples; it establishes a failure mode,
  not a success rate.
- The ffmpeg-location experiment is 12 pairs on one host; paired direction is promising
  but noisy.
- Probe success proves a ranged head/tail response with media magic, not audible
  playback.
- Base matrix rows from repeats 2–5 lack per-run UTC timestamps; they were kept only
  after whole-repeat conservative exclusion, and are not used to prove exact
  non-overlap.

## Reproduction

All commands use the ignored, pinned local copies; no global install or production
change.

```sh
# Timed main matrix (example; corpus and configs are explicit in the harness).
python3 scripts/profile-ytdlp-pipeline.py \
  --mode cli \
  --videos jNQXAC9IVRw,dQw4w9WgXcQ,aqz-KE-bpKQ,kJQP7kiw5Fk,OPf0YbXqDm0 \
  --configs frozen_baseline_dump,frozen_print_skip,frozen_visionos_safe,frozen_authed_clients,frozen_print_skip_cache \
  --repeats 6 --seed 20261021 \
  --out /tmp/opencode/ytprof/matrix.json

# Clean replacement block after the disclosed contamination interval.
python3 scripts/profile-ytdlp-pipeline.py \
  --mode cli \
  --videos jNQXAC9IVRw,dQw4w9WgXcQ,aqz-KE-bpKQ,kJQP7kiw5Fk,OPf0YbXqDm0 \
  --configs frozen_baseline_dump,frozen_print_skip,frozen_visionos_safe,frozen_authed_clients,frozen_print_skip_cache \
  --repeats 2 --seed 20261021 --batch rerun-clean --skip-extras \
  --out /tmp/opencode/ytprof/rerun.json

# The checked-in artifact was assembled by merge_report(base, rerun, exclude_below=2);
# the artifact records the excluded window and batch provenance.

# Developer-only API smoke / cProfile mode.
python3 scripts/profile-ytdlp-pipeline.py --mode api \
  --config frozen_visionos_safe --video dQw4w9WgXcQ \
  --yt-dlp .tools/yt-dlp --bun .tools/bun \
  --out /tmp/opencode/ytprof/api.json
```

The frozen and venv hashes/versions, per-run rows, stage partitions, solver samples,
probe status, cache inventory, negative controls, visitorData result labels, and
ffmpeg-location pairs are all stored in the JSON artifact. The artifact intentionally
stores no raw media URL, cookie, visitor value, or request argv containing a secret.

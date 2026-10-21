# Resolver optimization and architecture decision

The implemented recommendation is **HYBRID**: keep the existing direct VISIONOS audio response as a bounded fast path, and delegate every challenge, cipher, rejected media URL, or native failure to unmodified upstream yt-dlp + Bun. Do not remove the legacy implementation yet. Full upstream replacement is working, but the measured latency and physical-memory cost remain substantially above native. No persistent Python or JavaScript worker is shipped.

This is the current decision report. Earlier QuickJS/Bun reports describe historical full-replacement candidates. The [raw matched application data](packaging/resolver/validation/linux-resolver-matrix.json) and [matrix harness](scripts/benchmark-resolver-matrix.py) are the authority for the tables below.

## Matched application results

Four rounds rotate the four modes through a Latin-square order. Every round resolves the same six public videos twice, with shuffled video order: **48 requests per mode, 192 total**. Builds finish before measurements. The same temporary application hook, HIGH quality request, restored session, CDN validation, and real muted libVLC playback requirements apply to every arm. The source Secret Service entry is read-only, unchanged before/after; disposable clones and private profiles are removed.

| Mode | Success | Min | p50 | p90 | p95 | Max |
|---|---:|---:|---:|---:|---:|---:|
| Legacy | 48/48 | 124 | 643.5 | 1301 | 1412 | 1599 |
| Original yt-dlp + Bun | 48/48 | 2773 | 3280.5 | 4087 | 4245 | 4748 |
| Optimized yt-dlp + Bun | 48/48 | 2633 | 3199 | 4159 | 5004 | 5136 |
| Hybrid (direct then upstream) | 48/48 | 123 | 168 | 362 | 510 | 540 |

Times are milliseconds. p50 is the conventional median; p90/p95 use nearest rank. All attempts are retained, including failures (there were none). These are backend resolution calls that deliberately bypass the application’s resolved-URL cache, not repeat playback start times. First-per-video and immediate-repeat timings are separate below; OS page cache was not flushed.

| Mode | First/video p50 (n=24) | Repeat p50 (n=24) | First/process p50 (n=4) |
|---|---:|---:|---:|
| Legacy | 998.5 | 171 | 1458.5 |
| Original yt-dlp + Bun | 3311 | 3231.5 | 4190.5 |
| Optimized yt-dlp + Bun | 3213 | 3160 | 4131 |
| Hybrid (direct then upstream) | 184.5 | 155.5 | 269 |

| Mode | Extraction peak PSS | Extraction peak RSS | Idle peak PSS | Post-idle peak PSS | Extraction CPU / 12 requests |
|---|---:|---:|---:|---:|---:|
| Legacy | 629.88 | 1310.09 | 375.47 | 421.77 | 5.565 s |
| Original yt-dlp + Bun | 798.98 | 844.68 | 361.16 | 422.50 | 32.020 s |
| Optimized yt-dlp + Bun | 781.88 | 839.84 | 364.99 | 424.55 | 30.255 s |
| Hybrid (direct then upstream) | 396.40 | 422.05 | 389.18 | 437.27 | 0.575 s |

Memory is MiB. Memory columns show the largest observed simultaneous tree total across four runs, not a sum of separate per-process maxima. CPU is the median phase total across four runs, each with 12 requests. JVM idle/post-idle variation remains; this does not establish an idle-memory improvement. Hybrid’s 48 natural requests all used the direct path, with **zero natural fallbacks**. Its low extraction peak must not be generalized to upstream fallbacks.

The manifest optimization alone changes signed-in application p50 only from 3280.5 to 3199 ms; p95 is worse in this small sample. It is a reasonable removal of unused work, not evidence that full replacement has approached native. The direct-only hybrid removes eager proof minting and challenge work on eligible URLs, explaining the much larger gain.

All 16 application runs restored the account and verified an account request, minted the separate cold-proof control, passed start/advance/seek/pause/resume/stop, and exited without helper processes remaining. Output was muted. Proof controls run **after extraction**, and are excluded from extraction memory/CPU/latency. Production token bindings are retained during extraction.

## Formats and loudness are part of the comparison

Identical HIGH requests do not force identical codecs. Legacy and hybrid selected the same itags for every tested video; yt-dlp selected Opus 251 for all six. The hybrid retains native HIGH semantics rather than changing quality policy to win a benchmark.

| Video ID | Legacy / hybrid | yt-dlp | Native/hybrid perceptual loudness (dB) |
|---|---|---|---:|
| `9bZkp7q19f0` | Opus / webm 251 | Opus / webm 251 | -7.31 |
| `M7lc1UVf-VE` | AAC / m4a 140 | Opus / webm 251 | -13.04 |
| `aqz-KE-bpKQ` | Opus / webm 251 | Opus / webm 251 | -18.71 |
| `dQw4w9WgXcQ` | Opus / webm 251 | Opus / webm 251 | -13.01 |
| `jNQXAC9IVRw` | AAC / m4a 140 | Opus / webm 251 | -23.67 |
| `kJQP7kiw5Fk` | Opus / webm 251 | Opus / webm 251 | -8.29 |

These are not codec-matched measurements. Bitrate fields are preserved in the raw data; a native format bitrate and yt-dlp’s abr are not interchangeable quality scores across AAC/Opus. Perceptual loudness is present and matches native on all six videos. The optional yt-dlp loudness metadata fetch can time out independently. Signed-in HIGHEST calls bypass the non-cookie fast client; current desktop playback and downloads request HIGH, so that API guard is not a desktop quality setting. Public streams in a signed-in application do not establish restricted/member-only or premium-format acceptance.

## Where upstream extraction spends time

The [pipeline profile](YT_DLP_PIPELINE_PROFILE.md) records per-process, diagnostic and network windows, 150 timed matrix attempts within 228 total rows. Stage windows can overlap and are not additive causal timings.

- Every frozen process pays roughly **417–422 ms** before the first diagnostic; initialization/extractor setup is roughly **156–158 ms** afterward.
- Network work dominates many unchallenged requests: webpage roughly **0.75–1.3 s**, player API roughly **0.15–0.2 s**, optional HLS manifest roughly **0.3–0.5 s**, with host/network variation.
- Bun launch itself is around **2 ms** for a trivial evaluation. The EJS challenge evaluation, not runtime spawn, costs roughly **0.6–0.7 s wall** and about **1–1.6 s helper CPU** when challenged.
- JSON output compaction saved only around **10–20 ms** in the measured output stage. The pipe projection actually timed omitted parser-required fields; it was not adopted.

The anonymous CLI profile improved from 2420 ms p50 / 3126 ms p90 to 1844 / 2520 with compact output plus skipped manifests. That result is distinct from the signed-in application matrix above: client/challenge behavior and end-to-end probing differ. The adopted production change is only upstream `youtube:skip=hls,dash`, because the selector already accepts only progressive HTTP(S) audio. Cookies, webpage, default upstream clients, output schema, and probes remain intact.

Skipping the webpage is unsafe: explicit visitorData did not prevent bot-wall failures for jNQXAC9IVRw and aqz-KE-bpKQ. Forcing upstream VISIONOS can avoid challenges but cannot represent cookie authentication; no cookies are stripped to force that route. Empty ffmpeg-location controls showed only about 5 ms observed difference, so additional tooling complexity was rejected.

## Worker and cache decision

The [persistent-worker evaluation](YT_DLP_WORKER_EVALUATION.md) retained 72 valid attempts with no failures and passing head/tail probes. Default-client p50 was 2302 ms frozen, 1854 ms fresh Python, 1656 ms persistent/new YoutubeDL, and 1311 ms persistent/reused YoutubeDL. Forced challenged-client p50 remained 2827 / 2475 / 2343 / 2133 ms respectively.

A reused YoutubeDL preserves player JS/signature state and HTTP connections, but **Bun still ran once for every challenged extraction, including same-video repeats**. Standard upstream disk cache wrote a tiny signature-function entry and did not remove the n-challenge solve. Adjacent medians are not a causal decomposition: the study spans two sessions and unequal cold/warm counts. No custom upstream n-result caching patch is introduced.

The reused worker costs **77.6 MiB PSS / 92.8 MiB RSS idle** plus new credential lifetime and cancellation handling. This is a poor trade for the measured hybrid, where the upstream process is a fallback and the existing application URL cache already avoids repeated successful extraction. The shipped approach stays process-per-request and fully reaps children.

The [official onedir comparison](YT_DLP_UNPACKED_EVALUATION.md) measured the actual upstream ZIP against the shipped one-file build. Five interleaved new-process starts per binary, with warm OS page cache, gave **458.2 vs 281.5 ms p50** (observed difference **176.7 ms**), not the 448 ms inferred from a different virtual environment. Eighteen paired anonymous audio-only controls passed range validation, with one Bun start per challenged request in both arms. Those controls omit final production manifest/HIGH bitrate preferences; they are identical between binaries, not production-parity evidence. End-to-end wall medians favored onedir by 14–302 ms depending on scenario; n=3/scenario and mixed CPU/memory results limit interpretation. The onedir payload adds a 148-member interpreter/package tree. For an occasional upstream fallback, this saving does not justify changing the verified single-file packaging in this branch. No resident worker, custom cache, or onedir package is shipped.

## Browser helpers: cause and necessity

The [browser attribution study](YT_DLP_LEGACY_BROWSER_ANALYSIS.md) confirmed three controlled launches with the same nine-process role mix: one browser, three zygotes, two renderers, one GPU, one network utility, and one storage utility. Nine processes are **one Chromium invocation**, not nine independent browser launches. No crashpad process was observed. Teardown took 38–39 ms and left no processes/profile behind.

Legacy eagerly asks the session manager for per-video Web BotGuard proof before extraction. The provider caches that proof, so same-video repeats can avoid another launch. VISIONOS succeeded without using that Web proof in this corpus. Hybrid therefore skips the eager mint, while preserving the login system and leaving legacy available. Browser trees were app descendants with private proof profiles, not unrelated user browser processes. Neither architecture keeps these helpers resident after extraction.

## Architecture and compatibility boundaries

1. Try the existing VISIONOS player response for at most two seconds; accept only a direct audio URL without n/cipher/signatureCipher. Preserve native quality, headers, expiry and audioConfig; validate CDN ranges. Respect existing client backoff.
2. On any native failure, rejection, or timeout, take a fresh session snapshot and run unmodified upstream yt-dlp + Bun. Keep cookies/account context intact. Do not run Mori/NewPipe or maintain signature transforms on the default path.
3. Propagate caller cancellation without starting fallback. Keep existing playback/download/cache/preload interfaces and libVLC callback transport. Existing URL cache remains session- and expiry-aware.
4. Retain explicit `hybrid`, `ytdlp`, and `legacy` modes for diagnostics/comparison; no legacy code is removed and no branch is merged.

This retains a small native request/format-selection fast path, which can itself break. Its failures are contained by upstream fallback. It does not preserve the old burden of maintaining changing YouTube decipher/challenge code. If YouTube stops returning direct formats, the application becomes slower rather than relying on new local transforms.

A separate [controlled-fallback whole-app run](packaging/resolver/validation/linux-resolver-fallback.json) injects a native player IOException only into the disposable hybrid benchmark hook. It leaves production code unchanged and uses real signed-in yt-dlp, CDN validation, and libVLC playback. The same hook is compiled into both arms; only the hybrid test environment enables the failure. This isolates immediate-failure recovery from the natural fast-path hit-rate corpus.

| Mode | Success | p50 / p90 / p95 / max (ms) | Peak extraction PSS | CPU / 12 requests |
|---|---:|---:|---:|---:|
| Hybrid, controlled native failure | 12/12 | 3124.5 / 3973 / 4181 / 4181 | 795.43 MiB | 31.78 s |
| Upstream-only control | 12/12 | 3293.5 / 3480 / 4015 / 4015 | 792.49 MiB | 31.86 s |

Hybrid recorded **0 fast-path / 12 upstream fallback successes**, with the controlled marker absent from the upstream-only arm. Both application playback checks, account requests, proof controls and cleanup guards passed; the source account entry remained unchanged. A fallback has upstream-sized memory and latency, rather than the 396 MiB direct-path peak. This small one-round sample does not establish a fallback overhead effect size. An actual slow native failure can additionally consume up to the two-second fast-path deadline before upstream; the immediate IOException fixture does not measure that delay. Unit tests separately prove timeout cleanup precedes fallback and caller cancellation starts no fallback.

## Measurement accounting and exclusions

The sampler follows descendants plus a persistent (PID,starttime) registry, including helpers launched in setsid groups. It includes the application JVM, yt-dlp bootloader and embedded CPython worker, Bun, media probes, and Chromium helpers. Absence of a separately named python process does not mean Python is absent. PSS sums smaps_rollup across the tracked tree, apportioning shared pages rather than double-counting Chromium’s RSS. Samples are not perfectly atomic; complete PSS samples are used and incomplete samples are counted/discarded. Polling can miss short-lived peaks. RSS mixes stat samples with smaps Rss at paired PSS samples; PSS is the physical-memory comparison used here. Phase CPU retains exited helper ticks and is approximate at phase boundaries; supplementary JVM-reaped CPU excludes Xvfb and is not the sole CPU measure.

An initial matrix arm was **excluded** because an excessively long disposable profile path exceeded Chromium’s Unix socket path limit. It burned the 15-second browser startup timeout, despite native playback eventually succeeding. Thirteen crash dumps confirmed SingletonSocket/too-long errors. The [excluded artifact](packaging/resolver/validation/linux-resolver-matrix-excluded-long-path.json) is preserved and never used for performance claims. The rerun used atomic short owner-only profiles under /tmp, with proof-success/teardown guards. This was a harness problem, not a product/login regression.

A worker harness plumbing error also sent live traffic during the profiler’s first session. The disclosed 11:11:05.750–11:11:39.236 UTC window was conservatively excluded, the affected profiler blocks rerun cleanly, and provenance preserved. The worker’s invalid new-object mode row was superseded by a guarded rerun. These exclusions are not silently counted as successes.

Refs: baseline `0817a502413c6e77871eb161ee5c18ad6c4486da`; original upstream `6df503606725447dfa2edd3c2541c93dc911ce32`; optimized/hybrid `ac0350ee6200c42d73e72a8f299824d821d23ee9`; matrix harness `29ed69003`. All use core `144fe5c33c9e2f1f8a6c8c84bb03a34c3c4d7b3c`. Runtime remains unchanged through packaging ref `fb434722e`. Pinned binaries are yt-dlp 2026.08.19 (`58162f9b…99230a`) and Bun 1.4.3 (`7ce7d6b6…f8f24b`), bundled EJS 0.8.0.

## Verification and reproducibility

The final backend run reported 106 tests, zero failures and one unrelated opt-in skip; the opt-in policy class included four controlled native failure cases reaching live upstream and real CDN probes. The real playback integration class passed 3/3, including real yt-dlp and hybrid playback with seek/pause/resume; desktop compilation passed. Earlier unchanged resolver suite passed 26/26 and full playback suite passed 54 tests with two expected opt-in skips subsequently exercised separately. Python sampler/benchmark regression suite passed 22/22. Existing build-logic tests cover archive/manifest safety.

The four controlled native failures are an n-parameter URL, signatureCipher, PO-required player rejection, and a local HTTP 403. Live public problem IDs are exercised upstream after those deterministic failures; these are not claims that current YouTube naturally produced all four failures on this day. Historical anonymous native failures of jNQ/aqz are preserved in earlier reports, while the fixed signed-in Linux baseline now resolves them. `BaW_jenozKc` is unavailable and used only as a proof-binding probe, not as an extraction-success fixture.

Reproduce from the isolated branch with Java 21, system libVLC, Bun/yt-dlp pinned sidecars, Xvfb, Secret Service, and the already-authorized signed-in session:

```bash
DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus \
python3 scripts/benchmark-resolver-matrix.py \
  --candidate-ref ac0350ee6 --rounds 4 \
  --work-root /tmp/opencode/resolver-matrix-fresh \
  --output build/resolver-validation/resolver-matrix.json
```

Use a new work root for measured reruns. Build-only preparation and explicit `--skip-build` allow all compilation to finish before timed slots. No other build, browser proof loop, extraction benchmark, or compression task should overlap measurements. Network conditions are interleaved on one host/day, not laboratory-controlled; there is no statistical claim of universal reliability or confidence interval from 48 successes.

## Packaged candidate

The final Linux AppImage was built from exact ref `fb434722e` after verifying all submodule HEADs equal their gitlinks. `:desktopApp:createDistributable` and `:desktopApp:verifyBundledResolverSidecars` passed. The payload contains the pinned yt-dlp, Bun, EJS, manifest and license set, with no QuickJS files; its runtime JAR contains the hybrid default/direct path. Offscreen GUI boot passed with private HOME/XDG and network isolated by bubblewrap. No account was accessed in that packaging smoke and nothing was installed.

- [AppImage](/tmp/opencode/appimage-hybrid-final/ArchiveTune-15.10-x86_64.AppImage): 221,399,544 bytes.
- SHA-256: `3be8ceb5c232c8ab5d04e0e15b0592540d0f63f18cc50362db963a7cf052a615`.

The [packaged-sidecar live smoke](packaging/resolver/validation/linux-hybrid-packaged-smoke.json) then forced `web_embedded` challenge solving on dQw4w9WgXcQ using only the extracted AppImage binaries. Bundled EJS 0.8.0 executed through Bun, selected audio-only HTTPS Opus 251 with four headers, and returned HTTP 206 / 65,536 bytes at both head and tail. The expected upstream unsupported-Bun-version warning appeared; no version bypass was used. No process group survived, the private temporary root was removed, and no cookies or account data were read. This is a functionality smoke, not another timed benchmark.

Windows runtime/Job Object containment, full Windows MSI/GraalVM execution, ARM/macOS, restricted/cookie-only media, audible output, full manual queue/download acceptance, and concurrent-preload memory stress remain unverified. These limitations do not block this isolated experimental branch, and no install or merge has occurred.

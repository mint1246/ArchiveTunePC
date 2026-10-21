# ArchiveTune unpacked yt-dlp release evaluation

Developer-only evaluation of the **upstream unpacked yt-dlp release** as a
cheaper start-up than the self-extracting bundle that the desktop resolver
currently ships. Nothing in this document changes production code, and no
upstream yt-dlp extraction or challenge patch is proposed or used.

* Harness: [`scripts/benchmark-ytdlp-unpacked.py`](scripts/benchmark-ytdlp-unpacked.py)
* Machine-readable result: [`packaging/resolver/validation/linux-ytdlp-unpacked-evaluation.json`](packaging/resolver/validation/linux-ytdlp-unpacked-evaluation.json)
* Pinned release: yt-dlp **2026.08.19**, Bun **1.4.3**
* Related: [`YT_DLP_WORKER_EVALUATION.md`](YT_DLP_WORKER_EVALUATION.md) (the
  persistent worker evaluation)

> **Status: measured.** Eighteen paired public-video extractions and ten startup controls completed on 2026-10-21. All extractions passed range validation; no helper remained.

---

## 1. Question and decision context

The desktop resolver starts the yt-dlp executable **once per request**. The
executable that ships today is the upstream self-extracting bundle, and its
per-request start-up is the one part of the resolver request path that the
persistent worker evaluation could attribute to packaging rather than to
yt-dlp itself.

The question:

> Does the upstream **unpacked** Linux artifact of the same release start
> measurably faster than the shipped bundle, with identical behaviour, and is
> the difference worth the packaging complexity?

Decision context (from the parent task):

* The direct native hybrid pilot already removed the normal Python start-up
  (12 of 12 runs, 140-550 ms).
* The remaining question is narrower: whether **more packaging complexity** is
  justified for a **upstream fallback on this corpus** path.
* The goal of this evaluation is therefore not "make the resolver fast" but
  "decide whether the unpacked artifact is a better packaging choice at all".

Two things this evaluation deliberately does **not** do:

* It does not assume that the saving measured in the virtual-environment
  comparison applies to the bundle. The bundle overhead is measured directly,
  binary against binary, with the same arguments.
* It does not change any default. The harness is a developer tool, nothing in
  the shipped runtime depends on it and **no persistence or caching policy is
  implied** by the measurement.

---

## 2. Method

### 2.1 Artifacts and verification

1. The official upstream release artifacts of yt-dlp 2026.08.19 are fetched
   over HTTPS from the GitHub release:
   * `yt-dlp_linux.zip` - the upstream Linux zip
   * `SHA2-256SUMS` - the published checksum feed of that release
   Nothing is installed globally; everything stays under the git-ignored
   `.tools/` directory.
2. The archive is verified against the **published feed** of the release.
   That feed check is the **only acceptance gate**.
   The digest of the extracted member is recorded and compared with the digest
   of the shipped one-file bundle
   (`58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a`),
   but **equality is a finding, not a requirement**: the upstream unpacked
   artifact is expected to be a *different* binary than the self-extracting
   one-file bundle, so a different digest is not a failure and does not stop
   the measurement. The report states both digests and whether they match.
3. The archive is extracted with a **path guard**: every member must be a
   plain relative file inside the target directory - no absolute paths, no
   `..`, no directory traversal and no symlinks. The guard is exercised by an
   offline fixture.
4. The static layout of the upstream Windows zip (`yt-dlp_win.zip`) is
   explored as well, if the slot allows it: the member list, the sizes and
   the integrity against the same published feed. It is **not** executed and
   not extracted.
5. The executable member that is measured is selected by a recorded rule (a
   member whose base name is `yt-dlp`, otherwise the first executable
   member), so the comparison is reproducible.

### 2.2 Comparison

Both binaries are measured with an **identical argument set**:

| | |
|---|---|
| common arguments | `--ignore-config --no-plugin-dirs --no-remote-components --no-cache-dir` |
| JavaScript runtime | `--no-js-runtimes --js-runtimes bun:<bun>` |
| request arguments | `--no-playlist --skip-download --dump-single-json --socket-timeout 12 --retries 1 --extractor-retries 1 --no-progress --format bestaudio[protocol=https]/bestaudio[protocol=http]` |
| target | `-- https://www.youtube.com/watch?v=<id>` |

Three measurements:

1. **New-process start-up**: 5 interleaved `--version` starts of each
   binary, with the wall time and the `getrusage(RUSAGE_CHILDREN)` delta.
   These are **not cold boots**: the operating system page cache holds the
   binary after its first launch, so only the very first launch of a binary
   can still read a cold page cache - and even that is not guaranteed. The
   first launch of each binary is therefore reported separately, and the
   report labels the block "new process, page cache warm".
2. **Toolchain diagnostics**: the verbose unsupported-scheme probe of the
   shipped resolver (`--verbose --simulate -- archivetune-runtime-check:`)
   with the same common arguments, so the diagnostics contain the exact
   JavaScript runtime and EJS versions that the shipped toolchain check
   relies on.
3. **Paired real extractions**: 3 rounds over three scenarios - the
   challenged video with the default player client, the same video with the
   explicit `web_embedded` client, and an unchallenged video - each measured
   with both binaries back to back. Every successful result is probed with a
   ranged GET at the head and at the tail using the headers yt-dlp returned,
   and the process tree RSS, PSS, CPU, challenge-solver invocations and the
   cleanup state are recorded.

### 2.3 Safety contract

* Cookies are never requested, never read and never written; the run is
  anonymous and no original application session is used.
* Only the official upstream release artifacts are fetched.
* Signed media URLs, raw `--dump-single-json` payloads and process argv are
  never written to disk and never printed.
* Every extraction runs with a private HOME/XDG_*/TMPDIR.
* The range probe always runs outside the measured request window.
* Nothing is installed globally and no daemon is left behind.

---

## 3. Results

Every number in this section is taken from
[`packaging/resolver/validation/linux-ytdlp-unpacked-evaluation.json`](packaging/resolver/validation/linux-ytdlp-unpacked-evaluation.json).
The measured run was 2026-10-21T12:21:44Z .. 12:22:39Z. Nothing here is an
estimate.

### 3.1 What the upstream artifact actually is

| | |
|---|---|
| archive | `yt-dlp_linux.zip`, 40 516 244 bytes |
| archive sha256 | `32e72032766bef9199d99d15beb69fd52e46df8f8b06f0d8745db59e04d339e9` |
| published feed | the archive digest **matches** the published `SHA2-256SUMS` entry - the only acceptance gate |
| layout | a PyInstaller **onedir** distribution: 148 members, one top-level program plus a `_internal` directory with 147 files (85 470 495 bytes) holding the interpreter, the packages and the EJS solver scripts |
| the program | top-level ELF `yt-dlp_linux`, 10 519 128 bytes, sha256 `9daa5228ec81576268728a7ca231a066fc7a21cae52a583392d42abb08e65b86` |
| the shipped sidecar | one-file bundle `yt-dlp`, 40 446 224 bytes, sha256 `58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a` |
| digest equality | **different**, as expected for the onedir build. The comparison is a recorded finding, not a gate. |

The upstream Linux zip is therefore the same program in a different packaging:
the onedir build avoids the self-extracting step of the one-file bundle.

The toolchain probe of the shipped resolver confirms that the two artifacts
behave identically apart from the packaging:

| | `frozen_cli` | `unpacked_zip` |
|---|---|---|
| yt-dlp version | `stable@2026.08.19` from `yt-dlp/yt-dlp` | `stable@2026.08.19` from `yt-dlp/yt-dlp` |
| build tag | `linux_exe` | `linux_dir` |
| Python | 3.14.7 | 3.14.7 |
| EJS | `yt_dlp_ejs-0.8.0` | `yt_dlp_ejs-0.8.0` |
| JavaScript runtime | `bun-1.4.3` | `bun-1.4.3` |
| extractors loaded | 1744 | 1744 |
| optional libraries | Cryptodome 3.23.0, brotli 1.2.0, certifi 2026.07.22, curl_cffi 0.16.0, mutagen 1.48.1, requests 2.34.2, secretstorage 3.5.0, sqlite3 3.53.4, urllib3 2.7.0, websockets 17.0.1, yt_dlp_ejs 0.8.0 | identical |
| exe versions | ffmpeg 9.0.2, ffprobe 9.0.2, rtmpdump 2.6 | identical |

### 3.2 New-process start-up

5 interleaved `--version` starts of each binary. **These are new-process
measurements with a warm operating system page cache, not cold boots**; the
first launch of each binary is reported separately because only it can still
read a cold page cache.

| binary | wall p50 | wall p90 | wall max | CPU p50 | CPU max | first launch wall | first launch CPU |
|---|---|---|---|---|---|---|---|
| `frozen_cli` (one-file) | 458.2 ms | 468.3 ms | 468.3 ms | 0.457 s | 0.467 s | 467.6 ms | 0.466 s |
| `unpacked_zip` (onedir) | 281.5 ms | 289.1 ms | 289.1 ms | 0.281 s | 0.288 s | 289.0 ms | 0.288 s |

Excluding the first launch the medians are 457.1 ms (`frozen_cli`) and 279.5 ms
(`unpacked_zip`).

**Observed difference: 176.7 ms of wall time and 0.176 s of CPU per process.**
This is an observation on one machine, not an effect size. It is also
**smaller than the 448 ms** that the virtual-environment comparison of the
persistent worker evaluation suggested for the frozen bundle - as the review
required, the bundle overhead was not transferred from the venv.

### 3.3 Paired extractions

Identical audio-only control flags in both arms, anonymous, no cookie and no
session: 9 + 9 = 18 attempts, **0 failed**, and every successful result passed
the head/tail range probe (HTTP 206 at the head and at the tail with the
headers yt-dlp returned).

| scenario | binary | attempts | wall p50 | wall max | CPU p50 | peak tree RSS p50 | peak tree PSS p50 | Bun invocations |
|---|---|---|---|---|---|---|---|---|
| `challenged_default` | `frozen_cli` | 3 | 2906 ms | 3028 ms | 1.84 s | 389.5 MiB | 381.1 MiB | 3 |
| `challenged_default` | `unpacked_zip` | 3 | 2892 ms | 2893 ms | 2.28 s | 410.6 MiB | 404.9 MiB | 3 |
| `challenged_web_embedded` | `frozen_cli` | 3 | 3075 ms | 3083 ms | 2.48 s | 406.4 MiB | 403.4 MiB | 3 |
| `challenged_web_embedded` | `unpacked_zip` | 3 | 2773 ms | 2811 ms | 1.68 s | 366.4 MiB | 364.1 MiB | 3 |
| `simple_default` | `frozen_cli` | 3 | 2219 ms | 2386 ms | 0.76 s | 85.9 MiB | 79.6 MiB | 0 |
| `simple_default` | `unpacked_zip` | 3 | 1991 ms | 2077 ms | 0.57 s | 88.2 MiB | 84.1 MiB | 0 |

Pooled over the three scenarios the wall p50 is 2883 ms (`frozen_cli`) against
2773 ms (`unpacked_zip`) and the CPU p50 is 1.84 s against 1.68 s.

All three wall-median differences favor onedir (-14 ms, -302 ms, -228 ms),
but their magnitudes vary substantially; CPU differences have mixed signs
(+0.44 s, -0.80 s, -0.19 s). Three attempts per scenario do not establish
a stable end-to-end effect size. The one difference
that is stable is the **start-up**: 5 interleaved samples per binary, same
sign, same magnitude as the CPU delta (0.176 s of CPU for 0.177 s of wall
time).

Both arms resolved the same kind of result (format `251`, WebM/Opus, HTTP
206 at the head and the tail) and both started the challenge solver exactly
once per challenged request.

The extraction control does **not** reproduce the final production argument vector: it uses bestaudio by protocol, omits the HIGH bitrate preference, and omits `youtube:skip=hls,dash`. Both binaries receive the same control arguments, so the binary comparison remains useful; it is not the final optimized application measurement. A draft explanation that comma-separated skip values would be inert was incorrect: upstream parses those values. The main application matrix measures the actual production configuration.

### 3.4 The upstream Windows artifact (static only)

| | |
|---|---|
| archive | `yt-dlp_win.zip`, 18 087 041 bytes |
| archive sha256 | `30b4c14aafab6082becff7881e41b76df46dc43ea7633479410a91e29da492bf` |
| published feed | **matches** |
| layout | 143 members, one top-level program `yt-dlp.exe`, no rejected member paths |

It was **not** executed and not extracted.

### 3.5 Reliability and cleanup

* 18 extractions, 0 failed, no retries needed.
* Every successful result passed the head/tail range probe.
* `cleanup.leftover_processes` is empty and the private temporary tree was
  removed: no Bun, no helper and no child process survived.

### 3.6 What this means for the packaging decision

* The upstream unpacked artifact is **functionally the same program** (same
  version, same Python, same EJS, same runtime, same extractors, same
  libraries) and it starts **176.7 ms faster per request** than the shipped
  one-file bundle, for the price of a directory instead of a single file
  (148 members, 95 989 623 bytes uncompressed) and of a different install
  layout.
* It does **not** recover the 448 ms that the virtual-environment comparison
  of the worker evaluation attributed to the bundle, and it does **not** touch
  the challenge solver: the Bun start per challenged request is identical in
  both arms.
* For the stated decision - *is more packaging complexity worth it for an
  upstream fallback path* - the measured answer is: the gain is real but
  small, and it only exists for the paths that still start a yt-dlp process
  per request. With the native hybrid pilot already removing the normal
  Python start-up, the remaining consumer of this gain is the fallback on this corpus
  itself. **This evaluation therefore does not justify changing the shipped
  packaging default**; it quantifies what such a change would buy.
* No persistence, cache or resident process is implied by any of these
  numbers.

---

## 4. Upstream references

* The release assets and the checksum feed of yt-dlp 2026.08.19:
  <https://github.com/yt-dlp/yt-dlp/releases/tag/2026.08.19>
* The resolver flag set, the toolchain probe and the format selector of the
  desktop resolver:
  `stream-resolver/src/main/kotlin/moe/rukamori/archivetune/stream/YtDlpStreamResolver.kt`
* The pinned upstream digests of the desktop sidecars:
  `buildSrc/src/main/kotlin/ResolverSidecars.kt`

---

## 5. Caveats

* Small sample by design: the evaluation is a bounded developer check, not a
  statistical benchmark. Every percentile in the report carries its sample
  size.
* The start-up numbers are **new-process** measurements with a warm operating
  system page cache, not cold boots. They depend on the file system and the
  temporary directory; the numbers are from one machine and one file system.
* The differences between the two binaries are an **observation on one
  machine, not a causal claim**. The sample is small, the first launch of
  each binary has a different page-cache state than the later ones, and the
  two binaries do not run from an identical machine state. Adjacent
  percentiles must not be read as an effect size.
* **No persistence, cache or resident process is implied** by this
  measurement, and no default changes.
* The Bun version that the shipped sidecar carries is newer than the last
  version that upstream yt-dlp supports. Both binaries are measured with the
  same Bun, so the comparison stays fair, but the challenge cost itself is
  upstream-deprecated.
* The Windows artifact is explored statically only.

---

## 6. Reproduction

```bash
# download and verify the upstream artifacts, extract them into the
# git-ignored tools directory and measure
scripts/benchmark-ytdlp-unpacked.py \
    --yt-dlp .tools/resolver/yt-dlp \
    --bun    .tools/resolver/bun \
    --tools-dir .tools/unpacked \
    --repeats 3 --startup-repeats 5 \
    --windows-layout \
    --out-json packaging/resolver/validation/linux-ytdlp-unpacked-evaluation.json

# reuse artifacts that are already in the tools directory
scripts/benchmark-ytdlp-unpacked.py --skip-download ...
```

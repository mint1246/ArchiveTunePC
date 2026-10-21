# Experimental desktop resolver sidecars

ArchiveTune Desktop's experimental stream resolver can delegate extraction to the
official `yt-dlp` executable and run yt-dlp's JavaScript challenge scripts with
the official `bun` runtime. Neither dependency is committed to this repository.
The build downloads pinned upstream release assets, verifies their SHA-256
digests, and stages them with their notices into the distribution.

QuickJS/`qjs` was the previous default. It is no longer pinned or staged; a
staged directory that still contains `qjs` is rejected by the verifier and by
the Linux AppImage wrapper.

## Bundled layout

The runtime discovery contract is:

1. Developer overrides: `archivetune.ytdlp` / `archivetune.bun` system properties,
   then `ARCHIVETUNE_YTDLP` / `ARCHIVETUNE_BUN`. Explicit relative paths are resolved
   before the child changes into its private request directory.
2. JVM distribution: `<compose.application.resources.dir>/resolver`.
3. GraalVM native distribution: `<executable parent>/resolver`, then
   `<executable parent>/../resolver`.
4. `<user.dir>/.tools/resolver` for local development.
5. `yt-dlp` / `bun` (or `yt-dlp.exe` / `bun.exe` on Windows) on `PATH`.

Every staged resolver directory has this shape:

```text
resolver/
  yt-dlp            # yt-dlp.exe on Windows
  bun               # bun.exe on Windows
  manifest.properties
  THIRD_PARTY_NOTICES.md
  licenses/
    bun-LICENSE.md
    bun-LGPL-2.0.txt
    bun-LGPL-2.1.txt
    bun-WEBKIT-NOTICE.md
    pyinstaller-6.22.0-COPYING.txt
    yt-dlp-THIRD_PARTY_LICENSES.txt
    yt-dlp-UNLICENSE.txt
    yt-dlp-ejs-UNLICENSE.txt
```

Inside a Compose Desktop app image the directory depends on the platform:

```text
Windows: ArchiveTune/app/resources/resolver/
Linux:   ArchiveTune/lib/app/resources/resolver/
```

Inside the GraalVM native release directory it is a sibling of the executable:

```text
ArchiveTune/
  ArchiveTune.exe
  resolver/
    yt-dlp.exe
    bun.exe
    ...
```

`manifest.properties` records the platform, versions, upstream URLs, and
SHA-256 values. The runtime does not need it, but it makes support reports and
release verification unambiguous. For Bun, `bun.sha256` is the downloaded ZIP
digest and `bun.binary-sha256` is the extracted executable digest.

Installers carry the staged tree automatically: jpackage packages the platform's
app resource directory, the Linux AppImage wrapper copies and validates that image,
and the native WiX build harvests everything below
the GraalVM release directory with `heat.exe`. No installer file list needs to
be updated when the sidecar set changes.

Both sidecars are standalone executables. The resolver launches them for one
extraction and waits for the process to exit; no Python interpreter or
JavaScript engine is hosted persistently inside ArchiveTune. The bundled Bun
adds roughly 89 MB (Linux) / 96 MB (Windows) of uncompressed payload to an
installation, which is the main cost of replacing the much smaller QuickJS
binary.

## Pinned versions

| Sidecar | Version | Linux asset | Windows asset |
| --- | --- | --- | --- |
| yt-dlp | 2026.08.19 | `yt-dlp_linux` (raw binary) | `yt-dlp.exe` (raw binary) |
| Bun | 1.4.3 | `bun-linux-x64.zip` | `bun-windows-x64.zip` |

All four assets come from GitHub Releases. SHA-256 values and the exact ZIP
member paths live in one place,
[`buildSrc/src/main/kotlin/ResolverSidecars.kt`](../../buildSrc/src/main/kotlin/ResolverSidecars.kt):

| Asset | SHA-256 |
| --- | --- |
| `yt-dlp_linux` | `58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a` |
| `yt-dlp.exe` | `66674953fe251b89f4d08c5f0e35e0728679bd67ab3d7d05c0562af101dd3e7a` |
| `bun-linux-x64.zip` | `3e730528d89775f03f87f05c06787c4df4099d0a37f3f252e7b829cb23816e18` |
| `bun-windows-x64.zip` | `0bf84e808552beea67bf1202a287a35c6c825d837b287b3f8e9dd513dba35d3d` |
| extracted `bun-linux-x64/bun` | `7ce7d6b654eddeec20afddf2396317fba6dc19ab52788a27e894e1806bf8f24b` |
| extracted `bun-windows-x64/bun.exe` | `095db4abae203d42f29fdec26da4a882fcde6333cdd84e40c0c7828c0cf543f6` |

The yt-dlp 2026.08.19 frozen executables embed CPython 3.14.7, PyInstaller 6.22.0,
and EJS solver `yt-dlp-ejs` 0.8.0. Confirm the EJS version of a future release
with `yt-dlp --verbose` (look for `yt_dlp_ejs-...` in the optional libraries
line) before bumping `YT_DLP_BUNDLED_EJS_VERSION`.

### Bun version gate

yt-dlp 2026.08.19 supports Bun >= 1.2.11 and marks Bun as deprecated above
1.3.14. That upper bound only logs `bun version ... is not supported!` and then
runs the same Bun invocation; it does not disable the provider. The pinned
1.4.3 binary has been exercised with the unchanged yt-dlp release, and the
build does **not** spoof or override the reported version. Update Bun and
re-run the resolver smoke tests rather than patching the version gate.

### CPU compatibility

Bun's current official installation docs state:

> Bun ships a single x64 binary per platform. It targets the Nehalem
> microarchitecture (SSE4.2) and selects AVX2/AVX-512 code paths at runtime when
> the CPU supports them, so there is no separate "baseline" download to choose.
>
> Bun does not support x64 CPUs without the SSE4.2 extension. ... The
> `-baseline` release assets ... are kept as aliases of the single x64 binary for
> backward compatibility with older install scripts.

Source: <https://bun.sh/docs/installation#cpu-requirements> (retrieved
2026-10-21).

The Bun 1.4.3 build configuration agrees: x64 builds use `-march=nehalem` and
`baseline` defaults to true for every x64 build ("the only x64 build we ship"):

- <https://github.com/oven-sh/bun/blob/bun-v1.4.3/scripts/build/config.ts#L125-L126>
- <https://github.com/oven-sh/bun/blob/bun-v1.4.3/scripts/build/config.ts#L1029>
- <https://github.com/oven-sh/bun/blob/bun-v1.4.3/scripts/build/flags.ts#L74-L76>

The published assets confirm the alias for 1.4.3. The two ZIPs differ (their
archive names and internal directory prefixes differ), but both extract to the
exact same executable:

| Release asset | ZIP SHA-256 | Extracted executable SHA-256 |
| --- | --- | --- |
| `bun-linux-x64.zip` | `3e730528d89775f03f87f05c06787c4df4099d0a37f3f252e7b829cb23816e18` | `7ce7d6b654eddeec20afddf2396317fba6dc19ab52788a27e894e1806bf8f24b` |
| `bun-linux-x64-baseline.zip` | `1fc2edac843102909e3a1be1d8d9802cc6071cf074e67e88231f4ffe0f8b397b` | `7ce7d6b654eddeec20afddf2396317fba6dc19ab52788a27e894e1806bf8f24b` |
| `bun-windows-x64.zip` | `0bf84e808552beea67bf1202a287a35c6c825d837b287b3f8e9dd513dba35d3d` | `095db4abae203d42f29fdec26da4a882fcde6333cdd84e40c0c7828c0cf543f6` |
| `bun-windows-x64-baseline.zip` | `d7a7d3eb216614544aab0cde2d8d9f2e4e87839bf5a30a1cd11d077f02d84e0c` | `095db4abae203d42f29fdec26da4a882fcde6333cdd84e40c0c7828c0cf543f6` |

ZIP digests are the ones published in Bun's clearsigned
[`SHASUMS256.txt`](https://github.com/oven-sh/bun/releases/download/bun-v1.4.3/SHASUMS256.txt);
the extracted hashes were measured locally after safe extraction. The build
therefore uses the standard assets. They are the same Nehalem/SSE4.2 baseline
binary with runtime AVX2/AVX-512 dispatch, and x86-64 CPUs without SSE4.2 are
not supported by Bun at all.

Licensing details are in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) and
[`LICENSES/`](LICENSES). The PyInstaller bootloader is GPL-2.0-or-later with
its bootloader exception, Python is PSF-2.0, yt-dlp and yt-dlp-ejs are
Unlicense, Bun itself is MIT, and Bun statically links LGPL-2.0
JavaScriptCore/WebKit plus LGPL-2.1 tinycc. The Bun notice records the pinned
upstream source revisions and the documented relink procedure.

## Download, extraction, and verification

Raw assets (`yt-dlp_linux`, `yt-dlp.exe`) are copied after their download
checksum matches. Bun is distributed as a ZIP, so staging uses two verified
steps:

1. `DownloadVerifiedFileTask` downloads the release ZIP and compares its
   SHA-256 with the committed digest (the same digest published in Bun's
   clearsigned `SHASUMS256.txt`).
2. `ExtractVerifiedArchiveEntryTask` validates every central-directory name,
   rejects absolute paths, `..`, backslashes, drive letters, control
   characters, duplicate names, and any entry other than the exact pinned
   member and its parent directories. It caps the archive at 1,024 entries,
   streams only that member to a temporary file, enforces a 256 MiB extracted
   size limit, verifies the extracted executable's SHA-256, and publishes it
   atomically. Because the output path is chosen by the build and the archive
   tree is never expanded, ZIP-slip and symlink entries cannot create files
   outside the output path.
3. `StageResolverSidecarsTask` replaces the destination directory, copies the
   executable with fixed permissions (`0755` on Linux, `0644` for Windows
   targets), normalizes timestamps, and writes a stable manifest.

Staging deletes the whole destination first, so a stale `qjs`/`qjs.exe` from an
older checkout cannot survive, and the verifier rejects any unexpected file.
Downloads are cached under `desktopApp/build/resolver/downloads/<platform>/`;
verified extracted Bun executables live under
`desktopApp/build/resolver/extracted/<platform>/`. Gradle up-to-date checks reuse
unchanged inputs and outputs; these extraction tasks do not use the build cache.

## Build tasks

| Task | Purpose |
| --- | --- |
| `:desktopApp:downloadResolverYtDlpWindows` | Fetch and verify `yt-dlp.exe` |
| `:desktopApp:downloadResolverYtDlpLinux` | Fetch and verify `yt-dlp_linux` |
| `:desktopApp:downloadResolverBunWindows` | Fetch and verify `bun-windows-x64.zip` |
| `:desktopApp:downloadResolverBunLinux` | Fetch and verify `bun-linux-x64.zip` |
| `:desktopApp:extractResolverBunWindows` | Extract and verify `bun.exe` from the ZIP |
| `:desktopApp:extractResolverBunLinux` | Extract and verify `bun` from the ZIP |
| `:desktopApp:stageBundledResolverSidecars` | Stage into the main JVM app image |
| `:desktopApp:stageBundledResolverSidecarsRelease` | Stage into the ProGuard release app image |
| `:desktopApp:verifyBundledResolverSidecars` | Verify files, hashes, exec bits, notices, manifest |
| `:desktopApp:verifyBundledResolverSidecarsRelease` | Same verification for the release app image |
| `:desktop-native:stageNativeResolverSidecars` | Stage beside the GraalVM native executable |
| `:desktop-native:verifyNativeResolverSidecars` | Verify the sidecars in the native release directory |

Staging runs automatically:

- `createDistributable` and `createReleaseDistributable` finalize with the
  matching JVM stage task.
- `packageMsiUpgradable` and `packageExeUpgradable` depend on staging and
  verification.
- `packageGraalvmNative` finalizes with `stageNativeResolverSidecars`;
  `stageNativeDistribution` carries the directory into the release image, and
  `packageNativeMsi` depends on `verifyNativeResolverSidecars`.

The OpenJ9 wrapper tasks (`createOpenJ9Distributable`,
`createOpenJ9ReleaseDistributable`, and their packaging tasks) invoke a nested
Gradle build that runs `createDistributable` / `createReleaseDistributable`, so
staging happens inside that nested build. Run the matching `verify...` task
afterwards when those images are published manually.

Typical local cycle:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew \
  :desktopApp:stageBundledResolverSidecars \
  :desktopApp:verifyBundledResolverSidecars
```

The task defaults to the host platform. Compose Desktop and GraalVM Native Image
cannot produce a complete distribution for another OS, so unsupported hosts get
an actionable error instead of silently staging the wrong executable. For
layout testing only, the platform can be selected explicitly:

```bash
./gradlew :desktopApp:stageBundledResolverSidecars \
  :desktopApp:verifyBundledResolverSidecars \
  -Parchivetune.resolverPlatform=windows-x86_64
```

Only Windows and Linux x86_64 assets are pinned. ARM64 and macOS are not
supported by this packaging yet.

The verifier intentionally does not execute the sidecars, so it works when
staging resources for another target platform. Smoke-test the host-native pair
after staging (Linux paths below; use `app/resources` on Windows):

```bash
desktopApp/build/compose/binaries/main/app/ArchiveTune/lib/app/resources/resolver/yt-dlp --version
desktopApp/build/compose/binaries/main/app/ArchiveTune/lib/app/resources/resolver/bun --version
```

For a real challenge-solver check, run yt-dlp with the staged runtime:

```bash
desktopApp/build/compose/binaries/main/app/ArchiveTune/lib/app/resources/resolver/yt-dlp \
  --js-runtimes bun:desktopApp/build/compose/binaries/main/app/ArchiveTune/lib/app/resources/resolver/bun \
  --simulate --dump-single-json <url>
```

Expect yt-dlp to log its deprecation/unsupported-version notice for Bun 1.4.3
while the extraction still succeeds. That warning is upstream policy, not a
packaging failure.

## Reproducibility and updates

- Versions, URLs, ZIP member paths, and both SHA-256 values are committed in
  `ResolverSidecars.kt`.
- `DownloadVerifiedFileTask` reuses a cached file only when its digest matches
  and deletes partial downloads.
- ZIP extraction writes only the exact member selected by name; no archive tree
  is ever expanded into the workspace.
- Staging deletes the old directory, copies with fixed permissions, and
  normalizes file timestamps to a constant. The manifest is written in a stable
  key order, so repeated staging produces the same bytes.
- Binaries are never committed. `.gitignore` excludes staged/downloaded
  sidecars, while `LICENSES/` remains tracked.
- To update: change the version, all four asset digests, and the extracted
  Bun binary digests in `ResolverSidecars.kt` (even if only one platform
  changed), refresh the hashes in `THIRD_PARTY_NOTICES.md` and this file, and
  refresh `LICENSES/yt-dlp-THIRD_PARTY_LICENSES.txt` from the matching yt-dlp
  tag when yt-dlp changes. Re-check Bun's `LICENSE.md` and linked-library table
  when Bun changes. Re-run the stage and verify tasks for both platforms before
  publishing installers.

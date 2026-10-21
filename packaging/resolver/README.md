# Experimental desktop resolver sidecars

ArchiveTune Desktop's experimental stream resolver can delegate extraction to the
official `yt-dlp` executable and run yt-dlp's JavaScript challenge scripts with
`quickjs-ng`. Neither dependency is committed to this repository. The build
downloads pinned upstream release assets, verifies their SHA-256 digests, and
stages them with their notices into the distribution.

## Bundled layout

The runtime discovery contract is:

1. Developer overrides: absolute paths in `ARCHIVETUNE_YTDLP` and
   `ARCHIVETUNE_QUICKJS`.
2. JVM distribution: `<compose.application.resources.dir>/resolver`.
3. GraalVM native distribution: `<executable parent>/resolver`, then
   `<executable parent>/../resolver`.
4. `yt-dlp` / `qjs` (or `yt-dlp.exe` / `qjs.exe` on Windows) on `PATH`.

Every staged resolver directory has this shape:

```text
resolver/
  yt-dlp            # yt-dlp.exe on Windows
  qjs               # qjs.exe on Windows
  manifest.properties
  THIRD_PARTY_NOTICES.md
  licenses/
    pyinstaller-6.22.0-COPYING.txt
    quickjs-ng-MIT.txt
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
    qjs.exe
    ...
```

`manifest.properties` records the platform, versions, upstream URLs, and
SHA-256 values. The runtime does not need it, but it makes support reports and
release verification unambiguous.

Installers carry the staged tree automatically: jpackage packages the platform's
app resource directory, the Linux AppImage wrapper copies and validates that image,
and the native WiX build harvests everything below
the GraalVM release directory with `heat.exe`. No installer file list needs to
be updated when the sidecar set changes.

Both sidecars are standalone executables. The resolver launches them for one
extraction and waits for the process to exit; no Python interpreter or
JavaScript engine is hosted persistently inside ArchiveTune.

## Pinned versions

| Sidecar | Version | Linux asset | Windows asset |
| --- | --- | --- | --- |
| yt-dlp | 2026.08.19 | `yt-dlp_linux` | `yt-dlp.exe` |
| quickjs-ng | 0.17.0 | `qjs-linux-x86_64` | `qjs-windows-x86_64.exe` |

All four assets come from GitHub Releases. SHA-256 values live in one place,
[`buildSrc/src/main/kotlin/ResolverSidecars.kt`](../../buildSrc/src/main/kotlin/ResolverSidecars.kt):

| Asset | SHA-256 |
| --- | --- |
| `yt-dlp_linux` | `58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a` |
| `yt-dlp.exe` | `66674953fe251b89f4d08c5f0e35e0728679bd67ab3d7d05c0562af101dd3e7a` |
| `qjs-linux-x86_64` | `0bfc02511a9f549c28b53880d988fc7cd5d361e90c5e8afdfcd7dc6774ceace5` |
| `qjs-windows-x86_64.exe` | `2aeabf0092c3262d6b2609824418f7dd7ed1f1df939f73b2b15645230cac0d77` |

The yt-dlp 2026.08.19 frozen executables embed CPython 3.14.7, PyInstaller 6.22.0,
and EJS solver `yt-dlp-ejs` 0.8.0. Confirm the EJS version of a future release
with `yt-dlp --verbose` (look for `yt_dlp_ejs-...` in the optional libraries
line) before bumping `YT_DLP_BUNDLED_EJS_VERSION`.

Licensing details are in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) and
[`LICENSES/`](LICENSES). The PyInstaller bootloader is GPL-2.0-or-later with its
bootloader exception, Python is PSF-2.0, yt-dlp and yt-dlp-ejs are Unlicense, and
quickjs-ng is MIT.

## Build tasks

Downloads are cached under `desktopApp/build/resolver/downloads/<platform>/` and
are re-downloaded only when the checksum-verified file is missing or corrupt.
The staging tasks replace their destination directory, so stale sidecars from a
different platform cannot survive.

| Task | Purpose |
| --- | --- |
| `:desktopApp:downloadResolverYtDlpWindows` | Fetch and verify `yt-dlp.exe` |
| `:desktopApp:downloadResolverYtDlpLinux` | Fetch and verify `yt-dlp_linux` |
| `:desktopApp:downloadResolverQuickJsWindows` | Fetch and verify `qjs-windows-x86_64.exe` |
| `:desktopApp:downloadResolverQuickJsLinux` | Fetch and verify `qjs-linux-x86_64` |
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
  -Parchivetune.resolverPlatform=windows-x86_64
```

Only Windows and Linux x86_64 assets are pinned. ARM64 and macOS are not
supported by this packaging yet.

The verifier intentionally does not execute the sidecars, so it works when
staging resources for another target platform. Smoke-test the host-native pair
after staging (Linux paths below; use `app/resources` on Windows):

```bash
desktopApp/build/compose/binaries/main/app/ArchiveTune/lib/app/resources/resolver/yt-dlp --version
desktopApp/build/compose/binaries/main/app/ArchiveTune/lib/app/resources/resolver/qjs --version
```

## Reproducibility and updates

- Versions, URLs, and SHA-256 values are committed in `ResolverSidecars.kt`.
- `DownloadVerifiedFileTask` reuses a cached file only when its digest matches
  and deletes partial downloads.
- Staging deletes the old directory, copies with fixed permissions, and
  normalizes file timestamps to a constant. The manifest is written in a stable
  key order, so repeated staging produces the same bytes.
- Binaries are never committed. `.gitignore` excludes staged/downloaded
  sidecars, while `LICENSES/` remains tracked.
- To update: change the version and all four digests in `ResolverSidecars.kt`
  (even if only one platform changed), refresh the hashes in
  `THIRD_PARTY_NOTICES.md` and this file, and refresh
  `LICENSES/yt-dlp-THIRD_PARTY_LICENSES.txt` from the matching yt-dlp tag when
  yt-dlp changes. Re-run the stage and verify tasks for both platforms before
  publishing installers.

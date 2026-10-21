# Third-party notices: experimental desktop resolver sidecars

ArchiveTune Desktop's experimental resolver can use two external executables that
are staged beside the application by the build:

| Sidecar | Version | Upstream project | License |
| --- | --- | --- | --- |
| `yt-dlp` / `yt-dlp.exe` | 2026.08.19 | [yt-dlp/yt-dlp](https://github.com/yt-dlp/yt-dlp) | Unlicense (public domain) |
| `bun` / `bun.exe` | 1.4.3 | [oven-sh/bun](https://github.com/oven-sh/bun) | MIT; statically links LGPL-2.0 JavaScriptCore/WebKit and LGPL-2.1 tinycc |

The complete license texts are committed under [`LICENSES/`](LICENSES) and are
copied into the application image next to the binaries at
`resolver/licenses/`. This notice is copied to
`resolver/THIRD_PARTY_NOTICES.md`.

## yt-dlp

- Release: <https://github.com/yt-dlp/yt-dlp/releases/tag/2026.08.19>
- Linux asset: `yt-dlp_linux`
  SHA-256 `58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a`
- Windows asset: `yt-dlp.exe`
  SHA-256 `66674953fe251b89f4d08c5f0e35e0728679bd67ab3d7d05c0562af101dd3e7a`
- License: [Unlicense](LICENSES/yt-dlp-UNLICENSE.txt)
- Corresponding source: <https://github.com/yt-dlp/yt-dlp/tree/2026.08.19>

The official frozen executables are produced with PyInstaller 6.22.0 and embed
CPython 3.14.7. They also embed yt-dlp's optional EJS challenge solver
(`yt-dlp-ejs` 0.8.0) and its JavaScript dependencies. The aggregate license
texts shipped by the yt-dlp release are reproduced verbatim in
[`yt-dlp-THIRD_PARTY_LICENSES.txt`](LICENSES/yt-dlp-THIRD_PARTY_LICENSES.txt)
(SHA-256 `472aefe951c7db35e1657c1d13fd337140511ed6f2b329205105ad441c5a02b7`,
from yt-dlp tag `2026.08.19`), which covers CPython (PSF-2.0), requests, urllib3,
websockets, brotli, certifi, cryptography, curl_cffi, Cryptodome, mutagen,
secretstorage, Meriyah (ISC), Astring (MIT), and the other components of that
bundle.

The PyInstaller bootloader is licensed under GPL-2.0-or-later with the
PyInstaller bootloader exception, which permits embedding it in a combined
executable without imposing GPL terms on ArchiveTune. The full text is in
[`pyinstaller-6.22.0-COPYING.txt`](LICENSES/pyinstaller-6.22.0-COPYING.txt).
`yt-dlp-ejs` is public domain under the Unlicense; see
[`yt-dlp-ejs-UNLICENSE.txt`](LICENSES/yt-dlp-ejs-UNLICENSE.txt).

yt-dlp does not bundle FFmpeg in these assets. ArchiveTune does not bundle
FFmpeg with the resolver sidecars.

## Bun

- Release: <https://github.com/oven-sh/bun/releases/tag/bun-v1.4.3>
- Linux asset: `bun-linux-x64.zip`
  SHA-256 `3e730528d89775f03f87f05c06787c4df4099d0a37f3f252e7b829cb23816e18`
  (extracted `bun-linux-x64/bun`
  SHA-256 `7ce7d6b654eddeec20afddf2396317fba6dc19ab52788a27e894e1806bf8f24b`)
- Windows asset: `bun-windows-x64.zip`
  SHA-256 `0bf84e808552beea67bf1202a287a35c6c825d837b287b3f8e9dd513dba35d3d`
  (extracted `bun-windows-x64/bun.exe`
  SHA-256 `095db4abae203d42f29fdec26da4a882fcde6333cdd84e40c0c7828c0cf543f6`)
- License: [MIT for Bun itself](LICENSES/bun-LICENSE.md); the same text
  documents the statically linked LGPL components.
- Corresponding source: <https://github.com/oven-sh/bun/tree/bun-v1.4.3>
- Relink/source instructions: [`bun-WEBKIT-NOTICE.md`](LICENSES/bun-WEBKIT-NOTICE.md)

Bun statically links JavaScriptCore and WebKit (LGPL-2.0) and tinycc
(LGPL-2.1). Those license texts are reproduced in
[`bun-LGPL-2.0.txt`](LICENSES/bun-LGPL-2.0.txt) and
[`bun-LGPL-2.1.txt`](LICENSES/bun-LGPL-2.1.txt). Bun's `LICENSE.md` lists the
remaining statically linked libraries and embedded polyfills with their
licenses (BoringSSL, brotli, libarchive, lol-html, lsquic, mimalloc, zstd,
simdutf, uSockets, ICU, libuv on Windows, and others).

The packaged executable is the official unmodified upstream build. ArchiveTune
does not modify Bun or its JavaScriptCore/WebKit sources; the exact upstream
source revisions and the upstream rebuild instructions are recorded in
[`bun-WEBKIT-NOTICE.md`](LICENSES/bun-WEBKIT-NOTICE.md) so the library can be
studied, modified, and relinked.

## Distribution obligations

ArchiveTune itself remains GPL-3.0-or-later. The stage tasks copy this notice and
the complete `LICENSES/` directory into every distribution that contains the
sidecars. Do not ship a distribution with a manually copied sidecar without
those files.

When updating a pinned version, update `buildSrc/src/main/kotlin/ResolverSidecars.kt`,
the hashes above, the version table in this file, `README.md`, and refresh
`yt-dlp-THIRD_PARTY_LICENSES.txt` from the matching yt-dlp tag when yt-dlp
changes. Re-check Bun's `LICENSE.md` and its linked-library table when Bun
changes. Confirm the bundled EJS version with `yt-dlp --verbose` before changing
`ResolverSidecars.YT_DLP_BUNDLED_EJS_VERSION`.

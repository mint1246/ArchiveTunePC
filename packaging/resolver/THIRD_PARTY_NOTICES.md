# Third-party notices: experimental desktop resolver sidecars

ArchiveTune Desktop's experimental resolver can use two external executables that
are staged beside the application by the build:

| Sidecar | Version | Upstream project | License |
| --- | --- | --- | --- |
| `yt-dlp` / `yt-dlp.exe` | 2026.08.19 | [yt-dlp/yt-dlp](https://github.com/yt-dlp/yt-dlp) | Unlicense (public domain) |
| `qjs` / `qjs.exe` | 0.17.0 | [quickjs-ng/quickjs](https://github.com/quickjs-ng/quickjs) | MIT |

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

## quickjs-ng

- Release: <https://github.com/quickjs-ng/quickjs/releases/tag/v0.17.0>
- Linux asset: `qjs-linux-x86_64`
  SHA-256 `0bfc02511a9f549c28b53880d988fc7cd5d361e90c5e8afdfcd7dc6774ceace5`
- Windows asset: `qjs-windows-x86_64.exe`
  SHA-256 `2aeabf0092c3262d6b2609824418f7dd7ed1f1df939f73b2b15645230cac0d77`
- License: [MIT](LICENSES/quickjs-ng-MIT.txt)
- Corresponding source: <https://github.com/quickjs-ng/quickjs/tree/v0.17.0>

## Distribution obligations

ArchiveTune itself remains GPL-3.0-or-later. The stage tasks copy this notice and
the complete `LICENSES/` directory into every distribution that contains the
sidecars. Do not ship a distribution with a manually copied sidecar without
those files.

When updating a pinned version, update `buildSrc/src/main/kotlin/ResolverSidecars.kt`,
the hashes above, the version table in this file, `README.md`, and refresh
`yt-dlp-THIRD_PARTY_LICENSES.txt` from the matching yt-dlp tag. Confirm the
bundled EJS version with `yt-dlp --verbose` before changing
`ResolverSidecars.YT_DLP_BUNDLED_EJS_VERSION`.

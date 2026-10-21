# Bun JavaScriptCore/WebKit source notice

ArchiveTune Desktop redistributes the **unmodified official Bun 1.4.3**
executable (`bun` / `bun.exe`) as the optional JavaScript runtime for yt-dlp's
EJS challenge solver. Bun itself is MIT-licensed, but the official binary
statically links JavaScriptCore and WebKit (LGPL-2.0) and tinycc (LGPL-2.1).

The complete Bun license text, including the linked-library and embedded
polyfill inventory, is reproduced verbatim in
[`bun-LICENSE.md`](bun-LICENSE.md). The LGPL texts are in
[`bun-LGPL-2.0.txt`](bun-LGPL-2.0.txt) and
[`bun-LGPL-2.1.txt`](bun-LGPL-2.1.txt).

## Corresponding source

| Component | Pinned source |
| --- | --- |
| Bun 1.4.3 | <https://github.com/oven-sh/bun/tree/bun-v1.4.3> |
| Bun's patched WebKit/JavaScriptCore | <https://github.com/oven-sh/WebKit/tree/0c06faadf65bf8e8c8ad3a5a8aca83e1e9ed653f> |

The WebKit commit above is the version pinned by
`scripts/build/deps/webkit.ts` in Bun's `bun-v1.4.3` tag. Bun's own
[`LICENSE.md`](bun-LICENSE.md) documents how to clone that tree and rebuild Bun
against modified JavaScriptCore/WebKit sources:

```sh
git clone https://github.com/oven-sh/bun
cd bun
git clone https://github.com/oven-sh/WebKit vendor/WebKit
bun sync-webkit-source
bun run build:local
```

ArchiveTune does not compile, patch, or statically link Bun or WebKit into its
own code. The packaged file is the official upstream binary, verified by the
committed ZIP and extracted-binary SHA-256 digests. A rebuilt Bun can replace
`resolver/bun` (or `resolver/bun.exe`) as long as it keeps the standard Bun CLI
behaviour that yt-dlp invokes.

## Release artifacts

- Release: <https://github.com/oven-sh/bun/releases/tag/bun-v1.4.3>
- Linux: `bun-linux-x64.zip`
- Windows: `bun-windows-x64.zip`
- Checksums: the release's clearsigned `SHASUMS256.txt`
  (OpenPGP key fingerprint `F3DCC08A8572C0749B3E18888EAB4D40A7B22B59`)

The ZIPs are downloaded and checksum-verified first, then exactly one member is
streamed out and its own SHA-256 is verified before staging. No other archive
entry is extracted.

# Bundled mpv runtime (Windows x64)

ArchiveTune packages this directory through Compose Desktop's `appResourcesRootDir`.
The installed Windows application therefore contains the runtime at
`app/resources/native/windows-x64/mpv.exe`, which is the first packaged-runtime
location checked by `MpvExecutableLocator`.

## Pinned upstream artifact

- Upstream build provider: Shinchiro's `mpv-winbuild-cmake`, listed by mpv.io as
  a Windows build provider: <https://github.com/shinchiro/mpv-winbuild-cmake/releases>
- Release/tag: `20260814`
- Asset: `mpv-x86_64-20260814-git-7b8915bc1d.7z` (standard x86-64, not x86-64-v3,
  so it works on more Windows x64 systems)
- HTTPS release URL: <https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/20260814/mpv-x86_64-20260814-git-7b8915bc1d.7z>
- Published and independently verified archive SHA-256:
  `1bf3b029da2c98e605e00e85f21ee3142f22a1dcc4ceb5c827b5c51e36e390f9`

Only the runtime files needed by ArchiveTune are retained:

| File | SHA-256 |
| --- | --- |
| `common/native/windows-x64/mpv.exe` | `09dc5c350a70b536cc91015e7a856f77c933fa9d7f4c41563666e51c998a0243` |
| `common/native/windows-x64/d3dcompiler_43.dll` | `4b074a3976399dc735484f5d43d04b519b7bdee8ac719d9ab8ed6bd4e6be0345` |
| `common/native/windows-x64/fonts.conf` | `f141c1b89b172d22f213531646c21e288f0ebf3ec46484698896e1b33c626756` |

To reproduce the extraction on Windows, first verify the archive hash, then use
`7z x <archive>` and copy those three files into the paths above. The upstream
build scripts and build configuration are available at
<https://github.com/shinchiro/mpv-winbuild-cmake>. Do not use the installer,
updater, registration scripts, or bundled documentation in the application.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the required source and
license notices.

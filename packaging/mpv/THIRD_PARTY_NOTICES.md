# mpv and codec notices

This distribution includes a Windows x64 build of mpv, plus `d3dcompiler_43.dll`
and its Fontconfig configuration, from the exact artifact documented in
[`README.md`](README.md).

mpv is GPL-2.0-or-later. ArchiveTune is distributed under GPL-3.0-or-later, so
the bundled executable is compatible with ArchiveTune's distribution license.
The executable is a combined build containing FFmpeg and other codec/library
components; their licenses and source obligations remain applicable even though
ArchiveTune invokes mpv as a separate process.

Corresponding source and build instructions are offered at no charge from the
upstream projects:

- mpv source and COPYING: <https://github.com/mpv-player/mpv>
- Shinchiro Windows build scripts/configuration: <https://github.com/shinchiro/mpv-winbuild-cmake>
- FFmpeg source and licensing: <https://ffmpeg.org/download.html> and
  <https://ffmpeg.org/legal.html>

For source needed to reproduce the bundled Windows runtime, start with the
pinned build artifact/version in `README.md`, use the Shinchiro build scripts,
and obtain the dependency sources specified by those scripts. Distributors who
modify or replace the runtime must preserve all mpv, FFmpeg, Fontconfig, and
other embedded dependency notices and must satisfy the relevant GPL/LGPL source
and relinking/source-offer requirements for their resulting binary.

`d3dcompiler_43.dll` is a Microsoft Direct3D compiler redistributable included
unchanged by the upstream mpv Windows artifact for graphics/shader support. It
is not open-source and has no corresponding-source offer; redistribution is
subject to Microsoft's DirectX end-user runtime license. It can be removed only
after testing the replacement runtime on the supported graphics paths.

# VLC and codec notices

ArchiveTune Desktop uses VLCJ to load libVLC in-process. VLCJ is licensed under
the Eclipse Public License 2.0. libVLC and its bundled plugins are distributed
under the licenses of VLC and their individual dependencies, including GPL and
LGPL components where applicable.

The final Windows distribution must include the notices and corresponding source
offers required by the exact VideoLAN runtime artifact used for the release.
Keep this file with that artifact and update it with the pinned VLC version,
download URL, checksums, and dependency notices before publishing installers.

ArchiveTune itself remains GPL-3.0-or-later. No decoded-audio callback output is
used in the normal playback path; audio is rendered by libVLC's normal system
audio output.

## Pinned runtime and Canvas support

The runtime uses VideoLAN VLC 3.0.21 for Windows x64. Canvas additionally requires
`plugins/video_chroma/libswscale_plugin.dll` and the existing vmem, avcodec and MP4
plugins. The official cache generator is used during staging and is removed from
the installer payload after it creates the timestamp-correct plugin cache.

Official archive: https://download.videolan.org/pub/videolan/vlc/3.0.21/win64/vlc-3.0.21-win64.zip

SHA-256:

- Archive: `a0b7ec02b50adf6417eed014fb8df50af39690505a4225b85b3dc2ed17d14843`
- swscale plugin: `167ebdf10029df2fa3bb998798e8275c94ebdca58a0110b79a017c73a5c83904`
- cache generator: `ce6aff142d300ce7caebd91a87a09a1752a56cfa324ccaac2bd2d66ad36288fe`

Corresponding VLC source: https://download.videolan.org/pub/videolan/vlc/3.0.21/vlc-3.0.21.tar.xz
The swscale plugin includes FFmpeg libswscale; FFmpeg source and license information:
https://ffmpeg.org/download.html and https://ffmpeg.org/legal.html.

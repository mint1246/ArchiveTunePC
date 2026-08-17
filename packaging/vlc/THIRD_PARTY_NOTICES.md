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

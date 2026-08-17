# Bundled libVLC runtime (Windows x64)

ArchiveTune packages this directory beside the desktop application. The required
runtime layout is:

```text
native/windows-x64/vlc/
  libvlc.dll
  libvlccore.dll
  plugins/
```

The files must come from a redistributable VideoLAN VLC Windows x64 build. Do not
rely on VLC being installed globally, present on `PATH`, or discoverable through
the Windows registry. `VlcRuntimeLocator` resolves this directory relative to the
installed application.

Only the libVLC DLLs, their required runtime dependencies, and the plugins needed
by ArchiveTune should be distributed. Keep the corresponding VideoLAN, VLC, and
codec license notices with the packaged application.

The repository intentionally does not contain third-party binary DLLs yet. The
release packaging step must stage a verified runtime under
`native/windows-x64/vlc` before producing an installer.

# ArchiveTune Windows media integration

This isolated JVM module offers a platform-neutral `WindowsMediaControls` service. It consumes
`PlaybackSnapshot`, retains safe now-playing metadata (never source URLs or request headers), and
emits play/pause, next, and previous commands from Windows hardware media keys. A
`PlaybackMediaCommandRouter` maps all service commands, including seek forward/backward, to the
existing `PlaybackController`.

## Implemented Windows subset

`JnaWindowsHotKeySource` uses `RegisterHotKey` on a dedicated Win32 message-loop thread for
`VK_MEDIA_PLAY_PAUSE`, `VK_MEDIA_NEXT_TRACK`, and `VK_MEDIA_PREV_TRACK`. Callbacks are dispatched
onto a separate app-owned executor. It has no paths, bundled binaries, or network requests, and
returns `UNSUPPORTED` safely off Windows or when registration is unavailable.

## Explicit SMTC boundary

This module **does not publish Windows System Media Transport Controls (SMTC) or shell
now-playing metadata**. JNA's maintained `jna-platform` artifacts bind Win32 but do not provide a
maintained Java/WinRT projection for `Windows.Media.SystemMediaTransportControls`. Pretending that
the retained `presentation` flow changes Windows' media flyout would be incorrect. The flow is
provided as the exact integration seam for a future, separately packaged WinRT bridge.

## Build and licensing

Run `..\\gradlew.bat -p windows-media-integration test` from this directory. The only native
binding is `net.java.dev.jna:jna(-platform)-jpms:5.17.0`, downloaded from Maven Central. JNA is
dual-licensed LGPL-2.1-or-later or Apache-2.0; ArchiveTune can use the Apache-2.0 option. This
module's own code remains GPL-3.0-or-later with the application.

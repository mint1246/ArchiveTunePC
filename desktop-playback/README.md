# ArchiveTune desktop playback

This module provides a real audio backend for Windows x64 and a small future-compatible Unix IPC
transport. It launches `mpv` as a supervised child process and controls it through JSON IPC. No
media URL is hardcoded, and mpv's YouTube extraction (`yt-dlp`) is not used. ArchiveTune continues
to resolve YouTube Music streams through its own backend and hands the resulting direct audio URL
to `MpvPlaybackController`.

## Why mpv

ArchiveTune currently resolves audio-only adaptive formats including WebM/Opus and MP4/AAC. mpv's
FFmpeg-backed demuxers handle those formats, ranged HTTP seeking, local audio formats, buffering,
speed, and volume consistently. Running the player out of process also gives the JVM a clear
lifecycle boundary: `close()` sends `quit`, closes IPC, waits briefly, then destroys a stuck child.
It avoids an in-process native ABI/JNA binding and prevents an mpv crash from taking down Compose.

## Runtime discovery and packaging

Lookup order is:

1. JVM property `archivetune.mpv.path`
2. environment variable `ARCHIVETUNE_MPV_PATH`
3. `<app>/native/<os>-<arch>/mpv.exe` (Windows x64 is `native/windows-x64/mpv.exe`)
4. `<app>/native/mpv.exe`, then `<app>/mpv.exe`
5. `mpv.exe` from `PATH`

`archivetune.app.dir` can identify the packaged application resource directory. A missing binary,
IPC startup failure, or unexpected exit produces `PlaybackStatus.ERROR` with
`ENGINE_UNAVAILABLE`/`ENGINE`; the app does not crash. Current CI and this development machine do
not contain mpv, so real audio output cannot be exercised here.

For a self-contained Windows distribution, package a trusted x64 mpv build and its adjacent DLLs
under `native/windows-x64/`, preserve its notices/source offer, and set `archivetune.app.dir` to the
resource root at launch. Do not download a mutable native binary at application startup. mpv build
license varies with enabled FFmpeg/codecs (commonly GPLv2+); ArchiveTune is GPL-3.0, but the exact
redistributed build and codec patent obligations still require a release review.

## ArchiveTune resolver adapter

Remote queue items should use `PlaybackSource.Deferred(mediaId)`. At load or retry time the
`PlaybackSourceResolver` should call the existing portable equivalent of
`YTPlayerUtils.playerResponseForPlayback`, then map its result like this:

```kotlin
val profile = StreamClientUtils.resolveRequestProfile(playbackData.streamUrl)
ResolvedPlaybackSource(
    uri = playbackData.streamUrl,
    mimeType = playbackData.format.mimeType,
    expiresAtEpochMs = System.currentTimeMillis() +
        playbackData.streamExpiresInSeconds * 1_000L,
    headers = buildMap {
        put("User-Agent", profile.userAgent)
        profile.origin?.let { put("Origin", it) }
        profile.referer?.let { put("Referer", it) }
        // Add Cookie/Authorization only when the selected stream client actually requires it.
    },
)
```

The backend replaces `http-header-fields` for every item, including an empty list for local files,
and rejects invalid names or CR/LF values. URLs and headers are sent over IPC rather than process
arguments and are never copied into player state or diagnostic messages.

Local tracks use `PlaybackSource.LocalFile(path)`. `DesktopConcretePlaybackSourceResolver` verifies the
file and converts it to a properly escaped `file:` URI before mpv sees it.

## Standalone verification

Until the root build includes these modules:

```powershell
.\gradlew.bat -p desktop-playback test
```

The nested settings file exists only for this isolated build and is ignored when the directories
are included by the repository's root settings.

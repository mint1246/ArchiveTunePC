# Linux desktop development

Use JDK 21 for the Gradle build. Run the application with:

```sh
./gradlew :desktopApp:run
```

Build an application image with `./gradlew :desktopApp:createDistributable`. The
launcher is `desktopApp/build/compose/binaries/main/app/ArchiveTune/bin/ArchiveTune`.

## AppImage packaging

`packaging/linux/build-appimage.sh` wraps that jpackage image in an AppImage. It
does not compile, add a JVM, or bundle libVLC or a browser: the AppDir payload is
the existing image copied unchanged into `usr/lib/archivetune`, and `AppRun`
forwards its arguments to the jpackage launcher. The launcher and the trimmed
runtime keep their relative `lib/app` and `lib/runtime` layout.

The image must contain the yt-dlp resolver sidecars staged by
`stageBundledResolverSidecars` under
`lib/app/resources/resolver` (`yt-dlp`, `bun`, the manifest, notices, and
licenses). The script refuses to package an image whose sidecars are missing,
not executable, or staged for a different platform, and rejects a stale `qjs`
left from the retired QuickJS packaging. Run
`:desktopApp:createDistributable` (or its `stageBundledResolverSidecars`
finalizer) before `--skip-build`; the Gradle image uses
`lib/app/resources` on Linux rather than Windows' `app/resources`.

Requirements:

- An x86_64 Linux host. The resolver sidecars are pinned only for
  `linux-x86_64`; the AppImage script rejects any other staged platform.
- JDK 21 in `JAVA_HOME` when the script runs the Gradle build.
- `appimagetool` passed through `APPIMAGETOOL`, either a plain binary or an
  AppImage. There is no download step.

```sh
JAVA_HOME=/path/to/jdk-21 \
APPIMAGETOOL=/path/to/appimagetool.AppImage \
  packaging/linux/build-appimage.sh
```

To package an image that was already built and verified, skip Gradle:

```sh
APPIMAGETOOL=/tmp/archivetune-appimage-tools/appimagetool.AppImage \
  packaging/linux/build-appimage.sh --skip-build
```

The result is `dist/ArchiveTune-15.10-x86_64.AppImage` (`dist/` is ignored by
git). `--version`, `--arch`, `--image`, and `--output` change the inputs and the
artifact name; `--keep-staging` preserves the generated AppDir for inspection.
Staging lives under `build/packaging/appimage`, and the script deletes only that
directory, guarded by an explicit path check. The script also rejects an
architecture that differs from the host, because the jpackage runtime is built
for the host architecture.

The AppImage is not distribution-independent. It carries the runtime for the
build host's architecture and glibc, so the target needs a compatible C library
and desktop stack, and the runtime dependencies below still apply: a native
Chromium browser, `secret-tool` with an unlocked Secret Service keyring, system
libVLC with its plugins, and a normal desktop session with D-Bus and the usual
GL/fontconfig libraries. On systems without libfuse2, run the AppImage with
`--appimage-extract-and-run` or set `APPIMAGE_EXTRACT_AND_RUN=1`.

Only build output is staged. No account data, browser profile, keyring entry,
configuration, cache, or log is copied into the AppDir.

For an optional desktop-menu entry, move the AppImage to a stable location such
as `~/Applications` and add it in Gear Lever, which writes the desktop entry and
icon from the metadata bundled here (`archivetune.desktop`,
`usr/share/icons/hicolor/192x192/apps/archivetune.png`). The bundled entry
carries `X-AppImage-Version`, so Gear Lever reports the packaged version (15.10)
instead of an unknown one. Point Gear Lever at the installed copy, not at
`dist/`, because rebuilding overwrites the file in place.

## Runtime dependencies

- A native Chromium browser, such as Brave, Chrome, or Chromium, on `PATH` for
  managed Google sign-in and browser-generated playback proof. Firefox can export
  cookies for manual import; managed sign-in uses Chromium's DevTools protocol.
- `secret-tool` from libsecret and an unlocked desktop Secret Service keyring
  for account credentials. Linux does not use Windows DPAPI or plaintext session
  files. Run from a normal desktop session with its D-Bus environment available.
- System libVLC and its audio/network plugins. Linux does not load the bundled
  Windows VLC DLLs.

Managed sign-in uses a dedicated ArchiveTune browser profile. Complete Google
sign-in, wait until YouTube Music is signed in, then close that browser window
normally. ArchiveTune imports the session automatically after the browser has
saved its cookies. Linux sign-in does not terminate the browser to finish: a
SIGTERM can discard freshly written cookies.
Manual import accepts Cookie-Editor JSON exported from `music.youtube.com`.

Configuration, account browser data, caches, and logs follow the XDG directories
under `archivetune`. `ARCHIVETUNE_HOME` can select a separate development data
root. Do not share cookie exports, browser profiles, or keyring contents in bug
reports. Account validation diagnostics contain fixed stage names and error
categories without session values.

## Verification

```sh
./gradlew :core:test :desktop-auth:test :desktop-backend:test :stream-resolver:test :desktopApp:test :desktop-playback:test
```

The real libVLC integration tests run when a local runtime is available. To
exercise the real Linux keyring with a temporary entry containing synthetic
bytes:

```sh
ARCHIVETUNE_TEST_SECRET_SERVICE=1 ./gradlew :desktop-auth:test --tests '*SecretServiceIntegrationTest' --rerun
```

The browser smoke uses a disposable profile and synthetic cookies. The BotGuard
smoke contacts YouTube with a synthetic session identifier and reports timing
without printing tokens:

```sh
ARCHIVETUNE_BRAVE_SMOKE=1 ./gradlew :desktop-auth:test --tests '*ManagedChromiumBraveSmokeTest' --rerun
ARCHIVETUNE_BOTGUARD_SMOKE=1 ./gradlew :desktop-auth:test --tests '*DesktopBotGuardSmokeTest' --rerun
```

To check anonymous stream resolution for a currently available public video:

```sh
ARCHIVETUNE_TEST_VIDEO_ID=<video-id> ./gradlew :desktop-backend:test --tests '*DesktopStreamIntegrationTest' --rerun
```

YouTube may require authentication even for public videos. This anonymous test
does not read the application's stored account session.

An actual Google account is still needed to verify end-to-end sign-in and
authenticated YouTube playback. Unit tests use synthetic credentials and do not
contact account services.

## Sign-in troubleshooting

Linux locale tags such as `en_IL.UTF-8` are valid system settings, but YouTube
rejects the resulting `hl=en-IL` with HTTP 400. The app now sends the display
language and country separately (`hl=en`, `gl=IL` in this case). Updating only
the launcher without rebuilding `:core` will retain the old behavior.

Validation has a 45-second overall deadline. Network failures, timeouts, invalid
requests, temporary server failures, and unavailable secure storage have separate
messages. A failed import leaves the previously saved account active. Account
metadata authenticates the cookie; channel and visitor lookups enrich it, with
the signed-in Music homepage used when the channel-list endpoint is unavailable.

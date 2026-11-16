# Android reference and parity captures

Android is the target, including its behavior. These captures are observational
references from the current 15.1.0 GMS x86_64 debug build. They are not a certificate
of iOS parity. macbox has been restored and the current component port has been
built, installed and launched. Initial paired captures show remaining differences;
there is no matching-pixel or feature-parity certification.

`reference/android-15.1/manifest.json` records the reference APK and device. Home,
Search, Library and Friends use a fresh logged-out account with the default light
system palette and Poppins. The player uses the actual selected remote song,
paused at 0:24; its animated backdrop had already advanced before pausing.
Recapture both sides with identical data and animation state before using pixel
metrics as a visual acceptance gate. Do not substitute fake production data.

For each screen/state, use the T3 Device panel and the `agent-device` command
returned by `device_open`. Capture both apps at matching logical viewports, theme,
font scale, locale, content, scroll position, queue, playback position and animation
state. iOS captures from this session are 402x874 at 1x; Android is 1206x2622 at 3x.
Keep system insets/chrome recorded separately. Never scale one layout to conceal
a geometry difference, and never mask app content to make a comparison pass.

```sh
python3 iosApp/parity/scripts/compare_screenshots.py \
  --android iosApp/parity/reference/android-15.1/search.png \
  --ios /path/to/new-ios-search.png --output iosApp/build/parity/search
```

The tool writes original-size-normalized frames, an overlay, the raw difference,
an amplified difference and metrics with source hashes. It does not infer matching
app state or certify feature parity. Capture fresh comparable states, inspect each
visible difference, then verify the corresponding interaction on both platforms.
See `../ANDROID_PARITY.md` for the incomplete feature inventory.


The recovery pass fixed a native startup crash caused by Android vector `dip`
dimensions: Compose's Apple vector loader accepts `dp`/`px` instead. The simulator
build script now runs `scripts/verify-resources.py` before Xcode. Initial light-mode
Search, Friends and Library captures and unmasked comparisons are under
`../build/parity/current/`. They retain system chrome and all visible differences.
The Android debug build's title is “ArchiveTune Debug” (truncated in the header),
whereas the iOS display title currently says “ArchiveTune”. OS insets differ too.
The current layout, content and feature differences remain correction targets.

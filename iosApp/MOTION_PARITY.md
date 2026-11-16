# Android/iOS motion and native capture ledger

The target is Android appearance, gestures and animations. This pass is not an exact-parity certification. Source parameters, passing unit tests and static screenshots are separate evidence; none replaces a matching-state recording.

## Implemented motion contracts

| Interaction | Android reference | Shared implementation |
| --- | --- | --- |
| Main tabs | MainActivity NavHost: 250 ms enter / 200 ms exit fades | AndroidMotion.kt; native tab recording captured |
| Detail navigation | MainActivity: half-width push/pop plus fades | AndroidRouteFrame freezes outgoing browse state; bounded route saveable state and browse back stack |
| Floating header | ui/utils/AppBar.kt non-consuming quick-return connection | AndroidAppBarMotion.kt, Home/Search/Library state and nested scroll |
| Navigation bar | Dimensions.kt no-bounce low-stiffness spring | 78 dp bar slides below screen; proximity-driven corners join the mini player |
| Selected tab tap | MainActivity scroll-to-top/reset | Shared page scroll requests and header reset |
| Search double tap | MainActivity Search shortcut | Verified natively: double tap opens query/history |
| Player sheet | BottomSheet.kt: low spring for soft commands, medium-low for drag settlement | Always-composed host, finger-follow drag, velocity/midpoint anchors, quarter-progress crossfade and real dismiss |
| Mini-player swipe | MiniPlayer.kt sensitivity/threshold and low spring return | Horizontal drag, queue operations, default title/artist fades |
| Full artwork | Thumbnail.kt previous/current/next slots and 500 px/s snap | Three-slot lazy grid, high-resolution source-preserving image URLs, double-tap five-second seek overlay |
| Queue | Queue.kt reorder and BottomSheet nested scrolling | Finger-follow sheet, nested scroll handoff, live indexed moves and animated rows; occurrence identity/continuous edge scroll and broader layout still need work |
| Lyrics | Player.kt MikoLyricsTransition | Damping .82 / medium-low spring; source scale/translation/alpha/corners. Player content is removed while lyrics is the active page. Parent sheet drag is disabled in overlays. |
| Library | LibraryScreen.kt pager/chip springs and LibraryMixScreen ShortcutCard | HorizontalPager, overlay chips, content inset, source press scales/colors and byte-channel shortcut blending |
| Settings preferences | PreferenceSelectionBottomSheet / preference cards | Source-sized sheets/options/cards and source press/motion specs; full settings coverage remains incomplete |

## Native corrections verified on 2026-11-12

- macbox was restarted after the user approved enabling virtual audio. It now exposes Apple Virtual Sound Device at 48 kHz. AVPlayer starts and reports advancing playback; a headless output does not prove speaker audibility.
- Full player no longer draws Home/Search content behind the shared artwork backdrop.
- Artwork-mode text and foreground roles are white, as explicitly requested. The theme also provides its current on-surface color to plain text, rather than inheriting black.
- Player center transport uses an opaque contrasting icon color; the transparent shell surface cannot serve as an icon pigment. Its corner spring now follows V4.
- Lyrics is the sole visible page when open. Player artwork, metadata, seek bar and transport are absent from the lyrics capture.

Latest verified captures: `build/parity/motion-review/white-player-final.png`, `white-lyrics-final.png`, and `white-player-lyrics-motion.mp4`. The simulator ZIP at `build/ArchiveTune-simulator.zip` includes those fixes. Logs and source hashes should be recorded again after subsequent builds.

## Comparison evidence and limitations

Unmasked, density-normalized Search and Friends comparisons are under `build/parity/motion-review/{search,friends}`. Both platforms were signed out, in light mode, without a mini player. Their metrics still show substantial differences. System chrome/insets remain visible; no app content is masked or geometrically corrected. Live feed, result, queue and artwork animation states must match before a visual acceptance result can be asserted.

Original tab and player recordings are retained beside `manifest.json`. Android recordings contain encoder pillarboxes. Both recorders use variable frame rates with idle gaps; average FPS is not an animation-performance measurement. Recordings have not established identical transition timing/easing or frame-by-frame appearance. The earlier player recordings have different selected video IDs/positions and cannot serve as a matching-state comparison.

A native media-duration discrepancy is still under investigation: selected song metadata is 232 s; a complete downloaded source probes as AAC-LC 44.1 kHz / 231.746667 s, but the simulator UI reports 7:43. Do not hide this by substituting metadata or scaling the reported clock. Native error-dialog/crash recovery also still needs targeted acceptance testing.

All-screen feature parity remains incomplete, particularly authenticated sync, downloads/local media, full social features, complete collection/detail/menu/settings states, rich synchronized lyrics and alternate player designs. See ANDROID_PARITY.md. Every app-visible difference remains a correction target.

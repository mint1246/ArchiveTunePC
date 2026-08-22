# ArchiveTune Desktop — Overnight Engineering Log

Run started: 2026-08-20
Branch: `main`
Orchestrator team: `team_b9772141cf06`

## Rules for this run

- Think in real-world desktop music-player workflows, not isolated feature checkboxes.
- Prefer simple changes that reuse existing architecture over new abstraction layers.
- A feature/fix is **not** listed as fully working until it has appropriate automated/build verification and, when UI/runtime behavior matters, manual verification. Computer Use is currently paused entirely because the user is actively using the PC.
- Preserve pre-existing uncommitted work. Do not silently revert or overwrite it.
- Workers are reusable. When one task finishes, repurpose that worker to the highest-value next task.
- Keep performance, memory, discoverability, reliability, and desktop-native ergonomics in scope alongside features.
- Avoid committing `.mcp-state/` or other agent-runtime state.

## Baseline before new overnight edits

`main` was already 6 commits ahead of `origin/main`.

Recent local commits at start:
- `ae21a20b0` restore profile-driven artist album recovery
- `4c2240054` fix missing regional artist albums
- `631af6700` match desktop artist section layout to Android
- `4dbfe1bf2` fix locale-dependent artist album recovery
- `ae9e53185` fix desktop artist album recovery
- `60b6184de` Fix post-start stream re-resolution

Pre-existing uncommitted product changes at start:
- `desktop-lyrics-ui/.../DesktopLyricsPane.kt`
- `desktop-play-together/.../PlayTogetherManager.kt`
- `desktopApp/.../Main.kt`
- `desktopApp/.../DesktopMusicSurface.kt`
- `desktopApp/.../DesktopQueuePanel.kt`

Those changes are primarily the in-progress Play Together integration: guest queue permissions, shared queue editing, guest playback-control restrictions, and lyrics seek restrictions.

Generated/untracked agent state at start:
- `.mcp-state/` — never commit this.

## Existing project priorities carried into the run

From `TODO.md` / current product state:
- playlist downloading from playlist surfaces
- make cached/downloaded songs start immediately
- continue lyrics polish toward SpicyLyrics quality
- expand desktop settings/customization substantially
- preserve/fix native-feeling title bar and desktop interaction quality

From `DESKTOP_PERFORMANCE_PLAN.md`:
- reduce screen-owned remote state/reloads
- preserve Home/Library/Search/detail navigation state
- stale-while-revalidate instead of blocking refreshes
- deduplicate concurrent resource loads
- keep background maintenance lower priority than direct user actions
- keep caches bounded and avoid RAM regressions

## Current assignments

### worker1 — shared playback HTTP + first-request recovery
Status: implementation in isolated worktree `ArchiveTunePC-w1-http`
Scope: reuse one playback-scoped OkHttp transport across tracks and ensure initial 403/416 range failures reach existing stream recovery. Must add focused regression tests and commit its branch.

### worker2 — native window chrome reliability
Status: implementation in isolated worktree `ArchiveTunePC-w2-chrome`
Scope: fix concrete titlebar drag/maximize/restore/resize/DPI/multi-monitor roughness without redesigning the shell. Add pure geometry/state tests where feasible and commit its branch.

### worker3 — local-library artwork RAM cleanup
Status: implementation in isolated worktree `ArchiveTunePC-w3-memory`
Scope: stop retaining unused embedded artwork byte arrays per local track, preserve metadata behavior, update focused tests, and commit its branch.

### worker4 — download Retry re-resolution
Status: implementation in isolated worktree `ArchiveTunePC-w4-download`
Scope: replace stale signed-URL reuse on user Retry with a fresh resolver pass using stable song metadata only. Add deterministic tests proving the resolver is called again and can return a new URL/headers, then commit its branch.

### worker5 — advisor / second orchestrator
Status: reviewing pre-existing Play Together diff
Scope: protocol/permission/state-sync review of guest queue editing and playback restrictions, especially disconnect/error recovery, index safety, transport races, local-file handling, and missing tests. No edits unless reassigned.

## Accepted audit findings / plan corrections

- The broad cache/repository performance plan is already substantially implemented and measured; do not rebuild it tonight.
- Playlist-level `Download all` already exists despite stale wording in `TODO.md`; focus on correctness/latency instead of re-adding it.
- libVLC cold/plugin initialization has already been optimized; current playback work should target concrete runtime reliability/per-track overhead.
- Real crossfade is intentionally deferred until safer audio groundwork exists because the current desktop controller owns one VLC player; a proper overlap design would need a second player and careful transition ownership.
- EQ remains a good near-term customization feature because vlcj already exposes equalizer support, but it comes after the first reliability batch unless another worker frees safely.
- High-value UX issues found: duplicate browse back controls, Downloads missing from compact navigation, lyrics artist/album links opening behind immersive lyrics, no forward navigation, queue overlay/Esc behavior, weak keyboard discoverability, and dead accent preference infrastructure.
- High-value performance issues found: 20 Hz full playback snapshot fan-out, unused embedded local artwork retention, per-track OkHttp creation, large in-memory whole-object fallback, duplicated artwork fetch/decode pipelines, and frequent queue persistence writes.
- Real-world workflow audit also found: stale signed-URL reuse on download Retry; party disconnect can leave guest controls locked; duplicate video IDs in a playlist can start the wrong occurrence; playlist mutations can leave cached details stale; playlist action errors are poorly surfaced; scrolled queue drag math may target the wrong item; partial batch downloads can start invisibly without opening Downloads; search errors lack a Retry action; settings-triggered local-library scan failures are not surfaced; logout/profile cleanup can block the UI thread.
- Current party-control gating is confirmed too broad: UI checks raw `mode == GUEST`, so a disconnected `GUEST + ERROR` state can remain artificially locked. Restrictions should follow an actually connected guest session, while disconnected queue mutation must fail rather than pretend to send.

## Candidate queue

- verify and finish the pre-existing Play Together queue/lyrics integration before building more on top of it
- native/package smoke harness with machine-readable log assertions
- download/cache-first playback correctness and instant local-hit path
- EQ/preamp/presets with persisted settings using vlcj's existing equalizer API
- output-device selection with safe fallback when devices disappear
- accent/theme customization wired to the already-persisted accent seed
- fix duplicate browse back controls and compact-nav Downloads omission
- fix immersive lyrics links so artist/album navigation becomes visible immediately
- queue overlay responsiveness + Escape behavior
- safe desktop keyboard shortcuts with text-input focus protection
- split/throttle high-frequency playback state consumers and Windows media updater
- reduce repeated artwork download/decode work for dynamic theme + lyrics background
- investigate next-track pre-resolution to reduce transition gaps without prematurely implementing full crossfade

## Fully working changes completed tonight

- `9f169814b` — Play Together authority/lifecycle checkpoint landed on `main` after repeated strict advisor reviews. Guest/host authority, queue permissions/revisions, local-file exclusion, hardware media-command gating, transient speed/repeat/shuffle policy, generation-owned socket/RTC/tunnel resources, atomic CONNECTED publication, lifecycle-fenced suspended playback mutations, disconnect recovery, and stale-session rejection are committed together. Latest forced pre-commit gate: 62/62 tasks executed across party/windows-media/desktop tests+compile; `git diff --check` clean apart from line-ending warnings. Advisor final verdict: GO.

## Verified worker commits awaiting integration

- `f97e2ac41` — playback callback HTTP reliability/performance: one controller-scoped OkHttp transport is reused across signed-stream callback media; first-request 403/416 failures now reach the existing stream-recovery path. Verified with `:desktop-playback:test`, `:desktopApp:compileKotlin`, and `git diff --check`. Awaiting advisor integration review/cherry-pick before promotion to Fully working.
- `39102662a` — local-library RAM cleanup: removed dead embedded-artwork byte arrays from indexed `LocalTrack`/metadata and stopped reading up to 5 MiB of artwork per track during scans. Verified with `:desktop-library:test`, `:desktopApp:compileKotlin`, and focused no-artwork-retention assertions. Awaiting advisor integration review/cherry-pick.
- `5cd6350` — download Retry now re-resolves a fresh stream instead of replaying expired signed URLs/headers. Verified with focused retry tests, `:desktop-downloads:test`, and `:desktopApp:compileKotlin`. Awaiting advisor integration review/cherry-pick.
- `e17a12a3d` — desktop navigation/keyboard ergonomics: Downloads remains reachable in compact navigation, browser-style forward history + Alt+Right, Ctrl+K Search, Ctrl+, Settings, and focus-safe shortcut routing. Verified with `:desktopApp:test`, focused navigation/shortcut tests, and `:desktopApp:compileKotlin`. Manual orchestrator UI verification pending integration.
- `9fd538c35` — real accent customization: Default + Blue/Purple/Rose/Orange/Teal presets, persistent `accentSeedArgb`, deterministic static theme derivation, and Dynamic artwork theme remains authoritative. Verified with `:desktop-settings:test`, `:desktopApp:test`, `:desktopApp:compileKotlin`, and `git diff --check`. Manual orchestrator visual/persistence verification pending integration.
- `fc44427d2` — live VLC EQ v1: enable/bypass, preamp, per-band gains, VLC presets, persistence, and native-player lifecycle reapplication behind a desktop-specific audio-effects facade. `:desktop-playback:test :desktop-settings:test :desktopApp:compileKotlin` PASS. Manual audible/persistence verification pending integration.
- `ec6a939c9` — native window chrome reliability: maximized drag restore keeps cursor anchor/gesture continuity, multi-monitor/negative virtual coordinates, remembered floating bounds, startup-maximized fallback, pure resize/min-size geometry, and a real compact drag region. Focused + full desktopApp tests PASS; manual window verification is orchestrator-only.
- `4e5935958` — Windows media-key registration now reflects actual `RegisterHotKey` success, handles partial/all-fail registration, unregisters only owned IDs, and avoids failed-start loop/thread leaks via a fakeable Win32 boundary. `:windows-media-integration:test :desktopApp:compileKotlin` PASS with no live OS probing.
- `53e265df9` — logout/profile cleanup moved to structured `Dispatchers.IO`; slow managed Chromium profile deletion no longer blocks the UI dispatcher and cleanup failures preserve signed-out semantics. `:desktop-auth:test :desktopApp:test :desktopApp:compileKotlin` PASS.
- `1ef3a65b0` — completed downloads now win before remote stream resolution through normal LocalFile validation; stale/missing local candidates fall back exactly once to remote. Focused restored-queue/local-hit tests + playback/download/app compile PASS.
- `99965cb01` — Windows media presentation no longer consumes 50 ms position-only playback ticks. Deterministic test collapses ~200 steady 10-second ticks to one Windows-media update while play/pause/metadata/capabilities/volume/duration still update immediately.
- `7ddb94b89` — playlist duplicate video IDs preserve exact original occurrence/index through filtering, so `[A,B,A]` can play the intended first or second A. Focused occurrence tests + compile PASS.
- `67a8ead7d` — successful album/artist links from immersive lyrics now dismiss lyrics and reveal the requested existing detail view; failures/stale navigation do not dismiss. desktopApp + lyrics tests/compile PASS; manual visual check pending.
- `00f18df7e` — failed desktop search has explicit Retry through the existing force-refresh/generation path; blank retry is ignored and stale retry responses cannot overwrite a newer query. backend + desktopApp tests PASS.
- `6eeb76102` — queue drag/reorder now targets visible LazyColumn row centers using stable queue-entry IDs and viewport offsets, fixing wrong-item moves in scrolled/autoscrolling queues. Pure scrolled/edge/duplicate-ID tests + compile PASS; manual drag verification pending integration.
- `e0e4b8c6c` — playlist add/remove/rename/delete now invalidates revisioned detail cache correctly, stale pre-mutation loads cannot overwrite fresh content, and failures stay visible on the existing detail instead of optimistically losing context. backend + desktopApp tests/compile PASS.
- `4a4b105b6` — user download Retry ownership is retargeted/pruned after successful fresh attempts, and partial batch downloads navigate to Downloads so successful work is visible with an explicit queued/failed count. desktopApp + desktop-downloads tests/compile PASS.
- `1311c9f84` — closing ArchiveTune's managed Chromium sign-in process now aborts interactive login promptly instead of waiting for the long completion timeout; success/cancel/exit races are covered with fake Process tests. desktop-auth + desktopApp tests/compile PASS.
- `c50890857` — Library has a fast local in-memory filter across remote playlist title/author and local title/artist/album, preserving section order and useful empty states. Focused helper tests + desktopApp compile PASS.
- `a263a0dd1` — queue overlay width is bounded responsively against the actual content viewport and Escape now dismisses lyrics > queue > search in explicit precedence while text fields still get first chance. desktopApp tests/compile PASS.
- `df7274526` — desktop Search now includes already-loaded local-library tracks in a distinct local section, matched by title/artist/album without extra backend work. Local-only hits survive remote empty/error states; local playback carries the indexed duration hint. desktopApp + desktop-backend tests/compile PASS.
- `c85db79d3` — album/artist/playlist detail pages no longer duplicate the shell Back control, and non-obvious mini-player/queue/window icon controls gained concise Material tooltips without changing accessibility descriptions or click behavior. desktopApp tests/compile PASS; hover/visual verification deferred while Computer Use is paused.
- `f9727ab96` — persistent VLC audio-output selection: System default + enumerated devices, live switching, saved-device restore, backend/player reattach, and missing-device fallback. Playback/settings/desktopApp tests+compile PASS; manual audible/hot-plug check deferred while Computer Use is paused.
- `0dce988cc` — Settings now surfaces the existing local-library scan progress/failure/count state and Retry/Rescan without duplicating repository state; overlapping scans are guarded. desktop-library + desktopApp tests/compile PASS.

## Bugs / rough edges discovered tonight

- `.mcp-state/` appeared as untracked repo content; `.gitignore` now ignores both `/.mcp-state/` and `/.mcp-checkpoints/`.
- Computer Use approval-handshake issue was fixed during the run. `node_repl` + `@oai/sky` can enumerate, activate, capture, and send input to the live ArchiveTune window, but all Computer Use is now paused by user request while they actively use the PC.
- Multiple-agent live desktop access caused interference risk: the window-chrome worker attempted direct `.NET`/Win32 (`Add-Type` + `user32.dll`) probing. New orchestration rule: only the main orchestrator may interact with the live Windows desktop. All workers are code/build/test-only and must hand manual UI verification back to the orchestrator.
- A temporary `globalThis` persistence anomaly occurred while Computer Use was being interrupted with Escape; treat it as interruption/session reset unless it reproduces without user interruption.

## Verification record

- Baseline `:desktopApp:compileKotlin` on the pre-existing dirty main tree: PASS on 2026-08-20.
- Baseline `:desktop-play-together:test :desktop-lyrics-ui:test :desktopApp:test`: PASS on the pre-existing dirty main tree.
- Broader workflow-audit baseline `:desktopApp:test :desktop-auth:test :desktop-downloads:test :playback-core:test :desktop-play-together:test :desktop-lyrics-ui:test`: PASS.
- Hardened Play Together authority + lifecycle rewrite passes a broad forced gate: `:desktop-play-together:test --rerun-tasks :windows-media-integration:test :desktop-lyrics-ui:test :desktopApp:test :desktopApp:compileKotlin`, plus `git diff --check`. Session begin/finish is serialized with a generation token; stale WebSocket/RTC callbacks and stale finishes are ignored; guest restores exact pre-party speed/repeat/shuffle; host restores shuffle only; settings/persistence/hardware-media policy is synchronous by current party state. Host-server stop handle, guest WebSocket, Quick Tunnel process/log job, and host-peer insertion are now published atomically under the lifecycle mutex or disposed as stale; host participant/send paths are generation-filtered.
- Play Together queue revisions now incorporate stable queue-entry identity/logical position when available, so duplicate video IDs and reorderings are distinguishable. Added focused duplicate-entry revision + connected-guest-state tests; `:desktop-play-together:test :desktopApp:compileKotlin` PASS.
- New direct Play Together lifecycle tests cover role policy, exact guest restore, host keep-speed/repeat semantics, immediate old-generation finish vs newer party, shutdown mode restoration, stale final CONNECTED publication, early permission/direct-state merge, and a suspended authoritative playback mutation vs a new session. Final startup CONNECTED publication, generation-owned ping/broadcast installation, and session-owned queue/seek/play/mode mutations share the same lifecycle fence as begin/finish. Latest forced gate: 62/62 actionable tasks executed successfully. Advisor final GO received and checkpoint committed as `9f169814b`.
- Computer Use verification earlier in the run: `@oai/sky` successfully enumerated the running ArchiveTune window, activated it, captured screenshots/accessibility state, and delivered a harmless Ctrl+F input. Current policy is stricter: nobody, including the orchestrator, uses Computer Use while the user is actively using the PC.

## Commits created tonight

- `f97e2ac41` — Reuse playback HTTP transport and recover initial HTTP failures (worker branch; integration pending)
- `39102662a` — Stop retaining unused local-library artwork bytes (worker branch; integration pending)
- `5cd6350` — Fix desktop download Retry stream refresh (worker branch; integration pending)
- `e17a12a3d` — Improve desktop navigation shortcuts (worker branch; integration pending)
- `9fd538c35` — Add desktop accent customization (worker branch; integration pending)
- `fc44427d2` — Add live VLC equalizer controls (worker lineage; integration pending)
- `ec6a939c9` — Fix native window chrome reliability (worker lineage; integration pending)
- `4e5935958` — Fix Windows media hotkey registration lifecycle (worker lineage; integration pending)
- `53e265df9` — Move logout/profile cleanup off UI thread (worker lineage; integration pending)
- `1ef3a65b0` — Prefer completed downloads for playback (worker lineage; integration pending)
- `99965cb01` — Reduce Windows media state fan-out (worker lineage; integration pending)
- `7ddb94b89` — Preserve duplicate playlist song occurrences (worker lineage; integration pending)
- `67a8ead7d` — Reveal browse detail from immersive lyrics (worker lineage; integration pending)
- `00f18df7e` — Add Retry for failed desktop search (worker lineage; integration pending)
- `6eeb76102` — Fix scrolled queue reorder targeting (worker lineage; integration pending)
- `e0e4b8c6c` — Refresh playlist detail after mutations and preserve failure context (worker lineage; integration pending)
- `4a4b105b6` — Polish retry ownership and partial batch downloads (worker lineage; integration pending)
- `1311c9f84` — Stop managed login waiting after browser closes (worker lineage; integration pending)
- `c50890857` — Add fast local Library filtering (worker lineage; integration pending)
- `a263a0dd1` — Make desktop queue overlay responsive and Escape-closeable (worker lineage; integration pending)
- `df7274526` — Show local-library tracks in desktop Search (worker lineage; integration pending)
- `c85db79d3` — Remove duplicate detail Back controls and add desktop control tooltips (worker lineage; integration pending)
- `f9727ab96` — Add persistent desktop audio-output device selection (worker lineage; integration pending)
- `0dce988cc` — Surface local-library scan status/errors in Settings (worker lineage; integration pending)
- `9f169814b` — Harden Play Together authority and lifecycle (**integrated on main**)

## Current assignments

- worker1 — per-media HTTP cancellation + bounded/file-backed whole-object fallback; no shared-transport `cancelAll`, deterministic loopback tests only.
- worker2 — make remote `Not interested` failure truthful and local suppression undoable.
- worker3 — reduce continuous queue-persistence disk churn while preserving crash-recovery freshness on meaningful changes.
- worker4 — make Add-to-playlist picker refresh/add failures recoverable without dismissing away context; preserve cached editable playlists.
- worker5 — advisor parked after final Play Together GO; available for integration review / next-roadmap prioritization.

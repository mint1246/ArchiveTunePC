# ArchiveTune Desktop Performance / Fluidity Plan

## Goal

Make the desktop app feel much closer to the Android app: instant-feeling navigation, minimal unnecessary loading, background refresh instead of blocking refresh, preserved screen state, fewer duplicate requests, smoother UI under load, and downloads/background work that are not tied to a specific screen being open.

This must be done **without materially increasing RAM usage**. Ideally steady-state RAM should go down by deduplicating state and bounding caches.

This file exists as the persistent implementation plan for the work. The interface being used has no subagents, so execution will be mostly serial. Work should follow this file phase-by-phase and update it as discoveries are made.

---

## Current problems already confirmed

### Home is screen-owned and reloads too easily

`DesktopHomeSurface` currently owns remote home state inside Compose with `remember` and runs `loadInitial()` from `LaunchedEffect(Unit)`.

That means the screen itself owns:

- remote home page
- quick picks
- playlists
- loading state
- selected chip state
- continuation state
- previous chip state

The initial load calls both:

- `backend.home()`
- `backend.libraryPlaylists()`

So recreating the Home surface can cause another full remote load.

### Library refreshes online playlists when entering the destination

`DesktopMusicContent` runs `refreshOnlinePlaylists()` when the current destination becomes `LIBRARY`.

This means revisiting Library can unnecessarily wait on another network request even if the playlists were just loaded moments ago.

### Search state is transient

Desktop search currently has one transient `resultItems` list and a delayed `searchSummary()` call.

Missing compared with Android:

- persistent search history
- query/result cache
- result preservation when navigating into an album/artist/playlist and back
- scroll position preservation
- proper per-query state
- stale-while-revalidate behavior

### Browse/detail navigation is not a real back stack

Desktop currently has a single `Detail?` in `DesktopBrowseController`.

Opening a detail replaces the previous detail. Going back generally clears the detail rather than restoring the exact previous route/page/state.

This explains cases where Back does not return to the real prior page and can require reloading.

### Startup/background work can generate many requests

`DesktopMusicServices` can start account-related work such as:

- liked songs sync
- library songs sync
- liked albums sync
- subscribed artists sync
- related-song warming
- similar-recommendation generation
- artist page requests for recommendation seeds
- `next()` / `related()` requests for recommendation seeds

Meanwhile Home separately loads remote home and playlists.

The result can be excessive concurrent background/network work while the visible UI is trying to become usable.

### Some persistence work is synchronous

`DesktopHomeStore` updates in-memory state and writes the entire JSON-backed store synchronously.

Potentially expensive actions such as recording history, pinning items, or dismissing recommendations should not perform whole-file serialization/replacement on a UI-sensitive path.

### Background scope is too generic

`DesktopRuntime.applicationScope` currently uses `Dispatchers.Default`.

Network calls, disk persistence, response parsing, recommendation generation, and CPU work should not all compete in one generic pool without prioritization.

### Downloads are already closer to the desired architecture

`DesktopDownloadManager` owns its own application-independent coroutine scope on `Dispatchers.IO`.

Downloads therefore do not fundamentally need to be tied to the Downloads screen. This is a good pattern to follow for other long-lived application work.

Do **not** rewrite the download engine merely for architectural consistency unless profiling discovers a concrete issue.

---

# Core architecture rules

## 1. Screens do not own remote application data

Compose surfaces should render long-lived state. They should not be the primary owner of remote Home, Library, Search, Album, Artist, Playlist, or similar data.

Long-lived repositories/state holders should be owned by application services, most likely `DesktopMusicServices` or a closely related application-scoped container.

Likely components:

- `DesktopHomeRepository`
- `DesktopLibraryRepository`
- `DesktopSearchRepository`
- `DesktopBrowseRepository`
- request/cache coordinator
- background task coordinator

Names may change if the existing architecture suggests a cleaner design.

## 2. Cache data, not entire Compose screens

Do not keep whole UI trees alive just to preserve state.

Preserve compact state such as:

- route IDs
- query strings
- selected filter/chip
- scroll position
- content cache keys
- timestamps
- compact parsed data where useful

This avoids a large RAM increase while still making navigation instant.

## 3. Memory cache must be bounded

No unbounded page/result caches.

Use bounded LRU/age-based caches for parsed data.

Initial target ideas, subject to measured object size:

- Home: current remote page + selected/previous chip state only
- Search: last ~10-20 normalized query states in memory
- Album/artist/playlist/detail pages: ~20-40 total recent parsed pages, size permitting
- Related-song seeds: bounded by count + age
- Similar recommendations: only current snapshot

Old entries should be evicted from RAM but may remain available in persistent disk cache.

## 4. Persistent cache should be used aggressively for slow remote content

Important remote content should survive app navigation and, where practical, app restart.

Candidate persistent cache:

- Home feed
- account playlists
- library remote metadata
- recent search results
- album pages
- artist pages
- playlist pages

The persistent format should avoid storing sensitive short-lived playback URLs/headers.

## 5. Stale-while-revalidate is the default behavior

General flow:

1. Show cached data immediately if any exists.
2. Determine whether it is still fresh enough.
3. If stale, refresh in background while keeping current content visible.
4. Replace cached state only after a successful refresh.
5. Only show a full-screen blocking loader when there is literally no usable cached content yet.

Avoid replacing usable UI with a spinner during normal refreshes.

## 6. One resource load should mean one in-flight request

Requests need deduplication/coalescing.

Examples:

- two consumers request Home simultaneously -> one Home request
- multiple UI components request the same playlist list -> one playlist request
- album already loading -> second caller joins same job/result
- recent cached album -> no remote call until refresh policy says it is stale

## 7. Background work has lower priority than direct user work

Task classes:

### Foreground

User explicitly clicked something and needs the result now.

Examples:

- open album
- open playlist
- new search query

### Refresh

Refreshing stale data while cached data remains usable.

### Prefetch

Likely-to-be-used data fetched proactively.

### Maintenance

Examples:

- related-song warming
- recommendation regeneration
- background library synchronization
- cache pruning
- persistence flushing

Foreground work must not be starved by maintenance work.

## 8. Disk/network work should not run on the UI-sensitive path

Use appropriate dispatchers/executors:

- network I/O -> `Dispatchers.IO` or equivalent bounded I/O path
- filesystem -> `Dispatchers.IO`
- CPU-heavy transforms -> bounded background CPU dispatcher
- UI -> small state updates and rendering only

The generic application scope may remain a parent scope, but work should switch to the correct dispatcher.

---

# RAM strategy

The performance architecture should keep RAM approximately the same or lower than current steady-state.

## Main techniques

### One canonical data copy

Avoid screens each keeping separate large lists of the same library/home/search data.

Repositories should expose shared canonical state.

### Compact navigation state

Navigation history should keep routes/IDs/scroll/query state, not full screens.

### Bounded LRU page caches

Recent parsed pages stay hot; old pages fall out automatically.

### Disk-first for older cached data

Old search/detail/home cache can be reloaded from local disk rather than retained forever in RAM.

### Avoid retaining intermediate API responses

After extracting the state actually needed by the repository, release raw/intermediate response structures.

### Deduplicate metadata

Where practical, avoid storing the same complete song/artist/album metadata repeatedly inside history and caches.

`DesktopHomeStore` currently stores up to 2,000 play events containing complete `SongItem` objects. This should be reviewed. A compact event model plus deduplicated metadata could use substantially less memory and disk.

### Bound related-song storage

`relatedSongsBySeed` should have a global bound/age policy rather than growing indefinitely.

### Keep image memory cache bounded

Coil already has a desktop 64 MiB memory cache. Do not blindly increase it.

Measure hit rate and decoded-image behavior before changing this budget.

### Avoid repeated collection transforms during recomposition

Move expensive `map/filter/distinctBy` chains out of hot recomposition paths where possible and memoize/derive them in stable state holders.

---

# Phase 1 - application-scoped state and cache infrastructure

## Goal

Create the foundation without changing every screen at once.

## Work

- Add application-scoped repository/state-holder infrastructure.
- Add a bounded in-memory cache abstraction suitable for typed page data.
- Add request deduplication/in-flight coalescing.
- Add freshness metadata / TTL support.
- Add a persistent cache directory under the existing desktop cache/data paths.
- Ensure account-sensitive cache keys include account/session identity.
- Add cache invalidation primitives for mutations such as playlist create/delete/rename and like changes.
- Add separate foreground/background task handling or at least concurrency controls.
- Add lightweight request metrics/logging.

## Acceptance criteria

- no user-visible regression
- no significant RAM increase at idle
- can prove duplicate simultaneous resource requests are coalesced
- cache layer has explicit limits

---

# Phase 2 - Home repository + instant cached Home

## Goal

Home should stop being rebuilt from the network every time the user returns to it.

## Move out of `DesktopHomeSurface`

- remote Home page
- remote quick picks
- account playlists
- selected chip
- previous chip state
- continuation
- loading more state
- refresh state
- feedback-hidden state where appropriate

Local personalization state may continue to originate from `DesktopHomeStore`, but the screen should consume one coherent Home state.

## Behavior

### First load with no cache

- show available local Home content immediately if possible
- fetch remote Home in foreground
- avoid waiting on unrelated recommendation/library maintenance

### Warm Home open

- show cached Home immediately
- if fresh -> no request
- if stale -> background refresh while old Home remains visible

### Return to Home

Target: **0 blocking network requests**.

### Home startup work

Move recommendation regeneration / related-song warming out of the critical visible Home path.

Background recommendation work should be concurrency limited and should not create UI hitches.

## Home persistence changes

- persist remote Home snapshot where safe
- persist account playlists cache where safe
- move `DesktopHomeStore` writes off UI-sensitive paths
- debounce/coalesce frequent writes
- preserve crash-safe atomic write behavior

## RAM checks

- only one canonical remote Home snapshot
- do not keep old continuation pages separately after merging
- bound related-song cache
- consider compacting history event storage

## Acceptance criteria

- revisit Home -> immediately populated
- cached Home does not blank during refresh
- no full-screen spinner when usable cache exists
- repeated Home open within freshness window -> no Home network request
- scrolling remains smooth while background refresh occurs

---

# Phase 3 - Library becomes local/cached-first

## Goal

Library should open immediately and refresh remote data in the background.

## Work

- move remote playlist state out of `DesktopMusicContent`
- create canonical library state
- persist/cache YouTube playlist metadata
- stop refreshing playlists merely because `LIBRARY` entered composition/destination
- add freshness/invalidation policy
- invalidate/update immediately after playlist mutation
- keep local scan state separate from remote playlist loading

## Local filesystem scanning

Review the current startup/entry behavior.

Desired behavior:

- use last known local library index immediately
- scan only when needed
- explicit refresh triggers scan
- folder changes trigger scan
- optional startup background validation if warranted
- do not block opening Library on a scan unless there is no prior state

## Acceptance criteria

- Library opens with cached/local content immediately
- leaving and returning does not cause a blocking playlist fetch
- remote refresh does not blank the library
- playlist changes appear immediately and reconcile with backend afterward

---

# Phase 4 - Search subsystem

**Status: completed 2026-08-19.**

Implementation checkpoint:

- Added application-scoped `DesktopSearchRepository`.
- Normalizes queries and keeps an access-ordered in-memory cache capped at 12 queries.
- Persists recent query results and up to 30 search-history entries per account scope.
- Uses a 10-minute freshness window with stale-while-revalidate behavior.
- Cached results remain visible during refresh; only an uncached query needs an initial loader.
- Obsolete searches are cancelled, and a generation/current-query guard prevents late responses from replacing newer results.
- Search UI now renders repository state instead of owning a disposable `resultItems` list.
- Added recent-search select/remove/clear UI.
- Added a compact `YTItem` persistence codec that stores render/navigation metadata only, never signed playback URLs or headers.
- Added focused repository tests for normalized cache hits, stale-response races, bounded memory, and persisted history.
- Verified with `:desktop-backend:test :desktopApp:compileKotlin`.

Scroll-position restoration is intentionally completed together with route/view-state work in Phase 5/8 rather than retaining Compose screens.

## Goal

Search should behave like a persistent user session rather than a disposable request.

## Work

- add `DesktopSearchRepository`
- normalize/cache queries
- bounded in-memory query LRU
- optional persistent recent search result cache
- persistent search history
- preserve search query
- preserve results
- preserve selected filter when applicable
- preserve scroll position
- cancel obsolete queries
- prevent late stale response from replacing a newer query
- support suggestions/history similar to Android behavior
- background refresh cached search results where useful

## Desired behavior

Example:

1. search `radiohead`
2. open artist
3. open album
4. back to artist
5. back to exact `radiohead` results at same scroll position

No repeated search request should be necessary for this path.

Searching `radiohead` again soon should be an immediate cache hit.

## Acceptance criteria

- recent searches survive navigation
- stale response cannot overwrite newer query
- repeated query is instant from memory/disk cache
- search state is memory bounded

---

# Phase 5 - Real navigation stacks and route state

**Status: completed 2026-08-19 (route/cache architecture; scroll restoration is finalized in Phase 8).**

Implementation checkpoint:

- Replaced the destructive single-detail navigation behavior with compact typed browse routes.
- Added an independent bounded route stack per top-level destination, so switching Search/Library/Home no longer destroys nested detail history.
- Added a 24-page access-ordered LRU for parsed album/artist/playlist/artist-items pages; navigation stacks retain IDs/endpoints rather than whole screens.
- Returning through a still-hot detail route is a zero-network cache hit.
- Late browse responses are generation/destination guarded so a slow page cannot overwrite a newer route.
- Account-scope changes clear both cached detail pages and compact route history.
- Shell Back/Alt+Left now consumes nested detail history before top-level destination history.
- Capped top-level navigation history at 32 entries and nested history at 16 entries per destination.
- Added focused navigation-state tests for per-destination restoration and bounded history.
- Verified with `:desktopApp:test :desktopApp:compileKotlin`.

Exact list scroll offsets remain compact view state rather than retained screens and are implemented/audited in Phase 8.

## Goal

Back should return to the actual previous page/state.

## Replace the single-detail model

Current `DesktopBrowseController.detail: Detail?` is insufficient.

Introduce typed route/navigation entries, likely representing:

- Home
- Explore
- Search(query/session key)
- Library
- Downloads
- Settings
- Album(id)
- Artist(id)
- ArtistItems(endpoint/parent)
- Playlist(id)

Potentially preserve a stack per top-level destination so switching between Home/Search/Library retains each destination's internal navigation history.

## Each route should preserve small view state

Examples:

- scroll position
- selected tab/filter
- query
- route ID
- cache key

Do not keep whole screen composables alive.

## Acceptance criteria

- Back restores exact previous route
- Back to a cached detail makes no network request
- Search/library/home retain their prior state after visiting another top-level destination
- navigation history remains compact in RAM

---

# Phase 6 - Request ownership, deduplication, priority, and concurrency

**Status: completed 2026-08-19.**

Implementation checkpoint:

- Audited direct desktop backend calls and moved remaining page/resource reads behind application-scoped owners; explicit user mutations remain direct foreground actions.
- Added application-scoped Explore state with a 10-minute freshness window. Explore + Charts load concurrently, stale content remains visible during refresh, and refresh traffic uses bounded background permits.
- Routed Search Up Next and artist-items continuation requests through single-flight resource keys.
- Recommendation/related-song warmup now limits the actual network requests with `MAINTENANCE` priority rather than holding one permit around a large opaque batch.
- Reused identical related-page keys so startup warming and playback-triggered warming coalesce.
- Account maintenance observer now keys on the stable account identity scope, so cookie/PoToken/session refreshes do not replay Home/Library synchronization.
- Account-scoped startup children are tied to `collectLatest`; switching accounts cancels stale Home/Library/maintenance work.
- Existing coordinator keeps background concurrency at two while foreground requests bypass that queue.
- Final UI audit leaves direct backend calls only for explicit mutations/feedback actions.
- Verified with `:desktop-backend:test :desktopApp:test :desktopApp:compileKotlin`.

## Goal

Stop request storms and protect foreground responsiveness.

## Work

- central in-flight request map / single-flight mechanism
- bounded background concurrency
- foreground request priority
- prevent recommendation/cache-warming work from flooding backend
- coalesce repeated synchronization triggers
- review auth/session refresh interactions to avoid redundant resource reloads

## Startup policy

Critical startup path should be small:

- restore persisted auth/session
- restore cached UI state
- only fetch directly necessary stale foreground data

Everything else should be background maintenance.

## Acceptance criteria

- opening Home does not trigger unrelated recommendation storms
- duplicate resource requests are deduplicated
- maintenance work does not noticeably hurt scroll/input responsiveness

---

# Phase 7 - Persistence and filesystem smoothness

**Status: completed 2026-08-19.**

Implementation checkpoint:

- `DesktopSettingsStore` now reads disk once per process-local settings path; `snapshot()` is an in-memory read instead of reparsing the properties file.
- Settings instances for the same path share one canonical locked state, preserving concurrent-update semantics.
- Routine settings `update()` calls mutate memory immediately and debounce atomic persistence by 250 ms.
- Explicit `save()` remains synchronous, and application shutdown synchronously flushes the latest settings snapshot.
- Playback queue checkpoints are serialized/written on `Dispatchers.IO`; queue restore also reads from IO. The final shutdown queue save remains synchronous for durability.
- Audited `DesktopHomeStore`: production writes were already debounced/asynchronous with atomic close flush.
- Audited `DesktopAlbumIndex`: already has a daemon 2-second coalesced atomic flusher.
- Audited local-library index persistence: scans/persistence already run on background/IO paths and use atomic replacement.
- Audited download journal: high-frequency byte-progress updates explicitly use `persistState = false`; durable state transitions are small and occur primarily on the download IO dispatcher, so the working download engine was intentionally left unchanged.
- Persistent page/search/Home cache writes already run from repository IO/background jobs.
- Added a durability test proving a pending settings update is persisted by `close()`.
- Verified with `:desktop-settings:test :desktop-backend:test :desktop-library:test :desktop-downloads:test :desktop-playback:test :desktopApp:test :desktopApp:compileKotlin`.

## Goal

Remove synchronous whole-file work from UI-sensitive paths.

## Audit targets

- `DesktopHomeStore`
- settings persistence
- queue persistence
- album index
- library index/state
- download journal
- any new persistent page cache

## Changes

- update in-memory state immediately
- queue persistence asynchronously
- debounce high-frequency persistence
- preserve crash safety via atomic replacement where important
- batch/coalesce writes where possible

Do not risk corrupting persistent state merely to save a few milliseconds.

## Acceptance criteria

- pin/dismiss/history actions do not perform visible synchronous stalls
- frequent state updates do not result in excessive filesystem writes

---

# Phase 8 - Compose/rendering smoothness audit

**Status: completed 2026-08-19.**

Implementation checkpoint:

- Added a bounded 64-entry compact view-state LRU that stores only lazy-list `(index, offset)` coordinates, never retained Compose screens or `LazyListState` objects.
- Restored vertical scroll position across Home, Explore, Search, Library, Downloads, albums, artists, playlists and artist "See all" pages.
- Added a small `rememberPersistedLazyListState` adapter that snapshots position when a route/list leaves composition.
- Home and Explore no longer collect the full playback snapshot. They subscribe to a distinct `(isPlaying, currentItemId, currentAlbumId)` identity projection, so 250 ms playback-position ticks do not recompose the large feed screens.
- Memoized Home section visibility filtering, Quick Pick extraction, Speed Dial song extraction, playlist deduplication and similar-recommendation filtering/song queues.
- Memoized Explore chart filtering/deduplication, Top Music Videos extraction and artist-preference new-release sorting.
- Kept palette quantization/sorting unchanged because it already runs only on artwork cache misses rather than normal recomposition.
- Added a focused bounded-view-state restoration test.
- Verified with `:desktopApp:test :desktopApp:compileKotlin`.

Do this after the architecture stops constantly destroying/reloading state.

## Inspect

- whole-screen recompositions
- unstable state/lambdas
- expensive transformations in composition
- repeated `distinctBy/filter/map` allocations
- image decode size
- image request churn
- lazy list keys
- preserved scroll state
- `AnimatedContent` recreating expensive trees
- animation cost while network/background work is active
- state emission frequency

## Principle

The app should remain smooth **during background loading**, not just after loading finishes.

## Acceptance criteria

- no obvious UI hitch while Home/Library refresh in background
- no repeated artwork/network churn during simple navigation
- 60 fps-sensitive areas do not compete heavily with maintenance work

---

# Phase 9 - Instrumentation and measurable targets

Add lightweight debug metrics/logging, ideally disableable or low-overhead in release.

## Per request

Capture enough to answer:

- resource type/key
- cache hit/miss
- memory vs disk cache
- request duration
- caller/category (foreground/refresh/prefetch/maintenance)
- deduplicated/coalesced yes/no

Avoid logging credentials, cookies, signed playback URLs, auth headers, or other sensitive data.

## Useful counters

- requests caused by opening Home
- requests caused by returning Home
- requests caused by opening Library
- Home cache hit rate
- Library cache hit rate
- Search cache hit rate
- duplicate requests coalesced
- active foreground/background jobs
- Home first-content latency
- Library first-content latency
- Search first-result latency

## Performance targets

| action | target |
|---|---|
| return to Home | immediate, 0 blocking requests |
| return to Library | immediate, 0 blocking requests |
| Back to previous detail/search | immediate, 0 requests if cached |
| warm Home navigation | ~<100 ms perceived |
| warm Library navigation | ~<100 ms perceived |
| repeated recent search | immediate cache hit |
| background refresh | never replace usable content with full-screen spinner |
| duplicate simultaneous resource loads | 0 duplicate backend calls |
| leave Downloads screen | downloads unaffected |

---

# RAM acceptance criteria

Performance work is not considered successful if it makes the app substantially heavier at steady state.

Current practical target based on prior desktop measurements:

- avoid any large idle-home RAM regression
- ideally remain around the same range or lower
- do not accept a jump of ~100+ MiB merely because page caching was introduced

Measure at least:

- Home idle after warm-up
- Library idle
- Search results idle
- playing without lyrics
- playing with lyrics
- after navigating through many album/artist/playlist pages
- after many searches

A long navigation/search session should stabilize rather than grow continuously.

If memory keeps climbing, inspect:

- cache eviction
- Compose retained state
- image cache
- coroutine/job leaks
- raw response retention
- route history
- related-song cache
- search cache
- recommendation cache

---

# Cache invalidation rules

A cache that is fast but incorrect is not acceptable.

At minimum invalidate/update relevant cached state after:

- create playlist
- delete playlist
- rename playlist
- add song to playlist
- remove song from playlist
- like/unlike song
- library add/remove mutations
- account switch/sign-out
- auth identity change
- local library folder changes
- explicit user refresh

Account-specific cache must never bleed between accounts.

---

# Things not to do

- do not keep whole screens alive just for navigation state
- do not create unbounded HashMaps of pages/searches
- do not increase Coil memory budget blindly
- do not persist signed stream URLs or sensitive request headers
- do not refresh a page solely because its composable re-entered composition
- do not blank usable cached UI during refresh
- do not make Home wait for recommendation-generation maintenance
- do not rewrite the working download engine without a measured reason
- do not merge all phases into one giant risky commit
- do not modify Android behavior unnecessarily; use Android as the behavioral reference where appropriate

---

# Implementation / commit order

Use relatively isolated commits so regressions can be bisected.

Suggested sequence:

1. `desktop: add bounded resource cache and request deduplication`
2. `desktop: move home remote state into application repository`
3. `desktop: add persistent stale-while-revalidate home cache`
4. `desktop: make library cached-first and stop entry refreshes`
5. `desktop: add persistent search sessions/history/cache`
6. `desktop: replace single detail state with route back stacks`
7. `desktop: prioritize foreground requests and bound background work`
8. `desktop: move hot-path persistence to asynchronous coalesced writes`
9. `desktop: reduce retained home/history/cache memory`
10. `desktop: profile and optimize compose rendering hot paths`
11. `desktop: add/request performance diagnostics and final tuning`

Commit grouping may change if a phase naturally requires two smaller commits.

---

# Working-tree safety

There is existing uncommitted work in this repository from other recent tasks.

Before each phase:

- inspect `git status`
- inspect relevant diffs before editing overlapping files
- preserve unrelated changes
- avoid broad formatting/rewrite churn
- only stage files/parts belonging to the current performance phase

Do not accidentally commit unrelated current work unless explicitly intended.

---

# Verification loop after each major phase

For each phase:

1. build/compile desktop app
2. run focused tests where available
3. manually verify affected navigation path
4. inspect request logs/counts
5. inspect RAM after warm-up
6. verify cache invalidation for changed resources
7. verify account isolation
8. check `git diff`
9. commit isolated work
10. update this plan with completed status/findings before moving on

---

# Phase 1-3 implementation checkpoint - 2026-08-19

## Completed implementation

- Added a bounded application-scoped content/request coordinator:
  - 32-entry access-order memory cache in the desktop app
  - persistent cache under the existing desktop cache directory
  - SHA-256 cache filenames so account/resource keys are not exposed as filenames
  - exact-key single-flight request coalescing
  - separate foreground vs bounded background execution (2 background requests at a time)
  - cache/request metrics and diagnostic events
- Added account-scoped `DesktopHomeRepository` and `DesktopLibraryRepository`.
- Home remote page state, quick picks, category-chip state, continuation state, loading/refresh state, feedback-hidden state, and account playlists no longer belong to `DesktopHomeSurface`.
- Home now restores memory/disk state first, keeps cached content visible while stale data refreshes, and does not issue a Home request while the cached snapshot is fresh.
- Home and Library now share one canonical playlist list instead of retaining/fetching separate copies.
- Startup recommendation generation, related-song warming, and full Home-library synchronization were moved out of the first-paint path and through bounded maintenance work.
- `DesktopHomeStore` production persistence is now debounced/asynchronous while retaining crash-safe atomic replacement and a synchronous close flush.
- Related-song cache is bounded to 64 seeds x 100 songs.
- Repeated play-history entries for the same song share one canonical `SongItem` object in memory.
- Like/unlike and artist subscribe/unsubscribe mutations now update the exact local Home state after the backend mutation instead of triggering the four-resource `syncHomeLibrary()` request fan-out.
- Remote playlists are cached persistently and use a 5-minute freshness window.
- Playlist create/rename/delete update the canonical Library cache immediately; rename/delete roll back the local change if the backend mutation fails.
- Spotify import intentionally forces one Library refresh because it is a batch mutation performed outside the normal playlist mutation path.
- Local Library now has a persistent metadata index and exposes a stable `StateFlow`.
- The local index deliberately excludes embedded artwork to avoid retaining/persisting large image blobs.
- Local rescans reuse unchanged entries by normalized path + modified time + file size, avoiding repeat tag parsing.
- Entering Library no longer triggers an unconditional local rescan or remote playlist fetch.
- Folder changes and explicit refresh still trigger a local scan; startup validation is background maintenance.

## Automated verification completed

`./gradlew :desktop-backend:test :desktop-library:test :desktopApp:compileKotlin --no-daemon`

Verified by tests/build:

- simultaneous identical resource requests execute the backing request once
- memory content cache obeys its explicit LRU bound
- persisted local-library metadata is available immediately after reconstruction
- unchanged local files do not invoke the metadata reader again
- artwork is not restored into the compact local metadata index
- precise like/subscription state mutations work without a full resync
- repeated play-history entries share one canonical song object
- related-song cache respects both seed and per-seed bounds
- desktop application compiles with the new Home/Library architecture
- request audit finds no direct `backend.home()` / `backend.libraryPlaylists()` / old `refreshOnlinePlaylists()` calls remaining in the Home and main Library surfaces

## Runtime validation still required

The implementation is complete, but these are deliberately not claimed from automated tests alone and remain part of the later runtime/performance validation pass:

- measure warm Home and Library navigation latency in the packaged app
- inspect real request logs over repeated Home/Library navigation
- compare steady-state RAM before/after warm Home + Library use
- verify cached account isolation by switching real accounts/channels
- manually exercise stale-while-revalidate behavior with real YouTube responses/failures

No phase commit was made at this checkpoint because `DesktopMusicSurface.kt` already contained unrelated uncommitted work before these phases began. Do not stage that file wholesale and accidentally include unrelated changes; either commit later with a carefully isolated hunk or after the existing work has been resolved.

---

# Progress tracker

- [x] initial architecture/performance audit
- [x] persistent implementation plan written
- [x] phase 1 - app-scoped cache/request infrastructure (implementation + automated verification complete)
- [x] phase 2 - Home repository + stale-while-revalidate (implementation + automated verification complete)
- [x] phase 3 - Library cached-first behavior (implementation + automated verification complete)
- [x] phase 4 - Search sessions/history/cache (implementation + automated verification complete)
- [x] phase 5 - real route/back-stack navigation (route/cache architecture + automated verification complete)
- [x] phase 6 - request priority/concurrency cleanup (ownership/priority/auth-refresh audit complete)
- [x] phase 7 - async/coalesced persistence (filesystem audit + hot-path fixes complete)
- [x] phase 8 - Compose/rendering smoothness audit (view-state restoration + hot recomposition/allocation audit complete)
- [ ] phase 9 - diagnostics, RAM validation, final tuning

---

# Definition of done

The desktop app should behave as if it remembers what the user was just doing without needing to repeatedly ask YouTube for the same information.

The intended final feel is:

- Home is there when opened, not 5-10 seconds later.
- Library playlists are already there.
- Search remembers recent queries/results.
- Back returns to the exact previous page/state.
- Cached content stays visible while refreshing quietly.
- Background recommendation/sync work does not make scrolling lag.
- Downloads continue regardless of which page is visible.
- Network request count is substantially lower during normal navigation.
- RAM remains bounded and roughly the same or lower than before.
- A long session does not continuously accumulate retained pages/searches/history in memory.

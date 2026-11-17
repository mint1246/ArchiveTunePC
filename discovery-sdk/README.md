# discovery-sdk

Headless JVM building blocks for Android discovery, search and browse content loading.

This module contains only platform-neutral coordination logic. It never performs network
requests, reads Android databases, starts a player, or configures an account. Callers supply
the real YouTube/content backend and an account-scope provider; the SDK owns only the caching,
single-flight, cancellation and account-isolation policy around those calls.

## What is here

- `DiscoveryContentCache<T>` — bounded, account-scoped, single-flight cache for loaded content.
  Keys always include the current account scope, cached values are LRU-evicted, concurrent
  loads for the same key are coalesced, cancellation of the last waiter cancels its loader, and
  a response that finishes after an account switch is discarded instead of cached. `close()`
  releases every owned loader scope.
- `DiscoveryKeys` — stable key/namespace builders for search, search summaries, browse pages,
  continuations and the aggregated discovery payload.
- `DiscoveryFanOut` — bounded parallel batch loading with cancellation propagation and the
  `yield()` between batches used by Android discovery seed expansion.
- `DiscoveryLists` — id-based deduplication and bounded selection used to build discovery UI
  models from heterogeneous content lists.

## Consumers

Android's `SearchDiscoveryRepository` and `LoadSearchDiscoveryUseCase` use the batching and list
policies. The cache is experimental and is not wired into the Android search view model; its
existing UI state and loading behavior are retained. Desktop browse/search repositories have
their own JVM implementations and are not changed by this module.

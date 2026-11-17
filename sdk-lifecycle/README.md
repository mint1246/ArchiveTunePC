# sdk-lifecycle

Experimental, headless JVM lifecycle primitive for ArchiveTune SDK components.

Artifact: `moe.rukamori.archivetune:sdk-lifecycle:0.1.0-SNAPSHOT` (Maven Local).

## Why this exists

The SDK migration currently publishes many independent JVM artifacts, but each SDK figures out
its own initialization and teardown:

- some own a `CoroutineScope` internally (`StreamResolutionCoordinator`), so a headless consumer
  cannot share or constrain its threading;
- some accept a caller-owned scope but still need manual, correctly ordered cleanup
  (`TogetherClient`, `TogetherServer`);
- some expose terminal `close()` operations (`PlaybackController`, `NextTrackPrefetcher`,
  `SleepTimerController`, `DesktopDownloadManager`, the together transport), but a consumer that
  initializes several of them has no deterministic way to shut the whole set down;
- several SDK modules still expose process-global `object` singletons with no independent
  instance lifecycle.

This module is a small, additive boundary for that coordination. It does **not** configure any SDK
feature, start playback, open a network connection, or replace an existing SDK `close()`. It does
not own any global state.

## API

```kotlin
val parent = CoroutineScope(SupervisorJob() + Dispatchers.IO)
val lifecycle = SdkLifecycle.forScope(parent)

val downloads = lifecycle.register(DesktopDownloadManager(directory))
val workerScope = lifecycle.scope()          // child scope, cancelled first on close
lifecycle.onClosing("metrics") { flushMetrics() }

// ... use downloads / workerScope ...

lifecycle.close()                            // idempotent, reverse order, aggregates failures
```

- `SdkLifecycle(parentContext)` / `SdkLifecycle.forScope(existingScope)` — construction only; no
  work is started eagerly.
- `register(component)` — returns the component unchanged; registers it for shutdown.
- `scope()` — creates a child scope with its own `SupervisorJob`; the child is cancelled before
  any component is closed. A failure in one child does not cancel its siblings.
- `onClosing(name, hook)` — named, idempotent shutdown callback; repeated names keep the first
  callback.
- `unregister(component)` — transfers ownership away from the lifecycle.
- `close()` — idempotent and thread-safe; runs hooks and components in reverse registration
  order, closes everything even if one cleanup fails, and throws the first failure with all
  later failures attached as suppressed. Component cleanup `CancellationException`s are treated as normal
  early exits. A component registered after shutdown starts is closed immediately instead of
  leaking.

## Scope

The headless download example uses this lifecycle to own its download manager. Existing
applications have not been migrated to it. The helper supports synchronous `AutoCloseable`
cleanup; services with suspending shutdown still require explicit coordination. It is not a
complete SDK session or a replacement for feature-specific lifecycle contracts.

## Validation performed

With the root catalog available, using an isolated project cache:

```powershell
.\gradlew.bat :sdk-lifecycle:test :sdk-lifecycle:publishToMavenLocal --no-daemon
```

The tests cover parent cancellation, shutdown ordering, failures, and concurrent registration.
The POM and jar publish to Maven Local.

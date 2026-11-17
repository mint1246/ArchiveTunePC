# ArchiveTune SDK: experimental JVM components

The migration is in progress. These artifacts are not a stable, complete ArchiveTune SDK:
Android playback/session, authenticated discovery and other application-owned features still
need extraction. The existing Android and desktop applications remain the behavior reference.
See [SDK_MIGRATION.md](SDK_MIGRATION.md) and the platform inventories for status and device
verification gaps.

## Using individual modules

Use JDK 21. Publish a module and its project dependencies to Maven Local from the repository,
then resolve `moe.rukamori.archivetune:<artifact>:0.1.0-SNAPSHOT` from `mavenLocal()` in a
separate Gradle project. These snapshots are local development artifacts, not a public release.
Choose only the modules required by the client; publishing or resolving a library does not
start a playback session or background feature.

| Artifact | Current boundary | Independent example |
| --- | --- | --- |
| `playback-core` | Queue, controller contracts and shared playback policies; no player implementation | `examples/headless-playback` |
| `desktop-playback` | VLC desktop player; caller supplies source resolution and a compatible VLC runtime | `examples/headless-desktop` |
| `desktop-downloads` | Desktop download engine; caller supplies a resolved URL and destination, then closes the manager | `examples/headless-download` |
| `desktop-resource-core` | Desktop bounded content cache and single-flight coordination; caller owns account-scoped keys and safe cache contents | `examples/headless-resource-cache` |
| `account-sdk` | Saved-account JSON and cookie-export parsing; caller owns credential validation and protected storage | `examples/headless-accounts` |
| `social-sdk` | Social HTTP client and wire models; caller supplies a token | `examples/headless-social` |
| `social-session` | Social session orchestration with caller-provided login, session storage, scope and realtime transport | `examples/headless-social-session` |
| `social-realtime` | Optional WebSocket implementation; caller owns connection and `close()` | Local WebSocket test in the module |
| `social-login` | Optional nonce-checked loopback callback flow; caller opens the browser | Local loopback test in the module |
| `sdk-lifecycle` | Optional synchronous ownership helper for scopes and `AutoCloseable` components | `examples/headless-download` and `examples/headless-social-session` |

Other experimental modules include lyrics, chords, analysis, party transport, playlist import and
export, statistics, local-media parsing, discovery policies, desktop STEM and SponsorBlock. Their
Gradle files and tests describe their present dependencies; inclusion does not imply that every
application path or feature is available as an independent service.

For example, to publish the playback contracts and desktop backend and run the queue example:

```powershell
.\gradlew.bat :playback-core:publishToMavenLocal :desktop-playback:publishToMavenLocal
.\gradlew.bat -p examples/headless-playback run
```

Close owned services when finished. `sdk-lifecycle` can cancel its child scopes and close
synchronous resources, but does not provide a unified music session or handle services whose
shutdown is suspending. Existing service-specific lifecycle requirements remain authoritative.

Never log or persist cookies, tokens, authorization headers or signed stream URLs as ordinary
cache data. Saved-account JSON contains credentials and requires protected storage. The desktop
resource coordinator does not automatically scope its keys by account. Service and dependency
licensing, distribution terms, device compatibility and Quest 2 resource usage remain to be
verified before shipping a third-party integration.

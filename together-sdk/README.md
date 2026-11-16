# Play Together protocol module

`together-sdk` contains the existing Android party messages and models, guest permissions and playback planning, clock/synchronization policy, join-link parsing, and the desktop compatibility codec. It does not start a server, connect a socket, own a player or require an ArchiveTune UI. The optional `together-transport` module provides the existing direct WebSocket host/client and online host/HTTP API; Android still supplies endpoint discovery, foreground lifecycle, clock, storage and playback integration. Desktop's party implementation remains separate.

Build tests and publish the experimental local artifact:

```powershell
.\gradlew.bat :together-sdk:test :together-sdk:publishToMavenLocal
```

Coordinates: `moe.rukamori.archivetune:together-sdk:0.1.0-SNAPSHOT`. Compose stability annotations are compile-only; consumers do not need to initialize Compose. The module uses kotlinx serialization JSON.

```kotlin
val join = TogetherLink.decode(invitation) ?: error("Invalid invitation")
// Pass join to your platform transport; do not log the invitation or session key.
```

The optional transport is published separately with `:together-transport:publishToMavenLocal`; publish `:together-sdk` first. `examples/headless-together` exercises a local host/guest session from independently resolved artifacts. Supply a monotonic millisecond clock to both host and client, call `client.close()` and `server.stop()` on shutdown, and cancel the owner scope. Close an online host with `host.close()` and an online API with `api.close()`. `disconnect()` alone permits reconnecting and does not release the client's HTTP resources.

Protocol tests and Android/desktop codec integration tests pass on the JVM. Actual multiplayer audio synchronization, device lifecycles, authentication, transport security and interoperability with deployed clients still need device/network verification. API stability is not yet promised.

# Social realtime transport

This optional JVM module supplies the existing authenticated WebSocket transport and presence wire format. It starts a connection only when `connect` is called; callers own reconnect policy, session credentials and the transport's `close()` lifecycle. The HTTP-only `social-sdk` remains usable without this module.

The local WebSocket test checks authorization, incoming messages and presence updates. Live service and Android background behavior remain unverified.

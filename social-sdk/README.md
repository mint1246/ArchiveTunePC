# Social client

This experimental JVM module contains the existing Android social HTTP client, wire models and parsing. It does not initialize a session or start background services. Applications supply tokens and retain ownership of login, token storage, realtime connections, notifications and playback integration. Do not log tokens or wire payloads containing private data.

The artifact `moe.rukamori.archivetune:social-sdk:0.1.0-SNAPSHOT` can be published to Maven Local. Its headless HTTP test exercises authenticated user retrieval against a local server; live service behavior remains unverified.

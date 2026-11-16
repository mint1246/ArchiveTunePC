# Playlist import resolution

An optional headless JVM module for resolving ordered imported song occurrences against a local collection and an injected YouTube song search. `ImportResolver` preserves input order and duplicates, limits concurrent resolutions, propagates cancellation, and reports remote failures through the injected callback while returning unresolved entries. `ImportMatcher` contains the existing Android fuzzy matching policy. Models and search providers belong to the caller; this module does not fetch Spotify playlists or append items to a playlist.

The Android app uses this engine with its existing Room song models and YouTube search. The independent `examples/headless-import` example resolves local tracks only; it does not demonstrate a live search provider.

Experimental local coordinates: `moe.rukamori.archivetune:playlist-import-sdk:0.1.0-SNAPSHOT` (JDK 21). Build with `:playlist-import-sdk:test :playlist-import-sdk:publishToMavenLocal` and run the example with `-p examples/headless-import run`. This is not a stable or complete import SDK. Live provider behavior and Android device flows remain unverified.

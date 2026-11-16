# Songsterr chord SDK

This JVM module contains the shared Android/desktop Songsterr chord models, real HTTP client, authored-chord parser and tab-derived fallback. The two clients previously differed only in their User-Agent; Android continues to provide its Android value and desktop retains the desktop default. Android uses its platform `org.json`, while JVM consumers receive the `org.json` dependency.

Experimental Maven Local coordinates: `moe.rukamori.archivetune:chords-sdk:0.1.0-SNAPSHOT`. The separate optional `desktop-neural-chords` artifact uses these models plus `desktop-model-runtime` and `playback-core`; neither the Rust runner nor model assets are bundled. `examples/headless-chords` demonstrates artifact resolution and construction, not live provider access or inference.

Shared parser tests, Android unit tests/assembly, desktop backend and lyrics tests, desktop compilation and quick Graal compile pass. Android device behavior, real Songsterr requests and installed native operation remain unverified.

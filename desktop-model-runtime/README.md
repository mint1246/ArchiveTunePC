# Desktop model runtime

The optional JVM model-runtime module contains the existing Windows runner lookup/environment setup, pinned STEM/chord specifications, manifest parsing and verification, and model/asset download with size and SHA-256 checks and atomic replacement. Callers provide their model directory and default manifest URL. Android is not supported by this Windows runner adapter; the Rust runner and native DLLs are not included in this artifact.

ArchiveTune supplies the same cache directory and manifest URL as before. Existing explicit model-path environment overrides continue to bypass download/pin checks. Locks are bounded and keyed by directory/model so unrelated directories never reuse a previously verified path; each lookup verifies files against the requested spec. This avoids retaining a process-global map of arbitrary model IDs.

Experimental Maven Local coordinates: `moe.rukamori.archivetune:desktop-model-runtime:0.1.0-SNAPSHOT`. Publish with `:desktop-model-runtime:publishToMavenLocal`. The independent `examples/headless-model` project validates the checked-in manifest without running inference.

JVM tests include local HTTP downloads, coalescing, tamper rejection and legacy/schema-2 manifests. The full desktop app tests, Rust tests and a quick Graal native compile pass. A packaged runner inference or installed app has not been tested for this extraction.

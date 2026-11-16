# Desktop neural chord analysis

This module owns the existing cached local ChordMini analysis flow, including runner process management, model verification, result parsing and atomic cache writes. Hosts supply cache/model directories, a manifest URL, an audio-decoding callback and optional diagnostic reporting. The desktop app passes its real VLC decoder, preserving stream resolution and existing audio behavior. Songsterr source priority remains in the lyrics presenter, outside this module.

This is a project module rather than an independently published artifact: chord models currently live in `desktop-backend`. The cache-reuse test, desktop compilation and quick Graal native compile pass. A live runner inference, cancellation on a real GPU worker, and installed-package operation have not been verified by this extraction.

# Desktop Spotify playlist importer

The existing real Spotify-to-YouTube playlist importer is isolated from `desktop-backend` in this JVM module. It uses the existing Spotify client and a caller-provided `ArchiveTuneJvmClient`; it still performs playlist creation, serial ordered batch appends with retries, and optional cover copying. `desktopApp` uses this implementation directly.

This is a Gradle project module, not yet an independently published artifact: its backend and provider dependencies must also be made independently distributable. The moved link and ordered-batch tests, desktop-backend tests, desktop compilation and a quick Graal native compile pass. These do not prove live import, account-specific behavior or installed-package operation.

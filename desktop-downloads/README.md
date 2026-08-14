# Desktop downloads

JVM download engine for the Compose Desktop client.  It deliberately has no
dependency on the UI, playback, or backend modules: callers pass a neutral
`DownloadRequest` containing the short-lived resolved URL and its request
headers.

Downloads are streamed with OkHttp to `*.part`, then atomically moved into the
configured directory. The small `downloads.properties` journal contains only
safe display metadata, the final filename, and status; it never stores URLs or
HTTP headers. A restarted queued/running download is surfaced as failed because
its signed URL cannot safely be recovered from disk. Re-submit a freshly
resolved request to resume it.

The code is part of ArchiveTune and is distributed under GPL-3.0-or-later; see
the repository [LICENSE](../LICENSE). Runtime dependency: OkHttp (Apache-2.0).

To wire it later, add `include(":desktop-downloads")` in `settings.gradle.kts`
and `implementation(project(":desktop-downloads"))` to the desktop consumer.

package moe.rukamori.archivetune.stream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class YtDlpStreamResolverTest {
    private val videoId = "jNQXAC9IVRw"
    private val now = 1_700_000_000_000L
    private fun audio(): JSONObject = JSONObject().apply {
        put("id", videoId); put("url", "https://media.example/audio?expire=1700003600")
        put("protocol", "https"); put("vcodec", "none"); put("acodec", "opus")
        put("ext", "webm"); put("format_id", "251"); put("abr", 128.4)
        put("duration", 19.5); put("asr", 48000); put("audio_channels", 2); put("filesize", 12345)
        put("title", "Public video"); put("uploader", "Author")
        put("http_headers", JSONObject().put("User-Agent", "test-agent").put("Origin", "https://www.youtube.com"))
    }

    @Test fun `maps direct audio metadata headers and expiry into existing format contract`() {
        val result = parseYtDlpAudio(audio().toString(), videoId, now)
        assertEquals(251, result.format.itag)
        assertEquals("audio/webm; codecs=\"opus\"", result.format.mimeType)
        assertEquals(128400, result.format.bitrate)
        assertEquals(12345L, result.format.contentLength)
        assertEquals("19500", result.format.approxDurationMs)
        assertEquals(48000, result.format.audioSampleRate)
        assertEquals(1_700_003_600_000L, result.expiresAtEpochMillis)
        assertEquals("test-agent", result.headers["User-Agent"])
        assertEquals("Public video", result.videoDetails.title)
    }

    @Test fun `never hands login credentials or transport headers to media CDN`() {
        val info = audio()
        info.getJSONObject("http_headers").put("Cookie", "SID=secret").put("Authorization", "secret")
            .put("Proxy-Authorization", "secret").put("Range", "bytes=0-1").put("Host", "youtube.com")
        val result = parseYtDlpAudio(info.toString(), videoId, now)
        assertEquals(setOf("User-Agent", "Origin"), result.headers.keys)
    }

    @Test fun `rejects mismatched ids manifests muxed video malformed headers and expiring URLs`() {
        val invalid = listOf(
            audio().put("id", "other-id"), audio().put("protocol", "m3u8_native"),
            audio().put("vcodec", "avc1"), audio().put("url", "file:///etc/passwd"),
            audio().put("url", "https://user:pass@media.example/audio"),
            audio().put("requested_formats", listOf("251", "140")),
            audio().put("http_headers", JSONObject().put("User-Agent", "bad\r\nCookie: secret")),
        )
        invalid.forEach { info -> assertEquals(YtDlpFailure.INVALID_OUTPUT,
            assertFailsWith<YtDlpException> { parseYtDlpAudio(info.toString(), videoId, now) }.kind) }
        assertEquals(YtDlpFailure.INVALID_OUTPUT,
            assertFailsWith<YtDlpException> { parseYtDlpAudio("not-json SECRET", videoId, now) }.kind)
    }

    @Test fun `uses conservative expiry without URL timestamp and accepts m4a`() {
        val result = parseYtDlpAudio(audio().put("ext", "m4a").put("acodec", "mp4a.40.2")
            .put("url", "https://media.example/audio").toString(), videoId, now)
        assertEquals(now + 300_000, result.expiresAtEpochMillis)
        assertTrue(result.format.mimeType.startsWith("audio/mp4"))
    }

    @Test fun `requires actual ng runtime and EJS capabilities rather than version alone`() {
        val diagnostics = "[debug] yt-dlp version stable@2026.08.19 from yt-dlp/yt-dlp\n" +
            "[debug] Optional libraries: yt_dlp_ejs-0.8.0\n[debug] JS runtimes: quickjs-ng-0.17.0\n"
        assertEquals(YtDlpToolchain("2026.08.19", "0.17.0", "0.8.0"), parseToolchain(diagnostics))
        listOf(diagnostics.replace("yt_dlp_ejs-0.8.0", ""), diagnostics.replace("quickjs-ng", "quickjs"),
            diagnostics.replace("quickjs-ng-0.17.0", "none")).forEach {
            assertEquals(YtDlpFailure.TOOLCHAIN, assertFailsWith<YtDlpException> { parseToolchain(it) }.kind)
        }
    }

    @Test fun `quality selectors support only direct audio with bounded bitrate preference`() {
        assertTrue(formatSelector(StreamAudioQuality.LOW).contains("[abr<=70]"))
        assertTrue(formatSelector(StreamAudioQuality.HIGH).contains("[abr<=160]"))
        assertEquals("bestaudio[protocol=https]/bestaudio[protocol=http]", formatSelector(StreamAudioQuality.HIGHEST))
        assertTrue(formatSelector(StreamAudioQuality.HIGH, setOf("251-drc")).split('/').all { "[format_id!=251-drc]" in it })
        assertEquals(YtDlpFailure.INVALID_OUTPUT, assertFailsWith<YtDlpException> {
            formatSelector(StreamAudioQuality.HIGH, setOf("bad][format"))
        }.kind)
    }

    @Test fun `expired streams and actionable YouTube errors have retryable or authentication classes`() {
        assertEquals(YtDlpFailure.EXPIRED_STREAM, assertFailsWith<YtDlpException> {
            parseYtDlpAudio(audio().put("url", "https://media.example/audio?expire=1700000001").toString(), videoId, now)
        }.kind)
        assertEquals(251, parseYtDlpAudio(audio().put("format_id", "251-drc").toString(), videoId, now).format.itag)
        for (code in listOf(403, 429, 503)) assertEquals(YtDlpFailure.NETWORK, classifyYtDlpError("ERROR: HTTP Error $code"))
        assertEquals(YtDlpFailure.AUTH_REQUIRED, classifyYtDlpError("Join this channel for members-only content"))
        assertEquals(YtDlpFailure.AUTH_REQUIRED, classifyYtDlpError("This video is for members only"))
        assertEquals(YtDlpFailure.AUTH_REQUIRED, classifyYtDlpError("Sign in to confirm you're not a bot"))
        assertEquals(YtDlpFailure.UNAVAILABLE, classifyYtDlpError("This content isn’t available"))
        assertEquals(YtDlpFailure.EXTRACTION, classifyYtDlpError("Unrelated file ejs-notes.txt is missing"))
    }

    @Test fun `successful exit cannot silently lose its required runtime or EJS`() {
        assertTrue(hasYtDlpToolchainFailure("WARNING: No supported JavaScript runtime could be found"))
        assertTrue(hasYtDlpToolchainFailure("WARNING: No usable challenge solver lib script available"))
        assertTrue(hasYtDlpToolchainFailure("Error running QuickJS process (returncode: 3)"))
        assertFalse(hasYtDlpToolchainFailure("WARNING: unrelated metadata is missing"))
    }

    @Test fun `cookie bridge preserves equals signs and scopes cookies to secure youtube requests`() {
        val result = netscapeCookies("SID=one=two; SAPISID=secret")
        assertTrue(result.contains(".youtube.com\tTRUE\t/\tTRUE\t0\tSID\tone=two\n"))
        assertTrue(result.contains("\tSAPISID\tsecret\n"))
        assertFailsWith<YtDlpException> { netscapeCookies("SID=bad\nline") }
        assertFailsWith<YtDlpException> { netscapeCookies("not-a-cookie") }
    }

    @Test fun `failure classes carry no subprocess secrets`() {
        val samples = mapOf("Sign in to confirm private-sensitive-marker" to YtDlpFailure.AUTH_REQUIRED,
            "This video is unavailable private-sensitive-marker" to YtDlpFailure.UNAVAILABLE,
            "unable to download webpage private-sensitive-marker" to YtDlpFailure.NETWORK,
            "timed out private-sensitive-marker" to YtDlpFailure.TIMEOUT, "unknown private-sensitive-marker" to YtDlpFailure.EXTRACTION)
        samples.forEach { (diagnostic, expected) ->
            val kind = classifyYtDlpError(diagnostic)
            assertEquals(expected, kind)
            assertFalse(YtDlpException(kind).stackTraceToString().contains("private-sensitive-marker"))
        }
    }

    @Test fun `caller cancellation kills extraction and descendants and removes temporary cookies`() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "POSIX subprocess fixture requires /bin/sh")
        val root = Files.createTempDirectory("resolver-cancel-test-")
        try {
            val fixture = createFixture(root, "sleep")
            val resolver = YtDlpStreamResolver(YtDlpConfiguration(fixture.toString(), "qjs", 10_000, root))
            val task = async { resolver.resolve(videoId, cookie = "SID=private-value") }
            withTimeout(5000) { while (!Files.exists(root.resolve("started"))) delay(20) }
            val pid = Files.readString(root.resolve("pid")).trim().toLong()
            assertTrue(ProcessHandle.of(pid).get().isAlive)
            task.cancelAndJoin()
            withTimeout(3000) { while (ProcessHandle.of(pid).map { it.isAlive && !isZombie(pid) }.orElse(false)) delay(20) }
            assertFalse(Files.list(root).use { paths -> paths.anyMatch { it.fileName.toString().startsWith("archivetune-resolver-") } })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `own deadline reports timeout while external deadline stays cancellation`() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "POSIX subprocess fixture requires /bin/sh")
        val root = Files.createTempDirectory("resolver-timeout-test-")
        try {
            val fixture = createFixture(root, "sleep")
            val resolver = YtDlpStreamResolver(YtDlpConfiguration(fixture.toString(), "qjs", 500, root))
            val failure = assertFailsWith<YtDlpException> { resolver.resolve(videoId) }
            assertEquals(YtDlpFailure.TIMEOUT, failure.kind)
            val other = YtDlpStreamResolver(YtDlpConfiguration(fixture.toString(), "qjs", 10_000, root))
            assertFailsWith<CancellationException> { withTimeout(500) { other.resolve(videoId) } }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `subprocess invocation handles spaces ignores configs and deletes cookies on success`() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "POSIX subprocess fixture requires /bin/sh")
        val root = Files.createTempDirectory("resolver space test ")
        try {
            val fixture = createFixture(root, "success")
            val resolver = YtDlpStreamResolver(YtDlpConfiguration(fixture.toString(), "qjs path with spaces", 5000, root, root.resolve("player cache")))
            val result = resolver.resolve(videoId, cookie = "SID=private-value",
                proxyUrl = "http://test-user:private-proxy@localhost:8080", dataSyncId = "123||456")
            assertEquals(videoId, result.videoId)
            val args = Files.readString(root.resolve("args"))
            assertTrue(args.contains("--ignore-config\n--no-plugin-dirs\n--no-remote-components\n--no-js-runtimes\n"))
            assertTrue(args.contains("quickjs:qjs path with spaces\n"))
            assertTrue(args.contains("--cache-dir\n${root.resolve("player cache")}\n"))
            assertTrue(Files.isDirectory(root.resolve("player cache")))
            assertFalse(args.contains("private-value"))
            assertFalse(args.contains("private-proxy"))
            assertFalse(args.contains("123||456"))
            val requestConfig = Files.readString(root.resolve("request-snapshot"))
            assertTrue(requestConfig.contains("youtube:data_sync_id=123||456"))
            assertTrue(requestConfig.contains("http://test-user:private-proxy@localhost:8080"))
            assertTrue(Files.exists(root.resolve("cookie-checked")))
            assertFalse(Files.list(root).use { paths -> paths.anyMatch { it.fileName.toString().startsWith("archivetune-resolver-") } })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `concurrent extraction keeps cookie files isolated and capability check single flight`() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "POSIX subprocess fixture requires /bin/sh")
        val root = Files.createTempDirectory("resolver-parallel-test-")
        try {
            val fixture = createFixture(root, "success")
            val resolver = YtDlpStreamResolver(YtDlpConfiguration(fixture.toString(), "qjs", 5000, root))
            val first = async { resolver.resolve(videoId, cookie = "SID=first") }
            val second = async { resolver.resolve(videoId, cookie = "SID=second") }
            assertEquals(videoId, first.await().videoId)
            assertEquals(videoId, second.await().videoId)
            assertEquals(1, Files.readAllLines(root.resolve("verifications")).size)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `nonzero extraction and oversized output clean up request credentials`() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "POSIX subprocess fixture requires /bin/sh")
        val root = Files.createTempDirectory("resolver-errors-test-")
        try {
            for ((mode, expected) in listOf("fail" to YtDlpFailure.AUTH_REQUIRED, "flood" to YtDlpFailure.INVALID_OUTPUT)) {
                val fixture = createFixture(root, mode)
                val resolver = YtDlpStreamResolver(YtDlpConfiguration(fixture.toString(), "qjs", 5000, root))
                val failure = assertFailsWith<YtDlpException> { resolver.resolve(videoId, cookie = "SID=private-value") }
                assertEquals(expected, failure.kind)
                assertFalse(failure.stackTraceToString().contains("private-value"))
                assertFalse(Files.list(root).use { paths -> paths.anyMatch { it.fileName.toString().startsWith("archivetune-resolver-") } })
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `runtime warning after a cached verification fails and invalidates the capability check`() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "POSIX subprocess fixture requires /bin/sh")
        val root = Files.createTempDirectory("resolver-runtime-change-")
        try {
            val fixture = createFixture(root, "success")
            val resolver = YtDlpStreamResolver(YtDlpConfiguration(fixture.toString(), "qjs", 5000, root))
            resolver.resolve(videoId)
            createFixture(root, "runtime-missing")
            val failure = assertFailsWith<YtDlpException> { resolver.resolve(videoId, cookie = "SID=private-value") }
            assertEquals(YtDlpFailure.TOOLCHAIN, failure.kind)
            createFixture(root, "success")
            resolver.resolve(videoId)
            assertEquals(2, Files.readAllLines(root.resolve("verifications")).size)
            assertFalse(Files.list(root).use { paths -> paths.anyMatch { it.fileName.toString().startsWith("archivetune-resolver-") } })
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `temporary cleanup never follows sidecar symlinks outside the request directory`() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "POSIX subprocess fixture requires /bin/sh")
        val root = Files.createTempDirectory("resolver-symlink-test-")
        try {
            Files.createDirectory(root.resolve("keep"))
            Files.writeString(root.resolve("keep/untouched"), "preserve")
            val fixture = createFixture(root, "symlink")
            YtDlpStreamResolver(YtDlpConfiguration(fixture.toString(), "qjs", 5000, root)).resolve(videoId)
            assertEquals("preserve", Files.readString(root.resolve("keep/untouched")))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `startup removes abandoned credentials while retaining active request directories`() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "POSIX subprocess fixture requires /bin/sh")
        val root = Files.createTempDirectory("resolver-sweep-test-")
        try {
            val orphan = Files.createDirectory(root.resolve("archivetune-resolver-orphan"))
            Files.writeString(orphan.resolve(".owner.lock"), "")
            Files.writeString(orphan.resolve("cookies.txt"), "SID=private-value")
            Files.setLastModifiedTime(orphan, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 120_000))
            val recent = Files.createDirectory(root.resolve("archivetune-resolver-recent"))
            Files.writeString(recent.resolve(".owner.lock"), "")
            val active = Files.createDirectory(root.resolve("archivetune-resolver-active"))
            java.nio.channels.FileChannel.open(active.resolve(".owner.lock"),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    Files.setLastModifiedTime(active, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 120_000))
                    val fixture = createFixture(root, "success")
                    YtDlpStreamResolver(YtDlpConfiguration(fixture.toString(), "qjs", 5000, root)).resolve(videoId)
                    assertFalse(Files.exists(orphan))
                    assertTrue(Files.isDirectory(active))
                    assertTrue(Files.isDirectory(recent))
                }
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `an exited parent cannot orphan a process that still holds stdout`() = runBlocking<Unit> {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/setsid")), "Linux process group fixture requires setsid")
        val root = Files.createTempDirectory("resolver-orphan-test-")
        try {
            val fixture = createFixture(root, "exit-parent")
            val result = YtDlpStreamResolver(YtDlpConfiguration(fixture.toString(), "qjs", 5000, root)).resolve(videoId)
            assertEquals(videoId, result.videoId)
            val pid = Files.readString(root.resolve("pid")).trim().toLong()
            withTimeout(3000) { while (ProcessHandle.of(pid).map { it.isAlive && !isZombie(pid) }.orElse(false)) delay(20) }
        } finally { root.toFile().deleteRecursively() }
    }

    private fun createFixture(root: Path, mode: String): Path {
        val response = audio().put("url", "https://media.example/audio?expire=9999999999").toString()
        Files.writeString(root.resolve("response.json"), response)
        val script = root.resolve("fake yt-dlp")
        // Derive root from script location rather than interpolating an unescaped filesystem path.
        Files.writeString(script, """
            #!/bin/sh
            root=${'$'}(CDPATH= cd -- "${'$'}(dirname -- "${'$'}0")" && pwd)
            case "${'$'}*" in
              *archivetune-runtime-check:*)
                echo checked >> "${'$'}root/verifications"
                printf '[debug] yt-dlp version stable@2026.08.19 from fixture\n[debug] Optional libraries: yt_dlp_ejs-0.8.0\n[debug] JS runtimes: quickjs-ng-0.17.0\n' >&2
                exit 1 ;;
            esac
            printf '%s\n' "${'$'}@" > "${'$'}root/args"
            previous=''
            for arg in "${'$'}@"; do
              if [ "${'$'}previous" = '--cookies' ]; then
                test -f "${'$'}arg" || exit 3
                test "${'$'}(stat -c %a "${'$'}(dirname "${'$'}arg")")" = 700 || exit 4
                touch "${'$'}root/cookie-checked"
              fi
              if [ "${'$'}previous" = '--config-locations' ]; then
                cat "${'$'}arg" > "${'$'}root/request-snapshot"
              fi
              previous=${'$'}arg
            done
            if [ '$mode' = exit-parent ]; then
              sleep 60 &
              echo ${'$'}! > "${'$'}root/pid"
              cat "${'$'}root/response.json"
              exit 0
            elif [ '$mode' = fail ]; then
              printf 'ERROR: Sign in to confirm private-value\n' >&2
              exit 1
            elif [ '$mode' = flood ]; then
              dd if=/dev/zero bs=1048576 count=17 2>/dev/null
            elif [ '$mode' = sleep ]; then
              sleep 60 &
              echo ${'$'}! > "${'$'}root/pid"
              touch "${'$'}root/started"
              wait
            else
              if [ '$mode' = symlink ]; then
                ln -s "${'$'}root/keep" "${'$'}TMPDIR/link"
              fi
              if [ '$mode' = runtime-missing ]; then
                printf 'WARNING: No supported JavaScript runtime could be found\n' >&2
              fi
              cat "${'$'}root/response.json"
            fi
        """.trimIndent())
        script.toFile().setExecutable(true)
        return script
    }

    private fun isZombie(pid: Long): Boolean = runCatching {
        Files.readString(Path.of("/proc/$pid/stat")).substringAfterLast(") ").startsWith("Z")
    }.getOrDefault(false)
}

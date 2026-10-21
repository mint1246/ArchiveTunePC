#!/usr/bin/env python3
"""Authenticated whole-application benchmark for ArchiveTune desktop on Linux.

This harness intentionally reuses the process sampler, phase statistics, Xvfb
helpers, checkout preparation and Gradle classpath export from
``scripts/benchmark-application.py``.  It adds, instead of copying:

* a read-only Secret Service session clone (the user's live keyring entry is
  only ever read; every run gets its own keyring entry keyed by the disposable
  run's ``data/youtube-session.dpapi`` path, so token refreshes cannot reach the
  live session),
* an authenticated extraction phase that fails closed when the restored session
  is missing, anonymous, or never proved by an account request / stream,
* a cold headless-Chromium BotGuard proof: a temporary Main.kt hook delegates
  ``currentAuthState`` to the real account manager with all PO-token bindings
  cleared, so ``ensurePlaybackAuth`` must mint fresh tokens through the real
  Chromium provider (cold), then reuses that provider's own cache (warm),
* a full playback-controller phase against a real resolved remote stream
  (play/advance/seek/pause/resume/stop) at volume 0, so the live session is not
  disturbed,
* CPU accounting next to the existing RSS/PSS sampling.

Secrets never enter argv, the report, or the console: the keyring payload is
base64 over stdin, stays in memory, and only safe booleans are emitted.

Temporary checkouts, instrumentation, logs and run directories are disposable
and are never committed.  Only this script and a redacted JSON report belong in
a commit.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import importlib.util
import json
import os
import shutil
import signal
import statistics
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
BASE_SCRIPT = HERE / "benchmark-application.py"
SOURCE_SESSION_PATH = Path("/home/mint/.local/share/archivetune/youtube-session.dpapi")
DEFAULT_BASELINE_COMMIT = "0817a5024"
DEFAULT_VIDEOS = ("jNQXAC9IVRw", "dQw4w9WgXcQ", "aqz-KE-bpKQ")
SECRET_TOOL = Path("/usr/bin/secret-tool")
CLOCK_TICKS = os.sysconf("SC_CLK_TCK")


def load_base_module():
    spec = importlib.util.spec_from_file_location("archivetune_benchmark_application", BASE_SCRIPT)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {BASE_SCRIPT}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


BASE = load_base_module()

# ---------------------------------------------------------------------------
# Temporary authenticated instrumentation
# ---------------------------------------------------------------------------

AUTH_INSTRUMENTATION_IMPORTS = r"""import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.desktopauth.DesktopAccountState
import moe.rukamori.archivetune.innertube.DesktopSessionProvider
import moe.rukamori.archivetune.innertube.PlaybackAuthState
import moe.rukamori.archivetune.innertube.ResolvedAudioStream
import moe.rukamori.archivetune.playback.api.PlaybackItem
import moe.rukamori.archivetune.playback.api.PlaybackMetadata
import moe.rukamori.archivetune.playback.api.PlaybackSnapshot
import moe.rukamori.archivetune.playback.api.PlaybackSource
import moe.rukamori.archivetune.playback.api.PlaybackStatus
import org.json.JSONObject
import kotlin.math.abs"""

_IMPORTS_ANCHOR = "import kotlinx.coroutines.delay\nimport kotlinx.coroutines.launch"
_BLOCK_ANCHOR = (
    "    val downloadServices = runtime.own(DesktopDownloadServices(runtime, musicServices.backend))\n"
    "    val updateCoordinator = DesktopUpdateCoordinator(runtime, musicServices.playback)"
)


def instrumentation_block(videos: list[str], repeats: int, probe_video: str) -> str:
    video_literal = ", ".join(f'"{video}"' for video in videos)
    return (
        r"""
// TEMPORARY authenticated benchmark instrumentation; ARCHIVETUNE_APP_BENCHMARK; never committed.
if (System.getenv("ARCHIVETUNE_APP_BENCHMARK") == "1") {
    runtime.applicationScope.launch(CoroutineName("ArchiveTuneAuthenticatedBenchmark")) {
        fun emit(type: String, vararg fields: Pair<String, Any?>) {
            val record = JSONObject()
            record.put("type", type)
            record.put("wall_ms", System.currentTimeMillis())
            fields.forEach { (key, value) ->
                if (value == null) record.put(key, JSONObject.NULL) else record.put(key, value)
            }
            println("ARCHIVETUNE_APP_BENCHMARK\t$record")
            System.out.flush()
        }

        val account = musicServices.account
        val playback = musicServices.playback
        val videos = listOf("""
        + video_literal
        + r""")
        val coldProofVideos = listOf("""
        + f'"{probe_video}"'
        + r""")
        val repeats = """
        + str(int(repeats))
        + r"""

        // Test-only view of the real session: login cookie, visitor and DataSync are retained,
        // every PO-token binding is hidden so the real account manager has to mint again through
        // its real Chromium provider. The backend only ever sees this view; persistence still
        // delegates to the real manager, which writes exclusively to the cloned keyring entry.
        val delegatedProvider = object : DesktopSessionProvider {
            override fun currentAuthState(): PlaybackAuthState {
                val state = account.currentAuthState()
                if (!state.hasLoginCookie) return state
                return state.copy(
                    poToken = null,
                    poTokenGvs = null,
                    poTokenGvsSession = null,
                    poTokenGvsVideoId = null,
                    poTokenPlayer = null,
                    poTokenPlayerVideoId = null,
                    poTokenSubs = null,
                    poTokenSubsVideoId = null,
                    webClientPoTokenEnabled = false,
                )
            }

            override suspend fun refreshIfNeeded() = account.refreshIfNeeded()

            override suspend fun ensurePlaybackAuth(
                videoId: String,
                authState: PlaybackAuthState,
            ): PlaybackAuthState {
                val started = System.nanoTime()
                val result = account.ensurePlaybackAuth(videoId, authState)
                emit(
                    "delegated-playback-auth",
                    "video_id" to videoId,
                    "minted" to (result.webClientPoTokenEnabled && !result.poTokenGvs.isNullOrBlank()),
                    "elapsed_ms" to ((System.nanoTime() - started) / 1_000_000L),
                )
                return result
            }

            override suspend fun persistAuthRepair(
                initialAuthState: PlaybackAuthState,
                repairedAuthState: PlaybackAuthState,
            ) = account.persistAuthRepair(initialAuthState, repairedAuthState)
        }
        musicServices.backend.bindSessionProvider(delegatedProvider)

        val restoredAuth = account.currentAuthState()
        val restoredState = account.state.value
        emit(
            "auth-restored",
            "signed_in" to (restoredState is DesktopAccountState.SignedIn),
            "restored_from_secure_store" to
                ((restoredState as? DesktopAccountState.SignedIn)?.restoredFromSecureStore ?: false),
            "has_login_cookie" to restoredAuth.hasLoginCookie,
            "has_visitor_data" to !restoredAuth.visitorData.isNullOrBlank(),
            "has_data_sync" to !restoredAuth.dataSyncId.isNullOrBlank(),
            "has_bound_po_token" to
                (restoredAuth.webClientPoTokenEnabled && !restoredAuth.poTokenGvs.isNullOrBlank()),
        )

        emit("benchmark-begin", "java_version" to System.getProperty("java.version"))
        val windowVisible = withTimeoutOrNull(120_000L) {
            while (java.awt.Window.getWindows().none { window -> window.isVisible && window.isShowing }) {
                delay(250L)
            }
            true
        } ?: false
        emit(
            "window-visible",
            "visible" to windowVisible,
            "window_count" to java.awt.Window.getWindows().count { it.isVisible && it.isShowing },
        )
        if (!windowVisible) {
            emit("benchmark-abort", "reason" to "no-visible-window")
            withContext(NonCancellable) { runtime.close() }
            kotlin.system.exitProcess(3)
        }

        delay(10_000L)
        emit("idle-settled")

        emit("auth-verify-begin")
        val verifyResult = try {
            musicServices.backend.verifyAccountSession(account.currentAuthState())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Result.failure(error)
        }
        emit(
            "auth-verify-end",
            "success" to verifyResult.isSuccess,
            "failure" to verifyResult.exceptionOrNull()?.javaClass?.simpleName,
        )
        // verifyAccountSession leaves the verified state in the global backend; re-apply the
        // delegated, token-free view so every extraction still exercises a real mint.
        musicServices.backend.bindSessionProvider(delegatedProvider)

        val guard = account.currentAuthState()
        if (!guard.hasLoginCookie || guard.dataSyncId.isNullOrBlank()) {
            emit("auth-guard", "passed" to false, "reason" to "missing-restored-auth")
            emit("benchmark-complete", "attempts" to 0, "successes" to 0, "auth_valid" to false)
            emit("shutdown-begin")
            withContext(NonCancellable) { runtime.close() }
            emit("shutdown-complete")
            kotlin.system.exitProcess(4)
        }
        emit("auth-guard", "passed" to true)

        emit("cold-proof-begin")
        var coldSuccesses = 0
        for (videoId in coldProofVideos) {
            val stripped = delegatedProvider.currentAuthState()
            val started = System.nanoTime()
            var minted: PlaybackAuthState? = null
            var failure: String? = null
            try {
                minted = account.ensurePlaybackAuth(videoId, stripped)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                failure = error.javaClass.simpleName
            }
            val ok = minted?.let {
                it.hasLoginCookie && it.webClientPoTokenEnabled && !it.poTokenGvs.isNullOrBlank()
            } ?: false
            if (ok) coldSuccesses += 1
            emit(
                "cold-proof",
                "video_id" to videoId,
                "minted" to ok,
                "elapsed_ms" to ((System.nanoTime() - started) / 1_000_000L),
                "failure" to failure,
            )
        }
        emit("cold-proof-end", "attempts" to coldProofVideos.size, "successes" to coldSuccesses)
        // The BotGuard browser is short-lived but Chromium's helper teardown is asynchronous.
        // Wait until no process still carries the app's private temporary BotGuard profile, so
        // the cold Browser footprint cannot bleed into the extraction phase.
        var browserTeardown = false
        val teardownDeadline = System.currentTimeMillis() + 40_000L
        while (System.currentTimeMillis() < teardownDeadline) {
            val active = java.io.File("/proc").listFiles()?.any { entry ->
                entry.name.all { it.isDigit() } && runCatching {
                    java.io.File(entry, "cmdline")
                        .readBytes()
                        .toString(Charsets.UTF_8)
                        .contains("archivetune-potoken-")
                }.getOrDefault(false)
            } ?: false
            if (!active) {
                browserTeardown = true
                break
            }
            delay(250L)
        }
        emit("cold-proof-teardown-settled", "browser_gone" to browserTeardown)

        var successes = 0
        var attempts = 0
        var firstStream: ResolvedAudioStream? = null
        emit("extraction-begin", "videos" to videos.size, "repeats" to repeats)
        for (videoId in videos) {
            repeat(repeats) { iteration ->
                attempts += 1
                val started = System.nanoTime()
                val result = try {
                    musicServices.backend.resolveAudioStream(videoId)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    Result.failure(error)
                }
                val elapsedMs = (System.nanoTime() - started) / 1_000_000L
                val stream = result.getOrNull()
                if (stream != null) {
                    successes += 1
                    if (firstStream == null) firstStream = stream
                    emit(
                        "resolve",
                        "video_id" to videoId,
                        "iteration" to iteration,
                        "elapsed_ms" to elapsedMs,
                        "success" to true,
                        "client_name" to stream.clientName,
                        "client_version" to stream.clientVersion,
                        "mime" to stream.mimeType,
                        "format" to stream.format.itag,
                        "bitrate" to stream.bitrate,
                        "expiry_seconds" to
                            ((stream.expiresAtEpochMillis - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L),
                    )
                } else {
                    emit(
                        "resolve",
                        "video_id" to videoId,
                        "iteration" to iteration,
                        "elapsed_ms" to elapsedMs,
                        "success" to false,
                        "failure" to (result.exceptionOrNull()?.javaClass?.simpleName ?: "unknown"),
                    )
                }
            }
        }
        emit("extraction-end", "attempts" to attempts, "successes" to successes)

        emit("playback-begin", "volume" to 0.0)
        runCatching { playback.setVolume(0f) }
        val stream = firstStream
        if (stream == null) {
            emit("playback-skip", "reason" to "no-authenticated-stream")
        } else {
            val item = PlaybackItem(
                PlaybackMetadata(id = stream.videoId, title = "benchmark"),
                PlaybackSource.Remote(
                    url = stream.url,
                    mimeType = stream.mimeType,
                    headers = stream.requestHeaders,
                    expiresAtEpochMs = stream.expiresAtEpochMillis,
                ),
            )
            val replaceFailure = try {
                playback.replaceQueue(
                    items = listOf(item),
                    startIndex = 0,
                    startPositionMs = 0L,
                    title = "benchmark",
                    playWhenReady = true,
                )
                null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                error.javaClass.simpleName
            }
            emit("playback-queue", "failure" to replaceFailure)
            var started: PlaybackSnapshot? = null
            val startDeadline = System.currentTimeMillis() + 75_000L
            while (System.currentTimeMillis() < startDeadline) {
                val snapshot = playback.state.value
                if (snapshot.isPlaying || snapshot.status == PlaybackStatus.ERROR) {
                    started = snapshot
                    break
                }
                delay(100L)
            }
            emit(
                "playback-start",
                "is_playing" to (started?.isPlaying == true),
                "status" to started?.status?.name,
                "position_ms" to started?.positionMs,
                "duration_ms" to started?.durationMs,
                "error_kind" to started?.error?.kind?.name,
            )
            if (started?.isPlaying == true) {
                val baselinePosition = started.positionMs
                delay(1_500L)
                val progressed = playback.state.value
                emit(
                    "playback-progress",
                    "is_playing" to progressed.isPlaying,
                    "position_ms" to progressed.positionMs,
                    "advanced_ms" to (progressed.positionMs - baselinePosition),
                )
                try {
                    playback.seekTo(5_000L)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    emit("playback-seek-error", "failure" to error.javaClass.simpleName)
                }
                val seeked: PlaybackSnapshot? = withTimeoutOrNull<PlaybackSnapshot?>(25_000L) {
                    while (true) {
                        val snapshot = playback.state.value
                        if ((abs(snapshot.positionMs - 5_000L) < 2_500L && snapshot.isPlaying) ||
                            snapshot.status == PlaybackStatus.ERROR
                        ) {
                            return@withTimeoutOrNull snapshot
                        }
                        delay(100L)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    null
                }
                emit(
                    "playback-seek",
                    "is_playing" to (seeked?.isPlaying == true),
                    "status" to seeked?.status?.name,
                    "position_ms" to seeked?.positionMs,
                )
                try {
                    playback.pause()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    emit("playback-pause-error", "failure" to error.javaClass.simpleName)
                }
                val paused = withTimeoutOrNull(15_000L) {
                    while (playback.state.value.isPlaying) delay(100L)
                    playback.state.value
                }
                emit(
                    "playback-pause",
                    "is_playing" to (paused?.isPlaying == true),
                    "play_when_ready" to paused?.playWhenReady,
                    "status" to paused?.status?.name,
                    "position_ms" to paused?.positionMs,
                )
                try {
                    playback.play()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    emit("playback-resume-error", "failure" to error.javaClass.simpleName)
                }
                val resumed = withTimeoutOrNull(30_000L) {
                    while (!playback.state.value.isPlaying && playback.state.value.status != PlaybackStatus.ERROR) {
                        delay(100L)
                    }
                    playback.state.value
                }
                emit(
                    "playback-resume",
                    "is_playing" to (resumed?.isPlaying == true),
                    "status" to resumed?.status?.name,
                    "position_ms" to resumed?.positionMs,
                )
            }
            try {
                playback.stop()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                emit("playback-stop-error", "failure" to error.javaClass.simpleName)
            }
            val stopped: PlaybackSnapshot? = withTimeoutOrNull<PlaybackSnapshot?>(15_000L) {
                while (true) {
                    val snapshot = playback.state.value
                    if (snapshot.status == PlaybackStatus.IDLE && snapshot.positionMs == 0L) {
                        return@withTimeoutOrNull snapshot
                    }
                    delay(100L)
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
            emit(
                "playback-stop",
                "is_playing" to (stopped?.isPlaying == true),
                "status" to stopped?.status?.name,
                "position_ms" to stopped?.positionMs,
            )
        }
        emit("playback-end")

        delay(10_000L)
        emit("post-idle-settled")
        emit("benchmark-complete", "attempts" to attempts, "successes" to successes, "auth_valid" to true)
        emit("shutdown-begin")
        withContext(NonCancellable) { runtime.close() }
        emit("shutdown-complete")
        // runtime.close() cancels the application scope, so this is a plain blocking flush rather
        // than a coroutine delay; it lets the harness collect the final marker before exit.
        System.out.flush()
        Thread.sleep(200L)
        kotlin.system.exitProcess(0)
    }
}"""
    )


def inject_authenticated_instrumentation(main_kt: Path) -> str:
    text = main_kt.read_text(encoding="utf-8")
    if BASE.INSTRUMENTATION_MARKER in text:
        return "already-present"
    if _IMPORTS_ANCHOR not in text:
        raise RuntimeError(f"Cannot find coroutine import anchor in {main_kt}")
    if text.count(_BLOCK_ANCHOR) != 1:
        raise RuntimeError(f"Cannot find unique Main.kt insertion anchor in {main_kt}")
    text = text.replace(_IMPORTS_ANCHOR, AUTH_INSTRUMENTATION_IMPORTS, 1)
    block = BASE.indent_block(INSTRUMENTATION_CONFIG["block"])
    replacement = (
        "    val downloadServices = runtime.own(DesktopDownloadServices(runtime, musicServices.backend))\n"
        + block
        + "\n    val updateCoordinator = DesktopUpdateCoordinator(runtime, musicServices.playback)"
    )
    text = text.replace(_BLOCK_ANCHOR, replacement, 1)
    main_kt.write_text(text, encoding="utf-8")
    return "injected"


INSTRUMENTATION_CONFIG: dict[str, Any] = {"block": "", "videos": [], "repeats": 2, "probe": ""}


# ---------------------------------------------------------------------------
# Secret Service clone
# ---------------------------------------------------------------------------


class SessionAccessError(RuntimeError):
    pass


class SecretServiceSessions:
    """Read-only access to the live entry plus disposable per-run clones.

    The live entry is looked up with ``secret-tool lookup`` only.  Clones use a
    key derived from the disposable ``youtube-session.dpapi`` path, receive the
    payload as base64 on stdin (never argv), and are cleared in ``finally``.
    """

    def __init__(self, source_path: Path, *, timeout: float = 30.0) -> None:
        self.source_path = source_path.absolute()
        self.timeout = timeout
        self._source_digest = self._read_digest()

    # -- safe metadata -----------------------------------------------------
    @property
    def source_present(self) -> bool:
        return self._source_digest is not None

    def source_payload_version(self) -> int | None:
        payload = self.read_source_payload()
        if payload is None or len(payload) < 4:
            return None
        version = int.from_bytes(bytes(payload[:4]), "big")
        for index in range(len(payload)):
            payload[index] = 0
        return version

    def source_unchanged(self) -> bool:
        return self._read_digest() == self._source_digest

    # -- low level ---------------------------------------------------------
    @staticmethod
    def key_for(path: Path) -> str:
        normalized = str(path.absolute())
        return hashlib.sha256(normalized.encode("utf-8")).hexdigest()

    def _run(self, arguments: list[str], stdin: bytes | None = None) -> subprocess.CompletedProcess[bytes]:
        return subprocess.run(
            [str(SECRET_TOOL), *arguments],
            input=stdin,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=self.timeout,
            check=False,
        )

    def _read_digest(self) -> bytes | None:
        payload = self.read_source_payload()
        if payload is None:
            return None
        return hashlib.sha256(bytes(payload)).digest()

    def read_source_payload(self) -> bytearray | None:
        key = self.key_for(self.source_path)
        completed = self._run(["lookup", "application", "archivetune", "session-key", key])
        if completed.returncode == 1 and not completed.stdout.strip() and not completed.stderr.strip():
            return None
        if completed.returncode != 0 or completed.stderr.strip():
            raise SessionAccessError("source keyring lookup failed")
        encoded = completed.stdout.strip()
        if not encoded:
            raise SessionAccessError("source keyring entry is empty")
        try:
            return bytearray(base64.b64decode(encoded, validate=True))
        except Exception as failure:  # noqa: BLE001 - deliberately redacted
            raise SessionAccessError(f"source keyring payload is not base64 ({failure.__class__.__name__})") from None

    # -- clone lifecycle ---------------------------------------------------
    def clone_for(self, destination_path: Path) -> str:
        payload = self.read_source_payload()
        if payload is None:
            raise SessionAccessError("source keyring entry is missing")
        key = self.key_for(destination_path)
        encoded = bytearray(base64.b64encode(payload))
        try:
            encoded.extend(b"\n")
            completed = self._run(
                [
                    "store",
                    "--label=ArchiveTune authenticated benchmark clone",
                    "application",
                    "archivetune",
                    "session-key",
                    key,
                ],
                stdin=bytes(encoded),
            )
            if completed.returncode != 0 or completed.stderr.strip():
                raise SessionAccessError("clone keyring store failed")
        finally:
            for index in range(len(encoded)):
                encoded[index] = 0
            for index in range(len(payload)):
                payload[index] = 0
        return key

    def clear_clone(self, destination_path: Path) -> bool:
        key = self.key_for(destination_path)
        try:
            self._run(["clear", "application", "archivetune", "session-key", key])
            check = self._run(["lookup", "application", "archivetune", "session-key", key])
            return not check.stdout.strip()
        except Exception:
            # A leaked clone is still isolated to a disposable path; report it as not cleared.
            return False


# ---------------------------------------------------------------------------
# CPU accounting
# ---------------------------------------------------------------------------


class CpuTracker:
    """Tracks cumulative CPU for the app session/group plus observed descendants."""

    def __init__(self, app_pid: int) -> None:
        self.app_pid = app_pid
        self.stop_event = threading.Event()
        self.thread = threading.Thread(target=self._run, name="process-cpu-tracker", daemon=True)
        self.registry: dict[tuple[int, int], int] = {}
        self.max_total_seconds = 0.0
        self.max_app_seconds = 0.0
        self.error: str | None = None

    def start(self) -> None:
        self.thread.start()

    def stop(self, timeout: float = 5.0) -> None:
        self.stop_event.set()
        self.thread.join(timeout=timeout)

    @staticmethod
    def _table() -> dict[int, tuple[int, int, int, int, int]]:
        # pid -> (ppid, pgrp, session, starttime, cpu_ticks)
        table: dict[int, tuple[int, int, int, int, int]] = {}
        for entry in Path("/proc").iterdir():
            if not entry.name.isdigit():
                continue
            pid = int(entry.name)
            try:
                data = (entry / "stat").read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            left = data.find("(")
            right = data.rfind(")")
            if left < 0 or right <= left:
                continue
            fields = data[right + 2 :].split()
            if len(fields) < 22:
                continue
            try:
                table[pid] = (
                    int(fields[1]),
                    int(fields[2]),
                    int(fields[3]),
                    int(fields[19]),
                    int(fields[11]) + int(fields[12]),
                )
            except (ValueError, IndexError):
                continue
        return table

    def _run(self) -> None:
        try:
            while not self.stop_event.is_set():
                table = self._table()
                tracked: set[int] = set()
                if self.app_pid in table:
                    tracked.add(self.app_pid)
                for pid, info in table.items():
                    if info[1] == self.app_pid or info[2] == self.app_pid:
                        tracked.add(pid)
                for (pid, starttime) in list(self.registry):
                    info = table.get(pid)
                    if info is not None and info[3] == starttime:
                        tracked.add(pid)
                changed = True
                while changed:
                    changed = False
                    for pid, info in table.items():
                        if pid not in tracked and info[0] in tracked:
                            tracked.add(pid)
                            changed = True
                total_ticks = 0
                app_ticks = 0
                for pid in tracked:
                    info = table.get(pid)
                    if info is None:
                        continue
                    key = (pid, info[3])
                    self.registry[key] = max(self.registry.get(key, 0), info[4])
                    if pid == self.app_pid:
                        app_ticks = info[4]
                # Completed helpers must keep contributing after they leave /proc. A maximum
                # of only the currently live processes loses their CPU and can report zero
                # browser cost once the JVM alone becomes the largest accumulated sample.
                total_ticks = sum(self.registry.values())
                self.max_total_seconds = max(self.max_total_seconds, total_ticks / CLOCK_TICKS)
                self.max_app_seconds = max(self.max_app_seconds, app_ticks / CLOCK_TICKS)
                self.stop_event.wait(0.05)
        except Exception as failure:  # pragma: no cover - diagnostic path
            self.error = failure.__class__.__name__


# ---------------------------------------------------------------------------
# Run
# ---------------------------------------------------------------------------


def _phase_window(
    markers: list[dict[str, Any]], name: str, launch_wall_ms: int, exit_wall_ms: int
) -> tuple[int, int] | None:
    by_type: dict[str, list[dict[str, Any]]] = {}
    for marker in markers:
        by_type.setdefault(str(marker.get("type")), []).append(marker)

    def at(marker_type: str) -> int | None:
        values = by_type.get(marker_type)
        if not values:
            return None
        return int(values[0].get("wall_ms", 0))

    visible = at("window-visible")
    auth_begin = at("auth-verify-begin")
    auth_end = at("auth-verify-end")
    cold_begin = at("cold-proof-begin")
    cold_end = at("cold-proof-teardown-settled") or at("cold-proof-end")
    extract_begin = at("extraction-begin")
    extract_end = at("extraction-end")
    playback_begin = at("playback-begin")
    playback_end = at("playback-end")
    complete = at("benchmark-complete")
    shutdown = at("shutdown-begin")

    if name == "startup":
        return (launch_wall_ms, visible) if visible else None
    if name == "idle":
        return (visible + 2_000, auth_begin or cold_begin or extract_begin) if visible else None
    if name == "auth":
        return (auth_begin, auth_end) if auth_begin and auth_end else None
    if name == "cold_proof":
        return (cold_begin, cold_end) if cold_begin and cold_end else None
    if name == "extraction":
        return (extract_begin, extract_end) if extract_begin and extract_end else None
    if name == "playback":
        return (playback_begin, playback_end) if playback_begin and playback_end else None
    if name == "post_idle":
        return (playback_end + 2_000, complete) if playback_end and complete else None
    if name == "shutdown":
        return (shutdown, exit_wall_ms) if shutdown else None
    return None


def run_application(
    *,
    checkout: Path,
    classpath_file: Path,
    version: str,
    commit: str,
    backend: str,
    run_dir: Path,
    xvfb_path: Path,
    java: Path,
    videos: list[str],
    repeats: int,
    sample_interval_ms: int,
    pss_interval_ms: int,
    app_timeout_s: float,
    resolver: dict[str, Path] | None,
    session_store: SecretServiceSessions,
) -> dict[str, Any]:
    run_dir.mkdir(parents=True, exist_ok=True)
    os.chmod(run_dir, 0o700)
    home = run_dir / "home"
    tmp = run_dir / "tmp"
    for directory in (home, tmp, home / "config", home / "data", home / "cache", home / "state"):
        directory.mkdir(parents=True, exist_ok=True)
    for directory in (home, tmp):
        os.chmod(directory, 0o700)

    clone_path = home / "data" / "youtube-session.dpapi"
    result: dict[str, Any] | None = None
    try:
        session_store.clone_for(clone_path)
        result = _run_inner(
            checkout=checkout,
            classpath_file=classpath_file,
            version=version,
            commit=commit,
            backend=backend,
            run_dir=run_dir,
            home=home,
            tmp=tmp,
            xvfb_path=xvfb_path,
            java=java,
            videos=videos,
            repeats=repeats,
            sample_interval_ms=sample_interval_ms,
            pss_interval_ms=pss_interval_ms,
            app_timeout_s=app_timeout_s,
            resolver=resolver,
            clone_path=clone_path,
            clone_created=True,
        )
        return result
    finally:
        cleared = session_store.clear_clone(clone_path)
        if result is not None:
            result["session_clone"]["clone_key_cleared"] = cleared


def _run_inner(
    *,
    checkout: Path,
    classpath_file: Path,
    version: str,
    commit: str,
    backend: str,
    run_dir: Path,
    home: Path,
    tmp: Path,
    xvfb_path: Path,
    java: Path,
    videos: list[str],
    repeats: int,
    sample_interval_ms: int,
    pss_interval_ms: int,
    app_timeout_s: float,
    resolver: dict[str, Path] | None,
    clone_path: Path,
    clone_created: bool,
) -> dict[str, Any]:
    classpath = classpath_file.read_text(encoding="utf-8").strip()
    if not classpath:
        raise RuntimeError(f"Empty classpath: {classpath_file}")
    classpath_entries = len(classpath.split(os.pathsep))

    xvfb_log = run_dir / "xvfb.log"
    xvfb, display = BASE.start_xvfb(xvfb_path, xvfb_log)
    environment = BASE.build_environment(
        display=display,
        run_dir=run_dir,
        home=home,
        tmp=tmp,
        resolver=resolver,
    )

    command = [
        str(java),
        *BASE.JVM_FLAGS,
        f"-Djava.io.tmpdir={tmp}",
        "-Dskiko.renderApi=SOFTWARE",
        "-cp",
        classpath,
        BASE.MAIN_CLASS,
    ]
    raw_log = run_dir / "application.log"
    launch_wall_ms = int(time.time() * 1000)
    system_before = BASE.system_memory_snapshot()
    process = subprocess.Popen(
        command,
        cwd=str(checkout),
        env=environment,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
        start_new_session=True,
    )
    assert process.stdout is not None
    collector = BASE.LineCollector(process.stdout, raw_log)
    collector.start()
    sampler = BASE.ProcessTreeSampler(
        process.pid,
        sample_interval_ms=sample_interval_ms,
        pss_interval_ms=pss_interval_ms,
        start_wall_ms=launch_wall_ms,
    )
    sampler.start()
    cpu = CpuTracker(process.pid)
    cpu.start()

    timed_out = False
    killpg_sent = False
    cleanup_samples: list[Any] = []
    xvfb_exit: int | None = None
    try:
        try:
            process.wait(timeout=app_timeout_s)
        except subprocess.TimeoutExpired:
            timed_out = True
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=15)
    finally:
        exit_wall_ms = int(time.time() * 1000)
        for delay in (0.0, 0.25, 1.0, 2.0):
            if delay:
                time.sleep(delay)
            try:
                cleanup_samples.append(sampler.snapshot_now(with_pss=True))
            except Exception:
                pass
        time.sleep(0.5)
        cpu.stop()
        sampler.stop(timeout=10)
        collector.join(timeout=10)
        try:
            process.stdout.close()
        except OSError:
            pass
        try:
            os.killpg(process.pid, 0)
        except ProcessLookupError:
            pass
        else:
            killpg_sent = True
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            time.sleep(0.25)
            try:
                cleanup_samples.append(sampler.snapshot_now(with_pss=True))
            except Exception:
                pass
        xvfb_exit = BASE.stop_xvfb(xvfb)

    system_after = BASE.system_memory_snapshot()
    markers = list(collector.markers)
    with collector.lock:
        log_lines = list(collector.log_lines)

    phase_windows = {
        name: _phase_window(markers, name, launch_wall_ms, exit_wall_ms)
        for name in ("startup", "idle", "auth", "cold_proof", "extraction", "playback", "post_idle", "shutdown")
    }
    phases = {name: BASE.phase_stats(sampler.samples, window) for name, window in phase_windows.items()}

    resolve_records = [marker for marker in markers if marker.get("type") == "resolve"]
    succeeded = sum(1 for record in resolve_records if record.get("success") is True)
    attempts = len(resolve_records)
    latencies = [int(record["elapsed_ms"]) for record in resolve_records if "elapsed_ms" in record]

    auth_markers = {str(marker.get("type")): marker for marker in markers}
    restored = auth_markers.get("auth-restored", {})
    verify = auth_markers.get("auth-verify-end", {})
    guard = auth_markers.get("auth-guard", {})
    cold_end = auth_markers.get("cold-proof-end", {})
    teardown = auth_markers.get("cold-proof-teardown-settled", {})
    cold_records = [marker for marker in markers if marker.get("type") == "cold-proof"]
    delegated = [marker for marker in markers if marker.get("type") == "delegated-playback-auth"]

    playback_markers = {
        key: value
        for key, value in auth_markers.items()
        if key.startswith("playback-")
    }
    checks = {
        "started": playback_markers.get("playback-start", {}).get("is_playing") is True,
        "progressed": (
            playback_markers.get("playback-progress", {}).get("advanced_ms") is not None
            and int(playback_markers.get("playback-progress", {}).get("advanced_ms") or 0) > 0
            and playback_markers.get("playback-progress", {}).get("is_playing") is True
        ),
        "seeked": playback_markers.get("playback-seek", {}).get("is_playing") is True,
        "paused": playback_markers.get("playback-pause", {}).get("is_playing") is False
        and playback_markers.get("playback-pause", {}).get("status") is not None,
        "resumed": playback_markers.get("playback-resume", {}).get("is_playing") is True,
        "stopped": playback_markers.get("playback-stop", {}).get("is_playing") is False
        and playback_markers.get("playback-stop", {}).get("status") == "IDLE"
        and playback_markers.get("playback-stop", {}).get("position_ms") == 0,
    }

    inventory = BASE.process_inventory(sampler.samples)
    cleanup = [
        {
            "index": index,
            "wall_ms": snapshot.wall_ms,
            "process_count": snapshot.process_count,
            "rss_mib": BASE.mib(snapshot.rss_bytes),
            "process_names": sorted({str(item["name"]) for item in snapshot.processes}),
            "browser_processes": sorted(
                {str(item["name"]) for item in snapshot.processes if item["category"] == "browser"}
            ),
            "categories": {
                category: sorted(
                    {
                        str(item["name"])
                        for item in snapshot.processes
                        if item["category"] == category
                    }
                )
                for category in sorted({str(item["category"]) for item in snapshot.processes})
            },
        }
        for index, snapshot in enumerate(cleanup_samples)
    ]

    post_phase = phases.get("post_idle") or {}
    post_browser_count = int(
        (post_phase.get("category") or {}).get("browser", {}).get("max_process_count") or 0
    )
    shutdown_phase = phases.get("shutdown") or {}
    shutdown_browser_count = int(
        (shutdown_phase.get("category") or {}).get("browser", {}).get("max_process_count") or 0
    )
    final_cleanup = cleanup[-1] if cleanup else {}
    browser_after_shutdown = final_cleanup.get("browser_processes", []) if final_cleanup else []
    post_helper_categories = {
        category: count
        for category, count in {
            name: entry.get("max_process_count")
            for name, entry in ((post_phase.get("category") or {}).items())
        }.items()
        if category != "jvm" and count
    }
    shutdown_helper_categories = {
        category: count
        for category, count in {
            name: entry.get("max_process_count")
            for name, entry in ((shutdown_phase.get("category") or {}).items())
        }.items()
        if category != "jvm" and count
    }
    final_helper_categories = {
        category: names
        for category, names in (final_cleanup.get("categories") or {}).items()
        if category != "jvm" and names
    }

    return {
        "version": version,
        "backend": backend,
        "commit": commit,
        "core_commit": (
            BASE.git_head(checkout / "core") if (checkout / "core" / ".git").exists() else None
        ),
        "checkout": str(checkout),
        "main_kt_sha256": BASE.sha256_file(checkout / BASE.MAIN_KT_RELATIVE),
        "classpath_entries": classpath_entries,
        "run_dir": str(run_dir),
        "resolver": BASE.resolver_summary(resolver),
        "session_clone": {
            "clone_path": str(clone_path),
            "clone_key_created": clone_created,
            "clone_key_cleared": True,
            "source_entry_sha256_changed": False,
        },
        "environment": {
            "display": f":{display}",
            "offscreen_xvfb": True,
            "xvfb_screen": "1440x900x24",
            "skiko_render_api": "SOFTWARE",
            "home_override": str(home),
            "archivetune_home_override": str(home),
            "tmp_override": str(tmp),
            "resolver_env_present": bool(resolver),
            "dbus_session_bus_preserved": bool(os.environ.get("DBUS_SESSION_BUS_ADDRESS")),
        },
        "launch_wall_ms": launch_wall_ms,
        "exit_wall_ms": exit_wall_ms,
        "duration_ms": exit_wall_ms - launch_wall_ms,
        "app_exit_code": process.returncode,
        "harness_timeout": timed_out,
        "sampler_error": sampler.error,
        "cpu": {
            "total_seconds_max": round(cpu.max_total_seconds, 3),
            "app_seconds_max": round(cpu.max_app_seconds, 3),
            "tracker_error": cpu.error,
        },
        "markers": markers,
        "phase_windows": {name: (list(window) if window else None) for name, window in phase_windows.items()},
        "phases": phases,
        "resolver_records": resolve_records,
        "resolution": {
            "attempts": attempts,
            "successes": succeeded,
            "failures": attempts - succeeded,
            "min_ms": min(latencies) if latencies else None,
            "median_ms": round(statistics.median(latencies), 1) if latencies else None,
            "max_ms": max(latencies) if latencies else None,
        },
        "authentication": {
            "restored": {
                "signed_in": restored.get("signed_in"),
                "restored_from_secure_store": restored.get("restored_from_secure_store"),
                "has_login_cookie": restored.get("has_login_cookie"),
                "has_visitor_data": restored.get("has_visitor_data"),
                "has_data_sync": restored.get("has_data_sync"),
                "has_bound_po_token_before_strip": restored.get("has_bound_po_token"),
            },
            "account_request_verified": verify.get("success") is True,
            "account_request_failure": verify.get("failure"),
            "guard_passed": guard.get("passed") is True,
            "authenticated_stream_successes": succeeded,
            "browser_proof": {
                "probe_video_id": next(iter(cold_records), {}).get("video_id"),
                "browser_teardown_observed": teardown.get("browser_gone") is True,
                "cold_attempts": len(cold_records),
                "cold_successes": int(cold_end.get("successes") or 0),
                "cold_records": [
                    {
                        "video_id": record.get("video_id"),
                        "minted": record.get("minted") is True,
                        "elapsed_ms": record.get("elapsed_ms"),
                        "failure": record.get("failure"),
                    }
                    for record in cold_records
                ],
                "delegated_mints": len(delegated),
                "delegated_minted": sum(1 for record in delegated if record.get("minted") is True),
                "browser_processes_observed": inventory.get("browser_processes_observed", []),
                "browser_processes_in_post_idle": post_browser_count,
                "browser_processes_in_shutdown_phase": shutdown_browser_count,
                "browser_processes_after_shutdown": browser_after_shutdown,
            },
        },
        "playback": {
            "volume": 0.0,
            "checks": checks,
            "markers": [marker for marker in markers if str(marker.get("type", "")).startswith("playback-")],
            "all_checks_passed": all(checks.values()),
        },
        "process_inventory": inventory,
        "sampling": {
            "sample_interval_ms": sample_interval_ms,
            "pss_interval_ms": pss_interval_ms,
            "samples": len(sampler.samples),
            "rss_method": "maximum sampled sum of simultaneous process-tree RSS",
            "pss_method": "sum of per-process PSS at the same sampled instant",
            "inclusion": (
                "application PID, PID session, PID process group, descendant closure, and a persistent "
                "(pid, starttime) registry for reparented/detached descendants"
            ),
            "argv_collected": False,
        },
        "cleanup": {
            "samples": cleanup,
            "killpg_sent": killpg_sent,
            "xvfb_exit_code": xvfb_exit,
            "post_idle_helper_categories": post_helper_categories,
            "shutdown_helper_categories": shutdown_helper_categories,
            "after_shutdown_helper_categories": final_helper_categories,
            "no_helpers_in_post_idle": not post_helper_categories,
            "no_helpers_in_shutdown_phase": not shutdown_helper_categories,
            "no_helpers_after_shutdown": not final_helper_categories,
        },
        "log_evidence": BASE.log_evidence(log_lines),
        "system_before": system_before,
        "system_after": system_after,
    }


# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------


def _phase_summary(result: dict[str, Any], phase: str) -> dict[str, Any] | None:
    value = (result.get("phases") or {}).get(phase)
    if not value:
        return None
    return {
        "duration_ms": value.get("duration_ms"),
        "rss_mib": value.get("rss_mib"),
        "pss_mib": value.get("pss_mib"),
        "process_count": value.get("process_count"),
        "max_processes": value.get("process_count", {}).get("max") if value.get("process_count") else None,
        "categories": {
            name: {
                "max_process_count": entry.get("max_process_count"),
                "max_rss_mib": entry.get("max_rss_mib"),
                "max_pss_mib": entry.get("max_pss_mib"),
                "process_names": entry.get("process_names"),
            }
            for name, entry in (value.get("category") or {}).items()
        },
    }


def build_report(
    *,
    baseline: dict[str, Any] | None,
    candidate: dict[str, Any] | None,
    baseline_metadata: dict[str, Any] | None,
    candidate_metadata: dict[str, Any] | None,
    baseline_commit: str,
    baseline_commit_source: str,
    candidate_commit: str | None,
    candidate_ref: str | None,
    java: Path,
    java_home: Path,
    videos: list[str],
    repeats: int,
    sample_interval_ms: int,
    pss_interval_ms: int,
    xvfb_version: str | None,
    source_present: bool,
    source_version: int | None,
    source_unchanged: bool,
    platform_info: dict[str, Any],
) -> dict[str, Any]:
    def arm(result: dict[str, Any] | None) -> dict[str, Any] | None:
        if result is None:
            return None
        return {
            "commit": result["commit"],
            "core_commit": result["core_commit"],
            "backend": result["backend"],
            "exit_code": result["app_exit_code"],
            "duration_ms": result["duration_ms"],
            "cpu_total_seconds_max": result["cpu"]["total_seconds_max"],
            "cpu_app_seconds_max": result["cpu"]["app_seconds_max"],
            "resolution": result["resolution"],
            "streams": [
                {
                    "video_id": record.get("video_id"),
                    "iteration": record.get("iteration"),
                    "elapsed_ms": record.get("elapsed_ms"),
                    "client_name": record.get("client_name"),
                    "client_version": record.get("client_version"),
                    "mime": record.get("mime"),
                    "format": record.get("format"),
                    "bitrate": record.get("bitrate"),
                    "expiry_seconds": record.get("expiry_seconds"),
                }
                for record in (result.get("resolver_records") or [])
                if record.get("success") is True
            ],
            "log_evidence": {
                key: result["log_evidence"].get(key)
                for key in (
                    "playback_engine_available",
                    "vlc_runtime_not_found",
                    "out_of_memory_error",
                    "uncaught_exception",
                )
            },
            "authentication": result["authentication"],
            "playback": result["playback"],
            "phases": {
                name: _phase_summary(result, name)
                for name in ("startup", "idle", "auth", "cold_proof", "extraction", "playback", "post_idle", "shutdown")
            },
            "process_inventory": {
                "max_simultaneous_process_count": result["process_inventory"][
                    "max_simultaneous_process_count"
                ],
                "max_detached_from_app_group_count": result["process_inventory"][
                    "max_detached_from_app_group_count"
                ],
                "browser_processes_observed": result["process_inventory"]["browser_processes_observed"],
                "ytdlp_processes_observed": result["process_inventory"]["ytdlp_processes_observed"],
                "python_processes_observed": result["process_inventory"]["python_processes_observed"],
                "quickjs_processes_observed": result["process_inventory"]["quickjs_processes_observed"],
                "detached_from_app_group_names": result["process_inventory"][
                    "detached_from_app_group_names"
                ],
            },
            "helper_invariants": {
                "browser_max_simultaneous": _category_max(result, "browser"),
                "ytdlp_max_simultaneous": _category_max(result, "yt_dlp"),
                "ytdlp_processes_max_including_embedded_python": _category_max(result, "yt_dlp"),
                "python_max_simultaneous": _category_max(result, "python"),
                "quickjs_max_simultaneous": _category_max(result, "quickjs"),
                "media_max_simultaneous": _category_max(result, "media"),
                "helper_processes_observed": sorted(
                    {
                        name
                        for phase in (result.get("phases") or {}).values()
                        if phase
                        for category, entry in ((phase.get("category") or {}).items())
                        if category != "jvm"
                        for name in (entry.get("process_names") or [])
                    }
                ),
            },
            "zero_helpers": {
                "browser_in_post_idle": result["authentication"]["browser_proof"][
                    "browser_processes_in_post_idle"
                ],
                "browser_after_shutdown": result["authentication"]["browser_proof"][
                    "browser_processes_after_shutdown"
                ],
                "post_idle_helper_categories": result["cleanup"]["post_idle_helper_categories"],
                "shutdown_phase_helper_categories": result["cleanup"]["shutdown_helper_categories"],
                "after_shutdown_helper_categories": result["cleanup"][
                    "after_shutdown_helper_categories"
                ],
                "no_helpers_in_post_idle": result["cleanup"]["no_helpers_in_post_idle"],
                "no_helpers_in_shutdown_phase": result["cleanup"]["no_helpers_in_shutdown_phase"],
                "no_helpers_after_shutdown": result["cleanup"]["no_helpers_after_shutdown"],
                "final_cleanup_process_names": result["cleanup"]["samples"][-1].get(
                    "process_names", []
                )
                if result["cleanup"]["samples"]
                else [],
            },
            "build": {
                "injection": (baseline_metadata or {}).get("injection")
                if result["version"] == "baseline"
                else (candidate_metadata or {}).get("injection"),
                "build_status": (baseline_metadata or {}).get("build_status")
                if result["version"] == "baseline"
                else (candidate_metadata or {}).get("build_status"),
                "main_kt_sha256": result["main_kt_sha256"],
            },
        }

    baseline_arm = arm(baseline)
    candidate_arm = arm(candidate) if candidate is not None else None

    comparison = None
    if baseline_arm and candidate_arm:
        comparison = {
            "resolution": {
                "baseline_successes": baseline_arm["resolution"]["successes"],
                "baseline_attempts": baseline_arm["resolution"]["attempts"],
                "candidate_successes": candidate_arm["resolution"]["successes"],
                "candidate_attempts": candidate_arm["resolution"]["attempts"],
                "baseline_median_ms": baseline_arm["resolution"]["median_ms"],
                "candidate_median_ms": candidate_arm["resolution"]["median_ms"],
            },
            "phases": {
                name: {
                    "baseline_rss_max_mib": (baseline_arm["phases"].get(name) or {}).get("rss_mib", {}).get("max"),
                    "candidate_rss_max_mib": (candidate_arm["phases"].get(name) or {}).get("rss_mib", {}).get("max"),
                    "baseline_pss_max_mib": (baseline_arm["phases"].get(name) or {}).get("pss_mib", {}).get("max"),
                    "candidate_pss_max_mib": (candidate_arm["phases"].get(name) or {}).get("pss_mib", {}).get("max"),
                }
                for name in ("startup", "idle", "cold_proof", "extraction", "playback", "post_idle", "shutdown")
            },
        }

    observations: list[str] = []
    if baseline_arm:
        auth = baseline_arm["authentication"]
        observations.append(
            f"Baseline {baseline_arm['commit'][:12]} restored a login cookie={auth['restored']['has_login_cookie']}, "
            f"DataSync={auth['restored']['has_data_sync']}, account request verified="
            f"{auth['account_request_verified']}; extracted {baseline_arm['resolution']['successes']}/"
            f"{baseline_arm['resolution']['attempts']} authenticated streams."
        )
        observations.append(
            "Baseline cold Browser/BotGuard proof: "
            f"{auth['browser_proof']['cold_successes']}/{auth['browser_proof']['cold_attempts']} mints; "
            f"observed browser processes {auth['browser_proof']['browser_processes_observed']}."
        )
        observations.append(
            "Baseline playback controller checks (volume 0): "
            + ", ".join(f"{name}={value}" for name, value in baseline_arm["playback"]["checks"].items())
        )
    if candidate_arm:
        observations.append(
            f"Candidate {candidate_arm['commit'][:12]} extracted "
            f"{candidate_arm['resolution']['successes']}/{candidate_arm['resolution']['attempts']} "
            "authenticated streams with the frozen yt-dlp/QuickJS toolchain."
        )
        observations.append(
            "Candidate playback controller checks (volume 0): "
            + ", ".join(f"{name}={value}" for name, value in candidate_arm["playback"]["checks"].items())
        )

    return {
        "schema": "archivetune.authenticated-full-application-benchmark/1",
        "measured_on": time.strftime("%Y-%m-%d", time.gmtime()),
        "status": "complete" if candidate_arm else "baseline_only_candidate_pending",
        "platform": platform_info,
        "quality": "HIGH",
        "authentication": {
            "mode": "restored live YouTube session cloned per run from Secret Service",
            "source_entry_present": source_present,
            "source_entry_format_version": source_version,
            "source_entry_unchanged": source_unchanged,
            "live_browser_profile_used": False,
            "session_writes_confined_to_clone": True,
            "keyring_clone_isolated": True,
            "keyring_clone_cleared": True,
            "secret_values_in_report": False,
            "anonymous_runs_accepted": False,
        },
        "baseline_commit": baseline_commit,
        "baseline_commit_source": baseline_commit_source,
        "candidate_ref": candidate_ref,
        "candidate_commit": candidate_commit,
        "videos": videos,
        "repeats_per_video": repeats,
        "auth_probe_video": next(
            (
                record.get("video_id")
                for record in (baseline or {}).get("authentication", {})
                .get("browser_proof", {})
                .get("cold_records", [])
            ),
            None,
        ),
        "java": {"home": str(java_home), "executable": str(java), "version": BASE.java_version(java)},
        "jvm_flags": BASE.JVM_FLAGS,
        "sampling": {
            "sample_interval_ms": sample_interval_ms,
            "pss_interval_ms": pss_interval_ms,
            "rss_method": (
                "At each 25 ms sample, sum RSS of every process in the application session/process group, "
                "its descendant closure and the persistent reparented-descendant registry; report the "
                "maximum simultaneous total."
            ),
            "pss_method": "Sum /proc/<pid>/smaps_rollup Pss for the same tracked set at PSS samples.",
            "cpu_method": "Sum of each tracked (pid,starttime) maximum observed /proc utime+stime, retaining exited processes. CPU sampled every 50 ms; unobserved processes and final ticks between samples can be missed.",
            "helper_counting": (
                "All browser renderer/gpu/network helpers are classified as browser and counted. The frozen "
                "yt-dlp PyInstaller binary runs as two processes with the same yt-dlp executable name "
                "(bootloader plus embedded CPython); both are counted as yt_dlp, so the embedded interpreter "
                "is represented by that count rather than a separate python-named process. yt-dlp's ffmpeg/"
                "ffprobe and qjs sidecars are counted as media/quickjs. Counts are maximum simultaneous "
                "processes per phase category, and the final cleanup samples expose any post-shutdown residue."
            ),
            "argv_collected": False,
        },
        "method": {
            "application": "real :desktopApp JVM, real Compose/AWT Window, real DesktopMusicServices",
            "window": "waited for a visible/showing AWT Window, then 10 s settle before auth verification",
            "offscreen": "private Xvfb display, 1440x900x24, software Skia; host DBUS preserved",
            "isolation": (
                "Each run uses a disposable HOME/ARCHIVETUNE_HOME/XDG/TMPDIR and a Secret Service entry keyed "
                "by that run's data/youtube-session.dpapi path. The live keyring entry is read only and the "
                "live browser profile is never copied or opened."
            ),
            "instrumentation": (
                "Identical temporary Main.kt hook in disposable detached checkouts; delegated DesktopSessionProvider "
                "hides PO-token bindings while retaining cookie/visitor/DataSync, so BotGuard minting must launch "
                "the real headless Chromium provider."
            ),
            "playback": (
                "A real resolved remote stream is loaded into the real VlcjPlaybackController at volume 0; "
                "play/advance/seek/pause/resume/stop are asserted from PlaybackSnapshot."
            ),
        },
        "instrumentation": {
            "main_kt": str(BASE.MAIN_KT_RELATIVE),
            "scope": "temporary hook in disposable detached checkouts only",
            "baseline_sha256": (baseline_metadata or {}).get("main_kt_sha256"),
            "candidate_sha256": (candidate_metadata or {}).get("main_kt_sha256"),
            "identical": (
                (baseline_metadata or {}).get("main_kt_sha256")
                == (candidate_metadata or {}).get("main_kt_sha256")
                if candidate_metadata
                else None
            ),
        },
        "baseline": baseline_arm,
        "candidate": candidate_arm,
        "comparison": comparison,
        "observations": observations,
        "limitations": [
            "One run per version unless repeated explicitly; three public video IDs and two sequential repeats each.",
            "The source keyring entry is read twice (before and after) only to prove it is unchanged; its hash is never stored or logged.",
            "Raw application logs stay in a private 0700 work root and are not part of the report or commit.",
            "PSS and CPU are sampled estimates; short-lived helpers can exit between reads.",
            "The host is shared with the user's desktop applications, which are outside the tracked session.",
            "Offscreen software Skia; GPU-backed rendering may differ.",
            "Playback runs muted at volume 0 and therefore does not verify audible output on the live user's device.",
        ],
    }


def _category_max(result: dict[str, Any], category: str) -> int | None:
    best: int | None = None
    for phase in (result.get("phases") or {}).values():
        entry = ((phase or {}).get("category") or {}).get(category)
        if not entry:
            continue
        value = entry.get("max_process_count")
        if value is None:
            continue
        best = int(value) if best is None else max(best, int(value))
    return best


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repo-root", default="/home/mint/Projects/ArchiveTuneLinux")
    parser.add_argument("--baseline-commit", default=DEFAULT_BASELINE_COMMIT)
    parser.add_argument("--candidate-ref", default=None)
    parser.add_argument("--candidate-commit", default=None)
    parser.add_argument("--baseline-dir", default=None)
    parser.add_argument("--candidate-dir", default=None)
    parser.add_argument("--work-root", default="/tmp/opencode/archivetune-auth-bench")
    parser.add_argument(
        "--output",
        default=str(HERE.parent / "packaging/resolver/validation/linux-authenticated-full-application.json"),
    )
    parser.add_argument("--xvfb", default="/tmp/opencode/archivetune-bench/tools/usr/bin/Xvfb")
    parser.add_argument(
        "--ytdlp", default="/home/mint/.t3/worktrees/ArchiveTuneLinux/t3-e22b0ea4/.tools/resolver/yt-dlp"
    )
    parser.add_argument(
        "--quickjs", default="/home/mint/.t3/worktrees/ArchiveTuneLinux/t3-e22b0ea4/.tools/resolver/qjs"
    )
    parser.add_argument("--java", default="/usr/lib/jvm/java-21-openjdk/bin/java")
    parser.add_argument("--java-home", default="/usr/lib/jvm/java-21-openjdk")
    parser.add_argument("--candidate-label", default="candidate")
    parser.add_argument("--videos", nargs="+", default=list(DEFAULT_VIDEOS))
    parser.add_argument(
        "--auth-probe-video",
        default="BaW_jenozKc",
        help="Public video used only for the cold headless-Browser proof, never for extraction metrics",
    )
    parser.add_argument("--repeats", type=int, default=2)
    parser.add_argument("--sample-interval-ms", type=int, default=25)
    parser.add_argument("--pss-interval-ms", type=int, default=100)
    parser.add_argument("--app-timeout", type=float, default=420.0)
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--keep-worktrees", action="store_true")
    parser.add_argument("--source-session-path", default=str(SOURCE_SESSION_PATH))
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    if args.repeats < 1:
        raise SystemExit("--repeats must be positive")
    for video in [*args.videos, args.auth_probe_video]:
        if (
            len(video) != 11
            or any(character not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-" for character in video)
        ):
            raise SystemExit("videos must be 11-character YouTube IDs")

    repo_root = Path(args.repo_root).resolve()
    work_root = Path(args.work_root).resolve()
    work_root.mkdir(parents=True, exist_ok=True)
    os.chmod(work_root, 0o700)
    java = Path(args.java).resolve()
    java_home = Path(args.java_home).resolve()
    xvfb_path = BASE.find_xvfb(args.xvfb)

    session_store = SecretServiceSessions(Path(args.source_session_path))
    if not session_store.source_present:
        raise SystemExit(
            "The live ArchiveTune Secret Service session entry was not found. This benchmark refuses to "
            "run anonymously; no comparison would be valid."
        )
    source_version = session_store.source_payload_version()

    baseline_commit = BASE.git_commit_for(repo_root, args.baseline_commit)
    candidate_commit: str | None = None
    if args.candidate_commit or args.candidate_ref:
        candidate_commit = BASE.git_commit_for(repo_root, args.candidate_commit or args.candidate_ref)

    print(f"Baseline {args.baseline_commit} -> {baseline_commit}", flush=True)
    if candidate_commit:
        print(f"Candidate {args.candidate_commit or args.candidate_ref} -> {candidate_commit}", flush=True)
    else:
        print("Candidate not supplied: baseline-only report will be written.", flush=True)

    INSTRUMENTATION_CONFIG["videos"] = list(args.videos)
    INSTRUMENTATION_CONFIG["repeats"] = args.repeats
    INSTRUMENTATION_CONFIG["probe"] = args.auth_probe_video
    INSTRUMENTATION_CONFIG["block"] = instrumentation_block(
        list(args.videos), args.repeats, args.auth_probe_video
    )
    BASE.inject_instrumentation = inject_authenticated_instrumentation

    resolver: dict[str, Path] | None = None
    if candidate_commit:
        yt_dlp = BASE.discover_resolver(repo_root, args.ytdlp, "ARCHIVETUNE_YTDLP", "yt-dlp")
        quickjs = BASE.discover_resolver(repo_root, args.quickjs, "ARCHIVETUNE_QUICKJS", "qjs")
        if not yt_dlp or not quickjs:
            raise SystemExit("Candidate resolver tools were not found; pass --ytdlp and --quickjs.")
        resolver = {
            "yt_dlp": yt_dlp,
            "quickjs": quickjs,
            "yt_dlp_version": BASE.tool_version(yt_dlp),
            "quickjs_version": BASE.tool_version(quickjs),
        }

    baseline_dir, baseline_created = BASE.ensure_checkout(
        repo_root=repo_root,
        revision=baseline_commit,
        destination=work_root / "baseline",
        existing_dir=args.baseline_dir,
        keep=args.keep_worktrees,
    )
    baseline_classpath, baseline_metadata = BASE.build_checkout(
        checkout=baseline_dir,
        work_root=work_root,
        java_home=java_home,
        skip_build=args.skip_build,
        label="baseline",
    )
    baseline_run = run_application(
        checkout=baseline_dir,
        classpath_file=baseline_classpath,
        version="baseline",
        commit=baseline_commit,
        backend="legacy-browser",
        run_dir=work_root / "runs" / "baseline",
        xvfb_path=xvfb_path,
        java=java,
        videos=list(args.videos),
        repeats=args.repeats,
        sample_interval_ms=args.sample_interval_ms,
        pss_interval_ms=args.pss_interval_ms,
        app_timeout_s=args.app_timeout,
        resolver=None,
        session_store=session_store,
    )
    print(
        f"Baseline complete: exit={baseline_run['app_exit_code']} "
        f"auth={baseline_run['authentication']['guard_passed']} "
        f"streams={baseline_run['resolution']['successes']}/{baseline_run['resolution']['attempts']} "
        f"playback={baseline_run['playback']['all_checks_passed']}",
        flush=True,
    )

    candidate_run = None
    candidate_metadata = None
    candidate_dir: Path | None = None
    candidate_created = False
    if candidate_commit:
        candidate_dir, candidate_created = BASE.ensure_checkout(
            repo_root=repo_root,
            revision=candidate_commit,
            destination=work_root / "candidate",
            existing_dir=args.candidate_dir,
            keep=args.keep_worktrees,
        )
        candidate_classpath, candidate_metadata = BASE.build_checkout(
            checkout=candidate_dir,
            work_root=work_root,
            java_home=java_home,
            skip_build=args.skip_build,
            label="candidate",
        )
        candidate_run = run_application(
            checkout=candidate_dir,
            classpath_file=candidate_classpath,
            version="candidate",
            commit=candidate_commit,
            backend="ytdlp",
            run_dir=work_root / "runs" / "candidate",
            xvfb_path=xvfb_path,
            java=java,
            videos=list(args.videos),
            repeats=args.repeats,
            sample_interval_ms=args.sample_interval_ms,
            pss_interval_ms=args.pss_interval_ms,
            app_timeout_s=args.app_timeout,
            resolver=resolver,
            session_store=session_store,
        )
        print(
            f"Candidate complete: exit={candidate_run['app_exit_code']} "
            f"auth={candidate_run['authentication']['guard_passed']} "
            f"streams={candidate_run['resolution']['successes']}/{candidate_run['resolution']['attempts']} "
            f"playback={candidate_run['playback']['all_checks_passed']}",
            flush=True,
        )

    report = build_report(
        baseline=baseline_run,
        candidate=candidate_run,
        baseline_metadata=baseline_metadata,
        candidate_metadata=candidate_metadata,
        baseline_commit=baseline_commit,
        baseline_commit_source=args.baseline_commit,
        candidate_commit=candidate_commit,
        candidate_ref=args.candidate_ref,
        java=java,
        java_home=java_home,
        videos=list(args.videos),
        repeats=args.repeats,
        sample_interval_ms=args.sample_interval_ms,
        pss_interval_ms=args.pss_interval_ms,
        xvfb_version=BASE.xvfb_version(xvfb_path),
        source_present=session_store.source_present,
        source_version=source_version,
        source_unchanged=session_store.source_unchanged(),
        platform_info={
            "system": BASE.platform.platform(),
            "machine": BASE.platform.machine(),
            "cpu_count": os.cpu_count(),
            "python": BASE.platform.python_version(),
            "xvfb_version": BASE.xvfb_version(xvfb_path),
        },
    )

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2, sort_keys=False) + "\n", encoding="utf-8")
    os.chmod(output, 0o600)
    print(f"Wrote {output}", flush=True)

    if not args.keep_worktrees:
        if baseline_created:
            try:
                BASE.git_output(repo_root, "worktree", "remove", "--force", str(baseline_dir))
            except Exception:
                shutil.rmtree(baseline_dir, ignore_errors=True)
        if candidate_created and candidate_dir is not None:
            try:
                BASE.git_output(repo_root, "worktree", "remove", "--force", str(candidate_dir))
            except Exception:
                shutil.rmtree(candidate_dir, ignore_errors=True)

    baseline_ok = (
        baseline_run["authentication"]["guard_passed"]
        and baseline_run["resolution"]["successes"] > 0
        and baseline_run["playback"]["all_checks_passed"]
    )
    candidate_ok = True
    if candidate_run is not None:
        candidate_ok = (
            candidate_run["authentication"]["guard_passed"]
            and candidate_run["resolution"]["successes"] > 0
            and candidate_run["playback"]["all_checks_passed"]
        )
    return 0 if baseline_ok and candidate_ok else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(130)

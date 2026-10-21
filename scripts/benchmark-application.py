#!/usr/bin/env python3
"""Benchmark the complete ArchiveTune desktop application on Linux.

This is intentionally not a resolver-JVM benchmark.  It builds the real
``:desktopApp`` application, injects a temporary, uncommitted instrumentation
hook into ``Main.kt`` in disposable checkouts, starts the real Compose/AWT
window on an offscreen X server, waits for the initial UI/services/home work to
settle, resolves the same public video IDs through the real
``DesktopMusicServices.backend``, and samples every process in the application's
session/process group plus any descendant that detaches from it.

Reported memory is the maximum of *simultaneous* process-tree RSS, never the sum
of per-process peak maxima.  PSS is sampled separately when
``/proc/<pid>/smaps_rollup`` is available.

The candidate arm runs yt-dlp with exactly one JavaScript runtime for the
challenge solver, and the choice is explicit:

* ``quickjs`` -- quickjs-ng.  This is the historical mode that produced the
  archived reports; the old command still works unchanged.  The report gains
  additive fields, so a regenerated report is not literally byte-identical to
  the archived one.
* ``bun`` -- the experimental Bun line (1.4.3, the Rust rewrite).

``--resolver-engine`` selects the engine.  Only the selected engine is
published to the application: ``ARCHIVETUNE_QUICKJS`` for quickjs,
``ARCHIVETUNE_BUN`` for bun.  The other variable is removed from the child
environment so a stale system-wide runtime can never be picked up silently.

Only the benchmark script, the Markdown report and the generated JSON belong in
a commit.  Temporary checkouts, instrumentation and run directories are not
tracked.
"""

from __future__ import annotations

import argparse
import bisect
import hashlib
import json
import os
import platform
import re
import shutil
import signal
import statistics
import subprocess
import sys
import tempfile
import threading
import time
import traceback
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_BASELINE_COMMIT = "8e4bfb314"
DEFAULT_VIDEOS = ("jNQXAC9IVRw", "dQw4w9WgXcQ", "aqz-KE-bpKQ")
# The JavaScript runtime used by yt-dlp for the challenge solver.  "quickjs" is
# the historical mode; "bun" is the experimental Bun line.
RESOLVER_ENGINES = ("quickjs", "bun")
MAIN_CLASS = "moe.rukamori.archivetune.desktop.MainKt"
MAIN_KT_RELATIVE = Path(
    "desktopApp/src/main/kotlin/moe/rukamori/archivetune/desktop/Main.kt"
)
MARKER_PREFIX = "ARCHIVETUNE_APP_BENCHMARK\t"
INSTRUMENTATION_MARKER = "ARCHIVETUNE_APP_BENCHMARK"
PAGE_SIZE = os.sysconf("SC_PAGE_SIZE")
MIB = 1024.0 * 1024.0
JVM_FLAGS = [
    "-Xms32m",
    "-Xmx768m",
    "-XX:ReservedCodeCacheSize=128m",
    "-XX:ParallelGCThreads=4",
    "-XX:ConcGCThreads=2",
    "-XX:G1ConcRefinementThreads=4",
    "-Dfile.encoding=UTF-8",
    "-Dsun.java2d.dpiaware=true",
    "-Djava.awt.headless=false",
]
# These flags are not part of the packaged application, but make the offscreen
# benchmark deterministic.  They are identical for both versions.
SKIKO_ENV = {"SKIKO_RENDER_API": "SOFTWARE"}


# ---------------------------------------------------------------------------
# Temporary instrumentation
# ---------------------------------------------------------------------------

INSTRUMENTATION_IMPORTS = """\
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject"""

INSTRUMENTATION_BLOCK = """\
// TEMPORARY benchmark instrumentation; ARCHIVETUNE_APP_BENCHMARK; never committed.
if (System.getenv("ARCHIVETUNE_APP_BENCHMARK") == "1") {
    runtime.applicationScope.launch(CoroutineName("ArchiveTuneApplicationBenchmark")) {
        fun emit(type: String, vararg fields: Pair<String, Any?>) {
            val record = JSONObject()
            record.put("type", type)
            record.put("wall_ms", System.currentTimeMillis())
            fields.forEach { (key, value) ->
                if (value == null) record.put(key, JSONObject.NULL) else record.put(key, value)
            }
            println("ARCHIVETUNE_APP_BENCHMARK\\t$record")
            System.out.flush()
        }

        emit("benchmark-begin", "java_version" to System.getProperty("java.version"))
        val windowVisible = withTimeoutOrNull(120_000L) {
            while (java.awt.Window.getWindows().none { window -> window.isVisible && window.isShowing }) {
                delay(250L)
            }
            true
        } ?: false
        val visibleWindows = java.awt.Window.getWindows().count { it.isVisible && it.isShowing }
        emit("window-visible", "visible" to windowVisible, "window_count" to visibleWindows)
        if (!windowVisible) {
            emit("benchmark-abort", "reason" to "no-visible-window")
            withContext(NonCancellable) { runtime.close() }
            kotlin.system.exitProcess(3)
        }

        delay(10_000L)
        emit("idle-settled")

        val videos = listOf("jNQXAC9IVRw", "dQw4w9WgXcQ", "aqz-KE-bpKQ")
        val repeats = 2
        var successes = 0
        var attempts = 0
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
                        "expiry_seconds" to ((stream.expiresAtEpochMillis - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L),
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
        delay(10_000L)
        emit("benchmark-complete", "attempts" to attempts, "successes" to successes)
        emit("shutdown-begin")
        withContext(NonCancellable) { runtime.close() }
        emit("shutdown-complete")
        kotlin.system.exitProcess(0)
    }
}"""

IMPORTS_ANCHOR = "import kotlinx.coroutines.delay\nimport kotlinx.coroutines.launch"
BLOCK_ANCHOR = (
    "    val downloadServices = runtime.own(DesktopDownloadServices(runtime, musicServices.backend))\n"
    "    val updateCoordinator = DesktopUpdateCoordinator(runtime, musicServices.playback)"
)


def indent_block(block: str, spaces: int = 4) -> str:
    prefix = " " * spaces
    return "\n".join(prefix + line if line else "" for line in block.splitlines())


def inject_instrumentation(main_kt: Path) -> str:
    """Insert the benchmark hook once.  Returns injected/already-present."""
    text = main_kt.read_text(encoding="utf-8")
    if INSTRUMENTATION_MARKER in text:
        return "already-present"
    if IMPORTS_ANCHOR not in text:
        raise RuntimeError(f"Cannot find coroutine import anchor in {main_kt}")
    if text.count(BLOCK_ANCHOR) != 1:
        raise RuntimeError(f"Cannot find unique Main.kt insertion anchor in {main_kt}")
    text = text.replace(IMPORTS_ANCHOR, INSTRUMENTATION_IMPORTS, 1)
    block = indent_block(INSTRUMENTATION_BLOCK)
    replacement = (
        "    val downloadServices = runtime.own(DesktopDownloadServices(runtime, musicServices.backend))\n"
        + block
        + "\n    val updateCoordinator = DesktopUpdateCoordinator(runtime, musicServices.playback)"
    )
    text = text.replace(BLOCK_ANCHOR, replacement, 1)
    main_kt.write_text(text, encoding="utf-8")
    return "injected"


# ---------------------------------------------------------------------------
# Small helpers
# ---------------------------------------------------------------------------


def mib(value: float | int | None) -> float | None:
    if value is None:
        return None
    return round(float(value) / MIB, 2)


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    index = min(len(ordered) - 1, max(0, int(round(fraction * (len(ordered) - 1)))))
    return ordered[index]


def stat_summary(values: Iterable[float | int | None]) -> dict[str, float | int | None]:
    clean = [float(value) for value in values if value is not None]
    if not clean:
        return {"samples": 0, "min": None, "median": None, "p95": None, "max": None}
    return {
        "samples": len(clean),
        "min": round(min(clean), 2),
        "median": round(statistics.median(clean), 2),
        "p95": round(percentile(clean, 0.95) or 0.0, 2),
        "max": round(max(clean), 2),
    }


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run_command(
    command: list[str],
    *,
    cwd: Path,
    env: dict[str, str] | None = None,
    log_path: Path | None = None,
    timeout: float | None = None,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    log_handle = None
    try:
        if log_path is not None:
            log_path.parent.mkdir(parents=True, exist_ok=True)
            log_handle = log_path.open("w", encoding="utf-8")
        completed = subprocess.run(
            command,
            cwd=str(cwd),
            env=env,
            stdout=log_handle if log_handle is not None else subprocess.PIPE,
            stderr=subprocess.STDOUT if log_handle is not None else subprocess.PIPE,
            text=True,
            timeout=timeout,
            check=False,
        )
        if check and completed.returncode != 0:
            raise RuntimeError(
                f"Command failed ({completed.returncode}): {' '.join(command)}\n"
                f"{(completed.stdout or '')[-4000:]}"
            )
        return completed
    finally:
        if log_handle is not None:
            log_handle.close()


def git_output(directory: Path, *args: str) -> str:
    completed = subprocess.run(
        ["git", "-C", str(directory), *args],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed in {directory}: {completed.stderr.strip()}")
    return completed.stdout.strip()


def git_head(directory: Path) -> str:
    return git_output(directory, "rev-parse", "HEAD")


def git_commit_for(directory: Path, revision: str) -> str:
    return git_output(directory, "rev-parse", "--verify", f"{revision}^{{commit}}")


def system_memory_snapshot() -> dict[str, Any]:
    info: dict[str, Any] = {}
    try:
        for line in Path("/proc/meminfo").read_text(encoding="ascii").splitlines():
            key, _, rest = line.partition(":")
            if key in {"MemTotal", "MemAvailable", "MemFree", "SwapTotal", "SwapFree"}:
                info[key] = int(rest.strip().split()[0]) * 1024
    except OSError:
        pass
    try:
        info["loadavg"] = list(os.getloadavg())
    except (OSError, AttributeError):
        info["loadavg"] = None
    return {
        "mem_total_mib": mib(info.get("MemTotal")),
        "mem_available_mib": mib(info.get("MemAvailable")),
        "mem_free_mib": mib(info.get("MemFree")),
        "swap_total_mib": mib(info.get("SwapTotal")),
        "swap_free_mib": mib(info.get("SwapFree")),
        "loadavg": info.get("loadavg"),
    }


def java_version(java: Path) -> str:
    completed = subprocess.run(
        [str(java), "-version"], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False
    )
    return completed.stdout.splitlines()[0] if completed.stdout else "unknown"


def tool_version(executable: Path | None) -> str | None:
    if executable is None:
        return None
    completed = subprocess.run(
        [str(executable), "--version"],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=30,
        check=False,
    )
    text = (completed.stdout or "").strip()
    return text.splitlines()[0] if text else None


def xvfb_version(executable: Path) -> str | None:
    # A locally extracted distro package carries its version in .PKGINFO.
    for parent in executable.resolve().parents:
        package_info = parent / ".PKGINFO"
        if package_info.is_file():
            try:
                for line in package_info.read_text(encoding="utf-8", errors="replace").splitlines():
                    if line.startswith("pkgver"):
                        return line.split("=", 1)[1].strip()
            except OSError:
                pass
    for flag in ("-version", "-help"):
        completed = subprocess.run(
            [str(executable), flag],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=30,
            check=False,
        )
        text = (completed.stdout or "").strip()
        for line in text.splitlines():
            if "X.Org X Server" in line or "Xvfb" in line or line.startswith("use: X"):
                return line.strip()
    return None


def discover_resolver(
    repo_root: Path,
    explicit: str | None,
    env_name: str,
    binary: str,
    *,
    extra_candidates: list[Path] | None = None,
) -> Path | None:
    candidates: list[Path] = []
    if explicit:
        candidates.append(Path(explicit))
    env_value = os.environ.get(env_name)
    if env_value:
        candidates.append(Path(env_value))
    candidates.extend(
        [
            repo_root / ".tools" / "resolver" / binary,
            repo_root
            / "desktopApp/build/compose/binaries/main/app/ArchiveTune/app/resources/resolver"
            / binary,
        ]
    )
    # Developer-staged builds come before whatever happens to be on PATH, so a
    # pre-installed system runtime never silently replaces a verified binary.
    if extra_candidates:
        candidates.extend(extra_candidates)
    discovered = shutil.which(binary)
    if discovered:
        candidates.append(Path(discovered))
    for candidate in candidates:
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return candidate.resolve()
    return None


def staged_bun_candidates(repo_root: Path) -> list[Path]:
    """Bun builds staged by the developer tooling under ``build/tools/runtimes``."""
    root = repo_root / "build" / "tools" / "runtimes"
    if not root.is_dir():
        return []
    found: list[Path] = []
    for entry in sorted(root.glob("bun-*/bun-linux-*/bun")):
        if entry.is_file() and os.access(entry, os.X_OK):
            found.append(entry.resolve())
    return sorted(found, key=_bun_version_key, reverse=True)


def _bun_version_key(path: Path) -> tuple[int, ...]:
    match = re.search(r"bun-(\d+(?:\.\d+)*)", str(path))
    if not match:
        return ()
    return tuple(int(part) for part in match.group(1).split("."))


def discover_bun(repo_root: Path, explicit: str | None) -> Path | None:
    """Locate the Bun executable for the experimental engine."""
    return discover_resolver(
        repo_root,
        explicit,
        "ARCHIVETUNE_BUN",
        "bun",
        extra_candidates=staged_bun_candidates(repo_root),
    )


# ---------------------------------------------------------------------------
# Process-tree sampling
# ---------------------------------------------------------------------------


@dataclass
class ProcessInfo:
    pid: int
    comm: str
    ppid: int
    pgrp: int
    session: int
    starttime: int
    rss_bytes: int
    name: str = ""
    category: str = "other"
    pss_bytes: int | None = None
    cgroup: str = ""


@dataclass
class KnownProcess:
    pid: int
    starttime: int
    name: str
    category: str
    first_wall_ms: int
    last_wall_ms: int


@dataclass
class TreeSnapshot:
    wall_ms: int
    rss_bytes: int
    pss_bytes: int | None
    pss_missing: int
    app_rss_bytes: int
    child_rss_bytes: int
    process_count: int
    session_count: int
    pgrp_count: int
    registry_count: int
    reparented_count: int
    reparented_names: list[str]
    detached_from_app_group_count: int
    detached_from_app_group_names: list[str]
    cgroup_paths: list[str]
    processes: list[dict[str, Any]] = field(default_factory=list)


# A Bun release binary is called "bun"; a staged copy may keep a versioned
# file name such as "bun-1.4.3" or "bun-linux-x64".  The pattern deliberately
# requires a non-alphanumeric or numeric suffix so unrelated names such as
# "bundle" are never misclassified.
BUN_NAME_PATTERN = re.compile(r"^bun([0-9._-]|$)")


def is_bun_process(name: str, comm: str) -> bool:
    """True when the executable is a Bun runtime."""
    if comm.strip().lower() == "bun":
        return True
    return bool(BUN_NAME_PATTERN.match(name.strip().lower()))


def classify_process(name: str, comm: str, is_app: bool) -> str:
    lowered = f"{name} {comm}".lower()
    if is_app or name in {"java", "java.exe"}:
        return "jvm"
    if "yt-dlp" in lowered or "youtube-dl" in lowered:
        return "yt_dlp"
    if "qjs" in lowered or "quickjs" in lowered:
        return "quickjs"
    if is_bun_process(name, comm):
        return "bun"
    if "python" in lowered:
        return "python"
    if any(token in lowered for token in ("brave", "chromium", "chrome", "headless_shell", "msedge", "firefox")):
        return "browser"
    if any(token in lowered for token in ("node", "deno")):
        return "javascript"
    if any(token in lowered for token in ("vlc", "ffmpeg", "ffprobe")):
        return "media"
    return "other"


def read_process_table() -> dict[int, ProcessInfo]:
    table: dict[int, ProcessInfo] = {}
    proc_root = Path("/proc")
    for entry in proc_root.iterdir():
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
        comm = data[left + 1 : right]
        fields = data[right + 2 :].split()
        if len(fields) < 22:
            continue
        try:
            table[pid] = ProcessInfo(
                pid=pid,
                comm=comm,
                ppid=int(fields[1]),
                pgrp=int(fields[2]),
                session=int(fields[3]),
                starttime=int(fields[19]),
                rss_bytes=max(0, int(fields[21])) * PAGE_SIZE,
            )
        except (ValueError, IndexError):
            continue
    return table


def process_name(pid: int, fallback: str) -> str:
    try:
        target = os.readlink(f"/proc/{pid}/exe")
        if target.endswith(" (deleted)"):
            target = target[: -len(" (deleted)")]
        name = Path(target).name
        if name:
            return name
    except OSError:
        pass
    return fallback or "unknown"


def process_pss(pid: int) -> int | None:
    try:
        with open(f"/proc/{pid}/smaps_rollup", "r", encoding="ascii", errors="replace") as handle:
            for line in handle:
                if line.startswith("Pss:"):
                    return int(line.split()[1]) * 1024
    except (OSError, ValueError, IndexError):
        return None
    return None


def process_cgroup(pid: int) -> str:
    try:
        text = Path(f"/proc/{pid}/cgroup").read_text(encoding="ascii", errors="replace")
        return text.strip().replace("\n", "; ")
    except OSError:
        return ""


class ProcessTreeSampler:
    """Samples the app session/process group plus a persistent descendant registry."""

    def __init__(
        self,
        app_pid: int,
        *,
        sample_interval_ms: int,
        pss_interval_ms: int,
        start_wall_ms: int,
    ) -> None:
        self.app_pid = app_pid
        self.sample_interval = max(0.005, sample_interval_ms / 1000.0)
        self.pss_every = max(1, int(round(pss_interval_ms / max(1, sample_interval_ms))))
        self.start_wall_ms = start_wall_ms
        self.samples: list[TreeSnapshot] = []
        self.known: dict[tuple[int, int], KnownProcess] = {}
        self.lock = threading.RLock()
        self.stop_event = threading.Event()
        self.thread = threading.Thread(target=self._run, name="process-tree-sampler", daemon=True)
        self.error: str | None = None
        self._names: dict[tuple[int, int], str] = {}
        self._categories: dict[tuple[int, int], str] = {}

    def start(self) -> None:
        self.thread.start()

    def stop(self, timeout: float = 10.0) -> None:
        self.stop_event.set()
        self.thread.join(timeout=timeout)

    def _run(self) -> None:
        next_sample = time.monotonic()
        iteration = 0
        try:
            while not self.stop_event.is_set():
                now = time.monotonic()
                if now < next_sample:
                    self.stop_event.wait(min(next_sample - now, 0.05))
                    continue
                with self.lock:
                    snapshot = self._snapshot(with_pss=(iteration % self.pss_every == 0))
                    self.samples.append(snapshot)
                iteration += 1
                next_sample += self.sample_interval
                if next_sample < now - self.sample_interval * 4:
                    next_sample = now + self.sample_interval
        except Exception:  # pragma: no cover - diagnostic path
            self.error = traceback.format_exc()

    def snapshot_now(self, *, with_pss: bool = True) -> TreeSnapshot:
        with self.lock:
            snapshot = self._snapshot(with_pss=with_pss)
            self.samples.append(snapshot)
            return snapshot

    def _snapshot(self, *, with_pss: bool) -> TreeSnapshot:
        wall_ms = int(time.time() * 1000)
        table = read_process_table()

        tracked: set[int] = set()
        pgrp_members: set[int] = set()
        session_members: set[int] = set()
        if self.app_pid in table:
            tracked.add(self.app_pid)
        for pid, info in table.items():
            if info.pgrp == self.app_pid:
                pgrp_members.add(pid)
                tracked.add(pid)
            if info.session == self.app_pid:
                session_members.add(pid)
                tracked.add(pid)

        # Persistent registry catches children that called setsid()/double-forked
        # and were reparented.  (pid, starttime) prevents PID-reuse confusion.
        registry_members: set[int] = set()
        for (pid, starttime), known in self.known.items():
            info = table.get(pid)
            if info is not None and info.starttime == starttime:
                if pid not in tracked:
                    registry_members.add(pid)
                tracked.add(pid)
                known.last_wall_ms = wall_ms

        # Include new children of already-observed detached/reparented processes,
        # as well as children of the app's original session/process group.
        changed = True
        while changed:
            changed = False
            for pid, info in table.items():
                if pid not in tracked and info.ppid in tracked:
                    tracked.add(pid)
                    changed = True

        tracked.discard(0)
        tracked = {pid for pid in tracked if pid in table}
        # A dead app process must not keep the whole tree alive in the registry.
        if self.app_pid not in table:
            tracked = {pid for pid in tracked if pid != self.app_pid}

        reparented_names: list[str] = []
        detached_from_app_group_names: list[str] = []
        pss_total = 0
        pss_missing = 0
        pss_available = False
        cgroup_paths: set[str] = set()
        processes: list[dict[str, Any]] = []
        total_rss = 0
        app_rss = 0

        for pid in sorted(tracked):
            info = table.get(pid)
            if info is None:
                continue
            key = (pid, info.starttime)
            name = self._names.get(key)
            if name is None:
                name = process_name(pid, info.comm)
                self._names[key] = name
            category = self._categories.get(key)
            if category is None:
                category = classify_process(name, info.comm, pid == self.app_pid)
                self._categories[key] = category
            info.name = name
            info.category = category
            info.cgroup = process_cgroup(pid)
            if info.cgroup:
                cgroup_paths.add(info.cgroup)
            if with_pss:
                info.pss_bytes = process_pss(pid)
                if info.pss_bytes is None:
                    pss_missing += 1
                else:
                    pss_available = True
                    pss_total += info.pss_bytes
            else:
                pss_missing += 1
            total_rss += info.rss_bytes
            if pid == self.app_pid:
                app_rss = info.rss_bytes
            if pid != self.app_pid and info.ppid not in tracked:
                reparented_names.append(name)
            detached_from_app_group = (
                pid != self.app_pid
                and info.session != self.app_pid
                and info.pgrp != self.app_pid
            )
            if detached_from_app_group:
                detached_from_app_group_names.append(name)
            existing = self.known.get(key)
            if existing is None:
                self.known[key] = KnownProcess(
                    pid=pid,
                    starttime=info.starttime,
                    name=name,
                    category=category,
                    first_wall_ms=wall_ms,
                    last_wall_ms=wall_ms,
                )
            else:
                existing.name = name
                existing.category = category
            processes.append(
                {
                    "pid": pid,
                    "name": name,
                    "category": category,
                    "rss_bytes": info.rss_bytes,
                    "pss_bytes": info.pss_bytes,
                    "session_id": info.session,
                    "pgrp_id": info.pgrp,
                    "detached_from_app_group": detached_from_app_group,
                    "is_app": pid == self.app_pid,
                }
            )

        return TreeSnapshot(
            wall_ms=wall_ms,
            rss_bytes=total_rss,
            pss_bytes=pss_total if pss_available else None,
            pss_missing=pss_missing,
            app_rss_bytes=app_rss,
            child_rss_bytes=max(0, total_rss - app_rss),
            process_count=len(processes),
            session_count=len(session_members.intersection(tracked)),
            pgrp_count=len(pgrp_members.intersection(tracked)),
            registry_count=len(registry_members.intersection(tracked)),
            reparented_count=len(reparented_names),
            reparented_names=sorted(set(reparented_names)),
            detached_from_app_group_count=len(detached_from_app_group_names),
            detached_from_app_group_names=sorted(set(detached_from_app_group_names)),
            cgroup_paths=sorted(cgroup_paths),
            processes=processes,
        )


# ---------------------------------------------------------------------------
# Phase analysis
# ---------------------------------------------------------------------------


def phase_window(
    markers: list[dict[str, Any]], name: str, launch_wall_ms: int | None = None
) -> tuple[int, int] | None:
    by_type: dict[str, list[dict[str, Any]]] = {}
    for marker in markers:
        by_type.setdefault(str(marker.get("type")), []).append(marker)

    def marker_time(marker_type: str) -> int | None:
        entries = by_type.get(marker_type)
        if not entries:
            return None
        return int(entries[0].get("wall_ms", 0))

    visible = marker_time("window-visible")
    begin = marker_time("extraction-begin")
    end = marker_time("extraction-end")
    complete = marker_time("benchmark-complete")
    shutdown = marker_time("shutdown-begin")

    if name == "startup":
        if visible is None:
            return None
        start = launch_wall_ms if launch_wall_ms is not None else (
            int(markers[0].get("wall_ms", visible)) if markers else visible
        )
        return start, visible
    if name == "idle":
        if visible is None or begin is None:
            return None
        return visible + 2_000, begin
    if name == "extraction":
        if begin is None or end is None:
            return None
        return begin, end
    if name == "post_idle":
        if end is None or complete is None:
            return None
        return end + 2_000, complete
    if name == "shutdown":
        if shutdown is None:
            return None
        return shutdown, int(markers[-1].get("wall_ms", shutdown)) if markers else shutdown
    return None


def category_aggregate(samples: list[TreeSnapshot]) -> dict[str, dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for snapshot in samples:
        per_category: dict[str, list[dict[str, Any]]] = {}
        for process in snapshot.processes:
            per_category.setdefault(process["category"], []).append(process)
        for category, processes in per_category.items():
            entry = result.setdefault(
                category,
                {
                    "counts": [],
                    "rss": [],
                    "pss": [],
                    "names": set(),
                    "max_rss_bytes": 0,
                    "max_pss_bytes": 0,
                    "pss_seen": False,
                },
            )
            rss = sum(int(process["rss_bytes"]) for process in processes)
            pss_values = [int(process["pss_bytes"]) for process in processes if process["pss_bytes"] is not None]
            entry["counts"].append(len(processes))
            entry["rss"].append(rss)
            entry["pss"].append(sum(pss_values) if pss_values else None)
            entry["max_rss_bytes"] = max(entry["max_rss_bytes"], rss)
            if pss_values:
                entry["pss_seen"] = True
                entry["max_pss_bytes"] = max(entry["max_pss_bytes"], sum(pss_values))
            for process in processes:
                entry["names"].add(str(process["name"]))

    output: dict[str, dict[str, Any]] = {}
    for category, entry in sorted(result.items()):
        counts = entry["counts"]
        output[category] = {
            "process_names": sorted(entry["names"]),
            "max_process_count": max(counts) if counts else 0,
            "median_process_count": round(statistics.median(counts), 2) if counts else 0,
            "max_rss_mib": mib(entry["max_rss_bytes"]),
            "median_rss_mib": round(statistics.median(mib(value) for value in entry["rss"]), 2)
            if entry["rss"]
            else None,
            "max_pss_mib": mib(entry["max_pss_bytes"]) if entry["pss_seen"] else None,
        }
    return output


def phase_stats(samples: list[TreeSnapshot], window: tuple[int, int] | None) -> dict[str, Any] | None:
    if window is None:
        return None
    start_ms, end_ms = window
    selected = [sample for sample in samples if start_ms <= sample.wall_ms <= end_ms]
    if not selected:
        return None
    peak = max(selected, key=lambda sample: sample.rss_bytes)
    pss_candidates = [sample for sample in selected if sample.pss_bytes is not None]
    peak_pss = max(pss_candidates, key=lambda sample: int(sample.pss_bytes or 0)) if pss_candidates else None

    def process_rows(snapshot: TreeSnapshot) -> list[dict[str, Any]]:
        return [
            {
                "pid": process["pid"],
                "name": process["name"],
                "category": process["category"],
                "rss_mib": mib(process["rss_bytes"]),
                "pss_mib": mib(process["pss_bytes"]),
                "session_id": process.get("session_id"),
                "pgrp_id": process.get("pgrp_id"),
                "detached_from_app_group": process.get("detached_from_app_group", False),
                "is_app": process["is_app"],
            }
            for process in sorted(snapshot.processes, key=lambda item: int(item["rss_bytes"]), reverse=True)
        ]

    peak_processes = process_rows(peak)
    return {
        "start_wall_ms": start_ms,
        "end_wall_ms": end_ms,
        "duration_ms": max(0, end_ms - start_ms),
        "samples": len(selected),
        "rss_mib": stat_summary(mib(sample.rss_bytes) for sample in selected),
        "pss_mib": stat_summary(mib(sample.pss_bytes) for sample in selected),
        "app_rss_mib": stat_summary(mib(sample.app_rss_bytes) for sample in selected),
        "children_rss_mib": stat_summary(mib(sample.child_rss_bytes) for sample in selected),
        "process_count": stat_summary(sample.process_count for sample in selected),
        "session_process_count": stat_summary(sample.session_count for sample in selected),
        "process_group_count": stat_summary(sample.pgrp_count for sample in selected),
        "registry_count": stat_summary(sample.registry_count for sample in selected),
        "reparented_count": stat_summary(sample.reparented_count for sample in selected),
        "detached_from_app_group_count": stat_summary(
            sample.detached_from_app_group_count for sample in selected
        ),
        "detached_from_app_group_names": sorted(
            {
                name
                for sample in selected
                for name in sample.detached_from_app_group_names
            }
        ),
        "category": category_aggregate(selected),
        "peak_simultaneous_sample": {
            "wall_ms": peak.wall_ms,
            "rss_mib": mib(peak.rss_bytes),
            "pss_mib": mib(peak.pss_bytes),
            "app_rss_mib": mib(peak.app_rss_bytes),
            "children_rss_mib": mib(peak.child_rss_bytes),
            "process_count": peak.process_count,
            "detached_from_app_group_count": peak.detached_from_app_group_count,
            "detached_from_app_group_names": peak.detached_from_app_group_names,
            "sum_process_rss_mib": mib(sum(int(process["rss_bytes"]) for process in peak.processes)),
            "cgroup_paths": peak.cgroup_paths,
            "processes": peak_processes,
        },
        "peak_simultaneous_pss_sample": (
            {
                "wall_ms": peak_pss.wall_ms,
                "rss_mib": mib(peak_pss.rss_bytes),
                "pss_mib": mib(peak_pss.pss_bytes),
                "app_rss_mib": mib(peak_pss.app_rss_bytes),
                "children_rss_mib": mib(peak_pss.child_rss_bytes),
                "process_count": peak_pss.process_count,
                "detached_from_app_group_count": peak_pss.detached_from_app_group_count,
                "detached_from_app_group_names": peak_pss.detached_from_app_group_names,
                "sum_process_rss_mib": mib(
                    sum(int(process["rss_bytes"]) for process in peak_pss.processes)
                ),
                "cgroup_paths": peak_pss.cgroup_paths,
                "processes": process_rows(peak_pss),
            }
            if peak_pss is not None
            else None
        ),
    }


def process_inventory(samples: list[TreeSnapshot]) -> dict[str, Any]:
    categories: dict[str, dict[str, Any]] = {}
    all_names: set[str] = set()
    reparented_names: set[str] = set()
    detached_from_app_group_names: set[str] = set()
    max_process_count = 0
    max_detached_from_app_group_count = 0
    for snapshot in samples:
        max_process_count = max(max_process_count, snapshot.process_count)
        reparented_names.update(snapshot.reparented_names)
        detached_from_app_group_names.update(snapshot.detached_from_app_group_names)
        max_detached_from_app_group_count = max(
            max_detached_from_app_group_count,
            snapshot.detached_from_app_group_count,
        )
        # Simultaneous per-category totals for this one instant.  A category is
        # summed across the processes that exist at the same sampled moment, so
        # a transient helper that overlaps another helper is fully accounted.
        simultaneous_rss: dict[str, int] = {}
        simultaneous_pss: dict[str, int] = {}
        for process in snapshot.processes:
            category = str(process["category"])
            simultaneous_rss[category] = simultaneous_rss.get(category, 0) + int(process["rss_bytes"])
            pss_value = process["pss_bytes"]
            if pss_value is not None:
                simultaneous_pss[category] = simultaneous_pss.get(category, 0) + int(pss_value)
        for process in snapshot.processes:
            name = str(process["name"])
            category = str(process["category"])
            all_names.add(name)
            entry = categories.setdefault(
                category,
                {
                    "names": set(),
                    "max_rss_bytes": 0,
                    "max_pss_bytes": 0,
                    "pss_seen": False,
                    "max_count": 0,
                    "max_simultaneous_rss_bytes": 0,
                    "max_simultaneous_pss_bytes": 0,
                    "simultaneous_pss_seen": False,
                },
            )
            entry["names"].add(name)
            entry["max_rss_bytes"] = max(entry["max_rss_bytes"], int(process["rss_bytes"]))
            if process["pss_bytes"] is not None:
                entry["pss_seen"] = True
                entry["max_pss_bytes"] = max(entry["max_pss_bytes"], int(process["pss_bytes"]))
        for category, rss_total in simultaneous_rss.items():
            entry = categories[category]
            entry["max_simultaneous_rss_bytes"] = max(
                entry["max_simultaneous_rss_bytes"], rss_total
            )
            entry["max_count"] = max(
                entry["max_count"],
                sum(1 for process in snapshot.processes if process["category"] == category),
            )
        for category, pss_total in simultaneous_pss.items():
            entry = categories[category]
            entry["simultaneous_pss_seen"] = True
            entry["max_simultaneous_pss_bytes"] = max(
                entry["max_simultaneous_pss_bytes"], pss_total
            )
    category_output: dict[str, Any] = {}
    for category, entry in sorted(categories.items()):
        category_output[category] = {
            "names": sorted(entry["names"]),
            # Largest single process of the category (a per-process maximum).
            "max_observed_rss_mib": mib(entry["max_rss_bytes"]),
            "max_observed_pss_mib": mib(entry["max_pss_bytes"]) if entry["pss_seen"] else None,
            # Largest sum of the category's processes at one sampled instant.
            "max_simultaneous_rss_mib": mib(entry["max_simultaneous_rss_bytes"]),
            "max_simultaneous_pss_mib": (
                mib(entry["max_simultaneous_pss_bytes"]) if entry["simultaneous_pss_seen"] else None
            ),
            "max_simultaneous_process_count": entry["max_count"],
        }
    return {
        "all_process_names": sorted(all_names),
        "categories": category_output,
        "max_simultaneous_process_count": max_process_count,
        "reparented_process_names": sorted(reparented_names),
        "detached_from_app_group_names": sorted(detached_from_app_group_names),
        "max_detached_from_app_group_count": max_detached_from_app_group_count,
        "browser_processes_observed": sorted(categories.get("browser", {}).get("names", set())),
        "python_processes_observed": sorted(categories.get("python", {}).get("names", set())),
        "quickjs_processes_observed": sorted(categories.get("quickjs", {}).get("names", set())),
        "bun_processes_observed": sorted(categories.get("bun", {}).get("names", set())),
        "javascript_processes_observed": sorted(categories.get("javascript", {}).get("names", set())),
        "ytdlp_processes_observed": sorted(categories.get("yt_dlp", {}).get("names", set())),
        "media_processes_observed": sorted(categories.get("media", {}).get("names", set())),
    }


# ---------------------------------------------------------------------------
# Xvfb
# ---------------------------------------------------------------------------


def find_xvfb(explicit: str | None) -> Path:
    if explicit:
        path = Path(explicit)
        if not path.is_file() or not os.access(path, os.X_OK):
            raise RuntimeError(f"Xvfb is not executable: {path}")
        return path.resolve()
    discovered = shutil.which("Xvfb")
    if discovered:
        return Path(discovered)
    raise RuntimeError(
        "Xvfb was not found. Install it, or extract a distro package locally and pass --xvfb PATH. "
        "The benchmark deliberately does not use the user's live desktop display."
    )


def start_xvfb(xvfb: Path, log_path: Path, start_display: int = 90) -> tuple[subprocess.Popen[Any], int]:
    for display in range(start_display, start_display + 20):
        socket = Path(f"/tmp/.X11-unix/X{display}")
        if socket.exists():
            continue
        log_handle = log_path.open("w", encoding="utf-8")
        process = subprocess.Popen(
            [str(xvfb), f":{display}", "-screen", "0", "1440x900x24", "-nolisten", "tcp", "-ac"],
            stdout=log_handle,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        log_handle.close()
        deadline = time.monotonic() + 8.0
        while time.monotonic() < deadline:
            if process.poll() is not None:
                break
            if socket.exists():
                return process, display
            time.sleep(0.05)
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=3)
        log_handle.close()
    raise RuntimeError("Could not start Xvfb on a free display")


def stop_xvfb(process: subprocess.Popen[Any] | None) -> int | None:
    if process is None:
        return None
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
    return process.returncode


# ---------------------------------------------------------------------------
# Application run
# ---------------------------------------------------------------------------


class LineCollector(threading.Thread):
    def __init__(self, stream: Any, raw_log: Path) -> None:
        super().__init__(name="application-stdout", daemon=True)
        self.stream = stream
        self.raw_log = raw_log
        self.markers: list[dict[str, Any]] = []
        self.log_lines: list[str] = []
        self.lock = threading.Lock()

    def run(self) -> None:
        with self.raw_log.open("w", encoding="utf-8", errors="replace") as handle:
            for line in self.stream:
                handle.write(line)
                handle.flush()
                with self.lock:
                    self.log_lines.append(line)
                    if line.startswith(MARKER_PREFIX):
                        payload = line[len(MARKER_PREFIX) :].strip()
                        try:
                            self.markers.append(json.loads(payload))
                        except ValueError:
                            pass

    def marker_types(self) -> dict[str, list[dict[str, Any]]]:
        with self.lock:
            grouped: dict[str, list[dict[str, Any]]] = {}
            for marker in self.markers:
                grouped.setdefault(str(marker.get("type")), []).append(marker)
            return grouped


def log_evidence(log_lines: list[str]) -> dict[str, Any]:
    text = "".join(log_lines)

    def failed_request(key: str) -> bool:
        return any(
            f"key={key}" in line and "result=failure" in line
            for line in log_lines
        )

    return {
        "app_log_bytes": len(text.encode("utf-8", errors="replace")),
        "libvlc_prewarm_failed": "libVLC prewarm failed" in text,
        "vlc_runtime_not_found": "VLC runtime not found" in text,
        "playback_engine_available": "libVLC prewarm failed" not in text
        and "VLC runtime not found" not in text,
        "anonymous_home_request_failed": failed_request("home"),
        "anonymous_library_playlists_request_failed": failed_request("library-playlists"),
        "anonymous_podcast_library_request_failed": failed_request("podcast-library-sync"),
        "anonymous_request_400": "invalid: 400" in text,
        "out_of_memory_error": "OutOfMemoryError" in text,
        "severe_log_entries": text.count("SEVERE"),
        "uncaught_exception": "Uncaught exception" in text,
    }


def resolver_engine(resolver: dict[str, Any] | None) -> str:
    """Return the JavaScript runtime engine of a resolver description.

    Older callers build the mapping without an explicit engine; those always
    described the historical quickjs mode.
    """
    if not resolver:
        return "quickjs"
    engine = resolver.get("engine")
    if engine in RESOLVER_ENGINES:
        return str(engine)
    if "bun" in resolver:
        return "bun"
    return "quickjs"


def resolver_system_properties(resolver: dict[str, Any] | None) -> list[str]:
    """JVM flags that pin the resolver binary for the selected engine.

    The quickjs mode keeps the historical command line untouched: the runtime
    already discovers ``ARCHIVETUNE_QUICKJS`` from the environment.
    """
    if not resolver or resolver_engine(resolver) != "bun":
        return []
    return [f"-Darchivetune.bun={resolver['bun']}"]


def build_environment(
    *,
    display: int,
    run_dir: Path,
    home: Path,
    tmp: Path,
    resolver: dict[str, Any] | None,
) -> dict[str, str]:
    environment = os.environ.copy()
    # Do not let a user's JAVA_TOOL_OPTIONS/_JAVA_OPTIONS change one side only.
    for key in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "XAUTHORITY"):
        environment.pop(key, None)
    # A runtime-specific variable from the developer's shell would change the
    # measured JavaScript runtime, so it never reaches the benchmarked app.
    for key in ("BUN_OPTIONS", "DO_NOT_TRACK", "NODE_OPTIONS"):
        environment.pop(key, None)
    environment.update(SKIKO_ENV)
    environment.update(
        {
            "DISPLAY": f":{display}",
            "HOME": str(home),
            "ARCHIVETUNE_HOME": str(home),
            "ARCHIVETUNE_APP_BENCHMARK": "1",
            "XDG_CONFIG_HOME": str(home / "config"),
            "XDG_DATA_HOME": str(home / "data"),
            "XDG_CACHE_HOME": str(home / "cache"),
            "XDG_STATE_HOME": str(home / "state"),
            "TMPDIR": str(tmp),
            "TMP": str(tmp),
            "TEMP": str(tmp),
            "PYTHONIOENCODING": "utf-8",
        }
    )
    # Exactly one JavaScript runtime is published.  The other variable is
    # removed so a stale system-wide quickjs/bun can never be discovered
    # instead of the verified binary.
    environment.pop("ARCHIVETUNE_QUICKJS", None)
    environment.pop("ARCHIVETUNE_BUN", None)
    if resolver:
        environment["ARCHIVETUNE_YTDLP"] = str(resolver["yt_dlp"])
        if resolver_engine(resolver) == "bun":
            environment["ARCHIVETUNE_BUN"] = str(resolver["bun"])
        else:
            environment["ARCHIVETUNE_QUICKJS"] = str(resolver["quickjs"])
    else:
        environment.pop("ARCHIVETUNE_YTDLP", None)
    return environment


def resolver_summary(resolver: dict[str, Any] | None) -> dict[str, Any] | None:
    if not resolver:
        return None
    engine = resolver_engine(resolver)
    summary: dict[str, Any] = {
        "engine": engine,
        "yt_dlp_path": str(resolver["yt_dlp"]),
        "yt_dlp_version": resolver.get("yt_dlp_version"),
    }
    if engine == "bun":
        summary["bun_path"] = str(resolver["bun"])
        summary["bun_version"] = resolver.get("bun_version")
        summary["selection"] = "ARCHIVETUNE_YTDLP / ARCHIVETUNE_BUN environment variables"
        summary["environment_variables"] = ["ARCHIVETUNE_YTDLP", "ARCHIVETUNE_BUN"]
    else:
        summary["quickjs_path"] = str(resolver["quickjs"])
        summary["quickjs_version"] = resolver.get("quickjs_version")
        summary["selection"] = "ARCHIVETUNE_YTDLP / ARCHIVETUNE_QUICKJS environment variables"
        summary["environment_variables"] = ["ARCHIVETUNE_YTDLP", "ARCHIVETUNE_QUICKJS"]
    return summary


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
    resolver: dict[str, Any] | None,
) -> dict[str, Any]:
    run_dir.mkdir(parents=True, exist_ok=True)
    home = run_dir / "home"
    tmp = run_dir / "tmp"
    for directory in (home, tmp, home / "config", home / "data", home / "cache", home / "state"):
        directory.mkdir(parents=True, exist_ok=True)

    classpath = classpath_file.read_text(encoding="utf-8").strip()
    classpath_entries = len(classpath.split(os.pathsep)) if classpath else 0
    if not classpath:
        raise RuntimeError(f"Empty classpath: {classpath_file}")

    xvfb_log = run_dir / "xvfb.log"
    xvfb, display = start_xvfb(xvfb_path, xvfb_log)
    environment = build_environment(
        display=display,
        run_dir=run_dir,
        home=home,
        tmp=tmp,
        resolver=resolver,
    )

    command = [
        str(java),
        *JVM_FLAGS,
        f"-Djava.io.tmpdir={tmp}",
        "-Dskiko.renderApi=SOFTWARE",
        *resolver_system_properties(resolver),
        "-cp",
        classpath,
        MAIN_CLASS,
    ]
    raw_log = run_dir / "application.log"
    start_wall_ms = int(time.time() * 1000)
    system_before = system_memory_snapshot()
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
    collector = LineCollector(process.stdout, raw_log)
    collector.start()
    sampler = ProcessTreeSampler(
        process.pid,
        sample_interval_ms=sample_interval_ms,
        pss_interval_ms=pss_interval_ms,
        start_wall_ms=start_wall_ms,
    )
    sampler.start()

    timed_out = False
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
        # Keep sampling briefly so detached/reparented children are visible.
        cleanup_samples: list[TreeSnapshot] = []
        cleanup_delays = (0.0, 0.25, 1.0)
        for delay in cleanup_delays:
            if delay:
                time.sleep(delay)
            try:
                cleanup_samples.append(sampler.snapshot_now(with_pss=True))
            except Exception:
                cleanup_samples.append(
                    TreeSnapshot(
                        wall_ms=int(time.time() * 1000),
                        rss_bytes=0,
                        pss_bytes=None,
                        pss_missing=0,
                        app_rss_bytes=0,
                        child_rss_bytes=0,
                        process_count=0,
                        session_count=0,
                        pgrp_count=0,
                        registry_count=0,
                        reparented_count=0,
                        reparented_names=[],
                        detached_from_app_group_count=0,
                        detached_from_app_group_names=[],
                        cgroup_paths=[],
                    )
                )
        time.sleep(0.5)
        sampler.stop(timeout=10)
        collector.join(timeout=10)
        try:
            process.stdout.close()
        except OSError:
            pass

        killpg_sent = False
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
        xvfb_exit = stop_xvfb(xvfb)

    system_after = system_memory_snapshot()
    markers = list(collector.markers)
    with collector.lock:
        log_lines = list(collector.log_lines)
    resolver_records = [marker for marker in markers if marker.get("type") == "resolve"]

    phase_windows = {
        name: phase_window(markers, name, start_wall_ms)
        for name in ("startup", "idle", "extraction", "post_idle", "shutdown")
    }
    phases = {
        name: phase_stats(sampler.samples, window)
        for name, window in phase_windows.items()
    }

    cleanup = []
    for index, snapshot in enumerate(cleanup_samples):
        cleanup.append(
            {
                "index": index,
                "wall_ms": snapshot.wall_ms,
                "process_count": snapshot.process_count,
                "rss_mib": mib(snapshot.rss_bytes),
                "process_names": sorted({str(item["name"]) for item in snapshot.processes}),
            }
        )

    evidence = log_evidence(log_lines)
    succeeded = sum(1 for record in resolver_records if record.get("success") is True)
    attempts = len(resolver_records)
    latencies = [int(record["elapsed_ms"]) for record in resolver_records if "elapsed_ms" in record]
    latency_summary = {
        "min_ms": min(latencies) if latencies else None,
        "median_ms": round(statistics.median(latencies), 1) if latencies else None,
        "max_ms": max(latencies) if latencies else None,
    }

    return {
        "version": version,
        "backend": backend,
        "commit": commit,
        "checkout": str(checkout),
        "main_kt_sha256": sha256_file(checkout / MAIN_KT_RELATIVE),
        "classpath_entries": classpath_entries,
        "run_dir": str(run_dir),
        "resolver": resolver_summary(resolver),
        "environment": {
            "display": f":{display}",
            "offscreen_xvfb": True,
            "xvfb_screen": "1440x900x24",
            "skiko_render_api": "SOFTWARE",
            "home_override": str(home),
            "archivetune_home_override": str(home),
            "tmp_override": str(tmp),
            "resolver_env_present": bool(resolver),
        },
        "launch_wall_ms": start_wall_ms,
        "exit_wall_ms": exit_wall_ms,
        "duration_ms": exit_wall_ms - start_wall_ms,
        "app_exit_code": process.returncode,
        "harness_timeout": timed_out,
        "sampler_error": sampler.error,
        "markers": markers,
        "phase_windows": {name: (list(window) if window else None) for name, window in phase_windows.items()},
        "phases": phases,
        "resolver_records": resolver_records,
        "resolution": {
            "attempts": attempts,
            "successes": succeeded,
            "failures": attempts - succeeded,
            **latency_summary,
        },
        "process_inventory": process_inventory(sampler.samples),
        "sampling": {
            "sample_interval_ms": sample_interval_ms,
            "pss_interval_ms": pss_interval_ms,
            "samples": len(sampler.samples),
            "rss_method": "maximum sampled sum of simultaneous process-tree RSS",
            "pss_method": "sum of per-process PSS at the same sampled instant; skipped when smaps_rollup is unavailable",
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
        },
        "log_evidence": evidence,
        "system_before": system_before,
        "system_after": system_after,
    }


# ---------------------------------------------------------------------------
# Checkout preparation / build
# ---------------------------------------------------------------------------


def ensure_checkout(
    *,
    repo_root: Path,
    revision: str,
    destination: Path,
    existing_dir: str | None,
    keep: bool,
) -> tuple[Path, bool]:
    if existing_dir:
        checkout = Path(existing_dir).resolve()
        head = git_head(checkout)
        expected = git_commit_for(repo_root, revision)
        if head != expected:
            raise RuntimeError(f"{checkout} is at {head}, expected {expected} ({revision})")
        return checkout, False

    destination = destination.resolve()
    if destination.exists():
        try:
            git_output(repo_root, "worktree", "remove", "--force", str(destination))
        except RuntimeError:
            shutil.rmtree(destination, ignore_errors=True)
    destination.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["git", "-C", str(repo_root), "worktree", "add", "--detach", str(destination), revision],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    subprocess.run(
        [
            "git",
            "-C",
            str(destination),
            "-c",
            "protocol.file.allow=always",
            "submodule",
            "update",
            "--init",
            "--recursive",
        ],
        check=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    return destination, True


def ensure_local_properties(checkout: Path) -> None:
    local_properties = checkout / "local.properties"
    if local_properties.exists():
        return
    sdk = (
        os.environ.get("ANDROID_HOME")
        or os.environ.get("ANDROID_SDK_ROOT")
        or "/opt/android-sdk"
    )
    if Path(sdk).is_dir():
        local_properties.write_text(f"sdk.dir={sdk}\n", encoding="utf-8")


def write_gradle_init_script(path: Path) -> None:
    path.write_text(
        """\
gradle.projectsEvaluated {
    def app = gradle.rootProject.findProject(':desktopApp')
    if (app != null) {
        app.tasks.register('exportBenchmarkRuntimeClasspath') {
            group = 'benchmark'
            description = 'Writes the desktopApp main runtime classpath for the external application benchmark.'
            dependsOn app.tasks.named('classes')
            doLast {
                def sourceSets = app.extensions.getByType(org.gradle.api.tasks.SourceSetContainer)
                def classpath = sourceSets.getByName('main').runtimeClasspath.files.collect { it.absolutePath }
                def output = new File(app.layout.buildDirectory.get().asFile, 'benchmark-runtime-classpath.txt')
                output.parentFile.mkdirs()
                output.text = classpath.join(File.pathSeparator) + '\\n'
                println "Wrote benchmark runtime classpath with ${classpath.size()} entries to ${output}"
            }
        }
    }
}
""",
        encoding="utf-8",
    )


def build_checkout(
    *,
    checkout: Path,
    work_root: Path,
    java_home: Path,
    skip_build: bool,
    label: str,
) -> tuple[Path, dict[str, Any]]:
    main_kt = checkout / MAIN_KT_RELATIVE
    injection = inject_instrumentation(main_kt)
    ensure_local_properties(checkout)
    classpath_file = checkout / "desktopApp/build/benchmark-runtime-classpath.txt"
    init_script = work_root / "export-runtime-classpath.init.gradle"
    write_gradle_init_script(init_script)
    build_log = work_root / f"build-{label}.log"
    if skip_build:
        if not classpath_file.exists():
            raise RuntimeError(
                f"--skip-build was requested but {classpath_file} does not exist; build once without it."
            )
        build_status = "skipped"
    else:
        environment = os.environ.copy()
        environment["JAVA_HOME"] = str(java_home)
        environment["PATH"] = f"{java_home / 'bin'}{os.pathsep}{environment.get('PATH', '')}"
        completed = run_command(
            [
                str(checkout / "gradlew"),
                "-I",
                str(init_script),
                ":desktopApp:exportBenchmarkRuntimeClasspath",
                "--console=plain",
            ],
            cwd=checkout,
            env=environment,
            log_path=build_log,
            timeout=3_600,
            check=False,
        )
        if completed.returncode != 0:
            tail = build_log.read_text(encoding="utf-8", errors="replace")[-5000:]
            raise RuntimeError(f"Gradle build failed for {label}:\n{tail}")
        build_status = "built"
    return classpath_file, {
        "injection": injection,
        "build_status": build_status,
        "build_log": str(build_log) if build_log.exists() else None,
        "main_kt_sha256": sha256_file(main_kt),
    }


# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------


def comparison_summary(baseline: dict[str, Any], candidate: dict[str, Any]) -> dict[str, Any]:
    def phase_rss(result: dict[str, Any], phase: str, key: str = "max") -> float | None:
        phase_value = (result.get("phases") or {}).get(phase) or {}
        stats = phase_value.get("rss_mib") or {}
        return stats.get(key)

    def phase_pss(result: dict[str, Any], phase: str, key: str = "max") -> float | None:
        phase_value = (result.get("phases") or {}).get(phase) or {}
        stats = phase_value.get("pss_mib") or {}
        return stats.get(key)

    def delta(candidate_value: float | None, baseline_value: float | None) -> float | None:
        if candidate_value is None or baseline_value is None:
            return None
        return round(candidate_value - baseline_value, 2)

    baseline_idle = phase_rss(baseline, "idle")
    candidate_idle = phase_rss(candidate, "idle")
    baseline_peak = phase_rss(baseline, "extraction")
    candidate_peak = phase_rss(candidate, "extraction")
    baseline_post = phase_rss(baseline, "post_idle")
    candidate_post = phase_rss(candidate, "post_idle")
    return {
        "rss_mib": {
            "baseline_idle_max": baseline_idle,
            "candidate_idle_max": candidate_idle,
            "idle_delta": delta(candidate_idle, baseline_idle),
            "baseline_idle_pss_max": phase_pss(baseline, "idle"),
            "candidate_idle_pss_max": phase_pss(candidate, "idle"),
            "baseline_extraction_peak": baseline_peak,
            "candidate_extraction_peak": candidate_peak,
            "extraction_peak_delta": delta(candidate_peak, baseline_peak),
            "baseline_extraction_peak_pss": phase_pss(baseline, "extraction"),
            "candidate_extraction_peak_pss": phase_pss(candidate, "extraction"),
            "baseline_post_idle_max": baseline_post,
            "candidate_post_idle_max": candidate_post,
            "post_idle_delta": delta(candidate_post, baseline_post),
        },
        "resolution": {
            "baseline_successes": baseline["resolution"]["successes"],
            "baseline_attempts": baseline["resolution"]["attempts"],
            "candidate_successes": candidate["resolution"]["successes"],
            "candidate_attempts": candidate["resolution"]["attempts"],
            "baseline_latency_median_ms": baseline["resolution"]["median_ms"],
            "candidate_latency_median_ms": candidate["resolution"]["median_ms"],
            "baseline_latency_max_ms": baseline["resolution"]["max_ms"],
            "candidate_latency_max_ms": candidate["resolution"]["max_ms"],
        },
        "processes": {
            "baseline_max_process_count": baseline["process_inventory"]["max_simultaneous_process_count"],
            "candidate_max_process_count": candidate["process_inventory"]["max_simultaneous_process_count"],
            "baseline_all_process_names": baseline["process_inventory"]["all_process_names"],
            "candidate_all_process_names": candidate["process_inventory"]["all_process_names"],
            "baseline_browser_processes": baseline["process_inventory"]["browser_processes_observed"],
            "candidate_browser_processes": candidate["process_inventory"]["browser_processes_observed"],
            "baseline_quickjs_processes": baseline["process_inventory"]["quickjs_processes_observed"],
            "candidate_quickjs_processes": candidate["process_inventory"]["quickjs_processes_observed"],
            "baseline_bun_processes": baseline["process_inventory"].get("bun_processes_observed", []),
            "candidate_bun_processes": candidate["process_inventory"].get("bun_processes_observed", []),
            "baseline_python_processes": baseline["process_inventory"]["python_processes_observed"],
            "candidate_python_processes": candidate["process_inventory"]["python_processes_observed"],
            "baseline_media_processes": baseline["process_inventory"]["categories"]
            .get("media", {})
            .get("names", []),
            "candidate_media_processes": candidate["process_inventory"]["categories"]
            .get("media", {})
            .get("names", []),
            "candidate_detached_from_app_group_names": candidate["process_inventory"][
                "detached_from_app_group_names"
            ],
            "candidate_max_detached_from_app_group_count": candidate["process_inventory"][
                "max_detached_from_app_group_count"
            ],
        },
    }


def build_report(
    *,
    baseline: dict[str, Any],
    candidate: dict[str, Any],
    baseline_metadata: dict[str, Any],
    candidate_metadata: dict[str, Any],
    java: Path,
    java_home: Path,
    videos: list[str],
    repeats: int,
    sample_interval_ms: int,
    pss_interval_ms: int,
    xvfb_version: str | None,
    repo_root: Path,
    candidate_label: str,
    resolver_engine: str = "quickjs",
) -> dict[str, Any]:
    comparison = comparison_summary(baseline, candidate)
    baseline_peak = comparison["rss_mib"]["baseline_extraction_peak"]
    candidate_peak = comparison["rss_mib"]["candidate_extraction_peak"]
    baseline_idle = comparison["rss_mib"]["baseline_idle_max"]
    candidate_idle = comparison["rss_mib"]["candidate_idle_max"]
    candidate_post = comparison["rss_mib"]["candidate_post_idle_max"]
    engine_label = "Bun" if resolver_engine == "bun" else "QuickJS"
    engine_variable = "ARCHIVETUNE_BUN" if resolver_engine == "bun" else "ARCHIVETUNE_QUICKJS"
    engine_inventory = (
        candidate["process_inventory"]["bun_processes_observed"]
        if resolver_engine == "bun"
        else candidate["process_inventory"]["quickjs_processes_observed"]
    )

    observations = [
        (
            f"Baseline {baseline['commit'][:12]} (legacy resolver) resolved "
            f"{baseline['resolution']['successes']}/{baseline['resolution']['attempts']} requests; "
            f"candidate {candidate['commit'][:12]} (yt-dlp default) resolved "
            f"{candidate['resolution']['successes']}/{candidate['resolution']['attempts']}."
        ),
        (
            "Whole-application idle max RSS: baseline "
            f"{baseline_idle} MiB, candidate {candidate_idle} MiB "
            f"(candidate delta {comparison['rss_mib']['idle_delta']} MiB)."
        ),
        (
            "Whole-application extraction-phase peak of simultaneous total RSS: baseline "
            f"{baseline_peak} MiB, candidate {candidate_peak} MiB "
            f"(candidate delta {comparison['rss_mib']['extraction_peak_delta']} MiB). "
            "This is a measured process-tree total at one instant, not a sum of per-process maxima."
        ),
        (
            "Candidate post-extraction idle max RSS: "
            f"{candidate_post} MiB; baseline post-extraction idle max RSS: "
            f"{comparison['rss_mib']['baseline_post_idle_max']} MiB."
        ),
        (
            "Observed resolver helper processes: yt-dlp "
            f"{candidate['process_inventory']['ytdlp_processes_observed']}, {engine_label} "
            f"{engine_inventory}, separately named Python executables "
            f"{candidate['process_inventory']['python_processes_observed']}, browser "
            f"{candidate['process_inventory']['browser_processes_observed']}, media helpers "
            f"{candidate['process_inventory']['categories'].get('media', {}).get('names', [])}. "
            "The yt-dlp category includes its embedded CPython worker. Anonymous startup did not open a sign-in browser; auth-specific browser RAM is unmeasured."
        ),
        (
            "Transient media helpers observed during candidate extraction: "
            f"{candidate['process_inventory']['categories'].get('media', {}).get('names', [])}. "
            "Their RSS is included in the simultaneous process-tree totals; short-lived ffprobe/ffmpeg "
            "processes can be missed by the PSS sampling pass."
        ),
        (
            "Candidate resolver process groups: during extraction the sampler observed "
            f"{candidate['process_inventory']['max_detached_from_app_group_count']} tracked process(es) "
            "outside the application's own session/process group at once: "
            f"{candidate['process_inventory']['detached_from_app_group_names']}. These are retained by the "
            "descendant closure and persistent (pid, starttime) registry, so the new setsid-per-extraction "
            "lifecycle is included in the totals rather than being dropped with the app's original group."
        ),
        (
            "The baseline legacy path failed every request in this whole-app run, so the peak extraction "
            "number for baseline reflects failed legacy attempts with no resolver child processes. "
            f"The candidate peak includes the transient yt-dlp/{engine_label} processes and is the relevant "
            "whole-application worst case."
            if baseline["resolution"]["successes"] == 0 else
            "The baseline completed at least one resolution; interpret latency alongside the recorded success counts."
        ),
        (
            "The production Windows-only VLC locator did not find the installed system libVLC, so the app's "
            "playback engine could not be initialized. The measurement covers the real UI, services, "
            "home loads and stream resolution, but not audio playback."
        ),
    ]

    return {
        "schema": "archivetune.full-application-benchmark/1",
        "measured_on": time.strftime("%Y-%m-%d", time.gmtime()),
        "platform": {
            "system": platform.platform(),
            "machine": platform.machine(),
            "cpu_count": os.cpu_count(),
            "python": platform.python_version(),
            "xvfb_version": xvfb_version,
        },
        "quality": "HIGH",
        "authentication": "anonymous; no account session and no sign-in flow was started",
        "baseline_commit": baseline["commit"],
        "candidate_commit": candidate["commit"],
        "candidate_label": candidate_label,
        "videos": videos,
        "repeats_per_video": repeats,
        "java": {
            "home": str(java_home),
            "executable": str(java),
            "version": java_version(java),
        },
        "jvm_flags": JVM_FLAGS,
        "sampling": {
            "sample_interval_ms": sample_interval_ms,
            "pss_interval_ms": pss_interval_ms,
            "rss_method": (
                "At each sample, sum RSS of every process in the application session/process group, "
                "its descendant closure and the persistent reparented-descendant registry. Report the "
                "maximum of those simultaneous totals."
            ),
            "pss_method": (
                "At PSS samples, sum /proc/<pid>/smaps_rollup Pss for the same tracked set. Report the "
                "maximum sampled sum. PSS is unavailable for processes that exit during the read."
            ),
            "process_inclusion": (
                "Application PID, PID session, PID process group, descendant closure, and a persistent "
                "(pid, starttime) registry for children that detach or are reparented. Processes that "
                "start a new setsid session/process group remain tracked by parentage and registry. "
                "No full argv is read or stored."
            ),
            "not_a_sum_of_peaks": True,
            "serialized": True,
        },
        "method": {
            "application": "real :desktopApp JVM, real Compose/AWT Window, real DesktopMusicServices",
            "window": "waited for a visible/showing AWT Window, then 10 s settle before extraction",
            "post_idle": "10 s after the last extraction before runtime.close()/exit",
            "offscreen": "private Xvfb display, 1440x900x24, software Skia",
            "isolation": (
                "Each run uses a disposable ARCHIVETUNE_HOME, HOME, XDG dirs and TMPDIR; the user's "
                "profiles, cookies, settings and tokens are not read or written."
            ),
            "instrumentation": (
                "Identical temporary Main.kt instrumentation in disposable detached checkouts; it is not "
                "committed and does not exist on either production branch."
            ),
            "resolver": (
                "Baseline uses the original constructor path (legacy). Candidate keeps the production "
                f"default (yt-dlp) and points ARCHIVETUNE_YTDLP/{engine_variable} at the verified "
                "official tools. All other app code and instrumentation are identical."
            ),
        },
        "instrumentation": {
            "main_kt": str(MAIN_KT_RELATIVE),
            "scope": (
                "temporary hook in disposable detached checkouts only; never committed and "
                "never present in production source"
            ),
            "baseline_sha256": baseline_metadata.get("main_kt_sha256"),
            "candidate_sha256": candidate_metadata.get("main_kt_sha256"),
            "identical": (
                baseline_metadata.get("main_kt_sha256")
                == candidate_metadata.get("main_kt_sha256")
            ),
        },
        "baseline_build": baseline_metadata,
        "candidate_build": candidate_metadata,
        "application_comparison": [baseline, candidate],
        "comparison": comparison,
        "observations": observations,
        "limitations": [
            "One run per version, three public video IDs and two sequential repeats each; this is a small sample.",
            "The Linux playback controller locator is Windows-only. libVLC was not initialized, so audio playback was not exercised.",
            "Anonymous startup produced a visible window and loaded services/home, but this environment's anonymous home/library requests returned HTTP 400; the UI idle footprint is still real, while server-content-dependent memory may differ from a signed-in session.",
            "No sign-in flow was triggered, so browser/WebView memory for authentication is unmeasured; anonymous runs observed no browser processes.",
            "PSS sums are sampled estimates and can miss processes that exit between /proc reads, especially short-lived ffmpeg/ffprobe helpers; RSS is the primary reported number.",
            "The host is shared with the user's desktop applications. Those processes are outside the tracked session and were not counted, but they can affect system scheduling and page-cache pressure.",
            "The benchmark is offscreen with software Skia; GPU-backed rendering may differ.",
        ],
        "repo_root": str(repo_root),
    }


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repo-root", default=str(REPO_ROOT))
    parser.add_argument("--baseline-commit", default=DEFAULT_BASELINE_COMMIT)
    parser.add_argument(
        "--candidate-ref",
        default="feature/ytdlp-resolver",
        help="Branch/ref resolved to an exact commit immediately before the candidate run",
    )
    parser.add_argument(
        "--candidate-commit",
        default=None,
        help="Exact candidate commit; when omitted, --candidate-ref is resolved",
    )
    parser.add_argument("--baseline-dir", help="Existing checkout at --baseline-commit (skips worktree creation)")
    parser.add_argument("--candidate-dir", help="Existing checkout at --candidate-commit (skips worktree creation)")
    parser.add_argument(
        "--work-root",
        default="/tmp/opencode/archivetune-application-benchmark",
        help="Disposable worktrees, build logs and run directories",
    )
    parser.add_argument(
        "--output",
        default=None,
        help=(
            "Report path (default: packaging/resolver/validation/linux-full-application.json for "
            "the quickjs engine, linux-bun-full-application.json for the bun engine)"
        ),
    )
    parser.add_argument("--xvfb", help="Path to Xvfb (default: PATH)")
    parser.add_argument("--ytdlp", help="Verified yt-dlp executable for the candidate")
    parser.add_argument("--quickjs", help="Verified quickjs-ng qjs executable for the candidate")
    parser.add_argument("--bun", help="Verified Bun executable for the candidate (experimental engine)")
    parser.add_argument(
        "--resolver-engine",
        choices=list(RESOLVER_ENGINES),
        default=None,
        help=(
            "JavaScript runtime yt-dlp uses for the candidate arm: quickjs (historical) or bun "
            "(experimental Bun line). Default: bun when --bun is given, otherwise quickjs."
        ),
    )
    parser.add_argument("--java", default="/usr/lib/jvm/java-21-openjdk/bin/java")
    parser.add_argument("--java-home", default="/usr/lib/jvm/java-21-openjdk")
    parser.add_argument(
        "--candidate-label",
        default="final",
        help="Label stored with this candidate run (for example final or interim)",
    )
    parser.add_argument("--videos", nargs="+", default=list(DEFAULT_VIDEOS))
    parser.add_argument("--repeats", type=int, default=2)
    parser.add_argument("--sample-interval-ms", type=int, default=25)
    parser.add_argument("--pss-interval-ms", type=int, default=100)
    parser.add_argument("--app-timeout", type=float, default=240.0)
    parser.add_argument("--skip-build", action="store_true", help="Reuse exported runtime classpaths")
    parser.add_argument("--keep-worktrees", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    if args.repeats < 1:
        raise SystemExit("--repeats must be positive")
    if args.sample_interval_ms < 5:
        raise SystemExit("--sample-interval-ms must be at least 5")
    if any(
        len(video) != 11
        or any(character not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-" for character in video)
        for video in args.videos
    ):
        raise SystemExit("videos must be 11-character YouTube IDs")

    repo_root = Path(args.repo_root).resolve()
    work_root = Path(args.work_root).resolve()
    work_root.mkdir(parents=True, exist_ok=True)
    java = Path(args.java).resolve()
    java_home = Path(args.java_home).resolve()
    xvfb_path = find_xvfb(args.xvfb)

    baseline_commit = git_commit_for(repo_root, args.baseline_commit)
    candidate_commit = git_commit_for(
        repo_root, args.candidate_commit or args.candidate_ref
    )
    print(
        f"Resolved baseline {args.baseline_commit} -> {baseline_commit}",
        flush=True,
    )
    print(
        f"Resolved candidate {args.candidate_commit or args.candidate_ref} -> {candidate_commit}",
        flush=True,
    )

    baseline_dir, baseline_created = ensure_checkout(
        repo_root=repo_root,
        revision=baseline_commit,
        destination=work_root / "baseline",
        existing_dir=args.baseline_dir,
        keep=args.keep_worktrees,
    )
    candidate_dir, candidate_created = ensure_checkout(
        repo_root=repo_root,
        revision=candidate_commit,
        destination=work_root / "candidate",
        existing_dir=args.candidate_dir,
        keep=args.keep_worktrees,
    )

    yt_dlp = discover_resolver(repo_root, args.ytdlp, "ARCHIVETUNE_YTDLP", "yt-dlp")
    if not yt_dlp:
        raise SystemExit(
            "Candidate resolver tools were not found. Pass --ytdlp, or set ARCHIVETUNE_YTDLP. "
            "The benchmark will not silently fall back to a different toolchain."
        )
    engine = args.resolver_engine or ("bun" if args.bun else "quickjs")
    if engine == "bun":
        bun = discover_bun(repo_root, args.bun)
        if not bun:
            raise SystemExit(
                "Candidate resolver tools were not found. Pass --bun, or set ARCHIVETUNE_BUN. "
                "The benchmark will not silently fall back to a different toolchain."
            )
        resolver = {
            "engine": "bun",
            "yt_dlp": yt_dlp,
            "bun": bun,
            "yt_dlp_version": tool_version(yt_dlp),
            "bun_version": tool_version(bun),
        }
        print(
            f"Resolver: yt-dlp {resolver['yt_dlp_version']} at {yt_dlp}; "
            f"bun {resolver['bun_version']} at {bun}",
            flush=True,
        )
    else:
        quickjs = discover_resolver(repo_root, args.quickjs, "ARCHIVETUNE_QUICKJS", "qjs")
        if not quickjs:
            raise SystemExit(
                "Candidate resolver tools were not found. Pass --ytdlp and --quickjs, or set "
                "ARCHIVETUNE_YTDLP/ARCHIVETUNE_QUICKJS. The benchmark will not silently fall back to a "
                "different toolchain."
            )
        resolver = {
            "engine": "quickjs",
            "yt_dlp": yt_dlp,
            "quickjs": quickjs,
            "yt_dlp_version": tool_version(yt_dlp),
            "quickjs_version": tool_version(quickjs),
        }
        print(
            f"Resolver: yt-dlp {resolver['yt_dlp_version']} at {yt_dlp}; "
            f"qjs {resolver['quickjs_version']} at {quickjs}",
            flush=True,
        )

    print(f"Baseline checkout: {baseline_dir}", flush=True)
    print(f"Candidate checkout: {candidate_dir}", flush=True)

    baseline_classpath, baseline_build = build_checkout(
        checkout=baseline_dir,
        work_root=work_root,
        java_home=java_home,
        skip_build=args.skip_build,
        label="baseline",
    )
    candidate_classpath, candidate_build = build_checkout(
        checkout=candidate_dir,
        work_root=work_root,
        java_home=java_home,
        skip_build=args.skip_build,
        label="candidate",
    )

    xvfb_server_version = xvfb_version(xvfb_path)
    baseline_run = run_application(
        checkout=baseline_dir,
        classpath_file=baseline_classpath,
        version="baseline",
        commit=baseline_commit,
        backend="legacy",
        run_dir=work_root / "runs" / "baseline",
        xvfb_path=xvfb_path,
        java=java,
        videos=args.videos,
        repeats=args.repeats,
        sample_interval_ms=args.sample_interval_ms,
        pss_interval_ms=args.pss_interval_ms,
        app_timeout_s=args.app_timeout,
        resolver=None,
    )
    print(
        f"Baseline complete: exit={baseline_run['app_exit_code']} "
        f"resolution={baseline_run['resolution']['successes']}/{baseline_run['resolution']['attempts']}",
        flush=True,
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
        videos=args.videos,
        repeats=args.repeats,
        sample_interval_ms=args.sample_interval_ms,
        pss_interval_ms=args.pss_interval_ms,
        app_timeout_s=args.app_timeout,
        resolver=resolver,
    )
    print(
        f"Candidate complete: exit={candidate_run['app_exit_code']} "
        f"resolution={candidate_run['resolution']['successes']}/{candidate_run['resolution']['attempts']}",
        flush=True,
    )

    report = build_report(
        baseline=baseline_run,
        candidate=candidate_run,
        baseline_metadata=baseline_build,
        candidate_metadata=candidate_build,
        java=java,
        java_home=java_home,
        videos=args.videos,
        repeats=args.repeats,
        sample_interval_ms=args.sample_interval_ms,
        pss_interval_ms=args.pss_interval_ms,
        xvfb_version=xvfb_server_version,
        repo_root=repo_root,
        candidate_label=args.candidate_label,
        resolver_engine=engine,
    )

    report["candidate_ref"] = args.candidate_ref
    report["candidate_commit_source"] = args.candidate_commit or args.candidate_ref
    report["baseline_commit_source"] = args.baseline_commit
    output = Path(args.output) if args.output else (
        REPO_ROOT
        / "packaging/resolver/validation"
        / ("linux-bun-full-application.json" if engine == "bun" else "linux-full-application.json")
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2, sort_keys=False) + "\n", encoding="utf-8")
    print(f"Wrote {output}", flush=True)
    comparison = report["comparison"]
    print(
        "Idle/extraction/post-idle max RSS (MiB): "
        f"baseline {comparison['rss_mib']['baseline_idle_max']} / "
        f"{comparison['rss_mib']['baseline_extraction_peak']} / "
        f"{comparison['rss_mib']['baseline_post_idle_max']}; candidate "
        f"{comparison['rss_mib']['candidate_idle_max']} / "
        f"{comparison['rss_mib']['candidate_extraction_peak']} / "
        f"{comparison['rss_mib']['candidate_post_idle_max']}",
        flush=True,
    )

    cleanup_worktrees = []
    if baseline_created and not args.keep_worktrees:
        cleanup_worktrees.append(baseline_dir)
    if candidate_created and not args.keep_worktrees:
        cleanup_worktrees.append(candidate_dir)
    for directory in cleanup_worktrees:
        try:
            git_output(repo_root, "worktree", "remove", "--force", str(directory))
        except RuntimeError:
            shutil.rmtree(directory, ignore_errors=True)

    failed = (
        baseline_run["app_exit_code"] != 0
        or candidate_run["app_exit_code"] != 0
        or baseline_run["harness_timeout"]
        or candidate_run["harness_timeout"]
    )
    return 1 if failed else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(130)

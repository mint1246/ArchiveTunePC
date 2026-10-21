#!/usr/bin/env python3
"""Compare the Linux desktop application's JVM arm against its GraalVM native arm.

The harness launches two **explicitly configured** commands, samples the Linux
``/proc`` tree of each launch and writes a JSON report.  It never builds, never
compiles, never touches the network, never reads an account secret and never
writes to the user's normal ArchiveTune profile.

Measured
    * **Startup** -- from ``Popen`` until the application reports its first
      Compose frame (the ``window-ready`` marker).
    * **CPU** -- per-process ``utime+stime`` deltas from ``/proc/<pid>/stat``,
      as *percent of one logical core* (100% == one fully busy core) and as CPU
      seconds per phase.
    * **RAM** -- simultaneous sums of ``Rss``/``Pss``/``Private_Clean+Private_Dirty``
      from ``/proc/<pid>/smaps_rollup``.  The three are kept apart on purpose:
      ``rss`` counts a shared page once per process and therefore overstates the
      physical RAM, ``pss`` divides shared pages among their sharers, and ``uss``
      is the private footprint that would be released if the tree died now.  The
      host page cache is reported separately.

Contract (both arms run the same ``Main``)
    The application exports ``ARCHIVETUNE_RUNTIME_BENCHMARK=1``,
    ``ARCHIVETUNE_BENCHMARK_ROOT``, ``ARCHIVETUNE_BENCHMARK_AUDIO``,
    ``ARCHIVETUNE_BENCHMARK_IDLE_SECONDS`` and
    ``ARCHIVETUNE_BENCHMARK_PLAY_SECONDS``, and writes one marker line per event
    on stdout: ``ARCHIVETUNE_RUNTIME_BENCHMARK\\t`` plus a JSON object with an
    ``event`` of ``window-ready``, ``phase-start``, ``phase-end``, ``complete`` or
    ``failure``; the phase events carry ``phase`` = ``idle``/``playback``/``paused``.

Safety
    * Only the launched tree is sampled: the launched PID plus its descendants,
      so compilers and desktop services are never part of a measurement.
    * Cleanup signals **exact PIDs**, each re-validated against its recorded
      ``/proc/<pid>/stat`` start time, so a recycled PID is never hit.  There is
      no process-group kill and no name-based matcher: a ``java`` or
      ``ArchiveTune`` process the user started is never touched.
    * Every run gets a private ``HOME``/``XDG_*``/``TMPDIR``/``XDG_RUNTIME_DIR``
      tree plus a settings fixture that starts the app on the settings page, so
      no network home is loaded and the user's own instance may keep running.
    * The child environment comes from an allow-list; credential-looking
      variables are dropped even when they are inherited.
    * Log files and the JSON report are redacted.

Usage
    scripts/benchmark-linux-runtimes.py \\
        --variant "jvm=/path/to/jvm-image/bin/ArchiveTune" \\
        --variant "native=/tmp/opencode/graal-linux-validation/ArchiveTune" \\
        --xvfb /tmp/opencode/archivetune-hybrid-final/xvfb/usr/bin/Xvfb \\
        --rounds 3 --warmup 1 --out build/reports/linux-runtimes/report.json

The harness never rewrites a command line. Record heap limits baked into a
launcher or native image using --note; they are not inferred from the executable.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import math
import os
import resource
import platform
import re
import shlex
import shutil
import signal
import struct
import subprocess
import sys
import tempfile
import threading
import time
import wave
from pathlib import Path
from typing import Any, Sequence

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parent
SCHEMA = "archivetune.linux-runtime-benchmark/1"


def _load_base():
    """Reuse the host/sampling helpers of the existing application benchmark."""
    path = HERE / "benchmark-application.py"
    spec = importlib.util.spec_from_file_location("archivetune_benchmark_application", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


BASE = _load_base()

MARKER_PREFIX = "ARCHIVETUNE_RUNTIME_BENCHMARK\t"
READY_EVENT = "window-ready"
ENV_BENCHMARK = "ARCHIVETUNE_RUNTIME_BENCHMARK"
ENV_BENCHMARK_ROOT = "ARCHIVETUNE_BENCHMARK_ROOT"
ENV_BENCHMARK_AUDIO = "ARCHIVETUNE_BENCHMARK_AUDIO"
ENV_BENCHMARK_IDLE_SECONDS = "ARCHIVETUNE_BENCHMARK_IDLE_SECONDS"
ENV_BENCHMARK_PLAY_SECONDS = "ARCHIVETUNE_BENCHMARK_PLAY_SECONDS"
DEFAULT_IDLE_SECONDS = 10
DEFAULT_PLAY_SECONDS = 20
DEFAULT_AUDIO_SECONDS = 60
PAGE_SIZE = os.sysconf("SC_PAGE_SIZE")
CLOCK_TICKS = os.sysconf("SC_CLK_TCK")

# The desktop starts on the settings page so the benchmark never loads the
# network home.  This is the application's own settings file format.
SETTINGS_FIXTURE = {"navigation.lastDestination": "SETTINGS"}

# Forwarded to the child when they exist in the parent environment.  Anything
# else has to be requested explicitly, so a stray credential in the developer's
# shell can never reach the benchmarked application.
INHERITED_ENV = (
    "PATH", "LANG", "LC_ALL", "LC_CTYPE", "TZ", "DISPLAY", "XAUTHORITY",
    "XDG_SESSION_TYPE", "DBUS_SESSION_BUS_ADDRESS", "PULSE_SERVER",
    "PULSE_COOKIE", "LD_LIBRARY_PATH", "JAVA_HOME", "GRAALVM_HOME",
)
SECRET_ENV = re.compile(
    r"(?i)(token|secret|password|passwd|api[_-]?key|credential|bearer|cookie|session[_-]?key|"
    r"auth|sas|oauth|jwt|private[_-]?key)"
)
SECRET_ENV_EXEMPT = frozenset({"PULSE_COOKIE"})
SECRET_PATTERNS = (
    # The scheme and its credential go together, otherwise the token survives as
    # the "next word" of the header.
    re.compile(r"(?i)\b(bearer|basic)\s+[A-Za-z0-9._\-+/=]{8,}"),
    # A credential header consumes the rest of the line.
    re.compile(r"(?i)\b(authorization|proxy-authorization|set-cookie|cookie)\s*[:=]\s*[^\r\n]*"),
    re.compile(
        r"(?i)\b(token|secret|password|passwd|api[_-]?key|session[_-]?key|access[_-]?key|"
        r"private[_-]?key|jwt|oauth)\s*[:=]\s*[^\r\n]*"
    ),
    re.compile(r"(?i)\b(sap|sapisid|__Secure-[A-Za-z0-9_]+|sid|hsid|ssid)\s*=\s*[^;\s]+"),
)
REDACTED = "<redacted>"
# The environment keys that are recorded for every run.
EXPECTED_ENV_KEYS = (
    "HOME", "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME", "XDG_STATE_HOME",
    "XDG_RUNTIME_DIR", "TMPDIR", "DISPLAY", "SKIKO_RENDER_API", ENV_BENCHMARK,
    ENV_BENCHMARK_ROOT, ENV_BENCHMARK_AUDIO, ENV_BENCHMARK_IDLE_SECONDS, ENV_BENCHMARK_PLAY_SECONDS,
)


def redact_text(text: str) -> str:
    """Remove credential-looking values before anything is persisted."""
    for pattern in SECRET_PATTERNS:
        text = pattern.sub(REDACTED, text)
    return text


def redact_value(value: Any) -> Any:
    if isinstance(value, str):
        return redact_text(value)
    if isinstance(value, dict):
        return {str(key): redact_value(item) for key, item in value.items()}
    if isinstance(value, list):
        return [redact_value(item) for item in value]
    return value


def cpu_percent_one_core(delta_ticks: float, delta_seconds: float) -> float:
    """CPU percent where 100% means one fully used logical core."""
    if delta_seconds <= 0:
        return 0.0
    return round((delta_ticks / CLOCK_TICKS) / delta_seconds * 100.0, 3)


def host_snapshot() -> dict[str, Any]:
    info: dict[str, int] = {}
    try:
        for line in Path("/proc/meminfo").read_text(encoding="ascii").splitlines():
            key, _, rest = line.partition(":")
            if key in ("MemTotal", "MemAvailable", "Cached", "Buffers", "SwapTotal"):
                info[key] = int(rest.strip().split()[0]) * 1024
    except (OSError, ValueError, IndexError):
        pass
    model = None
    try:
        for line in Path("/proc/cpuinfo").read_text(encoding="utf-8", errors="replace").splitlines():
            if line.lower().startswith("model name"):
                model = line.split(":", 1)[1].strip()
                break
    except OSError:
        pass
    return {
        "kernel": platform.release(),
        "machine": platform.machine(),
        "cpu_model": model,
        "logical_cpus": os.cpu_count(),
        "mem_total_mib": BASE.mib(info.get("MemTotal")),
        "mem_available_mib": BASE.mib(info.get("MemAvailable")),
        "page_cache_mib": BASE.mib(info.get("Cached")),
        "buffers_mib": BASE.mib(info.get("Buffers")),
        "swap_total_mib": BASE.mib(info.get("SwapTotal")),
        "python": platform.python_version(),
    }


class ProcRow:
    """The ``/proc/<pid>/stat`` fields the harness needs."""

    __slots__ = ("pid", "ppid", "starttime", "cpu_ticks", "rss_bytes")

    def __init__(self, pid: int, ppid: int, starttime: int, cpu_ticks: int, rss_bytes: int) -> None:
        self.pid, self.ppid, self.starttime = pid, ppid, starttime
        self.cpu_ticks, self.rss_bytes = cpu_ticks, rss_bytes


def read_rows() -> dict[int, ProcRow]:
    """Every readable ``/proc/<pid>/stat`` row."""
    rows: dict[int, ProcRow] = {}
    try:
        entries = list(Path("/proc").iterdir())
    except OSError:
        return rows
    for entry in entries:
        if not entry.name.isdigit():
            continue
        try:
            data = (entry / "stat").read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        left, right = data.find("("), data.rfind(")")
        if left < 0 or right <= left:
            continue
        fields = data[right + 2 :].split()
        if len(fields) < 22:
            continue
        try:
            pid = int(entry.name)
            rows[pid] = ProcRow(
                pid,
                int(fields[1]),
                int(fields[19]),
                int(fields[11]) + int(fields[12]),
                max(0, int(fields[21])) * PAGE_SIZE,
            )
        except (ValueError, IndexError):
            continue
    return rows


def read_rollup(pid: int) -> tuple[int, int, int] | None:
    """``(rss, pss, uss)`` in bytes from ``/proc/<pid>/smaps_rollup``."""
    values: dict[str, int] = {}
    try:
        text = Path(f"/proc/{pid}/smaps_rollup").read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None
    for line in text.splitlines():
        key, _, rest = line.partition(":")
        if key not in ("Rss", "Pss", "Private_Clean", "Private_Dirty"):
            continue
        parts = rest.split()
        try:
            values[key] = int(parts[0]) * 1024
        except (ValueError, IndexError):
            continue
    if "Rss" not in values:
        return None
    return values["Rss"], values.get("Pss", 0), values.get("Private_Clean", 0) + values.get("Private_Dirty", 0)


class Sampler:
    """Samples the tree the harness launched: the root PID plus its descendants.

    A ``(pid, starttime)`` registry keeps a child that double-forks or calls
    ``setsid()`` attached to the run.  Nothing outside that tree is ever read.
    """

    def __init__(self, root_pid: int, *, interval_ms: int, pss_interval_ms: int) -> None:
        self.root_pid = root_pid
        self.interval = max(0.005, interval_ms / 1000.0)
        self.pss_every = max(1, int(round(pss_interval_ms / max(1, interval_ms))))
        self.samples: list[dict[str, Any]] = []
        self.tracked: dict[int, int] = {}
        self._names: dict[tuple[int, int], str] = {}
        self._cum: dict[tuple[int, int], int] = {}
        self._prev: dict[tuple[int, int], int] = {}
        self._prev_monotonic: float | None = None
        self._iteration = 0

    def sample(self) -> dict[str, Any]:
        now = time.monotonic()
        # The real elapsed time is used, not the nominal interval: reading
        # /proc costs time, and a nominal divisor would inflate the percentage.
        elapsed = now - self._prev_monotonic if self._prev_monotonic is not None else self.interval
        self._prev_monotonic = now
        rows = read_rows()
        tracked = self._tracked_pids(rows)
        with_pss = (self._iteration % self.pss_every) == 0
        processes: list[dict[str, Any]] = []
        for pid in sorted(tracked):
            row = rows.get(pid)
            if row is None:
                continue
            key = (pid, row.starttime)
            self._cum[key] = max(self._cum.get(key, 0), row.cpu_ticks)
            previous = self._prev.get(key)
            delta = max(0, self._cum[key] - previous) if previous is not None else 0
            name = self._names.get(key)
            if name is None:
                name = BASE.process_name(pid, "")
                self._names[key] = name
            rss_bytes, pss_bytes, uss_bytes = row.rss_bytes, None, None
            if with_pss:
                rollup = read_rollup(pid)
                if rollup is not None:
                    rss_bytes, pss_bytes, uss_bytes = rollup
            processes.append(
                {
                    "pid": pid,
                    "name": name,
                    "is_root": pid == self.root_pid,
                    "cpu_percent_one_core": cpu_percent_one_core(delta, elapsed),
                    "cpu_seconds": round(self._cum[key] / CLOCK_TICKS, 3),
                    "rss_mib": BASE.mib(rss_bytes),
                    "pss_mib": BASE.mib(pss_bytes),
                    "uss_mib": BASE.mib(uss_bytes),
                }
            )
        self._prev = dict(self._cum)
        pss = [item["pss_mib"] for item in processes if item["pss_mib"] is not None]
        uss = [item["uss_mib"] for item in processes if item["uss_mib"] is not None]
        sample = {
            "wall_ms": int(time.time() * 1000),
            "process_count": len(processes),
            "rss_mib": round(sum(item["rss_mib"] or 0.0 for item in processes), 2),
            "pss_mib": round(sum(pss), 2) if processes and len(pss) == len(processes) else None,
            "uss_mib": round(sum(uss), 2) if processes and len(uss) == len(processes) else None,
            "cpu_percent_one_core": round(sum(item["cpu_percent_one_core"] for item in processes), 3),
            # Retain already observed CPU from exited children as well as live ones.
            "cpu_seconds": round(sum(self._cum.values()) / CLOCK_TICKS, 3),
            "processes": processes,
        }
        self.samples.append(sample)
        return sample

    def _tracked_pids(self, rows: dict[int, ProcRow]) -> set[int]:
        tracked: set[int] = set()
        harness = os.getpid()
        if self.root_pid in rows:
            tracked.add(self.root_pid)
        changed = True
        while changed:
            changed = False
            for pid, row in rows.items():
                if pid in tracked or pid == harness or pid == 1:
                    continue
                if row.ppid in tracked:
                    tracked.add(pid)
                    changed = True
        for pid, starttime in list(self.tracked.items()):
            row = rows.get(pid)
            if row is not None and row.starttime == starttime:
                tracked.add(pid)
        for pid in tracked:
            row = rows.get(pid)
            if row is not None:
                self.tracked[pid] = row.starttime
        return tracked

    def tracked_entries(self) -> list[tuple[int, int]]:
        """``(pid, starttime)`` of every process this run observed."""
        return sorted(self.tracked.items())

    def primary_process(self) -> dict[str, Any] | None:
        """The process that did the work (a launcher wrapper is not it)."""
        best: dict[str, Any] | None = None
        for sample in self.samples:
            for process in sample["processes"]:
                if best is None or process["cpu_seconds"] > best["cpu_seconds"]:
                    best = process
        return best


def parse_marker_line(line: str) -> dict[str, Any] | None:
    """Parse one ``ARCHIVETUNE_RUNTIME_BENCHMARK\\t{json}`` line."""
    if not line.startswith(MARKER_PREFIX):
        return None
    text = line[len(MARKER_PREFIX) :].strip()
    try:
        payload = json.loads(text) if text else {}
    except ValueError:
        payload = {"raw": redact_text(text)}
    if not isinstance(payload, dict):
        payload = {"raw": redact_text(text)}
    event = str(payload.get("event") or "").strip()
    if not event:
        return None
    marker: dict[str, Any] = {"event": event, "wall_ms": int(time.time() * 1000)}
    # The application's own timestamp is more accurate than the arrival time of
    # the marker line, and both come from the same system clock.
    reported = payload.get("wall_ms")
    if isinstance(reported, (int, float)) and reported > 0:
        marker["wall_ms"] = int(reported)
    if payload.get("phase") is not None:
        marker["phase"] = str(payload["phase"])
    for key, value in payload.items():
        if key not in ("event", "wall_ms", "phase"):
            marker[key] = redact_value(value)
    return marker


class MarkerCollector:
    """Reads the child's stdout, keeps the contract markers and a redacted log."""

    def __init__(self, stream: Any, log_path: Path) -> None:
        self.stream = stream
        self.log_path = log_path
        self.markers: list[dict[str, Any]] = []
        self._events: dict[str, threading.Event] = {}
        self._lock = threading.Lock()
        self._thread = threading.Thread(target=self._run, name="benchmark-stdout", daemon=True)

    def event(self, name: str) -> threading.Event:
        with self._lock:
            handle = self._events.get(name)
            if handle is None:
                handle = threading.Event()
                self._events[name] = handle
            return handle

    def start(self) -> None:
        self._thread.start()

    def join(self, timeout: float = 10.0) -> None:
        self._thread.join(timeout=timeout)

    def _run(self) -> None:
        try:
            self.log_path.parent.mkdir(parents=True, exist_ok=True)
            with self.log_path.open("w", encoding="utf-8", errors="replace") as handle:
                for line in self.stream:
                    marker = parse_marker_line(line.rstrip("\n"))
                    if marker is None:
                        continue
                    with self._lock:
                        self.markers.append(marker)
                        event = self._events.get(marker["event"])
                        if event is not None:
                            event.set()
                    handle.write(redact_text(line.rstrip("\n")) + "\n")
                    handle.flush()
        except OSError:
            pass
        finally:
            with self._lock:
                for event in self._events.values():
                    event.set()


def build_isolated_environment(
    *, root: Path, inherited: dict[str, str], idle_seconds: int, play_seconds: int, audio: Path, display: str | None
) -> dict[str, str]:
    """Create the private directory tree and the child environment."""
    root = root.resolve()
    directories = {
        "HOME": root / "home",
        "XDG_CONFIG_HOME": root / "config",
        "XDG_DATA_HOME": root / "data",
        "XDG_CACHE_HOME": root / "cache",
        "XDG_STATE_HOME": root / "state",
        "XDG_RUNTIME_DIR": root / "runtime",
        "TMPDIR": root / "tmp",
    }
    root.mkdir(parents=True, exist_ok=True)
    for directory in directories.values():
        directory.mkdir(parents=True, exist_ok=True)
        os.chmod(directory, 0o700)

    environment = {name: inherited[name] for name in INHERITED_ENV if name in inherited}
    for name in list(environment):
        if name not in SECRET_ENV_EXEMPT and SECRET_ENV.search(name):
            environment.pop(name, None)
    # Keep the same real audio output while isolating XDG_RUNTIME_DIR. Otherwise
    # PulseAudio would search the empty private directory and fall back to ALSA.
    pulse_socket = Path(inherited.get("XDG_RUNTIME_DIR", "/nonexistent")) / "pulse" / "native"
    if "PULSE_SERVER" not in environment and pulse_socket.is_socket():
        environment["PULSE_SERVER"] = f"unix:{pulse_socket}"

    # The isolation is authoritative: the application's own HOME override must
    # not win over the private tree.
    environment.update({name: str(path) for name, path in directories.items()})
    environment["TMP"] = environment["TMPDIR"]
    environment["TEMP"] = environment["TMPDIR"]
    environment.pop("ARCHIVETUNE_HOME", None)
    if display:
        environment["DISPLAY"] = display
    # Both arms render with the software renderer, so this is not a comparison
    # of two different rasterisers.
    environment["SKIKO_RENDER_API"] = "SOFTWARE"
    environment[ENV_BENCHMARK] = "1"
    environment[ENV_BENCHMARK_ROOT] = str(root)
    environment[ENV_BENCHMARK_AUDIO] = str(audio)
    environment[ENV_BENCHMARK_IDLE_SECONDS] = str(int(idle_seconds))
    environment[ENV_BENCHMARK_PLAY_SECONDS] = str(int(play_seconds))
    return environment


def write_settings_fixture(root: Path) -> list[str]:
    """Pre-seed the desktop settings so the app starts on the settings page.

    The Linux layout puts the settings file below ``$XDG_CONFIG_HOME/archivetune``.
    Both that path and the ``ARCHIVETUNE_HOME`` layout are written, so the
    fixture is effective whichever one the application resolves.
    """
    body = "".join(f"{key}={value}\n" for key, value in sorted(SETTINGS_FIXTURE.items()))
    written: list[str] = []
    for candidate in (
        root / "config" / "archivetune" / "desktop-settings.properties",
        root / "config" / "desktop-settings.properties",
    ):
        candidate.parent.mkdir(parents=True, exist_ok=True)
        candidate.write_text(body, encoding="utf-8")
        written.append(str(candidate))
    return written


def write_tone_wav(path: Path, seconds: int) -> Path:
    """Write a deterministic mono PCM tone so both arms play identical audio."""
    sample_rate = 22050
    frames = sample_rate * max(1, int(seconds))
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(sample_rate)
        written = 0
        while written < frames:
            count = min(4096, frames - written)
            data = bytearray()
            for index in range(written, written + count):
                data += struct.pack("<h", int(12000 * math.sin(2.0 * math.pi * 440.0 * index / sample_rate)))
            handle.writeframes(bytes(data))
            written += count
    return path


def terminate_tracked(entries: Sequence[tuple[int, int]], *, grace_seconds: float = 5.0) -> dict[str, Any]:
    """Terminate exactly the tracked PIDs.

    Every PID is re-validated against its recorded ``/proc/<pid>/stat`` start
    time before it is signalled, so a recycled PID is never hit.  There is no
    process-group kill and no name-based matcher: a process the harness did not
    start is never signalled.
    """
    terminated: list[int] = []
    killed: list[int] = []
    skipped: list[dict[str, Any]] = []
    for pid, starttime in sorted(entries):
        row = read_rows().get(pid)
        if row is None:
            continue
        if row.starttime != starttime:
            skipped.append({"pid": pid, "reason": "pid-reused"})
            continue
        try:
            os.kill(pid, signal.SIGTERM)
            terminated.append(pid)
        except (ProcessLookupError, PermissionError):
            continue
    deadline = time.monotonic() + max(0.0, grace_seconds)
    while time.monotonic() < deadline:
        alive = read_rows()
        if not any(pid in alive for pid in terminated):
            break
        time.sleep(0.1)
    for pid, starttime in sorted(entries):
        if pid not in terminated:
            continue
        row = read_rows().get(pid)
        if row is None:
            continue
        if row.starttime != starttime:
            skipped.append({"pid": pid, "reason": "pid-reused"})
            continue
        try:
            os.kill(pid, signal.SIGKILL)
            killed.append(pid)
        except (ProcessLookupError, PermissionError):
            continue
    return {"sigterm_pids": terminated, "sigkill_pids": killed, "skipped": skipped}


def heap_flags(command: Sequence[str]) -> dict[str, Any]:
    """Report the heap-related flags of a command verbatim (never modifies it)."""
    found: dict[str, Any] = {"xms": None, "xmx": None, "max_heap_size": None, "raw": []}
    for part in command:
        for prefix, key in (("-Xms", "xms"), ("-Xmx", "xmx"), ("-XX:MaxHeapSize=", "max_heap_size")):
            if part.startswith(prefix):
                found[key] = part[len(prefix) :]
                found["raw"].append(part)
    return found


def describe_variant(name: str, command: Sequence[str], notes: str) -> dict[str, Any]:
    """Record the exact command, the executable identity and the version."""
    described: dict[str, Any] = {
        "name": name,
        "command": list(command),
        "notes": notes,
        "heap": heap_flags(command),
        "executable": None,
        "size_bytes": None,
        "sha256": None,
        "version_string": None,
    }
    executable = Path(command[0])
    if executable.is_file():
        resolved = executable.resolve()
        described["executable"] = str(resolved)
        described["size_bytes"] = resolved.stat().st_size
        described["sha256"] = BASE.sha256_file(resolved)
    if executable.name.startswith("java"):
        try:
            completed = subprocess.run(
                [str(executable), "-version"],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                timeout=30,
                check=False,
            )
            text = (completed.stdout or "").strip()
            described["version_string"] = redact_text(text.splitlines()[0]) if text else None
        except (OSError, subprocess.SubprocessError):
            described["version_string"] = None
    return described


def derive_phase_windows(
    markers: Sequence[dict[str, Any]], *, launch_wall_ms: int, ready_wall_ms: int | None, exit_wall_ms: int
) -> dict[str, tuple[int, int] | None]:
    """The phase windows the contract markers define."""
    windows: dict[str, tuple[int, int] | None] = {"startup": None, "idle": None, "playback": None, "paused": None}
    if ready_wall_ms is not None:
        windows["startup"] = (launch_wall_ms, ready_wall_ms)
    phases: dict[str, dict[str, int]] = {}
    for marker in markers:
        phase = marker.get("phase")
        if marker.get("event") not in ("phase-start", "phase-end") or not phase:
            continue
        phases.setdefault(str(phase), {})[str(marker["event"])] = int(marker["wall_ms"])
    for phase, bounds in phases.items():
        start = bounds.get("phase-start")
        if start is not None:
            windows[phase] = (start, bounds.get("phase-end", exit_wall_ms))
    return windows


def summarize_phase(samples: Sequence[dict[str, Any]], window: tuple[int, int] | None) -> dict[str, Any] | None:
    """Summarise one phase of one run."""
    if window is None:
        return None
    start_ms, end_ms = window
    selected = [sample for sample in samples if start_ms <= sample["wall_ms"] <= end_ms]
    if not selected:
        return None
    def cpu_at(wall_ms: int) -> float:
        before = next((s for s in reversed(samples) if s["wall_ms"] <= wall_ms), samples[0])
        after = next((s for s in samples if s["wall_ms"] >= wall_ms), samples[-1])
        span = after["wall_ms"] - before["wall_ms"]
        fraction = (wall_ms - before["wall_ms"]) / span if span > 0 else 0.0
        return before["cpu_seconds"] + (after["cpu_seconds"] - before["cpu_seconds"]) * fraction

    cpu_seconds = max(0.0, cpu_at(end_ms) - cpu_at(start_ms))
    peak = max(selected, key=lambda sample: sample["rss_mib"])
    return {
        "start_wall_ms": start_ms,
        "end_wall_ms": end_ms,
        "duration_ms": max(0, end_ms - start_ms),
        "samples": len(selected),
        "cpu_percent_one_core": BASE.stat_summary(sample["cpu_percent_one_core"] for sample in selected),
        "cpu_seconds": round(cpu_seconds, 3),
        "cpu_mean_percent_one_core": cpu_seconds * 100_000 / max(1, end_ms - start_ms),
        "rss_mib": BASE.stat_summary(sample["rss_mib"] for sample in selected),
        "pss_mib": BASE.stat_summary(sample["pss_mib"] for sample in selected),
        "uss_mib": BASE.stat_summary(sample["uss_mib"] for sample in selected),
        "process_count": BASE.stat_summary(sample["process_count"] for sample in selected),
        "peak_sample": {
            "wall_ms": peak["wall_ms"],
            "rss_mib": peak["rss_mib"],
            "pss_mib": peak["pss_mib"],
            "uss_mib": peak["uss_mib"],
            "cpu_percent_one_core": peak["cpu_percent_one_core"],
            "process_count": peak["process_count"],
            "processes": peak["processes"],
        },
    }


def run_variant(
    *, name: str, command: Sequence[str], notes: str, run_dir: Path, options: dict[str, Any], round_index: int, warmup: bool
) -> dict[str, Any]:
    """Run one arm once and return its measured record."""
    run_dir.mkdir(parents=True, exist_ok=True)
    root = run_dir / "root"
    environment = build_isolated_environment(
        root=root,
        inherited=options["inherited"],
        idle_seconds=options["idle_seconds"],
        play_seconds=options["play_seconds"],
        audio=options["audio"],
        display=options["display"],
    )
    fixture_files = write_settings_fixture(root)
    collector = MarkerCollector(None, run_dir / "application.log")
    process: subprocess.Popen[Any] | None = None
    sampler: Sampler | None = None
    status = "ok"
    failure: dict[str, Any] | None = None
    timed_out = False
    cleanup: dict[str, Any] = {"sigterm_pids": [], "sigkill_pids": [], "skipped": []}

    try:
        launch_wall_ms = int(time.time() * 1000)
        process = subprocess.Popen(
            list(command),
            env=environment,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
            start_new_session=True,
        )
        collector = MarkerCollector(process.stdout, run_dir / "application.log")
        collector.start()
        sampler = Sampler(
            process.pid,
            interval_ms=options["sample_interval_ms"],
            pss_interval_ms=options["pss_interval_ms"],
        )
        ready, complete, failed = (collector.event(name) for name in (READY_EVENT, "complete", "failure"))
        # One loop samples the tree and watches the contract markers at the same
        # time, so the CPU and memory timeline is the marker timeline.
        ready_deadline = time.monotonic() + options["ready_timeout"]
        workload_deadline = ready_deadline + options["workload_timeout"]
        hard_deadline = time.monotonic() + options["run_timeout"]
        while process.poll() is None and time.monotonic() < hard_deadline:
            sampler.sample()
            if not ready.is_set() and time.monotonic() > ready_deadline:
                status, failure = "not-ready", {"category": "readiness-timeout"}
                break
            if ready.is_set() and not (complete.is_set() or failed.is_set()):
                if time.monotonic() > workload_deadline:
                    status, failure = "timeout", {"category": "workload-timeout"}
                    break
            time.sleep(sampler.interval)
        else:
            if process.poll() is None:
                timed_out, status = True, "timeout"
    except Exception as error:  # pragma: no cover - defensive path
        status = "error"
        failure = {"category": error.__class__.__name__, "detail": redact_text(str(error))}
    finally:
        if sampler is not None:
            cleanup = terminate_tracked(sampler.tracked_entries())
        if process is not None and process.poll() is None:
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                pass
        collector.join(timeout=10)
        if process is not None and process.stdout is not None:
            try:
                process.stdout.close()
            except OSError:
                pass

    markers = list(collector.markers)
    ready_wall_ms = next(
        (marker["wall_ms"] for marker in markers if marker["event"] == READY_EVENT), None
    )
    failure_marker = next((marker for marker in markers if marker.get("event") == "failure"), None)
    complete_marker = next((marker for marker in markers if marker.get("event") == "complete"), None)
    if failure_marker is not None:
        status, failure = "failed", {"category": failure_marker.get("category") or "unknown"}
    elif complete_marker is not None and complete_marker.get("success") is False:
        status, failure = "failed", {"category": "reported-failure"}
    elif status == "ok" and complete_marker is None:
        status, failure = "incomplete", {"category": "no-complete-marker"}
    elif status == "ok" and process is not None and process.returncode != 0:
        status, failure = "failed", {"category": "nonzero-exit"}

    samples = list(sampler.samples) if sampler is not None else []
    exit_wall_ms = int(time.time() * 1000)
    windows = derive_phase_windows(
        markers, launch_wall_ms=launch_wall_ms, ready_wall_ms=ready_wall_ms, exit_wall_ms=exit_wall_ms
    )
    return {
        "variant": name,
        "notes": notes,
        "round": round_index,
        "warmup": warmup,
        "status": status,
        "failure": failure,
        "exit_code": process.poll() if process is not None else None,
        "harness_timeout": timed_out,
        "launch_wall_ms": launch_wall_ms,
        "exit_wall_ms": exit_wall_ms,
        "duration_ms": exit_wall_ms - launch_wall_ms,
        "ready_wall_ms": ready_wall_ms,
        "phase_windows": {key: (list(window) if window else None) for key, window in windows.items()},
        "phases": {key: summarize_phase(samples, window) for key, window in windows.items()},
        "markers": markers,
        "primary_process": sampler.primary_process() if sampler is not None else None,
        "process_names": sorted({item["name"] for sample in samples for item in sample["processes"]}),
        "max_process_count": max((sample["process_count"] for sample in samples), default=0),
        "environment": {
            **{key: environment.get(key) for key in environment if key in EXPECTED_ENV_KEYS},
            "settings_fixture_files": fixture_files,
        },
        "sampling": {
            "sample_interval_ms": options["sample_interval_ms"],
            "pss_interval_ms": options["pss_interval_ms"],
            "samples": len(samples),
            "cpu_unit": "percent of one logical core (100% == one busy core)",
            "inclusion": "the launched PID, its descendants and a persistent (pid, starttime) registry",
        },
        "cleanup": cleanup,
        "raw_samples": samples,
    }


def aggregate_runs(runs: Sequence[dict[str, Any]]) -> dict[str, Any]:
    """Aggregate the measured (non-warmup) runs of one arm."""
    attempted = [run for run in runs if not run.get("warmup")]
    measured = [run for run in attempted if run.get("status") == "ok"]
    if not measured:
        return {"runs": len(attempted), "ok_runs": 0, "failed_runs": len(attempted), "usable": False}
    summary: dict[str, Any] = {
        "runs": len(attempted),
        "ok_runs": len(measured),
        "failed_runs": len(attempted) - len(measured),
        "usable": len(measured) == len(attempted),
        "startup_ms": BASE.stat_summary(
            run["phases"]["startup"]["duration_ms"]
            for run in measured
            if (run.get("phases") or {}).get("startup")
        ),
        "process_names": sorted({name for run in measured for name in run["process_names"]}),
        "max_process_count": max(run["max_process_count"] for run in measured),
    }
    for phase in ("idle", "playback", "paused"):
        phases = [stats for stats in ((run.get("phases") or {}).get(phase) for run in measured) if stats]
        summary[phase] = {
            "cpu_percent_one_core": BASE.stat_summary(stats["cpu_mean_percent_one_core"] for stats in phases),
            "cpu_seconds": BASE.stat_summary(stats["cpu_seconds"] for stats in phases),
            "rss_mib": BASE.stat_summary(stats["rss_mib"]["median"] for stats in phases),
            "pss_mib": BASE.stat_summary(stats["pss_mib"]["median"] for stats in phases),
            "uss_mib": BASE.stat_summary(stats["uss_mib"]["median"] for stats in phases),
        }
    for key in ("rss_mib", "pss_mib", "uss_mib"):
        summary[f"peak_{key}"] = max(
            (sample[key] or 0.0) for run in measured for sample in run["raw_samples"]
        )
    return summary


def compare(baseline: dict[str, Any], candidate: dict[str, Any]) -> dict[str, Any]:
    """Field-by-field comparison of the two arms."""
    paths = (
        ("startup_ms_median", ("startup_ms", "median")),
        ("idle_cpu_percent_one_core_median", ("idle", "cpu_percent_one_core", "median")),
        ("playback_cpu_percent_one_core_median", ("playback", "cpu_percent_one_core", "median")),
        ("idle_rss_mib_median", ("idle", "rss_mib", "median")),
        ("idle_pss_mib_median", ("idle", "pss_mib", "median")),
        ("idle_uss_mib_median", ("idle", "uss_mib", "median")),
        ("playback_rss_mib_median", ("playback", "rss_mib", "median")),
        ("playback_pss_mib_median", ("playback", "pss_mib", "median")),
        ("playback_uss_mib_median", ("playback", "uss_mib", "median")),
        ("peak_rss_mib", ("peak_rss_mib",)),
        ("peak_pss_mib", ("peak_pss_mib",)),
        ("peak_uss_mib", ("peak_uss_mib",)),
    )

    def value(summary: dict[str, Any], path: Sequence[str]) -> Any:
        current: Any = summary
        for key in path:
            current = (current or {}).get(key) if isinstance(current, dict) else None
        return current

    comparison: dict[str, Any] = {}
    for label, path in paths:
        base, cand = value(baseline, path), value(candidate, path)
        entry: dict[str, Any] = {"baseline": base, "candidate": cand}
        if baseline.get("usable", True) and candidate.get("usable", True) and isinstance(base, (int, float)) and isinstance(cand, (int, float)):
            entry["delta"] = round(cand - base, 2)
            entry["ratio"] = round(cand / base, 3) if base else None
        comparison[label] = entry
    return comparison


LIMITATIONS = [
    "CPU comes from /proc/<pid>/stat deltas polled every sample interval: a helper that lives "
    "for less than one interval can be missed, as can its last CPU increment before exit.",
    "CPU percent is relative to one logical core (100% is one fully busy core, not one machine).",
    "The tree RSS total counts a shared page once per process and therefore overstates the "
    "physical RAM; PSS divides shared pages among their sharers and USS is the private footprint.",
    "PSS/USS come from /proc/<pid>/smaps_rollup; without that file the report carries null values "
    "instead of a partial total.",
    "Only the tree this harness launched is measured; the user's own instance and other desktop "
    "services are excluded from the totals but still consume host resources.",
    "The launcher wrapper (AppImage AppRun, jpackage launcher, shell script) is reported "
    "separately from the dominant CPU consumer, which is treated as the application process.",
    "Warmups prime shared host caches; every measured run still has a fresh private XDG tree.",
    "The harness never rewrites a command line: JVM flags and the native heap setting "
    "must be documented in arm notes when baked into a launcher or image; both arms must be "
    "built from the same source.",
    "Anonymous background maintenance may run; the workload needs no account or DRM and uses a local muted file played "
    "through the real playback controller.",
    "This is a process-level measurement: it says nothing about in-app latency or frame rate.",
]


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument(
        "--variant", action="append", required=True, metavar="NAME=COMMAND",
        help="the exact command line of an arm (repeat twice: the baseline and the candidate)",
    )
    parser.add_argument("--note", action="append", default=[], help="a note per arm, in arm order")
    parser.add_argument("--out", help="path of the JSON report")
    parser.add_argument("--rounds", type=int, default=3, help="measured rounds per arm (default: 3)")
    parser.add_argument("--warmup", type=int, default=1, help="warmup rounds per arm (default: 1)")
    parser.add_argument("--sample-interval-ms", type=int, default=100)
    parser.add_argument("--pss-interval-ms", type=int, default=1000)
    parser.add_argument("--idle-seconds", type=int, default=DEFAULT_IDLE_SECONDS)
    parser.add_argument("--play-seconds", type=int, default=DEFAULT_PLAY_SECONDS)
    parser.add_argument("--audio-seconds", type=int, default=DEFAULT_AUDIO_SECONDS)
    parser.add_argument("--audio", help="use an existing local media fixture instead of the generated WAV")
    parser.add_argument("--xvfb", help="path to the Xvfb binary (it does not have to be on PATH)")
    parser.add_argument("--display", help="use an existing X display instead of starting Xvfb")
    parser.add_argument("--run-root", help="directory for the isolated run trees")
    parser.add_argument("--keep-run-root", action="store_true")
    parser.add_argument("--ready-timeout", type=float, default=180.0)
    parser.add_argument("--workload-timeout", type=float, default=180.0)
    parser.add_argument("--run-timeout", type=float, default=600.0)
    parser.add_argument("--dry-run", action="store_true", help="print the plan and exit")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    # Native crashes must not leave multi-gigabyte core dumps in the run tree.
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    arms: list[tuple[str, list[str]]] = []
    for entry in args.variant:
        name, separator, command = entry.partition("=")
        if not separator or not name.strip() or not command.strip():
            print(f"error: --variant must be NAME=COMMAND, got {entry!r}", file=sys.stderr)
            return 2
        arms.append((name.strip(), shlex.split(command)))
    if len(arms) != 2:
        print("error: exactly two --variant arms are required (baseline and candidate)", file=sys.stderr)
        return 2
    if arms[0][0] == arms[1][0] or any(not re.fullmatch(r"[A-Za-z0-9_-]+", name) for name, _ in arms):
        print("error: arm names must be distinct and contain only letters, digits, underscores or hyphens", file=sys.stderr)
        return 2

    if args.run_root:
        run_root = Path(args.run_root).resolve()
        run_root.mkdir(parents=True, exist_ok=True)
        temporary_root = False
    else:
        run_root = Path(tempfile.mkdtemp(prefix="at-bench-")).resolve()
        temporary_root = True

    display = args.display
    xvfb_process = None
    if display is None:
        try:
            xvfb_path = BASE.find_xvfb(args.xvfb)
        except RuntimeError:
            xvfb_path = None
        if xvfb_path is not None:
            xvfb_process, xvfb_display = BASE.start_xvfb(xvfb_path, run_root / "xvfb.log")
            display = f":{xvfb_display}"
        else:
            print("error: Xvfb is unavailable; pass --xvfb or explicitly choose --display", file=sys.stderr)
            return 2

    audio = (
        Path(args.audio).resolve()
        if args.audio
        else write_tone_wav(run_root / "audio" / "tone.wav", args.audio_seconds)
    )
    if not audio.is_file():
        print(f"error: the audio fixture does not exist: {audio}", file=sys.stderr)
        return 2

    options = {
        "inherited": dict(os.environ),
        "idle_seconds": args.idle_seconds,
        "play_seconds": args.play_seconds,
        "audio": audio,
        "display": display,
        "sample_interval_ms": args.sample_interval_ms,
        "pss_interval_ms": args.pss_interval_ms,
        "ready_timeout": args.ready_timeout,
        "workload_timeout": args.workload_timeout,
        "run_timeout": args.run_timeout,
    }
    plan = {
        "arms": [{"name": name, "command": command} for name, command in arms],
        "rounds": args.rounds,
        "warmup_rounds": args.warmup,
        "sample_interval_ms": args.sample_interval_ms,
        "pss_interval_ms": args.pss_interval_ms,
        "idle_seconds": args.idle_seconds,
        "play_seconds": args.play_seconds,
        "audio": str(audio),
        "display": display,
        "run_root": str(run_root),
    }
    if args.dry_run:
        print(json.dumps(plan, indent=2))
        if xvfb_process is not None:
            BASE.stop_xvfb(xvfb_process)
        return 0

    runs: list[dict[str, Any]] = []

    def execute(name: str, command: Sequence[str], note: str, run_dir: Path, index: int, warmup: bool) -> None:
        runs.append(
            run_variant(
                name=name, command=command, notes=note, run_dir=run_dir,
                options=options, round_index=index, warmup=warmup,
            )
        )

    try:
        for index in range(max(0, args.warmup)):
            for arm, (name, command) in enumerate(arms):
                print(f"[warmup {index + 1}/{args.warmup}] {name} ...", flush=True)
                execute(name, command, args.note[arm] if arm < len(args.note) else "",
                        run_root / name / f"warmup-{index}", index, True)
        for index in range(args.rounds):
            # The order alternates so a slow drift of the host cannot favour one arm.
            order = list(arms) if index % 2 == 0 else list(reversed(arms))
            for name, command in order:
                arm = arms.index((name, command))
                print(f"[round {index + 1}/{args.rounds}] {name} ...", flush=True)
                execute(name, command, args.note[arm] if arm < len(args.note) else "",
                        run_root / name / f"round-{index}", index, False)
    finally:
        if xvfb_process is not None:
            BASE.stop_xvfb(xvfb_process)

    summaries = {name: aggregate_runs([run for run in runs if run["variant"] == name]) for name, _ in arms}
    report = {
        "schema": SCHEMA,
        "harness": {"script": str(Path(__file__).resolve()), "python": platform.python_version()},
        "measured_on": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "host": host_snapshot(),
        "plan": plan,
        "arms": [
            describe_variant(name, command, args.note[index] if index < len(args.note) else "")
            for index, (name, command) in enumerate(arms)
        ],
        "runs": runs,
        "summary": summaries,
        "comparison": compare(summaries[arms[0][0]], summaries[arms[1][0]]),
        "limitations": LIMITATIONS,
    }
    out_path = Path(args.out) if args.out else REPO_ROOT / "build" / "reports" / "linux-runtimes" / "report.json"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print_summary(summaries, arms, out_path)

    if not args.keep_run_root and temporary_root:
        shutil.rmtree(run_root, ignore_errors=True)
    measured_ok = sum(1 for run in runs if not run.get("warmup") and run.get("status") == "ok")
    return 0 if measured_ok > 0 and measured_ok == sum(not run.get("warmup") for run in runs) else 1


def print_summary(summaries: dict[str, Any], arms: Sequence[tuple[str, list[str]]], out_path: Path) -> None:
    print()
    print(f"{'arm':<14}{'runs':>6}{'startup ms':>13}{'idle cpu%':>11}{'play cpu%':>11}{'rss MiB':>10}{'pss MiB':>10}{'uss MiB':>10}")
    for name, _ in arms:
        summary = summaries[name]
        cells = [
            summary.get("runs", 0),
            (summary.get("startup_ms") or {}).get("median"),
            ((summary.get("idle") or {}).get("cpu_percent_one_core") or {}).get("median"),
            ((summary.get("playback") or {}).get("cpu_percent_one_core") or {}).get("median"),
            summary.get("peak_rss_mib"),
            summary.get("peak_pss_mib"),
            summary.get("peak_uss_mib"),
        ]
        rendered = [
            str(cells[0]),
            f"{cells[1]:.0f}" if cells[1] is not None else "-",
            f"{cells[2]:.1f}" if cells[2] is not None else "-",
            f"{cells[3]:.1f}" if cells[3] is not None else "-",
            *(str(value) if value else "-" for value in cells[4:]),
        ]
        print(
            f"{name:<14}{rendered[0]:>6}{rendered[1]:>13}{rendered[2]:>11}{rendered[3]:>11}"
            f"{rendered[4]:>10}{rendered[5]:>10}{rendered[6]:>10}"
        )
    print()
    print(f"report: {out_path}")


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ArchiveTune yt-dlp + Bun pipeline profiler (developer-only harness).

This script is deliberately *not* shipped and nothing in the product imports it.
It profiles the pinned official yt-dlp/Bun sidecars stage by stage, separately
from the application JVM.  It is the evidence generator for
``YT_DLP_PIPELINE_PROFILE.md`` and
``packaging/resolver/validation/linux-ytdlp-pipeline-profile.json``.

Safety contract
---------------
* Anonymous, public requests only.  Cookies are never read, written or printed.
* Signed media URLs, query strings, request headers and raw yt-dlp JSON never
  enter the report.  They live in this process's memory and are discarded.
* Only redacted stage labels, durations, statuses, byte counts and safe format
  metadata are emitted.
* Every child runs in its own process group with a hard timeout and group kill.
* The harness never edits the yt-dlp/Bun executables, the application, or the
  original ``.tools`` checkout.

Measurement contract
---------------------
* ``--mode cli`` measures new processes with stderr line timestamps.  A marker
  timestamp is when the harness observed the line, not when the child emitted
  the first byte, so each request window is *inclusive* of the parsing that
  happened before the next marker.
* ``--mode api`` uses the developer virtualenv and Python API instrumentation
  (``cProfile`` plus a ``urlopen`` wrapper).  It is allowed for profiling but is
  not a production patch.
* Quantiles are Hyndman-Fan type 7 (linear interpolation); they are computed
  over successful runs only.  The success denominator always includes failures.
* Cold/warm state is explicit.  Binary page-cache eviction is best effort via
  ``posix_fadvise(POSIX_FADV_DONTNEED)``; DNS/TLS/CDN state cannot be evicted.

Usage
-----
    # stage profile, interleaved, writes nothing by default
    scripts/profile-ytdlp-pipeline.py --mode cli --yt-dlp .tools/yt-dlp \
        --bun .tools/bun --repeats 5 --out /tmp/profile.json

    # developer API instrumentation (run with the venv interpreter)
    scripts/profile-ytdlp-pipeline.py --mode api --yt-dlp .tools/yt-dlp \
        --bun .tools/bun --out /tmp/api.json

Exit code is 0 when the pinned tools verify and every planned control ran.
"""

from __future__ import annotations

import argparse
import cProfile
import datetime as dt
import hashlib
import http.server
import io
import json
import math
import os
import pstats
import random
import re
import resource
import shutil
import signal
import socket
import statistics
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

# ---------------------------------------------------------------------------
# Pinned artifacts (must match buildSrc/src/main/kotlin/ResolverSidecars.kt)
# ---------------------------------------------------------------------------

YTDLP_VERSION = "2026.08.19"
YTDLP_SHA256 = "58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a"
BUN_VERSION = "1.4.3"
BUN_SHA256 = "7ce7d6b654eddeec20afddf2396317fba6dc19ab52788a27e894e1806bf8f24b"
EJS_VERSION = "0.8.0"

DEFAULT_VIDEOS: Tuple[str, ...] = (
    "jNQXAC9IVRw",   # first YouTube video; anonymous visionos-skip-web control
    "dQw4w9WgXcQ",   # n-challenge case with web_embedded
    "aqz-KE-bpKQ",
    "kJQP7kiw5Fk",
    "OPf0YbXqDm0",
)
EXTRA_VIDEOS: Tuple[str, ...] = (
    "fJ9rUzIMcZQ",
    "hTWKbfoikeg",
    "YQHsXMglC9A",
    "9bZkp7q19f0",
)

# Only public, anonymous, non-age-restricted fixtures verified in this slot.
VIDEO_NOTES: Dict[str, str] = {
    "jNQXAC9IVRw": "public; visionos-only with player_skip=webpage is bot-blocked here",
    "dQw4w9WgXcQ": "public; web_embedded executes a genuine n challenge",
    "aqz-KE-bpKQ": "public; visionos-only with player_skip=webpage is bot-blocked here",
    "kJQP7kiw5Fk": "public upstream fixture",
    "OPf0YbXqDm0": "public upstream fixture",
    "fJ9rUzIMcZQ": "public upstream fixture",
    "hTWKbfoikeg": "public upstream fixture",
    "YQHsXMglC9A": "public upstream fixture",
    "9bZkp7q19f0": "public upstream fixture",
}

AUDIO_FORMAT = "bestaudio[protocol=https]/bestaudio[protocol=http]"

# ---------------------------------------------------------------------------
# Small utilities
# ---------------------------------------------------------------------------

_ANSI_RE = re.compile(r"\x1b\[[0-9;]*m")
_URL_RE = re.compile(r"https?://[^\s\"'<>\\)\]]+")
_SECRET_QUERY_RE = re.compile(
    r"\b(sig|s|lsig|n|lsparams|pot|cpn|ip|ei|id|ratebypass|expire)=[A-Za-z0-9._%-]+",
    re.IGNORECASE,
)


def sanitize(text: str, limit: int = 400) -> str:
    """Redact URLs and signed-looking query parameters from free-form text."""
    if not text:
        return ""
    cleaned = _URL_RE.sub("<redacted-url>", _ANSI_RE.sub("", text))
    cleaned = _SECRET_QUERY_RE.sub(lambda m: f"{m.group(1)}=<redacted>", cleaned)
    cleaned = cleaned.replace("\r", " ").replace("\n", " | ")
    return cleaned[:limit]


def sha256_of(path: str) -> Optional[str]:
    try:
        digest = hashlib.sha256()
        with open(path, "rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
        return digest.hexdigest()
    except OSError:
        return None


def quantile_type7(sorted_values: Sequence[float], p: float) -> Optional[float]:
    """Hyndman-Fan type 7 (numpy/Excel default) sample quantile."""
    if not sorted_values:
        return None
    if len(sorted_values) == 1:
        return float(sorted_values[0])
    h = (len(sorted_values) - 1) * p
    lo = math.floor(h)
    frac = h - lo
    if lo + 1 >= len(sorted_values):
        return float(sorted_values[-1])
    return float(sorted_values[lo] + frac * (sorted_values[lo + 1] - sorted_values[lo]))


def distribution(values: Iterable[float]) -> Dict[str, Any]:
    vals = sorted(float(v) for v in values if v is not None)
    if not vals:
        return {"n": 0, "min": None, "p50": None, "p90": None, "p95": None,
                "max": None, "mean": None}
    return {
        "n": len(vals),
        "min": round(vals[0], 3),
        "p50": round(quantile_type7(vals, 0.50), 3),
        "p90": round(quantile_type7(vals, 0.90), 3),
        "p95": round(quantile_type7(vals, 0.95), 3),
        "max": round(vals[-1], 3),
        "mean": round(statistics.fmean(vals), 3),
    }


def quantile_method() -> str:
    return ("Hyndman-Fan type 7 linear interpolation over successful runs; "
            "n is the successful-run count, while attempted/failed counts are "
            "reported separately so the success denominator includes failures")


def loadavg() -> Optional[float]:
    try:
        return round(os.getloadavg()[0], 2)
    except OSError:
        return None


def count_foreign_sidecars(own_pid: int) -> int:
    """Count yt-dlp/bun processes that are not descendants of this harness."""
    counts = 0
    for entry in os.listdir("/proc"):
        if not entry.isdigit():
            continue
        pid = int(entry)
        if pid == own_pid:
            continue
        try:
            with open(f"/proc/{pid}/comm", "r", encoding="utf-8", errors="replace") as handle:
                comm = handle.read().strip()
        except OSError:
            continue
        if comm in {"yt-dlp", "bun", "yt-dlp.exe", "bun.exe"}:
            counts += 1
    return counts


def file_system_type(path: str) -> Optional[str]:
    """Return the mount filesystem type for *path* using /proc/self/mountinfo."""
    try:
        real = os.path.realpath(path)
        best = None
        with open("/proc/self/mountinfo", "r", encoding="utf-8") as handle:
            for line in handle:
                fields = line.split()
                if len(fields) < 5:
                    continue
                mount = fields[4].replace("\\040", " ")
                if real == mount or real.startswith(mount.rstrip("/") + "/"):
                    fstype = fields[-3] if len(fields) >= 3 else None
                    # mountinfo: ... - fstype source superopts
                    sep = fields.index("-") if "-" in fields else -1
                    if sep >= 0 and sep + 1 < len(fields):
                        best = (len(mount), fields[sep + 1], mount)
        return best[1] if best else None
    except (OSError, ValueError):
        return None


def evict_file_cache(path: str) -> Dict[str, Any]:
    """Best-effort page-cache eviction for one file."""
    result = {"attempted": True, "ok": False, "error": None}
    try:
        fd = os.open(path, os.O_RDONLY)
        try:
            os.posix_fadvise(fd, 0, 0, os.POSIX_FADV_DONTNEED)
            result["ok"] = True
        finally:
            os.close(fd)
    except (OSError, AttributeError) as error:
        result["error"] = type(error).__name__
    return result

# ---------------------------------------------------------------------------
# Process tree sampling (RSS, CPU and Bun lifetime)
# ---------------------------------------------------------------------------


def _stat_fields(pid: int) -> Optional[List[str]]:
    try:
        with open(f"/proc/{pid}/stat", "r", encoding="utf-8", errors="replace") as handle:
            raw = handle.read()
    except OSError:
        return None
    close = raw.rfind(")")
    if close < 0:
        return None
    return raw[close + 2:].split()


def _comm(pid: int) -> Optional[str]:
    try:
        with open(f"/proc/{pid}/comm", "r", encoding="utf-8", errors="replace") as handle:
            return handle.read().strip()
    except OSError:
        return None


class ProcessTreeSampler(threading.Thread):
    """Polls /proc for tree RSS, per-process CPU and Bun lifecycle.

    Sampling limitations are reported with the data: a short spike can fall
    between samples; RSS double-counts shared pages per mapping; CPU ticks have
    the kernel's USER_HZ granularity (normally 10 ms).
    """

    def __init__(self, pid: int, interval: float = 0.005) -> None:
        super().__init__(name="pipeline-tree-sampler", daemon=True)
        self.root = pid
        self.interval = interval
        self._stop = threading.Event()
        self._page = os.sysconf("SC_PAGE_SIZE")
        self._hz = os.sysconf("SC_CLK_TCK")
        self.samples = 0
        self.peak_tree_rss = 0
        self.peak_tree_procs = 0
        self.max_total_cpu_ticks = 0
        self.processes: Dict[Tuple[int, str], Dict[str, Any]] = {}
        self.bun_starts = 0
        self.bun_wall_ms = 0.0
        self.bun_cpu_user_ticks = 0
        self.bun_cpu_sys_ticks = 0
        self.bun_peak_rss = 0
        self._bun_seen: set = set()

    def _children(self, pid: int) -> List[int]:
        out: List[int] = []
        task_dir = f"/proc/{pid}/task"
        try:
            tasks = os.listdir(task_dir)
        except OSError:
            return out
        for task in tasks:
            try:
                with open(f"{task_dir}/{task}/children", "r", encoding="utf-8") as handle:
                    out.extend(int(part) for part in handle.read().split())
            except (OSError, ValueError):
                continue
        return out

    def _tree(self) -> List[int]:
        seen: List[int] = []
        pending = [self.root]
        visited = set()
        while pending:
            pid = pending.pop()
            if pid in visited:
                continue
            visited.add(pid)
            if os.path.exists(f"/proc/{pid}"):
                seen.append(pid)
            pending.extend(self._children(pid))
        return seen

    def _rss(self, pid: int) -> int:
        try:
            with open(f"/proc/{pid}/statm", "r", encoding="utf-8") as handle:
                return int(handle.read().split()[1]) * self._page
        except (OSError, IndexError, ValueError):
            return 0

    def sample(self) -> None:
        now = time.monotonic()
        total_rss = 0
        total_ticks = 0
        pids = self._tree()
        for pid in pids:
            fields = _stat_fields(pid)
            if not fields or len(fields) < 20:
                continue
            try:
                state = fields[0]
                utime = int(fields[11])
                stime = int(fields[12])
                starttime = fields[19]
            except (ValueError, IndexError):
                continue
            if state == "Z":
                continue
            key = (pid, starttime)
            info = self.processes.get(key)
            if info is None:
                info = {"pid": pid, "comm": _comm(pid) or "?", "first": now,
                        "last": now, "user": 0, "sys": 0, "rss": 0,
                        "exe": None}
                self.processes[key] = info
                if info["comm"] in {"bun", "bun.exe", "bunx"}:
                    self.bun_starts += 1
                    self._bun_seen.add(key)
            info["last"] = now
            info["user"] = max(info["user"], utime)
            info["sys"] = max(info["sys"], stime)
            rss = self._rss(pid)
            info["rss"] = max(info["rss"], rss)
            total_rss += rss
            total_ticks += utime + stime
            if key in self._bun_seen or (info["comm"] in {"bun", "bun.exe", "bunx"}):
                self._bun_seen.add(key)
                self.bun_peak_rss = max(self.bun_peak_rss, rss)
        self.samples += 1
        self.peak_tree_rss = max(self.peak_tree_rss, total_rss)
        self.peak_tree_procs = max(self.peak_tree_procs, len(pids))
        self.max_total_cpu_ticks = max(self.max_total_cpu_ticks, total_ticks)

    def run(self) -> None:
        while not self._stop.is_set():
            try:
                self.sample()
            except Exception:
                pass
            self._stop.wait(self.interval)

    def stop(self) -> None:
        self._stop.set()
        self.join(timeout=2.0)
        # Final accounting for processes that exited between polls: keep the
        # highest observed CPU and the lifetime of each Bun process.
        bun_wall = 0.0
        user = 0
        sysv = 0
        for key in self._bun_seen:
            info = self.processes.get(key)
            if not info:
                continue
            bun_wall += max(0.0, (info["last"] - info["first"]) * 1000.0)
            user = max(user, info["user"])
            sysv = max(sysv, info["sys"])
            self.bun_peak_rss = max(self.bun_peak_rss, info["rss"])
        self.bun_wall_ms = round(bun_wall, 3)
        self.bun_cpu_user_ticks = user
        self.bun_cpu_sys_ticks = sysv

    def summary(self) -> Dict[str, Any]:
        tick_ms = 1000.0 / self._hz
        return {
            "interval_ms": round(self.interval * 1000.0, 3),
            "samples": self.samples,
            "peak_tree_rss_bytes": self.peak_tree_rss,
            "peak_tree_processes": self.peak_tree_procs,
            "tree_cpu_user_ms_sampled": round(
                sum(p["user"] for p in self.processes.values()) * tick_ms, 3),
            "tree_cpu_sys_ms_sampled": round(
                sum(p["sys"] for p in self.processes.values()) * tick_ms, 3),
            "process_starts": len(self.processes),
            "bun": {
                "present": self.bun_starts > 0,
                "starts": self.bun_starts,
                "observed_wall_ms": round(self.bun_wall_ms, 3),
                "cpu_user_ms_sampled": round(self.bun_cpu_user_ticks * tick_ms, 3),
                "cpu_sys_ms_sampled": round(self.bun_cpu_sys_ticks * tick_ms, 3),
                "peak_rss_bytes": self.bun_peak_rss,
                "sample_resolution_ms": round(self.interval * 1000.0, 3),
            },
            "limitations": [
                "RSS sums per-process mappings and overstates shared pages",
                "CPU uses kernel clock ticks; Bun lifetime is sampled, not exact",
                "a process shorter than one sampling interval can be missed",
            ],
        }

# ---------------------------------------------------------------------------
# Run model
# ---------------------------------------------------------------------------


@dataclass
class ProbeResult:
    attempted: bool = False
    ok: bool = False
    head_status: Optional[int] = None
    head_bytes_read: int = 0
    head_content_type: Optional[str] = None
    head_content_range: Optional[bool] = None
    head_magic_hex: Optional[str] = None
    tail_status: Optional[int] = None
    tail_bytes_read: int = 0
    tail_content_range: Optional[bool] = None
    duration_ms: Optional[float] = None
    error_class: Optional[str] = None
    verdict: str = "not_run"

    def summary(self) -> Dict[str, Any]:
        return {
            "attempted": self.attempted,
            "verdict": self.verdict,
            "head": {
                "status": self.head_status,
                "bytes_read": self.head_bytes_read,
                "content_type": self.head_content_type,
                "content_range": self.head_content_range,
                "magic_hex": self.head_magic_hex,
            },
            "tail": {
                "status": self.tail_status,
                "bytes_read": self.tail_bytes_read,
                "content_range": self.tail_content_range,
            },
            "duration_ms": (round(self.duration_ms, 3)
                            if self.duration_ms is not None else None),
            "error_class": self.error_class,
            "note": "probe happens after the timed extraction and is not part of wall_ms",
        }


@dataclass
class RunSample:
    config_id: str
    video_id: str
    repeat: int
    order: int
    ok: bool = False
    failure_class: str = "NOT_RUN"
    exit_code: int = -1
    timed_out: bool = False
    wall_ms: float = 0.0
    cpu_user_ms: float = 0.0
    cpu_sys_ms: float = 0.0
    max_rss_bytes: int = 0
    stage: Dict[str, Any] = field(default_factory=dict)
    markers: List[Dict[str, Any]] = field(default_factory=list)
    solver: Dict[str, Any] = field(default_factory=dict)
    result: Dict[str, Any] = field(default_factory=dict)
    probe: Optional[ProbeResult] = None
    host: Dict[str, Any] = field(default_factory=dict)
    cache_state: str = "warm"
    cold_eviction: Optional[Dict[str, Any]] = None
    instrumentation: Dict[str, Any] = field(default_factory=dict)
    batch: str = "matrix"
    started_utc: Optional[str] = None
    ended_utc: Optional[str] = None
    _probe_url: Optional[str] = field(default=None, repr=False)
    _probe_headers: Dict[str, str] = field(default_factory=dict, repr=False)

    def summary(self) -> Dict[str, Any]:
        return {
            "config": self.config_id,
            "video_id": self.video_id,
            "repeat": self.repeat,
            "order": self.order,
            "ok": self.ok,
            "failure_class": self.failure_class,
            "exit_code": self.exit_code,
            "timed_out": self.timed_out,
            "wall_ms": round(self.wall_ms, 3),
            "cpu_user_ms": round(self.cpu_user_ms, 3),
            "cpu_sys_ms": round(self.cpu_sys_ms, 3),
            "max_rss_bytes": self.max_rss_bytes,
            "cache_state": self.cache_state,
            "batch": self.batch,
            "started_utc": self.started_utc,
            "ended_utc": self.ended_utc,
            "cold_eviction": self.cold_eviction,
            "host": self.host,
            "stage": self.stage,
            "markers": self.markers,
            "solver": self.solver,
            "result": self.result,
            "probe": self.probe.summary() if self.probe else None,
            "instrumentation": self.instrumentation,
        }

# ---------------------------------------------------------------------------
# Command runner with a timestamped stderr reader
# ---------------------------------------------------------------------------


@dataclass
class CommandOutcome:
    exit_code: int
    timed_out: bool
    wall_ms: float
    stdout: bytes
    stderr_lines: List[Tuple[float, str]]
    cpu_user_ms: float
    cpu_sys_ms: float
    max_rss_bytes: int
    sampler: Dict[str, Any]


def _terminate_group(process: subprocess.Popen) -> None:
    try:
        os.killpg(os.getpgid(process.pid), signal.SIGTERM)
    except (ProcessLookupError, PermissionError, OSError):
        try:
            process.terminate()
        except OSError:
            pass
    try:
        process.wait(timeout=5)
        return
    except subprocess.TimeoutExpired:
        pass
    try:
        os.killpg(os.getpgid(process.pid), signal.SIGKILL)
    except (ProcessLookupError, PermissionError, OSError):
        try:
            process.kill()
        except OSError:
            pass
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        pass


def run_command(argv: Sequence[str], timeout: float, cwd: str,
                env: Dict[str, str], max_stdout: int = 32 * 1024 * 1024,
                sampler_enabled: bool = True) -> CommandOutcome:
    started = time.monotonic()
    process = subprocess.Popen(
        list(argv), stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        stdin=subprocess.DEVNULL, cwd=cwd, env=env, start_new_session=True,
        bufsize=0, text=False)
    assert process.stdout is not None and process.stderr is not None
    sampler = ProcessTreeSampler(process.pid) if sampler_enabled else None
    if sampler:
        sampler.start()
    lines: List[Tuple[float, str]] = []
    stdout_chunks: List[bytes] = []
    stdout_size = 0
    overflow = threading.Event()

    def read_stderr() -> None:
        while True:
            raw = process.stderr.readline()
            if not raw:
                return
            lines.append((time.monotonic() - started,
                          raw.decode("utf-8", "replace").rstrip("\r\n")))

    def read_stdout() -> None:
        nonlocal stdout_size
        while True:
            chunk = process.stdout.read(65536)
            if not chunk:
                return
            stdout_size += len(chunk)
            if stdout_size > max_stdout:
                overflow.set()
                _terminate_group(process)
                return
            stdout_chunks.append(chunk)

    threads = [threading.Thread(target=read_stderr, name="stderr-reader"),
               threading.Thread(target=read_stdout, name="stdout-reader")]
    for thread in threads:
        thread.start()
    timed_out = False
    try:
        process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        timed_out = True
        _terminate_group(process)
    for thread in threads:
        thread.join(timeout=10)
    if sampler:
        sampler.stop()
    wall_ms = (time.monotonic() - started) * 1000.0
    usage = resource.getrusage(resource.RUSAGE_CHILDREN)
    # RUSAGE_CHILDREN is cumulative and includes earlier runs; the caller uses
    # the process-tree sampler for per-run attribution and this value only as a
    # coarse sanity check.  Prefer wait4-style accounting when available.
    cpu_user_ms = 0.0
    cpu_sys_ms = 0.0
    max_rss = 0
    if sampler:
        s = sampler.summary()
        cpu_user_ms = s["tree_cpu_user_ms_sampled"]
        cpu_sys_ms = s["tree_cpu_sys_ms_sampled"]
        max_rss = s["peak_tree_rss_bytes"]
    if overflow.is_set():
        timed_out = True
    return CommandOutcome(
        exit_code=process.returncode if process.returncode is not None else -1,
        timed_out=timed_out,
        wall_ms=wall_ms,
        stdout=b"".join(stdout_chunks),
        stderr_lines=lines,
        cpu_user_ms=cpu_user_ms,
        cpu_sys_ms=cpu_sys_ms,
        max_rss_bytes=max_rss,
        sampler=sampler.summary() if sampler else {},
    )

# ---------------------------------------------------------------------------
# Configs
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Config:
    key: str
    binary: str                 # frozen | venv
    output: str                 # print | dump
    clients: Optional[str] = None
    skip_manifests: bool = True
    skip_webpage: bool = False
    skip_configs: bool = False
    cache: str = "off"          # off | private
    selector: str = AUDIO_FORMAT
    description: str = ""
    default_client_set: Optional[str] = None


CONFIGS: Dict[str, Config] = {
    "frozen_baseline_dump": Config(
        key="frozen_baseline_dump", binary="frozen", output="dump",
        skip_manifests=False, cache="off",
        description="current application shape: dump-single-json, default clients, HLS/DASH manifests fetched"),
    "frozen_print_skip": Config(
        key="frozen_print_skip", binary="frozen", output="print",
        skip_manifests=True, cache="off",
        description="compact --print output plus upstream skip=hls,dash"),
    "frozen_visionos_safe": Config(
        key="frozen_visionos_safe", binary="frozen", output="print",
        clients="visionos", skip_manifests=True, skip_configs=True, cache="off",
        description="anonymous visionos fast path: webpage kept, ytcfg/configs skipped"),
    "frozen_visionos_fast_unsafe": Config(
        key="frozen_visionos_fast_unsafe", binary="frozen", output="print",
        clients="visionos", skip_manifests=True, skip_configs=True,
        skip_webpage=True, cache="off",
        description="visionos-only without webpage; expected bot-block on some public videos"),
    "frozen_authed_clients": Config(
        key="frozen_authed_clients", binary="frozen", output="print",
        clients="web_embedded,tv_downgraded,web", skip_manifests=True, cache="off",
        description="cookie-capable default authenticated client order; JS challenge expected"),
    "frozen_challenge_web_embedded": Config(
        key="frozen_challenge_web_embedded", binary="frozen", output="print",
        clients="web_embedded", skip_manifests=True, cache="off",
        description="single cookie-capable client that forces the n challenge"),
    "frozen_print_skip_cache": Config(
        key="frozen_print_skip_cache", binary="frozen", output="print",
        skip_manifests=True, cache="private",
        description="frozen_print_skip with the standard upstream cache enabled"),
    "venv_print_skip": Config(
        key="venv_print_skip", binary="venv", output="print",
        skip_manifests=True, cache="off",
        description="pinned local venv equivalent of frozen_print_skip"),
    "venv_visionos_safe": Config(
        key="venv_visionos_safe", binary="venv", output="print",
        clients="visionos", skip_manifests=True, skip_configs=True, cache="off",
        description="pinned local venv equivalent of frozen_visionos_safe"),
}

MATRIX_CONFIGS: Tuple[str, ...] = (
    "frozen_baseline_dump",
    "frozen_print_skip",
    "frozen_visionos_safe",
    "frozen_authed_clients",
    "frozen_print_skip_cache",
)
VENV_CONFIGS: Tuple[str, ...] = ("frozen_print_skip", "frozen_visionos_safe",
                                 "venv_print_skip", "venv_visionos_safe")

PRINT_TEMPLATE = ("%(id)s|%(format_id)s|%(ext)s|%(acodec)s|%(abr)s|%(protocol)s|"
                  "%(http_headers)j|%(url)s")


def build_argv(config: Config, ytdlp: str, bun: str, venv_python: str,
               cache_dir: Optional[str], video_id: str) -> List[str]:
    argv = (["-m", "yt_dlp"] if config.binary == "venv" else [])
    if config.binary == "venv":
        argv = [venv_python] + argv
    else:
        argv = [ytdlp]
    argv.extend([
        "--ignore-config", "--no-plugin-dirs", "--no-remote-components",
        "--no-playlist", "--skip-download",
        "--no-js-runtimes", "--js-runtimes", f"bun:{bun}",
        "-v", "-f", config.selector,
    ])
    if config.output == "dump":
        argv.append("--dump-single-json")
    else:
        argv.extend(["--print", PRINT_TEMPLATE])
    if config.cache == "off":
        argv.append("--no-cache-dir")
    elif cache_dir:
        argv.extend(["--cache-dir", cache_dir])
    extractor_args = []
    if config.skip_manifests:
        extractor_args.append("skip=hls,dash")
    if config.clients:
        extractor_args.append(f"player_client={config.clients}")
    skipped = []
    if config.skip_webpage:
        skipped.append("webpage")
    if config.skip_configs:
        skipped.append("configs")
    if skipped:
        extractor_args.append("player_skip=" + ",".join(skipped))
    if extractor_args:
        argv.extend(["--extractor-args", "youtube:" + ";".join(extractor_args)])
    argv.append(f"https://www.youtube.com/watch?v={video_id}")
    return argv

# ---------------------------------------------------------------------------
# Stage parsing
# ---------------------------------------------------------------------------

NETWORK_EVENTS: Tuple[Tuple[str, re.Pattern], ...] = (
    ("webpage", re.compile(r": Downloading webpage$")),
    ("initial_data", re.compile(r": Downloading initial data API JSON$")),
    ("player_api", re.compile(r": Downloading .+ player API JSON$")),
    ("m3u8", re.compile(r": Downloading m3u8 information$")),
    ("dash", re.compile(r": Downloading dash information$")),
    ("player_js", re.compile(r": Downloading player \S+")),
)


def parse_markers(lines: Sequence[Tuple[float, str]]) -> Dict[str, Any]:
    markers: List[Dict[str, Any]] = []
    first_diag = None
    loaded = None
    extracting = None
    formats_sorted = None
    format_selected = None
    python_line = None
    libs_line = None
    jsr_line = None
    solve = None
    for ts, line in lines:
        if first_diag is None and "[debug] Command-line config:" in line:
            first_diag = ts
        if "[debug] Python " in line:
            python_line = ts
        if "[debug] Optional libraries:" in line:
            libs_line = ts
        if "[debug] JS runtimes:" in line:
            jsr_line = ts
        if loaded is None and re.search(r"Loaded \d+ extractors", line):
            loaded = ts
        if extracting is None and "Extracting URL:" in line:
            extracting = ts
        if formats_sorted is None and "[debug] Formats sorted by:" in line:
            formats_sorted = ts
        if format_selected is None and re.search(r": Downloading \d+ format\(s\)", line):
            format_selected = ts
        for name, pattern in NETWORK_EVENTS:
            if pattern.search(line):
                markers.append({"event": name, "at_ms": round(ts * 1000.0, 3),
                                "inclusive": True})
                break
        if "Solving JS challenges using" in line:
            solve = ts
            markers.append({"event": "solve_start", "at_ms": round(ts * 1000.0, 3),
                            "inclusive": True})
    return {
        "first_diag": first_diag,
        "python": python_line,
        "libraries": libs_line,
        "js_runtimes": jsr_line,
        "loaded": loaded,
        "extracting": extracting,
        "formats_sorted": formats_sorted,
        "format_selected": format_selected,
        "solve": solve,
        "events": markers,
    }


def build_stage(markers: Dict[str, Any], wall_ms: float,
                stdout_size: int) -> Dict[str, Any]:
    def ms(value: Optional[float]) -> Optional[float]:
        return round(value * 1000.0, 3) if value is not None else None

    first = markers["first_diag"]
    extracting = markers["extracting"]
    sorted_at = markers["formats_sorted"]
    selected = markers["format_selected"]
    solve_at = markers["solve"]
    stage: Dict[str, Any] = {
        "wall_ms": round(wall_ms, 3),
        "boot_to_first_diagnostic_ms": ms(first),
        "diagnostic_to_python_ms": (
            round((markers["python"] - first) * 1000.0, 3)
            if first is not None and markers["python"] is not None else None),
        "diagnostic_to_libraries_ms": (
            round((markers["libraries"] - first) * 1000.0, 3)
            if first is not None and markers["libraries"] is not None else None),
        "diagnostic_to_extractor_load_ms": (
            round((markers["loaded"] - first) * 1000.0, 3)
            if first is not None and markers["loaded"] is not None else None),
        "extractor_load_to_dispatch_ms": (
            round((extracting - markers["loaded"]) * 1000.0, 3)
            if extracting is not None and markers["loaded"] is not None else None),
        "extraction_ms": (
            round((sorted_at - extracting) * 1000.0, 3)
            if sorted_at is not None and extracting is not None else None),
        "format_select_ms": (
            round((selected - sorted_at) * 1000.0, 3)
            if selected is not None and sorted_at is not None else None),
        "output_ms": (
            round((wall_ms / 1000.0 - selected) * 1000.0, 3)
            if selected is not None else None),
        "stdout_bytes": stdout_size,
        "solve_marker_to_formats_sorted_ms": (
            round((sorted_at - solve_at) * 1000.0, 3)
            if sorted_at is not None and solve_at is not None else None),
        "solver_window_overlaps_extraction": solve_at is not None,
        "request_windows": [],
        "request_window_sum_ms": 0.0,
        "extraction_gaps_ms": None,
        "partition_note": ("request windows run from the request marker to the next "
                           "marker and therefore include parsing/solver startup; "
                           "boot/init/extraction partitions do not overlap"),
    }
    events = markers["events"]
    total_request = 0.0
    for index, event in enumerate(events):
        start = event["at_ms"]
        if index + 1 < len(events):
            end = events[index + 1]["at_ms"]
        elif sorted_at is not None:
            end = sorted_at * 1000.0
        else:
            end = wall_ms
        duration = max(0.0, end - start)
        stage["request_windows"].append({
            "event": event["event"],
            "duration_ms": round(duration, 3),
            "inclusive_of_parse": True,
        })
        total_request += duration
    stage["request_window_sum_ms"] = round(total_request, 3)
    if stage["extraction_ms"] is not None:
        stage["extraction_gaps_ms"] = round(
            max(0.0, stage["extraction_ms"] - total_request), 3)
    # Partition sum sanity check (boot + init + dispatch + extraction + select + output).
    parts = [stage["boot_to_first_diagnostic_ms"],
             stage["diagnostic_to_extractor_load_ms"],
             stage["extractor_load_to_dispatch_ms"],
             stage["extraction_ms"], stage["format_select_ms"], stage["output_ms"]]
    if all(p is not None for p in parts):
        stage["partition_sum_ms"] = round(sum(parts), 3)
        stage["unattributed_ms"] = round(wall_ms - sum(parts), 3)
    return stage


FAILURE_PATTERNS: Tuple[Tuple[str, str], ...] = (
    ("error solving n challenge", "JS_CHALLENGE_SOLVER_FAILED"),
    ("n challenge solving failed", "JS_CHALLENGE_SOLVER_FAILED"),
    ("no supported javascript runtime", "JS_RUNTIME_MISSING"),
    ("sign in to confirm", "LOGIN_REQUIRED"),
    ("confirm you're not a bot", "LOGIN_REQUIRED"),
    ("unable to download webpage", "WEBPAGE_FETCH_FAILED"),
    ("requested format is not available", "FORMAT_UNAVAILABLE"),
    ("no video formats found", "NO_FORMATS_FOUND"),
    ("http error 403", "HTTP_403"),
    ("http error 404", "HTTP_404"),
    ("private video", "PRIVATE_VIDEO"),
    ("video unavailable", "VIDEO_UNAVAILABLE"),
    ("members-only", "MEMBERS_ONLY"),
    ("age-restricted", "AGE_RESTRICTED"),
    ("unsupported url", "UNSUPPORTED_URL"),
    ("timed out", "TIMEOUT"),
)


def classify_failure(exit_code: int, timed_out: bool,
                     stderr_lines: Sequence[Tuple[float, str]]) -> str:
    if timed_out:
        return "TIMEOUT"
    haystack = "\n".join(line for _, line in stderr_lines).lower()
    for needle, label in FAILURE_PATTERNS:
        if needle in haystack:
            return label
    return "NONE" if exit_code == 0 else "EXTRACTOR_ERROR"

# ---------------------------------------------------------------------------
# Output parsing and media probe
# ---------------------------------------------------------------------------


def _safe_headers(headers: Any) -> Dict[str, str]:
    if not isinstance(headers, dict):
        return {}
    out = {}
    for key, value in headers.items():
        if not isinstance(key, str) or not isinstance(value, str):
            continue
        if key.lower() in {"cookie", "authorization", "proxy-authorization"}:
            continue
        out[key] = value
    return out


def parse_result(config: Config, stdout: bytes) -> Dict[str, Any]:
    result: Dict[str, Any] = {
        "parsed": False,
        "format_id": None,
        "format_count": 0,
        "ext": None,
        "acodec": None,
        "protocol": None,
        "abr": None,
        "url_present": False,
        "url_has_n": None,
        "url_has_signature": None,
        "url_expire_present": None,
        "header_names": [],
        "header_count": 0,
        "selected_format_count": 0,
    }
    info: Optional[Dict[str, Any]] = None
    if config.output == "dump":
        try:
            info = json.loads(stdout.decode("utf-8", "replace"))
        except (ValueError, UnicodeDecodeError):
            return result
    else:
        text = stdout.decode("utf-8", "replace").strip()
        lines = [line for line in text.splitlines() if line.strip()]
        if not lines:
            return result
        parts = lines[-1].split("|", 7)
        if len(parts) != 8:
            return result
        try:
            headers = json.loads(parts[6]) if parts[6] not in ("", "NA", "null") else {}
        except ValueError:
            headers = {}
        info = {
            "id": parts[0], "format_id": parts[1], "ext": parts[2],
            "acodec": parts[3], "abr": parts[4], "protocol": parts[5],
            "http_headers": headers, "url": parts[7],
        }
    if not isinstance(info, dict):
        return result
    result["format_count"] = len(info.get("formats") or [])
    requested = info.get("requested_downloads") or []
    selected = requested[0] if requested else info
    if not isinstance(selected, dict):
        selected = info
    result["selected_format_count"] = len(requested) or 1
    result["format_id"] = str(selected.get("format_id") or info.get("format_id") or "")[:48] or None
    result["ext"] = str(selected.get("ext") or info.get("ext") or "")[:12] or None
    result["acodec"] = str(selected.get("acodec") or info.get("acodec") or "")[:24] or None
    result["protocol"] = str(selected.get("protocol") or info.get("protocol") or "")[:24] or None
    abr = selected.get("abr") or info.get("abr")
    try:
        result["abr"] = round(float(abr), 3) if abr is not None else None
    except (TypeError, ValueError):
        result["abr"] = None
    url = selected.get("url") or info.get("url")
    headers = _safe_headers(selected.get("http_headers") or info.get("http_headers"))
    result["header_names"] = sorted({key.lower() for key in headers})
    result["header_count"] = len(headers)
    if isinstance(url, str) and url.startswith(("http://", "https://")):
        result["url_present"] = True
        try:
            query = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
            result["url_has_n"] = "n" in query
            result["url_has_signature"] = any(k in query for k in ("sig", "lsig", "s"))
            result["url_expire_present"] = "expire" in query
        except ValueError:
            pass
        result["_url"] = url
        result["_headers"] = headers
    result["parsed"] = True
    return result


def probe_url(url: str, headers: Dict[str, str], timeout: float = 15.0) -> ProbeResult:
    probe = ProbeResult(attempted=True)
    safe_headers = _safe_headers(headers)
    safe_headers.setdefault("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36")
    started = time.monotonic()
    total = None

    def fetch(offset: int, length: int) -> Tuple[int, bytes, Optional[str], bool, str]:
        end = offset + length - 1
        request = urllib.request.Request(
            url, headers={**safe_headers, "Range": f"bytes={offset}-{end}"}, method="GET")
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read(length)
            return (int(response.status), body,
                    (response.headers.get("Content-Type") or "")[:80] or None,
                    bool(response.headers.get("Content-Range")),
                    response.headers.get("Content-Range") or "")

    try:
        status, body, content_type, ranged, content_range = fetch(0, 65536)
        probe.head_status = status
        probe.head_bytes_read = len(body)
        probe.head_content_type = content_type
        probe.head_content_range = ranged
        probe.head_magic_hex = body[:8].hex() if body else None
        # Tail range uses the advertised object size, matching the application's
        # head/tail probe rather than a suffix range (Googlevideo rejects suffix
        # ranges with 416).
        tail = content_range.rsplit("/", 1)[-1]
        if tail.isdigit():
            total = int(tail)
        if total and total > len(body):
            tail_status, tail_body, _, tail_ranged, _ = fetch(
                max(total - 65536, len(body)), 65536)
            probe.tail_status = tail_status
            probe.tail_bytes_read = len(tail_body)
            probe.tail_content_range = tail_ranged
        elif total is not None:
            # Object is not larger than the head read; the same response is the
            # whole object, so a second range is unnecessary.
            probe.tail_status = probe.head_status
            probe.tail_bytes_read = probe.head_bytes_read
            probe.tail_content_range = probe.head_content_range
        content_type = (probe.head_content_type or "").lower()
        head_ok = probe.head_status in (200, 206) and probe.head_bytes_read > 0
        tail_ok = (probe.tail_status in (200, 206)
                   and probe.tail_bytes_read > 0)
        media_type = any(token in content_type for token in
                         ("audio/", "video/", "application/octet-stream",
                          "application/vnd", "webm"))
        probe.ok = bool(head_ok and tail_ok and media_type)
        probe.verdict = "ok" if probe.ok else "unexpected_response"
    except urllib.error.HTTPError as error:
        if probe.head_status is None:
            probe.head_status = error.code
        else:
            probe.tail_status = error.code
        probe.error_class = f"HTTP_{error.code}"
        probe.verdict = "http_error"
    except (urllib.error.URLError, TimeoutError, socket.timeout, OSError) as error:
        probe.error_class = type(error).__name__
        probe.verdict = "transport_error"
    finally:
        probe.duration_ms = (time.monotonic() - started) * 1000.0
    return probe


class _ForbiddenHandler(http.server.BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802 - http.server API
        self.send_response(403)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_HEAD(self) -> None:  # noqa: N802
        self.do_GET()

    def log_message(self, *_args: Any) -> None:
        pass


def probe_403_control() -> Dict[str, Any]:
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), _ForbiddenHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        port = server.server_address[1]
        probe = probe_url(f"http://127.0.0.1:{port}/media", {}, timeout=5.0)
        return {
            "endpoint": "loopback-403-fixture",
            "status": probe.head_status,
            "verdict": probe.verdict,
            "failed_as_expected": (not probe.ok and probe.head_status == 403),
            "error_class": probe.error_class,
            "duration_ms": round(probe.duration_ms or 0.0, 3),
            "note": "local synthetic 403; no external request and no media URL in report",
        }
    finally:
        server.shutdown()
        server.server_close()

# ---------------------------------------------------------------------------
# Tool verification
# ---------------------------------------------------------------------------


def verify_tool(path: str, expected_sha: str, expected_version: str,
                version_args: Sequence[str] = ("--version",)) -> Dict[str, Any]:
    resolved = os.path.abspath(path)
    exists = os.path.isfile(resolved)
    sha = sha256_of(resolved) if exists else None
    version = None
    error = None
    if exists:
        try:
            completed = subprocess.run([resolved, *version_args], capture_output=True,
                                       timeout=30, text=True)
            version = (completed.stdout or completed.stderr).strip().splitlines()[0][:80]
        except (OSError, subprocess.SubprocessError) as exc:
            error = type(exc).__name__
    return {
        "exists": exists,
        "executable": os.access(resolved, os.X_OK),
        "sha256": sha,
        "sha256_expected": expected_sha,
        "sha256_matches": sha == expected_sha,
        "version": version,
        "version_expected": expected_version,
        "version_matches": bool(version and expected_version in version),
        "size_bytes": os.path.getsize(resolved) if exists else None,
        "error_class": error,
    }


def parse_ejs_version(lines: Sequence[Tuple[float, str]]) -> Optional[str]:
    for _, line in lines:
        match = re.search(r"yt_dlp_ejs-([0-9][0-9A-Za-z.\-]*)", line)
        if match:
            return match.group(1)
    return None

# ---------------------------------------------------------------------------
# Run orchestration
# ---------------------------------------------------------------------------


def _env(bun: str) -> Dict[str, str]:
    env = {key: value for key, value in os.environ.items()
           if key not in {"BUN_OPTIONS", "BUN_INSTALL", "PYTHONPROFILEIMPORTTIME",
                          "PYTHONVERBOSE"}}
    env["DO_NOT_TRACK"] = "1"
    env["NO_COLOR"] = "1"
    env.setdefault("LC_ALL", "C.UTF-8")
    env.setdefault("LANG", "C.UTF-8")
    return env


def execute_sample(config: Config, video_id: str, repeat: int, order: int,
                   ytdlp: str, bun: str, venv_python: str,
                   cache_root: str, timeout: float, cold: bool = False,
                   sampler_enabled: bool = True, batch: str = "matrix") -> RunSample:
    sample = RunSample(config_id=config.key, video_id=video_id,
                       repeat=repeat, order=order,
                       cache_state="cold-binary" if cold else "warm", batch=batch)
    sample.started_utc = dt.datetime.now(dt.timezone.utc).isoformat(timespec="milliseconds")
    workdir = tempfile.mkdtemp(prefix="archivetune-profile-run-", dir="/tmp")
    try:
        if cold:
            sample.cold_eviction = {
                "yt_dlp": evict_file_cache(ytdlp),
                "bun": evict_file_cache(bun),
            }
        cache_dir = os.path.join(cache_root, config.key, video_id)
        os.makedirs(cache_dir, exist_ok=True)
        argv = build_argv(config, ytdlp, bun, venv_python,
                          cache_dir if config.cache == "private" else None, video_id)
        sample.host = {
            "loadavg_1m_before": loadavg(),
            "foreign_sidecars_before": count_foreign_sidecars(os.getpid()),
        }
        outcome = run_command(argv, timeout=timeout, cwd=workdir,
                              env=_env(bun), sampler_enabled=sampler_enabled)
        sample.host["loadavg_1m_after"] = loadavg()
        sample.host["foreign_sidecars_after"] = count_foreign_sidecars(os.getpid())
        sample.exit_code = outcome.exit_code
        sample.timed_out = outcome.timed_out
        sample.wall_ms = outcome.wall_ms
        sample.cpu_user_ms = outcome.cpu_user_ms
        sample.cpu_sys_ms = outcome.cpu_sys_ms
        sample.max_rss_bytes = outcome.max_rss_bytes
        sample.solver = {
            "bun_process": outcome.sampler.get("bun", {}),
            "tree": {
                "peak_tree_rss_bytes": outcome.sampler.get("peak_tree_rss_bytes"),
                "peak_tree_processes": outcome.sampler.get("peak_tree_processes"),
                "process_starts": outcome.sampler.get("process_starts"),
                "tree_cpu_user_ms_sampled": outcome.sampler.get("tree_cpu_user_ms_sampled"),
                "tree_cpu_sys_ms_sampled": outcome.sampler.get("tree_cpu_sys_ms_sampled"),
                "sample_resolution_ms": outcome.sampler.get("interval_ms"),
            },
        }
        markers = parse_markers(outcome.stderr_lines)
        sample.markers = markers["events"][:]
        sample.stage = build_stage(markers, outcome.wall_ms, len(outcome.stdout))
        sample.stage["ejs_version"] = parse_ejs_version(outcome.stderr_lines)
        sample.stage["js_challenge_marker"] = markers["solve"] is not None
        sample.failure_class = classify_failure(outcome.exit_code, outcome.timed_out,
                                                outcome.stderr_lines)
        sample.result = parse_result(config, outcome.stdout)
        sample.ended_utc = dt.datetime.now(dt.timezone.utc).isoformat(timespec="milliseconds")
        sample.ok = (outcome.exit_code == 0 and not outcome.timed_out
                     and bool(sample.result.get("parsed"))
                     and bool(sample.result.get("url_present")))
        if sample.ok:
            sample.failure_class = "NONE"
        # Keep the URL/headers only on private fields until the optional probe;
        # they are never serialized by RunSample.summary().
        sample._probe_url = sample.result.pop("_url", None)
        sample._probe_headers = sample.result.pop("_headers", {}) or {}
        return sample
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


def attach_probe(sample: RunSample, url: str, headers: Dict[str, str],
                 timeout: float) -> None:
    sample.probe = probe_url(url, headers, timeout=timeout)


def run_matrix(args: argparse.Namespace, ytdlp: str, bun: str,
               venv_python: str) -> Tuple[List[RunSample], Dict[str, Any]]:
    videos = list(args.videos)
    configs = list(args.configs)
    rng = random.Random(args.seed)
    runs: List[RunSample] = []
    order = 0
    probe_seen: set = set()
    cache_root = os.path.join(args.work_root, "cache")
    os.makedirs(cache_root, exist_ok=True)
    # Warm the upstream cache for the cache config before the timed blocks.
    if "frozen_print_skip_cache" in configs:
        for video in videos:
            warm = execute_sample(CONFIGS["frozen_print_skip_cache"], video, -1, -1,
                                  ytdlp, bun, venv_python, cache_root,
                                  timeout=args.timeout, sampler_enabled=False)
            warm.result.clear()
    for repeat in range(args.repeats):
        video_order = videos[:]
        rng.shuffle(video_order)
        for video in video_order:
            config_order = configs[:]
            rng.shuffle(config_order)
            for config_id in config_order:
                config = CONFIGS[config_id]
                order += 1
                sample = execute_sample(config, video, repeat, order, ytdlp, bun,
                                        venv_python, cache_root,
                                        timeout=args.timeout, batch=args.batch)
                # Probe the first success for each (video, config) after the
                # timed wall so probe latency cannot inflate extraction stats.
                if sample.ok and (video, config_id) not in probe_seen and sample._probe_url:
                    probe_seen.add((video, config_id))
                    attach_probe(sample, sample._probe_url, sample._probe_headers,
                                 args.probe_timeout)
                sample._probe_url = None
                sample._probe_headers = {}
                runs.append(sample)
                print(f"[{order:03d}] {config_id:31s} {video:11s} "
                      f"{'OK' if sample.ok else sample.failure_class:24s} "
                      f"{sample.wall_ms:8.0f} ms", file=sys.stderr, flush=True)
    return runs, {"probe_first_success_per_pair": True, "seed": args.seed,
                  "batch": args.batch}

# ---------------------------------------------------------------------------
# Summaries
# ---------------------------------------------------------------------------


def summarise_runs(runs: Sequence[RunSample]) -> Dict[str, Any]:
    out: Dict[str, Any] = {}
    configs = sorted({run.config_id for run in runs})
    videos = sorted({run.video_id for run in runs})
    for config in configs:
        per_video = {}
        pooled_success: List[float] = []
        pooled_attempt: List[float] = []
        total_attempted = 0
        total_success = 0
        for video in videos:
            selected = [run for run in runs
                        if run.config_id == config and run.video_id == video]
            if not selected:
                continue
            successes = [run for run in selected if run.ok]
            total_attempted += len(selected)
            total_success += len(successes)
            success_walls = [run.wall_ms for run in successes]
            attempt_walls = [run.wall_ms for run in selected]
            pooled_success.extend(success_walls)
            pooled_attempt.extend(attempt_walls)
            stage_keys = ("boot_to_first_diagnostic_ms",
                          "diagnostic_to_extractor_load_ms",
                          "extractor_load_to_dispatch_ms",
                          "extraction_ms", "format_select_ms", "output_ms",
                          "request_window_sum_ms", "solve_marker_to_formats_sorted_ms")
            per_video[video] = {
                "attempted": len(selected),
                "succeeded": len(successes),
                "failed": len(selected) - len(successes),
                "success_rate": round(len(successes) / len(selected), 4),
                "failure_classes": sorted({run.failure_class for run in selected
                                           if not run.ok}),
                "success_wall_ms": distribution(success_walls),
                "attempt_wall_ms_all_denominator": distribution(attempt_walls),
                "success_stage_ms": {
                    key: distribution([run.stage.get(key) for run in successes
                                       if run.stage.get(key) is not None])
                    for key in stage_keys
                },
                "solver": {
                    "runs_with_bun": sum(1 for run in successes
                                         if run.solver.get("bun_process", {}).get("present")),
                    "challenge_marker_runs": sum(1 for run in successes
                                                 if run.stage.get("js_challenge_marker")),
                    "bun_observed_wall_ms": distribution([
                        run.solver.get("bun_process", {}).get("observed_wall_ms")
                        for run in successes if run.solver.get("bun_process", {}).get("present")]),
                    "bun_cpu_user_ms": distribution([
                        run.solver.get("bun_process", {}).get("cpu_user_ms_sampled")
                        for run in successes if run.solver.get("bun_process", {}).get("present")]),
                    "bun_cpu_sys_ms": distribution([
                        run.solver.get("bun_process", {}).get("cpu_sys_ms_sampled")
                        for run in successes if run.solver.get("bun_process", {}).get("present")]),
                },
                "request_events": sorted({
                    event["event"] for run in successes for event in run.markers
                    if event["event"] != "solve_start"}),
                "request_event_counts": {
                    event: sum(1 for run in successes for item in run.markers
                               if item["event"] == event)
                    for event in ("webpage", "initial_data", "player_api", "m3u8",
                                  "dash", "player_js")
                },
                "peak_tree_rss_bytes": distribution([
                    run.max_rss_bytes for run in successes]),
                "probe": {
                    "attempted": sum(1 for run in successes if run.probe
                                     and run.probe.attempted),
                    "ok": sum(1 for run in successes if run.probe and run.probe.ok),
                    "verdicts": sorted({run.probe.verdict for run in successes
                                        if run.probe}),
                },
            }
        out[config] = {
            "description": CONFIGS[config].description,
            "attempted": total_attempted,
            "succeeded": total_success,
            "failed": total_attempted - total_success,
            "success_rate": (round(total_success / total_attempted, 4)
                             if total_attempted else None),
            "pooled_success_wall_ms": distribution(pooled_success),
            "pooled_attempt_wall_ms_all_denominator": distribution(pooled_attempt),
            "per_video": per_video,
        }
    return out


def summarise_cold_warm(runs: Sequence[RunSample]) -> Dict[str, Any]:
    """Split the cold-binary block into warm and evicted runs.

    Only yt-dlp/Bun file pages are evicted; DNS/TLS/CDN warmth remains, so this
    is a lower bound on a true cold start, not a fresh-machine measurement.
    """
    out: Dict[str, Any] = {}
    for config in sorted({run.config_id for run in runs}):
        selected = [run for run in runs if run.config_id == config]
        per_video = {}
        for video in sorted({run.video_id for run in selected}):
            rows = [run for run in selected if run.video_id == video]
            warm = [run for run in rows if run.cache_state == "warm"]
            cold = [run for run in rows if run.cache_state == "cold-binary"]
            per_video[video] = {
                "warm": {
                    "attempted": len(warm),
                    "succeeded": sum(1 for run in warm if run.ok),
                    "wall_ms": distribution([run.wall_ms for run in warm if run.ok]),
                },
                "cold_binary": {
                    "attempted": len(cold),
                    "succeeded": sum(1 for run in cold if run.ok),
                    "wall_ms": distribution([run.wall_ms for run in cold if run.ok]),
                    "eviction_attempted": sum(1 for run in cold if run.cold_eviction),
                    "eviction_ok": sum(
                        1 for run in cold if run.cold_eviction
                        and all(v.get("ok") for v in run.cold_eviction.values())),
                },
            }
        out[config] = {
            "description": CONFIGS[config].description,
            "per_video": per_video,
            "note": ("posix_fadvise(DONTNEED) on the frozen yt-dlp/Bun files; "
                     "DNS/TLS/CDN caches are not evicted, and the parent process "
                     "and OS page cache for shared libraries remain warm"),
        }
    return out


def summarise_cache(runs: Sequence[RunSample]) -> Dict[str, Any]:
    groups: Dict[str, List[RunSample]] = {}
    for run in runs:
        if run.config_id != "frozen_print_skip_cache":
            continue
        groups.setdefault(run.video_id, []).append(run)
    out: Dict[str, Any] = {}
    for video, selected in groups.items():
        ordered = sorted(selected, key=lambda run: run.repeat)
        out[video] = {
            "attempted": len(ordered),
            "succeeded": sum(1 for run in ordered if run.ok),
            "challenge_marker_by_repeat": [bool(run.stage.get("js_challenge_marker"))
                                           for run in ordered],
            "solve_window_ms_by_repeat": [run.stage.get("solve_marker_to_formats_sorted_ms")
                                          for run in ordered],
            "wall_ms_by_repeat": [round(run.wall_ms, 1) for run in ordered],
            "note": ("upstream yt-dlp 2026.08.19 writes n/sig solver results with "
                     "use_disk_cache=True but disables the preprocessed-player disk "
                     "cache; repeats are only expected to reuse the same player URL "
                     "and the same challenge string"),
        }
    return out


def cache_inventory(root: str) -> Dict[str, Any]:
    files = 0
    total = 0
    sections: Dict[str, int] = {}
    try:
        for dirpath, _dirnames, filenames in os.walk(root):
            for name in filenames:
                files += 1
                try:
                    total += os.path.getsize(os.path.join(dirpath, name))
                except OSError:
                    pass
                section = os.path.relpath(dirpath, root).split(os.sep)[0]
                sections[section] = sections.get(section, 0) + 1
    except OSError:
        pass
    return {"files": files, "bytes": total,
            "sections": {key: sections[key] for key in sorted(sections)}}

# ---------------------------------------------------------------------------
# Startup floor / cold-warm microbenchmarks
# ---------------------------------------------------------------------------


def startup_floor(ytdlp: str, bun: str, venv_python: str,
                  samples: int, cold: bool = False) -> Dict[str, Any]:
    commands = {
        "frozen_version": [ytdlp, "--version"],
        "frozen_help": [ytdlp, "--help"],
        "venv_python_pass": [venv_python, "-c", "pass"],
        "venv_import_yt_dlp": [venv_python, "-c", "import yt_dlp"],
        "venv_module_version": [venv_python, "-m", "yt_dlp", "--version"],
        "bun_version": [bun, "--version"],
        "bun_eval_noop": [bun, "-e", "0"],
    }
    out: Dict[str, Any] = {}
    for label, argv in commands.items():
        walls = []
        cpu_user = []
        cpu_sys = []
        for _ in range(samples):
            if cold:
                evict_file_cache(ytdlp)
                evict_file_cache(bun)
            before = resource.getrusage(resource.RUSAGE_CHILDREN)
            started = time.monotonic()
            try:
                subprocess.run(argv, stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL, timeout=60)
            except (OSError, subprocess.SubprocessError):
                continue
            walls.append((time.monotonic() - started) * 1000.0)
            after = resource.getrusage(resource.RUSAGE_CHILDREN)
            cpu_user.append((after.ru_utime - before.ru_utime) * 1000.0)
            cpu_sys.append((after.ru_stime - before.ru_stime) * 1000.0)
        out[label] = {
            "samples": len(walls),
            "wall_ms": distribution(walls),
            "cpu_user_ms": distribution(cpu_user),
            "cpu_sys_ms": distribution(cpu_sys),
            "cold_binary_eviction": cold,
        }
    return out


def tmpdir_sensitivity(ytdlp: str, samples: int) -> Dict[str, Any]:
    variants = {
        "default_tmp": None,
        "worktree_tmp": os.path.join(os.path.dirname(os.path.abspath(ytdlp)), "tmp-profile"),
        "shm_tmp": "/dev/shm",
    }
    out: Dict[str, Any] = {}
    for label, path in variants.items():
        if path:
            os.makedirs(path, exist_ok=True)
        env = _env("")
        if path:
            env["TMPDIR"] = path
        walls = []
        for _ in range(samples):
            started = time.monotonic()
            subprocess.run([ytdlp, "--version"], stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, env=env, timeout=60)
            walls.append((time.monotonic() - started) * 1000.0)
        out[label] = {
            "samples": len(walls),
            "wall_ms": distribution(walls),
            "tmp_fs": file_system_type(path or "/tmp"),
            "note": "PyInstaller one-file bootstrap extracts into TMPDIR on each run",
        }
    return out

# ---------------------------------------------------------------------------
# Negative controls
# ---------------------------------------------------------------------------


def make_broken_solver(bun: str, control_root: str) -> Tuple[str, str]:
    path = os.path.join(control_root, "broken-solver")
    log = os.path.join(control_root, "broken-solver-invocations.log")
    for existing in (path, log):
        try:
            os.unlink(existing)
        except OSError:
            pass
    with open(path, "w", encoding="utf-8") as handle:
        handle.write("#!/bin/sh\n")
        handle.write(f'printf "%s\\n" "$*" >> "{log}"\n')
        handle.write('if [ "$1" = "--version" ]; then\n')
        handle.write(f'  exec "{bun}" --version\n')
        handle.write("fi\n")
        handle.write('echo "ARCHIVETUNE_PROFILE_BROKEN_SOLVER" >&2\n')
        handle.write("exit 3\n")
    os.chmod(path, 0o755)
    return path, log


def count_lines(path: str) -> int:
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            return sum(1 for line in handle if line.strip())
    except OSError:
        return 0


def run_negative_controls(args: argparse.Namespace, ytdlp: str, bun: str,
                          venv_python: str) -> Dict[str, Any]:
    video = "dQw4w9WgXcQ"
    cache_root = os.path.join(args.work_root, "cache-negative")
    os.makedirs(cache_root, exist_ok=True)
    control_root = os.path.join(args.work_root, "controls")
    os.makedirs(control_root, exist_ok=True)
    # Genuine challenge with the real runtime, no cache.
    positive_runs = []
    for repeat in range(2):
        sample = execute_sample(CONFIGS["frozen_challenge_web_embedded"], video,
                                repeat, 1000 + repeat, ytdlp, bun, venv_python,
                                cache_root, args.timeout)
        positive_runs.append(sample)
    # Broken solver: answers --version, then fails every actual solver call.
    broken, log = make_broken_solver(bun, control_root)
    broken_config = Config(key="broken_solver", binary="frozen", output="print",
                           clients="web_embedded", skip_manifests=True, cache="off",
                           description="negative runtime control")
    broken_runs = []
    for repeat in range(2):
        workdir = tempfile.mkdtemp(prefix="archivetune-profile-broken-", dir="/tmp")
        try:
            argv = build_argv(broken_config, ytdlp, broken, venv_python, None, video)
            outcome = run_command(argv, timeout=args.timeout, cwd=workdir,
                                  env=_env(broken), sampler_enabled=True)
            sample = RunSample(config_id="broken_solver", video_id=video,
                               repeat=repeat, order=2000 + repeat,
                               cache_state="warm")
            sample.exit_code = outcome.exit_code
            sample.timed_out = outcome.timed_out
            sample.wall_ms = outcome.wall_ms
            sample.cpu_user_ms = outcome.cpu_user_ms
            sample.cpu_sys_ms = outcome.cpu_sys_ms
            sample.max_rss_bytes = outcome.max_rss_bytes
            sample.solver = {"bun_process": outcome.sampler.get("bun", {}),
                             "tree": outcome.sampler}
            markers = parse_markers(outcome.stderr_lines)
            sample.markers = markers["events"][:]
            sample.stage = build_stage(markers, outcome.wall_ms, len(outcome.stdout))
            sample.failure_class = classify_failure(outcome.exit_code, outcome.timed_out,
                                                    outcome.stderr_lines)
            sample.ok = False
            broken_runs.append(sample)
        finally:
            shutil.rmtree(workdir, ignore_errors=True)
    invocations = count_lines(log)
    # Missing-runtime control: no Bun at all.
    missing_config = Config(key="missing_runtime", binary="frozen", output="print",
                            clients="web_embedded", skip_manifests=True, cache="off")
    missing_runs = []
    for repeat in range(2):
        workdir = tempfile.mkdtemp(prefix="archivetune-profile-missing-", dir="/tmp")
        try:
            argv = build_argv(missing_config, ytdlp, bun, venv_python, None, video)
            # Replace the explicit runtime with no runtime at all.
            argv = [item for item in argv if item not in {"--js-runtimes", f"bun:{bun}"}]
            argv.insert(1, "--no-js-runtimes")
            outcome = run_command(argv, timeout=args.timeout, cwd=workdir,
                                  env=_env(bun), sampler_enabled=True)
            sample = RunSample(config_id="missing_runtime", video_id=video,
                               repeat=repeat, order=3000 + repeat,
                               cache_state="warm")
            sample.exit_code = outcome.exit_code
            sample.timed_out = outcome.timed_out
            sample.wall_ms = outcome.wall_ms
            sample.cpu_user_ms = outcome.cpu_user_ms
            sample.cpu_sys_ms = outcome.cpu_sys_ms
            sample.max_rss_bytes = outcome.max_rss_bytes
            sample.solver = {"bun_process": outcome.sampler.get("bun", {}),
                             "tree": outcome.sampler}
            markers = parse_markers(outcome.stderr_lines)
            sample.markers = markers["events"][:]
            sample.stage = build_stage(markers, outcome.wall_ms, len(outcome.stdout))
            sample.failure_class = classify_failure(outcome.exit_code, outcome.timed_out,
                                                    outcome.stderr_lines)
            missing_runs.append(sample)
        finally:
            shutil.rmtree(workdir, ignore_errors=True)
    # Website-skip shortcut control: known bot-block on public fixtures.
    unsafe_runs = []
    for video in ("jNQXAC9IVRw", "aqz-KE-bpKQ", "dQw4w9WgXcQ"):
        sample = execute_sample(CONFIGS["frozen_visionos_fast_unsafe"], video,
                                0, 4000, ytdlp, bun, venv_python, cache_root,
                                args.timeout)
        unsafe_runs.append(sample)
    return {
        "positive_challenge": {
            "attempted": len(positive_runs),
            "succeeded": sum(1 for run in positive_runs if run.ok),
            "challenge_marker_runs": sum(1 for run in positive_runs
                                         if run.stage.get("js_challenge_marker")),
            "bun_present_runs": sum(1 for run in positive_runs
                                    if run.solver.get("bun_process", {}).get("present")),
            "wall_ms": [round(run.wall_ms, 1) for run in positive_runs],
            "solve_window_ms": [run.stage.get("solve_marker_to_formats_sorted_ms")
                                for run in positive_runs],
            "bun_cpu_user_ms": [run.solver.get("bun_process", {}).get("cpu_user_ms_sampled")
                                for run in positive_runs],
            "bun_cpu_sys_ms": [run.solver.get("bun_process", {}).get("cpu_sys_ms_sampled")
                               for run in positive_runs],
            "ejs_versions": sorted({run.stage.get("ejs_version") for run in positive_runs
                                    if run.stage.get("ejs_version")}),
        },
        "broken_solver": {
            "attempted": len(broken_runs),
            "failed_as_expected": sum(1 for run in broken_runs
                                      if not run.ok and run.failure_class
                                      == "JS_CHALLENGE_SOLVER_FAILED"),
            "failure_classes": [run.failure_class for run in broken_runs],
            "solver_invocations": invocations,
            "wall_ms": [round(run.wall_ms, 1) for run in broken_runs],
            "note": "shim answers --version then exits 3 for the solver call",
        },
        "missing_runtime": {
            "attempted": len(missing_runs),
            "failed_as_expected": sum(1 for run in missing_runs if not run.ok),
            "failure_classes": [run.failure_class for run in missing_runs],
            "wall_ms": [round(run.wall_ms, 1) for run in missing_runs],
        },
        "visionos_without_webpage": {
            "attempted": len(unsafe_runs),
            "succeeded": sum(1 for run in unsafe_runs if run.ok),
            "failure_classes": [run.failure_class for run in unsafe_runs],
            "by_video": {run.video_id: ("OK" if run.ok else run.failure_class)
                         for run in unsafe_runs},
            "note": ("upstream-supported shortcut, but not safe as a default: "
                     "webpage/visitor data is load-bearing for some public videos"),
        },
        "probe_403_control": probe_403_control(),
        "cache_inventory_after": cache_inventory(cache_root),
    }

# ---------------------------------------------------------------------------
# API instrumentation (developer-only)
# ---------------------------------------------------------------------------


def _api_params(config: Config, bun: str) -> Dict[str, Any]:
    params: Dict[str, Any] = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "format": config.selector,
        "noplaylist": True,
        "cachedir": False,
        "js_runtimes": {"bun": {"path": bun}},
        "remote_components": set(),
        "socket_timeout": 20,
        "retries": 0,
    }
    extractor_args: Dict[str, List[str]] = {}
    if config.skip_manifests:
        extractor_args["skip"] = ["hls", "dash"]
    if config.clients:
        extractor_args["player_client"] = config.clients.split(",")
    skipped = []
    if config.skip_webpage:
        skipped.append("webpage")
    if config.skip_configs:
        skipped.append("configs")
    if skipped:
        extractor_args["player_skip"] = skipped
    if extractor_args:
        params["extractor_args"] = {"youtube": extractor_args}
    return params


def api_mode(args: argparse.Namespace, ytdlp: str, bun: str) -> int:
    """Run inside the pinned venv; import yt_dlp and profile one extraction."""
    for path in (ytdlp, bun):
        if not os.path.isfile(path):
            print(f"missing tool: {path}", file=sys.stderr)
            return 2
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    try:
        import yt_dlp  # type: ignore
    except ImportError:
        print("yt_dlp is not importable; run this mode with .tools/venv/bin/python",
              file=sys.stderr)
        return 2
    from yt_dlp import YoutubeDL  # type: ignore

    config = CONFIGS[args.config]
    video = args.video
    params = _api_params(config, bun)
    requests: List[Dict[str, Any]] = []
    original_urlopen = YoutubeDL.urlopen

    def wrapped_urlopen(self: Any, request: Any, *a: Any, **kw: Any) -> Any:
        started = time.monotonic()
        status = None
        error = None
        try:
            response = original_urlopen(self, request, *a, **kw)
            status = getattr(response, "status", None)
            return response
        except Exception as exc:
            error = type(exc).__name__
            raise
        finally:
            requests.append({
                "duration_ms": round((time.monotonic() - started) * 1000.0, 3),
                "status": status,
                "error_class": error,
            })

    YoutubeDL.urlopen = wrapped_urlopen  # type: ignore[method-assign]
    profiler = cProfile.Profile()
    ydl = YoutubeDL(params)
    started = time.monotonic()
    info: Optional[Dict[str, Any]] = None
    error_class = None
    try:
        profiler.enable()
        try:
            info = ydl.extract_info(video, download=False)
        finally:
            profiler.disable()
    except Exception as exc:
        error_class = type(exc).__name__
    total_ms = (time.monotonic() - started) * 1000.0
    YoutubeDL.urlopen = original_urlopen  # type: ignore[method-assign]
    # cProfile statistics, module/function names only.
    stream = io.StringIO()
    stats = pstats.Stats(profiler, stream=stream)
    stats.sort_stats("cumulative")
    top = []
    for func, (cc, nc, tt, ct, _callers) in sorted(
            stats.stats.items(), key=lambda item: item[1][3], reverse=True)[:25]:
        filename, line, name = func
        if "yt_dlp" not in filename and "site-packages" not in filename:
            continue
        top.append({
            "function": name[:80],
            "module": os.path.basename(filename)[:60],
            "line": line,
            "calls": nc,
            "self_ms": round(tt * 1000.0, 3),
            "cumulative_ms": round(ct * 1000.0, 3),
        })
    json_ms = None
    json_bytes = None
    selected = info
    if isinstance(info, dict):
        requested = info.get("requested_downloads") or []
        selected = requested[0] if requested else info
        sanitized = ydl.sanitize_info(info)
        started_json = time.monotonic()
        dumped = json.dumps(sanitized, default=str)
        json_ms = (time.monotonic() - started_json) * 1000.0
        json_bytes = len(dumped)
    safe_info = {}
    if isinstance(selected, dict):
        for key in ("format_id", "ext", "acodec", "protocol", "abr"):
            value = selected.get(key)
            if isinstance(value, (str, int, float)) or value is None:
                safe_info[key] = value
    report = {
        "config": config.key,
        "video_id": video,
        "ok": info is not None and error_class is None,
        "error_class": error_class,
        "total_extract_info_ms": round(total_ms, 3),
        "network_requests": requests,
        "network_request_sum_ms": round(sum(item["duration_ms"] for item in requests), 3),
        "non_request_extraction_ms": round(
            max(0.0, total_ms - sum(item["duration_ms"] for item in requests)), 3),
        "profile_top_cumulative": top,
        "json_dump_ms": round(json_ms, 3) if json_ms is not None else None,
        "json_bytes": json_bytes,
        "format": safe_info,
        "instrumentation_overhead": {
            "cProfile_enabled": True,
            "urlopen_wrapper": True,
            "note": "cProfile and the urlopen wrapper inflate absolute times; use for shares only",
        },
    }
    print(json.dumps(report, sort_keys=True))
    return 0

# ---------------------------------------------------------------------------
# CLI and report assembly
# ---------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("cli", "api"), default="cli")
    parser.add_argument("--yt-dlp", default=".tools/yt-dlp")
    parser.add_argument("--bun", default=".tools/bun")
    parser.add_argument("--venv-python", default=".tools/venv/bin/python")
    parser.add_argument("--videos", default=",".join(DEFAULT_VIDEOS))
    parser.add_argument("--configs", default=",".join(MATRIX_CONFIGS))
    parser.add_argument("--repeats", type=int, default=5)
    parser.add_argument("--seed", type=int, default=20261021)
    parser.add_argument("--timeout", type=float, default=90.0)
    parser.add_argument("--probe-timeout", type=float, default=15.0)
    parser.add_argument("--work-root", default=os.path.join("/tmp/opencode", "ytdlp-profile-work"))
    parser.add_argument("--out", default=None)
    parser.add_argument("--skip-extras", action="store_true",
                       help="skip cold-start, venv, cache, and negative-control blocks")
    parser.add_argument("--batch", default="matrix",
                       help="label stored on every run for provenance")
    parser.add_argument("--merge-base", default=None,
                       help="existing report to merge with a rerun matrix")
    parser.add_argument("--merge-exclude-repeat-below", type=int, default=0,
                       help="drop base matrix runs with repeat below this value")
    parser.add_argument("--mode-merge", action="store_true",
                       help="merge --merge-base with the current run's matrix")
    parser.add_argument("--config", default="frozen_print_skip",
                       help="config id for --mode api")
    parser.add_argument("--video", default="dQw4w9WgXcQ",
                       help="video id for --mode api")
    return parser


def merge_report(base: Dict[str, Any], rerun: Dict[str, Any],
                 exclude_below: int) -> Dict[str, Any]:
    """Replace contaminated base repeats with a clean rerun matrix.

    Base order numbers below 5000 are the timed matrix; 5000+ are cold, venv
    and coverage extras that ran after the contaminated interval.  Base matrix
    rows with repeat < exclude_below are discarded as a whole.  No failed row
    is dropped: failures remain in attempted/succeeded denominators.
    """
    base_matrix: List[Dict[str, Any]] = []
    base_extras: List[Dict[str, Any]] = []
    for run in base.get("runs", []):
        order = int(run.get("order", 0))
        if order >= 5000:
            row = dict(run)
            if order < 6000:
                row["batch"] = "cold"
            elif order < 7000:
                row["batch"] = "venv"
            else:
                row["batch"] = "coverage"
            base_extras.append(row)
        elif int(run.get("repeat", 0)) >= exclude_below:
            row = dict(run)
            row["batch"] = "matrix1-clean"
            base_matrix.append(row)
    rerun_matrix = []
    for run in rerun.get("runs", []):
        row = dict(run)
        row["batch"] = row.get("batch") or "rerun-clean"
        rerun_matrix.append(row)
    merged = dict(rerun)
    merged["runs"] = base_matrix + rerun_matrix + base_extras
    merged["generated_at"] = base.get("generated_at") or rerun.get("generated_at")
    for key in ("toolchain", "environment", "methodology", "videos", "configs",
                "extras", "cold_warm_summary", "venv_comparison_summary",
                "coverage_summary", "cache_experiment", "limitations"):
        if key in base:
            merged[key] = base[key]
    merged["summaries"] = summary_from_run_dicts(base_matrix + rerun_matrix)
    merged["matrix"] = {
        "source": "clean rerun block plus uncontaminated base repeats",
        "seed": rerun.get("matrix", {}).get("seed"),
        "rerun_batch": "rerun-clean",
        "base_batch": "matrix1-clean",
        "excluded_base_repeats_below": exclude_below,
        "quantile_note": quantile_method(),
    }
    merged["exclusions"] = [{
        "window_utc": "2026-10-21T11:11:05.750Z..2026-10-21T11:11:39.236Z",
        "reason": "worker accidental live plumbing check (15 YouTube extractions, 13 Bun invocations); worker discarded its numbers",
        "action": ("conservatively excluded base matrix repeats 0 and 1 because original "
                   "rows lacked per-run UTC timestamps; clean rerun supplies those repeats"),
        "failed_rows_kept": True,
    }]
    return merged


def summary_from_run_dicts(runs: Sequence[Dict[str, Any]]) -> Dict[str, Any]:
    """Summarize already-serialized rows without reconstructing RunSample."""
    # Rebuild minimal objects; this keeps the same quantile/denominator logic.
    objects: List[RunSample] = []
    for row in runs:
        sample = RunSample(config_id=row.get("config", ""),
                           video_id=row.get("video_id", ""),
                           repeat=int(row.get("repeat", 0)),
                           order=int(row.get("order", 0)))
        sample.ok = bool(row.get("ok"))
        sample.failure_class = row.get("failure_class", "NOT_RUN")
        sample.wall_ms = float(row.get("wall_ms") or 0.0)
        sample.stage = row.get("stage") or {}
        sample.solver = row.get("solver") or {}
        sample.markers = row.get("markers") or []
        sample.max_rss_bytes = int(row.get("max_rss_bytes") or 0)
        probe = row.get("probe")
        if probe:
            sample.probe = ProbeResult(
                attempted=bool(probe.get("attempted")),
                ok=probe.get("verdict") == "ok",
                head_status=(probe.get("head") or {}).get("status"),
                head_bytes_read=int((probe.get("head") or {}).get("bytes_read") or 0),
                head_content_type=(probe.get("head") or {}).get("content_type"),
                head_content_range=(probe.get("head") or {}).get("content_range"),
                head_magic_hex=(probe.get("head") or {}).get("magic_hex"),
                tail_status=(probe.get("tail") or {}).get("status"),
                tail_bytes_read=int((probe.get("tail") or {}).get("bytes_read") or 0),
                tail_content_range=(probe.get("tail") or {}).get("content_range"),
                duration_ms=probe.get("duration_ms"),
                error_class=probe.get("error_class"),
                verdict=probe.get("verdict", "not_run"),
            )
        objects.append(sample)
    return summarise_runs(objects)


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    if args.mode == "api":
        return api_mode(args, os.path.abspath(args.yt_dlp), os.path.abspath(args.bun))
    os.makedirs(args.work_root, exist_ok=True)
    args.videos = tuple(item.strip() for item in args.videos.split(",") if item.strip())
    args.configs = tuple(item.strip() for item in args.configs.split(",") if item.strip())
    for config in args.configs:
        if config not in CONFIGS:
            print(f"unknown config {config}", file=sys.stderr)
            return 2
    ytdlp = os.path.abspath(args.yt_dlp)
    bun = os.path.abspath(args.bun)
    venv_python = os.path.abspath(args.venv_python)
    toolchain = {
        "yt_dlp": verify_tool(ytdlp, YTDLP_SHA256, YTDLP_VERSION),
        "bun": verify_tool(bun, BUN_SHA256, BUN_VERSION),
        "local_venv": {
            "python": venv_python,
            "exists": os.path.isfile(venv_python),
            "yt_dlp_version": None,
            "ejs_version": None,
        },
    }
    if os.path.isfile(venv_python):
        try:
            completed = subprocess.run(
                [venv_python, "-c", "import yt_dlp, importlib.metadata as m; "
                 "print(yt_dlp.version.__version__); print(m.version('yt-dlp-ejs'))"],
                capture_output=True, text=True, timeout=30)
            lines = completed.stdout.strip().splitlines()
            toolchain["local_venv"]["yt_dlp_version"] = lines[0] if lines else None
            toolchain["local_venv"]["ejs_version"] = lines[1] if len(lines) > 1 else None
        except (OSError, subprocess.SubprocessError):
            pass
    if not (toolchain["yt_dlp"]["sha256_matches"] and toolchain["bun"]["sha256_matches"]):
        print("pinned sidecar verification failed", file=sys.stderr)
        return 2
    # Warm-up once so first-run PyInstaller/cache effects are not attributed to
    # a named config; all measured runs are still new processes.
    subprocess.run([ytdlp, "--version"], capture_output=True, timeout=60)

    started_at = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    runs, matrix_meta = run_matrix(args, ytdlp, bun, venv_python)
    summaries = summarise_runs(runs)
    cold_runs: List[RunSample] = []
    venv_runs: List[RunSample] = []
    extras: Dict[str, Any] = {}
    if not args.skip_extras:
        # Cold binary cache experiment, interleaved with warm repeats.
        cold_videos = args.videos[:2]
        for video in cold_videos:
            for cold in (False, True, False, True, False):
                cold_runs.append(execute_sample(
                    CONFIGS["frozen_print_skip"], video, 0, 5000 + len(cold_runs),
                    ytdlp, bun, venv_python,
                    os.path.join(args.work_root, "cache"), args.timeout, cold=cold,
                    batch="cold"))
        # Venv comparison on a subset, interleaved.
        for repeat in range(min(args.repeats, 5)):
            for video in args.videos[:3]:
                order = list(VENV_CONFIGS)
                random.Random(args.seed + repeat + hash(video) % 1000).shuffle(order)
                for config_id in order:
                    venv_runs.append(execute_sample(
                        CONFIGS[config_id], video, repeat, 6000 + len(venv_runs),
                        ytdlp, bun, venv_python,
                        os.path.join(args.work_root, "cache"), args.timeout,
                        batch="venv"))
        extras["startup_floor"] = startup_floor(
            ytdlp, bun, venv_python, samples=7)
        extras["startup_floor_cold_binary"] = startup_floor(
            ytdlp, bun, venv_python, samples=5, cold=True)
        extras["tmpdir_sensitivity"] = tmpdir_sensitivity(ytdlp, samples=5)
        extras["negative_controls"] = run_negative_controls(args, ytdlp, bun, venv_python)
        extras["cache_inventory"] = cache_inventory(
            os.path.join(args.work_root, "cache"))
    coverage_runs: List[RunSample] = []
    if not args.skip_extras:
        for video in EXTRA_VIDEOS:
            for config_id in ("frozen_visionos_safe", "frozen_print_skip"):
                coverage_runs.append(execute_sample(
                    CONFIGS[config_id], video, 0, 7000 + len(coverage_runs),
                    ytdlp, bun, venv_python,
                    os.path.join(args.work_root, "cache"), args.timeout,
                    batch="coverage"))
    all_runs = runs + cold_runs + venv_runs + coverage_runs
    report = {
        "schema_version": 1,
        "kind": "linux-ytdlp-pipeline-stage-profile",
        "generated_at": started_at,
        "scope": {
            "application_jvm": False,
            "note": ("developer-only pipeline profile. It does not include the JVM, "
                     "libVLC, account/session acquisition, or the parent's whole-application "
                     "benchmark. Anonymous public requests only."),
            "no_persistent_worker": True,
        },
        "toolchain": toolchain,
        "environment": {
            "platform": sys.platform,
            "machine": os.uname().machine,
            "kernel": os.uname().release,
            "python_for_harness": sys.version.split()[0],
            "logical_cpus": os.cpu_count(),
            "loadavg_1m_start": loadavg(),
            "tmp_fs": file_system_type("/tmp"),
            "worktree_fs": file_system_type(os.getcwd()),
            "host_note": ("timed slot was exclusive for this agent; other host workloads "
                          "may still affect network and CPU. Per-run load averages and "
                          "foreign sidecar counts are recorded."),
        },
        "methodology": {
            "quantiles": quantile_method(),
            "interleaving": ("configs are shuffled within each video/repeat block and "
                             "videos are shuffled per repeat; a fixed seed is recorded"),
            "cold_warm": {
                "warm": "new process, warm OS page cache, default /tmp tmpfs",
                "cold-binary": ("new process after best-effort posix_fadvise DONTNEED on "
                                "yt-dlp and bun; DNS/TLS/CDN state is not evicted"),
                "cache-off": "--no-cache-dir",
                "cache-private": "standard upstream cache in a private directory",
                "object-warm": "only the --mode api instrumentation reuses one YoutubeDL object",
            },
            "stage_source": ("pinned yt-dlp verbose markers timestamped at the harness pipe; "
                             "request windows are inclusive of following parse work"),
            "measured_vs_inferred": {
                "measured": ("wall clock, process-tree sampled CPU/RSS, marker intervals, "
                             "HTTP statuses/bytes, Bun lifetime observations"),
                "inferred": ("PyInstaller/frozen bootstrap portion of boot, network vs parse "
                             "inside a request window, and any whole-app extrapolation"),
                "instrumentation_overhead": ("verbose logging, line timestamping, 5 ms /proc "
                                             "sampling, and cProfile are included; see measured "
                                             "overhead fields and limitations"),
            },
            "preferred_quantiles": ("p50/p90/p95/max over successful runs; attempted, succeeded, "
                                    "failed and success_rate are always reported"),
        },
        "configs": {key: {
            "description": config.description,
            "output": config.output,
            "clients": config.clients,
            "skip_hls_dash": config.skip_manifests,
            "skip_webpage": config.skip_webpage,
            "skip_configs": config.skip_configs,
            "cache": config.cache,
        } for key, config in CONFIGS.items() if key in args.configs or key in
            {"frozen_print_skip", "frozen_visionos_safe", "venv_print_skip",
             "venv_visionos_safe", "frozen_challenge_web_embedded",
             "frozen_visionos_fast_unsafe"}},
        "videos": {video: {"note": VIDEO_NOTES.get(video, "public fixture"),
                           "anonymous": True}
                   for video in sorted(set(args.videos) | set(EXTRA_VIDEOS))},
        "matrix": matrix_meta,
        "runs": [run.summary() for run in all_runs],
        "summaries": summaries,
        "cold_warm_summary": summarise_cold_warm(cold_runs) if cold_runs else {},
        "venv_comparison_summary": summarise_runs(venv_runs) if venv_runs else {},
        "coverage_summary": summarise_runs(coverage_runs) if coverage_runs else {},
        "cache_experiment": summarise_cache(runs),
        "extras": extras,
        "limitations": [
            "No cookies, account context, age-restricted, members-only or premium video.",
            "No JVM/application scheduling, libVLC playback, or parent whole-app budget.",
            "YouTube responses, bot checks, player JS and CDN latency vary by request.",
            "Visionos has no cookie support upstream; it cannot stand in for authenticated extraction.",
            "The cache experiment only exercises upstream-supported n/sig solver reuse; no player cache is enabled.",
            "A 5-10 sample block gives wide p95 uncertainty; p95 is near max for small n.",
            "Probe success proves a ranged head/tail response, not audible playback.",
        ],
    }
    if args.mode_merge and args.merge_base:
        with open(args.merge_base, "r", encoding="utf-8") as handle:
            base = json.load(handle)
        report = merge_report(base, report, args.merge_exclude_repeat_below)
    text = json.dumps(report, indent=2, sort_keys=True)
    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)) or ".", exist_ok=True)
        with open(args.out, "w", encoding="utf-8") as handle:
            handle.write(text + "\n")
        print(f"wrote {args.out} ({len(text)} bytes)", file=sys.stderr)
    else:
        print(text)
    failures = [run for run in all_runs if not run.ok]
    return 0 if not failures else 0  # failures are data, not harness failure


if __name__ == "__main__":
    raise SystemExit(main())

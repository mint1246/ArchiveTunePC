#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ArchiveTune yt-dlp JS-runtime comparison benchmark (developer-only harness).

This script answers one narrow question with measurements instead of engine
claims: **is QuickJS-NG the cheapest JS runtime for the yt-dlp EJS challenge
solver, or is a heavier runtime cheaper in wall time / CPU / peak RSS?**

What it does
------------
1. Verifies the pinned upstream tools (yt-dlp 2026.08.19, QuickJS-NG 0.17.0) by
   SHA-256 and records the version/size/SHA-256 of every candidate JS runtime.
2. For each candidate runtime it runs *cold* extractions (fresh yt-dlp process,
   private HOME/XDG/TMPDIR, no cookie file, no user profile) against
      * a **challenged** video with a player client that forces the YouTube
        ``n`` challenge, so the JS runtime is load-bearing, and
      * an **unchallenged control** video, and
      * a **same-video control** (challenged video, default player client) so
        the challenge cost can be separated from the per-video network cost.
3. Samples the whole process tree from ``/proc`` during every extraction and
   records the maximum *simultaneous* RSS total, the process count, the
   per-process breakdown at the peak sample and the CPU time of the whole
   child tree (which includes the embedded CPython of the frozen yt-dlp).
4. Proves the runtime really did the work: a shim wrapper counts the runtime
   invocations, the yt-dlp diagnostics are matched for the challenge-solver
   lines, and a deliberately broken runtime is used as a negative control that
   must make the challenged extraction fail.
5. Probes the resolved media URL with a short ranged GET so a "successful"
   extraction is backed by an actual audio byte read.

Safety contract (identical to scripts/benchmark-ytdlp.py)
---------------------------------------------------------
* Cookies are never requested, never read and never written. No account
  profile, no user configuration and no system configuration is touched.
* Signed media URLs are never printed and never written to disk. The yt-dlp
  ``--dump-single-json`` payload is parsed in memory and discarded; only safe
  derived fields (ids, codecs, protocols, timings, byte counts) are stored.
* Every runtime is executed with a private HOME/XDG_*/TMPDIR, so runtime
  caches cannot touch the user's profile.

Usage
-----
    scripts/benchmark-ytdlp-runtimes.py \
        --yt-dlp  /path/to/yt-dlp \
        --qjs     /path/to/qjs \
        --node    /usr/bin/node \
        --bun     /path/to/bun \
        --repeats 3 \
        --out-dir build/reports/ytdlp-runtimes

A second bun build can be measured side by side with ``--bun-new`` (for
example the Rust-rewrite 1.4.x line). It is measured with the *unchanged*
yt-dlp CLI: no version gate is bypassed and no wrapper pretends to be a
different version. The report records what yt-dlp decided about the runtime
version (``per_runtime.<key>.version_gate``) next to the measurements.

Exit code is 0 only when every runtime completed its challenged extraction and
the negative control behaved as expected.
"""

from __future__ import annotations

import argparse
import csv
import importlib.util
import json
import os
import platform
import statistics
import re
import resource
import shutil
import signal
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Sequence, Tuple

# ---------------------------------------------------------------------------
# Reuse the existing, already reviewed yt-dlp benchmark harness
# ---------------------------------------------------------------------------

_BASE_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "benchmark-ytdlp.py")


def _load_base_module():
    """Import ``benchmark-ytdlp.py`` (hyphenated name) as a module."""
    spec = importlib.util.spec_from_file_location("archivetune_benchmark_ytdlp", _BASE_PATH)
    if spec is None or spec.loader is None:  # pragma: no cover - defensive
        raise RuntimeError("cannot load %s" % _BASE_PATH)
    module = importlib.util.module_from_spec(spec)
    # Register before executing: dataclasses resolve annotations through
    # sys.modules[cls.__module__].
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


BASE = _load_base_module()

REDACT = BASE.redact
HERMETIC_ENV = BASE.hermetic_env
NORMALIZE_VIDEO = BASE.normalize_video
CLASSIFY_FAILURE = BASE.classify_failure
PROBE_URL = BASE.probe_url
SHA256_OF = BASE.sha256_of

# Production flag set of the ArchiveTune resolver (YtDlpStreamResolver.kt):
# --ignore-config --no-plugin-dirs --no-remote-components --no-js-runtimes
# --js-runtimes quickjs:<path> --no-cache-dir. The base benchmark's flag tuple
# is reused and --no-cache-dir is added so no extraction can reuse a cached
# player response from an earlier request.
RESOLVER_FLAGS: Tuple[str, ...] = tuple(BASE.RESOLVER_FLAGS) + ("--no-cache-dir",)

YTDLP_VERSION = "2026.08.19"
YTDLP_SHA256 = "58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a"
QJS_VERSION = "0.17.0"
QJS_SHA256 = "0bfc02511a9f549c28b53880d988fc7cd5d361e90c5e8afdfcd7dc6774ceace5"

# Player client that is actually challenged by YouTube's "n" challenge.
CHALLENGED_CLIENT = "web_embedded"

# Primary upstream sources for the runtime facts quoted in the report.
UPSTREAM_SOURCES = [
    {
        "title": "yt-dlp 2026.08.19 - yt_dlp/utils/_jsruntime.py (minimum supported runtime versions)",
        "url": "https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/yt_dlp/utils/_jsruntime.py",
        "fact": "deno >= 2.3.0, node >= 22.0.0, bun >= 1.2.11, quickjs >= 2023-12-9, "
                "all quickjs-ng versions supported.",
    },
    {
        "title": "yt-dlp 2026.08.19 - yt_dlp/extractor/youtube/jsc/_builtin/{deno,node,quickjs,bun}.py",
        "url": "https://github.com/yt-dlp/yt-dlp/tree/2026.08.19/yt_dlp/extractor/youtube/jsc/_builtin",
        "fact": "Provider preference: deno 1000, node 900, quickjs 850, bun 800. Bun is capped at "
                "1.3.14 and deprecated. quickjs-ng below 0.12.0 is missing optimizations.",
    },
    {
        "title": "yt-dlp wiki - EJS: External JS Scripts Setup Guide",
        "url": "https://github.com/yt-dlp/yt-dlp/wiki/EJS",
        "fact": "Deno is the recommended runtime and is enabled by default; bun support is "
                "deprecated and versions after 1.3.14 are unsupported; quickjs-ng below "
                "0.12.0 can take minutes per challenge.",
    },
    {
        "title": "yt-dlp 2026.08.19 - yt_dlp/extractor/youtube/jsc/README.md",
        "url": "https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/yt_dlp/extractor/youtube/jsc/README.md",
        "fact": "Public JS Challenge Provider API and the -v jsc_trace debugging switch.",
    },
    {
        "title": "yt-dlp 2026.08.19 - yt_dlp/extractor/youtube/jsc/_builtin/bun.py (bun version gate)",
        "url": "https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/yt_dlp/extractor/youtube/jsc/_builtin/bun.py",
        "fact": "Source of truth for the bun gate, quoted verbatim: "
                "_BUN_MAX_SUPPORTED_VERSION = (1, 3, 14); "
                "is_unsupported_version = self.runtime_info.version_tuple > self._BUN_MAX_SUPPORTED_VERSION; "
                "the unsupported branch only logs 'bun version {v} is not supported! {max} is the last supported "
                "bun version. DO NOT open a bug report even if you encounter any errors!' and then continues into "
                "the same bun invocation; the supported branch logs 'bun support has been deprecated.'. "
                "The gate is therefore a warning, not a refusal to run.",
    },
    {
        "title": "yt-dlp 2026.08.19 - yt_dlp/utils/_jsruntime.py (BunJsRuntime.MIN_SUPPORTED_VERSION)",
        "url": "https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/yt_dlp/utils/_jsruntime.py",
        "fact": "BunJsRuntime.MIN_SUPPORTED_VERSION = (1, 2, 11) and JsRuntimeInfo.supported = "
                "vt >= MIN_SUPPORTED_VERSION. This is the only value that makes a runtime unavailable "
                "(EJSBaseJCP.runtime_info returns None when not runtime.info.supported), so a bun newer "
                "than 1.3.14 stays available to the bun challenge provider.",
    },
    {
        "title": "Bun v1.4 release blog (Rust rewrite)",
        "url": "https://bun.com/blog/bun-v1.4",
        "fact": "Primary upstream statement: 'We rewrote Bun in Rust - and this is the first release'. "
                "Bun 1.4 is therefore the Rust rewrite of the runtime.",
    },
]

# Public upstream URLs that are allowed to survive the harness' URL redaction
# inside the version-gate messages (they are documentation links, not signed
# media URLs).
_KNOWN_PUBLIC_UPSTREAM_URLS = ("https://github.com/yt-dlp/yt-dlp/issues/16766",)

# yt-dlp's own bun version gate messages (yt_dlp/extractor/youtube/jsc/_builtin/bun.py @ 2026.08.19).
_BUN_GATE_UNSUPPORTED_RE = re.compile(
    r"bun version [\d.]+ is not supported!.*last supported bun version", re.I)
_BUN_GATE_DEPRECATED_RE = re.compile(r"bun support has been deprecated", re.I)

# ---------------------------------------------------------------------------
# Runtime descriptions
# ---------------------------------------------------------------------------

# Minimum supported versions, from the pinned yt-dlp source
# (yt_dlp/utils/_jsruntime.py, tag 2026.08.19) and the upstream EJS wiki.
RUNTIME_MIN_VERSION: Dict[str, str] = {
    "deno": "2.3.0",
    "node": "22.0.0",
    "quickjs": "2023.12.9 (quickjs-ng: any version)",
    "bun": "1.2.11",
}


@dataclass
class RuntimeSpec:
    """One candidate JS runtime."""

    key: str                       # harness key, e.g. "quickjs-ng"
    ytdlp_name: str                # yt-dlp --js-runtimes name
    path: str
    version: Optional[str] = None
    version_source: Optional[str] = None
    sha256: Optional[str] = None
    size_bytes: Optional[int] = None
    supported_by_ytdlp: Optional[bool] = None
    above_pinned_ytdlp_ceiling: Optional[bool] = None
    upstream_status: str = "unknown"
    notes: List[str] = field(default_factory=list)
    error: Optional[str] = None

    def summary(self) -> Dict[str, Any]:
        return {
            "key": self.key,
            "ytdlp_runtime_name": self.ytdlp_name,
            "path": self.path,
            "version": self.version,
            "version_source": self.version_source,
            "sha256": self.sha256,
            "size_bytes": self.size_bytes,
            "size_mib": round(self.size_bytes / (1024 * 1024), 2) if self.size_bytes else None,
            "supported_by_ytdlp": self.supported_by_ytdlp,
            "above_pinned_ytdlp_ceiling": self.above_pinned_ytdlp_ceiling,
            "upstream_min_supported_version": RUNTIME_MIN_VERSION.get(self.ytdlp_name),
            "upstream_status": self.upstream_status,
            "notes": list(self.notes),
            "error": self.error,
        }


# ---------------------------------------------------------------------------
# Process tree sampling with CPU + per-process attribution
# ---------------------------------------------------------------------------


class RuntimeTreeSampler(BASE.ProcessTreeRssSampler):
    """Extends the base sampler with CPU ticks and per-process attribution.

    The base class already provides the simultaneous process-tree RSS total
    (and documents its own limitations: polling can miss short spikes and
    shared pages are counted once per mapping process). This subclass adds:
      * per-process ``comm``/RSS at the peak sample,
      * the set of every executable name observed in the tree,
      * the maximum user+system CPU ticks seen per process.
    """

    def __init__(self, pid: int, interval: float = 0.02) -> None:
        super().__init__(pid, interval=interval)
        self.peak_breakdown: List[Dict[str, Any]] = []
        self.observed_comm: Dict[str, int] = {}
        self.pid_state: Dict[int, Dict[str, Any]] = {}
        self.runtime_process_observed = False
        self.runtime_comm_names = ("qjs", "node", "bun", "deno")

    # -- helpers -----------------------------------------------------------
    def _stat_fields(self, pid: int) -> Optional[List[str]]:
        try:
            with open("/proc/%d/stat" % pid, "r") as handle:
                data = handle.read()
        except OSError:
            return None
        # comm may contain spaces/parens; split after the last ')'
        close = data.rfind(")")
        if close < 0:
            return None
        tail = data[close + 2:].split()
        # tail[0] is state (field 3); utime is field 14 -> tail[11], stime field 15 -> tail[12]
        if len(tail) < 13:
            return None
        try:
            return [int(tail[11]), int(tail[12])]
        except ValueError:
            return None

    def _comm(self, pid: int) -> str:
        try:
            with open("/proc/%d/comm" % pid, "r") as handle:
                return handle.read().strip()
        except OSError:
            return "unknown"

    # -- sampling ----------------------------------------------------------
    def sample(self) -> int:
        pids = self._tree()
        breakdown: List[Dict[str, Any]] = []
        total = 0
        for pid in pids:
            rss = self._rss_of(pid)
            total += rss
            comm = self._comm(pid)
            breakdown.append({"pid": pid, "comm": comm, "rss_bytes": rss})
            self.observed_comm[comm] = self.observed_comm.get(comm, 0) + 1
            if comm in self.runtime_comm_names:
                self.runtime_process_observed = True
            state = self.pid_state.setdefault(pid, {"comm": comm, "cpu_ticks": 0, "rss_bytes": 0})
            state["comm"] = comm
            state["rss_bytes"] = max(state["rss_bytes"], rss)
            ticks = self._stat_fields(pid)
            if ticks is not None:
                cpu = ticks[0] + ticks[1]
                if cpu > state["cpu_ticks"]:
                    state["cpu_ticks"] = cpu
        self.samples += 1
        if total > self.peak_bytes:
            self.peak_bytes = total
            self.peak_processes = len(pids)
            self.peak_breakdown = breakdown
        return total

    # -- aggregation -------------------------------------------------------
    def cpu_ticks_by_comm(self) -> Dict[str, int]:
        """Sum the highest observed CPU ticks of every distinct PID, per comm.

        Each PID is counted once, so the frozen yt-dlp bootloader and its
        embedded CPython worker are two separate contributions even though
        both report the comm ``yt-dlp``.
        """
        totals: Dict[str, int] = {}
        for state in self.pid_state.values():
            comm = state["comm"]
            totals[comm] = totals.get(comm, 0) + int(state["cpu_ticks"])
        return totals

    def distinct_processes(self) -> List[Dict[str, Any]]:
        return [
            {"pid": pid, "comm": state["comm"], "cpu_ticks": state["cpu_ticks"],
             "max_rss_bytes": state["rss_bytes"]}
            for pid, state in sorted(self.pid_state.items())
        ]


# ---------------------------------------------------------------------------
# Subprocess execution with timeout, CPU accounting and cleanup
# ---------------------------------------------------------------------------


@dataclass
class RuntimeRun:
    exit_code: int = -1
    stdout: bytes = b""
    stderr: str = ""
    timed_out: bool = False
    wall_ms: int = 0
    cpu_user_s: float = 0.0
    cpu_sys_s: float = 0.0
    peak_tree_rss_bytes: int = 0
    peak_process_count: int = 0
    peak_breakdown: List[Dict[str, Any]] = field(default_factory=list)
    observed_comm: Dict[str, int] = field(default_factory=dict)
    cpu_ticks_by_comm: Dict[str, int] = field(default_factory=dict)
    distinct_processes: List[Dict[str, Any]] = field(default_factory=list)
    runtime_process_observed: bool = False
    rss_samples: int = 0


def _child_cpu() -> Tuple[float, float]:
    usage = resource.getrusage(resource.RUSAGE_CHILDREN)
    return usage.ru_utime, usage.ru_stime


def run_measured(argv: Sequence[str], timeout: float, env: Dict[str, str],
                 sample_interval: float = 0.02) -> RuntimeRun:
    """Run ``argv`` in its own process group and measure wall/CPU/RSS."""
    started = time.monotonic()
    cpu_user_before, cpu_sys_before = _child_cpu()
    process = subprocess.Popen(
        list(argv),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        stdin=subprocess.DEVNULL,
        start_new_session=True,
        env=env,
    )
    sampler = RuntimeTreeSampler(process.pid, interval=sample_interval)
    sampler.start()
    timed_out = False
    try:
        stdout, stderr_bytes = process.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        timed_out = True
        BASE._terminate_group(process)
        try:
            stdout, stderr_bytes = process.communicate(timeout=15)
        except subprocess.TimeoutExpired:  # pragma: no cover - defensive
            process.kill()
            stdout, stderr_bytes = process.communicate()
    peak = sampler.stop()
    wall_ms = int((time.monotonic() - started) * 1000)
    cpu_user_after, cpu_sys_after = _child_cpu()
    return RuntimeRun(
        exit_code=process.returncode if process.returncode is not None else -1,
        stdout=stdout or b"",
        stderr=(stderr_bytes or b"").decode("utf-8", "replace"),
        timed_out=timed_out,
        wall_ms=wall_ms,
        cpu_user_s=round(cpu_user_after - cpu_user_before, 3),
        cpu_sys_s=round(cpu_sys_after - cpu_sys_before, 3),
        peak_tree_rss_bytes=peak,
        peak_process_count=sampler.peak_processes,
        peak_breakdown=sampler.peak_breakdown,
        observed_comm=dict(sampler.observed_comm),
        cpu_ticks_by_comm=sampler.cpu_ticks_by_comm(),
        distinct_processes=sampler.distinct_processes(),
        runtime_process_observed=sampler.runtime_process_observed,
        rss_samples=sampler.samples,
    )


# ---------------------------------------------------------------------------
# Runtime shims (invocation evidence + negative control)
# ---------------------------------------------------------------------------


def make_counting_shim(real_binary: str, log_path: str) -> str:
    """A wrapper that logs each invocation and delegates to the real binary."""
    directory = tempfile.mkdtemp(prefix="archivetune-runtime-shim-")
    shim = os.path.join(directory, "runtime-shim")
    counter = os.path.abspath(log_path)
    with open(shim, "w") as handle:
        handle.write(
            "#!/bin/sh\n"
            'printf "%%s\\n" "$*" >> "%s"\n'
            'exec "%s" "$@"\n' % (counter, real_binary)
        )
    os.chmod(shim, 0o755)
    return shim


def make_broken_runtime(log_path: str) -> str:
    """A runtime stand-in that always fails, used as a negative control."""
    directory = tempfile.mkdtemp(prefix="archivetune-runtime-broken-")
    broken = os.path.join(directory, "runtime-broken")
    counter = os.path.abspath(log_path)
    with open(broken, "w") as handle:
        handle.write(
            "#!/bin/sh\n"
            'printf "%%s\\n" "$*" >> "%s"\n'
            'echo "ARCHIVETUNE_BENCH_RUNTIME_FAILURE" >&2\n'
            "exit 3\n" % counter
        )
    os.chmod(broken, 0o755)
    return broken


def count_invocations(log_path: str) -> int:
    try:
        with open(log_path, "r") as handle:
            return sum(1 for line in handle if line.strip())
    except OSError:
        return 0


def _proc_starttime(pid: int) -> Optional[int]:
    try:
        with open("/proc/%d/stat" % pid, "r") as handle:
            data = handle.read()
    except OSError:
        return None
    close = data.rfind(")")
    if close < 0:
        return None
    fields = data[close + 2:].split()
    # field 22 (starttime) -> index 19 after the state field
    if len(fields) < 20:
        return None
    try:
        return int(fields[19])
    except ValueError:
        return None


def _matching_runtime_processes(paths: Sequence[str]) -> Dict[Tuple[int, int], str]:
    """Map (pid, starttime) -> exe for every running process using one of ``paths``.

    The start time is part of the key so a recycled PID is not mistaken for a
    leftover process.
    """
    wanted = set()
    for path in paths:
        if not path:
            continue
        try:
            wanted.add(os.path.realpath(path))
        except OSError:
            continue
    found: Dict[Tuple[int, int], str] = {}
    try:
        entries = os.listdir("/proc")
    except OSError:  # pragma: no cover - defensive
        return found
    for entry in entries:
        if not entry.isdigit():
            continue
        try:
            exe = os.readlink("/proc/%s/exe" % entry)
        except OSError:
            continue
        if exe not in wanted:
            continue
        starttime = _proc_starttime(int(entry))
        if starttime is None:
            continue
        found[(int(entry), starttime)] = exe
    return found


def residue_check(paths: Sequence[str],
                  preexisting: Optional[Dict[Tuple[int, int], str]] = None
                  ) -> Dict[str, Any]:
    """Confirm that the benchmark left no runtime process behind.

    The resolver must not leave a worker behind: every extraction is a
    short-lived process tree. Processes that were already running *before* the
    benchmark started (for example an unrelated development server that
    happens to use the same executable) are excluded, because they are not
    the benchmark's residue.
    """
    preexisting = preexisting or {}
    current = _matching_runtime_processes(paths)
    leftovers = [
        {"pid": pid, "starttime": starttime, "exe": exe}
        for (pid, starttime), exe in sorted(current.items())
        if (pid, starttime) not in preexisting
    ]
    return {
        "checked_executables": sorted({os.path.realpath(path) for path in paths}),
        "matched_executables_at_check": sorted({exe for exe in current.values()}),
        "preexisting_processes_excluded": len(preexisting),
        "resident_leftover_count": len(leftovers),
        "resident_leftovers": leftovers,
        "no_resident_worker": not leftovers,
    }


def count_script_invocations(log_path: str, runtime_name: str) -> int:
    """Count the invocations that actually executed a challenge solver script.

    yt-dlp probes every enabled runtime once to detect its version
    (``qjs --help``, ``node --version``, ``bun --version``, ``deno --version``).
    Those probes must not be counted as challenge executions.
    """
    probes = {
        "quickjs": ("--help",),
        "node": ("--version",),
        "bun": ("--version",),
        "deno": ("--version",),
    }.get(runtime_name, ())
    try:
        with open(log_path, "r") as handle:
            lines = [line.strip() for line in handle if line.strip()]
    except OSError:
        return 0
    count = 0
    for line in lines:
        if any(probe in line for probe in probes):
            continue
        count += 1
    return count


def read_invocation_log(log_path: str, limit: int = 10) -> List[str]:
    try:
        with open(log_path, "r") as handle:
            lines = [line.strip() for line in handle if line.strip()]
    except OSError:
        return []
    return lines[:limit]


# ---------------------------------------------------------------------------
# Extraction
# ---------------------------------------------------------------------------


def extract(ytdlp: str, runtime_name: str, runtime_path: str, target: str,
            timeout: float, env: Dict[str, str],
            extra_args: Sequence[str] = ()) -> RuntimeRun:
    argv: List[str] = [ytdlp]
    argv.extend(RESOLVER_FLAGS)
    argv.append("--no-js-runtimes")
    argv.append("--js-runtimes")
    argv.append("%s:%s" % (runtime_name, runtime_path))
    argv.extend(extra_args)
    argv.append("-v")
    argv.append(target)
    return run_measured(argv, timeout=timeout, env=env)


@dataclass
class ExtractionResult:
    runtime_key: str
    scenario: str
    video_id: str
    repeat: int
    attempt: int = 1
    runtime_required: Optional[bool] = None
    ok: bool = False
    failure_class: str = "NOT_RUN"
    exit_code: int = -1
    timed_out: bool = False
    wall_ms: int = 0
    cpu_user_s: float = 0.0
    cpu_sys_s: float = 0.0
    cpu_total_s: float = 0.0
    peak_tree_rss_bytes: int = 0
    peak_process_count: int = 0
    peak_breakdown: List[Dict[str, Any]] = field(default_factory=list)
    observed_comm: Dict[str, int] = field(default_factory=dict)
    max_cpu_ticks: Dict[str, int] = field(default_factory=dict)
    runtime_process_observed: bool = False
    rss_samples: int = 0
    runtime_invocations: int = 0
    runtime_script_invocations: int = 0
    runtime_invocation_argv: List[str] = field(default_factory=list)
    challenge_solved: bool = False
    challenge_failed: bool = False
    challenge_provider: Optional[str] = None
    challenge_script_version: Optional[str] = None
    runtime_command_lines: List[str] = field(default_factory=list)
    runtime_version_gate_messages: List[Dict[str, str]] = field(default_factory=list)
    format_id: Optional[str] = None
    format_count: int = 0
    protocol: Optional[str] = None
    vcodec: Optional[str] = None
    acodec: Optional[str] = None
    ext: Optional[str] = None
    duration_s: Optional[int] = None
    http_status: Optional[int] = None
    http_bytes_read: int = 0
    http_content_type: Optional[str] = None
    http_ranged: bool = False
    http_tail_status: Optional[int] = None
    http_tail_bytes: int = 0
    http_error: Optional[str] = None
    stderr_excerpt: Optional[str] = None

    def summary(self) -> Dict[str, Any]:
        data = dict(vars(self))
        data["peak_tree_rss_mib"] = round(self.peak_tree_rss_bytes / (1024 * 1024), 2)
        data["cpu_total_s"] = round(self.cpu_total_s, 3)
        return data


_CHALLENGE_SOLVED_RE = re.compile(r"Solving JS challenges using ([a-z0-9_-]+)")
_CHALLENGE_FAILED_RE = re.compile(
    r"(?:Error solving n challenge|n challenge solving failed|JS Challenge Provider .* failed)", re.I)
_SCRIPT_VERSION_RE = re.compile(r"challenge solver (?:lib|core) script v?([0-9.]+)")
_RUNNING_RE = re.compile(r"Running (QuickJS|node|bun|deno): (.+)")


def _sanitize_gate_message(line: str) -> str:
    """Redact like the rest of the harness but keep the known public upstream URL.

    The deprecation notice points at a public yt-dlp issue. Keeping that link
    makes the gate message verifiable; every other URL is still redacted.
    """
    cleaned = REDACT(line, 400)
    for url in _KNOWN_PUBLIC_UPSTREAM_URLS:
        if url in line:
            cleaned = cleaned.replace("<redacted-url>", url, 1)
    return cleaned


def _runtime_version_gate_messages(stderr: str) -> List[Dict[str, str]]:
    """Extract yt-dlp's runtime version-gate lines from the diagnostics.

    These lines decide whether the pinned yt-dlp *accepted* the runtime
    (``bun support has been deprecated``) or *flagged* it
    (``bun version X is not supported!``). They are warnings in both cases -
    the upstream source continues into the same runtime invocation - so the
    benchmark records them verbatim instead of interpreting them.
    """
    messages: List[Dict[str, str]] = []
    for line in (stderr or "").splitlines():
        line = line.strip()
        if not line:
            continue
        if _BUN_GATE_UNSUPPORTED_RE.search(line):
            messages.append({"kind": "version_ceiling_warning",
                             "message": _sanitize_gate_message(line)})
        elif _BUN_GATE_DEPRECATED_RE.search(line):
            messages.append({"kind": "deprecation_notice",
                             "message": _sanitize_gate_message(line)})
    return messages


def summarize(run: RuntimeRun, payload: Optional[Dict[str, Any]],
              runtime_key: str, scenario: str, video_id: str, repeat: int,
              invocations: int, script_invocations: int = 0,
              invocation_argv: Optional[List[str]] = None) -> ExtractionResult:
    result = ExtractionResult(runtime_key=runtime_key, scenario=scenario, video_id=video_id,
                              repeat=repeat)
    result.exit_code = run.exit_code
    result.timed_out = run.timed_out
    result.wall_ms = run.wall_ms
    result.cpu_user_s = run.cpu_user_s
    result.cpu_sys_s = run.cpu_sys_s
    result.cpu_total_s = run.cpu_user_s + run.cpu_sys_s
    result.peak_tree_rss_bytes = run.peak_tree_rss_bytes
    result.peak_process_count = run.peak_process_count
    result.peak_breakdown = run.peak_breakdown
    result.observed_comm = run.observed_comm
    result.cpu_ticks_by_comm = run.cpu_ticks_by_comm
    result.distinct_processes = run.distinct_processes
    result.runtime_process_observed = run.runtime_process_observed
    result.rss_samples = run.rss_samples
    result.runtime_invocations = invocations
    result.runtime_script_invocations = script_invocations
    result.runtime_invocation_argv = list(invocation_argv or [])

    stderr = run.stderr or ""
    result.failure_class = CLASSIFY_FAILURE(run.exit_code, stderr, run.timed_out)
    solved = _CHALLENGE_SOLVED_RE.search(stderr)
    result.challenge_solved = solved is not None
    result.challenge_provider = solved.group(1) if solved else None
    result.challenge_failed = _CHALLENGE_FAILED_RE.search(stderr) is not None
    version = _SCRIPT_VERSION_RE.search(stderr)
    result.challenge_script_version = version.group(1) if version else None
    # The command line contains a temporary script path, never a URL.
    result.runtime_command_lines = [REDACT(match.group(0), 300)
                                    for match in _RUNNING_RE.finditer(stderr)]
    result.runtime_version_gate_messages = _runtime_version_gate_messages(stderr)
    if stderr.strip():
        result.stderr_excerpt = REDACT(stderr)

    if not payload:
        result.ok = False
        return result
    formats = payload.get("formats") or []
    result.format_count = len(formats)
    result.duration_s = payload.get("duration") if isinstance(payload.get("duration"), int) else None
    best = None
    for candidate in formats:
        if candidate.get("format_id") == "251":
            best = candidate
            break
    if best is None:
        for candidate in formats:
            if candidate.get("acodec") not in (None, "none"):
                best = candidate
                break
    if best is None and formats:
        best = formats[0]
    if best is not None:
        result.format_id = best.get("format_id")
        result.protocol = best.get("protocol")
        result.vcodec = best.get("vcodec")
        result.acodec = best.get("acodec")
        result.ext = best.get("ext")
    result.ok = run.exit_code == 0 and best is not None and bool((best or {}).get("url"))
    if result.ok:
        result.failure_class = "NONE"
    elif result.failure_class == "NONE":
        result.failure_class = "NO_MEDIA_URL"
    return result


def attach_http_probe(result: ExtractionResult, payload: Optional[Dict[str, Any]],
                      timeout: float) -> None:
    """Short ranged GET against the resolved media URL (no URL is stored)."""
    if not result.ok or not payload:
        return
    formats = payload.get("formats") or []
    best = None
    for candidate in formats:
        if candidate.get("format_id") == "251":
            best = candidate
            break
    if best is None:
        for candidate in formats:
            if candidate.get("acodec") not in (None, "none"):
                best = candidate
                break
    if best is None and formats:
        best = formats[0]
    if best is None:
        return
    url = best.get("url") or ""
    headers = best.get("http_headers") or {}
    probe = PROBE_URL(url, headers, timeout)
    result.http_status = probe.status
    result.http_bytes_read = probe.bytes_read
    result.http_content_type = probe.content_type
    result.http_ranged = probe.ranged_response
    result.http_tail_status = probe.tail_status
    result.http_tail_bytes = probe.tail_bytes_read
    result.http_error = probe.error


def parse_payload(stdout: bytes) -> Optional[Dict[str, Any]]:
    if not stdout:
        return None
    try:
        candidate = json.loads(stdout.decode("utf-8", "replace"))
    except (ValueError, UnicodeDecodeError):
        return None
    return candidate if isinstance(candidate, dict) else None


# ---------------------------------------------------------------------------
# Runtime discovery / verification
# ---------------------------------------------------------------------------


def _version_output(argv: Sequence[str], timeout: float = 30) -> Optional[str]:
    try:
        completed = subprocess.run(list(argv), capture_output=True, timeout=timeout)
    except (OSError, subprocess.SubprocessError):
        return None
    text = (completed.stdout or b"").decode("utf-8", "replace").strip()
    if not text:
        text = (completed.stderr or b"").decode("utf-8", "replace").strip()
    return text.splitlines()[0].strip() if text else None


def describe_runtime(spec: RuntimeSpec) -> RuntimeSpec:
    path = os.path.abspath(spec.path)
    spec.path = path
    if not os.path.isfile(path):
        spec.error = "runtime binary not found"
        spec.supported_by_ytdlp = False
        return spec
    spec.size_bytes = os.path.getsize(path)
    try:
        spec.sha256 = SHA256_OF(path)
    except OSError as error:
        spec.error = "sha256 failed: %s" % error
    if spec.ytdlp_name == "quickjs":
        # quickjs has no --version; --help returns exit code 1 and prints the banner
        out = _version_output([path, "--help"])
        spec.version_source = "qjs --help"
        if out:
            match = re.match(r"^QuickJS(?:-ng)?\s+version\s+(\S+)", out)
            spec.version = match.group(1) if match else out
    elif spec.ytdlp_name == "node":
        out = _version_output([path, "--version"])
        spec.version_source = "node --version"
        spec.version = out.lstrip("v") if out else None
    elif spec.ytdlp_name == "bun":
        out = _version_output([path, "--version"])
        spec.version_source = "bun --version"
        spec.version = out.split()[0] if out else None
    elif spec.ytdlp_name == "deno":
        out = _version_output([path, "--version"])
        spec.version_source = "deno --version"
        spec.version = out.split()[1] if out and len(out.split()) > 1 else None
    spec.supported_by_ytdlp = _is_supported(spec)
    if spec.ytdlp_name == "bun":
        # yt_dlp/extractor/youtube/jsc/_builtin/bun.py @ 2026.08.19 keeps
        # _BUN_MAX_SUPPORTED_VERSION = (1, 3, 14). A newer bun is *flagged*
        # by the provider but still executed, so the version stays above the
        # pinned ceiling while the runtime remains usable.
        spec.above_pinned_ytdlp_ceiling = _version_tuple(spec.version) > (1, 3, 14)
    return spec


def _version_tuple(version: Optional[str]) -> Tuple[int, ...]:
    if not version:
        return ()
    parts: List[int] = []
    for chunk in version.split("."):
        match = re.match(r"^(\d+)", chunk)
        if not match:
            break
        parts.append(int(match.group(1)))
    return tuple(parts)


def _is_supported(spec: RuntimeSpec) -> bool:
    """Mirror the version gate of yt_dlp/utils/_jsruntime.py (tag 2026.08.19)."""
    vt = _version_tuple(spec.version)
    if spec.ytdlp_name == "node":
        return vt >= (22, 0, 0)
    if spec.ytdlp_name == "bun":
        return vt >= (1, 2, 11)
    if spec.ytdlp_name == "deno":
        return vt >= (2, 3, 0)
    if spec.ytdlp_name == "quickjs":
        # quickjs-ng: any version is supported; original quickjs needs >= 2023-12-9
        if spec.version and re.match(r"^\d{4}-\d{2}-\d{2}", spec.version):
            return vt >= (2023, 12, 9)
        return bool(vt) and vt > (0,)
    return False


# ---------------------------------------------------------------------------
# Benchmark driver
# ---------------------------------------------------------------------------


@dataclass
class Scenario:
    key: str
    video_id: str
    client: Optional[str]
    description: str
    target_successes: int = 1
    max_attempts: int = 1
    runtime_required: Optional[bool] = None


def build_scenarios(challenged_video: str, control_video: str) -> List[Scenario]:
    return [
        Scenario("challenged", challenged_video, CHALLENGED_CLIENT,
                 "challenged video with the web_embedded player client; the JS runtime is "
                 "load-bearing here (a broken or missing runtime makes the extraction fail)",
                 target_successes=3, max_attempts=5, runtime_required=True),
        Scenario("default_client_same_video", challenged_video, None,
                 "challenged video with the default player client, i.e. the exact production "
                 "flag set; the runtime is still invoked by yt-dlp but the extraction can "
                 "also succeed without it",
                 target_successes=1, max_attempts=1, runtime_required=False),
        Scenario("control_unchallenged", control_video, None,
                 "unchallenged video, default player client; the runtime is only probed for "
                 "its version unless YouTube decides to challenge this request",
                 target_successes=2, max_attempts=2, runtime_required=False),
    ]


def run_scenario(ytdlp: str, spec: RuntimeSpec, scenario: Scenario, repeat: int,
                 timeout: float, env: Dict[str, str], workdir: str,
                 probe_timeout: float) -> Tuple[ExtractionResult, Optional[Dict[str, Any]]]:
    log_path = os.path.join(workdir, "runtime-invocations-%s-%s-%d.log"
                            % (spec.key, scenario.key, repeat))
    # Retries reuse the success index, but their invocation counts must be independent.
    with open(log_path, "w"):
        pass
    shim = make_counting_shim(spec.path, log_path)
    extra: List[str] = []
    if scenario.client:
        extra = ["--extractor-args", "youtube:player_client=%s" % scenario.client]
    try:
        run = extract(ytdlp, spec.ytdlp_name, shim, "https://www.youtube.com/watch?v=%s"
                      % scenario.video_id, timeout, env, extra_args=extra)
    finally:
        shutil.rmtree(os.path.dirname(shim), ignore_errors=True)
    invocations = count_invocations(log_path)
    script_invocations = count_script_invocations(log_path, spec.ytdlp_name)
    argv_log = read_invocation_log(log_path)
    payload = parse_payload(run.stdout)
    result = summarize(run, payload, spec.key, scenario.key, scenario.video_id, repeat,
                        invocations, script_invocations, argv_log)
    result.runtime_required = scenario.runtime_required
    if result.ok:
        attach_http_probe(result, payload, probe_timeout)
    return result, payload


def negative_control(ytdlp: str, spec: RuntimeSpec, scenario: Scenario, timeout: float,
                    env: Dict[str, str], workdir: str) -> Dict[str, Any]:
    """A broken runtime must make the challenged extraction fail."""
    log_path = os.path.join(workdir, "negative-control-%s.log" % spec.key)
    broken = make_broken_runtime(log_path)
    extra: List[str] = []
    if scenario.client:
        extra = ["--extractor-args", "youtube:player_client=%s" % scenario.client]
    try:
        run = extract(ytdlp, spec.ytdlp_name, broken, "https://www.youtube.com/watch?v=%s"
                      % scenario.video_id, timeout, env, extra_args=extra)
    finally:
        shutil.rmtree(os.path.dirname(broken), ignore_errors=True)
    stderr = run.stderr or ""
    return {
        "runtime_key": spec.key,
        "scenario": scenario.key,
        "video_id": scenario.video_id,
        "exit_code": run.exit_code,
        "failed_as_expected": run.exit_code != 0,
        "wall_ms": run.wall_ms,
        "failure_class": CLASSIFY_FAILURE(run.exit_code, stderr, run.timed_out),
        "runtime_invocations": count_invocations(log_path),
        "runtime_script_invocations": count_script_invocations(log_path, spec.ytdlp_name),
        "stderr_excerpt": REDACT(stderr, 600),
    }


def missing_runtime_control(ytdlp: str, scenario: Scenario, timeout: float,
                            env: Dict[str, str]) -> Dict[str, Any]:
    """Extraction without any JS runtime must fail on the challenged video."""
    argv: List[str] = [ytdlp]
    argv.extend(RESOLVER_FLAGS)
    argv.append("--no-js-runtimes")
    argv.append("-v")
    if scenario.client:
        argv.extend(["--extractor-args", "youtube:player_client=%s" % scenario.client])
    argv.append("https://www.youtube.com/watch?v=%s" % scenario.video_id)
    run = run_measured(argv, timeout=timeout, env=env)
    stderr = run.stderr or ""
    return {
        "scenario": scenario.key,
        "video_id": scenario.video_id,
        "exit_code": run.exit_code,
        "failed_as_expected": run.exit_code != 0,
        "wall_ms": run.wall_ms,
        "failure_class": CLASSIFY_FAILURE(run.exit_code, stderr, run.timed_out),
        "stderr_excerpt": REDACT(stderr, 600),
    }


# ---------------------------------------------------------------------------
# Reporting
# ---------------------------------------------------------------------------

CSV_COLUMNS = [
    "runtime_key", "scenario", "video_id", "repeat", "attempt", "runtime_required",
    "ok", "failure_class",
    "exit_code", "timed_out", "wall_ms", "cpu_user_s", "cpu_sys_s", "cpu_total_s",
    "peak_tree_rss_bytes", "peak_tree_rss_mib", "peak_process_count", "rss_samples",
    "runtime_invocations", "runtime_script_invocations", "runtime_process_observed",
    "challenge_solved", "challenge_failed", "challenge_provider", "challenge_script_version",
    "format_id", "format_count", "protocol", "vcodec", "acodec", "ext", "duration_s",
    "http_status", "http_bytes_read", "http_content_type", "http_ranged",
    "http_tail_status", "http_tail_bytes", "http_error",
]


def write_reports(report: Dict[str, Any], results: List[ExtractionResult],
                  out_dir: str, json_path: Optional[str]) -> Tuple[str, str]:
    os.makedirs(out_dir, exist_ok=True)
    if json_path:
        os.makedirs(os.path.dirname(os.path.abspath(json_path)), exist_ok=True)
        json_target = os.path.abspath(json_path)
    else:
        json_target = os.path.join(out_dir, "ytdlp-runtime-comparison.json")
    with open(json_target, "w") as handle:
        json.dump(report, handle, indent=2, sort_keys=True)
        handle.write("\n")
    csv_path = os.path.join(out_dir, "ytdlp-runtime-comparison.csv")
    with open(csv_path, "w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=CSV_COLUMNS)
        writer.writeheader()
        for result in results:
            row = {key: result.summary().get(key) for key in CSV_COLUMNS}
            writer.writerow(row)
    return json_target, csv_path


def _ratio(value: Optional[float], base: Optional[float]) -> Optional[float]:
    if value is None or not base:
        return None
    return round(value / base, 3)


def build_headline(per_runtime: Dict[str, Any], baseline_key: str) -> Dict[str, Any]:
    """Headline medians and baseline ratios for a set of measured runtimes.

    Used for the report of this run and for the report files of earlier
    repetitions, so a repetition recorded by an older harness version can
    still contribute its numbers.
    """
    baseline_challenged = per_runtime.get(baseline_key, {}).get("challenged", {})
    headline: Dict[str, Any] = {}
    for key, entry in per_runtime.items():
        challenged = entry.get("challenged", {})
        headline[key] = {
            "version": entry["runtime"]["version"],
            "size_mib": entry["runtime"]["size_mib"],
            "attempts_total": challenged.get("attempts_total"),
            "successful": challenged.get("successful"),
            "challenge_solved_runs": challenged.get("challenge_solved_runs"),
            "runtime_script_invocations_total": challenged.get("runtime_script_invocations_total"),
            "http_read_ok_runs": challenged.get("http_read_ok_runs"),
            "wall_ms_median": challenged.get("wall_ms_median"),
            "wall_ms_min": challenged.get("wall_ms_min"),
            "wall_ms_max": challenged.get("wall_ms_max"),
            "cpu_total_s_median": challenged.get("cpu_total_s_median"),
            "peak_tree_rss_bytes_median": challenged.get("peak_tree_rss_bytes_median"),
            "peak_tree_rss_bytes_max": challenged.get("peak_tree_rss_bytes_max"),
            "version_gate_outcome": entry.get("version_gate", {}).get("outcome"),
            "wall_median_vs_baseline": _ratio(challenged.get("wall_ms_median"),
                                              baseline_challenged.get("wall_ms_median")),
            "cpu_median_vs_baseline": _ratio(challenged.get("cpu_total_s_median"),
                                             baseline_challenged.get("cpu_total_s_median")),
            "peak_rss_max_vs_baseline": _ratio(challenged.get("peak_tree_rss_bytes_max"),
                                               baseline_challenged.get("peak_tree_rss_bytes_max")),
        }
    return headline


def compact_challenged_runs(runs: Sequence[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Keep enough safe per-run evidence to audit pooled repetition statistics."""
    fields = ("runtime_key", "repeat", "attempt", "ok", "failure_class", "wall_ms",
              "cpu_total_s", "peak_tree_rss_bytes", "cpu_ticks_by_comm",
              "runtime_script_invocations", "challenge_solved", "http_status",
              "http_bytes_read", "http_tail_status", "http_tail_bytes")
    return [{key: run.get(key) for key in fields} for run in runs
            if run.get("scenario") == "challenged"]


def pooled_challenged_summary(runs: Sequence[Dict[str, Any]]) -> Dict[str, Any]:
    """Pool raw observations, rather than averaging three-sample medians."""
    result: Dict[str, Any] = {}
    for key in sorted({run["runtime_key"] for run in runs}):
        attempts = [run for run in runs if run["runtime_key"] == key]
        ok = [run for run in attempts if run.get("ok")]
        result[key] = {
            "successful": len(ok), "attempts_total": len(attempts),
            "wall_ms_median": statistics.median(run["wall_ms"] for run in ok) if ok else None,
            "cpu_total_s_median": statistics.median(run["cpu_total_s"] for run in ok) if ok else None,
            "peak_tree_rss_bytes_median": statistics.median(run["peak_tree_rss_bytes"] for run in ok) if ok else None,
            "peak_tree_rss_bytes_max": max((run["peak_tree_rss_bytes"] for run in ok), default=None),
        }
    return result


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Compare yt-dlp EJS JS runtimes (QuickJS-NG vs Node/Bun/Deno) on a "
                    "challenged YouTube video.")
    parser.add_argument("--yt-dlp", default="build/tools/resolver/yt-dlp")
    parser.add_argument("--qjs", default="build/tools/resolver/qjs")
    parser.add_argument("--node", default="")
    parser.add_argument("--bun", default="")
    parser.add_argument("--bun-new", default="",
                        help="optional second bun build (e.g. the Rust-rewrite 1.4.x line); it is "
                             "measured with the unchanged yt-dlp CLI, no version gate is bypassed")
    parser.add_argument("--deno", default="")
    parser.add_argument("--challenged-video", default="dQw4w9WgXcQ")
    parser.add_argument("--control-video", default="jNQXAC9IVRw")
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--timeout", type=float, default=120.0)
    parser.add_argument("--probe-timeout", type=float, default=20.0)
    parser.add_argument("--sample-interval", type=float, default=0.02)
    parser.add_argument("--out-dir", default="build/reports/ytdlp-runtimes")
    parser.add_argument("--out-json", default="")
    parser.add_argument("--artifact-provenance", default="",
                        help="optional JSON file with the release provenance of an externally "
                             "provisioned runtime artifact; embedded verbatim in the report")
    parser.add_argument("--report-schema", default="archivetune.ytdlp.runtime-comparison/1")
    parser.add_argument("--baseline-runtime", default="quickjs-ng",
                        help="runtime key used as the denominator of the headline ratios")
    parser.add_argument("--repetition-json", action="append", default=[],
                        help="path to a previously produced report JSON of the same benchmark; "
                             "its headline numbers are added to the 'repetitions' section so a "
                             "single run cannot hide run-to-run variance")
    parser.add_argument("--skip-negative-control", action="store_true")
    parser.add_argument("--expect-ytdlp-sha256", default=YTDLP_SHA256)
    parser.add_argument("--expect-qjs-sha256", default=QJS_SHA256)
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    started_at = time.time()

    ytdlp_path = os.path.abspath(args.yt_dlp)
    qjs_path = os.path.abspath(args.qjs)

    tool_checks = {
        "yt-dlp": {
            "path": ytdlp_path,
            "expected_sha256": args.expect_ytdlp_sha256,
        },
        "quickjs-ng": {
            "path": qjs_path,
            "expected_sha256": args.expect_qjs_sha256,
        },
    }
    tools_ok = True
    for name, check in tool_checks.items():
        try:
            actual = SHA256_OF(check["path"])
        except OSError as error:
            check["error"] = "not readable: %s" % error
            check["sha256_matches"] = False
            tools_ok = False
            continue
        check["sha256"] = actual
        check["sha256_matches"] = actual == check["expected_sha256"]
        check["size_bytes"] = os.path.getsize(check["path"])
        check["version"] = _version_output([check["path"], "--version"])
        if not check["sha256_matches"]:
            tools_ok = False

    specs: List[RuntimeSpec] = [
        RuntimeSpec("quickjs-ng", "quickjs", qjs_path,
                    upstream_status="supported; quickjs-ng 0.17.0 >= 0.12.0 recommended threshold"),
    ]
    if args.node:
        specs.append(RuntimeSpec("node", "node", args.node,
                                 upstream_status="supported upstream runtime"))
    if args.bun:
        specs.append(RuntimeSpec("bun", "bun", args.bun,
                                 upstream_status="deprecated upstream "
                                                "(versions above 1.3.14 are unsupported)"))
    if args.bun_new:
        specs.append(RuntimeSpec("bun-rust", "bun", args.bun_new,
                                 upstream_status="above the pinned yt-dlp bun ceiling of 1.3.14 "
                                                "(yt-dlp 2026.08.19 warns but still invokes the "
                                                "runtime); measured with the unchanged CLI"))
    if args.deno:
        specs.append(RuntimeSpec("deno", "deno", args.deno,
                                 upstream_status="recommended upstream default (enabled by default)"))
    for spec in specs:
        describe_runtime(spec)

    scenarios = build_scenarios(args.challenged_video, args.control_video)
    workdir = tempfile.mkdtemp(prefix="archivetune-runtime-bench-")
    env = HERMETIC_ENV(workdir)
    env["TMPDIR"] = workdir

    # Processes that already use one of the measured executables are not the
    # benchmark's residue, so they are snapshotted before the first run.
    measured_paths = [ytdlp_path] + [spec.path for spec in specs]
    preexisting_processes = _matching_runtime_processes(measured_paths)

    results: List[ExtractionResult] = []
    negatives: List[Dict[str, Any]] = []
    missing_controls: List[Dict[str, Any]] = []

    try:
        for spec in specs:
            for scenario in scenarios:
                successes = 0
                attempt = 0
                while successes < scenario.target_successes and attempt < scenario.max_attempts:
                    attempt += 1
                    result, _ = run_scenario(ytdlp_path, spec, scenario, successes + 1,
                                             args.timeout, env, workdir, args.probe_timeout)
                    result.attempt = attempt
                    results.append(result)
                    if result.ok:
                        successes += 1
                    print("[run] runtime=%-11s scenario=%-24s attempt=%d ok=%s failure=%s "
                          "wall=%dms cpu=%.2fs peak_rss=%.1fMiB procs=%d script_invocations=%d"
                          % (spec.key, scenario.key, attempt, result.ok, result.failure_class,
                             result.wall_ms, result.cpu_total_s,
                             result.peak_tree_rss_bytes / (1024 * 1024),
                             result.peak_process_count, result.runtime_script_invocations),
                          flush=True)
                if scenario.key == "challenged" and not args.skip_negative_control:
                    negative = negative_control(ytdlp_path, spec, scenario, args.timeout, env, workdir)
                    negatives.append(negative)
                    print("[control] runtime=%-11s broken-runtime exit=%s failed_as_expected=%s"
                          % (spec.key, negative["exit_code"], negative["failed_as_expected"]),
                          flush=True)
        for scenario in scenarios:
            if scenario.key == "challenged":
                control = missing_runtime_control(ytdlp_path, scenario, args.timeout, env)
                missing_controls.append(control)
                print("[control] no-runtime exit=%s failed_as_expected=%s"
                      % (control["exit_code"], control["failed_as_expected"]), flush=True)
    finally:
        shutil.rmtree(workdir, ignore_errors=True)

    # -- aggregate ---------------------------------------------------------
    clock_ticks = os.sysconf("SC_CLK_TCK")

    def _stats(runs: List[ExtractionResult]) -> Dict[str, Any]:
        ok_runs = [r for r in runs if r.ok]
        walls = sorted(r.wall_ms for r in ok_runs)
        cpus = sorted(r.cpu_total_s for r in ok_runs)
        peaks = sorted(r.peak_tree_rss_bytes for r in ok_runs)
        return {
            "runs": len(runs),
            "successful": len(ok_runs),
            "attempts_total": len(runs),
            "challenge_solved_runs": sum(1 for r in runs if r.challenge_solved),
            "runtime_script_invocations_total": sum(r.runtime_script_invocations for r in runs),
            "wall_ms_min": walls[0] if walls else None,
            "wall_ms_max": walls[-1] if walls else None,
            "wall_ms_median": statistics.median(walls) if walls else None,
            "cpu_total_s_median": statistics.median(cpus) if cpus else None,
            "cpu_total_s_min": cpus[0] if cpus else None,
            "cpu_total_s_max": cpus[-1] if cpus else None,
            "peak_tree_rss_bytes_max": peaks[-1] if peaks else None,
            "peak_tree_rss_bytes_median": statistics.median(peaks) if peaks else None,
            "peak_process_count_max": max((r.peak_process_count for r in runs), default=None),
            "http_read_ok_runs": sum(1 for r in ok_runs if (r.http_bytes_read or 0) > 0),
        }

    def _gate_summary(runs: List[ExtractionResult], key: str) -> Dict[str, Any]:
        """What the pinned yt-dlp decided about this runtime version.

        ``accepted`` means the unchanged CLI ran the runtime and the extraction
        succeeded. ``accepted_with_warning`` additionally means yt-dlp printed a
        version-gate message. ``rejected`` means the runtime was refused
        (JS_RUNTIME_MISSING) or the challenge was never solved.
        """
        messages: List[Dict[str, str]] = []
        for run in runs:
            for message in run.runtime_version_gate_messages:
                if message not in messages:
                    messages.append(message)
        solved = sum(1 for r in runs if r.challenge_solved)
        ok = sum(1 for r in runs if r.ok)
        refused = sum(1 for r in runs if r.failure_class == "JS_RUNTIME_MISSING")
        kinds = sorted({message["kind"] for message in messages})
        if refused and not ok:
            outcome = "rejected"
        elif ok:
            outcome = "accepted_with_warning" if kinds else "accepted"
        else:
            outcome = "not_established"
        return {
            "cli_gate_bypassed": False,
            "outcome": outcome,
            "above_pinned_ytdlp_ceiling": next(
                (spec.above_pinned_ytdlp_ceiling for spec in specs if spec.key == key), None),
            "message_kinds": kinds,
            "messages": messages,
            "challenged_runs": len(runs),
            "challenged_successful": ok,
            "challenged_challenge_solved": solved,
            "challenged_runtime_refused": refused,
        }

    per_runtime: Dict[str, Dict[str, Any]] = {}
    for spec in specs:
        by_scenario: Dict[str, List[ExtractionResult]] = {}
        for scenario in scenarios:
            by_scenario[scenario.key] = [r for r in results
                                         if r.runtime_key == spec.key and r.scenario == scenario.key]
        challenged = by_scenario["challenged"]
        runtime_cpu_ticks: Dict[str, int] = {}
        runtime_rss: Dict[str, int] = {}
        for run in challenged:
            for comm, ticks in (run.cpu_ticks_by_comm or {}).items():
                runtime_cpu_ticks[comm] = max(runtime_cpu_ticks.get(comm, 0), ticks)
            for entry in run.peak_breakdown or []:
                comm = entry.get("comm", "unknown")
                runtime_rss[comm] = max(runtime_rss.get(comm, 0), int(entry.get("rss_bytes") or 0))
        entry: Dict[str, Any] = {
            "runtime": spec.summary(),
            "challenged": _stats(challenged),
            "default_client_same_video": _stats(by_scenario["default_client_same_video"]),
            "control_unchallenged": _stats(by_scenario["control_unchallenged"]),
            # The "unchallenged" control video is not guaranteed to stay
            # unchallenged: YouTube sometimes challenges it too. A control run
            # that executed a solver script is not a control, so the clean
            # subset (zero solver script invocations) is reported separately.
            "control_unchallenged_clean": _stats(
                [r for r in by_scenario["control_unchallenged"]
                 if not r.runtime_script_invocations]),
            "challenged_per_process_class_peak_rss_bytes": runtime_rss,
            "challenged_per_process_class_peak_cpu_s": {
                comm: round(ticks / clock_ticks, 3) for comm, ticks in runtime_cpu_ticks.items()
            },
            "control_unchallenged_script_invocations": sum(
                r.runtime_script_invocations for r in by_scenario["control_unchallenged"]),
            "default_client_script_invocations": sum(
                r.runtime_script_invocations for r in by_scenario["default_client_same_video"]),
            "version_gate": _gate_summary(challenged, spec.key),
        }
        per_runtime[spec.key] = entry

    # -- headline comparison ------------------------------------------------
    baseline_key = args.baseline_runtime
    headline_runtimes = build_headline(per_runtime, baseline_key)

    artifact_provenance: Optional[Dict[str, Any]] = None
    repetitions: List[Dict[str, Any]] = []
    for path in args.repetition_json:
        try:
            with open(os.path.abspath(path), "r") as handle:
                other = json.load(handle)
        except (OSError, ValueError) as error:
            print("ERROR: could not read --repetition-json %s: %s" % (path, error), file=sys.stderr)
            return 2
        other_baseline = (other.get("headline_comparison") or {}).get(
            "baseline_runtime", args.baseline_runtime)
        other_headline = (other.get("headline_comparison") or {}).get("runtimes")
        if not other_headline:
            # An earlier harness version did not write a headline block; the
            # per-runtime medians are still there, so derive them.
            other_headline = build_headline(other.get("per_runtime") or {}, other_baseline)
        repetitions.append({
            "source": os.path.join(os.path.basename(os.path.dirname(os.path.abspath(path))),
                                   os.path.basename(os.path.abspath(path))),
            "generated_at_utc": other.get("generated_at_utc"),
            "baseline_runtime": other_baseline,
            "runtimes": other_headline,
            "challenged_runs": compact_challenged_runs(other.get("runs", [])),
            "version_gate": {
                key: entry.get("version_gate", {}).get("outcome")
                for key, entry in (other.get("per_runtime") or {}).items()
            },
        })
    if args.artifact_provenance:
        try:
            with open(os.path.abspath(args.artifact_provenance), "r") as handle:
                artifact_provenance = json.load(handle)
        except (OSError, ValueError) as error:
            print("ERROR: could not read --artifact-provenance: %s" % error, file=sys.stderr)
            return 2

    report: Dict[str, Any] = {
        "schema": args.report_schema,
        "generated_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(started_at)),
        "duration_s": round(time.time() - started_at, 2),
        "host": {
            "platform": platform.platform(),
            "machine": platform.machine(),
            "cpu_count": os.cpu_count(),
            "python": platform.python_version(),
        },
        "method": {
            "description": "Cold yt-dlp extractions per JS runtime on a challenged YouTube "
                           "video plus two controls, serialized (one extraction at a time).",
            "control_note": "The 'control_unchallenged' video is not guaranteed to stay "
                            "unchallenged: YouTube sometimes challenges it too. "
                            "per_runtime.<key>.control_unchallenged_clean therefore restricts "
                            "the control to the runs that executed no solver script at all.",
            "cold_definition": "Every extraction is a fresh yt-dlp process with a private "
                               "HOME/XDG_*/TMPDIR, no cookie file, no user profile and no "
                               "shared runtime cache.",
            "resolver_flags": list(RESOLVER_FLAGS),
            "js_runtime_selection": "--no-js-runtimes --js-runtimes <name>:<path>",
            "challenged_client": CHALLENGED_CLIENT,
            "challenged_video": args.challenged_video,
            "control_video": args.control_video,
            "repeats_challenged": args.repeats,
            "scenarios": [
                {
                    "key": scenario.key,
                    "video_id": scenario.video_id,
                    "player_client": scenario.client or "default",
                    "description": scenario.description,
                    "target_successes": scenario.target_successes,
                    "max_attempts": scenario.max_attempts,
                    "runtime_required": scenario.runtime_required,
                }
                for scenario in scenarios
            ],
            "timeout_s": args.timeout,
            "sample_interval_s": args.sample_interval,
            "rss_method": "Maximum sampled sum of process-tree RSS over the whole yt-dlp "
                          "process tree; shared pages may be counted once per mapping "
                          "process and short-lived spikes may be missed.",
            "cpu_method": "getrusage(RUSAGE_CHILDREN) delta around each extraction; on Linux "
                          "this is transitive, so the frozen yt-dlp's embedded CPython and "
                          "the JS runtime child are both included.",
            "serialized": True,
            "cookies_used": False,
            "signed_urls_stored": False,
        },
        "tools": tools_checks_report(tools_ok, tool_checks),
        "artifact_provenance": artifact_provenance,
        "headline_comparison": {
            "baseline_runtime": baseline_key,
            "baseline_note": "Ratios are the median challenged values of each runtime divided "
                             "by the same values of the baseline runtime. A value below 1.0 "
                             "means the runtime is cheaper/faster than the baseline.",
            "runtimes": headline_runtimes,
        },
        "resident_worker_check": residue_check(measured_paths, preexisting_processes),
        "repetitions": {
            "note": "Independent repetitions of the whole benchmark. 'this_run' is the run "
                    "that produced this report; the other entries are earlier report files "
                    "passed with --repetition-json. Comparing them shows how much a "
                    "three-sample median can move between repetitions.",
            "this_run": {
                "source": os.path.basename(os.path.abspath(args.out_json))
                           if args.out_json else "this run",
                "generated_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(started_at)),
                "baseline_runtime": baseline_key,
                "runtimes": headline_runtimes,
                "version_gate": {
                    key: entry.get("version_gate", {}).get("outcome")
                    for key, entry in per_runtime.items()
                },
            },
            "earlier": repetitions,
        },
        "runtimes": [spec.summary() for spec in specs],
        "upstream_sources": UPSTREAM_SOURCES,
        "negative_controls": negatives,
        "missing_runtime_controls": missing_controls,
        "runs": [r.summary() for r in results],
        "per_runtime": per_runtime,
    }

    primary_challenged = compact_challenged_runs(report["runs"])
    report["repetitions"]["this_run"]["challenged_runs"] = primary_challenged
    all_challenged = primary_challenged + [run for repetition in repetitions
                                        for run in repetition.get("challenged_runs", [])]
    report["pooled_challenged"] = pooled_challenged_summary(all_challenged)
    json_path, csv_path = write_reports(report, results, args.out_dir, args.out_json or None)
    print("")
    print("report: %s" % json_path)
    print("csv   : %s" % csv_path)
    for key, entry in per_runtime.items():
        challenged = entry["challenged"]
        print("%-11s challenged ok=%d/%d wall_p50=%sms cpu_p50=%ss peak_rss_max=%.1fMiB "
              "script_invocations=%d"
              % (key, challenged["successful"], challenged["runs"],
                 challenged["wall_ms_median"], challenged["cpu_total_s_median"] or 0.0,
                 (challenged["peak_tree_rss_bytes_max"] or 0) / (1024 * 1024),
                 challenged["runtime_script_invocations_total"]))

    if not tools_ok:
        print("ERROR: tool verification failed", file=sys.stderr)
        return 2
    failed = [r for r in results if not r.ok]
    if failed:
        print("ERROR: %d extraction(s) failed" % len(failed), file=sys.stderr)
        return 1
    return 0


def tools_checks_report(tools_ok: bool, checks: Dict[str, Dict[str, Any]]) -> Dict[str, Any]:
    return {"verified": tools_ok, "checks": checks}


if __name__ == "__main__":
    sys.exit(main())

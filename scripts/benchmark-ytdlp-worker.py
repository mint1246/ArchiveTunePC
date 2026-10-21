#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ArchiveTune persistent yt-dlp worker evaluation harness (developer-only).

This script answers one narrow question with measurements instead of engine
claims: **does keeping a Python interpreter (and, optionally, a ``YoutubeDL``
object) alive between resolver requests beat launching the frozen yt-dlp CLI
once per request, and what does it cost in resident memory?**

What it does
------------
1. Verifies the pinned upstream artifacts by SHA-256: the frozen yt-dlp
   2026.08.19 executable, the Bun 1.4.3 sidecar and the virtual environment
   that holds the upstream yt-dlp 2026.08.19 sources plus ``yt-dlp-ejs==0.8.0``.
   Nothing is installed globally and no daemon is left running afterwards.
2. Measures four request models against the same videos with the same
   production flag set and the same JSON-lines protocol:

   ``frozen_cli``
       The shipped control: one frozen yt-dlp process per request.
   ``fresh_python``
       One *new* virtual-environment Python process per request, each building
       a new ``YoutubeDL`` object. This is the obvious port of the control.
   ``persistent_python_new_ydl``
       One long-lived Python process; every request builds a **new**
       ``YoutubeDL`` object.
   ``persistent_python_reuse_ydl``
       One long-lived Python process that **reuses** the same ``YoutubeDL``
       object, and therefore the same extractor instance, the same player-JS
       cache and the same HTTP connection pool.

3. Separates *cold* (a brand new process) from *warm* (a repeat on the same
   video) and *new video* repeats, because the upstream caches are keyed by
   player-JS build and video.
4. Samples the **whole process tree** from ``/proc`` during every request and
   records the maximum *simultaneous* RSS total, the maximum *simultaneous*
   PSS total (``/proc/<pid>/smaps_rollup``), the process count and the CPU
   time of the tree including helpers that already exited.
5. Records the **resident idle memory** of a persistent worker between
   requests, because that is what the desktop application would keep alive.
6. Counts **challenge-solver invocations** with an executable shim in front of
   Bun (so the count does not depend on log parsing) and corroborates it with
   yt-dlp's own diagnostics. Upstream source paths are cited for every claim
   about caching.
7. Probes every successful result with a short ranged GET at the head **and**
   at the tail of the object, using the exact headers yt-dlp returned, so a
   "success" is backed by real media bytes. The probe always runs outside the
   measured request window.

Safety contract
---------------
* Cookies are never requested, never read and never written. The default run
  is anonymous and the worker refuses a cookie file.
* Signed media URLs, raw ``--dump-single-json`` payloads, process argv and
  cookie values are never written to disk and never printed. Only redacted
  derived fields (ids, codecs, protocols, byte counts, timings) are stored.
* Every run happens with a private HOME/XDG_*/TMPDIR, so no runtime cache can
  touch the user's profile, and the virtual environment lives under the
  git-ignored ``.tools/`` directory.
* No daemon survives the harness: the persistent workers are terminated and
  the harness verifies that no Bun/helper process is left behind.

Usage
-----
    scripts/benchmark-ytdlp-worker.py \
        --yt-dlp .tools/resolver/yt-dlp \
        --bun    .tools/resolver/bun \
        --venv   .tools/ytdlp-worker-venv \
        --repeats 3 \
        --out-json packaging/resolver/validation/linux-ytdlp-worker-evaluation.json

Exit code is 0 only when every requested extraction succeeded and every
successful result passed the head/tail range probe.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import platform
import resource
import select
import shutil
import signal
import statistics
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
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
PROBE_URL = BASE.probe_url
SHA256_OF = BASE.sha256_of
CLASSIFY_FAILURE = BASE.classify_failure

# ---------------------------------------------------------------------------
# Pinned upstream artifacts
# ---------------------------------------------------------------------------

YTDLP_VERSION = "2026.08.19"
YTDLP_SHA256 = "58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a"
YTDLP_SOURCE_TARBALL_SHA256 = "072aad4f2a7604e92155f61a275a4752dc64046c8f6d90df3710525d94cd37c1"
EJS_VERSION = "0.8.0"
EJS_WHEEL_SHA256 = "79300e5fca7f937a1eeede11f0456862c1b41107ce1d726871e0207424f4bdb4"
BUN_VERSION = "1.4.3"
BUN_BINARY_SHA256 = "7ce7d6b654eddeec20afddf2396317fba6dc19ab52788a27e894e1806bf8f24b"

# The resolver flag set of the shipped desktop resolver
# (stream-resolver/src/main/kotlin/.../YtDlpStreamResolver.kt). The API worker
# mirrors it through YoutubeDL parameters; see ``_build_params``.
RESOLVER_FLAGS: Tuple[str, ...] = (
    "--ignore-config",
    "--no-plugin-dirs",
    "--no-remote-components",
    "--no-js-runtimes",
    "--no-cache-dir",
    "--no-playlist",
    "--no-progress",
    "--socket-timeout",
    "12",
    "--retries",
    "1",
    "--extractor-retries",
    "1",
    "--skip-download",
    "--dump-single-json",
)

# ``YtDlpStreamResolver.formatSelector`` (stream-resolver module).
QUALITY_SELECTORS: Dict[str, str] = {
    "LOW": (
        "bestaudio[protocol=https][abr<=70]/bestaudio[protocol=http][abr<=70]"
        "/bestaudio[protocol=https]/bestaudio[protocol=http]"
    ),
    "HIGH": (
        "bestaudio[protocol=https][abr<=160]/bestaudio[protocol=http][abr<=160]"
        "/bestaudio[protocol=https]/bestaudio[protocol=http]"
    ),
    "HIGHEST": "bestaudio[protocol=https]/bestaudio[protocol=http]",
}
DEFAULT_QUALITY = "HIGH"

# The three videos required by the evaluation brief.
CHALLENGED_VIDEO = "dQw4w9WgXcQ"     # a true ``n`` challenge video
SIMPLE_VIDEOS = ("jNQXAC9IVRw", "aqz-KE-bpKQ")

# The player client that forces the ``n`` challenge (the same one the existing
# runtime comparison used).
CHALLENGE_CLIENT = "web_embedded"

CONFIGS: Tuple[str, ...] = (
    "frozen_cli",
    "fresh_python",
    "persistent_python_new_ydl",
    "persistent_python_reuse_ydl",
)

PERSISTENT_CONFIGS = ("persistent_python_new_ydl", "persistent_python_reuse_ydl")


# ---------------------------------------------------------------------------
# Small helpers
# ---------------------------------------------------------------------------


def percentile(values: Sequence[float], fraction: float) -> Optional[float]:
    """Historical sampled-rank estimator, without interpolation (Python round-to-even).

    The exact convention is recorded in report.method.quantiles; it differs from
    the conventional averaged median for some even sample sizes.
    """
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return float(ordered[0])
    rank = max(1, min(len(ordered), int(round(fraction * len(ordered) + 0.5))))
    return float(ordered[rank - 1])


def summarize_numbers(values: Sequence[Optional[float]]) -> Dict[str, Optional[float]]:
    clean = [float(value) for value in values if value is not None]
    if not clean:
        return {"count": 0, "min": None, "p50": None, "p90": None, "p95": None, "max": None, "mean": None}
    return {
        "count": len(clean),
        "min": round(min(clean), 3),
        "p50": round(percentile(clean, 0.50) or 0.0, 3),
        "p90": round(percentile(clean, 0.90) or 0.0, 3),
        "p95": round(percentile(clean, 0.95) or 0.0, 3),
        "max": round(max(clean), 3),
        "mean": round(statistics.fmean(clean), 3),
    }


def cpu_of(who: int) -> float:
    usage = resource.getrusage(who)
    return usage.ru_utime + usage.ru_stime


def child_cpu() -> float:
    return cpu_of(resource.RUSAGE_CHILDREN)


def self_cpu() -> float:
    return cpu_of(resource.RUSAGE_SELF)


def file_sha256(path: str) -> Optional[str]:
    try:
        return SHA256_OF(path)
    except OSError:
        return None


# ---------------------------------------------------------------------------
# Process tree memory sampler (RSS + PSS)
# ---------------------------------------------------------------------------


class TreeMemorySampler(threading.Thread):
    """Samples the summed RSS and PSS of a process tree from ``/proc``.

    Limitations (documented on purpose, same caveats as the existing harness):
      * The sampler polls ``/proc`` every ``interval`` seconds, so a spike that
        lives entirely between two samples is not observed: the reported peaks
        are lower bounds.
      * A process that exits between two samples is not counted.
      * RSS counts shared pages once per mapping process, so the tree RSS
        overstates the physical footprint. PSS divides shared pages between
        their users and is the closer approximation of the real cost.
      * ``smaps_rollup`` is not available on every kernel; PSS is then 0 and
        the report says so.
    """

    def __init__(self, pid: int, interval: float = 0.05) -> None:
        super().__init__(daemon=True, name="tree-memory-sampler")
        self.pid = pid
        self.interval = interval
        self.peak_rss = 0
        self.peak_pss = 0
        self.peak_processes = 0
        self.samples = 0
        self.pss_supported = True
        self._stop = threading.Event()
        self._page_size = os.sysconf("SC_PAGE_SIZE") if hasattr(os, "sysconf") else 4096

    def _children(self, pid: int) -> List[int]:
        out: List[int] = []
        try:
            tasks = os.listdir("/proc/%d/task" % pid)
        except OSError:
            return out
        for task in tasks:
            try:
                with open("/proc/%d/task/%s/children" % (pid, task), "r") as handle:
                    out.extend(int(part) for part in handle.read().split())
            except (OSError, ValueError):
                continue
        return out

    def _tree(self) -> List[int]:
        seen: List[int] = []
        pending = [self.pid]
        visited = set()
        while pending:
            current = pending.pop()
            if current in visited:
                continue
            visited.add(current)
            seen.append(current)
            pending.extend(self._children(current))
        return seen

    def _rss_of(self, pid: int) -> int:
        try:
            with open("/proc/%d/statm" % pid, "r") as handle:
                fields = handle.read().split()
            return int(fields[1]) * self._page_size
        except (OSError, IndexError, ValueError):
            return 0

    def _pss_of(self, pid: int) -> int:
        try:
            with open("/proc/%d/smaps_rollup" % pid, "r") as handle:
                for line in handle:
                    if line.startswith("Pss:"):
                        return int(line.split()[1]) * 1024
        except (OSError, IndexError, ValueError):
            return 0
        return 0

    def sample(self) -> Tuple[int, int]:
        rss = 0
        pss = 0
        pids = self._tree()
        for pid in pids:
            rss += self._rss_of(pid)
            pss += self._pss_of(pid)
        self.samples += 1
        if pss == 0 and pids:
            self.pss_supported = False
        self.peak_rss = max(self.peak_rss, rss)
        self.peak_pss = max(self.peak_pss, pss)
        self.peak_processes = max(self.peak_processes, len(pids))
        return rss, pss

    def run(self) -> None:
        while not self._stop.is_set():
            self.sample()
            self._stop.wait(self.interval)

    def stop(self) -> None:
        self._stop.set()
        self.join(timeout=3.0)


def process_memory(pid: int) -> Dict[str, int]:
    """RSS/PSS of a single process (used for the idle-worker measurement)."""
    sampler = TreeMemorySampler(pid, interval=1.0)
    return {"rss_bytes": sampler._rss_of(pid), "pss_bytes": sampler._pss_of(pid)}


def find_leftovers(markers: Sequence[str], own_pid: int) -> List[Dict[str, Any]]:
    """Find processes whose command line still mentions one of ``markers``."""
    found: List[Dict[str, Any]] = []
    for entry in os.listdir("/proc"):
        if not entry.isdigit():
            continue
        pid = int(entry)
        if pid == own_pid:
            continue
        try:
            with open("/proc/%d/cmdline" % pid, "rb") as handle:
                raw = handle.read()
        except OSError:
            continue
        if not raw:
            continue
        cmdline = raw.replace(b"\x00", b" ").decode("utf-8", "replace").strip()
        if not cmdline:
            continue
        for marker in markers:
            if marker in cmdline:
                found.append({"pid": pid, "executable": os.path.basename(cmdline.split(" ")[0])})
                break
    return found


# ---------------------------------------------------------------------------
# Bun invocation shim
# ---------------------------------------------------------------------------


def make_counting_shim(real_binary: str, log_path: str) -> str:
    """A wrapper that logs every invocation and delegates to the real binary."""
    directory = tempfile.mkdtemp(prefix="archivetune-bun-shim-")
    shim = os.path.join(directory, "bun-shim")
    counter = os.path.abspath(log_path)
    with open(shim, "w") as handle:
        handle.write(
            "#!/bin/sh\n"
            'printf "%%s\\n" "$*" >> "%s"\n'
            'exec "%s" "$@"\n' % (counter, real_binary)
        )
    os.chmod(shim, 0o755)
    return shim


def count_solver_invocations(log_path: str) -> int:
    """Count the Bun invocations that actually ran the challenge solver.

    yt-dlp probes every enabled runtime once to detect its version
    (``bun --version``). Those probes are not solver runs and are excluded.
    """
    try:
        with open(log_path, "r") as handle:
            lines = [line.strip() for line in handle if line.strip()]
    except OSError:
        return 0
    return sum(1 for line in lines if "--version" not in line)


# ---------------------------------------------------------------------------
# Worker (persistent Python process driven over a JSON-lines pipe)
# ---------------------------------------------------------------------------


class _CaptureLogger:
    """A yt-dlp logger that keeps diagnostics in memory instead of printing."""

    def __init__(self, limit: int = 400) -> None:
        self.messages: List[str] = []
        self.limit = limit
        self.debug_count = 0
        self.solver_messages = 0

    def _record(self, message: Any) -> None:
        text = str(message)
        if "Solving JS challenges" in text:
            self.solver_messages += 1
        if len(self.messages) < self.limit:
            self.messages.append(text)

    def debug(self, message: Any) -> None:
        self.debug_count += 1
        self._record(message)

    def info(self, message: Any) -> None:
        self._record(message)

    def warning(self, message: Any) -> None:
        self._record(message)

    def error(self, message: Any) -> None:
        self._record(message)


def _worker_selector(provider):
    """A callable format selector that honours the *current* quality.

    ``YoutubeDL.__init__`` builds ``self.format_selector`` once, from
    ``params['format']``. A plain string would therefore freeze the quality
    preference for the whole life of the object. The public API allows a
    callable, and ``build_format_selector`` is a public method, so the worker
    re-builds the selector for the current quality on every request instead of
    rebuilding the whole object.

    ``provider()`` returns the selector function for the current quality;
    yt-dlp calls it with the format context and expects the selected formats.
    """

    def selector(ctx):
        current = provider()
        if current is None:  # pragma: no cover - defensive
            return []
        return current(ctx)

    return selector


def _build_params(
    request: Dict[str, Any],
    bun_path: str,
    logger: _CaptureLogger,
    selector_provider,
) -> Dict[str, Any]:
    """Map the shipped resolver CLI flags onto public ``YoutubeDL`` parameters."""
    params: Dict[str, Any] = {
        "ignoreconfig": True,
        "noprogress": True,
        "noplaylist": True,
        "skip_download": True,
        "socket_timeout": 12,
        "retries": 1,
        "extractor_retries": 1,
        "cachedir": False,
        "js_runtimes": {"bun": {"path": bun_path}},
        "remote_components": [],
        "verbose": True,
        "logger": logger,
        "format": _worker_selector(selector_provider),
        "consoletitle": False,
        "no_color": True,
    }
    client = request.get("player_client")
    if client:
        # The CLI wraps every --extractor-args value in a list
        # (yt_dlp/options.py: _extractor_arg_parser); InfoExtractor._configuration_arg
        # applies list() to the raw value, so a plain string would be split into
        # characters. The API therefore has to use the same list shape.
        params["extractor_args"] = {"youtube": {"player_client": [client]}}
    return params


class _Worker:
    """The persistent Python worker."""

    def __init__(self, bun_path: str, reuse_ydl: bool) -> None:
        self.bun_path = bun_path
        self.reuse_ydl = reuse_ydl
        self.ydl: Any = None
        self.session_fingerprint: Optional[Tuple[Any, ...]] = None
        self.quality: str = DEFAULT_QUALITY
        self.selector_spec: Optional[str] = None
        self.selector_fn: Any = None
        self.logger = _CaptureLogger()
        self.requests_served = 0
        self.ydl_constructions = 0

    # -- format selector ----------------------------------------------------
    def current_selector(self):
        """The selector function for the quality of the *current* request."""
        spec = QUALITY_SELECTORS[self.quality]
        if self.selector_fn is None or self.selector_spec != spec:
            self.selector_fn = self.ydl.build_format_selector(spec)
            self.selector_spec = spec
        return self.selector_fn

    # -- session handling ---------------------------------------------------
    @staticmethod
    def _fingerprint(request: Dict[str, Any]) -> Tuple[Any, ...]:
        """Everything that changes the meaning of an extraction.

        The cookie *path* plus its size and modification time are used as the
        identity of a session; the cookie values themselves are never hashed,
        copied or logged.

        The extractor arguments belong here as well: ``YoutubeDL.__init__``
        snapshots ``params``, and the extractor reads ``extractor_args`` from
        that snapshot, so a reused object would silently keep the player
        client of the *first* request. The quality preference is deliberately
        not part of the fingerprint because the callable format selector
        honours it without a rebuild.
        """
        cookie = request.get("cookie_file")
        cookie_state: Tuple[Any, ...] = (None, None, None)
        if cookie:
            try:
                stat = os.stat(cookie)
                cookie_state = (os.path.abspath(cookie), stat.st_size, stat.st_mtime_ns)
            except OSError:
                cookie_state = (os.path.abspath(cookie), None, None)
        return (
            cookie_state,
            request.get("proxy"),
            request.get("data_sync_id"),
            request.get("player_client"),
        )

    def _ensure_ydl(self, request: Dict[str, Any]) -> Dict[str, Any]:
        """Return the ``YoutubeDL`` for this request and report what happened.

        The invalidation policy is deliberately conservative: anything that
        carries credentials (cookie jar, proxy, account context) forces a new
        ``YoutubeDL`` object, so a reused worker can never serve one account's
        session to another. The quality preference is *not* part of the
        fingerprint because the callable format selector honours it without a
        rebuild.
        """
        fingerprint = self._fingerprint(request)
        if self.ydl is not None and self.reuse_ydl and fingerprint == self.session_fingerprint:
            self.quality = request.get("quality", DEFAULT_QUALITY)
            return {"reused": True, "rebuilt": False}
        params = _build_params(request, self.bun_path, self.logger, self.current_selector)
        self.quality = request.get("quality", DEFAULT_QUALITY)
        started = time.perf_counter()
        from yt_dlp import YoutubeDL

        ydl = YoutubeDL(params)
        construct_ms = (time.perf_counter() - started) * 1000.0
        self.ydl = ydl
        # The selector function belongs to the previous object; rebuild it.
        self.selector_fn = None
        self.selector_spec = None
        self.session_fingerprint = fingerprint
        self.ydl_constructions += 1
        return {"reused": False, "rebuilt": self.reuse_ydl, "construct_ms": construct_ms}

    # -- request handling ---------------------------------------------------
    def handle(self, request: Dict[str, Any]) -> Dict[str, Any]:
        response: Dict[str, Any] = {"id": request.get("id"), "ok": False}
        self.logger.messages.clear()
        solver_before = self.logger.solver_messages
        cpu_self_before = self_cpu()
        cpu_children_before = child_cpu()
        try:
            state = self._ensure_ydl(request)
            response["state"] = {key: value for key, value in state.items() if key != "construct_ms"}
            if "construct_ms" in state:
                response["state"]["construct_ms"] = round(state["construct_ms"], 3)
            video_id = request["video_id"]
            url = "https://www.youtube.com/watch?v=%s" % video_id
            extract_started = time.perf_counter()
            if request.get("stub"):
                # Offline fixture: never contact the network.
                info = _stub_info(request)
            else:
                info = self.ydl.extract_info(url, download=False)
            extract_ms = (time.perf_counter() - extract_started) * 1000.0
            response["ok"] = True
            response["stage_ms"] = {"extract": round(extract_ms, 3)}
            response["summary"] = _summarize_info(info)
            # The signed URL and its headers travel back in memory only. The
            # harness performs the range probe and stores nothing but the
            # redacted probe summary.
            response["media_url"] = info.get("url")
            response["media_headers"] = dict(info.get("http_headers") or {})
        except Exception as error:  # noqa: BLE001 - every failure is reported
            response["error_class"] = CLASSIFY_FAILURE(1, "%s: %s" % (type(error).__name__, error), False)
            response["error"] = REDACT("%s: %s" % (type(error).__name__, error), 400)
        finally:
            response["cpu_s"] = {
                "self": round(self_cpu() - cpu_self_before, 4),
                "children": round(child_cpu() - cpu_children_before, 4),
            }
            response["solver_diagnostics"] = self.logger.solver_messages - solver_before
            response["diagnostic_tail"] = [REDACT(line, 200) for line in self.logger.messages[-6:]]
            self.requests_served += 1
        return response


def _stub_info(request: Dict[str, Any]) -> Dict[str, Any]:
    """A synthetic extraction result for the offline fixture.

    The offline fixture never contacts YouTube: the worker returns this
    synthetic result instead of calling ``extract_info``. The URL points at a
    local range-serving HTTP server, so the head/tail probe still exercises the
    real probe code path.
    """
    stub = request.get("stub") or {}
    return {
        "id": request.get("video_id"),
        "format_id": stub.get("format_id", "140"),
        "ext": stub.get("ext", "m4a"),
        "acodec": stub.get("acodec", "mp4a.40.2"),
        "vcodec": "none",
        "protocol": stub.get("protocol", "https"),
        "abr": stub.get("abr", 129.5),
        "tbr": stub.get("abr", 129.5),
        "filesize": stub.get("filesize", 16384),
        "url": stub.get("url", ""),
        "http_headers": dict(stub.get("headers") or {}),
    }


def _summarize_info(info: Dict[str, Any]) -> Dict[str, Any]:
    """Reduce a yt-dlp info dict to redacted, derived fields only."""
    url = str(info.get("url") or "")
    parsed = urllib.parse.urlparse(url)
    query = urllib.parse.parse_qs(parsed.query or "")
    headers = info.get("http_headers") or {}
    expires_in = None
    expire = query.get("expire")
    if expire and expire[0].isdigit():
        expires_in = int(expire[0]) - int(time.time())
    return {
        "video_id": info.get("id"),
        "format_id": info.get("format_id"),
        "ext": info.get("ext"),
        "acodec": info.get("acodec"),
        "vcodec": info.get("vcodec"),
        "protocol": info.get("protocol"),
        "abr": info.get("abr"),
        "tbr": info.get("tbr"),
        "filesize": info.get("filesize"),
        "filesize_approx": info.get("filesize_approx"),
        "url_length": len(url),
        "url_has_n_parameter": "n" in query,
        "url_has_pot_parameter": "pot" in query,
        "url_expires_in_s": expires_in,
        "header_names": sorted(str(name).lower() for name in headers),
        "http_status": info.get("http_status"),
    }


def _summarize_probe(probe: Any) -> Dict[str, Any]:
    return {
        "attempted": probe.attempted,
        "status": probe.status,
        "bytes_read": probe.bytes_read,
        "content_type": probe.content_type,
        "ranged_response": probe.ranged_response,
        "magic_hex": probe.magic_hex,
        "tail_status": probe.tail_status,
        "tail_bytes_read": probe.tail_bytes_read,
        "error": probe.error,
    }


def _worker_main(argv: Sequence[str]) -> int:
    parser = argparse.ArgumentParser(description="ArchiveTune yt-dlp worker (developer-only)")
    parser.add_argument("--worker", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--bun", required=True)
    parser.add_argument("--reuse-ydl", choices=("0", "1"), default="0")
    parser.add_argument("--once", action="store_true", help="serve exactly one request and exit")
    args = parser.parse_args(list(argv))

    # Keep the protocol stream clean: yt-dlp writes screen output to stdout, so
    # stdout is redirected to stderr and the protocol uses a duplicated fd.
    protocol_fd = os.dup(1)
    os.dup2(2, 1)
    sys.stdout = sys.stderr
    protocol = os.fdopen(protocol_fd, "w", encoding="utf-8")

    script_started = time.perf_counter()
    import yt_dlp  # noqa: F401 - measured, then used through YoutubeDL
    from yt_dlp import YoutubeDL  # noqa: F401 - imported for the worker path

    import_done = time.perf_counter()

    worker = _Worker(args.bun, args.reuse_ydl == "1")
    protocol.write(json.dumps({
        "ready": True,
        "pid": os.getpid(),
        "stage_ms": {"import": round((import_done - script_started) * 1000.0, 3)},
        "yt_dlp_version": getattr(getattr(yt_dlp, "version", None), "__version__", None),
    }, default=str) + "\n")
    protocol.flush()

    while True:
        line = sys.stdin.readline()
        if not line:
            break
        line = line.strip()
        if not line:
            continue
        try:
            request = json.loads(line)
        except ValueError:
            continue
        if request.get("command") == "shutdown":
            break
        response = worker.handle(request)
        response["worker_stats"] = {
            "requests_served": worker.requests_served,
            "ydl_constructions": worker.ydl_constructions,
            "reuse_ydl": worker.reuse_ydl,
        }
        protocol.write(json.dumps(response, default=str) + "\n")
        protocol.flush()
        if args.once:
            break
    protocol.close()
    return 0


# ---------------------------------------------------------------------------
# Harness: running one request per configuration
# ---------------------------------------------------------------------------


@dataclass
class Scenario:
    key: str
    video_id: str
    player_client: Optional[str]
    label: str
    workload: str


def build_scenarios() -> List[Scenario]:
    """The workloads of this evaluation.

    Two workloads are kept strictly apart:

    ``default_client``
        The production-parity workload. The resolver does not pass a player
        client, so the default client is used. The challenged video is part of
        this workload because yt-dlp still solves a challenge for it with the
        default client; the two simple videos are the unchallenged control.
    ``forced_web_embedded``
        The same challenged video with an explicit ``web_embedded`` player
        client. This is the explicit challenge control of the existing runtime
        comparison and it is never mixed with the default-client numbers.
    """
    return [
        Scenario(
            key="challenged_default",
            video_id=CHALLENGED_VIDEO,
            player_client=None,
            label="challenged video with the default player client (production parity)",
            workload="default_client",
        ),
        Scenario(
            key="challenged_web_embedded",
            video_id=CHALLENGED_VIDEO,
            player_client=CHALLENGE_CLIENT,
            label="challenged video with the explicit %s player client" % CHALLENGE_CLIENT,
            workload="forced_web_embedded",
        ),
        Scenario(key="simple_a", video_id=SIMPLE_VIDEOS[0], player_client=None,
                 label="simple video with the default player client", workload="default_client"),
        Scenario(key="simple_b", video_id=SIMPLE_VIDEOS[1], player_client=None,
                 label="simple video with the default player client", workload="default_client"),
    ]


@dataclass
class RunResult:
    config: str
    scenario: str
    video_id: str
    round_index: int
    repeat_index: int
    kind: str                      # cold | warm_same_video | new_video
    ok: bool = False
    failure_class: str = "NOT_RUN"
    wall_ms: Optional[float] = None
    stage_ms: Dict[str, float] = field(default_factory=dict)
    cpu_s: Dict[str, float] = field(default_factory=dict)
    peak_rss_bytes: Optional[int] = None
    peak_pss_bytes: Optional[int] = None
    peak_processes: Optional[int] = None
    idle_rss_bytes: Optional[int] = None
    idle_pss_bytes: Optional[int] = None
    solver_invocations: Optional[int] = None
    solver_diagnostics: Optional[int] = None
    probe: Dict[str, Any] = field(default_factory=dict)
    summary: Dict[str, Any] = field(default_factory=dict)
    diagnostics: List[str] = field(default_factory=list)
    note: str = ""
    attempt: int = 1
    attempts_total: int = 1
    final_attempt: bool = True
    workload: str = ""
    session: str = ""

    def to_json(self) -> Dict[str, Any]:
        return {
            "config": self.config,
            "scenario": self.scenario,
            "video_id": self.video_id,
            "round": self.round_index,
            "repeat": self.repeat_index,
            "kind": self.kind,
            "ok": self.ok,
            "failure_class": self.failure_class,
            "wall_ms": self.wall_ms,
            "stage_ms": self.stage_ms,
            "cpu_s": self.cpu_s,
            "peak_tree_rss_bytes": self.peak_rss_bytes,
            "peak_tree_pss_bytes": self.peak_pss_bytes,
            "peak_process_count": self.peak_processes,
            "idle_rss_bytes": self.idle_rss_bytes,
            "idle_pss_bytes": self.idle_pss_bytes,
            "solver_invocations": self.solver_invocations,
            "solver_diagnostics": self.solver_diagnostics,
            "probe": self.probe,
            "summary": self.summary,
            "diagnostics": self.diagnostics,
            "attempt": self.attempt,
            "attempts_total": self.attempts_total,
            "final_attempt": self.final_attempt,
            "workload": self.workload,
            "session": self.session,
            "note": self.note,
        }


class _WorkerHandle:
    """Owns one worker process and talks the JSON-lines protocol with it.

    The pipes are binary and read through ``select`` so that a hung worker
    cannot stall the whole benchmark: every read honours the request timeout.
    """

    def __init__(self, python: str, script: str, bun: str, env: Dict[str, str],
                 reuse_ydl: bool, timeout: float, once: bool = False,
                 on_spawn=None) -> None:
        self.timeout = timeout
        self.request_count = 0
        self.last_video: Optional[str] = None
        self.startup: Dict[str, Any] = {}
        self.startup_ms: Optional[float] = None
        self.stderr_lines: List[str] = []
        self._buffer = b""
        argv = [python, script, "--worker", "--bun", bun, "--reuse-ydl", "1" if reuse_ydl else "0"]
        if once:
            argv.append("--once")
        self.reuse_ydl = bool(reuse_ydl)
        spawned = time.perf_counter()
        self.process = subprocess.Popen(
            argv,
            env=env,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            bufsize=0,
            start_new_session=True,
        )
        if on_spawn is not None:
            on_spawn(self.process.pid)
        self._drain = threading.Thread(target=self._drain_stderr, daemon=True)
        self._drain.start()
        ready_line = self._read_line()
        self.startup_ms = round((time.perf_counter() - spawned) * 1000.0, 3)
        if not ready_line:
            stderr = "\n".join(self.stderr_lines[-10:])
            raise RuntimeError("worker did not start: %s" % REDACT(stderr, 400))
        self.startup = json.loads(ready_line)

    def _read_line(self, timeout: Optional[float] = None) -> str:
        """Read one newline terminated line, honouring the timeout."""
        limit = self.timeout if timeout is None else timeout
        deadline = time.monotonic() + limit
        while b"\n" not in self._buffer:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("worker did not answer within %.1fs" % limit)
            ready, _, _ = select.select([self.process.stdout], [], [], remaining)
            if not ready:
                raise TimeoutError("worker did not answer within %.1fs" % limit)
            chunk = os.read(self.process.stdout.fileno(), 65536)
            if not chunk:
                raise RuntimeError("worker closed the pipe")
            self._buffer += chunk
        line, _, self._buffer = self._buffer.partition(b"\n")
        return line.decode("utf-8", "replace")

    def _drain_stderr(self) -> None:
        try:
            while True:
                chunk = self.process.stderr.read(4096)
                if not chunk:
                    break
                if len(self.stderr_lines) < 200:
                    self.stderr_lines.append(chunk.decode("utf-8", "replace").strip())
        except (OSError, ValueError):
            pass

    @property
    def pid(self) -> int:
        return self.process.pid

    def request(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        self.process.stdin.write((json.dumps(payload) + "\n").encode("utf-8"))
        self.process.stdin.flush()
        line = self._read_line()
        response = json.loads(line)
        response["worker_startup"] = self.startup
        response["worker_startup_ms"] = self.startup_ms
        self.request_count += 1
        self.last_video = payload.get("video_id")
        return response

    def stop(self) -> None:
        if self.process.poll() is None:
            try:
                self.process.stdin.write(b'{"command": "shutdown"}\n')
                self.process.stdin.flush()
                self.process.stdin.close()
            except (OSError, ValueError):
                pass
            try:
                self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(os.getpgid(self.process.pid), signal.SIGKILL)
                except OSError:
                    self.process.kill()
                self.process.wait(timeout=10)
        for stream in (self.process.stdout, self.process.stderr):
            try:
                if stream:
                    stream.close()
            except OSError:
                pass


class _LocalMediaServer:
    """A tiny range-serving HTTP server for the offline fixture.

    It exists so that the head/tail range probe can be exercised without any
    external network: the probe reads real bytes from 127.0.0.1.
    """

    def __init__(self, size: int = 64 * 1024) -> None:
        import http.server
        import socketserver

        payload = bytes(range(256)) * (size // 256)

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):  # noqa: N802 - http.server API
                start = 0
                spec = self.headers.get("Range", "")
                if spec.startswith("bytes="):
                    first = spec.split("=", 1)[1].split("-")[0]
                    start = int(first) if first.isdigit() else 0
                body = payload[start:start + 4096]
                self.send_response(206)
                self.send_header("Content-Type", "audio/mp4")
                self.send_header("Content-Range", "bytes %d-%d/%d" % (start, start + len(body) - 1, len(payload)))
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):  # noqa: A003 - silence the fixture
                pass

        class Server(socketserver.ThreadingTCPServer):
            allow_reuse_address = True
            daemon_threads = True

        self._server = Server(("127.0.0.1", 0), Handler)
        self.port = self._server.server_address[1]
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()

    @property
    def url(self) -> str:
        return "http://127.0.0.1:%d/media" % self.port

    def close(self) -> None:
        self._server.shutdown()
        self._server.server_close()


class Harness:
    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        self.ytdlp = os.path.abspath(args.yt_dlp)
        self.bun = os.path.abspath(args.bun)
        self.venv = os.path.abspath(args.venv)
        self.python = os.path.join(self.venv, "bin", "python")
        if not os.path.exists(self.python):
            self.python = os.path.join(self.venv, "Scripts", "python.exe")
        self.tmp_root = tempfile.mkdtemp(prefix="archivetune-worker-bench-")
        self.home = os.path.join(self.tmp_root, "home")
        os.makedirs(self.home, exist_ok=True)
        self.shim_log = os.path.join(self.tmp_root, "bun-invocations.log")
        self.shim = make_counting_shim(self.bun, self.shim_log)
        self.env = HERMETIC_ENV(self.home)
        self.env["YTDLP_NO_PLUGINS"] = "1"
        self.env["DO_NOT_TRACK"] = "1"
        self.env["BUN_OPTIONS"] = ""
        self.env["TMPDIR"] = os.path.join(self.tmp_root, "tmp")
        os.makedirs(self.env["TMPDIR"], exist_ok=True)
        self.workers: Dict[str, _WorkerHandle] = {}
        self.media_server: Optional[_LocalMediaServer] = None
        self.stub: Optional[Dict[str, Any]] = None
        if getattr(args, "offline_stub", False):
            self.media_server = _LocalMediaServer()
            self.stub = {
                "url": "%s?expire=%d&n=stubvalue" % (self.media_server.url, int(time.time()) + 21600),
                "headers": {"User-Agent": "archiveTune-offline-fixture", "Accept-Encoding": "identity"},
            }
            self.env["STUB_MEDIA_URL"] = self.media_server.url

    # -- environment --------------------------------------------------------
    def stop_workers(self) -> None:
        for handle in self.workers.values():
            handle.stop()
        self.workers.clear()

    def close(self) -> None:
        self.stop_workers()
        if self.media_server is not None:
            self.media_server.close()
            self.media_server = None
        shutil.rmtree(self.tmp_root, ignore_errors=True)

    def solver_invocations(self) -> int:
        return count_solver_invocations(self.shim_log)

    # -- start-up cost (no network) -----------------------------------------
    def measure_startup(self, repeats: int) -> Dict[str, Any]:
        """Measure the fixed per-process overhead of every runtime flavour."""
        results: Dict[str, Any] = {}

        def timed(argv: Sequence[str], env: Dict[str, str], parse: bool = False):
            walls: List[float] = []
            parsed: List[List[float]] = []
            for _ in range(repeats):
                started = time.perf_counter()
                completed = subprocess.run(
                    list(argv),
                    env=env,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.DEVNULL,
                    timeout=180,
                    check=False,
                )
                walls.append((time.perf_counter() - started) * 1000.0)
                if parse:
                    try:
                        values = [float(part) for part in completed.stdout.decode().split()]
                        parsed.append(values)
                    except (ValueError, UnicodeDecodeError):
                        parsed.append([])
            return walls, parsed

        # The control pays this on every request.
        walls, _parsed = timed([self.ytdlp, "--version"], self.env)
        results["frozen_cli_startup"] = summarize_numbers(walls)

        lazy_env = dict(self.env)
        eager_env = dict(self.env)
        eager_env["YTDLP_NO_LAZY_EXTRACTORS"] = "1"

        # The worker pays the import plus one YoutubeDL construction. With the
        # upstream lazy_extractors module the extractor registry is imported
        # on demand; without it every extractor module is imported.
        startup_code = (
            "import time; t=time.perf_counter(); import yt_dlp;"
            " from yt_dlp import YoutubeDL; t2=time.perf_counter();"
            " ydl=YoutubeDL({'ignoreconfig': True, 'cachedir': False});"
            " print((t2-t)*1000, (time.perf_counter()-t2)*1000)"
        )
        for key, env in (("python_startup_lazy", lazy_env), ("python_startup_eager", eager_env)):
            walls, parsed = timed([self.python, "-c", startup_code], env, parse=True)
            results[key] = {
                "wall_ms": summarize_numbers(walls),
                "import_ms": summarize_numbers([values[0] for values in parsed if len(values) > 0]),
                "ydl_construct_ms": summarize_numbers([values[1] for values in parsed if len(values) > 1]),
            }
        results["note"] = (
            "The import cost is the same in both variants; the extractor registry is imported by "
            "YoutubeDL.__init__ (add_default_info_extractors), so the difference shows up in the "
            "construction step. The frozen control is a self-extracting bundle and therefore pays "
            "its own unpacking cost on every request."
        )
        return results

    def self_check(self) -> List[Dict[str, Any]]:
        """Offline regression checks for the harness itself.

        These checks never touch the network: the worker answers from the
        offline stub. They exist because the mode flag of the persistent
        workers is the difference between two configurations of this
        evaluation, and a non-empty string is truthy in Python - a bug that
        silently turned the "new object per request" configuration into the
        "reusing" one.
        """
        checks: List[Dict[str, Any]] = []
        scenario = Scenario(
            key="self_check", video_id=SIMPLE_VIDEOS[0], player_client=None,
            label="offline self check", workload="default_client",
        )
        request = {
            "id": "self-check",
            "video_id": scenario.video_id,
            "player_client": None,
            "quality": self.args.quality,
        }
        if self.stub is not None:
            request["stub"] = self.stub

        for config, expected_reuse in (("persistent_python_new_ydl", False),
                                       ("persistent_python_reuse_ydl", True)):
            handle = self.worker_handle(config)
            checks.append({
                "check": "worker handle mode flag for %s" % config,
                "ok": handle.reuse_ydl is expected_reuse,
                "value": handle.reuse_ydl,
            })
            handle.request(request)
            second = handle.request(request)
            stats = second.get("worker_stats") or {}
            checks.append({
                "check": "worker reports its own mode for %s" % config,
                "ok": bool(stats.get("reuse_ydl")) is expected_reuse,
                "value": stats.get("reuse_ydl"),
            })
            constructions = stats.get("ydl_constructions")
            if expected_reuse:
                checks.append({
                    "check": "reusing worker keeps one object for two identical requests",
                    "ok": constructions == 1,
                    "value": constructions,
                })
            else:
                checks.append({
                    "check": "new-object worker builds one object per request",
                    "ok": constructions == 2,
                    "value": constructions,
                })
            changed = dict(request)
            changed["player_client"] = CHALLENGE_CLIENT
            third = handle.request(changed)
            after = (third.get("worker_stats") or {}).get("ydl_constructions")
            if expected_reuse:
                checks.append({
                    "check": "reusing worker rebuilds when the extractor arguments change",
                    "ok": after == 2,
                    "value": after,
                })
            else:
                checks.append({
                    "check": "new-object worker rebuilds on every request",
                    "ok": after == 3,
                    "value": after,
                })

        # The guard that would have caught the bug: a worker that runs in the
        # wrong mode has to be reported, not silently accepted.
        wrong = _WorkerHandle(
            self.python,
            os.path.abspath(__file__),
            self.shim,
            self.env,
            reuse_ydl=False,
            timeout=self.args.timeout,
        )
        self.workers["persistent_python_reuse_ydl"] = wrong
        try:
            result = self.run_python("persistent_python_reuse_ydl", scenario, 0, 0, "cold")
            checks.append({
                "check": "harness reports a worker that runs in the wrong mode",
                "ok": result.failure_class == "WORKER_MODE_MISMATCH",
                "value": result.failure_class,
            })
        finally:
            wrong.stop()
            self.workers.pop("persistent_python_reuse_ydl", None)
        return checks

    # -- upstream cache probe ------------------------------------------------
    def cache_probe(self, scenario: Scenario) -> Dict[str, Any]:
        """Does the upstream on-disk cache remove the challenge solver work?

        yt-dlp caches public signature data under ``--cache-dir``. The
        production resolver passes ``--no-cache-dir`` and, when a cache folder
        is configured, ``--cache-dir`` as well. This probe runs the frozen CLI
        twice against the same cache directory and records how often Bun was
        started and what the cache directory holds afterwards.
        """
        cache_dir = os.path.join(self.tmp_root, "cache")
        os.makedirs(cache_dir, exist_ok=True)
        selector = QUALITY_SELECTORS[self.args.quality]
        results: List[Dict[str, Any]] = []
        for attempt in range(2):
            argv = [self.ytdlp]
            argv.extend(RESOLVER_FLAGS)
            argv.extend(["--js-runtimes", "bun:%s" % self.shim])
            argv.extend(["--cache-dir", cache_dir, "--format", selector])
            if scenario.player_client:
                argv.extend(["--extractor-args", "youtube:player_client=%s" % scenario.player_client])
            argv.extend(["-v", "--", "https://www.youtube.com/watch?v=%s" % scenario.video_id])
            solver_before = self.solver_invocations()
            started = time.perf_counter()
            process = subprocess.Popen(
                argv, env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
            timed_out = False
            try:
                _stdout, stderr_bytes = process.communicate(timeout=self.args.timeout)
            except subprocess.TimeoutExpired:
                timed_out = True
                try:
                    os.killpg(os.getpgid(process.pid), signal.SIGKILL)
                except OSError:
                    process.kill()
                _stdout, stderr_bytes = process.communicate()
            wall_ms = (time.perf_counter() - started) * 1000.0
            stderr_text = stderr_bytes.decode("utf-8", "replace") if stderr_bytes else ""
            files: List[Dict[str, Any]] = []
            for root, _dirs, names in os.walk(cache_dir):
                for name in sorted(names):
                    path = os.path.join(root, name)
                    try:
                        size = os.path.getsize(path)
                    except OSError:
                        size = None
                    files.append({"name": os.path.relpath(path, cache_dir), "size_bytes": size})
            results.append({
                "attempt": attempt + 1,
                "exit_code": process.returncode,
                "timed_out": timed_out,
                "wall_ms": round(wall_ms, 3),
                "solver_invocations": self.solver_invocations() - solver_before,
                "solver_diagnostics": stderr_text.count("Solving JS challenges"),
                "cache_files": files,
            })
        return {
            "description": (
                "Two frozen-CLI extractions of the challenged video with one shared --cache-dir. "
                "The upstream cache holds public signature data; it does not hold n-challenge results."
            ),
            "cache_dir": "private temporary directory (removed with the harness)",
            "runs": results,
        }

    # -- configuration runners ---------------------------------------------
    def run_frozen_cli(self, scenario: Scenario, round_index: int, repeat_index: int,
                       kind: str) -> RunResult:
        result = RunResult("frozen_cli", scenario.key, scenario.video_id, round_index, repeat_index, kind)
        result.workload = scenario.workload
        selector = QUALITY_SELECTORS[self.args.quality]
        argv = [self.ytdlp]
        argv.extend(RESOLVER_FLAGS)
        argv.extend(["--js-runtimes", "bun:%s" % self.shim])
        argv.extend(["--format", selector])
        if scenario.player_client:
            argv.extend(["--extractor-args", "youtube:player_client=%s" % scenario.player_client])
        argv.extend(["-v", "--", "https://www.youtube.com/watch?v=%s" % scenario.video_id])

        solver_before = self.solver_invocations()
        cpu_children_before = child_cpu()
        started = time.perf_counter()
        process = subprocess.Popen(
            argv,
            env=self.env,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            start_new_session=True,
        )
        sampler = TreeMemorySampler(process.pid)
        sampler.start()
        try:
            stdout, stderr = process.communicate(timeout=self.args.timeout)
            timed_out = False
        except subprocess.TimeoutExpired:
            timed_out = True
            try:
                os.killpg(os.getpgid(process.pid), signal.SIGKILL)
            except OSError:
                process.kill()
            stdout, stderr = process.communicate()
        wall_ms = (time.perf_counter() - started) * 1000.0
        sampler.stop()
        cpu_children_after = child_cpu()

        result.wall_ms = round(wall_ms, 3)
        result.stage_ms = {"total": round(wall_ms, 3)}
        # The control runs as a child of the harness, so getrusage(RUSAGE_CHILDREN)
        # covers the frozen yt-dlp and, transitively, the JavaScript runtime it
        # spawned. That delta is the whole request tree.
        result.cpu_s = {
            "total": round(cpu_children_after - cpu_children_before, 4),
            "worker_self": 0.0,
            "exited_helpers": round(cpu_children_after - cpu_children_before, 4),
            "harness_children_delta": round(cpu_children_after - cpu_children_before, 4),
        }
        result.peak_rss_bytes = sampler.peak_rss
        result.peak_pss_bytes = sampler.peak_pss
        result.peak_processes = sampler.peak_processes
        result.solver_invocations = self.solver_invocations() - solver_before

        stderr_text = stderr.decode("utf-8", "replace") if stderr else ""
        result.solver_diagnostics = stderr_text.count("Solving JS challenges")
        payload = None
        if stdout:
            try:
                candidate = json.loads(stdout.decode("utf-8", "replace"))
                if isinstance(candidate, dict):
                    payload = candidate
            except (ValueError, UnicodeDecodeError):
                payload = None
        if timed_out:
            result.failure_class = "TIMEOUT"
        elif process.returncode != 0:
            result.failure_class = CLASSIFY_FAILURE(process.returncode, stderr_text, False)
        elif payload is None:
            result.failure_class = "NO_JSON_PAYLOAD"
        else:
            result.ok = True
            result.summary = _summarize_info(payload)
            url = payload.get("url")
            headers = dict(payload.get("http_headers") or {})
            if not url:
                # Fall back to the selected audio format when the merged info
                # dict does not carry the URL.
                for candidate in payload.get("formats") or []:
                    if candidate.get("acodec") not in (None, "none") and candidate.get("url"):
                        url = candidate.get("url")
                        headers = dict(candidate.get("http_headers") or {})
                        break
            self._probe(result, url, headers)
        if not result.ok:
            result.diagnostics = [REDACT(line, 200) for line in stderr_text.splitlines()[-6:]]
        return result

    def _probe(self, result: RunResult, url: Optional[str], headers: Dict[str, str]) -> None:
        """Range probe at the head and the tail; runs outside the timed window."""
        if not self.args.probe or not url:
            return
        probe = PROBE_URL(url, headers, self.args.probe_timeout)
        result.probe = _summarize_probe(probe)

    def worker_handle(self, config: str) -> _WorkerHandle:
        handle = self.workers.get(config)
        if handle is not None and handle.process.poll() is not None:
            # The worker died (crash or external kill): replace it so that the
            # remaining requests of the round are still measured. The death is
            # visible in the report as a failed attempt.
            handle.stop()
            handle = None
            self.workers.pop(config, None)
        if handle is None:
            # A boolean, not the "0"/"1" string: a non-empty string is truthy
            # and would silently turn every persistent worker into a reusing
            # worker.
            reuse = config == "persistent_python_reuse_ydl"
            handle = _WorkerHandle(
                self.python,
                os.path.abspath(__file__),
                self.shim,
                self.env,
                reuse_ydl=reuse,
                timeout=self.args.timeout,
            )
            self.workers[config] = handle
        return handle

    def _kind_for(self, config: str, scenario: Scenario, handle: Optional[_WorkerHandle]) -> str:
        if handle is None:
            return "cold"
        if handle.request_count == 0:
            return "cold"
        if handle.last_video == scenario.video_id:
            return "warm_same_video"
        return "new_video"

    def run_with_retry(self, runner, max_attempts: int = 3) -> List[RunResult]:
        """Run one request, retrying a failed extraction.

        YouTube occasionally answers a request without a usable format set.
        The existing runtime benchmark retries for the same reason. **Every**
        attempt is returned and every attempt is counted in the report
        denominators: a retry never replaces a failed measurement, it only
        adds another one.
        """
        attempts: List[RunResult] = []
        for attempt in range(max(1, max_attempts)):
            result = runner()
            result.attempt = attempt + 1
            attempts.append(result)
            if result.ok:
                break
            if attempt + 1 < max(1, max_attempts):
                time.sleep(2.0)
        total = len(attempts)
        for index, item in enumerate(attempts):
            item.attempts_total = total
            item.final_attempt = index == total - 1
            item.note = (item.note + " " if item.note else "") + "attempt=%d/%d" % (item.attempt, total)
        return attempts

    def run_python(self, config: str, scenario: Scenario, round_index: int, repeat_index: int,
                   kind: str) -> RunResult:
        result = RunResult(config, scenario.key, scenario.video_id, round_index, repeat_index, kind)
        result.workload = scenario.workload
        request = {
            "id": "%s-%d-%d" % (config, round_index, repeat_index),
            "video_id": scenario.video_id,
            "player_client": scenario.player_client,
            "quality": self.args.quality,
        }
        if self.stub is not None:
            request["stub"] = self.stub

        if config == "fresh_python":
            handle = None
            solver_before = self.solver_invocations()
            cpu_children_before = child_cpu()
            sampler_box: List[TreeMemorySampler] = []

            def on_spawn(pid: int) -> None:
                sampler = TreeMemorySampler(pid)
                sampler.start()
                sampler_box.append(sampler)

            started = time.perf_counter()
            handle = _WorkerHandle(
                self.python,
                os.path.abspath(__file__),
                self.shim,
                self.env,
                reuse_ydl=False,
                timeout=self.args.timeout,
                once=True,
                on_spawn=on_spawn,
            )
            try:
                response = handle.request(request)
            except Exception as error:  # noqa: BLE001
                response = {"ok": False, "error_class": "WORKER_CRASH",
                            "error": REDACT("%s: %s" % (type(error).__name__, error), 300)}
            for sampler in sampler_box:
                sampler.stop()
            # The one-shot worker exits after its answer; waiting for the exit
            # keeps the wall time comparable with the process-per-request
            # control and reaps the process so its CPU is accounted for.
            handle.stop()
            wall_ms = (time.perf_counter() - started) * 1000.0
            cpu_children_after = child_cpu()
            result.wall_ms = round(wall_ms, 3)
            startup_stage = (response.get("worker_startup") or {}).get("stage_ms") or {}
            result.stage_ms = {
                "total": round(wall_ms, 3),
                "import": round(float(startup_stage.get("import") or 0.0), 3),
                "extract": round(float((response.get("stage_ms") or {}).get("extract") or 0.0), 3),
            }
            # The worker process is reaped by the harness, so the harness delta
            # already contains the worker and every helper it reaped.
            harness_delta = cpu_children_after - cpu_children_before
            worker_self = float((response.get("cpu_s") or {}).get("self") or 0.0)
            result.cpu_s = {
                "total": round(harness_delta, 4),
                "worker_self": round(worker_self, 4),
                "exited_helpers": round(max(harness_delta - worker_self, 0.0), 4),
                "harness_children_delta": round(harness_delta, 4),
            }
            result.peak_rss_bytes = max([s.peak_rss for s in sampler_box] or [0])
            result.peak_pss_bytes = max([s.peak_pss for s in sampler_box] or [0])
            result.peak_processes = max([s.peak_processes for s in sampler_box] or [0])
            result.solver_invocations = self.solver_invocations() - solver_before
        else:
            handle = self.worker_handle(config)
            result.kind = self._kind_for(config, scenario, handle)
            solver_before = self.solver_invocations()
            cpu_children_before = child_cpu()
            sampler = TreeMemorySampler(handle.pid)
            sampler.start()
            started = time.perf_counter()
            try:
                response = handle.request(request)
            except Exception as error:  # noqa: BLE001
                response = {"ok": False, "error_class": "WORKER_CRASH",
                            "error": REDACT("%s: %s" % (type(error).__name__, error), 300)}
            wall_ms = (time.perf_counter() - started) * 1000.0
            sampler.stop()
            cpu_children_after = child_cpu()
            result.wall_ms = round(wall_ms, 3)
            result.stage_ms = {
                "total": round(wall_ms, 3),
                "extract": round(float((response.get("stage_ms") or {}).get("extract") or 0.0), 3),
            }
            # The worker stays alive, so the harness cannot see its CPU through
            # getrusage(RUSAGE_CHILDREN) yet: the worker reports its own usage,
            # including the JavaScript runtime it spawned and reaped.
            worker_self = float((response.get("cpu_s") or {}).get("self") or 0.0)
            exited_helpers = float((response.get("cpu_s") or {}).get("children") or 0.0)
            result.cpu_s = {
                "total": round(worker_self + exited_helpers, 4),
                "worker_self": round(worker_self, 4),
                "exited_helpers": round(exited_helpers, 4),
                "harness_children_delta": round(cpu_children_after - cpu_children_before, 4),
            }
            result.peak_rss_bytes = sampler.peak_rss
            result.peak_pss_bytes = sampler.peak_pss
            result.peak_processes = sampler.peak_processes
            result.solver_invocations = self.solver_invocations() - solver_before
            idle = process_memory(handle.pid)
            result.idle_rss_bytes = idle["rss_bytes"]
            result.idle_pss_bytes = idle["pss_bytes"]

        result.ok = bool(response.get("ok"))
        result.failure_class = "NONE" if result.ok else (response.get("error_class") or "EXTRACTOR_ERROR")
        result.summary = response.get("summary") or {}
        result.solver_diagnostics = response.get("solver_diagnostics")
        result.diagnostics = response.get("diagnostic_tail") or []
        stats = response.get("worker_stats") or {}
        state = response.get("state") or {}
        if state:
            result.note = "ydl_reused=%s rebuilt=%s" % (state.get("reused"), state.get("rebuilt"))
        if stats:
            result.note = (result.note + " " if result.note else "") + (
                "ydl_constructions=%s requests_served=%s" % (
                    stats.get("ydl_constructions"), stats.get("requests_served")))
            # Guard against a configuration silently running in another mode.
            expected_reuse = config == "persistent_python_reuse_ydl"
            if bool(stats.get("reuse_ydl")) != expected_reuse:
                result.ok = False
                result.failure_class = "WORKER_MODE_MISMATCH"
                result.note += " worker_mode_mismatch(expected_reuse=%s)" % expected_reuse
        if result.ok:
            self._probe(result, response.get("media_url"), dict(response.get("media_headers") or {}))
        return result


# ---------------------------------------------------------------------------
# Aggregation
# ---------------------------------------------------------------------------


def _block(runs: List[RunResult]) -> Dict[str, Any]:
    """Aggregate over *all* attempts.

    The counts describe attempts, not logical requests: a retried request
    contributes every one of its attempts, so a failure can never be hidden by
    a later success. The latency, CPU and memory distributions use the same
    attempt set.
    """
    logical = len({(run.config, run.round_index, run.repeat_index, run.scenario) for run in runs})
    return {
        "requests": logical,
        "attempts": len(runs),
        "successful_attempts": sum(1 for run in runs if run.ok),
        "failed_attempts": sum(1 for run in runs if not run.ok),
        "retried_attempts": sum(1 for run in runs if run.attempt > 1),
        "wall_ms": summarize_numbers([run.wall_ms for run in runs]),
        "extract_stage_ms": summarize_numbers([run.stage_ms.get("extract") for run in runs]),
        "cpu_total_s": summarize_numbers([run.cpu_s.get("total") for run in runs]),
        "cpu_worker_self_s": summarize_numbers([run.cpu_s.get("worker_self") for run in runs]),
        "cpu_exited_helpers_s": summarize_numbers([run.cpu_s.get("exited_helpers") for run in runs]),
        "peak_tree_rss_bytes": summarize_numbers([run.peak_rss_bytes for run in runs]),
        "peak_tree_pss_bytes": summarize_numbers([run.peak_pss_bytes for run in runs]),
        "solver_invocations_total": sum(run.solver_invocations or 0 for run in runs),
        "solver_diagnostics_total": sum(run.solver_diagnostics or 0 for run in runs),
    }


def aggregate(runs: List[RunResult]) -> Dict[str, Any]:
    per_config: Dict[str, Any] = {}
    for config in CONFIGS:
        config_runs = [run for run in runs if run.config == config]
        if not config_runs:
            continue
        entry = _block(config_runs)
        entry["failures"] = [
            {"scenario": run.scenario, "workload": run.workload, "kind": run.kind,
             "attempt": run.attempt, "failure_class": run.failure_class}
            for run in config_runs if not run.ok
        ]
        idle_runs = [run for run in config_runs if run.idle_rss_bytes is not None]
        if idle_runs:
            entry["idle_rss_bytes"] = summarize_numbers([run.idle_rss_bytes for run in idle_runs])
            entry["idle_pss_bytes"] = summarize_numbers([run.idle_pss_bytes for run in idle_runs])
        entry["by_kind"] = {
            kind: _block([run for run in config_runs if run.kind == kind])
            for kind in sorted({run.kind for run in config_runs})
        }
        entry["per_workload"] = {
            workload: _block([run for run in config_runs if run.workload == workload])
            for workload in sorted({run.workload for run in config_runs})
        }
        entry["per_scenario"] = {
            scenario_key: _block([run for run in config_runs if run.scenario == scenario_key])
            for scenario_key in sorted({run.scenario for run in config_runs})
        }
        per_config[config] = entry
    return per_config


def _run_from_json(item: Dict[str, Any]) -> RunResult:
    """Rebuild a run from its JSON form (used when merging two sessions)."""
    run = RunResult(
        item.get("config", ""),
        item.get("scenario", ""),
        item.get("video_id", ""),
        item.get("round", 0),
        item.get("repeat", 0),
        item.get("kind", ""),
    )
    run.ok = bool(item.get("ok"))
    run.failure_class = item.get("failure_class", "")
    run.wall_ms = item.get("wall_ms")
    run.stage_ms = dict(item.get("stage_ms") or {})
    run.cpu_s = dict(item.get("cpu_s") or {})
    run.peak_rss_bytes = item.get("peak_tree_rss_bytes")
    run.peak_pss_bytes = item.get("peak_tree_pss_bytes")
    run.peak_processes = item.get("peak_process_count")
    run.idle_rss_bytes = item.get("idle_rss_bytes")
    run.idle_pss_bytes = item.get("idle_pss_bytes")
    run.solver_invocations = item.get("solver_invocations")
    run.solver_diagnostics = item.get("solver_diagnostics")
    run.probe = dict(item.get("probe") or {})
    run.summary = dict(item.get("summary") or {})
    run.diagnostics = list(item.get("diagnostics") or [])
    run.attempt = item.get("attempt", 1)
    run.attempts_total = item.get("attempts_total", 1)
    run.final_attempt = bool(item.get("final_attempt", True))
    run.workload = item.get("workload", "")
    run.session = item.get("session", "")
    run.note = item.get("note", "")
    return run


def build_headline(per_config: Dict[str, Any]) -> Dict[str, Any]:
    baseline = per_config.get("frozen_cli", {})
    headline: Dict[str, Any] = {}
    for config, entry in per_config.items():
        base_wall = (baseline.get("wall_ms") or {}).get("p50")
        wall = (entry.get("wall_ms") or {}).get("p50")
        headline[config] = {
            "wall_p50_ms": wall,
            "wall_p50_delta_vs_frozen_cli_ms": (
                round(wall - base_wall, 3) if wall is not None and base_wall is not None else None),
            "cpu_total_p50_s": (entry.get("cpu_total_s") or {}).get("p50"),
            "cpu_exited_helpers_p50_s": (entry.get("cpu_exited_helpers_s") or {}).get("p50"),
            "peak_rss_p50_bytes": (entry.get("peak_tree_rss_bytes") or {}).get("p50"),
            "peak_pss_p50_bytes": (entry.get("peak_tree_pss_bytes") or {}).get("p50"),
            "idle_rss_p50_bytes": (entry.get("idle_rss_bytes") or {}).get("p50"),
            "idle_pss_p50_bytes": (entry.get("idle_pss_bytes") or {}).get("p50"),
            "solver_invocations_total": entry.get("solver_invocations_total"),
        }
    return headline


# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------

UPSTREAM_SOURCES = [
    {
        "fact": "YoutubeDL.get_info_extractor caches the extractor instance in _ies_instances, so one YoutubeDL object keeps YoutubeIE._code_cache (player JS) and YoutubeIE._player_cache (n / sigfuncs / sts) across extract_info calls.",
        "title": "yt-dlp 2026.08.19 - yt_dlp/YoutubeDL.py (get_info_extractor, _ies_instances)",
        "url": "https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/yt_dlp/YoutubeDL.py",
    },
    {
        "fact": "solve_js_challenges() always submits the collected n challenges to the solver; only s/sig challenges consult the cache first (_load_player_data_from_cache('sigfuncs', ..., use_disk_cache=True)). The n result is stored with use_disk_cache=False, i.e. in memory only.",
        "title": "yt-dlp 2026.08.19 - yt_dlp/extractor/youtube/_video.py (solve_js_challenges, _load_player_data_from_cache, _store_player_data_to_cache)",
        "url": "https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/yt_dlp/extractor/youtube/_video.py",
    },
    {
        "fact": "YoutubeDL.__init__ builds self.format_selector once from params['format']; a callable params['format'] is evaluated per request, so a reused object can still honour a changing quality preference.",
        "title": "yt-dlp 2026.08.19 - yt_dlp/YoutubeDL.py (format_selector, build_format_selector, _select_formats)",
        "url": "https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/yt_dlp/YoutubeDL.py",
    },
    {
        "fact": "The challenge solver spawns one JavaScript runtime process per bulk_solve call (bun --bun run --no-addons --prefer-offline --no-install -), so a Bun process is started for every extraction that carries an n challenge.",
        "title": "yt-dlp 2026.08.19 - yt_dlp/extractor/youtube/jsc/_builtin/bun.py (_run_js_runtime)",
        "url": "https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/yt_dlp/extractor/youtube/jsc/_builtin/bun.py",
    },
    {
        "fact": "The upstream lazy_extractors module makes the extractor import lazy; without it, YoutubeDL.__init__ imports every extractor module. The upstream release build generates it with devscripts/make_lazy_extractors.py.",
        "title": "yt-dlp 2026.08.19 - yt_dlp/extractor/extractors.py, devscripts/make_lazy_extractors.py",
        "url": "https://github.com/yt-dlp/yt-dlp/tree/2026.08.19/devscripts",
    },
    {
        "fact": "Bun support is deprecated in 2026.08.19 and versions above 1.3.14 are reported as unsupported; the shipped sidecar is Bun 1.4.3.",
        "title": "yt-dlp 2026.08.19 - yt_dlp/extractor/youtube/jsc/_builtin/bun.py (_BUN_MAX_SUPPORTED_VERSION)",
        "url": "https://github.com/yt-dlp/yt-dlp/blob/2026.08.19/yt_dlp/extractor/youtube/jsc/_builtin/bun.py",
    },
]


def _directory_size(path: str) -> Optional[int]:
    total = 0
    found = False
    for root, _dirs, names in os.walk(path):
        for name in names:
            try:
                total += os.path.getsize(os.path.join(root, name))
                found = True
            except OSError:
                continue
    return total if found else None


def _packaging_facts(args: argparse.Namespace) -> Dict[str, Any]:
    """Sizes that decide whether a Python worker could be shipped at all."""
    facts: Dict[str, Any] = {}
    frozen = os.path.abspath(args.yt_dlp)
    if os.path.exists(frozen):
        facts["frozen_ytdlp_bytes"] = os.path.getsize(frozen)
    venv = os.path.abspath(args.venv)
    facts["venv_bytes"] = _directory_size(venv)
    facts["venv_python_bytes"] = None
    python = os.path.join(venv, "bin", "python")
    if os.path.exists(python):
        real = os.path.realpath(python)
        try:
            facts["venv_python_bytes"] = os.path.getsize(real)
        except OSError:
            facts["venv_python_bytes"] = None
    site = _site_packages(venv)
    facts["site_packages_bytes"] = _directory_size(site[0]) if site else None
    facts["note"] = (
        "A persistent Python worker needs a Python runtime plus the yt-dlp and yt-dlp-ejs "
        "packages on every target. The frozen yt-dlp executable already bundles its own "
        "transient interpreter, the EJS solver package and the extractor registry in one file."
    )
    return facts


def build_report(args: argparse.Namespace, runs: List[RunResult],
                 startup: Dict[str, Any], tools: Dict[str, Any], leftovers: List[Dict[str, Any]],
                 duration_s: float) -> Dict[str, Any]:
    per_config = aggregate(runs)
    return {
        "schema": "archivetune.ytdlp.worker-evaluation/1",
        "generated_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "duration_s": round(duration_s, 2),
        "host": {
            "cpu_count": os.cpu_count(),
            "machine": platform.machine(),
            "platform": platform.platform(),
            "python": sys.version.split()[0],
        },
        "tools": tools,
        "method": {
            "quantiles": 'Historical sample-rank estimator: sort values, use one-based rank max(1,min(n,int(round(fraction*n+0.5)))), with Python round-to-even, without interpolation. For measured persistent workload groups n=6 and n=18, p50 is the upper middle value, not the conventional averaged median. The matched application matrix separately uses statistics.median for p50 and nearest-rank p90/p95.',
            "description": (
                "Four request models compared against the same videos with the same production "
                "resolver flag set; serialized (one request at a time) and interleaved per round "
                "so that network and machine drift affect every configuration equally."
            ),
            "serialized": True,
            "interleaved": "the configuration order is rotated every round",
            "repeats_per_scenario": args.repeats,
            "quality": args.quality,
            "quality_selector": QUALITY_SELECTORS[args.quality],
            "cookies_used": False,
            "offline_stub": bool(args.offline_stub),
            "scenarios": [
                {"key": scenario.key, "video_id": scenario.video_id,
                 "player_client": scenario.player_client, "workload": scenario.workload,
                 "label": scenario.label}
                for scenario in build_scenarios()
            ],
            "workloads": {
                "default_client": (
                    "Production parity. The resolver does not pass a player client, so the default "
                    "client is used: the challenged video with the default client plus the two "
                    "unchallenged videos."
                ),
                "forced_web_embedded": (
                    "Explicit challenge control: the same challenged video with the web_embedded "
                    "player client. These numbers are never mixed with the default-client workload."
                ),
            },
            "denominators": (
                "Every attempt of every request is recorded and every attempt is counted. A retried "
                "request contributes all of its attempts to the latency, CPU and memory "
                "distributions, so a failure can never be hidden by a later success. The counts are "
                "reported as requests and attempts separately."
            ),
            "probe": {
                "enabled": bool(args.probe),
                "method": "ranged GET at the head and at the tail with the headers yt-dlp returned",
                "max_bytes": 64 * 1024,
                "note": "the probe always runs outside the measured request window",
            },
            "rss_method": (
                "Maximum sampled sum of process-tree RSS over the whole tree; shared pages may be "
                "counted once per mapping process and short-lived spikes may be missed (lower bound)."
            ),
            "pss_method": (
                "Maximum sampled sum of process-tree PSS from /proc/<pid>/smaps_rollup; shared pages "
                "are divided between their users. 0 means the kernel does not expose smaps_rollup."
            ),
            "cpu_method": (
                "cpu_total_s is the CPU of the whole request tree. For the process-per-request "
                "configurations it is the harness getrusage(RUSAGE_CHILDREN) delta, which on Linux "
                "is transitive and therefore includes the JavaScript runtime the child spawned. For "
                "the persistent workers the worker stays alive, so the worker reports its own "
                "getrusage(RUSAGE_SELF) delta plus its getrusage(RUSAGE_CHILDREN) delta; the latter "
                "includes the JavaScript runtime it spawned and reaped even though that helper is no "
                "longer in /proc."
            ),
            "solver_count_method": (
                "A shim in front of Bun logs every invocation; invocations that only probe the runtime "
                "version (bun --version) are excluded."
            ),
            "wall_definition": {
                "frozen_cli": "process spawn until process exit (includes interpreter start-up)",
                "fresh_python": "process spawn until the response line is read (includes interpreter start-up and import)",
                "persistent_python_*": "request written to the worker until the response line is read",
            },
            "kinds": {
                "cold": "the first request of a configuration (fresh process or freshly started worker)",
                "warm_same_video": "a later request for the same video in the same worker",
                "new_video": "a later request for a different video in the same worker",
            },
            "worker_invalidation_policy": (
                "The worker rebuilds the YoutubeDL object whenever the cookie jar, the proxy or the "
                "account context changes. The quality preference is applied through a callable format "
                "selector, because YoutubeDL.__init__ freezes a string format selector."
            ),
        },
        "startup_cost_ms": startup,
        "per_config": per_config,
        "headline": build_headline(per_config),
        "runs": [run.to_json() for run in runs],
        "cleanup": {
            "leftover_processes": leftovers,
            "temporary_root_removed": True,
        },
        "upstream_sources": UPSTREAM_SOURCES,
    }


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def verify_tools(args: argparse.Namespace) -> Dict[str, Any]:
    tools: Dict[str, Any] = {"checks": {}}
    checks = tools["checks"]

    def add(kind: str, path: str, expected_sha: Optional[str], expected_version: Optional[str]) -> None:
        exists = os.path.exists(path)
        digest = file_sha256(path) if exists else None
        entry: Dict[str, Any] = {
            "path": path,
            "exists": exists,
            "sha256": digest,
            "expected_sha256": expected_sha,
            "sha256_matches": (digest == expected_sha) if (expected_sha and exists) else None,
            "version": None,
            "expected_version": expected_version,
        }
        if exists and expected_version:
            try:
                output = subprocess.run(
                    [path, "--version"], capture_output=True, text=True, timeout=30, check=False)
                text = (output.stdout or output.stderr).strip()
                entry["version"] = text.splitlines()[0] if text else None
            except (OSError, subprocess.SubprocessError):
                entry["version"] = None
        checks[kind] = entry

    add("frozen_ytdlp", os.path.abspath(args.yt_dlp), YTDLP_SHA256, YTDLP_VERSION)
    add("bun", os.path.abspath(args.bun), BUN_BINARY_SHA256, BUN_VERSION)

    venv = os.path.abspath(args.venv)
    python = os.path.join(venv, "bin", "python")
    if not os.path.exists(python):
        python = os.path.join(venv, "Scripts", "python.exe")
    checks["venv_python"] = {
        "path": python,
        "exists": os.path.exists(python),
        "sha256": None,
        "expected_sha256": None,
        "sha256_matches": None,
        "version": None,
        "expected_version": YTDLP_VERSION,
    }
    if os.path.exists(python):
        try:
            output = subprocess.run(
                [python, "-c",
                 "import yt_dlp, yt_dlp_ejs; print(yt_dlp.version.__version__); print(yt_dlp_ejs.version)"],
                capture_output=True, text=True, timeout=180, check=False)
            lines = [line.strip() for line in (output.stdout or "").splitlines() if line.strip()]
            checks["venv_python"]["version"] = lines[0] if lines else None
            checks["venv_python"]["ejs_version"] = lines[1] if len(lines) > 1 else None
            checks["venv_python"]["version_matches"] = bool(lines) and lines[0] == YTDLP_VERSION
        except (OSError, subprocess.SubprocessError):
            pass
        checks["venv_python"]["lazy_extractors"] = any(
            os.path.exists(os.path.join(root, "yt_dlp", "extractor", "lazy_extractors.py"))
            for root in _site_packages(venv)
        )
    tools["verified"] = all(
        checks[key].get("sha256_matches") is not False for key in checks
    )
    return tools


def _site_packages(venv: str) -> List[str]:
    found: List[str] = []
    for base in ("lib", "Lib", "lib64"):
        root = os.path.join(venv, base)
        if not os.path.isdir(root):
            continue
        for entry in os.listdir(root):
            if entry.startswith("python") or entry == "site-packages":
                found.append(os.path.join(root, entry, "site-packages"))
                found.append(os.path.join(root, entry))
    return [path for path in found if os.path.isdir(path)]


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="ArchiveTune persistent yt-dlp worker evaluation (developer-only)",
    )
    parser.add_argument("--yt-dlp", default=".tools/resolver/yt-dlp",
                        help="frozen yt-dlp executable used as the control")
    parser.add_argument("--bun", default=".tools/resolver/bun",
                        help="Bun executable used as the JavaScript runtime")
    parser.add_argument("--venv", default=".tools/ytdlp-worker-venv",
                        help="pinned virtual environment with yt-dlp and yt-dlp-ejs")
    parser.add_argument("--repeats", type=int, default=3,
                        help="rounds over the whole scenario set")
    parser.add_argument("--quality", default=DEFAULT_QUALITY, choices=sorted(QUALITY_SELECTORS))
    parser.add_argument("--timeout", type=float, default=60.0, help="per-request timeout in seconds")
    parser.add_argument("--probe-timeout", type=float, default=15.0)
    parser.add_argument("--probe", dest="probe", action="store_true", default=True,
                        help="run the head/tail range probe for every successful result")
    parser.add_argument("--no-probe", dest="probe", action="store_false")
    parser.add_argument("--skip-startup", dest="skip_startup", action="store_true", default=False,
                        help="skip the no-network start-up cost measurement")
    parser.add_argument("--skip-cache-probe", dest="skip_cache_probe", action="store_true", default=False,
                        help="skip the upstream --cache-dir probe")
    parser.add_argument("--offline-stub", dest="offline_stub", action="store_true", default=False,
                        help=(
                            "plumbing fixture: every configuration answers from a local stub and a local "
                            "range server, so the run cannot contact YouTube. The --yt-dlp binary must "
                            "then be a stub script (its name has to contain 'stub'), otherwise the run is "
                            "refused."))
    parser.add_argument("--out-json", default=None, help="path of the JSON report")
    parser.add_argument("--configs", default=None,
                        help="comma separated subset of configurations to measure (%s)"
                             % ",".join(CONFIGS))
    parser.add_argument("--self-check", dest="self_check", action="store_true", default=False,
                        help=(
                            "offline regression checks of the harness itself (worker mode flag, "
                            "object reuse, fingerprint rebuild, mode-mismatch guard). Never touches "
                            "the network."))
    parser.add_argument("--session-id", default=None,
                        help="provenance id of this measurement session (default: a UTC timestamp)")
    parser.add_argument("--merge-into", default=None,
                        help=(
                            "merge this session into an existing report JSON: the runs of the "
                            "configurations measured here supersede the runs of the same "
                            "configurations in that report, the remaining runs are kept, and both "
                            "sessions are listed in the provenance."))
    parser.add_argument("--session-note", default="",
                        help="one line describing this session; stored in the provenance")
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = build_parser().parse_args(list(argv) if argv is not None else None)
    started = time.monotonic()
    started_wall = time.time()

    if args.self_check:
        # The self check is offline by construction: the stub answers every
        # request, so no extraction can reach YouTube.
        args.offline_stub = True
        args.skip_startup = True
        args.skip_cache_probe = True
        harness = Harness(args)
        try:
            checks = harness.self_check()
        finally:
            harness.close()
        failed = [check for check in checks if not check["ok"]]
        print(json.dumps({"self_check": checks, "failed": len(failed)}, indent=1))
        return 0 if not failed else 1

    tools = verify_tools(args)
    if not tools["checks"].get("frozen_ytdlp", {}).get("exists"):
        print("frozen yt-dlp not found: %s" % args.yt_dlp, file=sys.stderr)
        return 2
    if not tools["checks"].get("bun", {}).get("exists"):
        print("bun not found: %s" % args.bun, file=sys.stderr)
        return 2
    if not tools["checks"].get("venv_python", {}).get("exists"):
        print("virtual environment not found: %s" % args.venv, file=sys.stderr)
        return 2

    harness = Harness(args)
    runs: List[RunResult] = []
    startup: Dict[str, Any] = {}
    leftovers: List[Dict[str, Any]] = []
    cache_probe: Dict[str, Any] = {}
    scenarios = build_scenarios()
    session_id = args.session_id or ("session-%s" % time.strftime("%Y%m%dT%H%M%SZ", time.gmtime(started_wall)))
    measured_configs: List[str] = []
    try:
        if args.offline_stub:
            # Hard safety guard: the offline fixture must never be able to
            # reach YouTube through the control binary.
            if "stub" not in os.path.basename(args.yt_dlp).lower():
                print("refusing the offline fixture: --yt-dlp must be a stub script", file=sys.stderr)
                return 2
            if not args.skip_cache_probe:
                args.skip_cache_probe = True
            if not args.skip_startup:
                args.skip_startup = True
        if not args.skip_startup:
            startup = harness.measure_startup(max(1, min(args.repeats, 5)))
        if not args.skip_cache_probe:
            cache_probe = harness.cache_probe(scenarios[0])

        for round_index in range(args.repeats):
            scenario_order = list(scenarios)
            if round_index % 2 == 1:
                scenario_order = list(reversed(scenario_order))

            # Build the request list of this round and interleave it per
            # configuration, so that network and machine drift hit every
            # configuration equally instead of penalising the last one.
            selected = set(args.configs.split(",")) if args.configs else set(CONFIGS)
            unknown = selected - set(CONFIGS)
            if unknown:
                raise SystemExit("unknown configuration(s): %s" % ",".join(sorted(unknown)))
            per_config_tasks: Dict[str, List[Tuple[str, Scenario, str]]] = {config: [] for config in CONFIGS}
            for config in ("frozen_cli", "fresh_python"):
                if config not in selected:
                    continue
                for scenario in scenario_order:
                    per_config_tasks[config].append((config, scenario, "cold"))
            for config in PERSISTENT_CONFIGS:
                if config not in selected:
                    continue
                for scenario in scenario_order:
                    per_config_tasks[config].append((config, scenario, "cold"))
                    per_config_tasks[config].append((config, scenario, "warm"))

            for config in CONFIGS:
                if per_config_tasks[config]:
                    measured_configs.append(config)
            offsets = {config: 0 for config in CONFIGS}
            exhausted = False
            while not exhausted:
                exhausted = True
                for config in CONFIGS:
                    index = offsets[config]
                    if index >= len(per_config_tasks[config]):
                        continue
                    exhausted = False
                    offsets[config] = index + 1
                    task_config, scenario, kind = per_config_tasks[config][index]
                    if task_config == "frozen_cli":
                        attempts = harness.run_with_retry(
                            lambda: harness.run_frozen_cli(scenario, round_index, index, kind))
                    else:
                        attempts = harness.run_with_retry(
                            lambda: harness.run_python(task_config, scenario, round_index, index, kind))
                    # Every attempt is kept: a retry never replaces a failed
                    # measurement, so the denominators stay complete.
                    for attempt_result in attempts:
                        attempt_result.session = session_id
                        runs.append(attempt_result)
                        _log(attempt_result, round_index)
    finally:
        harness.stop_workers()
        leftovers = find_leftovers([harness.shim, harness.bun], os.getpid())
        harness.close()

    duration_s = time.monotonic() - started
    report = build_report(args, runs, startup, tools, leftovers, duration_s)
    report["upstream_cache_probe"] = cache_probe
    report["packaging"] = _packaging_facts(args)
    report["sessions"] = [{
        "id": session_id,
        "started_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(started_wall)),
        "finished_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "duration_s": round(duration_s, 2),
        "configs": sorted(set(measured_configs)),
        "attempts": len(runs),
        "note": args.session_note or "",
    }]

    if args.merge_into and os.path.exists(args.merge_into):
        with open(args.merge_into, "r", encoding="utf-8") as handle:
            previous = json.load(handle)
        superseded = set(measured_configs)
        kept = [run for run in previous.get("runs", []) if run.get("config") not in superseded]
        dropped = [run for run in previous.get("runs", []) if run.get("config") in superseded]
        sessions = list(previous.get("sessions", []))
        if not sessions:
            # The report was written before the session field existed: derive
            # its provenance from the report itself instead of inventing it.
            old_configs = sorted({run.get("config") for run in previous.get("runs", []) if run.get("config")})
            sessions.append({
                "id": "unlabelled-session",
                "started_utc": None,
                "finished_utc": previous.get("generated_at_utc"),
                "duration_s": previous.get("duration_s"),
                "configs": old_configs,
                "attempts": len(previous.get("runs", [])),
                "note": (
                    "written before the session field existed; its provenance is the report "
                    "timestamp and the configurations it contains"
                ),
            })
        for session in sessions:
            session.setdefault("superseded_configs", [])
            session["superseded_configs"] = sorted(set(session.get("superseded_configs", [])) | superseded)
        sessions.extend(report["sessions"])
        report["runs"] = kept + [run.to_json() for run in runs]
        report["sessions"] = sessions
        report["per_config"] = aggregate([_run_from_json(item) for item in report["runs"]])
        report["headline"] = build_headline(report["per_config"])
        report["upstream_cache_probe"] = previous.get("upstream_cache_probe") or cache_probe
        report["startup_cost_ms"] = previous.get("startup_cost_ms") or startup
        report["packaging"] = previous.get("packaging") or report["packaging"]
        report["tools"] = previous.get("tools") or report["tools"]
        report["merge"] = {
            "merged_into": os.path.abspath(args.merge_into),
            "superseded_configs": sorted(superseded),
            "superseded_attempts": len(dropped),
            "kept_attempts": len(kept),
            "reason": args.session_note or "",
        }

    if args.out_json:
        out_path = os.path.abspath(args.out_json)
        os.makedirs(os.path.dirname(out_path), exist_ok=True)
        with open(out_path, "w", encoding="utf-8") as handle:
            json.dump(report, handle, indent=1, sort_keys=True)
            handle.write("\n")
        print("wrote %s" % out_path, file=sys.stderr)

    failures = [run for run in runs if not run.ok]
    probe_failures = [
        run for run in runs
        if run.ok and run.probe and (run.probe.get("status") not in (200, 206)
                                     or run.probe.get("bytes_read", 0) <= 0)
    ]
    print(json.dumps(report["headline"], indent=1), file=sys.stderr)
    if failures:
        print("failed runs: %d" % len(failures), file=sys.stderr)
    if probe_failures:
        print("probe failures: %d" % len(probe_failures), file=sys.stderr)
    if leftovers:
        print("WARNING: leftover processes: %s" % json.dumps(leftovers), file=sys.stderr)
    return 0 if (not failures and not probe_failures and not leftovers) else 1


def _log(result: RunResult, round_index: int) -> None:
    print(
        "round %d %-28s %-10s %-16s attempt=%d/%d ok=%-5s wall=%7.0fms extract=%7.0fms solver=%s"
        % (
            round_index + 1, result.config, result.scenario, result.kind,
            result.attempt, result.attempts_total, result.ok,
            result.wall_ms or 0.0, result.stage_ms.get("extract") or 0.0, result.solver_invocations,
        ),
        file=sys.stderr,
        flush=True,
    )


if __name__ == "__main__":
    if "--worker" in sys.argv:
        sys.exit(_worker_main(sys.argv[1:]))
    sys.exit(main())

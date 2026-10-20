#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ArchiveTune live yt-dlp + QuickJS-NG extraction benchmark (developer-only harness).

This script is a validation/benchmark tool for the desktop resolver work. It is NOT
shipped inside the application and nothing in the shipped runtime depends on it.

What it does
------------
1. Verifies the pinned upstream tools (yt-dlp 2026.08.19, QuickJS-NG 0.17.0) by SHA256.
2. Runs live ``yt-dlp`` extractions against real public videos using the exact
   resolver flags the desktop app is expected to use, with a hard timeout and
   full process-group cleanup.
3. Samples the peak RSS of the whole process tree from ``/proc`` while each
   extraction runs.
4. Probes the resolved media URL with the headers yt-dlp returned, using a short
   ranged GET at the head and at the tail of the object.
5. Optionally proves that the QuickJS runtime is load-bearing for the YouTube
   ``n`` challenge, and optionally proves that a resolved URL really plays
   (libVLC playback progress and/or an ffmpeg PCM decode of the stream).

Safety contract
---------------
* Cookies are never requested, never read and never written.
* Signed media URLs are never printed and never written to disk. The yt-dlp
   ``--dump-single-json`` payload is parsed in memory and discarded.
* The report only contains safe summaries: video ids, format ids/counts,
   codecs, protocols, timings, byte counts and a coarse failure class.
* Absolute media URLs that appear inside yt-dlp's own diagnostics are redacted
   before they are stored.

Usage
-----
    scripts/benchmark-ytdlp.py \
        --yt-dlp build/tools/resolver/yt-dlp \
        --qjs    build/tools/resolver/qjs \
        --videos jNQXAC9IVRw,dQw4w9WgXc,kJQP7kiw5Fk \
        --repeats 2 \
        --out-dir build/reports/ytdlp

Exit code is 0 only when every requested run succeeded.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import platform
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Sequence, Tuple

# ---------------------------------------------------------------------------
# Pinned upstream artifacts
# ---------------------------------------------------------------------------

YTDLP_VERSION = "2026.08.19"
YTDLP_SHA256 = "58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a"
QJS_VERSION = "0.17.0"
QJS_SHA256 = "0bfc02511a9f549c28b53880d988fc7cd5d361e90c5e8afdfcd7dc6774ceace5"

# The exact resolver flag set the desktop app is expected to use.
RESOLVER_FLAGS: Tuple[str, ...] = (
    "--ignore-config",
    "--no-plugin-dirs",
    "--no-remote-components",
    "--no-playlist",
    "--skip-download",
    "--dump-single-json",
    "-f",
    "bestaudio[protocol=https]/bestaudio[protocol=http]",
)

# Harness-only flag. yt-dlp writes its diagnostics to stderr, so the JSON on
# stdout stays clean. It is what makes the per-run JS challenge evidence
# ("Solving JS challenges using quickjs") observable.
DIAGNOSTIC_FLAGS: Tuple[str, ...] = ("-v",)

# Player clients that are known to require the YouTube "n" challenge solver.
# The default client currently answers without one, so the challenge proof needs
# a client that is actually challenged.
CHALLENGE_CLIENTS: Tuple[str, ...] = ("web_embedded", "mweb", "tv_simply")

DEFAULT_VIDEOS: Tuple[str, ...] = (
    "jNQXAC9IVRw",
    "dQw4w9WgXcQ",
    "kJQP7kiw5Fk",
    "9bZkp7q19f0",
)

# ---------------------------------------------------------------------------
# Redaction
# ---------------------------------------------------------------------------

_URL_RE = re.compile(r"https?://[^\s\"'<>\\)\]]+")
_SECRET_QUERY_RE = re.compile(
    r"\b(sig|s|lsig|n|lsparams|pot|cpn|ip|ei|id|ratebypass)=[A-Za-z0-9._%-]+",
    re.IGNORECASE,
)
_CHALLENGE_TOKENS_RE = re.compile(r"challenges=\[[^\]]*\]")


def redact(text: str, limit: int = 600) -> str:
    """Strip signed URLs and secret-looking query parameters from free-form text."""
    if not text:
        return ""
    cleaned = _URL_RE.sub("<redacted-url>", text)
    cleaned = _SECRET_QUERY_RE.sub(lambda m: f"{m.group(1)}=<redacted>", cleaned)
    cleaned = _CHALLENGE_TOKENS_RE.sub("challenges=[<redacted>]", cleaned)
    cleaned = cleaned.replace("\r", " ").replace("\n", " | ")
    return cleaned[:limit]


def normalize_video(value: str) -> Tuple[str, str, Optional[str]]:
    """Return ``(video_id, yt_dlp_target, note)`` for a bare id or a full URL.

    YouTube ids are exactly 11 characters. A shorter token is almost always a
    truncated id, so it is still expanded to a watch URL but flagged, because
    yt-dlp rejects it with "Incomplete YouTube ID".
    """
    value = value.strip()
    note: Optional[str] = None
    if re.match(r"^[A-Za-z0-9_-]{10,12}$", value):
        if len(value) != 11:
            note = "video id %r is %d characters, not 11; it is probably truncated" % (value, len(value))
        return value, "https://www.youtube.com/watch?v=%s" % value, note
    match = re.search(r"(?:v=|/shorts/|youtu\.be/|/embed/)([A-Za-z0-9_-]{11})", value)
    video_id = match.group(1) if match else re.sub(r"[^A-Za-z0-9_-]", "", value)[:32]
    return video_id or "unknown", value, note


# ---------------------------------------------------------------------------
# Failure classification
# ---------------------------------------------------------------------------

FAILURE_PATTERNS: Tuple[Tuple[str, str], ...] = (
    ("error solving n challenge", "JS_CHALLENGE_SOLVER_FAILED"),
    ("n challenge solving failed", "JS_CHALLENGE_SOLVER_FAILED"),
    ("no supported javascript runtime", "JS_RUNTIME_MISSING"),
    ("unable to download webpage", "WEBPAGE_FETCH_FAILED"),
    ("requested format is not available", "FORMAT_UNAVAILABLE"),
    ("no video formats found", "NO_FORMATS_FOUND"),
    ("sign in to confirm", "LOGIN_REQUIRED"),
    ("confirm you're not a bot", "LOGIN_REQUIRED"),
    ("http error 403", "HTTP_403"),
    ("http error 404", "HTTP_404"),
    ("private video", "PRIVATE_VIDEO"),
    ("video unavailable", "VIDEO_UNAVAILABLE"),
    ("this video is not available", "VIDEO_UNAVAILABLE"),
    ("members-only", "MEMBERS_ONLY"),
    ("age-restricted", "AGE_RESTRICTED"),
    ("premieres in", "NOT_PREMIERED"),
    ("live event will begin", "LIVE_NOT_STARTED"),
    ("unsupported url", "UNSUPPORTED_URL"),
    ("is not a valid url", "UNSUPPORTED_URL"),
    ("unable to extract", "EXTRACTOR_ERROR"),
    ("timed out", "TIMEOUT"),
)


def classify_failure(exit_code: int, stderr: str, timed_out: bool) -> str:
    if timed_out:
        return "TIMEOUT"
    haystack = (stderr or "").lower()
    for needle, label in FAILURE_PATTERNS:
        if needle in haystack:
            return label
    if exit_code == 0:
        return "NONE"
    return "EXTRACTOR_ERROR"


# ---------------------------------------------------------------------------
# Process tree RSS sampling
# ---------------------------------------------------------------------------


class ProcessTreeRssSampler:
    """Samples the summed RSS of a process tree from ``/proc``.

    Limitations (documented on purpose):
      * The sampler polls ``/proc`` every ``interval`` seconds, so a spike that
        lives entirely between two samples is not observed. The reported peak is
        therefore a lower bound.
      * RSS of a process that exits between two samples is not counted.
      * Shared pages (for example the mapped yt-dlp executable) are counted once
        per process that maps them, so the tree total overstates the real
        physical footprint.
    """

    def __init__(self, pid: int, interval: float = 0.02) -> None:
        self.pid = pid
        self.interval = interval
        self.peak_bytes = 0
        self.peak_processes = 0
        self.samples = 0
        self._stop = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._page_size = os.sysconf("SC_PAGE_SIZE") if hasattr(os, "sysconf") else 4096

    # -- process discovery -------------------------------------------------
    def _children(self, pid: int) -> List[int]:
        out: List[int] = []
        task_dir = "/proc/%d/task" % pid
        try:
            tasks = os.listdir(task_dir)
        except OSError:
            return out
        for task in tasks:
            children_file = os.path.join(task_dir, task, "children")
            try:
                with open(children_file, "r") as handle:
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

    def sample(self) -> int:
        total = 0
        pids = self._tree()
        for pid in pids:
            total += self._rss_of(pid)
        self.samples += 1
        if total > self.peak_bytes:
            self.peak_bytes = total
            self.peak_processes = len(pids)
        return total

    # -- lifecycle ---------------------------------------------------------
    def _run(self) -> None:
        while not self._stop.is_set():
            self.sample()
            self._stop.wait(self.interval)

    def start(self) -> None:
        self._thread = threading.Thread(target=self._run, name="rss-sampler", daemon=True)
        self._thread.start()

    def stop(self) -> int:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=2.0)
        return self.peak_bytes


# ---------------------------------------------------------------------------
# Tool verification
# ---------------------------------------------------------------------------


@dataclass
class ToolReport:
    kind: str
    path: str
    exists: bool
    executable: bool
    sha256: Optional[str]
    expected_sha256: Optional[str]
    sha256_matches: bool
    version: Optional[str]
    version_matches: bool
    size_bytes: Optional[int]
    error: Optional[str] = None


def sha256_of(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_tool(kind: str, path: str, expected_sha: str, expected_version: str) -> ToolReport:
    resolved = os.path.abspath(path)
    if not os.path.isfile(resolved):
        return ToolReport(kind, resolved, False, False, None, expected_sha, False, None, False, None,
                          "file not found")
    executable = os.access(resolved, os.X_OK)
    size = os.path.getsize(resolved)
    try:
        actual_sha = sha256_of(resolved)
    except OSError as error:
        return ToolReport(kind, resolved, True, executable, None, expected_sha, False, None, False, size,
                           "sha256 failed: %s" % error)
    version: Optional[str] = None
    error: Optional[str] = None
    try:
        completed = subprocess.run([resolved, "--version"], capture_output=True, timeout=30)
        version = completed.stdout.decode("utf-8", "replace").strip().splitlines()[0] if completed.stdout else None
    except (OSError, subprocess.SubprocessError) as exc:  # pragma: no cover - environment failure
        error = "version probe failed: %s" % exc
    return ToolReport(
        kind=kind,
        path=resolved,
        exists=True,
        executable=executable,
        sha256=actual_sha,
        expected_sha256=expected_sha,
        sha256_matches=actual_sha == expected_sha,
        version=version,
        version_matches=(version is not None and expected_version in version),
        size_bytes=size,
        error=error,
    )


# ---------------------------------------------------------------------------
# Subprocess execution with timeout + cleanup
# ---------------------------------------------------------------------------


@dataclass
class CommandResult:
    exit_code: int
    stdout: bytes
    stderr: str
    timed_out: bool
    duration_ms: int
    peak_tree_rss_bytes: int
    rss_samples: int
    peak_process_count: int


def run_command(
    argv: Sequence[str],
    timeout: float,
    sample_interval: float = 0.02,
    env: Optional[Dict[str, str]] = None,
    stdin_bytes: Optional[bytes] = None,
) -> CommandResult:
    """Run ``argv`` in its own process group with a hard timeout and cleanup."""
    started = time.monotonic()
    process = subprocess.Popen(
        list(argv),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        stdin=subprocess.PIPE if stdin_bytes is not None else subprocess.DEVNULL,
        start_new_session=True,
        env=env,
    )
    sampler = ProcessTreeRssSampler(process.pid, interval=sample_interval)
    sampler.start()
    timed_out = False
    try:
        stdout, stderr_bytes = process.communicate(input=stdin_bytes, timeout=timeout)
    except subprocess.TimeoutExpired:
        timed_out = True
        _terminate_group(process)
        try:
            stdout, stderr_bytes = process.communicate(timeout=15)
        except subprocess.TimeoutExpired:  # pragma: no cover - defensive
            process.kill()
            stdout, stderr_bytes = process.communicate()
    peak = sampler.stop()
    duration_ms = int((time.monotonic() - started) * 1000)
    return CommandResult(
        exit_code=process.returncode if process.returncode is not None else -1,
        stdout=stdout or b"",
        stderr=(stderr_bytes or b"").decode("utf-8", "replace"),
        timed_out=timed_out,
        duration_ms=duration_ms,
        peak_tree_rss_bytes=peak,
        rss_samples=sampler.samples,
        peak_process_count=sampler.peak_processes,
    )


def _terminate_group(process: subprocess.Popen) -> None:
    """Kill the whole process group so no child survives the harness."""
    try:
        os.killpg(os.getpgid(process.pid), signal.SIGTERM)
    except (ProcessLookupError, PermissionError, OSError):
        process.terminate()
    try:
        process.wait(timeout=5)
        return
    except subprocess.TimeoutExpired:
        pass
    try:
        os.killpg(os.getpgid(process.pid), signal.SIGKILL)
    except (ProcessLookupError, PermissionError, OSError):
        process.kill()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:  # pragma: no cover - defensive
        pass


# ---------------------------------------------------------------------------
# yt-dlp extraction
# ---------------------------------------------------------------------------


@dataclass
class HttpProbe:
    attempted: bool = False
    status: Optional[int] = None
    bytes_read: int = 0
    content_type: Optional[str] = None
    ranged_response: bool = False
    magic_hex: Optional[str] = None
    duration_ms: Optional[int] = None
    tail_status: Optional[int] = None
    tail_bytes_read: int = 0
    error: Optional[str] = None


@dataclass
class LivePlayback:
    checked: bool = False
    vlc_available: bool = False
    vlc_state_path: Optional[str] = None
    vlc_reached_playing: bool = False
    vlc_position_advanced: bool = False
    vlc_audio_codec: Optional[str] = None
    vlc_audio_sample_rate: Optional[int] = None
    vlc_error: Optional[str] = None
    ffmpeg_available: bool = False
    ffmpeg_pcm_frames: int = 0
    ffmpeg_rms: Optional[float] = None
    ffmpeg_peak: Optional[float] = None
    ffmpeg_error: Optional[str] = None
    ffmpeg_attempts: int = 0
    ffmpeg_offset_s: Optional[int] = None
    vlc_attempts: int = 0
    verdict: Optional[str] = None


@dataclass
class ExtractionRun:
    video_id: str
    repeat: int
    ok: bool = False
    failure_class: str = "NOT_RUN"
    exit_code: int = -1
    timed_out: bool = False
    latency_ms: int = 0
    peak_tree_rss_bytes: int = 0
    rss_samples: int = 0
    peak_process_count: int = 0
    format_id: Optional[str] = None
    format_count: int = 0
    protocol: Optional[str] = None
    vcodec: Optional[str] = None
    acodec: Optional[str] = None
    abr: Optional[float] = None
    ext: Optional[str] = None
    duration_s: Optional[int] = None
    title: Optional[str] = None
    url_has_n_param: bool = False
    url_has_sig_param: bool = False
    js_challenge_solved: bool = False
    js_challenge_attempted: bool = False
    js_challenge_failed: bool = False
    js_runtime_reported: Optional[str] = None
    ejs_version_reported: Optional[str] = None
    js_solver_source: Optional[str] = None
    header_names: List[str] = field(default_factory=list)
    http_probe: HttpProbe = field(default_factory=HttpProbe)
    live_playback: LivePlayback = field(default_factory=LivePlayback)
    stderr_excerpt: Optional[str] = None

    def summary(self) -> Dict[str, Any]:
        return {
            "video_id": self.video_id,
            "repeat": self.repeat,
            "ok": self.ok,
            "failure_class": self.failure_class,
            "exit_code": self.exit_code,
            "timed_out": self.timed_out,
            "latency_ms": self.latency_ms,
            "peak_tree_rss_bytes": self.peak_tree_rss_bytes,
            "rss_samples": self.rss_samples,
            "peak_process_count": self.peak_process_count,
            "format_id": self.format_id,
            "format_count": self.format_count,
            "protocol": self.protocol,
            "vcodec": self.vcodec,
            "acodec": self.acodec,
            "abr": self.abr,
            "ext": self.ext,
            "duration_s": self.duration_s,
            "title": self.title,
            "url_has_n_param": self.url_has_n_param,
            "url_has_sig_param": self.url_has_sig_param,
            "js_challenge_solved": self.js_challenge_solved,
            "js_challenge_attempted": self.js_challenge_attempted,
            "js_challenge_failed": self.js_challenge_failed,
            "js_runtime_reported": self.js_runtime_reported,
            "ejs_version_reported": self.ejs_version_reported,
            "js_solver_source": self.js_solver_source,
            "header_names": sorted(self.header_names),
            "http_probe": vars(self.http_probe),
            "live_playback": vars(self.live_playback),
            "stderr_excerpt": self.stderr_excerpt,
        }


def hermetic_env(base_dir: str) -> Dict[str, str]:
    """A private HOME so the harness never reads or writes global user config."""
    env = dict(os.environ)
    env["HOME"] = base_dir
    env["XDG_CACHE_HOME"] = os.path.join(base_dir, ".cache")
    env["XDG_CONFIG_HOME"] = os.path.join(base_dir, ".config")
    env["XDG_DATA_HOME"] = os.path.join(base_dir, ".data")
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    for key in list(env):
        if key.startswith("XDG_") and key not in ("XDG_CACHE_HOME", "XDG_CONFIG_HOME", "XDG_DATA_HOME"):
            env.pop(key)
    return env


def extract(
    ytdlp: str,
    qjs: str,
    target: str,
    timeout: float,
    env: Dict[str, str],
    extra_args: Sequence[str] = (),
    use_js_runtime: bool = True,
    qjs_override: Optional[str] = None,
) -> Tuple[CommandResult, Optional[Dict[str, Any]]]:
    argv: List[str] = [ytdlp]
    argv.extend(RESOLVER_FLAGS)
    if use_js_runtime:
        argv.append("--no-js-runtimes")
        argv.append("--js-runtimes")
        argv.append("quickjs:%s" % (qjs_override or qjs))
    argv.extend(extra_args)
    argv.extend(DIAGNOSTIC_FLAGS)
    argv.append(target)
    result = run_command(argv, timeout=timeout, env=env)
    payload: Optional[Dict[str, Any]] = None
    if result.stdout:
        try:
            candidate = json.loads(result.stdout.decode("utf-8", "replace"))
            if isinstance(candidate, dict):
                payload = candidate
        except (ValueError, UnicodeDecodeError):
            payload = None
    return result, payload


def summarize(run: ExtractionRun, payload: Optional[Dict[str, Any]], result: CommandResult) -> None:
    run.exit_code = result.exit_code
    run.timed_out = result.timed_out
    run.latency_ms = result.duration_ms
    run.peak_tree_rss_bytes = result.peak_tree_rss_bytes
    run.rss_samples = result.rss_samples
    run.peak_process_count = result.peak_process_count
    run.failure_class = classify_failure(result.exit_code, result.stderr, result.timed_out)
    stderr = result.stderr or ""
    # Per-run evidence that the JS runtime actually did the work.
    run.js_challenge_solved = "Solving JS challenges using quickjs" in stderr
    run.js_challenge_attempted = ("jsc:quickjs" in stderr
                                  or "Error solving n challenge" in stderr
                                  or "n challenge solving failed" in stderr)
    run.js_challenge_failed = ("Error solving n challenge" in stderr
                               or "n challenge solving failed" in stderr)
    runtime_match = re.search(r"JS runtimes:.*?quickjs-ng-([0-9.]+)", stderr)
    run.js_runtime_reported = runtime_match.group(1) if runtime_match else None
    ejs_match = re.search(r"yt_dlp_ejs-([0-9.]+)", stderr)
    run.ejs_version_reported = ejs_match.group(1) if ejs_match else None
    source_match = re.search(
        r"challenge solver (?:lib|core) script v?[0-9.]+ \(source: ([a-z ]+)", stderr)
    run.js_solver_source = source_match.group(1).strip() if source_match else None
    if stderr.strip():
        run.stderr_excerpt = redact(stderr)
    if not payload:
        run.ok = False
        return
    formats = payload.get("formats") or []
    run.format_count = len(formats)
    run.title = (payload.get("title") or "")[:80] or None
    run.duration_s = payload.get("duration") if isinstance(payload.get("duration"), int) else None
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
        run.format_id = best.get("format_id")
        run.protocol = best.get("protocol")
        run.vcodec = best.get("vcodec")
        run.acodec = best.get("acodec")
        run.abr = best.get("abr")
        run.ext = best.get("ext")
        headers = best.get("http_headers") or {}
        run.header_names = [str(name) for name in headers.keys()]
        url = best.get("url") or ""
        run.url_has_n_param = "&n=" in url or url.startswith("n=")
        run.url_has_sig_param = "&sig=" in url or url.startswith("sig=")
    run.ok = result.exit_code == 0 and best is not None and bool((best or {}).get("url"))
    if run.ok:
        run.failure_class = "NONE"
    elif run.failure_class == "NONE":
        run.failure_class = "NO_MEDIA_URL"


# ---------------------------------------------------------------------------
# HTTP probe
# ---------------------------------------------------------------------------

_SAFE_HEADER_DENY = ("cookie", "authorization", "set-cookie", "proxy-authorization")


def _probe_headers(headers: Dict[str, str]) -> Dict[str, str]:
    return {
        str(name): str(value)
        for name, value in headers.items()
        if str(name).lower() not in _SAFE_HEADER_DENY
    }


def probe_url(url: str, headers: Dict[str, str], timeout: float) -> HttpProbe:
    """Short ranged GET at the head and at the tail of the object.

    Mirrors ``probeResolvedAudioStream`` in desktop-backend: libVLC seeks while
    opening WebM/MP4 streams, so a URL that serves byte zero but rejects a later
    range can start playing and then fail.
    """
    probe = HttpProbe(attempted=True)
    safe_headers = _probe_headers(headers)
    max_bytes = 64 * 1024

    def fetch(offset: int, length: int) -> Tuple[int, bytes, Optional[str], bool, Optional[str]]:
        end = offset + length - 1
        request = urllib.request.Request(url, headers={**safe_headers, "Range": "bytes=%d-%d" % (offset, end)})
        opener = urllib.request.build_opener(NoRedirect())
        with opener.open(request, timeout=timeout) as response:
            body = response.read(length)
            content_range = response.headers.get("Content-Range")
            ranged = response.status == 206 or content_range is not None
            return response.status, body, response.headers.get("Content-Type"), ranged, content_range

    started = time.monotonic()
    try:
        status, body, content_type, ranged, content_range = fetch(0, max_bytes)
        probe.status = status
        probe.bytes_read = len(body)
        probe.content_type = content_type
        probe.ranged_response = ranged
        probe.magic_hex = body[:4].hex() if body else None
        # Tail probe: use the total size advertised by the head range response.
        total = None
        if content_range:
            tail = content_range.rsplit("/", 1)[-1]
            if tail.isdigit():
                total = int(tail)
        if total and total > len(body):
            try:
                tail_status, tail_body, _, _, _ = fetch(max(total - max_bytes, len(body)), max_bytes)
                probe.tail_status = tail_status
                probe.tail_bytes_read = len(tail_body)
            except Exception as error:  # pragma: no cover - reported as failure class
                probe.error = redact("tail range failed: %s" % error, 200)
        probe.duration_ms = int((time.monotonic() - started) * 1000)
    except urllib.error.HTTPError as error:
        probe.status = error.code
        probe.error = "HTTP %s" % error.code
        probe.duration_ms = int((time.monotonic() - started) * 1000)
    except Exception as error:
        probe.error = redact("%s: %s" % (type(error).__name__, error), 200)
        probe.duration_ms = int((time.monotonic() - started) * 1000)
    return probe


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: D102, N802
        return None


# ---------------------------------------------------------------------------
# Live playback verification
# ---------------------------------------------------------------------------


def _libvlc_probe(url: str, headers: Dict[str, str], timeout: float) -> LivePlayback:
    """Open the resolved URL in libVLC and check that playback actually starts.

    The probe runs in a child process whose stderr is discarded, because libVLC
    prints the full signed media URL in its own "unable to open the MRL"
    diagnostics. The signed URL is handed to the child over stdin so it never
    appears on a command line either.
    """
    payload = json.dumps({"url": url, "headers": headers, "timeout": timeout})
    argv = [sys.executable, os.path.abspath(__file__), "--internal-libvlc-probe"]
    try:
        completed = subprocess.run(
            argv,
            input=payload.encode("utf-8"),
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=timeout + 30,
        )
    except subprocess.TimeoutExpired:
        result = LivePlayback(checked=True)
        result.vlc_error = "libvlc probe timed out"
        return result
    except OSError as error:  # pragma: no cover - environment failure
        result = LivePlayback(checked=True)
        result.vlc_error = redact("libvlc probe could not start: %s" % error, 200)
        return result
    try:
        summary = json.loads(completed.stdout.decode("utf-8", "replace"))
    except (ValueError, UnicodeDecodeError):
        summary = {}
    result = LivePlayback(checked=True)
    result.vlc_available = bool(summary.get("vlc_available"))
    result.vlc_state_path = summary.get("vlc_state_path")
    result.vlc_reached_playing = bool(summary.get("vlc_reached_playing"))
    result.vlc_position_advanced = bool(summary.get("vlc_position_advanced"))
    result.vlc_error = summary.get("vlc_error")
    return result


def _libvlc_probe_child(url: str, headers: Dict[str, str], timeout: float) -> Dict[str, Any]:
    """The libVLC probe body. Runs inside the child process."""
    import ctypes

    summary: Dict[str, Any] = {
        "vlc_available": False,
        "vlc_state_path": None,
        "vlc_reached_playing": False,
        "vlc_position_advanced": False,
        "vlc_error": None,
    }
    try:
        lib = ctypes.CDLL("libvlc.so.5")
    except OSError:
        try:
            lib = ctypes.CDLL("libvlc.so")
        except OSError:
            summary["vlc_error"] = "libvlc shared library not found"
            return summary
    summary["vlc_available"] = True

    lib.libvlc_new.argtypes = [ctypes.c_int]
    lib.libvlc_new.restype = ctypes.c_void_p
    lib.libvlc_media_new_location.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
    lib.libvlc_media_new_location.restype = ctypes.c_void_p
    lib.libvlc_media_add_option.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
    lib.libvlc_media_player_new.argtypes = [ctypes.c_void_p]
    lib.libvlc_media_player_new.restype = ctypes.c_void_p
    lib.libvlc_media_player_set_media.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
    lib.libvlc_media_player_play.argtypes = [ctypes.c_void_p]
    lib.libvlc_media_player_stop.argtypes = [ctypes.c_void_p]
    lib.libvlc_media_player_release.argtypes = [ctypes.c_void_p]
    lib.libvlc_media_release.argtypes = [ctypes.c_void_p]
    lib.libvlc_release.argtypes = [ctypes.c_void_p]
    lib.libvlc_media_player_get_state.argtypes = [ctypes.c_void_p]
    lib.libvlc_media_player_get_state.restype = ctypes.c_int
    lib.libvlc_media_player_get_time.argtypes = [ctypes.c_void_p]
    lib.libvlc_media_player_get_time.restype = ctypes.c_int64

    states = {0: "NothingSpecial", 1: "Opening", 2: "Buffering", 3: "Playing",
              4: "Paused", 5: "Stopped", 6: "Ended", 7: "Error"}
    instance = lib.libvlc_new(0)
    if not instance:
        summary["vlc_error"] = "libvlc_new failed"
        return summary
    media = None
    player = None
    try:
        media = lib.libvlc_media_new_location(instance, url.encode())
        if not media:
            summary["vlc_error"] = "libvlc_media_new_location returned null"
            return summary
        for name, value in _probe_headers(headers).items():
            if name.lower() in ("user-agent", "referer"):
                lib.libvlc_media_add_option(media, (":http-%s=%s" % (name.lower(), value)).encode())
        player = lib.libvlc_media_player_new(instance)
        lib.libvlc_media_player_set_media(player, media)
        lib.libvlc_media_player_play(player)

        path: List[str] = []
        last_state = None
        first_time = None
        last_time = None
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            state = lib.libvlc_media_player_get_state(player)
            if state != last_state:
                path.append(states.get(state, str(state)))
                last_state = state
            if state == 3:
                current = lib.libvlc_media_player_get_time(player)
                if first_time is None:
                    first_time = current
                last_time = current
                if current > 0:
                    break
            if state in (5, 6, 7):
                break
            time.sleep(0.05)
        summary["vlc_state_path"] = "->".join(path)
        summary["vlc_reached_playing"] = "Playing" in path
        summary["vlc_position_advanced"] = bool(
            first_time is not None and last_time is not None and last_time > 0)
        if not summary["vlc_reached_playing"] and "Error" in path:
            summary["vlc_error"] = "libVLC reported Error state"
    except Exception as error:  # pragma: no cover - environment failure
        summary["vlc_error"] = redact("%s: %s" % (type(error).__name__, error), 200)
    finally:
        if player:
            try:
                lib.libvlc_media_player_stop(player)
            except Exception:
                pass
            try:
                lib.libvlc_media_player_release(player)
            except Exception:
                pass
        if media:
            try:
                lib.libvlc_media_release(media)
            except Exception:
                pass
        try:
            lib.libvlc_release(instance)
        except Exception:
            pass
    return summary


def _ffmpeg_probe(url: str, headers: Dict[str, str], timeout: float, seconds: int = 5,
                  offset: int = 5) -> LivePlayback:
    """Decode the resolved URL to PCM to prove it carries real audio content.

    The decode seeks into the stream first: some tracks open with digital
    silence, and an input side seek also exercises the CDN range request that
    libVLC performs while opening a WebM/MP4 stream.
    """
    import math
    import struct

    result = LivePlayback(checked=True)
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        result.ffmpeg_error = "ffmpeg not found"
        return result
    result.ffmpeg_available = True
    safe = _probe_headers(headers)
    header_blob = "".join("%s: %s\r\n" % (name, value) for name, value in safe.items())
    argv = [
        ffmpeg, "-v", "error", "-nostdin",
        "-headers", header_blob,
    ]
    if offset > 0:
        argv.extend(["-ss", str(offset)])
    argv.extend([
        "-i", url,
        "-t", str(seconds),
        "-f", "f32le", "-ac", "1", "-ar", "48000", "-",
    ])
    try:
        completed = subprocess.run(argv, capture_output=True, timeout=timeout + 10)
    except subprocess.TimeoutExpired:
        result.ffmpeg_error = "ffmpeg timed out"
        return result
    except OSError as error:  # pragma: no cover - environment failure
        result.ffmpeg_error = redact(str(error), 200)
        return result
    data = completed.stdout or b""
    frames = len(data) // 4
    result.ffmpeg_pcm_frames = frames
    result.ffmpeg_offset_s = offset
    if frames:
        values = struct.unpack("<%df" % frames, data[: frames * 4])
        result.ffmpeg_rms = math.sqrt(sum(v * v for v in values) / frames)
        result.ffmpeg_peak = max(abs(v) for v in values)
    if completed.returncode != 0 and frames == 0:
        result.ffmpeg_error = redact(completed.stderr.decode("utf-8", "replace"), 200)
    return result


def verify_live_playback(url: str, headers: Dict[str, str], timeout: float, mode: str,
                         retries: int = 2) -> LivePlayback:
    if mode == "off":
        return LivePlayback(checked=False)
    combined = LivePlayback(checked=True)
    vlc = LivePlayback(checked=True)
    attempts = 0
    for attempt in range(max(1, retries + 1)):
        attempts = attempt + 1
        vlc = _libvlc_probe(url, headers, timeout)
        if vlc.vlc_reached_playing and vlc.vlc_position_advanced:
            break
        if attempt < retries:
            time.sleep(1.0)
    combined.vlc_available = vlc.vlc_available
    combined.vlc_state_path = vlc.vlc_state_path
    combined.vlc_reached_playing = vlc.vlc_reached_playing
    combined.vlc_position_advanced = vlc.vlc_position_advanced
    combined.vlc_error = vlc.vlc_error
    combined.vlc_attempts = attempts

    ff = LivePlayback(checked=True)
    attempts = 0
    for attempt in range(max(1, retries + 1)):
        attempts = attempt + 1
        for offset in (5, 0, 15):
            ff = _ffmpeg_probe(url, headers, timeout, offset=offset)
            if ff.ffmpeg_pcm_frames > 0 and (ff.ffmpeg_rms or 0.0) > 1e-4:
                break
            if ff.ffmpeg_pcm_frames == 0:
                break
        if ff.ffmpeg_pcm_frames > 0 and (ff.ffmpeg_rms or 0.0) > 1e-4:
            break
        if attempt < retries:
            time.sleep(1.0)
    combined.ffmpeg_available = ff.ffmpeg_available
    combined.ffmpeg_pcm_frames = ff.ffmpeg_pcm_frames
    combined.ffmpeg_rms = ff.ffmpeg_rms
    combined.ffmpeg_peak = ff.ffmpeg_peak
    combined.ffmpeg_error = ff.ffmpeg_error
    combined.ffmpeg_offset_s = ff.ffmpeg_offset_s
    combined.ffmpeg_attempts = attempts
    decoded = ff.ffmpeg_pcm_frames > 0 and (ff.ffmpeg_rms or 0.0) > 1e-4
    played = vlc.vlc_reached_playing and vlc.vlc_position_advanced
    if played and decoded:
        combined.verdict = "LIVE_PLAYBACK_CONFIRMED"
    elif played or decoded:
        combined.verdict = "LIVE_PLAYBACK_PARTIAL"
    else:
        combined.verdict = "LIVE_PLAYBACK_NOT_CONFIRMED"
    return combined


# ---------------------------------------------------------------------------
# Challenge proof
# ---------------------------------------------------------------------------


@dataclass
class ChallengeProof:
    attempted: bool = False
    client: Optional[str] = None
    challenge_triggered: bool = False
    quickjs_script_invocations: int = 0
    negative_control_failed_as_expected: bool = False
    negative_control_error: Optional[str] = None
    missing_runtime_failed_as_expected: bool = False
    missing_runtime_error: Optional[str] = None
    client_success_without_runtime: bool = False
    notes: List[str] = field(default_factory=list)


def _make_qjs_sentinel(real_qjs: str, counter_path: str) -> str:
    """A qjs shim that counts invocations and delegates to the real binary."""
    directory = tempfile.mkdtemp(prefix="archivetune-qjs-sentinel-")
    shim = os.path.join(directory, "qjs")
    counter = os.path.abspath(counter_path)
    with open(shim, "w") as handle:
        handle.write(
            "#!/bin/sh\n"
            'printf "%%s\\n" "$*" >> "%s"\n'
            'exec "%s" "$@"\n' % (counter, real_qjs)
        )
    os.chmod(shim, 0o755)
    return shim


def prove_challenge(
    ytdlp: str,
    qjs: str,
    target: str,
    timeout: float,
    env: Dict[str, str],
    workdir: str,
) -> ChallengeProof:
    """Prove the QuickJS runtime is load-bearing for the YouTube n challenge.

    Strategy: find a player client that is actually challenged, then run a
    negative control with a deliberately broken runtime. If the broken runtime
    changes the outcome, the runtime really is solving the challenge.
    """
    proof = ChallengeProof(attempted=True)
    counter = os.path.join(workdir, "qjs-invocations.log")
    sentinel = _make_qjs_sentinel(qjs, counter)

    def count_script_invocations() -> int:
        try:
            with open(counter, "r") as handle:
                return sum(1 for line in handle if "--script" in line)
        except OSError:
            return 0

    def run(client: str, runtime: Optional[str]) -> CommandResult:
        extra = ["--extractor-args", "youtube:player_client=%s" % client]
        if runtime is None:
            return extract(ytdlp, qjs, target, timeout, env, extra_args=extra, use_js_runtime=False)
        return extract(ytdlp, qjs, target, timeout, env, extra_args=extra,
                       use_js_runtime=True, qjs_override=runtime)

    # Step 1: find a challenged client using the instrumented runtime.
    for client in CHALLENGE_CLIENTS:
        if os.path.exists(counter):
            os.remove(counter)
        result = run(client, sentinel)
        proof.client = client
        proof.quickjs_script_invocations = count_script_invocations()
        if proof.quickjs_script_invocations > 0:
            proof.challenge_triggered = True
            proof.notes.append("client %s triggered %d QuickJS challenge script run(s)"
                               % (client, proof.quickjs_script_invocations))
            break
        proof.notes.append("client %s did not trigger the n challenge" % client)
    if not proof.challenge_triggered:
        proof.notes.append("no challenged client found; the n challenge was not exercised")
        return proof

    # Step 2: negative control - a runtime that fails every --script invocation.
    broken = os.path.join(workdir, "qjs-broken")
    with open(broken, "w") as handle:
        handle.write(
            "#!/bin/sh\n"
            'case "$1" in\n'
            '  --help) echo "QuickJS-ng version 0.17.0"; exit 0;;\n'
            '  --script) echo "ARCHIVETUNE_SENTINEL_RUNTIME_FAILURE" >&2; exit 3;;\n'
            'esac\n'
            "exit 3\n"
        )
    os.chmod(broken, 0o755)
    broken_result, _ = run(proof.client, broken)
    proof.negative_control_failed_as_expected = broken_result.exit_code != 0
    proof.negative_control_error = redact(broken_result.stderr, 400)

    # Step 3: negative control - no runtime at all.
    missing_result, _ = run(proof.client, None)
    proof.missing_runtime_failed_as_expected = missing_result.exit_code != 0
    proof.missing_runtime_error = redact(missing_result.stderr, 400)
    proof.client_success_without_runtime = missing_result.exit_code == 0
    return proof


# ---------------------------------------------------------------------------
# Reporting
# ---------------------------------------------------------------------------

CSV_COLUMNS = [
    "video_id", "repeat", "ok", "failure_class", "exit_code", "timed_out",
    "latency_ms", "peak_tree_rss_bytes", "rss_samples", "peak_process_count",
    "format_id", "format_count", "protocol", "vcodec", "acodec", "abr", "ext",
    "duration_s", "url_has_n_param", "url_has_sig_param",
    "js_challenge_solved", "js_challenge_attempted", "js_challenge_failed",
    "js_runtime_reported", "ejs_version_reported", "js_solver_source",
    "http_probe_status", "http_probe_bytes", "http_probe_ranged",
    "http_probe_tail_status", "http_probe_tail_bytes",
    "live_verdict", "vlc_reached_playing", "vlc_position_advanced", "vlc_attempts",
    "ffmpeg_pcm_frames", "ffmpeg_rms", "ffmpeg_peak", "ffmpeg_attempts",
]


def write_reports(report: Dict[str, Any], runs: List[ExtractionRun], out_dir: str) -> Tuple[str, str]:
    os.makedirs(out_dir, exist_ok=True)
    json_path = os.path.join(out_dir, "ytdlp-benchmark-report.json")
    csv_path = os.path.join(out_dir, "ytdlp-benchmark-runs.csv")
    with open(json_path, "w") as handle:
        json.dump(report, handle, indent=2, sort_keys=True)
        handle.write("\n")
    with open(csv_path, "w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=CSV_COLUMNS)
        writer.writeheader()
        for run in runs:
            probe = run.http_probe
            playback = run.live_playback
            writer.writerow({
                "video_id": run.video_id,
                "repeat": run.repeat,
                "ok": run.ok,
                "failure_class": run.failure_class,
                "exit_code": run.exit_code,
                "timed_out": run.timed_out,
                "latency_ms": run.latency_ms,
                "peak_tree_rss_bytes": run.peak_tree_rss_bytes,
                "rss_samples": run.rss_samples,
                "peak_process_count": run.peak_process_count,
                "format_id": run.format_id,
                "format_count": run.format_count,
                "protocol": run.protocol,
                "vcodec": run.vcodec,
                "acodec": run.acodec,
                "abr": run.abr,
                "ext": run.ext,
                "duration_s": run.duration_s,
                "url_has_n_param": run.url_has_n_param,
                "url_has_sig_param": run.url_has_sig_param,
                "js_challenge_solved": run.js_challenge_solved,
                "js_challenge_attempted": run.js_challenge_attempted,
                "js_challenge_failed": run.js_challenge_failed,
                "js_runtime_reported": run.js_runtime_reported,
                "ejs_version_reported": run.ejs_version_reported,
                "js_solver_source": run.js_solver_source,
                "http_probe_status": probe.status,
                "http_probe_bytes": probe.bytes_read,
                "http_probe_ranged": probe.ranged_response,
                "http_probe_tail_status": probe.tail_status,
                "http_probe_tail_bytes": probe.tail_bytes_read,
                "live_verdict": playback.verdict,
                "vlc_reached_playing": playback.vlc_reached_playing,
                "vlc_position_advanced": playback.vlc_position_advanced,
                "vlc_attempts": playback.vlc_attempts,
                "ffmpeg_pcm_frames": playback.ffmpeg_pcm_frames,
                "ffmpeg_rms": playback.ffmpeg_rms,
                "ffmpeg_peak": playback.ffmpeg_peak,
                "ffmpeg_attempts": playback.ffmpeg_attempts,
            })
    return json_path, csv_path


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Live yt-dlp + QuickJS-NG extraction benchmark for ArchiveTune.",
    )
    parser.add_argument("--yt-dlp", dest="ytdlp", default="build/tools/resolver/yt-dlp",
                        help="path to the yt-dlp executable (default: %(default)s)")
    parser.add_argument("--qjs", dest="qjs", default="build/tools/resolver/qjs",
                        help="path to the QuickJS-NG executable (default: %(default)s)")
    parser.add_argument("--videos", default=",".join(DEFAULT_VIDEOS),
                        help="comma separated video ids or urls (default: %(default)s)")
    parser.add_argument("--repeats", type=int, default=2,
                        help="extractions per video (default: %(default)s)")
    parser.add_argument("--timeout", type=float, default=120.0,
                        help="per-extraction timeout in seconds (default: %(default)s)")
    parser.add_argument("--sample-interval", type=float, default=0.02,
                        help="process tree RSS sampling interval in seconds (default: %(default)s)")
    parser.add_argument("--out-dir", default="build/reports/ytdlp",
                        help="directory for the JSON and CSV reports (default: %(default)s)")
    parser.add_argument("--live-playback", choices=("off", "on", "first"), default="first",
                        help="live playback verification mode (default: %(default)s)")
    parser.add_argument("--live-timeout", type=float, default=25.0,
                        help="live playback verification timeout in seconds (default: %(default)s)")
    parser.add_argument("--live-retries", type=int, default=2,
                        help="retries for the live playback probes (default: %(default)s)")
    parser.add_argument("--probe-timeout", type=float, default=20.0,
                        help="http probe timeout in seconds (default: %(default)s)")
    parser.add_argument("--skip-challenge-proof", action="store_true",
                        help="skip the negative-control proof that QuickJS solves the n challenge")
    parser.add_argument("--expect-ytdlp-sha256", default=YTDLP_SHA256)
    parser.add_argument("--expect-qjs-sha256", default=QJS_SHA256)
    parser.add_argument("--keep-workdir", action="store_true",
                        help="keep the temporary hermetic HOME instead of deleting it")
    parser.add_argument("--internal-libvlc-probe", action="store_true",
                        help=argparse.SUPPRESS)
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    if args.internal_libvlc_probe:
        # Child mode for the libVLC probe. The signed URL arrives on stdin so it
        # never shows up in a command line, and this process' stderr is
        # discarded by the parent because libVLC prints the URL in its own
        # diagnostics.
        try:
            request = json.loads(sys.stdin.read())
            summary = _libvlc_probe_child(request["url"], request.get("headers") or {},
                                          float(request.get("timeout") or 25.0))
        except Exception as error:  # pragma: no cover - defensive
            summary = {"vlc_available": False, "vlc_error": "child probe failed: %s" % type(error).__name__}
        sys.stdout.write(json.dumps(summary))
        sys.stdout.flush()
        return 0
    started_at = time.time()

    ytdlp_report = verify_tool("yt-dlp", args.ytdlp, args.expect_ytdlp_sha256, YTDLP_VERSION)
    qjs_report = verify_tool("quickjs-ng", args.qjs, args.expect_qjs_sha256, QJS_VERSION)
    tools = {"yt-dlp": vars(ytdlp_report), "quickjs-ng": vars(qjs_report)}

    tool_ok = (ytdlp_report.exists and ytdlp_report.executable and ytdlp_report.sha256_matches
               and qjs_report.exists and qjs_report.executable and qjs_report.sha256_matches)

    workdir = tempfile.mkdtemp(prefix="archivetune-ytdlp-bench-")
    env = hermetic_env(workdir)
    runs: List[ExtractionRun] = []
    challenge = ChallengeProof()

    try:
        if tool_ok:
            for raw_video in [item for item in args.videos.split(",") if item.strip()]:
                video_id, target, note = normalize_video(raw_video)
                if note:
                    print("[warn] %s" % note, flush=True)
                for repeat in range(1, max(1, args.repeats) + 1):
                    run = ExtractionRun(video_id=video_id, repeat=repeat)
                    result, payload = extract(ytdlp_report.path, qjs_report.path, target,
                                              args.timeout, env)
                    summarize(run, payload, result)
                    if run.ok and payload:
                        best = None
                        for candidate in payload.get("formats") or []:
                            if candidate.get("format_id") == "251":
                                best = candidate
                                break
                        if best is None:
                            for candidate in payload.get("formats") or []:
                                if candidate.get("acodec") not in (None, "none"):
                                    best = candidate
                                    break
                        if best is not None:
                            url = best.get("url") or ""
                            headers = best.get("http_headers") or {}
                            run.http_probe = probe_url(url, headers, args.probe_timeout)
                            want_live = (args.live_playback == "on"
                                         or (args.live_playback == "first" and repeat == 1))
                            if want_live:
                                run.live_playback = verify_live_playback(
                                    url, headers, args.live_timeout, "on",
                                    retries=max(0, args.live_retries))
                    runs.append(run)
                    print("[run] %s repeat=%d ok=%s failure=%s latency=%dms peak_rss=%.1fMiB format=%s"
                          % (run.video_id, run.repeat, run.ok, run.failure_class, run.latency_ms,
                             run.peak_tree_rss_bytes / (1024 * 1024), run.format_id), flush=True)
            if not args.skip_challenge_proof:
                _, target, _ = normalize_video(args.videos.split(",")[0])
                challenge = prove_challenge(ytdlp_report.path, qjs_report.path, target,
                                            args.timeout, env, workdir)
    finally:
        if not args.keep_workdir:
            shutil.rmtree(workdir, ignore_errors=True)

    successes = [run for run in runs if run.ok]
    videos_ok = sorted({run.video_id for run in successes})
    latencies = sorted(run.latency_ms for run in successes)
    peaks = sorted(run.peak_tree_rss_bytes for run in successes)
    live_verdicts = sorted({run.live_playback.verdict for run in runs if run.live_playback.verdict})

    def percentile(values: List[int], fraction: float) -> Optional[int]:
        if not values:
            return None
        index = min(len(values) - 1, int(round(fraction * (len(values) - 1))))
        return values[index]

    report: Dict[str, Any] = {
        "schema": "archivetune.ytdlp.benchmark/1",
        "generated_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(started_at)),
        "duration_s": round(time.time() - started_at, 2),
        "host": {
            "platform": platform.platform(),
            "machine": platform.machine(),
            "python": platform.python_version(),
            "cpu_count": os.cpu_count(),
        },
        "config": {
            "videos": [item.strip() for item in args.videos.split(",") if item.strip()],
            "repeats": args.repeats,
            "timeout_s": args.timeout,
            "sample_interval_s": args.sample_interval,
            "live_playback_mode": args.live_playback,
            "resolver_flags": list(RESOLVER_FLAGS),
        },
        "tools": tools,
        "tools_verified": tool_ok,
        "challenge_proof": vars(challenge),
        "runs": [run.summary() for run in runs],
        "summary": {
            "total_runs": len(runs),
            "successful_runs": len(successes),
            "distinct_videos_ok": len(videos_ok),
            "videos_ok": videos_ok,
            "failure_classes": sorted({run.failure_class for run in runs if not run.ok}),
            "js_challenge_runs_solved": sum(1 for run in runs if run.js_challenge_solved),
            "js_challenge_runs_failed": sum(1 for run in runs if run.js_challenge_failed),
            "videos_with_js_challenge": sorted({run.video_id for run in runs if run.js_challenge_solved}),
            "js_runtime_reported": sorted({run.js_runtime_reported for run in runs if run.js_runtime_reported}),
            "ejs_version_reported": sorted({run.ejs_version_reported for run in runs if run.ejs_version_reported}),
            "js_solver_sources": sorted({run.js_solver_source for run in runs if run.js_solver_source}),
            "latency_ms_min": latencies[0] if latencies else None,
            "latency_ms_p50": percentile(latencies, 0.5),
            "latency_ms_p95": percentile(latencies, 0.95),
            "latency_ms_max": latencies[-1] if latencies else None,
            "peak_tree_rss_bytes_max": peaks[-1] if peaks else None,
            "peak_tree_rss_bytes_median": percentile(peaks, 0.5),
            "live_verdicts": live_verdicts,
        },
    }

    json_path, csv_path = write_reports(report, runs, args.out_dir)
    print("")
    print("report: %s" % json_path)
    print("csv   : %s" % csv_path)
    print("summary: %d/%d runs ok across %d distinct videos; latency p50=%sms p95=%sms; peak tree RSS max=%s bytes"
          % (report["summary"]["successful_runs"], report["summary"]["total_runs"],
             report["summary"]["distinct_videos_ok"], report["summary"]["latency_ms_p50"],
             report["summary"]["latency_ms_p95"], report["summary"]["peak_tree_rss_bytes_max"]))
    if challenge.attempted:
        print("challenge proof: client=%s triggered=%s negative_control_failed=%s missing_runtime_failed=%s"
              % (challenge.client, challenge.challenge_triggered,
                 challenge.negative_control_failed_as_expected,
                 challenge.missing_runtime_failed_as_expected))

    if not tool_ok:
        print("ERROR: tool verification failed", file=sys.stderr)
        return 2
    if len(runs) != len(successes):
        return 1
    if len(videos_ok) < 3:
        print("ERROR: fewer than 3 distinct videos succeeded", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

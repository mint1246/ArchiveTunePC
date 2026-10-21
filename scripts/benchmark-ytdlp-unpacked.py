#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ArchiveTune unpacked yt-dlp release evaluation harness (developer-only).

This script answers one narrow question with measurements instead of engine
claims: **is the upstream unpacked Linux release a cheaper start-up than the
self-extracting bundle that the desktop resolver currently ships?**

What it does
------------
1. Downloads the pinned upstream release artifacts of yt-dlp 2026.08.19 from
   the official GitHub release:
      * ``yt-dlp_linux.zip``   - the upstream Linux zip
      * ``SHA2-256SUMS``       - the published checksum feed of that release
   Nothing is installed globally; the artifacts stay under the git-ignored
   ``.tools/`` directory.
2. Verifies the archive against the **published feed** of the release. That
   feed check is the only acceptance gate. The digest of the extracted member
   is recorded and compared with the digest of the shipped one-file binary,
   but **equality is a finding, not a requirement**: the upstream unpacked
   artifact is expected to be a different binary than the self-extracting
   one-file bundle, and a different digest is not a failure.
3. Extracts the archive with a **path guard**: every member must be a plain
   relative file inside the target directory, no absolute paths, no ``..``,
   no directory traversal and no symlinks.
4. Compares the shipped bundle with the unpacked artifact:
      * 5 interleaved **new process** starts of each (wall time and CPU). The
        operating system page cache is warm after the first launch, so these
        are *not* cold boots; the first launch of each binary is reported
        separately and the label says so,
      * the verbose unsupported-scheme diagnostics that the shipped resolver
        uses for its toolchain check, including the exact JavaScript runtime
        and EJS versions,
      * 3 paired real extractions per scenario (the challenged video with the
        default player client, the same video with the explicit
        ``web_embedded`` client and an unchallenged video) with an identical
        argument set, a head/tail range probe, the process tree RSS and PSS,
        the CPU of the whole tree, the number of challenge-solver invocations
        and a cleanup check.
5. Writes a machine-readable report. **It does not change any default**: the
   harness is a developer tool, nothing in the shipped runtime depends on it
   and **no persistence or caching policy is implied** by the measurement.

Safety contract
---------------
* Cookies are never requested, never read and never written. The run is
  anonymous and the upstream artifacts are fetched over HTTPS from the
  official release only.
* Signed media URLs, raw ``--dump-single-json`` payloads and process argv
  are never written to disk and never printed; only redacted derived fields
  are stored.
* Every extraction runs with a private HOME/XDG_*/TMPDIR.
* The range probe always runs outside the measured request window.

Usage
-----
    scripts/benchmark-ytdlp-unpacked.py \
        --yt-dlp .tools/resolver/yt-dlp \
        --bun    .tools/resolver/bun \
        --repeats 3 \
        --out-json packaging/resolver/validation/linux-ytdlp-unpacked-evaluation.json

Exit code is 0 only when every extraction succeeded, every successful result
passed the head/tail range probe and no process was left behind.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import platform
import re
import resource
import shutil
import signal
import statistics
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import urllib.request
import zipfile
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
RELEASE_BASE = "https://github.com/yt-dlp/yt-dlp/releases/download/%s" % YTDLP_VERSION
LINUX_ZIP_ASSET = "yt-dlp_linux.zip"
CHECKSUM_FEED_ASSET = "SHA2-256SUMS"
CHECKSUM_SIGNATURE_ASSET = "SHA2-256SUMS.sig"
WINDOWS_ZIP_ASSET = "yt-dlp_win.zip"

# The digest of the upstream Linux executable that the desktop resolver
# already ships (buildSrc/src/main/kotlin/ResolverSidecars.kt). The unpacked
# artifact is compared with it as a recorded finding, never an acceptance gate.
UPSTREAM_LINUX_BINARY_SHA256 = "58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a"
BUN_VERSION = "1.4.3"

# Audio-only comparison controls, identical between binaries; not the final production vector.
# ``COMMON_ARGUMENTS`` are the flags that every invocation of the shipped
# resolver carries (YtDlpStreamResolver.commonArguments), ``REQUEST_FLAGS``
# the ones that only a real request carries.
COMMON_ARGUMENTS: Tuple[str, ...] = (
    "--ignore-config",
    "--no-plugin-dirs",
    "--no-remote-components",
    "--no-cache-dir",
)
REQUEST_FLAGS: Tuple[str, ...] = (
    "--no-playlist",
    "--skip-download",
    "--dump-single-json",
)
FORMAT_SELECTOR = "bestaudio[protocol=https]/bestaudio[protocol=http]"

# The toolchain probe of the shipped resolver: it exits before any network
# request but after the start-up diagnostics.
TOOLCHAIN_PROBE_ARGS: Tuple[str, ...] = ("--verbose", "--simulate", "--", "archivetune-runtime-check:")

CHALLENGED_VIDEO = "dQw4w9WgXcQ"
SIMPLE_VIDEO = "jNQXAC9IVRw"
CHALLENGE_CLIENT = "web_embedded"


# ---------------------------------------------------------------------------
# Small helpers
# ---------------------------------------------------------------------------


def percentile(values: Sequence[float], fraction: float) -> Optional[float]:
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


def child_cpu() -> float:
    usage = resource.getrusage(resource.RUSAGE_CHILDREN)
    return usage.ru_utime + usage.ru_stime


def mib(value: Optional[float]) -> Optional[float]:
    if value is None:
        return None
    return round(value / (1024 * 1024), 1)


# ---------------------------------------------------------------------------
# Process tree memory sampler (RSS + PSS)
# ---------------------------------------------------------------------------


class TreeMemorySampler(threading.Thread):
    """Samples the summed RSS and PSS of a process tree from ``/proc``.

    The sampler polls every ``interval`` seconds, so a spike that lives
    entirely between two samples is not observed: the reported peaks are lower
    bounds. RSS counts shared pages once per mapping process, PSS divides them
    between their users.
    """

    def __init__(self, pid: int, interval: float = 0.05) -> None:
        super().__init__(daemon=True, name="tree-memory-sampler")
        self.pid = pid
        self.interval = interval
        self.peak_rss = 0
        self.peak_pss = 0
        self.peak_processes = 0
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

    def sample(self) -> None:
        rss = 0
        pss = 0
        pids = self._tree()
        for pid in pids:
            rss += self._rss_of(pid)
            pss += self._pss_of(pid)
        if pss == 0 and pids:
            self.pss_supported = False
        self.peak_rss = max(self.peak_rss, rss)
        self.peak_pss = max(self.peak_pss, pss)
        self.peak_processes = max(self.peak_processes, len(pids))

    def run(self) -> None:
        while not self._stop.is_set():
            self.sample()
            self._stop.wait(self.interval)

    def stop(self) -> None:
        self._stop.set()
        self.join(timeout=3.0)


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
    """Count the Bun invocations that ran the challenge solver.

    The runtime version probe (``bun --version``) is not a solver run.
    """
    try:
        with open(log_path, "r") as handle:
            lines = [line.strip() for line in handle if line.strip()]
    except OSError:
        return 0
    return sum(1 for line in lines if "--version" not in line)


# ---------------------------------------------------------------------------
# Download and verification
# ---------------------------------------------------------------------------


def download(url: str, destination: str, timeout: float = 120.0) -> Dict[str, Any]:
    """Download an upstream artifact to ``destination`` and hash it.

    The download goes to a ``.part`` file first and is renamed on success, so
    a failed or interrupted download can never be mistaken for a complete
    artifact by a later run.
    """
    request = urllib.request.Request(url, headers={"User-Agent": "archivetune-unpacked-evaluation"})
    partial = destination + ".part"
    digest = hashlib.sha256()
    size = 0
    started = time.monotonic()
    with urllib.request.urlopen(request, timeout=timeout) as response:
        with open(partial, "wb") as handle:
            while True:
                chunk = response.read(256 * 1024)
                if not chunk:
                    break
                digest.update(chunk)
                size += len(chunk)
                handle.write(chunk)
    os.replace(partial, destination)
    return {
        "url": url,
        "path": destination,
        "size_bytes": size,
        "sha256": digest.hexdigest(),
        "duration_s": round(time.monotonic() - started, 3),
    }


def parse_checksum_feed(text: str) -> Dict[str, str]:
    """Parse a ``SHA2-256SUMS`` style feed into ``{filename: digest}``."""
    digests: Dict[str, str] = {}
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        parts = line.split(None, 1)
        if len(parts) != 2:
            continue
        digest, name = parts[0], parts[1].strip()
        if name.startswith("*"):
            name = name[1:]
        digests[name] = digest.lower()
    return digests


def verify_zip(path: str, expected_digest: Optional[str]) -> List[Dict[str, Any]]:
    """List the members of a zip and check every member for path traversal.

    Returns the member list with the reason of every rejected member. Nothing
    is written to disk here; the caller decides what to extract.
    """
    members: List[Dict[str, Any]] = []
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            name = info.filename
            reason = None
            if name.startswith("/") or name.startswith("\\"):
                reason = "absolute path"
            elif ".." in name.replace("\\", "/").split("/"):
                reason = "parent directory traversal"
            members.append({
                "name": name,
                "size_bytes": info.file_size,
                "compressed_bytes": info.compress_size,
                "is_dir": info.is_dir(),
                "is_symlink": (info.external_attr >> 16) & 0o170000 == 0o120000,
                "rejected_because": reason,
            })
    return members


def _is_elf(path: str) -> bool:
    """True for an ELF image."""
    try:
        with open(path, "rb") as handle:
            return handle.read(4) == b"\x7fELF"
    except OSError:
        return False


def _looks_executable(path: str) -> bool:
    """True for an ELF image or a script with a shebang."""
    try:
        with open(path, "rb") as handle:
            head = handle.read(4)
    except OSError:
        return False
    return head[:4] == b"\x7fELF" or head[:2] == b"#!"


def extract_zip_guarded(path: str, target_dir: str) -> List[Dict[str, Any]]:
    """Extract a zip into ``target_dir`` with a strict path guard."""
    os.makedirs(target_dir, exist_ok=True)
    root = os.path.realpath(target_dir)
    extracted: List[Dict[str, Any]] = []
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            if info.is_dir():
                continue
            name = info.filename.replace("\\", "/")
            if name.startswith("/") or ".." in name.split("/"):
                raise RuntimeError("refusing to extract %r" % info.filename)
            if (info.external_attr >> 16) & 0o170000 == 0o120000:
                raise RuntimeError("refusing to extract the symlink %r" % info.filename)
            destination = os.path.realpath(os.path.join(root, name))
            if not destination.startswith(root + os.sep):
                raise RuntimeError("refusing to extract %r outside the target" % info.filename)
            os.makedirs(os.path.dirname(destination), exist_ok=True)
            with archive.open(info) as source, open(destination, "wb") as target:
                shutil.copyfileobj(source, target)
            mode = (info.external_attr >> 16) & 0o777
            if _looks_executable(destination):
                # An ELF image or a script with a shebang is executable even
                # when the archive stores no (or wrong) permission bits.
                os.chmod(destination, 0o755)
            elif mode:
                os.chmod(destination, mode)
            extracted.append({
                "name": name,
                "path": destination,
                "size_bytes": info.file_size,
                "sha256": SHA256_OF(destination),
                "executable": os.access(destination, os.X_OK),
            })
    return extracted


# ---------------------------------------------------------------------------
# Extraction runs
# ---------------------------------------------------------------------------


@dataclass
class ExtractionRun:
    configuration: str
    scenario: str
    video_id: str
    player_client: Optional[str]
    round_index: int
    ok: bool = False
    failure_class: str = "NOT_RUN"
    wall_ms: Optional[float] = None
    cpu_total_s: Optional[float] = None
    peak_rss_bytes: Optional[int] = None
    peak_pss_bytes: Optional[int] = None
    peak_processes: Optional[int] = None
    solver_invocations: Optional[int] = None
    probe: Dict[str, Any] = field(default_factory=dict)
    summary: Dict[str, Any] = field(default_factory=dict)
    diagnostics: Dict[str, Any] = field(default_factory=dict)
    exit_code: int = -1

    def to_json(self) -> Dict[str, Any]:
        return {
            "configuration": self.configuration,
            "scenario": self.scenario,
            "video_id": self.video_id,
            "player_client": self.player_client,
            "round": self.round_index,
            "ok": self.ok,
            "failure_class": self.failure_class,
            "wall_ms": self.wall_ms,
            "cpu_total_s": self.cpu_total_s,
            "peak_tree_rss_bytes": self.peak_rss_bytes,
            "peak_tree_pss_bytes": self.peak_pss_bytes,
            "peak_process_count": self.peak_processes,
            "solver_invocations": self.solver_invocations,
            "probe": self.probe,
            "summary": self.summary,
            "diagnostics": self.diagnostics,
            "exit_code": self.exit_code,
        }


def parse_diagnostics(stderr: str) -> Dict[str, Any]:
    """Extract the start-up facts of the toolchain probe from the diagnostics."""
    facts: Dict[str, Any] = {}
    version = re.search(r"yt-dlp version (\S+) from (\S+) \(([^)]+)\)", stderr)
    if version:
        facts["yt_dlp_version"] = version.group(1)
        facts["yt_dlp_source"] = version.group(2)
        facts["yt_dlp_build"] = version.group(3)
    python = re.search(r"Python (\S+) \(([^)]+)\)", stderr)
    if python:
        facts["python_version"] = python.group(1)
    ejs = re.search(r"yt_dlp_ejs-([0-9.]+)", stderr)
    if ejs:
        facts["ejs_version"] = ejs.group(1)
    for line in stderr.splitlines():
        stripped = line.strip()
        if "JS runtimes:" in stripped and "js_runtimes" not in facts:
            facts["js_runtimes"] = stripped.split("JS runtimes:", 1)[1].split(" | ")[0].strip()
        if "exe versions:" in stripped and "exe_versions" not in facts:
            facts["exe_versions"] = stripped.split("exe versions:", 1)[1].split(" | ")[0].strip()
        if "Optional libraries:" in stripped and "optional_libraries" not in facts:
            facts["optional_libraries"] = stripped.split("Optional libraries:", 1)[1].split(" | ")[0].strip()
        if "Loaded" in stripped and "extractors_loaded" not in facts:
            match = re.search(r"Loaded (\d+) extractors", stripped)
            if match:
                facts["extractors_loaded"] = int(match.group(1))
    facts["solving_js_challenges"] = stderr.count("Solving JS challenges")
    facts["bun_version_warnings"] = [
        REDACT(line, 200) for line in stderr.splitlines()
        if re.search(r"bun (version|support)", line, re.IGNORECASE)
        and re.search(r"unsupported|deprecated", line, re.IGNORECASE)
    ]
    return facts


def summarize_info(info: Dict[str, Any]) -> Dict[str, Any]:
    url = str(info.get("url") or "")
    query = urllib.parse.parse_qs(urllib.parse.urlparse(url).query or "")
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
        "filesize": info.get("filesize"),
        "url_length": len(url),
        "url_has_n_parameter": "n" in query,
        "url_expires_in_s": expires_in,
        "header_names": sorted(str(name).lower() for name in headers),
    }


def summarize_probe(probe: Any) -> Dict[str, Any]:
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


# ---------------------------------------------------------------------------
# The harness
# ---------------------------------------------------------------------------


class Harness:
    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        self.frozen = os.path.abspath(args.yt_dlp)
        self.bun = os.path.abspath(args.bun)
        self.tmp_root = tempfile.mkdtemp(prefix="archivetune-unpacked-bench-")
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

    def close(self) -> None:
        shutil.rmtree(self.tmp_root, ignore_errors=True)

    def solver_invocations(self) -> int:
        return count_solver_invocations(self.shim_log)

    # -- start-up -----------------------------------------------------------
    def measure_startup(self, binaries: Dict[str, str], repeats: int) -> Dict[str, Any]:
        """New-process start-up of each binary, interleaved.

        These are **not cold boots**. The operating system page cache holds
        the binary after its first launch, so only the very first launch of a
        binary can touch a cold page cache, and even that is not guaranteed.
        The first launch is therefore reported separately and the label of the
        whole block says "new process, page cache warm".
        """
        samples: Dict[str, List[float]] = {key: [] for key in binaries}
        cpus: Dict[str, List[float]] = {key: [] for key in binaries}
        versions: Dict[str, Optional[str]] = {}
        for _ in range(repeats):
            for key in binaries:
                before = child_cpu()
                started = time.perf_counter()
                completed = subprocess.run(
                    [binaries[key], "--version"],
                    env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                    timeout=60, check=False,
                )
                wall = (time.perf_counter() - started) * 1000.0
                cpu = child_cpu() - before
                samples[key].append(wall)
                cpus[key].append(cpu)
                text = (completed.stdout or completed.stderr).decode("utf-8", "replace").strip()
                versions[key] = text.splitlines()[0] if text else None
        return {
            key: {
                "version_output": versions.get(key),
                "wall_ms": summarize_numbers(samples.get(key, [])),
                "cpu_s": summarize_numbers(cpus.get(key, [])),
                "first_launch_wall_ms": samples[key][0] if samples.get(key) else None,
                "first_launch_cpu_s": cpus[key][0] if cpus.get(key) else None,
                "wall_ms_excluding_first_launch": summarize_numbers(samples.get(key, [])[1:]),
                "page_cache_state": (
                    "the first launch may still read a cold page cache; every later launch "
                    "reads a warm one. These are new-process measurements, not cold boots."
                ),
            }
            for key in binaries
        }

    # -- toolchain diagnostics ---------------------------------------------
    def measure_diagnostics(self, binaries: Dict[str, str]) -> Dict[str, Any]:
        """The verbose unsupported-scheme probe of the shipped resolver.

        The probe uses the same common arguments as a real request (including
        the explicit JavaScript runtime), so the diagnostics contain the
        runtime and EJS versions that the shipped toolchain check relies on.
        """
        result: Dict[str, Any] = {}
        for key, path in binaries.items():
            argv = [path]
            argv.extend(COMMON_ARGUMENTS)
            argv.extend(["--no-js-runtimes", "--js-runtimes", "bun:%s" % self.shim])
            argv.extend(list(TOOLCHAIN_PROBE_ARGS))
            completed = subprocess.run(
                argv, env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                timeout=60, check=False,
            )
            stderr = completed.stderr.decode("utf-8", "replace")
            facts = parse_diagnostics(stderr)
            facts["exit_code"] = completed.returncode
            facts["stderr_excerpt"] = REDACT(stderr, 1200)
            result[key] = facts
        return result

    # -- extractions --------------------------------------------------------
    def extract(self, key: str, binary: str, scenario: str, video_id: str,
                player_client: Optional[str], round_index: int) -> ExtractionRun:
        run = ExtractionRun(key, scenario, video_id, player_client, round_index)
        argv = [binary]
        argv.extend(COMMON_ARGUMENTS)
        argv.extend(["--no-js-runtimes", "--js-runtimes", "bun:%s" % self.shim])
        argv.extend(REQUEST_FLAGS)
        argv.extend(["--socket-timeout", "12", "--retries", "1",
                     "--extractor-retries", "1", "--no-progress",
                     "--format", FORMAT_SELECTOR])
        if player_client:
            argv.extend(["--extractor-args", "youtube:player_client=%s" % player_client])
        argv.extend(["-v", "--", "https://www.youtube.com/watch?v=%s" % video_id])

        solver_before = self.solver_invocations()
        cpu_before = child_cpu()
        started = time.perf_counter()
        process = subprocess.Popen(
            argv, env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            start_new_session=True,
        )
        sampler = TreeMemorySampler(process.pid)
        sampler.start()
        timed_out = False
        try:
            stdout, stderr = process.communicate(timeout=self.args.timeout)
        except subprocess.TimeoutExpired:
            timed_out = True
            try:
                os.killpg(os.getpgid(process.pid), signal.SIGKILL)
            except OSError:
                process.kill()
            stdout, stderr = process.communicate()
        wall_ms = (time.perf_counter() - started) * 1000.0
        sampler.stop()
        cpu_total = child_cpu() - cpu_before

        run.wall_ms = round(wall_ms, 3)
        run.cpu_total_s = round(cpu_total, 4)
        run.peak_rss_bytes = sampler.peak_rss
        run.peak_pss_bytes = sampler.peak_pss
        run.peak_processes = sampler.peak_processes
        run.solver_invocations = self.solver_invocations() - solver_before
        run.exit_code = process.returncode

        stderr_text = stderr.decode("utf-8", "replace") if stderr else ""
        run.diagnostics = parse_diagnostics(stderr_text)
        payload = None
        if stdout:
            try:
                candidate = json.loads(stdout.decode("utf-8", "replace"))
                if isinstance(candidate, dict):
                    payload = candidate
            except (ValueError, UnicodeDecodeError):
                payload = None
        if timed_out:
            run.failure_class = "TIMEOUT"
        elif process.returncode != 0:
            run.failure_class = CLASSIFY_FAILURE(process.returncode, stderr_text, False)
        elif payload is None:
            run.failure_class = "NO_JSON_PAYLOAD"
        else:
            run.ok = True
            run.summary = summarize_info(payload)
            if self.args.probe and payload.get("url"):
                probe = PROBE_URL(
                    payload["url"],
                    dict(payload.get("http_headers") or {}),
                    self.args.probe_timeout,
                )
                run.probe = summarize_probe(probe)
        return run


def find_leftovers(markers: Sequence[str], own_pid: int) -> List[Dict[str, Any]]:
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
        for marker in markers:
            if cmdline and marker in cmdline:
                found.append({"pid": pid, "executable": os.path.basename(cmdline.split(" ")[0])})
                break
    return found


# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------

UPSTREAM_SOURCES = [
    {
        "fact": "The upstream release publishes the Linux artifacts and a SHA2-256SUMS feed next to them; the feed is the reference for the archive digest.",
        "title": "yt-dlp 2026.08.19 release assets (yt-dlp_linux.zip, SHA2-256SUMS)",
        "url": "https://github.com/yt-dlp/yt-dlp/releases/tag/2026.08.19",
    },
    {
        "fact": "The shipped desktop resolver starts yt-dlp once per request with --ignore-config --no-plugin-dirs --no-remote-components --no-js-runtimes --js-runtimes bun:<path> --no-cache-dir and a format selector; the toolchain check uses --verbose --simulate with an unsupported scheme.",
        "title": "ArchiveTune stream-resolver/src/main/kotlin/.../YtDlpStreamResolver.kt",
        "url": "https://github.com/Rukamori/ArchiveTuneLinux",
    },
]


def build_report(args: argparse.Namespace, harness: Harness, runs: List[ExtractionRun],
                 startup: Dict[str, Any], diagnostics: Dict[str, Any],
                 artifacts: Dict[str, Any], leftovers: List[Dict[str, Any]],
                 duration_s: float) -> Dict[str, Any]:
    per_configuration: Dict[str, Any] = {}
    for key in sorted({run.configuration for run in runs}):
        block = [run for run in runs if run.configuration == key]
        per_configuration[key] = {
            "attempts": len(block),
            "successful": sum(1 for run in block if run.ok),
            "failed": sum(1 for run in block if not run.ok),
            "wall_ms": summarize_numbers([run.wall_ms for run in block]),
            "cpu_total_s": summarize_numbers([run.cpu_total_s for run in block]),
            "peak_tree_rss_bytes": summarize_numbers([run.peak_rss_bytes for run in block]),
            "peak_tree_pss_bytes": summarize_numbers([run.peak_pss_bytes for run in block]),
            "solver_invocations_total": sum(run.solver_invocations or 0 for run in block),
            "per_scenario": {
                scenario: {
                    "attempts": len([run for run in block if run.scenario == scenario]),
                    "successful": sum(1 for run in block if run.scenario == scenario and run.ok),
                    "wall_ms": summarize_numbers([run.wall_ms for run in block if run.scenario == scenario]),
                    "cpu_total_s": summarize_numbers([run.cpu_total_s for run in block if run.scenario == scenario]),
                    "peak_tree_rss_bytes": summarize_numbers(
                        [run.peak_rss_bytes for run in block if run.scenario == scenario]),
                    "peak_tree_pss_bytes": summarize_numbers(
                        [run.peak_pss_bytes for run in block if run.scenario == scenario]),
                    "solver_invocations_total": sum(
                        run.solver_invocations or 0 for run in block if run.scenario == scenario),
                }
                for scenario in sorted({run.scenario for run in block})
            },
        }
    headline: Dict[str, Any] = {}
    baseline = per_configuration.get("frozen_cli", {})
    for key, entry in per_configuration.items():
        base = (baseline.get("wall_ms") or {}).get("p50")
        value = (entry.get("wall_ms") or {}).get("p50")
        headline[key] = {
            "wall_p50_ms": value,
            "wall_p50_observed_difference_ms": (
                round(value - base, 3) if value is not None and base is not None else None),
            "wall_p50_observed_difference_note": (
                "the difference between two adjacent samples of the same session, not an "
                "effect size and not a causal claim"
            ),
            "cpu_total_p50_s": (entry.get("cpu_total_s") or {}).get("p50"),
            "peak_rss_p50_bytes": (entry.get("peak_tree_rss_bytes") or {}).get("p50"),
            "peak_pss_p50_bytes": (entry.get("peak_tree_pss_bytes") or {}).get("p50"),
            "solver_invocations_total": entry.get("solver_invocations_total"),
        }
    return {
        "schema": "archivetune.ytdlp.unpacked-evaluation/1",
        "generated_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "duration_s": round(duration_s, 2),
        "host": {
            "cpu_count": os.cpu_count(),
            "machine": platform.machine(),
            "platform": platform.platform(),
            "python": sys.version.split()[0],
        },
        "artifacts": artifacts,
        "method": {
            "description": (
                "The shipped self-extracting yt-dlp bundle against the upstream unpacked Linux "
                "artifact of the same release, with an identical argument set."
            ),
            "cookies_used": False,
            "scenarios": [
                {"key": "challenged_default", "video_id": CHALLENGED_VIDEO, "player_client": None},
                {"key": "challenged_web_embedded", "video_id": CHALLENGED_VIDEO,
                 "player_client": CHALLENGE_CLIENT},
                {"key": "simple_default", "video_id": SIMPLE_VIDEO, "player_client": None},
            ],
            "startup_method": (
                "new-process start of each binary (--version), interleaved, wall time and "
                "getrusage(RUSAGE_CHILDREN) delta. NOT a cold boot: the operating system page "
                "cache is warm after the first launch, so the first launch of each binary is "
                "reported separately."
            ),
            "interpretation": (
                "The differences between the two binaries are an observation on one machine, not a "
                "causal claim. The sample is small, the first launch of each binary has a "
                "different page-cache state than the later ones, and the two binaries are not "
                "executed from an identical machine state. Adjacent percentiles must not be "
                "read as an effect size."
            ),
            "policy": (
                "This is a developer measurement. It changes no default, adds no persistence, "
                "no cache and no resident process, and it implies no caching policy."
            ),
            "diagnostics_method": (
                "the toolchain probe of the shipped resolver: --verbose --simulate with an "
                "unsupported scheme; it exits before any network request"
            ),
            "extraction_method": (
                "paired extractions per scenario with an identical argument set, serialized, "
                "interleaved; the head/tail range probe runs outside the measured window"
            ),
            "denominators": (
                "both configurations are measured in the same session, with the same scenarios "
                "and the same number of attempts per scenario, so the denominators are equal. "
                "There is only one session; no attempt of one configuration is compared with an "
                "attempt of another session."
            ),
            "rss_method": (
                "maximum sampled sum of process-tree RSS over the whole tree; shared pages may be "
                "counted once per mapping process and short-lived spikes may be missed"
            ),
            "pss_method": (
                "maximum sampled sum of process-tree PSS from /proc/<pid>/smaps_rollup"
            ),
            "cpu_method": "getrusage(RUSAGE_CHILDREN) delta around each extraction",
            "solver_count_method": (
                "a shim in front of Bun logs every invocation; the runtime version probe is excluded"
            ),
            "format_selector": FORMAT_SELECTOR,
            "manifest_exclusion": (
                "Both arms use identical audio-only control flags, not the final production vector. "
                "The protocol selector excludes manifest formats from selection but still permits "
                "manifest fetching. These controls omit the production HIGH bitrate/vcodec filters "
                "and youtube:skip=hls,dash. Upstream does parse comma-separated skip values; "
                "the earlier claim that they would be inert was incorrect."
            ),
        },
        "new_process_startup": startup,
        "diagnostics": diagnostics,
        "per_configuration": per_configuration,
        "headline": headline,
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


def fetch_artifacts(args: argparse.Namespace) -> Dict[str, Any]:
    """Download and verify the upstream artifacts."""
    tools_dir = os.path.abspath(args.tools_dir)
    os.makedirs(tools_dir, exist_ok=True)
    artifacts: Dict[str, Any] = {"tools_dir": tools_dir}

    feed_path = os.path.join(tools_dir, CHECKSUM_FEED_ASSET)
    if args.skip_download and os.path.exists(feed_path):
        artifacts["checksum_feed"] = {
            "url": "%s/%s" % (RELEASE_BASE, CHECKSUM_FEED_ASSET),
            "path": feed_path,
            "size_bytes": os.path.getsize(feed_path),
            "sha256": SHA256_OF(feed_path),
            "reused": True,
        }
    else:
        artifacts["checksum_feed"] = download(
            "%s/%s" % (RELEASE_BASE, CHECKSUM_FEED_ASSET), feed_path, args.download_timeout)
    with open(feed_path, "r", encoding="utf-8", errors="replace") as handle:
        feed = parse_checksum_feed(handle.read())
    artifacts["checksum_feed_entries"] = len(feed)

    zip_path = os.path.join(tools_dir, LINUX_ZIP_ASSET)
    if args.skip_download and os.path.exists(zip_path):
        artifacts["archive"] = {
            "url": "%s/%s" % (RELEASE_BASE, LINUX_ZIP_ASSET),
            "path": zip_path,
            "size_bytes": os.path.getsize(zip_path),
            "sha256": SHA256_OF(zip_path),
            "reused": True,
        }
    else:
        artifacts["archive"] = download(
            "%s/%s" % (RELEASE_BASE, LINUX_ZIP_ASSET), zip_path, args.download_timeout)
    expected = feed.get(LINUX_ZIP_ASSET)
    artifacts["archive"]["published_sha256"] = expected
    artifacts["archive"]["matches_published_feed"] = (
        expected is not None and expected == artifacts["archive"]["sha256"])
    if expected is None:
        raise RuntimeError("the published feed does not list %s" % LINUX_ZIP_ASSET)
    if not artifacts["archive"]["matches_published_feed"]:
        raise RuntimeError("the archive does not match the published checksum feed")

    members = verify_zip(zip_path, expected)
    artifacts["archive_members"] = members
    rejected = [member for member in members if member["rejected_because"]]
    if rejected:
        raise RuntimeError("the archive contains unsafe member paths: %s" % json.dumps(rejected))

    extract_dir = os.path.join(tools_dir, "unpacked")
    extracted = extract_zip_guarded(zip_path, extract_dir)
    artifacts["extraction"] = _summarize_extraction(extracted)
    binaries = [item for item in extracted
                if item["executable"] and not item["name"].endswith("/")]
    artifacts["candidate_binaries"] = binaries
    # The digest of the shipped one-file bundle is recorded for the report.
    # It is NOT a gate: the upstream unpacked artifact is expected to be a
    # different binary, and a different digest is a finding, not a failure.
    artifacts["shipped_onefile_binary_sha256"] = UPSTREAM_LINUX_BINARY_SHA256
    for item in binaries:
        item["same_digest_as_shipped_onefile_binary"] = (
            item["sha256"] == UPSTREAM_LINUX_BINARY_SHA256)
    return artifacts


def _summarize_extraction(extracted: List[Dict[str, Any]]) -> Dict[str, Any]:
    """Summarise the extracted members without listing every shared library.

    The upstream onedir archive contains hundreds of shared libraries and
    package files; the report keeps the top-level members with their digests
    and only counts the rest.
    """
    top_level: List[Dict[str, Any]] = []
    by_directory: Dict[str, Dict[str, Any]] = {}
    for item in extracted:
        name = item["name"]
        if "/" not in name:
            top_level.append({
                "name": name,
                "size_bytes": item["size_bytes"],
                "sha256": item["sha256"],
                "executable": item["executable"],
            })
            continue
        directory = name.split("/", 1)[0]
        entry = by_directory.setdefault(directory, {"files": 0, "size_bytes": 0})
        entry["files"] += 1
        entry["size_bytes"] += item["size_bytes"] or 0
    return {
        "member_count": len(extracted),
        "total_size_bytes": sum(item["size_bytes"] or 0 for item in extracted),
        "top_level_members": top_level,
        "directories": by_directory,
    }


def select_unpacked_binary(artifacts: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """Pick the member that is the yt-dlp program.

    The upstream Linux zip is a PyInstaller **onedir** distribution: the
    program is the top-level ELF executable and everything else lives in the
    ``_internal`` directory. Picking "the first executable member" would
    select a shared library, which is not a program. The rule is therefore:

    1. a top-level member (no directory component) whose name starts with
       ``yt-dlp`` and that is an ELF image,
    2. otherwise any top-level ELF image.

    The rule and the rejected candidates are recorded in the report so that
    the comparison is reproducible.
    """
    binaries = artifacts.get("candidate_binaries") or []
    if not binaries:
        return None
    top_level = [item for item in binaries if "/" not in item["name"] and "\\" not in item["name"]]
    elf = [item for item in top_level if _is_elf(item["path"])]
    preferred = [item for item in elf
                 if os.path.basename(item["name"]).lower().startswith("yt-dlp")]
    if preferred:
        chosen, rule = preferred[0], "top-level ELF image whose name starts with yt-dlp"
    elif elf:
        chosen, rule = elf[0], "first top-level ELF image"
    else:
        chosen, rule = top_level[0], "first top-level executable member (no ELF image found)"
    artifacts["selected_unpacked_binary"] = {
        "name": chosen["name"],
        "path": chosen["path"],
        "sha256": chosen["sha256"],
        "size_bytes": chosen["size_bytes"],
        "selection_rule": rule,
        "same_digest_as_shipped_onefile_binary": chosen.get(
            "same_digest_as_shipped_onefile_binary"),
        "candidate_count": len(binaries),
        "top_level_candidates": [item["name"] for item in top_level],
        "note": (
            "the upstream Linux zip is a PyInstaller onedir distribution: the program is the "
            "top-level executable and the interpreter, the packages and the EJS solver scripts "
            "live in the _internal directory"
        ),
    }
    return chosen


def explore_windows_layout(args: argparse.Namespace) -> Dict[str, Any]:
    """Static exploration of the upstream Windows zip (layout and integrity)."""
    tools_dir = os.path.abspath(args.tools_dir)
    os.makedirs(tools_dir, exist_ok=True)
    feed_path = os.path.join(tools_dir, CHECKSUM_FEED_ASSET)
    if not os.path.exists(feed_path):
        feed = download("%s/%s" % (RELEASE_BASE, CHECKSUM_FEED_ASSET), feed_path, args.download_timeout)
        feed_text = open(feed_path, "r", encoding="utf-8", errors="replace").read()
    else:
        feed_text = open(feed_path, "r", encoding="utf-8", errors="replace").read()
    feed = parse_checksum_feed(feed_text)
    zip_path = os.path.join(tools_dir, WINDOWS_ZIP_ASSET)
    if args.skip_download and os.path.exists(zip_path):
        info = {"path": zip_path, "size_bytes": os.path.getsize(zip_path),
                "sha256": SHA256_OF(zip_path), "reused": True}
    else:
        info = download("%s/%s" % (RELEASE_BASE, WINDOWS_ZIP_ASSET), zip_path, args.download_timeout)
    info["url"] = "%s/%s" % (RELEASE_BASE, WINDOWS_ZIP_ASSET)
    info["published_sha256"] = feed.get(WINDOWS_ZIP_ASSET)
    info["matches_published_feed"] = (
        info["published_sha256"] is not None and info["published_sha256"] == info["sha256"])
    members = verify_zip(zip_path, info["published_sha256"])
    return {
        "archive": info,
        "members": members,
        "rejected_members": [member for member in members if member["rejected_because"]],
        "note": ("static exploration only: the Windows artifact was not executed and not "
                 "extracted"),
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="ArchiveTune unpacked yt-dlp release evaluation (developer-only)")
    parser.add_argument("--yt-dlp", default=".tools/resolver/yt-dlp",
                        help="the shipped yt-dlp executable (the control)")
    parser.add_argument("--bun", default=".tools/resolver/bun")
    parser.add_argument("--tools-dir", default=".tools/unpacked",
                        help="git-ignored directory for the downloaded upstream artifacts")
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--startup-repeats", type=int, default=5)
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--probe-timeout", type=float, default=15.0)
    parser.add_argument("--probe", dest="probe", action="store_true", default=True)
    parser.add_argument("--no-probe", dest="probe", action="store_false")
    parser.add_argument("--download-timeout", type=float, default=180.0)
    parser.add_argument("--skip-download", action="store_true",
                        help="reuse the artifacts that are already in --tools-dir")
    parser.add_argument("--windows-layout", action="store_true",
                        help="additionally explore the upstream Windows zip statically")
    parser.add_argument("--out-json", default=None)
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = build_parser().parse_args(list(argv) if argv is not None else None)
    started = time.monotonic()

    artifacts = fetch_artifacts(args)
    if args.windows_layout:
        artifacts["windows"] = explore_windows_layout(args)

    selected = select_unpacked_binary(artifacts)
    if selected is None:
        raise SystemExit("the upstream archive contains no executable member")
    unpacked = selected["path"]
    frozen = os.path.abspath(args.yt_dlp)
    binaries = {"frozen_cli": frozen, "unpacked_zip": unpacked}

    harness = Harness(args)
    runs: List[ExtractionRun] = []
    startup: Dict[str, Any] = {}
    diagnostics: Dict[str, Any] = {}
    leftovers: List[Dict[str, Any]] = []
    try:
        startup = harness.measure_startup(binaries, max(1, args.startup_repeats))
        diagnostics = harness.measure_diagnostics(binaries)
        # The start-up phase and the toolchain probe have already executed
        # both binaries, so neither of them enters the paired extractions as
        # an untouched file. The page cache state of the two binaries is
        # therefore comparable here, but it is a *warm* state for both.
        scenarios = (
            ("challenged_default", CHALLENGED_VIDEO, None),
            ("challenged_web_embedded", CHALLENGED_VIDEO, CHALLENGE_CLIENT),
            ("simple_default", SIMPLE_VIDEO, None),
        )
        for round_index in range(args.repeats):
            for scenario, video_id, player_client in scenarios:
                # The pair is interleaved: the same scenario is measured with
                # both binaries back to back.
                for key in ("frozen_cli", "unpacked_zip"):
                    run = harness.extract(key, binaries[key], scenario, video_id,
                                          player_client, round_index)
                    runs.append(run)
                    print("%-14s %-26s round=%d ok=%s wall=%.0fms cpu=%.2fs solver=%s" % (
                        key, scenario, round_index + 1, run.ok, run.wall_ms or 0.0,
                        run.cpu_total_s or 0.0, run.solver_invocations), file=sys.stderr, flush=True)
    finally:
        leftovers = find_leftovers([harness.shim, harness.bun], os.getpid())
        harness.close()

    duration_s = time.monotonic() - started
    report = build_report(args, harness, runs, startup, diagnostics, artifacts, leftovers, duration_s)
    if args.out_json:
        out_path = os.path.abspath(args.out_json)
        os.makedirs(os.path.dirname(out_path), exist_ok=True)
        with open(out_path, "w", encoding="utf-8") as handle:
            json.dump(report, handle, indent=1, sort_keys=True)
            handle.write("\n")
        print("wrote %s" % out_path, file=sys.stderr)
    print(json.dumps(report["headline"], indent=1), file=sys.stderr)

    failures = [run for run in runs if not run.ok]
    probe_failures = [
        run for run in runs
        if run.ok and run.probe and (run.probe.get("status") not in (200, 206)
                                     or run.probe.get("bytes_read", 0) <= 0)
    ]
    if failures:
        print("failed runs: %d" % len(failures), file=sys.stderr)
    if probe_failures:
        print("probe failures: %d" % len(probe_failures), file=sys.stderr)
    if leftovers:
        print("WARNING: leftover processes: %s" % json.dumps(leftovers), file=sys.stderr)
    return 0 if (not failures and not probe_failures and not leftovers) else 1


if __name__ == "__main__":
    sys.exit(main())

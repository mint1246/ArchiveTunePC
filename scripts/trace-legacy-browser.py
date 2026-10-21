#!/usr/bin/env python3
"""Trace the process fan-out of ArchiveTune's managed BotGuard Chromium helper.

The desktop ``ChromiumBotGuardPoTokenProvider`` starts one short-lived headless
Chromium/Brave instance per token-cache miss.  All of Chromium's children share
the executable name (``brave``), so a process-name count cannot say which
process is the browser, a zygote, a renderer, a utility service, or crashpad.
This tool observes the fan-out without recording argv, URLs, cookies, profile
paths, or environment values.

Two modes:

``--app-pid PID``
    Read-only attach.  Finds only managed roots whose ``--user-data-dir`` has
    the ``archivetune-potoken-`` prefix and that live in the app's session or
    process group (or descend from it), then follows only those roots'
    descendants.  A user's unrelated Brave browser is never selected because
    it is outside the app session and has no matching managed profile.

``--launch``
    Controlled reproduction of ``botGuardBrowserCommand`` using a new private
    profile under ``--profile-root`` and ``about:blank``.  No cookie, session,
    token, or account value is passed.  This measures the standard fan-out of
    one invocation.  It does not execute BotGuard JavaScript; the provider's
    actual call chain is analysed separately.

``--self-test``
    Parses synthetic records only.  No /proc scan, no browser launch, no
    network.  Proves role mapping and redaction.

Output is a small JSON document with role names, ancestry edges, lifetimes and
sampled RSS/PSS.  Only these role flags are read from argv: ``--type=``,
``--utility-sub-type=``, ``--no-zygote-sandbox``.  All argv tokens are
discarded immediately after classification.
"""

from __future__ import annotations

import argparse
import json
import os
import random
import shutil
import signal
import string
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

CLOCK_TICKS = os.sysconf("SC_CLK_TCK")
PAGE_SIZE = os.sysconf("SC_PAGE_SIZE")

MANAGED_PROFILE_PREFIX = "archivetune-potoken-"
DEFAULT_PROFILE_ROOT = Path("/tmp/opencode")
DEFAULT_BROWSER = Path("/usr/bin/brave")
DEFAULT_SAMPLE_INTERVAL_MS = 20
DEFAULT_PSS_INTERVAL_MS = 100
DEFAULT_TERM_GRACE_MS = 2000

# Chromium's known utility service names.  Unknown values collapse to "other"
# so an unexpected or user-controlled string can never reach the report.
UTILITY_SUBTYPES = {
    "network.mojom.networkservice": "network",
    "storage.mojom.storageservice": "storage",
    "audio.mojom.audioservice": "audio",
    "data_decoder.mojom.datadecoderservice": "data_decoder",
    "video_capture.mojom.videocaptureservice": "video_capture",
    "video_effects.mojom.videoeffectsservice": "video_effects",
    "tracing.mojom.tracingservice": "tracing",
    "proxy_resolver.mojom.proxyresolverservice": "proxy_resolver",
    "network.mojom.proxyconfigservice": "proxy_config",
}

SAFE_TYPE_VALUES = {
    "browser",
    "crashpad-handler",
    "gpu-process",
    "nacl-loader",
    "pdf",
    "ppapi",
    "renderer",
    "service",
    "utility",
    "zygote",
}


@dataclass(frozen=True)
class Role:
    kind: str
    detail: str | None = None
    zygote_sandbox: str | None = None

    def label(self) -> str:
        if self.kind == "utility" and self.detail:
            return f"utility:{self.detail}"
        if self.kind == "other" and self.detail:
            return f"other:{self.detail}"
        return self.kind


@dataclass
class ProcRow:
    pid: int
    ppid: int
    pgrp: int
    session: int
    starttime: int
    threads: int
    rss_bytes: int
    cpu_ticks: int
    state: str = "?"


@dataclass
class TrackedProcess:
    pid: int
    starttime: int
    role: Role
    first_wall_ms: int
    last_wall_ms: int
    parent_role: str
    ppid: int = 0
    reparented: bool = False
    max_rss_bytes: int = 0
    max_pss_bytes: int = 0
    max_threads: int = 0
    in_app_session: bool = False
    in_app_pgrp: bool = False

    def record(self) -> dict[str, Any]:
        result: dict[str, Any] = {
            "pid": self.pid,
            "role": self.role.label(),
            "parent_role": self.parent_role,
            "first_wall_ms": self.first_wall_ms,
            "last_wall_ms": self.last_wall_ms,
            "lifetime_ms": max(0, self.last_wall_ms - self.first_wall_ms),
            "max_rss_mib": round(self.max_rss_bytes / (1024 * 1024), 2),
            "max_threads": self.max_threads,
            "in_app_session": self.in_app_session,
            "in_app_pgrp": self.in_app_pgrp,
            "reparented": self.reparented,
        }
        if self.max_pss_bytes:
            result["max_pss_mib"] = round(self.max_pss_bytes / (1024 * 1024), 2)
        return result


# ---------------------------------------------------------------------------
# Safe parsing
# ---------------------------------------------------------------------------


def decode_argv(raw: bytes) -> list[str]:
    """Split a NUL-delimited cmdline.  The result is never stored or emitted."""
    return [token.decode("utf-8", "replace") for token in raw.split(b"\0") if token]


def extract_profile_path(argv: list[str]) -> str | None:
    for argument in flag_tokens(argv):
        if argument.startswith("--user-data-dir="):
            return argument.split("=", 1)[1]
    return None


def flag_tokens(argv: list[str]) -> list[str]:
    """Normalize Chromium's space-joined process title back into flag tokens.

    Chromium rewrites argv for its process title on Linux; a helper's
    /proc/<pid>/cmdline can arrive as one space-separated string instead of
    NUL-separated arguments.  Only allowlisted role flags are read from the
    result, and the raw string is discarded.
    """
    tokens: list[str] = []
    for token in argv:
        tokens.extend(token.split())
    return tokens


def is_managed_profile(path: str | None) -> bool:
    if not path:
        return False
    try:
        return Path(path).name.startswith(MANAGED_PROFILE_PREFIX)
    except (OSError, ValueError):
        return False


def parse_role(exe_basename: str, argv: list[str]) -> Role:
    """Map one process to a Chromium role without retaining any argv value."""
    exe = (exe_basename or "").lower()
    if "crashpad_handler" in exe:
        return Role("crashpad")

    type_value: str | None = None
    utility_subtype: str | None = None
    no_zygote_sandbox = False
    for argument in flag_tokens(argv):
        if argument.startswith("--type="):
            type_value = argument.split("=", 1)[1].strip().lower()
        elif argument.startswith("--utility-sub-type="):
            utility_subtype = argument.split("=", 1)[1].strip().lower()
        elif argument == "--no-zygote-sandbox":
            no_zygote_sandbox = True

    if type_value is None:
        return Role("browser")
    if type_value == "zygote":
        return Role(
            "zygote",
            zygote_sandbox="none" if no_zygote_sandbox else "default",
        )
    if type_value == "renderer":
        return Role("renderer")
    if type_value == "gpu-process":
        return Role("gpu")
    if type_value == "utility":
        mapped = UTILITY_SUBTYPES.get(utility_subtype or "", "other")
        return Role("utility", detail=mapped)
    safe = type_value if type_value in SAFE_TYPE_VALUES else "other"
    return Role("other", detail=safe)


def observed_flags(argv: list[str]) -> dict[str, bool]:
    """Only production-relevant booleans; no argv token is copied out."""
    values: dict[str, bool] = {
        "headless": False,
        "disable_gpu": False,
        "disable_dev_shm_usage": False,
        "password_store_basic": False,
        "remote_debugging_port_zero": False,
        "no_sandbox": False,
        "single_process": False,
        "no_zygote": False,
    }
    for argument in flag_tokens(argv):
        if argument.startswith("--headless"):
            values["headless"] = True
        elif argument == "--disable-gpu":
            values["disable_gpu"] = True
        elif argument == "--disable-dev-shm-usage":
            values["disable_dev_shm_usage"] = True
        elif argument == "--password-store=basic":
            values["password_store_basic"] = True
        elif argument == "--remote-debugging-port=0":
            values["remote_debugging_port_zero"] = True
        elif argument == "--no-sandbox":
            values["no_sandbox"] = True
        elif argument == "--single-process":
            values["single_process"] = True
        elif argument == "--no-zygote":
            values["no_zygote"] = True
    return values


# ---------------------------------------------------------------------------
# /proc reading (only numeric/short fields)
# ---------------------------------------------------------------------------


def read_process_table() -> dict[int, ProcRow]:
    table: dict[int, ProcRow] = {}
    for entry in Path("/proc").iterdir():
        if not entry.name.isdigit():
            continue
        pid = int(entry.name)
        try:
            data = (entry / "stat").read_text(encoding="utf-8", errors="replace")
            left = data.find("(")
            right = data.rfind(")")
            if left < 0 or right <= left:
                continue
            fields = data[right + 2 :].split()
            table[pid] = ProcRow(
                pid=pid,
                ppid=int(fields[1]),
                pgrp=int(fields[2]),
                session=int(fields[3]),
                starttime=int(fields[19]),
                threads=int(fields[17]),
                rss_bytes=max(0, int(fields[21])) * PAGE_SIZE,
                cpu_ticks=int(fields[11]) + int(fields[12]),
                state=fields[0],
            )
        except (OSError, ValueError, IndexError):
            continue
    return table


def read_cmdline(pid: int) -> list[str]:
    try:
        return decode_argv(Path(f"/proc/{pid}/cmdline").read_bytes())
    except OSError:
        return []


def read_exe_basename(pid: int) -> str:
    try:
        target = os.readlink(f"/proc/{pid}/exe")
        if target.endswith(" (deleted)"):
            target = target[: -len(" (deleted)")]
        return Path(target).name
    except OSError:
        return ""


def is_browser_executable(basename: str) -> bool:
    lowered = (basename or "").lower()
    return any(
        token in lowered
        for token in ("brave", "chromium", "chrome", "headless_shell", "msedge")
    ) or "crashpad_handler" in lowered


def read_pss_bytes(pid: int) -> int | None:
    try:
        for line in Path(f"/proc/{pid}/smaps_rollup").read_text(
            encoding="ascii", errors="replace"
        ).splitlines():
            if line.startswith("Pss:"):
                return int(line.split()[1]) * 1024
    except (OSError, ValueError, IndexError):
        return None
    return None


def is_descendant(pid: int, ancestor: int, table: dict[int, ProcRow]) -> bool:
    seen: set[int] = set()
    current = pid
    while current and current not in seen:
        seen.add(current)
        row = table.get(current)
        if row is None:
            return False
        if row.ppid == ancestor:
            return True
        current = row.ppid
    return False


# ---------------------------------------------------------------------------
# Tracking
# ---------------------------------------------------------------------------


def discover_managed_roots(
    app_pid: int,
    table: dict[int, ProcRow],
    prefix: str,
) -> dict[int, tuple[int, str]]:
    """Return root pid -> (starttime, profile path) for managed BotGuard browsers."""
    roots: dict[int, tuple[int, str]] = {}
    for pid, row in table.items():
        if pid == app_pid:
            continue
        in_scope = (
            row.session == app_pid
            or row.pgrp == app_pid
            or is_descendant(pid, app_pid, table)
        )
        if not in_scope:
            continue
        argv = read_cmdline(pid)
        profile = extract_profile_path(argv)
        if not is_managed_profile(profile):
            continue
        if not any(argument.startswith("--headless") for argument in flag_tokens(argv)):
            continue
        # A managed profile alone is not enough; the profile basename must carry
        # the exact provider prefix, which the user's normal browser never has.
        if not Path(profile or "").name.startswith(prefix):
            continue
        roots[pid] = (row.starttime, profile or "")
    return roots


def select_tracked(
    table: dict[int, ProcRow],
    roots: dict[int, tuple[int, str]],
    previous: dict[int, int],
    profile_matches: dict[int, str],
) -> set[int]:
    """Select only browser-lineage processes, never the whole app tree."""
    tracked: set[int] = set()
    changed = True
    # Root processes (live, with matching starttime).
    for pid, (starttime, _profile) in roots.items():
        row = table.get(pid)
        if row is not None and row.starttime == starttime:
            tracked.add(pid)
    # Previously observed lineage members survive reparenting.
    for pid, starttime in previous.items():
        row = table.get(pid)
        if row is not None and row.starttime == starttime:
            tracked.add(pid)
    # Exact managed-profile matches catch helpers that reparented before a sample.
    for pid, _profile in profile_matches.items():
        row = table.get(pid)
        if row is not None:
            tracked.add(pid)
    # Descendant closure of the selected roots/lineage only.
    while changed:
        changed = False
        for pid, row in table.items():
            if pid not in tracked and row.ppid in tracked:
                tracked.add(pid)
                changed = True
    return {
        pid
        for pid in tracked
        if pid in table and table[pid].state != "Z"
    }


# ---------------------------------------------------------------------------
# Launch mode
# ---------------------------------------------------------------------------


def bot_guard_like_command(browser: Path, profile: Path) -> list[str]:
    """Mirror botGuardBrowserCommand for a non-root Linux account.

    The profile path differs by construction, and no session/cookie value is
    supplied.  All other switches match the provider.
    """
    return [
        str(browser),
        "--headless=new",
        f"--user-data-dir={profile}",
        "--profile-directory=Default",
        "--remote-debugging-port=0",
        "--remote-debugging-address=127.0.0.1",
        "--disable-sync",
        "--disable-background-mode",
        "--no-first-run",
        "--no-default-browser-check",
        "--disable-features=EnableBoundSessionCredentials",
        "--disable-dev-shm-usage",
        "--disable-gpu",
        "--password-store=basic",
        "--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3",
        "about:blank",
    ]


def make_profile(root: Path) -> Path:
    root.mkdir(parents=True, exist_ok=True)
    suffix = "".join(random.choice(string.ascii_lowercase + string.digits) for _ in range(10))
    profile = root / f"{MANAGED_PROFILE_PREFIX}{suffix}"
    profile.mkdir(mode=0o700)
    return profile


def signal_tracked(pids: set[int], sig: int) -> None:
    for pid in pids:
        try:
            os.kill(pid, sig)
        except (ProcessLookupError, PermissionError):
            continue


def terminate_and_drain(
    tracker: "BrowserTracker",
    grace_ms: int,
) -> dict[str, Any]:
    """SIGTERM then SIGKILL the tracked lineage, mirroring stopManagedBrowser."""
    started = time.monotonic()
    signal_tracked(tracker.live_pids(), signal.SIGTERM)
    deadline = time.monotonic() + grace_ms / 1000
    while time.monotonic() < deadline and tracker.sample_once():
        time.sleep(0.05)
    if tracker.live_pids():
        signal_tracked(tracker.live_pids(), signal.SIGKILL)
        drain_deadline = time.monotonic() + 2.0
        while time.monotonic() < drain_deadline and tracker.sample_once():
            time.sleep(0.05)
    return {
        "teardown_ms": round((time.monotonic() - started) * 1000),
        "leftovers": len(tracker.live_pids()),
    }


# ---------------------------------------------------------------------------
# Tracker
# ---------------------------------------------------------------------------


class BrowserTracker:
    def __init__(
        self,
        *,
        app_pid: int | None,
        roots: dict[int, tuple[int, str]],
        sample_interval_ms: int,
        pss_interval_ms: int,
    ) -> None:
        self.app_pid = app_pid
        self.roots = roots
        self.sample_interval_ms = max(5, sample_interval_ms)
        self.pss_every = max(1, round(pss_interval_ms / self.sample_interval_ms))
        self.started_wall_ms = int(time.time() * 1000)
        self.processes: dict[int, TrackedProcess] = {}
        self.max_simultaneous = 0
        self.samples = 0
        self.role_edges: dict[tuple[str, str], int] = {}
        self._profile_matches: dict[int, str] = {}
        self._previous: dict[int, int] = {}

    def live_pids(self) -> set[int]:
        table = read_process_table()
        live: set[int] = set()
        for pid, tracked in self.processes.items():
            row = table.get(pid)
            if row is not None and row.starttime == tracked.starttime and row.state != "Z":
                live.add(pid)
        return live

    def sample_once(self, *, with_pss: bool = False) -> int:
        self.samples += 1
        table = read_process_table()
        now = int(time.time() * 1000)

        # Discover new managed roots on every sample (sequential mints).
        if self.app_pid is not None:
            for pid, (starttime, profile) in discover_managed_roots(
                self.app_pid, table, MANAGED_PROFILE_PREFIX
            ).items():
                self.roots.setdefault(pid, (starttime, profile))

        # Refresh exact profile matches for already-known roots only.  Restrict the
        # scan to the app/root session so the tracer does not read every cmdline on
        # the machine every 20 ms.
        profiles = {profile for _starttime, profile in self.roots.values() if profile}
        scope_ids: set[int] = set()
        if self.app_pid is not None:
            scope_ids.add(self.app_pid)
        for root_pid, (starttime, _profile) in self.roots.items():
            root_row = table.get(root_pid)
            if root_row is not None and root_row.starttime == starttime:
                scope_ids.add(root_row.session)
                scope_ids.add(root_row.pgrp)
        for pid, row in table.items():
            if pid in self.roots or not profiles:
                continue
            if scope_ids and row.session not in scope_ids and row.pgrp not in scope_ids:
                continue
            argv = read_cmdline(pid)
            profile = extract_profile_path(argv)
            if profile and profile in profiles:
                self._profile_matches[pid] = profile

        tracked = select_tracked(
            table,
            self.roots,
            self._previous,
            self._profile_matches,
        )
        self.max_simultaneous = max(self.max_simultaneous, len(tracked))

        for pid in tracked:
            row = table.get(pid)
            if row is None:
                continue
            argv = read_cmdline(pid)
            role = parse_role(read_exe_basename(pid), argv)
            parent_role = "external"
            parent = table.get(row.ppid)
            if parent is not None:
                parent_exe = read_exe_basename(parent.pid)
                if is_browser_executable(parent_exe):
                    parent_role = parse_role(parent_exe, read_cmdline(parent.pid)).label()
            existing = self.processes.get(pid)
            if existing is None or existing.starttime != row.starttime:
                existing = TrackedProcess(
                    pid=pid,
                    starttime=row.starttime,
                    role=role,
                    first_wall_ms=now,
                    last_wall_ms=now,
                    parent_role=parent_role,
                    ppid=row.ppid,
                    in_app_session=(
                        self.app_pid is not None and row.session == self.app_pid
                    ),
                    in_app_pgrp=(
                        self.app_pid is not None and row.pgrp == self.app_pid
                    ),
                )
                self.processes[pid] = existing
                key = (parent_role, role.label())
                self.role_edges[key] = self.role_edges.get(key, 0) + 1
            elif existing.role.label() == "browser" and role.label() != "browser":
                # A helper can be sampled just after fork and before its exec/argv
                # rewrite.  Keep refining until it exposes a specific role.
                existing.role = role
            existing.ppid = row.ppid
            existing.last_wall_ms = now
            existing.max_rss_bytes = max(existing.max_rss_bytes, row.rss_bytes)
            existing.max_threads = max(existing.max_threads, row.threads)
            if row.ppid not in tracked and pid not in self.roots:
                existing.reparented = True
            if with_pss:
                pss = read_pss_bytes(pid)
                if pss is not None:
                    existing.max_pss_bytes = max(existing.max_pss_bytes, pss)
            self._previous[pid] = row.starttime

        return len(tracked)

    def snapshot(self) -> dict[str, Any]:
        now = int(time.time() * 1000)
        roles: dict[str, int] = {}
        edges: dict[tuple[str, str], int] = {}
        for process in self.processes.values():
            label = process.role.label()
            roles[label] = roles.get(label, 0) + 1
            parent = self.processes.get(process.ppid)
            if parent is not None and parent.pid != process.pid:
                process.parent_role = parent.role.label()
            key = (process.parent_role, label)
            edges[key] = edges.get(key, 0) + 1
        return {
            "schema": 1,
            "tool": "scripts/trace-legacy-browser.py",
            "mode": "attach" if self.app_pid is not None else "launch",
            "started_wall_ms": self.started_wall_ms,
            "ended_wall_ms": now,
            "sampling": {
                "interval_ms": self.sample_interval_ms,
                "pss_interval_ms": self.pss_every * self.sample_interval_ms,
                "samples": self.samples,
            },
            "scope": {
                "app_pid": self.app_pid,
                "managed_profile_prefix": MANAGED_PROFILE_PREFIX,
                "root_count": len(self.roots),
                "attribution": [
                    "root carries a managed profile flag with the archivetune-potoken- prefix",
                    "root is in the app session/process group or descends from it",
                    "helpers are descendant closure or exact managed-profile matches",
                    "no same-executable process outside that lineage is included",
                ],
            },
            "max_simultaneous_processes": self.max_simultaneous,
            "distinct_processes": len(self.processes),
            "roles": roles,
            "role_edges": [
                {"parent": parent, "child": child, "count": count}
                for (parent, child), count in sorted(edges.items())
            ],
            "processes": [
                process.record()
                for process in sorted(
                    self.processes.values(), key=lambda item: item.first_wall_ms
                )
            ],
            "redaction": {
                "argv_emitted": False,
                "urls_emitted": False,
                "cookies_emitted": False,
                "profile_path_emitted": False,
                "environment_emitted": False,
            },
        }


def aggregate_launches(launches: list[dict[str, Any]]) -> dict[str, Any]:
    """Aggregate controlled launches without carrying argv or profile paths."""
    completed = [item for item in launches if "error" not in item]
    role_counts = [dict(item.get("roles", {})) for item in completed]
    peaks = [int(item.get("max_simultaneous_processes", 0)) for item in completed]
    all_roles = sorted({role for counts in role_counts for role in counts})
    ranges: dict[str, list[int]] = {}
    for role in all_roles:
        values = [counts.get(role, 0) for counts in role_counts]
        ranges[role] = [min(values), max(values)]
    always = [
        role
        for role in all_roles
        if role_counts and all(counts.get(role, 0) > 0 for counts in role_counts)
    ]
    optional = [role for role in all_roles if role not in always]
    edges: dict[tuple[str, str], int] = {}
    for item in completed:
        for edge in item.get("role_edges", []):
            key = (str(edge.get("parent")), str(edge.get("child")))
            edges[key] = edges.get(key, 0) + int(edge.get("count", 0))
    stable_roles = bool(role_counts) and all(
        counts == role_counts[0] for counts in role_counts
    )
    return {
        "launches_requested": len(launches),
        "launches_completed": len(completed),
        "peaks": peaks,
        "peak_min": min(peaks) if peaks else None,
        "peak_max": max(peaks) if peaks else None,
        "stable_peak": bool(peaks) and len(set(peaks)) == 1,
        "role_counts_per_launch": role_counts,
        "role_count_ranges": ranges,
        "stable_role_counts": stable_roles,
        "roles_in_every_launch": always,
        "roles_optional": optional,
        "role_edges": [
            {"parent": parent, "child": child, "count": count}
            for (parent, child), count in sorted(edges.items())
        ],
        "gpu_seen": "gpu" in all_roles,
        "crashpad_seen": "crashpad" in all_roles,
        "leftovers_total": sum(
            int(item.get("lifecycle", {}).get("leftovers", 0)) for item in completed
        ),
        "all_profiles_removed": bool(completed)
        and all(bool(item.get("launch", {}).get("profile_removed")) for item in completed),
        "all_devtools_ready": bool(completed)
        and all(bool(item.get("launch", {}).get("devtools_ready")) for item in completed),
    }


# ---------------------------------------------------------------------------
# Self-test
# ---------------------------------------------------------------------------


def _safe_dump(value: Any) -> str:
    return json.dumps(value, sort_keys=True)


def self_test() -> int:
    secret_profile = "/tmp/secret/archivetune-potoken-abcdef"
    root_argv = bot_guard_like_command(Path("/usr/bin/brave"), Path(secret_profile))
    assert parse_role("brave", root_argv).label() == "browser"
    assert observed_flags(root_argv)["headless"] is True
    assert observed_flags(root_argv)["disable_gpu"] is True
    assert observed_flags(root_argv)["remote_debugging_port_zero"] is True

    assert (
        parse_role(
            "brave",
            ["brave", "--type=utility", "--utility-sub-type=network.mojom.NetworkService"],
        ).label()
        == "utility:network"
    )
    assert parse_role("brave", ["brave", "--type=renderer"]).label() == "renderer"
    # Chromium can expose a helper argv as one space-joined process title.
    assert (
        parse_role(
            "brave",
            [
                "brave --headless=new --type=renderer "
                "--user-data-dir=/tmp/opencode/archivetune-potoken-selftest"
            ],
        ).label()
        == "renderer"
    )
    assert (
        parse_role("brave", ["brave", "--type=zygote", "--no-zygote-sandbox"]).label()
        == "zygote"
    )
    assert (
        parse_role("chrome_crashpad_handler", ["chrome_crashpad_handler"]).label()
        == "crashpad"
    )
    assert (
        parse_role("brave", ["brave", "--type=utility", "--utility-sub-type=secret.Service"])
        .label()
        == "utility:other"
    )
    assert is_managed_profile(secret_profile) is True
    assert is_managed_profile("/home/user/.config/BraveSoftware/Brave-Browser") is False

    # Scope: a same-executable user browser in another session must be excluded.
    table = {
        100: ProcRow(100, 1, 100, 100, 1, 1, 0, 0),
        200: ProcRow(200, 100, 100, 100, 2, 1, 0, 0),
        201: ProcRow(201, 200, 100, 100, 3, 1, 0, 0),
        300: ProcRow(300, 1, 300, 300, 4, 1, 0, 0),
    }
    roots = {200: (2, secret_profile)}
    selected = select_tracked(table, roots, {}, {})
    assert selected == {200, 201}, selected

    tracker = BrowserTracker(
        app_pid=100,
        roots=roots,
        sample_interval_ms=20,
        pss_interval_ms=100,
    )
    tracker.processes[200] = TrackedProcess(
        pid=200,
        starttime=2,
        role=Role("browser"),
        first_wall_ms=1,
        last_wall_ms=2,
        parent_role="external",
        in_app_session=True,
        in_app_pgrp=True,
    )
    tracker.processes[201] = TrackedProcess(
        pid=201,
        starttime=3,
        role=Role("utility", detail="network"),
        first_wall_ms=1,
        last_wall_ms=2,
        parent_role="browser",
        in_app_session=True,
        in_app_pgrp=True,
    )
    rendered = _safe_dump(tracker.snapshot())
    for forbidden in (secret_profile, "--user-data-dir", "http://", "https://", "SAPISID"):
        assert forbidden not in rendered, forbidden

    sample_launch = {
        "max_simultaneous_processes": 9,
        "roles": {"browser": 1, "zygote": 3, "renderer": 2, "gpu": 1},
        "role_edges": [
            {"parent": "external", "child": "browser", "count": 1},
            {"parent": "browser", "child": "zygote", "count": 2},
            {"parent": "zygote", "child": "gpu", "count": 1},
        ],
        "lifecycle": {"leftovers": 0},
        "launch": {"profile_removed": True, "devtools_ready": True},
    }
    aggregate = aggregate_launches([sample_launch, sample_launch])
    assert aggregate["stable_peak"] is True
    assert aggregate["stable_role_counts"] is True
    assert aggregate["peaks"] == [9, 9]
    assert aggregate["gpu_seen"] is True
    assert aggregate["crashpad_seen"] is False
    assert aggregate["leftovers_total"] == 0
    assert aggregate["all_profiles_removed"] is True
    assert aggregate["role_edges"] == [
        {"parent": "browser", "child": "zygote", "count": 4},
        {"parent": "external", "child": "browser", "count": 2},
        {"parent": "zygote", "child": "gpu", "count": 2},
    ]
    print("SELFTEST OK")
    return 0


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Trace one managed BotGuard Chromium fan-out without logging argv."
    )
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--app-pid", type=int, help="read-only attach to this application PID")
    mode.add_argument("--launch", action="store_true", help="launch one controlled helper")
    mode.add_argument(
        "--aggregate",
        nargs="+",
        metavar="JSON",
        help="aggregate safe JSON files from controlled --launch runs",
    )
    mode.add_argument("--self-test", action="store_true", help="run synthetic parser tests")
    parser.add_argument("--browser", default=str(DEFAULT_BROWSER))
    parser.add_argument("--profile-root", default=str(DEFAULT_PROFILE_ROOT))
    parser.add_argument("--sample-interval-ms", type=int, default=DEFAULT_SAMPLE_INTERVAL_MS)
    parser.add_argument("--pss-interval-ms", type=int, default=DEFAULT_PSS_INTERVAL_MS)
    parser.add_argument("--hold-ms", type=int, default=1500,
                        help="launch mode: keep the browser alive this long before SIGTERM")
    parser.add_argument("--timeout", type=float, default=30.0,
                        help="launch mode: maximum observation time")
    parser.add_argument("--output", default=None, help="write JSON here instead of stdout")
    return parser.parse_args(argv)


def run_launch(args: argparse.Namespace) -> int:
    browser = Path(args.browser)
    if not browser.is_file():
        print(f"browser not found: {browser}", file=sys.stderr)
        return 2
    profile_root = Path(args.profile_root)
    profile = make_profile(profile_root)
    command = bot_guard_like_command(browser, profile)
    flags = observed_flags(command)
    process = subprocess.Popen(
        command,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        start_new_session=True,
    )
    started = time.monotonic()
    table = read_process_table()
    root_row = table.get(process.pid)
    tracker = BrowserTracker(
        app_pid=None,
        roots={process.pid: (root_row.starttime, str(profile))} if root_row else {},
        sample_interval_ms=args.sample_interval_ms,
        pss_interval_ms=args.pss_interval_ms,
    )
    if not tracker.roots:
        process.kill()
        process.wait(timeout=5)
        shutil.rmtree(profile, ignore_errors=True)
        print("browser exited before it could be sampled", file=sys.stderr)
        return 3

    devtools = profile / "DevToolsActivePort"
    devtools_ready = False
    hold_deadline: float | None = None
    iteration = 0
    try:
        while time.monotonic() < started + args.timeout:
            tracker.sample_once(with_pss=(iteration % tracker.pss_every == 0))
            iteration += 1
            now = time.monotonic()
            if not devtools_ready and devtools.is_file():
                devtools_ready = True
                hold_deadline = now + max(0, args.hold_ms) / 1000
            if process.poll() is not None and not tracker.live_pids():
                break
            if hold_deadline is not None and now >= hold_deadline:
                break
            time.sleep(tracker.sample_interval_ms / 1000)
        lifecycle = terminate_and_drain(tracker, DEFAULT_TERM_GRACE_MS)
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
        # Drain once more so final exits are recorded.
        for _ in range(10):
            if not tracker.sample_once():
                break
            time.sleep(0.05)
    finally:
        if process.poll() is None:
            process.kill()
        shutil.rmtree(profile, ignore_errors=True)

    result = tracker.snapshot()
    result["launch"] = {
        "browser_basename": browser.name,
        "observed_flags": flags,
        "devtools_ready": devtools_ready,
        "profile_created": True,
        "profile_removed": not profile.exists(),
        "exit_code": process.returncode,
    }
    result["lifecycle"] = lifecycle
    return emit(result, args.output)


def run_attach(args: argparse.Namespace) -> int:
    if args.app_pid <= 1:
        print("--app-pid must be a real application process", file=sys.stderr)
        return 2
    table = read_process_table()
    if args.app_pid not in table:
        print("application PID is not running", file=sys.stderr)
        return 2
    app = table[args.app_pid]
    if app.session != args.app_pid and app.pgrp != args.app_pid:
        print(
            "warning: the application is not a session/process-group leader; "
            "scope falls back to descendant matching",
            file=sys.stderr,
        )
    roots = discover_managed_roots(args.app_pid, table, MANAGED_PROFILE_PREFIX)
    tracker = BrowserTracker(
        app_pid=args.app_pid,
        roots=roots,
        sample_interval_ms=args.sample_interval_ms,
        pss_interval_ms=args.pss_interval_ms,
    )
    print(
        f"attached read-only to PID {args.app_pid}; waiting up to {args.timeout:.0f}s "
        f"for a managed BotGuard browser",
        file=sys.stderr,
    )
    deadline = time.monotonic() + args.timeout
    iteration = 0
    while time.monotonic() < deadline:
        count = tracker.sample_once(with_pss=(iteration % tracker.pss_every == 0))
        iteration += 1
        if count == 0 and not tracker.roots and iteration > 20:
            time.sleep(tracker.sample_interval_ms / 1000)
            continue
        if count == 0 and tracker.processes and not tracker.live_pids():
            break
        time.sleep(tracker.sample_interval_ms / 1000)
    result = tracker.snapshot()
    result["attach"] = {
        "app_session_matches_pid": app.session == args.app_pid,
        "app_pgrp_matches_pid": app.pgrp == args.app_pid,
        "managed_roots_seen": len(tracker.roots),
    }
    if not tracker.processes:
        result["warning"] = "no managed archivetune-potoken browser was observed"
    return emit(result, args.output)


def run_aggregate(args: argparse.Namespace) -> int:
    launches: list[dict[str, Any]] = []
    for raw_path in args.aggregate:
        path = Path(raw_path)
        try:
            item = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            print(f"cannot read launch JSON {path.name}: {exc}", file=sys.stderr)
            return 2
        if not isinstance(item, dict):
            print(f"launch JSON {path.name} is not an object", file=sys.stderr)
            return 2
        launches.append(item)
    result = {
        "schema": 1,
        "tool": "scripts/trace-legacy-browser.py",
        "mode": "aggregate",
        "aggregate": aggregate_launches(launches),
        "redaction": {
            "argv_emitted": False,
            "urls_emitted": False,
            "cookies_emitted": False,
            "profile_path_emitted": False,
            "environment_emitted": False,
        },
    }
    return emit(result, args.output)


def emit(result: dict[str, Any], output: str | None) -> int:
    rendered = json.dumps(result, indent=2, sort_keys=True)
    if output:
        Path(output).write_text(rendered + "\n", encoding="utf-8")
    else:
        print(rendered)
    return 0


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if args.self_test:
        return self_test()
    if args.launch:
        return run_launch(args)
    if args.aggregate:
        return run_aggregate(args)
    return run_attach(args)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

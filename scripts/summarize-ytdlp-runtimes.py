#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Render the markdown tables of a yt-dlp JS-runtime comparison from its JSON.

The report prose lives in ``YT_DLP_RUNTIME_COMPARISON.md`` and
``YT_DLP_RUST_BUN_COMPARISON.md``; the *numbers* in those tables are produced
here from the machine-readable artifact, so a table can never drift away from
the JSON it claims to describe.

Usage
-----
    scripts/summarize-ytdlp-runtimes.py \\
        packaging/resolver/validation/linux-rust-bun-comparison.json

The markdown is printed to stdout.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from typing import Any, Dict, List, Optional, Sequence

MIB = 1024 * 1024


def _mib(value: Optional[float]) -> str:
    if value is None:
        return "n/a"
    return "%.1f MiB" % (value / MIB)


def _runtime_label(entry: Dict[str, Any]) -> str:
    runtime = entry.get("runtime", {})
    return "%s %s" % (entry.get("key"), runtime.get("version") or "?")


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", help="the comparison JSON")
    parser.add_argument("--baseline", default="", help="override the baseline runtime key")
    args = parser.parse_args(argv)

    with open(os.path.abspath(args.report), "r") as handle:
        data = json.load(handle)

    per_runtime: Dict[str, Any] = data["per_runtime"]
    keys: List[str] = list(per_runtime.keys())
    baseline = args.baseline or data.get("headline_comparison", {}).get(
        "baseline_runtime", keys[0])

    out: List[str] = []

    # -- pooled repetitions, when raw observations are available ------------
    pooled = data.get("pooled_challenged") or {}
    if pooled:
        out.append("| Runtime | Success / attempts | Wall median (ms) | CPU median (s) | Median peak RSS | Maximum peak RSS |")
        out.append("| --- | ---: | ---: | ---: | ---: | ---: |")
        for key in keys:
            entry = pooled[key]
            out.append("| %s | %s / %s | %.1f | %.4f | %s | %s |" % (
                key, entry["successful"], entry["attempts_total"],
                entry["wall_ms_median"], entry["cpu_total_s_median"],
                _mib(entry["peak_tree_rss_bytes_median"]), _mib(entry["peak_tree_rss_bytes_max"])))
        out.append("")

    # -- headline table ----------------------------------------------------
    out.append("| Runtime | Version | Binary size | Wall time (median) | CPU time (median) "
               "| Peak tree RSS (max) | Runtime child RSS (max) | Runtime child CPU (max) |")
    out.append("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |")
    for key in keys:
        entry = per_runtime[key]
        runtime = entry["runtime"]
        challenged = entry["challenged"]
        child_rss = entry.get("challenged_per_process_class_peak_rss_bytes", {}) or {}
        child_cpu = entry.get("challenged_per_process_class_peak_cpu_s", {}) or {}
        runtime_class = ""
        for comm, value in child_rss.items():
            if comm in ("yt-dlp", "ffmpeg", "ffprobe", "unknown", "file", "uname"):
                continue
            runtime_class = comm
            break
        child_rss_text = "%s %s" % (runtime_class, _mib(child_rss.get(runtime_class)))
        child_cpu_text = "%s %.2f s" % (runtime_class, child_cpu.get(runtime_class) or 0.0)
        out.append("| **%s** | %s | %.2f MiB | **%s ms** | **%.3f s** | **%s** | %s | %s |" % (
            key, runtime.get("version"), runtime.get("size_mib") or 0.0,
            challenged.get("wall_ms_median"), challenged.get("cpu_total_s_median") or 0.0,
            _mib(challenged.get("peak_tree_rss_bytes_max")),
            child_rss_text, child_cpu_text))

    # -- ratio table -------------------------------------------------------
    out.append("")
    out.append("| Runtime | Wall vs %s | CPU vs %s | Peak RSS vs %s |" % (baseline, baseline, baseline))
    out.append("| --- | ---: | ---: | ---: |")
    for key in keys:
        entry = per_runtime[key]
        challenged = entry["challenged"]
        base = per_runtime[baseline]["challenged"]

        def ratio(value: Optional[float], reference: Optional[float]) -> str:
            if not value or not reference:
                return "n/a"
            return "%.2fx" % (value / reference)

        out.append("| %s | %s | %s | %s |" % (
            key,
            ratio(challenged.get("wall_ms_median"), base.get("wall_ms_median")),
            ratio(challenged.get("cpu_total_s_median"), base.get("cpu_total_s_median")),
            ratio(challenged.get("peak_tree_rss_bytes_max"), base.get("peak_tree_rss_bytes_max"))))

    # -- control / delta table --------------------------------------------
    out.append("")
    out.append("| Runtime | Clean control wall (median) | Clean control CPU (median) | "
               "Δ wall (challenged − clean control) | Δ CPU (challenged − clean control) | "
               "Δ peak tree RSS |")
    out.append("| --- | ---: | ---: | ---: | ---: | ---: |")
    for key in keys:
        entry = per_runtime[key]
        challenged = entry["challenged"]
        control = entry.get("control_unchallenged_clean") or entry["control_unchallenged"]
        delta_wall = None
        delta_cpu = None
        delta_rss = None
        if challenged.get("wall_ms_median") is not None and control.get("wall_ms_median") is not None:
            delta_wall = challenged["wall_ms_median"] - control["wall_ms_median"]
        if (challenged.get("cpu_total_s_median") is not None
                and control.get("cpu_total_s_median") is not None):
            delta_cpu = round(challenged["cpu_total_s_median"] - control["cpu_total_s_median"], 3)
        if (challenged.get("peak_tree_rss_bytes_max") is not None
                and control.get("peak_tree_rss_bytes_max") is not None):
            delta_rss = challenged["peak_tree_rss_bytes_max"] - control["peak_tree_rss_bytes_max"]
        out.append("| %s | %s ms | %.3f s | %s ms | %s s | %s |" % (
            key, control.get("wall_ms_median"), control.get("cpu_total_s_median") or 0.0,
            ("%+.0f" % delta_wall) if delta_wall is not None else "n/a",
            ("%+.3f" % delta_cpu) if delta_cpu is not None else "n/a",
            _mib(delta_rss) if delta_rss is not None else "n/a"))

    # -- per-run table -----------------------------------------------------
    out.append("")
    out.append("| Runtime | Run | Wall (ms) | Total CPU (s) | Peak tree RSS | Runtime child CPU ticks | "
               "Challenge solved | Solver script invocations | HTTP head | HTTP tail |")
    out.append("| --- | ---: | ---: | ---: | ---: | ---: | --- | ---: | --- | --- |")
    for run in data.get("runs", []):
        if run.get("scenario") != "challenged":
            continue
        child = ""
        for comm, ticks in sorted((run.get("cpu_ticks_by_comm") or {}).items()):
            if comm in ("yt-dlp", "ffmpeg", "ffprobe", "unknown", "file", "uname"):
                continue
            child = "%s %d" % (comm, ticks)
        out.append("| %s | %s | %s | %.3f | %s | %s | %s | %s | %s / %s bytes | %s / %s bytes |" % (
            run.get("runtime_key"), run.get("repeat"), run.get("wall_ms"),
            run.get("cpu_total_s") or 0.0, _mib(run.get("peak_tree_rss_bytes")),
            child or "n/a", "yes" if run.get("challenge_solved") else "no",
            run.get("runtime_script_invocations"),
            run.get("http_status"), run.get("http_bytes_read"),
            run.get("http_tail_status"), run.get("http_tail_bytes")))

    # -- repetition table --------------------------------------------------
    repetitions = data.get("repetitions") or {}
    entries: List[Dict[str, Any]] = []
    this_run = repetitions.get("this_run")
    if this_run:
        entries.append(this_run)
    entries.extend(repetitions.get("earlier") or [])
    if entries:
        out.append("")
        out.append("| Repetition | Runtime | Wall median (ms) | CPU median (s) | "
                   "Peak tree RSS max (MiB) |")
        out.append("| --- | --- | ---: | ---: | ---: |")
        for index, entry in enumerate(entries):
            label = "this run" if entry is this_run else entry.get("source", "?")
            for key in keys:
                values = (entry.get("runtimes") or {}).get(key, {})
                rss = values.get("peak_tree_rss_bytes_max")
                out.append("| %s | %s | %s | %s | %s |" % (
                    label, key, values.get("wall_ms_median"),
                    values.get("cpu_total_s_median"),
                    ("%.1f" % (rss / MIB)) if rss else "n/a"))

    print("\n".join(out))
    return 0

if __name__ == "__main__":
    sys.exit(main())

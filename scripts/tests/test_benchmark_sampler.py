#!/usr/bin/env python3
"""Developer checks for the process sampler and the resolver plumbing.

These are focused unit checks for the benchmark harnesses; they never start the
application, never read the keyring and never touch the network.  Run them
with:

    python3 scripts/tests/test_benchmark_sampler.py
"""

from __future__ import annotations

import importlib.util
import inspect
import os
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPTS = HERE.parent


def load_module(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


BASE = load_module(SCRIPTS / "benchmark-application.py", "archivetune_benchmark_application")
AUTH = load_module(SCRIPTS / "benchmark-authenticated-application.py", "archivetune_benchmark_authenticated")

MIB = BASE.MIB


def snapshot(processes: list[dict[str, object]]) -> BASE.TreeSnapshot:
    """Build a TreeSnapshot from raw process rows (no /proc access)."""
    rss = sum(int(process["rss_bytes"]) for process in processes)
    pss_values = [int(process["pss_bytes"]) for process in processes if process["pss_bytes"] is not None]
    return BASE.TreeSnapshot(
        wall_ms=1_000,
        rss_bytes=rss,
        pss_bytes=sum(pss_values) if pss_values else None,
        pss_missing=sum(1 for process in processes if process["pss_bytes"] is None),
        app_rss_bytes=int(processes[0]["rss_bytes"]) if processes else 0,
        child_rss_bytes=max(0, rss - (int(processes[0]["rss_bytes"]) if processes else 0)),
        process_count=len(processes),
        session_count=len(processes),
        pgrp_count=len(processes),
        registry_count=0,
        reparented_count=0,
        reparented_names=[],
        detached_from_app_group_count=0,
        detached_from_app_group_names=[],
        cgroup_paths=[],
        processes=processes,
    )


def process(name: str, category: str, rss_mib: float, pss_mib: float | None = None) -> dict[str, object]:
    return {
        "pid": 1,
        "name": name,
        "category": category,
        "rss_bytes": int(rss_mib * MIB),
        "pss_bytes": None if pss_mib is None else int(pss_mib * MIB),
        "session_id": 1,
        "pgrp_id": 1,
        "detached_from_app_group": False,
        "is_app": False,
    }


class ClassificationTest(unittest.TestCase):
    def test_bun_is_its_own_category(self) -> None:
        self.assertEqual(BASE.classify_process("bun", "bun", False), "bun")
        self.assertEqual(BASE.classify_process("bun-1.4.3", "bun-1.4.3", False), "bun")
        self.assertEqual(BASE.classify_process("bun", "bun", True), "jvm")

    def test_quickjs_stays_separate(self) -> None:
        self.assertEqual(BASE.classify_process("qjs", "qjs", False), "quickjs")

    def test_other_helpers_keep_their_categories(self) -> None:
        self.assertEqual(BASE.classify_process("yt-dlp", "yt-dlp", False), "yt_dlp")
        self.assertEqual(BASE.classify_process("ffmpeg", "ffmpeg", False), "media")
        self.assertEqual(BASE.classify_process("ffprobe", "ffprobe", False), "media")
        self.assertEqual(BASE.classify_process("brave", "brave", False), "browser")
        self.assertEqual(BASE.classify_process("node", "node", False), "javascript")
        self.assertEqual(BASE.classify_process("python3", "python3", False), "python")

    def test_unrelated_names_are_not_bun(self) -> None:
        for name in ("bundle", "bunny", "rebundle"):
            self.assertNotEqual(BASE.classify_process(name, name, False), "bun")


class SimultaneousAccountingTest(unittest.TestCase):
    """Memory numbers must be simultaneous sums, never sums of per-process maxima."""

    def test_peak_is_a_simultaneous_sum(self) -> None:
        samples = [
            snapshot(
                [
                    process("java", "jvm", 100.0, 60.0),
                    process("yt-dlp", "yt_dlp", 40.0, 20.0),
                ]
            ),
            snapshot(
                [
                    process("java", "jvm", 100.0, 60.0),
                    process("yt-dlp", "yt_dlp", 40.0, 20.0),
                    process("bun", "bun", 30.0, 15.0),
                ]
            ),
        ]
        stats = BASE.phase_stats(samples, (0, 5_000))
        assert stats is not None
        self.assertEqual(stats["rss_mib"]["max"], 170.0)
        self.assertEqual(stats["pss_mib"]["max"], 95.0)
        category = stats["category"]
        self.assertEqual(category["bun"]["max_rss_mib"], 30.0)
        self.assertEqual(category["bun"]["max_pss_mib"], 15.0)
        self.assertEqual(category["yt_dlp"]["max_rss_mib"], 40.0)

    def test_inventory_reports_simultaneous_totals_per_category(self) -> None:
        samples = [
            snapshot(
                [
                    process("java", "jvm", 100.0, 60.0),
                    process("yt-dlp", "yt_dlp", 40.0, 20.0),
                    process("yt-dlp", "yt_dlp", 35.0, 18.0),
                    process("bun", "bun", 30.0, 15.0),
                ]
            ),
        ]
        inventory = BASE.process_inventory(samples)
        yt_dlp = inventory["categories"]["yt_dlp"]
        self.assertEqual(yt_dlp["max_simultaneous_rss_mib"], 75.0)
        self.assertEqual(yt_dlp["max_simultaneous_pss_mib"], 38.0)
        self.assertEqual(yt_dlp["max_simultaneous_process_count"], 2)
        self.assertEqual(inventory["categories"]["bun"]["max_simultaneous_rss_mib"], 30.0)
        self.assertEqual(inventory["bun_processes_observed"], ["bun"])
        self.assertEqual(inventory["ytdlp_processes_observed"], ["yt-dlp"])

    def test_per_process_maximum_is_not_added_up(self) -> None:
        """Two samples that never overlap must not be summed into one peak."""
        samples = [
            snapshot([process("java", "jvm", 10.0, 5.0), process("bun", "bun", 40.0, 20.0)]),
            snapshot([process("java", "jvm", 10.0, 5.0), process("bun", "bun", 60.0, 30.0)]),
        ]
        stats = BASE.phase_stats(samples, (0, 5_000))
        assert stats is not None
        self.assertEqual(stats["rss_mib"]["max"], 70.0)
        self.assertNotEqual(stats["rss_mib"]["max"], 100.0)


class ResolverPlumbingTest(unittest.TestCase):
    def _environment(self, resolver: dict[str, object] | None) -> dict[str, str]:
        return BASE.build_environment(
            display=99,
            run_dir=Path("/tmp/opencode/harness-selftest"),
            home=Path("/tmp/opencode/harness-selftest/home"),
            tmp=Path("/tmp/opencode/harness-selftest/tmp"),
            resolver=resolver,
        )

    def test_bun_engine_publishes_only_bun(self) -> None:
        resolver = {"engine": "bun", "yt_dlp": Path("/opt/yt-dlp"), "bun": Path("/opt/bun")}
        environment = self._environment(resolver)
        self.assertEqual(environment["ARCHIVETUNE_BUN"], "/opt/bun")
        self.assertEqual(environment["ARCHIVETUNE_YTDLP"], "/opt/yt-dlp")
        self.assertNotIn("ARCHIVETUNE_QUICKJS", environment)

    def test_quickjs_engine_publishes_only_quickjs(self) -> None:
        resolver = {"engine": "quickjs", "yt_dlp": Path("/opt/yt-dlp"), "quickjs": Path("/opt/qjs")}
        environment = self._environment(resolver)
        self.assertEqual(environment["ARCHIVETUNE_QUICKJS"], "/opt/qjs")
        self.assertNotIn("ARCHIVETUNE_BUN", environment)

    def test_baseline_arm_has_no_resolver(self) -> None:
        environment = self._environment(None)
        self.assertNotIn("ARCHIVETUNE_YTDLP", environment)
        self.assertNotIn("ARCHIVETUNE_QUICKJS", environment)
        self.assertNotIn("ARCHIVETUNE_BUN", environment)

    def test_runtime_options_are_stripped(self) -> None:
        os.environ["BUN_OPTIONS"] = "--smol"
        os.environ["DO_NOT_TRACK"] = "1"
        try:
            environment = self._environment(None)
        finally:
            os.environ.pop("BUN_OPTIONS", None)
            os.environ.pop("DO_NOT_TRACK", None)
        self.assertNotIn("BUN_OPTIONS", environment)
        self.assertNotIn("DO_NOT_TRACK", environment)

    def test_system_property_only_for_bun(self) -> None:
        bun = {"engine": "bun", "yt_dlp": Path("/opt/yt-dlp"), "bun": Path("/opt/bun")}
        quickjs = {"engine": "quickjs", "yt_dlp": Path("/opt/yt-dlp"), "quickjs": Path("/opt/qjs")}
        self.assertEqual(BASE.resolver_system_properties(bun), ["-Darchivetune.bun=/opt/bun"])
        self.assertEqual(BASE.resolver_system_properties(quickjs), [])

    def test_resolver_summary_reports_the_engine(self) -> None:
        bun = {
            "engine": "bun",
            "yt_dlp": Path("/opt/yt-dlp"),
            "bun": Path("/opt/bun"),
            "bun_version": "1.4.3",
        }
        summary = BASE.resolver_summary(bun)
        assert summary is not None
        self.assertEqual(summary["engine"], "bun")
        self.assertEqual(summary["bun_version"], "1.4.3")
        self.assertNotIn("quickjs_path", summary)

    def test_version_match_is_prefix_exact(self) -> None:
        self.assertTrue(AUTH._version_matches("1.4.3", "1.4.3"))
        self.assertTrue(AUTH._version_matches("1.4.3+c6da4a4d3", "1.4.3"))
        self.assertFalse(AUTH._version_matches("1.4.30", "1.4.3"))
        self.assertFalse(AUTH._version_matches("1.3.14", "1.4.3"))
        self.assertFalse(AUTH._version_matches(None, "1.4.3"))


class InstrumentedArmTest(unittest.TestCase):
    def test_both_arms_use_the_same_hook(self) -> None:
        """There is one hook only: the same text is injected into both arms."""
        videos = ["jNQXAC9IVRw"]
        block = AUTH.instrumentation_block(videos, 2, "BaW_jenozKc")
        self.assertIn("// Test-only view of the real session", block)
        self.assertIn("account.ensurePlaybackAuth(videoId, authState)", block)
        self.assertIn("delegatedProvider.currentAuthState()", block)
        self.assertNotIn("production-no-application-mint", block)
        # The arm-independent hook must not carry a policy switch.
        self.assertNotIn("force_po_token_mint", inspect.signature(AUTH.instrumentation_block).parameters)


if __name__ == "__main__":
    unittest.main(verbosity=2)

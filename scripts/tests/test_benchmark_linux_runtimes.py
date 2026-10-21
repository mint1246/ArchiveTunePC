#!/usr/bin/env python3
"""Developer checks for the Linux runtime comparison harness.

These tests never start the real application, never read the keyring, never
touch the network and never write to the user's home directory.  They exercise
the /proc sampler, the marker protocol, the isolation rules and the cleanup
rules against real ``/proc`` data and against small helper commands.

Run them with:

    python3 scripts/tests/test_benchmark_linux_runtimes.py
"""

from __future__ import annotations

import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import textwrap
import time
import unittest
from pathlib import Path
from unittest.mock import patch

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


HARNESS = load_module(SCRIPTS / "benchmark-linux-runtimes.py", "archivetune_linux_runtime_benchmark")

FAKE_APP = textwrap.dedent(
    """\
    #!/usr/bin/env python3
    import json, sys, time
    PREFIX = "ARCHIVETUNE_RUNTIME_BENCHMARK\\t"
    def emit(event, **fields):
        payload = {"event": event}
        payload.update(fields)
        sys.stdout.write(PREFIX + json.dumps(payload) + "\\n")
        sys.stdout.flush()
    def burn(seconds):
        end = time.monotonic() + seconds
        value = 0
        while time.monotonic() < end:
            for index in range(20000):
                value += index * index
        return value
    burn(0.2)
    emit("window-ready")
    emit("phase-start", phase="idle")
    burn(0.3)
    emit("phase-end", phase="idle")
    emit("phase-start", phase="playback")
    burn(0.3)
    emit("phase-end", phase="playback")
    emit("complete", success=True)
    """
)

BUSY_APP = textwrap.dedent(
    """\
    #!/usr/bin/env python3
    import time
    end = time.monotonic() + 2.5
    value = 0
    while time.monotonic() < end:
        for index in range(20000):
            value += index * index
    """
)

SPAWNING_APP = textwrap.dedent(
    """\
    #!/usr/bin/env python3
    import subprocess, sys, time
    child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(2.5)"])
    sys.stdout.write("child=%d\\n" % child.pid)
    sys.stdout.flush()
    time.sleep(2.5)
    """
)


def write_script(directory: Path, name: str, body: str) -> Path:
    path = directory / name
    path.write_text(body, encoding="utf-8")
    path.chmod(0o755)
    return path


class ProcReadingTest(unittest.TestCase):
    def test_this_process_appears_in_the_proc_table(self) -> None:
        rows = HARNESS.read_rows()
        self.assertIn(os.getpid(), rows)
        row = rows[os.getpid()]
        self.assertEqual(row.ppid, os.getppid())
        self.assertGreater(row.rss_bytes, 0)
        self.assertGreater(row.starttime, 0)

    def test_rollup_separates_private_shared_and_total(self) -> None:
        rollup = HARNESS.read_rollup(os.getpid())
        self.assertIsNotNone(rollup)
        assert rollup is not None
        rss, pss, uss = rollup
        self.assertGreater(rss, 0)
        self.assertGreaterEqual(pss, 0)
        # The private footprint never exceeds the resident set.
        self.assertLessEqual(uss, rss)

    def test_a_missing_rollup_is_reported_as_absent(self) -> None:
        self.assertIsNone(HARNESS.read_rollup(999_999_999))


class CpuUnitTest(unittest.TestCase):
    def test_one_busy_core_is_one_hundred_percent(self) -> None:
        self.assertEqual(HARNESS.cpu_percent_one_core(HARNESS.CLOCK_TICKS, 1.0), 100.0)

    def test_half_a_core_over_two_seconds_is_fifty_percent(self) -> None:
        self.assertEqual(HARNESS.cpu_percent_one_core(HARNESS.CLOCK_TICKS, 2.0), 50.0)

    def test_two_cores_over_one_second_is_two_hundred_percent(self) -> None:
        self.assertEqual(HARNESS.cpu_percent_one_core(2 * HARNESS.CLOCK_TICKS, 1.0), 200.0)

    def test_a_zero_length_interval_is_not_a_division_by_zero(self) -> None:
        self.assertEqual(HARNESS.cpu_percent_one_core(100, 0.0), 0.0)


class RedactionTest(unittest.TestCase):
    def test_an_authorization_header_is_removed(self) -> None:
        text = HARNESS.redact_text("Authorization: Bearer abcdef0123456789")
        self.assertNotIn("abcdef0123456789", text)
        self.assertIn(HARNESS.REDACTED, text)

    def test_cookie_values_are_removed(self) -> None:
        text = HARNESS.redact_text("Cookie: SAPISID=xyz123; HSID=abc")
        self.assertNotIn("xyz123", text)

    def test_a_benign_line_is_untouched(self) -> None:
        line = "ArchiveTune Desktop is shutting down"
        self.assertEqual(HARNESS.redact_text(line), line)

    def test_nested_structures_are_redacted(self) -> None:
        payload = HARNESS.redact_value({"a": ["token=supersecretvalue"], "b": 3})
        self.assertNotIn("supersecretvalue", json.dumps(payload))
        self.assertEqual(payload["b"], 3)


class IsolationTest(unittest.TestCase):
    def _environment(self, root: Path) -> dict[str, str]:
        return HARNESS.build_isolated_environment(
            root=root,
            inherited={
                "HOME": "/home/user",
                "PATH": "/usr/bin",
                "XDG_CONFIG_HOME": "/home/user/.config",
                "XDG_DATA_HOME": "/home/user/.local/share",
                "XDG_CACHE_HOME": "/home/user/.cache",
                "XDG_RUNTIME_DIR": "/run/user/1000",
                "ARCHIVETUNE_HOME": "/home/user/.local/share/archivetune",
                "MY_API_TOKEN": "super-secret",
                "DISPLAY": ":0",
            },
            idle_seconds=10,
            play_seconds=20,
            audio=root / "audio.wav",
            display=":99",
        )

    def test_the_user_paths_are_replaced_by_the_private_tree(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "root"
            environment = self._environment(root)
            self.assertEqual(environment["HOME"], str(root / "home"))
            self.assertEqual(environment["XDG_CONFIG_HOME"], str(root / "config"))
            self.assertEqual(environment["XDG_DATA_HOME"], str(root / "data"))
            self.assertEqual(environment["XDG_CACHE_HOME"], str(root / "cache"))
            self.assertEqual(environment["XDG_STATE_HOME"], str(root / "state"))
            self.assertEqual(environment["XDG_RUNTIME_DIR"], str(root / "runtime"))
            self.assertEqual(environment["TMPDIR"], str(root / "tmp"))

    def test_the_application_home_override_cannot_win_over_the_isolation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            self.assertNotIn("ARCHIVETUNE_HOME", self._environment(Path(directory) / "root"))

    def test_account_secrets_are_not_forwarded(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            self.assertNotIn("MY_API_TOKEN", self._environment(Path(directory) / "root"))

    def test_the_benchmark_contract_environment_is_exported(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "root"
            environment = self._environment(root)
            self.assertEqual(environment[HARNESS.ENV_BENCHMARK], "1")
            self.assertEqual(environment[HARNESS.ENV_BENCHMARK_ROOT], str(root))
            self.assertEqual(environment[HARNESS.ENV_BENCHMARK_AUDIO], str(root / "audio.wav"))
            self.assertEqual(environment[HARNESS.ENV_BENCHMARK_IDLE_SECONDS], "10")
            self.assertEqual(environment[HARNESS.ENV_BENCHMARK_PLAY_SECONDS], "20")

    def test_both_arms_render_with_the_software_renderer(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(self._environment(Path(directory) / "root")["SKIKO_RENDER_API"], "SOFTWARE")

    def test_the_private_directories_exist_and_are_owner_only(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "root"
            self._environment(root)
            for name in ("home", "config", "data", "cache", "state", "runtime", "tmp"):
                path = root / name
                self.assertTrue(path.is_dir())
                self.assertEqual(path.stat().st_mode & 0o777, 0o700)

    def test_the_settings_fixture_lands_in_the_private_config_tree(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "root"
            self._environment(root)
            written = HARNESS.write_settings_fixture(root)
            self.assertTrue(written)
            for path in written:
                self.assertIn("navigation.lastDestination=SETTINGS", Path(path).read_text(encoding="utf-8"))


class CleanupTest(unittest.TestCase):
    def test_a_recycled_pid_is_never_signalled(self) -> None:
        class _Row:
            starttime = 1234  # different from the recorded one

        with patch.object(HARNESS, "read_rows", return_value={111: _Row()}):
            with patch.object(HARNESS.os, "kill") as kill:
                result = HARNESS.terminate_tracked([(111, 999)], grace_seconds=0.0)
        kill.assert_not_called()
        self.assertEqual(result["skipped"], [{"pid": 111, "reason": "pid-reused"}])

    def test_only_tracked_pids_are_signalled(self) -> None:
        class _Row:
            starttime = 5

        with patch.object(HARNESS, "read_rows", return_value={111: _Row()}):
            with patch.object(HARNESS.os, "kill") as kill:
                HARNESS.terminate_tracked([(111, 5)], grace_seconds=0.0)
        signalled = {call.args[0] for call in kill.call_args_list}
        self.assertEqual(signalled, {111})

    def test_the_harness_never_kills_a_process_group_or_a_name(self) -> None:
        source = (SCRIPTS / "benchmark-linux-runtimes.py").read_text(encoding="utf-8")
        for forbidden in ("os.killpg", "killpg(", "pkill(", "killall("):
            self.assertNotIn(forbidden, source, f"{forbidden} must never be used by the harness")


class MarkerProtocolTest(unittest.TestCase):
    def test_a_contract_marker_is_recognised(self) -> None:
        marker = HARNESS.parse_marker_line(
            'ARCHIVETUNE_RUNTIME_BENCHMARK\t{"event": "phase-start", "phase": "playback"}'
        )
        self.assertIsNotNone(marker)
        assert marker is not None
        self.assertEqual(marker["event"], "phase-start")
        self.assertEqual(marker["phase"], "playback")

    def test_the_application_timestamp_is_preferred_over_the_arrival_time(self) -> None:
        marker = HARNESS.parse_marker_line(
            'ARCHIVETUNE_RUNTIME_BENCHMARK\t{"event": "window-ready", "wall_ms": 1717171717171}'
        )
        self.assertIsNotNone(marker)
        assert marker is not None
        self.assertEqual(marker["wall_ms"], 1717171717171)

    def test_an_ordinary_log_line_is_not_a_marker(self) -> None:
        self.assertIsNone(HARNESS.parse_marker_line("Starting ArchiveTune Desktop"))

    def test_a_malformed_marker_does_not_raise(self) -> None:
        self.assertIsNone(HARNESS.parse_marker_line("ARCHIVETUNE_RUNTIME_BENCHMARK\tnot json"))

    def test_marker_payloads_are_redacted(self) -> None:
        marker = HARNESS.parse_marker_line(
            'ARCHIVETUNE_RUNTIME_BENCHMARK\t{"event": "failure", "category": "java.lang.Error",'
            ' "message": "Authorization: Bearer abcdef0123456789"}'
        )
        self.assertIsNotNone(marker)
        assert marker is not None
        self.assertEqual(marker["event"], "failure")
        self.assertEqual(marker["category"], "java.lang.Error")
        self.assertNotIn("abcdef0123456789", json.dumps(marker))


class PhaseWindowTest(unittest.TestCase):
    def _markers(self) -> list[dict[str, object]]:
        return [
            {"event": "window-ready", "wall_ms": 1_000},
            {"event": "phase-start", "wall_ms": 1_100, "phase": "idle"},
            {"event": "phase-end", "wall_ms": 11_100, "phase": "idle"},
            {"event": "phase-start", "wall_ms": 11_200, "phase": "playback"},
            {"event": "phase-end", "wall_ms": 31_200, "phase": "playback"},
        ]

    def test_the_contract_markers_define_the_phase_windows(self) -> None:
        windows = HARNESS.derive_phase_windows(
            self._markers(), launch_wall_ms=500, ready_wall_ms=1_000, exit_wall_ms=40_000
        )
        self.assertEqual(windows["startup"], (500, 1_000))
        self.assertEqual(windows["idle"], (1_100, 11_100))
        self.assertEqual(windows["playback"], (11_200, 31_200))
        self.assertIsNone(windows["paused"])

    def test_a_phase_without_an_end_marker_runs_until_the_process_exits(self) -> None:
        markers = [{"event": "phase-start", "wall_ms": 2_000, "phase": "playback"}]
        windows = HARNESS.derive_phase_windows(
            markers, launch_wall_ms=500, ready_wall_ms=1_000, exit_wall_ms=40_000
        )
        self.assertEqual(windows["playback"], (2_000, 40_000))


class PhaseCpuTest(unittest.TestCase):
    def test_failed_runs_cannot_produce_a_usable_comparison(self):
        summary = HARNESS.aggregate_runs([{"warmup": False, "status": "failed"}])
        self.assertEqual(summary["ok_runs"], 0)
        self.assertEqual(summary["failed_runs"], 1)
        self.assertFalse(summary["usable"])
        self.assertNotIn("startup_ms", summary)
        comparison = HARNESS.compare({"startup_ms": {"median": 100}, "usable": True},
                                     {"startup_ms": {"median": 50}, "usable": False})
        self.assertNotIn("ratio", comparison["startup_ms_median"])

    def test_mean_cpu_uses_boundary_interpolation_instead_of_quantized_sample_median(self):
        samples = [dict(wall_ms=ms, cpu_seconds=cpu, cpu_percent_one_core=0,
                        rss_mib=10, pss_mib=8, uss_mib=7, process_count=1, processes=[])
                   for ms, cpu in ((1000, 1.0), (2000, 1.1), (3000, 1.2))]
        phase = HARNESS.summarize_phase(samples, (1500, 2500))
        self.assertAlmostEqual(phase["cpu_seconds"], 0.1)
        self.assertAlmostEqual(phase["cpu_mean_percent_one_core"], 10.0)
        self.assertEqual(phase["cpu_percent_one_core"]["median"], 0)


class SamplerTest(unittest.TestCase):
    """The sampler is exercised against real helper processes."""

    def _sample_for(self, script: Path, seconds: float = 1.0) -> HARNESS.Sampler:
        process = subprocess.Popen(
            [sys.executable, str(script)],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            start_new_session=True,
        )
        sampler = HARNESS.Sampler(process.pid, interval_ms=50, pss_interval_ms=200)
        try:
            deadline = time.monotonic() + seconds
            while time.monotonic() < deadline:
                sampler.sample()
                time.sleep(0.05)
        finally:
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
            if process.stdout is not None:
                process.stdout.close()
        return sampler

    def test_a_busy_process_shows_cpu_and_memory(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            script = write_script(Path(directory), "busy.py", BUSY_APP)
            sampler = self._sample_for(script, seconds=1.0)
        self.assertTrue(sampler.samples)
        last = sampler.samples[-1]
        self.assertEqual(last["process_count"], 1)
        self.assertGreater(last["cpu_percent_one_core"], 0.0)
        self.assertGreater(last["rss_mib"], 0)
        self.assertGreater(last["cpu_seconds"], 0.0)

    def test_a_grandchild_is_tracked_as_part_of_the_tree(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            script = write_script(Path(directory), "spawning.py", SPAWNING_APP)
            sampler = self._sample_for(script, seconds=1.2)
        self.assertGreaterEqual(len(sampler.tracked), 2)

    def test_the_harness_process_itself_is_never_tracked(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            script = write_script(Path(directory), "busy.py", BUSY_APP)
            sampler = self._sample_for(script, seconds=0.6)
        self.assertNotIn(os.getpid(), sampler.tracked)


class EndToEndTest(unittest.TestCase):
    """A complete, tiny benchmark run against two helper arms."""

    def _run(self, directory: Path, script: Path) -> dict:
        out = directory / "report.json"
        exit_code = HARNESS.main(
            [
                "--variant", f"alpha={sys.executable} {script}",
                "--variant", f"beta={sys.executable} {script}",
                "--rounds", "1",
                "--warmup", "0",
                "--display", ":999",
                "--sample-interval-ms", "50",
                "--pss-interval-ms", "150",
                "--idle-seconds", "1",
                "--play-seconds", "1",
                "--audio-seconds", "1",
                "--run-root", str(directory / "runs"),
                "--out", str(out),
            ]
        )
        self.assertEqual(exit_code, 0)
        return json.loads(out.read_text(encoding="utf-8"))

    def test_the_report_contains_the_measured_phases(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            script = write_script(root, "fake_app.py", FAKE_APP)
            report = self._run(root, script)

        self.assertEqual(report["schema"], HARNESS.SCHEMA)
        self.assertEqual(len(report["runs"]), 2)
        for run in report["runs"]:
            self.assertEqual(run["status"], "ok")
            self.assertIsNotNone(run["phases"]["startup"])
            self.assertIsNotNone(run["phases"]["idle"])
            self.assertIsNotNone(run["phases"]["playback"])
            self.assertGreater(run["phases"]["idle"]["cpu_percent_one_core"]["median"], 0.0)
            self.assertIsNotNone(run["phases"]["idle"]["rss_mib"]["median"])
        self.assertIn("alpha", report["summary"])
        self.assertIn("beta", report["summary"])
        self.assertEqual(report["summary"]["alpha"]["runs"], 1)
        self.assertTrue(report["limitations"])

    def test_the_user_home_directory_is_never_used(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            script = write_script(root, "fake_app.py", FAKE_APP)
            report = self._run(root, script)
        for run in report["runs"]:
            self.assertTrue(run["environment"]["HOME"].startswith(str(root / "runs")))
            self.assertNotIn(str(Path.home()), run["environment"]["HOME"])

    def test_heap_flags_are_reported_exactly_as_configured(self) -> None:
        described = HARNESS.describe_variant(
            "jvm", ["java", "-Xms32m", "-Xmx768m", "-cp", "x", "MainKt"], "jvm arm"
        )
        self.assertEqual(described["heap"]["xmx"], "768m")
        self.assertEqual(described["heap"]["xms"], "32m")
        self.assertEqual(described["command"], ["java", "-Xms32m", "-Xmx768m", "-cp", "x", "MainKt"])

        native = HARNESS.describe_variant("native", ["./ArchiveTune"], "native image, 128m heap")
        self.assertIsNone(native["heap"]["max_heap_size"])
        self.assertEqual(native["command"], ["./ArchiveTune"])


if __name__ == "__main__":
    unittest.main(verbosity=2)

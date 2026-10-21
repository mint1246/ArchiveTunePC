#!/usr/bin/env python3
"""Serialized, counterbalanced whole-app resolver comparison with isolated account clones.

No extraction implementation is embedded here. All arms execute committed application
code, and use the same temporary hook, videos, quality, CDN probes and muted playback.
Builds finish before any timing starts. No cookie, URL, complete argv or raw app log is
written to the safe output. Original app/profile/keyring entries are never modified.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import math
import os
import random
import re
import statistics
import subprocess
import sys
import time
import tempfile
from pathlib import Path


def load_auth():
    spec = importlib.util.spec_from_file_location("matrix_authenticated_benchmark", Path(__file__).with_name("benchmark-authenticated-application.py"))
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


AUTH = load_auth()
BASE = AUTH.BASE
VIDEOS = ["jNQXAC9IVRw", "dQw4w9WgXcQ", "aqz-KE-bpKQ", "9bZkp7q19f0", "M7lc1UVf-VE", "kJQP7kiw5Fk"]


def distribution(values):
    values = sorted(values)
    if not values:
        return {"n": 0}
    return {"n": len(values), "min": values[0], "p50": statistics.median(values),
            "p90": values[math.ceil(.90 * len(values)) - 1],
            "p95": values[math.ceil(.95 * len(values)) - 1], "max": values[-1]}


def hook(videos, repeats, probe, auth_policy, native_failure_fixture=False):
    block = AUTH.instrumentation_block(videos, repeats, probe)
    if block.count('"bitrate" to stream.bitrate,') != 1:
        raise RuntimeError("Quality hook anchor changed")
    block = block.replace('"bitrate" to stream.bitrate,', '''"bitrate" to stream.bitrate,
                        "has_audio_config" to (stream.audioConfig != null),
                        "loudness_db" to stream.audioConfig?.loudnessDb,
                        "perceptual_loudness_db" to stream.audioConfig?.perceptualLoudnessDb,''')
    literal = ", ".join(f'"{video}"' for video in videos)
    if block.count(f"val videos = listOf({literal})") != 1:
        raise RuntimeError("Video-order hook anchor changed")
    block = block.replace(f"val videos = listOf({literal})",
                          f'val videos = System.getenv("ARCHIVETUNE_BENCH_VIDEOS")?.split(",") ?: listOf({literal})')
    if native_failure_fixture:
        anchor = '        emit("extraction-begin", "videos" to videos.size, "repeats" to repeats)'
        if block.count(anchor) != 1:
            raise RuntimeError("Native fault fixture anchor changed")
        # Test-only fault in a disposable Main.kt, never a production resolver flag.
        # Reflection keeps the hook compilable against historical baseline classes.
        fixture = '''        if (System.getenv("ARCHIVETUNE_BENCH_NATIVE_FAILURE") == "1") {
            val failNative: suspend (moe.rukamori.archivetune.innertube.models.YouTubeClient, String, String?, Int?, PlaybackAuthState) -> moe.rukamori.archivetune.innertube.models.response.PlayerResponse =
                { _, _, _, _, _ -> throw java.io.IOException("Controlled benchmark native failure") }
            musicServices.backend.javaClass.methods.single { it.name.startsWith("setStreamPlayerRequest") }
                .invoke(musicServices.backend, failNative)
            emit("controlled-native-failure", "enabled" to true)
        }
'''
        block = block.replace(anchor, fixture + anchor)
    if auth_policy == "production":
        pattern = r"override fun currentAuthState\(\): PlaybackAuthState \{.*?\n            \}\n\n            override suspend fun refreshIfNeeded"
        replacement = "override fun currentAuthState(): PlaybackAuthState = account.currentAuthState()\n\n            override suspend fun refreshIfNeeded"
        block, count = re.subn(pattern, replacement, block, count=1, flags=re.S)
        if count != 1:
            raise RuntimeError("Session-provider hook anchor changed")
        block = block.replace("// every PO-token binding is hidden so the real account manager has to mint again through",
                              "// PO-token bindings remain intact; the real account manager decides whether to mint through")
        # Force only the separate control, never the production extraction phase.
        if block.count("val stripped = delegatedProvider.currentAuthState()") != 1:
            raise RuntimeError("Cold proof hook anchor changed")
        block = block.replace("val stripped = delegatedProvider.currentAuthState()",
                              "val stripped = account.currentAuthState().copy(poToken = null, poTokenGvs = null, "
                              "poTokenGvsSession = null, poTokenGvsVideoId = null, poTokenPlayer = null, "
                              "poTokenPlayerVideoId = null, poTokenSubs = null, poTokenSubsVideoId = null, webClientPoTokenEnabled = false)")
    # Run the forced control AFTER extraction so it cannot prewarm that phase.
    begin = block.index('        emit("cold-proof-begin")')
    end_marker = '        emit("cold-proof-teardown-settled", "browser_gone" to browserTeardown)'
    end = block.index(end_marker, begin) + len(end_marker)
    control = block[begin:end]
    block = block[:begin] + block[end:]
    anchor = '        emit("playback-begin", "volume" to 0.0)'
    if block.count(anchor) != 1:
        raise RuntimeError("Playback hook anchor changed")
    return block.replace(anchor, control + "\n\n" + anchor)


def prepare_checkout(repo, revision, destination):
    """Use committed local submodule objects; never rewrite shared submodule URLs."""
    if destination.exists():
        if BASE.git_head(destination) != revision:
            raise RuntimeError("Existing matrix checkout has a different revision")
        return destination
    subprocess.run(["git", "-C", str(repo), "worktree", "add", "--detach", str(destination), revision],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    tree = BASE.git_output(repo, "ls-tree", revision).splitlines()
    for line in tree:
        details, name = line.split("\t", 1)
        mode, kind, commit = details.split()
        if mode != "160000":
            continue
        source, target = repo / name, destination / name
        if not source.exists():
            raise RuntimeError(f"Missing local committed submodule source: {name}")
        subprocess.run(["git", "clone", "--shared", "--no-checkout", str(source), str(target)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        subprocess.run(["git", "-C", str(target), "checkout", "--detach", commit],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    return destination


def safe_run(run, mode, round_number, expected, metadata):
    records = [{key: record.get(key) for key in
                ("video_id", "iteration", "elapsed_ms", "success", "failure", "client_name", "client_version",
                 "format", "mime", "bitrate", "has_audio_config", "loudness_db", "perceptual_loudness_db")}
               for record in run["resolver_records"]]
    return {"mode": mode, "round": round_number, "commit": run["commit"],
            "expected_attempts": expected, "missing_attempts": max(0, expected - len(records)),
            "records": records, "exit_code": run["app_exit_code"], "harness_timeout": run["harness_timeout"],
            "authentication": run["authentication"], "playback": run["playback"],
            "phases": {name: AUTH._phase_summary(run, name) for name in run["phases"]},
            "cpu": run["cpu"], "inventory": run["process_inventory"], "cleanup": run["cleanup"],
            "sampler_error": run["sampler_error"], "build": metadata,
            "resolver": run.get("resolver"), "session_clone": run["session_clone"],
            "controlled_native_failure": any(marker.get("type") == "controlled-native-failure" and marker.get("enabled")
                                             for marker in run.get("markers", []))}


def summary(runs):
    output = {}
    for mode in dict.fromkeys(run["mode"] for run in runs):
        selected = [run for run in runs if run["mode"] == mode]
        records = [record for run in selected for record in run["records"]]
        successes = [record for record in records if record["success"]]
        def peak(phase, metric):
            values = [((run["phases"].get(phase) or {}).get(metric) or {}).get("max") for run in selected]
            return distribution(value for value in values if value is not None)
        output[mode] = {
            "expected_attempts": sum(run["expected_attempts"] for run in selected),
            "observed_attempts": len(records), "successes": len(successes),
            "failed_attempts": len(records) - len(successes),
            "missing_attempts": sum(run["missing_attempts"] for run in selected),
            "latency_success_ms": distribution(record["elapsed_ms"] for record in successes),
            "latency_all_observed_ms": distribution(record["elapsed_ms"] for record in records),
            "first_request_in_process_ms": distribution(run["records"][0]["elapsed_ms"] for run in selected if run["records"]),
            "first_request_per_video_ms": distribution(record["elapsed_ms"] for record in successes if record["iteration"] == 0),
            "same_video_repeat_ms": distribution(record["elapsed_ms"] for record in successes if record["iteration"] > 0),
            "extraction_peak_pss_mib_per_run": peak("extraction", "pss_mib"),
            "extraction_peak_rss_mib_per_run": peak("extraction", "rss_mib"),
            "idle_peak_pss_mib_per_run": peak("idle", "pss_mib"),
            "post_idle_peak_pss_mib_per_run": peak("post_idle", "pss_mib"),
            "extraction_cpu_seconds_per_run": distribution(run["cpu"]["phases"]["extraction"]["total_seconds"]
                                                          for run in selected if run["cpu"]["phases"].get("extraction")),
            "jvm_reaped_tree_cpu_seconds": distribution(run["cpu"]["reaped_tree_seconds"] for run in selected),
            "all_playback_checks_passed": all(run["playback"]["all_checks_passed"] for run in selected),
            "fastpath_successes": sum(record["client_name"] == "VISIONOS" for record in successes) if mode == "hybrid" else None,
            "fallback_successes": sum(record["client_name"] == "YT_DLP" for record in successes) if mode == "hybrid" else None,
            "per_video_formats": {
                video: [dict(zip(("format", "mime", "bitrate", "has_audio_config", "loudness_db", "perceptual_loudness_db"), values))
                        for values in sorted({tuple(record.get(key) for key in ("format", "mime", "bitrate", "has_audio_config", "loudness_db", "perceptual_loudness_db"))
                                              for record in successes if record["video_id"] == video}, key=repr)]
                for video in sorted({record["video_id"] for record in records})},
        }
    return output


def valid_measurement(run):
    proof = run["authentication"]["browser_proof"]
    cleanup = run["cleanup"]
    return (run["authentication"]["guard_passed"] and run["session_clone"]["clone_key_cleared"]
            and run["sampler_error"] is None and run["cpu"]["tracker_error"] is None
            and proof["browser_teardown_observed"] and proof["cold_successes"] == proof["cold_attempts"] > 0
            and cleanup["no_helpers_in_post_idle"] and cleanup["no_helpers_after_shutdown"] and cleanup["private_home_removed"]
            and (run["phases"].get("extraction") or {}).get("complete_pss_samples", 0) > 0
            and all(not record["success"] or (record["bitrate"] and isinstance(record["has_audio_config"], bool))
                    for record in run["records"]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate-ref", default="feature/ytdlp-resolver")
    parser.add_argument("--baseline-ref", default="0817a5024")
    parser.add_argument("--original-ytdlp-ref", default="6df503606")
    parser.add_argument("--rounds", type=int, default=4)
    parser.add_argument("--repeats", type=int, default=2)
    parser.add_argument("--videos", nargs="+", default=VIDEOS)
    parser.add_argument("--modes", nargs="+", choices=("legacy", "ytdlp-original", "ytdlp-optimized", "hybrid"),
                        default=["legacy", "ytdlp-original", "ytdlp-optimized", "hybrid"])
    parser.add_argument("--auth-policy", choices=("production", "cold-proof"), default="production")
    parser.add_argument("--seed", type=int, default=20261021)
    parser.add_argument("--work-root", type=Path, default=Path("/tmp/opencode/archivetune-resolver-matrix"))
    parser.add_argument("--output", type=Path, default=Path("build/resolver-validation/resolver-matrix.json"))
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--build-only", action="store_true", help="Prepare identical instrumented builds without starting timed applications.")
    parser.add_argument("--native-failure-fixture", action="store_true",
                        help="Separate controlled-fallback benchmark, never mixed with natural hit-rate results.")
    parser.add_argument("--ytdlp", type=Path, default=Path(".tools/resolver/yt-dlp"))
    parser.add_argument("--bun", type=Path, default=Path(".tools/resolver/bun"))
    args = parser.parse_args()
    if args.rounds < 1 or args.repeats < 1 or not args.videos or any(not re.fullmatch(r"[A-Za-z0-9_-]{11}", video) for video in args.videos):
        parser.error("Positive rounds/repeats and valid public-video IDs are required")
    repo = Path(__file__).resolve().parents[1]
    root = args.work_root.resolve()
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(root, 0o700)
    candidate = BASE.git_commit_for(repo, args.candidate_ref)
    harness_commit = BASE.git_head(repo)
    BASE.git_output(repo, "cat-file", "-e", candidate + ":desktop-backend/src/main/kotlin/moe/rukamori/archivetune/innertube/DesktopStreamResolutionPolicy.kt")
    revisions = {"legacy": BASE.git_commit_for(repo, args.baseline_ref),
                 "ytdlp-original": BASE.git_commit_for(repo, args.original_ytdlp_ref),
                 "ytdlp-optimized": candidate, "hybrid": candidate}
    tools = {"engine": "bun", "yt_dlp": args.ytdlp.resolve(), "bun": args.bun.resolve(),
             "yt_dlp_version": BASE.tool_version(args.ytdlp.resolve()), "bun_version": BASE.tool_version(args.bun.resolve())}
    if tools["bun_version"] != "1.4.3" or tools["yt_dlp_version"] != "2026.08.19":
        raise RuntimeError("Unexpected resolver tool version")
    hashes = {name: hashlib.sha256(tools[name].read_bytes()).hexdigest() for name in ("yt_dlp", "bun")}
    if hashes != {"yt_dlp": "58162f9bfdc27458ea47bfcb311cf47028f17d8154a8bf7d689861d46399230a",
                  "bun": "7ce7d6b654eddeec20afddf2396317fba6dc19ab52788a27e894e1806bf8f24b"}:
        raise RuntimeError("Resolver binary digest does not match the pinned release")
    store = AUTH.SecretServiceSessions(AUTH.SOURCE_SESSION_PATH)
    if not store.source_present:
        raise RuntimeError("Signed-in source session missing; refusing anonymous substitution")
    AUTH.INSTRUMENTATION_CONFIG["block"] = hook(args.videos, args.repeats, "BaW_jenozKc", args.auth_policy, args.native_failure_fixture)
    BASE.inject_instrumentation = AUTH.inject_authenticated_instrumentation
    prepared = {}
    for revision in dict.fromkeys(revisions[mode] for mode in args.modes):
        checkout = prepare_checkout(repo, revision, root / revision[:12])
        for line in BASE.git_output(repo, "ls-tree", revision).splitlines():
            details, name = line.split("\t", 1)
            if details.startswith("160000 ") and BASE.git_head(checkout / name) != details.split()[2]:
                raise RuntimeError(f"Submodule differs from committed tree: {name}")
        # Validate against THIS requested hook, not merely equality of stale hooks across arms.
        with tempfile.TemporaryDirectory(dir=root) as temporary:
            expected_main = Path(temporary) / "Main.kt"
            expected_main.write_bytes(subprocess.run(["git", "-C", str(checkout), "show", revision + ":" + str(BASE.MAIN_KT_RELATIVE)],
                                                     check=True, stdout=subprocess.PIPE).stdout)
            AUTH.inject_authenticated_instrumentation(expected_main)
            expected_hash = BASE.sha256_file(expected_main)
        classpath, metadata = BASE.build_checkout(checkout=checkout, work_root=root,
            java_home=Path("/usr/lib/jvm/java-21-openjdk"), skip_build=args.skip_build, label=revision[:12])
        prepared[revision] = checkout, classpath, metadata
        if metadata["main_kt_sha256"] != expected_hash:
            raise RuntimeError("Stale benchmark hook; use a fresh work root")
    if len({item[2]["main_kt_sha256"] for item in prepared.values()}) != 1:
        raise RuntimeError("Instrumentation parity failed")
    if args.build_only:
        print("Prepared identical instrumented builds; no application or extraction started", flush=True)
        return 0
    runs, schedule = [], []
    for round_number in range(args.rounds):
        order = args.modes[round_number % len(args.modes):] + args.modes[:round_number % len(args.modes)]
        videos = list(args.videos)
        random.Random(args.seed + round_number).shuffle(videos)
        os.environ["ARCHIVETUNE_BENCH_VIDEOS"] = ",".join(videos)
        schedule.append({"round": round_number, "modes": order, "videos": videos})
        for mode in order:
            os.environ.pop("ARCHIVETUNE_BENCH_NATIVE_FAILURE", None)
            if args.native_failure_fixture and mode == "hybrid":
                os.environ["ARCHIVETUNE_BENCH_NATIVE_FAILURE"] = "1"
            revision = revisions[mode]
            checkout, classpath, metadata = prepared[revision]
            resolver = None if mode == "legacy" else {**tools, "policy": "hybrid" if mode == "hybrid" else "ytdlp"}
            print(f"Round {round_number + 1}: {mode}", flush=True)
            started_utc = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
            load_before = os.getloadavg()
            run = AUTH.run_application(checkout=checkout, classpath_file=classpath, version=mode,
                commit=revision, backend=mode, run_dir=root / "runs" / f"{round_number}-{mode}",
                xvfb_path=Path("/tmp/opencode/archivetune-bench/tools/usr/bin/Xvfb"),
                java=Path("/usr/lib/jvm/java-21-openjdk/bin/java"), videos=videos, repeats=args.repeats,
                sample_interval_ms=25, pss_interval_ms=100, app_timeout_s=600, resolver=resolver, session_store=store)
            runs.append(safe_run(run, mode, round_number, len(videos) * args.repeats, metadata))
            runs[-1]["host_context"] = {"started_utc": started_utc,
                                        "loadavg_before": load_before, "loadavg_after": os.getloadavg()}
            document = {"schema": "archivetune.resolver-matrix/1", "seed": args.seed, "schedule": schedule,
                        "harness_commit": harness_commit, "measurement_valid": all(valid_measurement(item) for item in runs),
                        "native_failure_fixture": args.native_failure_fixture,
                        "tool_sha256": hashes,
                        "auth_policy": args.auth_policy, "source_entry_unchanged": store.source_unchanged(),
                        "core_commits": {rev: BASE.git_output(repo, "ls-tree", rev, "core") for rev in prepared},
                        "quantiles": "p50=median; p90/p95=nearest rank; n is explicit; no failed/missing attempts dropped",
                        "cold_definition": "Fresh app/profile/cache per mode/run; OS page cache not flushed; browser control runs after extraction",
                        "runs": runs, "summary": summary(runs)}
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(document, indent=2) + "\n")
            print(f"  success {run['resolution']['successes']}/{len(videos) * args.repeats}; playback={run['playback']['all_checks_passed']}", flush=True)
            if not valid_measurement(runs[-1]):
                raise RuntimeError("Invalid benchmark environment or cleanup; saved evidence, refusing further timed runs")
            if args.native_failure_fixture and runs[-1]["controlled_native_failure"] != (mode == "hybrid"):
                raise RuntimeError("Controlled-failure fixture was not applied to the requested arm")
    return 0 if all(run["authentication"]["guard_passed"] and run["session_clone"]["clone_key_cleared"]
                    and run["sampler_error"] is None and run["cpu"]["tracker_error"] is None
                    and run["exit_code"] == 0 and not run["harness_timeout"] and run["missing_attempts"] == 0
                    and run["authentication"]["browser_proof"]["browser_teardown_observed"]
                    and run["authentication"]["browser_proof"]["cold_successes"] == run["authentication"]["browser_proof"]["cold_attempts"] > 0
                    and run["cleanup"]["no_helpers_in_post_idle"] and run["cleanup"]["no_helpers_after_shutdown"]
                    and run["cleanup"]["private_home_removed"]
                    and run["phases"]["extraction"]["complete_pss_samples"] > 0
                    and len(run["records"]) == run["expected_attempts"] and all(record["success"] for record in run["records"])
                    and all(record["bitrate"] and isinstance(record["has_audio_config"], bool) for record in run["records"])
                    and run["playback"]["all_checks_passed"]
                    and (run["mode"] == "legacy" or run["resolver"]["policy"] == ("hybrid" if run["mode"] == "hybrid" else "ytdlp"))
                    for run in runs) and store.source_unchanged() and all(
                        (value["fallback_successes"] > 0 if args.native_failure_fixture else value["fastpath_successes"] > 0)
                        for name, value in summary(runs).items() if name == "hybrid") else 1


if __name__ == "__main__":
    raise SystemExit(main())

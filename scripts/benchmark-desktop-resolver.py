#!/usr/bin/env python3
"""Compare the actual desktop resolver APIs in isolated JVMs; no cookies or signed URLs in reports.

First run :desktop-backend:writeResolverBenchmarkClasspath, then invoke this from the repo root.
RSS is sampled from Linux /proc for the JVM and every descendant, not Gradle or other applications.
"""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import threading
import time


def rss_tree(root_pid):
    processes = {}
    for entry in Path('/proc').iterdir():
        if not entry.name.isdigit():
            continue
        try:
            fields = (entry / 'stat').read_text().rsplit(') ', 1)[1].split()
            processes[int(entry.name)] = (int(fields[1]), int(fields[21]) * os.sysconf('SC_PAGE_SIZE'))
        except (OSError, ValueError, IndexError):
            pass
    children = {root_pid}
    while True:
        expanded = children | {pid for pid, (parent, _) in processes.items() if parent in children}
        if expanded == children:
            break
        children = expanded
    return (sum(processes.get(pid, (0, 0))[1] for pid in children),
            processes.get(root_pid, (0, 0))[1],
            sum(processes.get(pid, (0, 0))[1] for pid in children if pid != root_pid))


def run(args, backend, video):
    command = [args.java, '-Xms32m', '-Xmx512m', '-cp', Path(args.classpath).read_text().strip(),
               'moe.rukamori.archivetune.innertube.ResolverBenchmarkMainKt', backend, video, str(args.repeats)]
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                               text=True, start_new_session=True)
    records = []
    # Drain even verbose legacy stdout immediately; persist only deliberately redacted harness records.
    def drain():
        for line in process.stdout:
            if line.startswith('ARCHIVETUNE_BENCHMARK\t'):
                try:
                    records.append(json.loads(line.split('\t', 1)[1]))
                except ValueError:
                    pass
    reader = threading.Thread(target=drain, daemon=True)
    reader.start()
    peak_total = peak_jvm = peak_children = 0
    deadline = time.monotonic() + args.timeout * args.repeats
    timed_out = False
    try:
        while process.poll() is None:
            total, jvm, children = rss_tree(process.pid)
            peak_total, peak_jvm, peak_children = max(peak_total, total), max(peak_jvm, jvm), max(peak_children, children)
            if time.monotonic() > deadline:
                timed_out = True
                break
            time.sleep(0.02)
    finally:
        # Also terminates any orphaned children in the process group on normal/error exits.
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait(timeout=5)
        reader.join(timeout=5)
        process.stdout.close()
    report = {'backend': backend, 'video_id': video, 'records': records,
              'peak_tree_rss_mib': round(peak_total / 1048576, 2),
              'peak_jvm_rss_mib': round(peak_jvm / 1048576, 2),
              'peak_children_rss_mib': round(peak_children / 1048576, 2),
              'exit_code': process.returncode, 'harness_timeout': timed_out}
    print(json.dumps(report), flush=True)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default='/usr/lib/jvm/java-21-openjdk/bin/java')
    parser.add_argument('--classpath', default='desktop-backend/build/resolver-benchmark-classpath.txt')
    parser.add_argument('--videos', nargs='+', default=['jNQXAC9IVRw', 'dQw4w9WgXcQ', 'aqz-KE-bpKQ'])
    parser.add_argument('--backends', nargs='+', choices=['legacy', 'ytdlp'], default=['legacy', 'ytdlp'])
    parser.add_argument('--repeats', type=int, default=2)
    parser.add_argument('--timeout', type=int, default=60)
    parser.add_argument('--output', default='build/resolver-validation/desktop-benchmark.json')
    args = parser.parse_args()
    if args.repeats < 1 or args.timeout < 1:
        parser.error('repeats and timeout must be positive')
    if any(len(video) != 11 or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-' for c in video) for video in args.videos):
        parser.error('videos must be 11-character IDs')
    reports = [run(args, backend, video) for video in args.videos for backend in args.backends]
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({'sample_interval_ms': 20, 'results': reports}, indent=2) + '\n')


if __name__ == '__main__':
    main()

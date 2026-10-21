#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Provision and verify an official Bun release for the JS-runtime benchmark.

This is a developer-only helper. It never touches a system-wide Bun
installation: everything is downloaded into a caller-supplied directory
(the benchmark uses the git-ignored ``build/tools/runtimes``) and the
resulting provenance JSON is handed to ``scripts/benchmark-ytdlp-runtimes.py``
via ``--artifact-provenance``.

Provenance chain that is established and recorded
-------------------------------------------------
1. The release is resolved from the **primary upstream source**: the
   ``oven-sh/bun`` GitHub release that GitHub marks as the latest *stable*
   release (``prerelease == false`` and ``draft == false``). The version is
   never hard-coded here; ``--expect-version`` only *asserts* it.
2. Every downloaded file is hashed and compared with the digest that the
   GitHub release API publishes for that asset.
3. The platform archive is hashed and compared with the checksum published in
   the release's ``SHASUMS256.txt``.
4. ``SHASUMS256.txt.asc`` is a clearsigned document. Its OpenPGP signature is
   verified against the Bun release signing key
   (``F3DCC08A8572C0749B3E18888EAB4D40A7B22B59``), and the clearsigned payload
   is compared byte for byte with the separately downloaded
   ``SHASUMS256.txt``, so the checksum list that was verified is provably the
   one the release signer signed.

Safety contract
---------------
* Nothing is installed system wide, nothing is added to ``PATH``, and no
  existing Bun installation is replaced.
* The script only writes inside ``--dest-dir`` and a throw-away
  ``GNUPGHOME`` under the system temporary directory.

Usage
-----
    scripts/fetch-bun-release.py \
        --dest-dir build/tools/runtimes \
        --expect-version 1.4.3 \
        --out-json build/tools/runtimes/bun-provenance.json

Exit code is 0 only when every verification step succeeded.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
from typing import Any, Dict, List, Optional

# Primary upstream source of the release metadata.
GITHUB_API = "https://api.github.com/repos/oven-sh/bun/releases"
RELEASE_DOWNLOAD = "https://github.com/oven-sh/bun/releases/download/%s/%s"

# Bun signs its release checksum list with this key.
BUN_RELEASE_SIGNING_KEY_FPR = "F3DCC08A8572C0749B3E18888EAB4D40A7B22B59"
BUN_RELEASE_SIGNING_KEY_URL = (
    "https://keys.openpgp.org/vks/v1/by-fingerprint/" + BUN_RELEASE_SIGNING_KEY_FPR)


def _http_get(url: str, timeout: float = 300.0) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "archivetune-bench/1"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read()


def _http_get_json(url: str, timeout: float = 60.0) -> Dict[str, Any]:
    return json.loads(_http_get(url, timeout=timeout).decode("utf-8"))


def sha256_of(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _platform_asset_name() -> str:
    machine = platform.machine().lower()
    arch = {"x86_64": "x64", "amd64": "x64", "aarch64": "aarch64", "arm64": "aarch64"}.get(machine)
    if not arch:
        raise SystemExit("unsupported architecture: %s" % machine)
    system = platform.system().lower()
    if system != "linux":
        raise SystemExit("unsupported platform: %s" % system)
    return "bun-linux-%s.zip" % arch


def _download(url: str, target: str) -> None:
    print("[fetch] %s" % url)
    with urllib.request.urlopen(url, timeout=300) as response, open(target, "wb") as handle:
        shutil.copyfileobj(response, handle)


def _verify_gpg(asc_path: str, workdir: str) -> Dict[str, Any]:
    """Verify the clearsigned checksum list against the Bun release key."""
    gpg = shutil.which("gpg")
    if not gpg:
        return {"verified": False, "error": "gpg is not available"}
    gnupghome = tempfile.mkdtemp(prefix="archivetune-bun-gnupg-")
    os.chmod(gnupghome, 0o700)
    env = dict(os.environ)
    env["GNUPGHOME"] = gnupghome
    try:
        key_path = os.path.join(workdir, "bun-release-signing-key.asc")
        _download(BUN_RELEASE_SIGNING_KEY_URL, key_path)
        imported = subprocess.run([gpg, "--batch", "--import", key_path],
                                  env=env, capture_output=True, text=True)
        fingerprints = subprocess.run([gpg, "--batch", "--list-keys", "--with-colons",
                                       BUN_RELEASE_SIGNING_KEY_FPR],
                                      env=env, capture_output=True, text=True)
        if BUN_RELEASE_SIGNING_KEY_FPR not in fingerprints.stdout:
            return {"verified": False,
                    "error": "signing key %s not found in the imported keyring"
                             % BUN_RELEASE_SIGNING_KEY_FPR,
                    "import_stderr": imported.stderr.strip()[:400]}
        verified = subprocess.run([gpg, "--batch", "--verify", asc_path],
                                  env=env, capture_output=True, text=True)
        return {
            "verified": verified.returncode == 0,
            "key_fingerprint": BUN_RELEASE_SIGNING_KEY_FPR,
            "key_source": BUN_RELEASE_SIGNING_KEY_URL,
            "gpg_stdout": verified.stdout.strip()[:600],
            "gpg_stderr": verified.stderr.strip()[:600],
        }
    finally:
        shutil.rmtree(gnupghome, ignore_errors=True)


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dest-dir", default="build/tools/runtimes")
    parser.add_argument("--out-json", default="")
    parser.add_argument("--expect-version", default="",
                        help="assert the resolved release version (never used to select it)")
    parser.add_argument("--skip-download", action="store_true",
                        help="only re-verify files that are already in --dest-dir")
    args = parser.parse_args(argv)

    dest = os.path.abspath(args.dest_dir)
    os.makedirs(dest, exist_ok=True)

    release = _http_get_json(GITHUB_API + "/latest")
    if release.get("draft") or release.get("prerelease"):
        print("ERROR: the latest GitHub release is not a stable release", file=sys.stderr)
        return 2
    tag = release.get("tag_name") or ""
    version = tag.replace("bun-v", "").replace("v", "")
    print("[release] %s (%s) published %s" % (tag, release.get("name"), release.get("published_at")))

    if args.expect_version and args.expect_version != version:
        print("ERROR: upstream latest stable is %s, expected %s" % (version, args.expect_version),
              file=sys.stderr)
        return 2

    asset_name = _platform_asset_name()
    assets = {asset["name"]: asset for asset in release.get("assets", [])}
    wanted = ["bun-linux-x64.zip" if asset_name == "bun-linux-x64.zip" else asset_name,
              "SHASUMS256.txt", "SHASUMS256.txt.asc"]
    if asset_name not in assets:
        print("ERROR: release %s has no asset %s" % (tag, asset_name), file=sys.stderr)
        return 2

    paths = {name: os.path.join(dest, name) for name in wanted}
    if not args.skip_download:
        for name in wanted:
            _download(RELEASE_DOWNLOAD % (tag, name), paths[name])

    provenance: Dict[str, Any] = {
        "schema": "archivetune.bun-release-provenance/1",
        "generated_at_utc": None,
        "release": {
            "repository": "oven-sh/bun",
            "tag": tag,
            "version": version,
            "name": release.get("name"),
            "published_at_utc": release.get("published_at"),
            "prerelease": release.get("prerelease"),
            "draft": release.get("draft"),
            "html_url": release.get("html_url"),
            "api_url": GITHUB_API + "/latest",
            "release_notes": (release.get("body") or "")[:2000],
        },
        "asset": {
            "name": asset_name,
            "download_url": RELEASE_DOWNLOAD % (tag, asset_name),
            "api_digest_sha256": (assets.get(asset_name, {}).get("digest") or "").split(":")[-1],
            "api_size_bytes": assets.get(asset_name, {}).get("size"),
        },
        "files": {},
    }

    ok = True
    for name in wanted:
        entry = {
            "url": RELEASE_DOWNLOAD % (tag, name),
            "api_digest_sha256": (assets.get(name, {}).get("digest") or "").split(":")[-1],
            "local_sha256": sha256_of(paths[name]),
            "size_bytes": os.path.getsize(paths[name]),
        }
        if entry["api_digest_sha256"]:
            entry["matches_github_api_digest"] = entry["local_sha256"] == entry["api_digest_sha256"]
            ok = ok and entry["matches_github_api_digest"]
        else:
            entry["matches_github_api_digest"] = None
        provenance["files"][name] = entry

    # -- checksum list ----------------------------------------------------
    with open(paths["SHASUMS256.txt"], "r") as handle:
        sums = {}
        for line in handle:
            parts = line.split()
            if len(parts) == 2:
                sums[parts[1].lstrip("*")] = parts[0]
    expected = sums.get(asset_name)
    actual = provenance["files"][asset_name]["local_sha256"]
    provenance["files"][asset_name]["published_sha256"] = expected
    provenance["files"][asset_name]["matches_published_sha256"] = bool(expected) and expected == actual
    ok = ok and provenance["files"][asset_name]["matches_published_sha256"]

    # -- signature ---------------------------------------------------------
    gpg = _verify_gpg(paths["SHASUMS256.txt.asc"], dest)
    provenance["shasums_signature"] = gpg
    ok = ok and bool(gpg.get("verified"))

    # The clearsigned payload must be the checksum list we verified.
    gnupghome = tempfile.mkdtemp(prefix="archivetune-bun-gnupg-")
    os.chmod(gnupghome, 0o700)
    try:
        env = dict(os.environ)
        env["GNUPGHOME"] = gnupghome
        key_path = os.path.join(dest, "bun-release-signing-key.asc")
        subprocess.run(["gpg", "--batch", "--import", key_path], env=env, capture_output=True)
        payload = os.path.join(dest, "SHASUMS256.signed-payload.txt")
        subprocess.run(["gpg", "--batch", "--yes", "--output", payload, "--decrypt",
                        paths["SHASUMS256.txt.asc"]], env=env, capture_output=True)
        identical = (os.path.exists(payload)
                     and sha256_of(payload) == sha256_of(paths["SHASUMS256.txt"]))
    finally:
        shutil.rmtree(gnupghome, ignore_errors=True)
    provenance["shasums_signature"]["clearsigned_payload_matches_downloaded_shasums"] = identical
    ok = ok and identical

    # -- extract -----------------------------------------------------------
    extract_dir = os.path.join(dest, "bun-%s" % version)
    binary = os.path.join(extract_dir, asset_name[:-4], "bun")
    if not os.path.exists(binary):
        os.makedirs(extract_dir, exist_ok=True)
        with zipfile.ZipFile(paths[asset_name]) as archive:
            archive.extractall(extract_dir)
    if not os.path.exists(binary):
        print("ERROR: %s not found in %s" % ("bun", asset_name), file=sys.stderr)
        return 2
    binary = os.path.realpath(binary)
    os.chmod(binary, 0o755)
    reported = subprocess.run([binary, "--version"], capture_output=True, text=True)
    revision = subprocess.run([binary, "--revision"], capture_output=True, text=True)
    provenance["extracted_binary"] = {
        "path": binary,
        "sha256": sha256_of(binary),
        "size_bytes": os.path.getsize(binary),
        "reported_version": (reported.stdout or reported.stderr).strip(),
        "reported_revision": (revision.stdout or revision.stderr).strip(),
        "version_matches_release": (reported.stdout or reported.stderr).strip() == version,
    }
    ok = ok and provenance["extracted_binary"]["version_matches_release"]

    provenance["verified"] = ok
    provenance["verified_at_utc"] = __import__("time").strftime("%Y-%m-%dT%H:%M:%SZ",
                                                                __import__("time").gmtime())
    provenance["generated_at_utc"] = provenance["verified_at_utc"]
    out_path = os.path.abspath(args.out_json) if args.out_json else os.path.join(
        dest, "bun-%s-provenance.json" % version)
    with open(out_path, "w") as handle:
        json.dump(provenance, handle, indent=2, sort_keys=True)
        handle.write("\n")
    print("[ok] provenance written to %s (verified=%s)" % (out_path, ok))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

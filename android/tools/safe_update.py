#!/usr/bin/env python3
"""Back up a debuggable installed Rihla and install only a compatible signed update.

Requires Android SDK adb, aapt2 and apksigner on PATH. Never uninstalls the app.
Backups contain personal journeys: keep them private and outside Git.
"""
import argparse
import hashlib
import io
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import tarfile
import tempfile
from datetime import datetime, timezone

PACKAGE = "sa.rihla"

def command(*args):
    return subprocess.run(args, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout

def apk_info(path):
    metadata = command("aapt2", "dump", "badging", str(path)).decode()
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)'", metadata)
    signature = command("apksigner", "verify", "--print-certs", str(path)).decode()
    certs = frozenset(cert.lower() for cert in re.findall(
        r"(?:Signer #\d+|V\d+ Signer):? certificate SHA-256 digest: ([0-9a-fA-F]{64})", signature))
    if not package or not certs:
        raise ValueError("Could not verify APK identity/signature")
    return package[1], int(package[2]), certs

def compatible(installed, candidate):
    if installed[0] != PACKAGE or candidate[0] != PACKAGE:
        raise ValueError("Unexpected application ID; installed app is unchanged")
    if installed[2] != candidate[2]:
        raise ValueError("Signing certificates differ. Backup saved; update refused. Do not uninstall the old app.")
    if candidate[1] <= installed[1]:
        raise ValueError("Candidate version must be newer; update refused")

def validate_backup(archive):
    with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
        members = {m.name.removeprefix("./"): m for m in tar.getmembers()}
        if "databases/journeys.db" not in members:
            raise ValueError("Backup does not contain journeys.db; update refused")
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            for name in ("journeys.db", "journeys.db-wal", "journeys.db-shm"):
                member = members.get("databases/" + name)
                if member:
                    if not member.isfile():
                        raise ValueError("Invalid database archive entry")
                    (directory / name).write_bytes(tar.extractfile(member).read())
            with sqlite3.connect(directory / "journeys.db") as database:
                if database.execute("PRAGMA integrity_check").fetchone() != ("ok",):
                    raise ValueError("Backup database failed integrity check; update refused")
                if database.execute("PRAGMA foreign_key_check").fetchall():
                    raise ValueError("Backup has invalid references; update refused")
                return database.execute("SELECT COUNT(*) FROM outings").fetchone()[0]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--backup-dir", type=Path, required=True)
    parser.add_argument("--serial", help="adb device serial when multiple devices are attached")
    parser.add_argument("--backup-only", action="store_true")
    args = parser.parse_args()
    adb = ["adb"] + (["-s", args.serial] if args.serial else [])
    candidate = apk_info(args.apk.resolve())
    if candidate[0] != PACKAGE:
        raise ValueError("Unexpected candidate application ID")
    paths = command(*adb, "shell", "pm", "path", PACKAGE).decode().splitlines()
    base = next((p.removeprefix("package:") for p in paths if p.endswith("/base.apk")), None)
    if not base:
        raise ValueError("Installed Rihla base APK was not found")
    with tempfile.TemporaryDirectory() as temporary:
        installed_path = Path(temporary) / "installed.apk"
        command(*adb, "pull", base, str(installed_path))
        installed = apk_info(installed_path)
    # Stop writes first so DB + WAL + preferences represent a stable snapshot.
    command(*adb, "shell", "am", "force-stop", PACKAGE)
    archive = command(*adb, "exec-out", "run-as", PACKAGE, "tar", "-cf", "-", "databases", "files")
    count = validate_backup(archive)
    args.backup_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    filename = args.backup_dir / ("rihla-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ") + ".tar")
    descriptor = os.open(filename, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as output:
        output.write(archive)
        output.flush()
        os.fsync(output.fileno())
    if hashlib.sha256(filename.read_bytes()).digest() != hashlib.sha256(archive).digest():
        raise ValueError("Backup file verification failed; update refused")
    print(f"Verified private backup: {filename} ({count} outings)")
    if args.backup_only:
        return
    compatible(installed, candidate)
    result = command(*adb, "install", "-r", str(args.apk.resolve())).decode()
    if "Success" not in result.splitlines():
        raise ValueError("Android did not confirm a successful update; retain the backup")
    print("Update installed with retained app data. Open Rihla and check your outings.")

if __name__ == "__main__":
    try:
        main()
    except (ValueError, subprocess.CalledProcessError, OSError, sqlite3.Error, tarfile.TarError) as error:
        # Do not echo adb output that could include personal filesystem contents.
        raise SystemExit(f"Safe update stopped: {error}")

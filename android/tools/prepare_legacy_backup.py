#!/usr/bin/env python3
"""Prepare a portable local backup from the original debug app. Never uninstalls.

Runs on a computer or Termux on the phone after authorized wireless ADB pairing.
Requires only adb and Python's standard library. Finish the old outing first.
"""
import argparse
from datetime import datetime, timezone
import io
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import tarfile
import tempfile

TABLES = ("outings", "points", "events", "places", "expenses", "fuel", "active_slot", "imported_sms", "trip_details")

def portable_backup(archive):
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
            members = {member.name.removeprefix("./"): member for member in tar.getmembers()}
            if "databases/journeys.db" not in members:
                raise ValueError("Original journeys database is missing")
            for filename in ("journeys.db", "journeys.db-wal", "journeys.db-shm"):
                member = members.get("databases/" + filename)
                if member:
                    if not member.isfile():
                        raise ValueError("Invalid backup entry")
                    (root / filename).write_bytes(tar.extractfile(member).read())
        with sqlite3.connect(root / "journeys.db") as database:
            if database.execute("PRAGMA integrity_check").fetchone() != ("ok",) or database.execute("PRAGMA foreign_key_check").fetchall():
                raise ValueError("Original backup failed database integrity checks")
            version = database.execute("PRAGMA user_version").fetchone()[0]
            if version not in range(1, 6):
                raise ValueError("Unsupported original database version")
            names = {row[0] for row in database.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            if not {"outings", "points", "expenses"}.issubset(names):
                raise ValueError("Incomplete original database")
            database.row_factory = sqlite3.Row
            tables = {name: [dict(row) for row in database.execute(f'SELECT * FROM "{name}" ORDER BY rowid')]
                      if name in names else [] for name in TABLES}
    return {"format": "RIHLA_BACKUP", "formatVersion": 1, "schemaVersion": version,
            "exportedAt": int(datetime.now(timezone.utc).timestamp() * 1000), "tables": tables}

def write_private(path, data):
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as output:
        output.write(data)
        output.flush()
        os.fsync(output.fileno())

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--serial")
    args = parser.parse_args()
    original = args.output.with_suffix(".original.tar")
    if args.output.exists() or original.exists():
        raise ValueError("Choose a new backup filename; existing backups are never overwritten")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    adb = ["adb"] + (["-s", args.serial] if args.serial else [])
    def run(*command):
        return subprocess.run([*adb, *command], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout
    run("shell", "run-as", "sa.rihla", "id") # Require the original debuggable installation.
    run("shell", "am", "force-stop", "sa.rihla")
    archive = run("exec-out", "run-as", "sa.rihla", "tar", "-cf", "-", "databases", "files")
    payload = portable_backup(archive)
    write_private(original, archive) # Retain the exact original database, WAL and settings.
    write_private(args.output, json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8"))
    print(f"Verified backup saved: {args.output}")
    print(f"Outings: {len(payload['tables']['outings'])}. Original installation retained.")
    print("Open مشواري 2.0 → More → Restore local backup, then choose this JSON file.")

if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError, sqlite3.Error, tarfile.TarError) as error:
        raise SystemExit(f"Backup preparation stopped safely: {error}")

#!/usr/bin/env python3
"""Check the explicit Android asset-source contract after APK packaging."""
import argparse
from pathlib import Path
from zipfile import ZipFile

REQUIRED = {"assets/maps/saudi-roads.pack", "assets/maps/NOTICE.txt"}
EXCLUDED = {"assets/maps/jeddah-makkah.mbtiles", "assets/maps/saudi-roads.db"}


def verify_assets(apk: Path) -> None:
    with ZipFile(apk) as archive:
        names = set(archive.namelist())
        if not REQUIRED <= names:
            raise ValueError(f"Required assets missing: {sorted(REQUIRED - names)}")
        if names & EXCLUDED:
            raise ValueError(f"Legacy source data packaged: {sorted(names & EXCLUDED)}")
        if any(archive.getinfo(name).file_size == 0 for name in REQUIRED):
            raise ValueError("Required asset is empty")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apks", type=Path, nargs="+")
    for artifact in parser.parse_args().apks:
        verify_assets(artifact)
        print(f"PASS: explicit packaged asset contract: {artifact.name}")

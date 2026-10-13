#!/usr/bin/env python3
"""Package previously downloaded official archives after exact checksum verification."""
import argparse
import hashlib
from pathlib import Path
import tempfile
import zipfile

EXPECTED = {
    "python.zip": "f6cca216a359be84797cabb54149ce5e062afb16cc7567eb7fc51cacb2d86b65",
    "adb.zip": "45f4d63113e895ebde0c90f194099a4676b6ac653bd28d54314a9e022bbc1a99",
}


def build(downloads, output):
    for name, checksum in EXPECTED.items():
        if hashlib.sha256((downloads / name).read_bytes()).hexdigest() != checksum:
            raise ValueError("Official archive checksum mismatch: " + name)
    tools = Path(__file__).resolve().parent
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        with zipfile.ZipFile(downloads / "python.zip") as source:
            source.extractall(root / "python")
        with zipfile.ZipFile(downloads / "adb.zip") as source:
            source.extractall(root)
        (root / "tools").mkdir()
        for name in ("transfer_windows.py", "prepare_legacy_backup.py"):
            (root / "tools" / name).write_bytes((tools / name).read_bytes())
        (root / "START-TRANSFER.cmd").write_bytes((tools / "START-TRANSFER.cmd").read_bytes().replace(b"\r\n", b"\n").replace(b"\n", b"\r\n"))
        (root / "READ-ME.txt").write_text(
            "مشواري — نقل باستخدام USB\n"
            "استخرج الحزمة أولًا، ثم افتح START-TRANSFER.cmd.\n"
            "أنه المشوار القديم، فعّل تصحيح USB، وافق على اتصال اللابتوب.\n"
            "لا تحذف التطبيق القديم. لا تضف بيانات إلى 2.0 قبل الاستيراد.\n"
            "الدليل الكامل:\n"
            "https://github.com/m7mdxzx9/-/blob/main/android/docs/WINDOWS_MIGRATION.md\n"
            "Windows 10/11 x64. Includes official Python 3.13.7 and Android Platform Tools 37.0.1.\n"
            "No internet required after download. Original licenses are included.\n",
            encoding="utf-8-sig")
        paths = sorted(path for path in root.rglob("*") if path.is_file())
        (root / "SHA256SUMS.txt").write_text("".join(
            hashlib.sha256(path.read_bytes()).hexdigest() + "  " + path.relative_to(root).as_posix() + "\n"
            for path in paths), encoding="utf-8")
        output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as bundle:
            for path in sorted(root.rglob("*")):
                if path.is_file():
                    bundle.write(path, path.relative_to(root).as_posix())
    print(f"Verified Windows bundle: {output}, {output.stat().st_size} bytes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("downloads", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    build(args.downloads, args.output)

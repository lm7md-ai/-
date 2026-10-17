#!/usr/bin/env python3
"""Reproduce the pinned ARM64 source-separation binaries; never executes downloads."""
import argparse
import hashlib
import shutil
import tarfile
import tempfile
import urllib.request
from pathlib import Path

ARCHIVE_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-v1.13.8-android-aarch64-termux-static.tar.bz2"
ARCHIVE_SHA = "e212d6c41a55b3d0ef0a7926796b4fa48d42ea173b759fafff60ced024dd0883"
BINARY_SHA = "3043e4fd48c19faf38756decc7839a440f450324527b8b9ee8665940bd845c99"
STL_SHA = "ab4e6c71b96b851de45a8a9bd86369e7dbc2130a44b3b4520564be94847910f2"

def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", required=True, type=Path, help="Android NDK 28.2.13676358 root")
    args = parser.parse_args()
    destination = Path(__file__).resolve().parents[1] / "app/src/main/jniLibs/arm64-v8a"
    stl = args.ndk / "toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
    if digest(stl) != STL_SHA:
        raise ValueError("NDK shared C++ runtime differs from the pinned 28.2.13676358 input")
    with tempfile.TemporaryDirectory() as directory:
        archive = Path(directory) / "sherpa.tar.bz2"
        urllib.request.urlretrieve(ARCHIVE_URL, archive)
        if digest(archive) != ARCHIVE_SHA:
            raise ValueError("Upstream archive checksum mismatch")
        with tarfile.open(archive, "r:bz2") as tar:
            matches = [m for m in tar.getmembers() if m.isfile() and m.name.endswith("/bin/sherpa-onnx-offline-source-separation")]
            if len(matches) != 1:
                raise ValueError("Unexpected native archive layout")
            binary = Path(directory) / "separate"
            with tar.extractfile(matches[0]) as source, binary.open("wb") as output:
                shutil.copyfileobj(source, output)
        if digest(binary) != BINARY_SHA:
            raise ValueError("Native executable checksum mismatch")
        destination.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(binary, destination / "libmashwari_separate.so")
        shutil.copyfile(stl, destination / "libc++_shared.so")
    print("PASS: pinned ARM64 PIE executable and matching C++ runtime restored")

if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Verify the retained signing key and the explicitly selected edition before publication."""
import argparse
from pathlib import Path
from safe_update import apk_info, compatible

RELEASE_PACKAGE = "sa.rihla.next"
RELEASE_CERTIFICATE = "c82d020159412f6dac6f0675f1b826f7335730bbe9b647224644f94937da8b34"

def verify_identity(identity, edition="next", previous=None):
    package, version, certificates = identity
    expected = RELEASE_PACKAGE if edition == "next" else "sa.rihla"
    if edition not in ("next", "classic") or package != expected or version < 30:
        raise ValueError("Unexpected edition/package or version below 3.0")
    if certificates != {RELEASE_CERTIFICATE}:
        raise ValueError("Signing key changed. Rebuild with the saved key through RIHLA_SIGNING_KEYSTORE; never publish an incompatible update.")
    if edition == "classic":
        if previous is None:
            raise ValueError("A verified previous APK is required before publishing an in-place update")
        compatible(previous, identity)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk")
    parser.add_argument("--edition", choices=("classic", "next"), required=True)
    parser.add_argument("--previous", help="Published baseline APK; required for the classic update")
    args = parser.parse_args()
    if Path(args.apk).stat().st_size >= 100 * 1024 * 1024:
        raise ValueError("APK exceeds GitHub's 100 MiB file limit; compact the offline map before publishing")
    verify_identity(apk_info(args.apk), args.edition, apk_info(args.previous) if args.previous else None)
    print("PASS: selected edition, valid signature, expected persistent certificate and increasing version")

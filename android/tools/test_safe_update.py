import io
from pathlib import Path
import sqlite3
import tarfile
import tempfile
import unittest
from unittest.mock import patch
from safe_update import apk_info, compatible, validate_backup

class SafeUpdateTest(unittest.TestCase):
    def test_supported_android_signer_output_formats(self):
        metadata = b"package: name='sa.rihla' versionCode='3' versionName='1.2'"
        for label in ("Signer #1", "V2 Signer:"):
            signature = (label + " certificate SHA-256 digest: " + "AB" * 32).encode()
            with patch("safe_update.command", side_effect=[metadata, signature]):
                self.assertEqual(("sa.rihla", 3, frozenset({"ab" * 32})), apk_info(Path("example.apk")))

    def test_missing_signer_certificate_is_rejected(self):
        with patch("safe_update.command", side_effect=[b"package: name='sa.rihla' versionCode='3'", b"No signer"]):
            with self.assertRaises(ValueError):
                apk_info(Path("example.apk"))

    def test_only_newer_same_package_same_signer_is_accepted(self):
        compatible(("sa.rihla", 1, {"old"}), ("sa.rihla", 3, {"old"}))
        compatible(("sa.rihla.next", 20, {"old"}), ("sa.rihla.next", 21, {"old"}))
        with self.assertRaises(ValueError):
            compatible(("sa.rihla", 1, {"old"}), ("sa.rihla.next", 20, {"old"}))
        for candidate in (("other.app", 3, {"old"}), ("sa.rihla", 3, {"new"}), ("sa.rihla", 1, {"old"})):
            with self.assertRaises(ValueError):
                compatible(("sa.rihla", 1, {"old"}), candidate)

    def test_backup_preserves_uncheckpointed_wal_rows(self):
        with tempfile.TemporaryDirectory() as folder:
            file = Path(folder) / "journeys.db"
            db = sqlite3.connect(file)
            try:
                db.execute("PRAGMA journal_mode=WAL")
                db.execute("PRAGMA wal_autocheckpoint=0")
                db.execute("CREATE TABLE outings (id INTEGER PRIMARY KEY)")
                db.executemany("INSERT INTO outings VALUES (?)", [(1,), (2,)])
                db.commit()
                buffer = io.BytesIO()
                with tarfile.open(fileobj=buffer, mode="w") as tar:
                    tar.add(file, arcname="databases/journeys.db")
                    tar.add(Path(str(file) + "-wal"), arcname="databases/journeys.db-wal")
                self.assertEqual(2, validate_backup(buffer.getvalue()))
            finally:
                db.close()

    def test_missing_database_blocks_installation(self):
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode="w"):
            pass
        with self.assertRaises(ValueError):
            validate_backup(buffer.getvalue())

if __name__ == "__main__":
    unittest.main()

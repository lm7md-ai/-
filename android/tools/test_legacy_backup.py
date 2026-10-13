import io
import sqlite3
import tarfile
import tempfile
from pathlib import Path
import unittest
from prepare_legacy_backup import portable_backup

class LegacyBackupTest(unittest.TestCase):
    def test_original_wal_records_and_arabic_values_survive_conversion(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "journeys.db"
            database = sqlite3.connect(path)
            try:
                database.execute("PRAGMA journal_mode=WAL")
                database.execute("PRAGMA wal_autocheckpoint=0")
                database.execute("PRAGMA user_version=1")
                database.execute("CREATE TABLE outings (id INTEGER PRIMARY KEY, startedAt INTEGER)")
                database.execute("CREATE TABLE points (id INTEGER PRIMARY KEY, outingId INTEGER)")
                database.execute("CREATE TABLE expenses (id INTEGER PRIMARY KEY, amountHalala INTEGER, category TEXT)")
                database.execute("INSERT INTO outings VALUES(1,1000)")
                database.execute("INSERT INTO expenses VALUES(1,2450,'OTHER')")
                database.commit()
                archive = io.BytesIO()
                with tarfile.open(fileobj=archive, mode="w") as tar:
                    tar.add(path, arcname="databases/journeys.db")
                    tar.add(Path(str(path) + "-wal"), arcname="databases/journeys.db-wal")
                result = portable_backup(archive.getvalue())
                self.assertEqual(1, result['schemaVersion'])
                self.assertEqual(2450, result['tables']['expenses'][0]['amountHalala'])
                self.assertEqual(1, result['tables']['outings'][0]['id'])
                self.assertEqual([], result['tables']['trip_details'])
            finally:
                database.close()

    def test_missing_original_database_is_rejected(self):
        archive = io.BytesIO()
        with tarfile.open(fileobj=archive, mode="w"):
            pass
        with self.assertRaises(ValueError):
            portable_backup(archive.getvalue())

if __name__ == '__main__':
    unittest.main()

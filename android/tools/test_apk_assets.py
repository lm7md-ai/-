import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile
from verify_apk_assets import EXCLUDED, REQUIRED, verify_assets


class ApkAssetsTest(unittest.TestCase):
    def archive(self, names):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        target = Path(temporary.name) / "fixture.apk"
        with ZipFile(target, "w") as output:
            for name in names:
                output.writestr(name, b"synthetic fixture")
        return target

    def test_regular_assets_and_country_pack_survive_packaging(self):
        verify_assets(self.archive(REQUIRED))

    def test_missing_regular_asset_and_legacy_sources_are_rejected(self):
        with self.assertRaises(ValueError):
            verify_assets(self.archive({"assets/maps/saudi-roads.pack"}))
        for excluded in EXCLUDED:
            with self.assertRaises(ValueError):
                verify_assets(self.archive(REQUIRED | {excluded}))


if __name__ == "__main__":
    unittest.main()

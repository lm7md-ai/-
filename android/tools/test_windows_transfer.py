import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock
from transfer_windows import transfer, usb_device


class WindowsTransferTest(unittest.TestCase):
    def test_only_one_authorized_usb_phone_is_accepted(self):
        self.assertEqual("phone123", usb_device("List of devices attached\nphone123 device usb:1\n"))
        for rows in ("", "phone123 unauthorized", "a device\nb device", "127.0.0.1:5555 device", "emulator-5554 device"):
            with self.subTest(rows=rows), self.assertRaises(ValueError):
                usb_device("List of devices attached\n" + rows)

    def test_verified_backup_precedes_push_and_failure_stops_transfer(self):
        with tempfile.TemporaryDirectory() as folder:
            runner = Mock()
            target = transfer(folder, "phone123", runner)
            commands = [call.args[0] for call in runner.call_args_list]
            self.assertIn("prepare_legacy_backup.py", commands[0][3])
            self.assertEqual(["adb", "-s", "phone123", "push", str(target), "/sdcard/Download/" + target.name], commands[1])
            self.assertTrue(all("uninstall" not in cmd and "clear" not in cmd for cmd in commands))
            runner = Mock(side_effect=subprocess.CalledProcessError(1, ["converter"]))
            with self.assertRaises(subprocess.CalledProcessError):
                transfer(folder, "phone123", runner)
            self.assertEqual(1, runner.call_count)


if __name__ == "__main__":
    unittest.main()

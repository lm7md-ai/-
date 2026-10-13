#!/usr/bin/env python3
"""USB-only transfer assistant. Keeps verified backups; never uninstalls an app."""
from datetime import datetime
from pathlib import Path
import subprocess
import sys


def usb_device(output):
    rows = [line.split() for line in output.splitlines()[1:] if line.strip()]
    devices = [row for row in rows if len(row) >= 2 and ":" not in row[0]
               and not row[0].startswith("emulator-")]
    if len(devices) != 1:
        raise ValueError("وصل هاتفًا واحدًا بكابل USB، ثم أعد المحاولة.")
    if devices[0][1] != "device":
        raise ValueError("افتح شاشة الهاتف ووافق على السماح بتصحيح USB، ثم أعد المحاولة.")
    return devices[0][0]


def transfer(folder, serial, runner=subprocess.run):
    folder = Path(folder)
    folder.mkdir(parents=True, exist_ok=True)
    filename = "mashwari-" + datetime.now().strftime("%Y%m%d-%H%M%S") + ".json"
    target = folder / filename
    converter = Path(__file__).with_name("prepare_legacy_backup.py")
    runner([sys.executable, "-X", "utf8", str(converter), "--serial", serial, "--output", str(target)], check=True)
    # Conversion validates SQLite and saves both the JSON and original archive first.
    runner(["adb", "-s", serial, "push", str(target), "/sdcard/Download/" + filename], check=True)
    return target


def main():
    print("نقل بيانات مشواري — لا تحذف التطبيق القديم")
    print("1. أنهِ المشوار الجاري في النسخة القديمة.")
    print("2. فعّل تصحيح USB، وصل الهاتف وافتح شاشته ووافق على السماح بالاتصال.")
    input("بعد إتمام الخطوتين، اضغط Enter للنسخ: ")
    output = subprocess.run(["adb", "devices", "-l"], check=True, capture_output=True, text=True).stdout
    serial = usb_device(output)
    folder = Path(__file__).resolve().parent.parent / "Backups"
    target = transfer(folder, serial)
    print("\nاكتمل حفظ النسخة على اللابتوب ونسخ ملف الاستيراد إلى تنزيلات الهاتف.")
    print("نسخة اللابتوب: " + str(target))
    print("افتح نسخة مشواري 2.0 ذات الأيقونة الزرقاء، ثم استيراد نسخة محلية.")
    print("اختر الملف من التنزيلات وراجع عدد المشاوير قبل تأكيد الاستيراد.")
    print("إذا كنت قد أضفت بيانات إلى 2.0، لن يستبدلها الاستيراد. احتفظ بالنسختين.")
    print("احتفظ بالتطبيق القديم وملفات Backups حتى تتأكد من المشاوير والمصاريف.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print("\nتوقف النقل. لم يُحذف التطبيق القديم أو تُمسح بياناته.", file=sys.stderr)
        print(str(error), file=sys.stderr)
        print("إذا تم إنشاء مجلد Backups فاحتفظ به، حتى لو فشل نسخ الملف إلى الهاتف.", file=sys.stderr)
        sys.exit(1)

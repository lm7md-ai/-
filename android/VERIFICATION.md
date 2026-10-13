# Verification — version 2.0, 2026-10-13

The final source check passed with JDK 21, Gradle 9.8.0, AGP 9.4.1, Kotlin 2.4.20,
KSP 2.3.12, Android platform 37.2/build-tools 37.0.0, Compose BOM 2026.09.00,
Room 2.8.5, DataStore 1.2.1 and Robolectric 4.17. Dependency downloads used the
configured proxy and trusted CA; verification was not disabled.

```sh
./gradlew --no-daemon --max-workers=2 assembleNextDebug assembleClassicDebug testNextDebugUnitTest lintNextDebug lintClassicDebug
python3 tools/verify_schema.py
python3 tools/test_safe_update.py
python3 tools/test_legacy_backup.py
python3 tools/test_release_identity.py
python3 tools/test_windows_transfer.py
```

| Check | Result |
|---|---|
| Next and classic APK builds | Passed |
| Android/JVM tests | 61 executed, 61 passed, 0 failed/errors/skipped |
| Safe-update guard tests | 5 passed |
| Legacy archive conversion tests | 2 passed, including committed WAL records |
| Publication identity tests | 2 passed; wrong edition/key/old version and incompatible classic baselines rejected |
| Windows transfer assistant tests | 2 passed; one authorized USB device required; verified conversion precedes push; failed conversion prevents push |
| Android Lint | 0 errors; notices about a newer Gradle patch and the optional toUri KTX convenience |
| Actual Room migrations | Versions 1–5 opened and validated; every original column value preserved |
| SQLite migration/deletion checks | Passed through schema 5; trip-detail cascade and expense/fuel preservation checked |
| Local backup round trip | All database tables, IDs, points, fuel links, annotations and receipt hashes preserved |
| Original schema-1 portable import | Missing later fields filled with migration defaults; historical amounts/distance preserved |
| Invalid/nonempty restore | Foreign-key errors roll back; existing records cannot be overwritten; negative expenses/future schemas rejected |
| CSV/GPX | Exact halala amounts, spreadsheet-formula escaping, timestamps and GPS segment breaks checked |
| Search/favorites/sorting/budget | Passed, including Saudi midnight day boundaries and empty spending days |
| Compose UI interaction | Expense action, search, favorites, navigation, light/dark themes, backup panel and draft retention across saved-state restoration checked |
| Android 8 notification permission lookup | Passed under Robolectric API 26 |
| SMS import/parser/receipt persistence | Existing Arabic/English parsing, exclusions, concurrency, deletion and reopen tests passed |
| APK map asset | Original 56,582,144-byte MBTiles checksum retained |
| APK signature | Verified; no private keystore bundled |
| Main APK identity | `sa.rihla`, versionCode 20, versionName 2.0, label مشواري |
| Optional independent APK | `sa.rihla.next`, versionCode 20, versionName 2.0, label مشواري |
| 1.3 → 2.0 update identity | Published 1.3 certificate identical; package retained; versionCode increases 4 → 20 |
| Windows ZIP | Original official archive hashes checked; every bundled file checked against its manifest; SQLite and licenses included; no private keystores |
| Existing website application files | Unchanged |

The optional `RIHLA_SIGNING_KEYSTORE` configuration was exercised with the existing
private key path and produced the expected public certificate. No private key or
password is committed. `tools/verify_release.py` must pass before publishing the
APK; it refuses a regenerated or different signing key and requires an explicit
edition. Classic updates require a verified compatible previous APK.

Published main APK: `releases/app-v2.0.apk`, 60,282,373 bytes.
SHA-256: `b21439d82e4b07500a42fda8d1df9446b9743312fd9bc034d4645572f857f664`.
Independent APK SHA-256: `7495f7c253bf3867407c1ff91e09945dd136b4b14a6c8c02672846a06ad2f186`.
Windows transfer ZIP: 18,926,331 bytes; SHA-256
`8e328243254d812b7fdd3f78656eb24dec4c8c7257c465dbf5a102b2a73749c3`.
Public certificate SHA-256: `c82d020159412f6dac6f0675f1b826f7335730bbe9b647224644f94937da8b34`.

The user reports having transferred data into 1.3. The main classic APK is a
compatible update of the verified published 1.3 APK; schema 4 → 5 preserves
all existing rows. Installation on the physical handset remains untested.
The original 1.0 APK/key is unavailable, so direct 1.0 upgrade compatibility
is not established. The optional next APK has a distinct identity and requires
explicit local backup/import; historical transfer to that edition is not automatic.

The expense dialog's platform wrap-content sizing caused repeated layout and a
test idle timeout. Explicit dialog sizing with horizontal margins resolves it;
the full application draft-restoration assertion passes with native rendering.

## Visual evidence

Native Android rendering under Robolectric generated and was visually inspected:
`releases/previews/app-light.png`, `app-dark.png`, and `more-dark.png`. These are
actual Compose interfaces from a simulated Android runtime, with an empty ledger,
not photographs of a RedMagic handset or fabricated personal journeys. The
interactive history fixture is test data and is not inserted into production.

## Phone validation still required

No physical phone or full emulator was available. APK installation on the user's
RedMagic, Windows executable launch, USB/wireless ADB/run-as extraction, actual SAF file selection,
notification delivery/redaction, live GPS, Android/OEM background callbacks,
reboot recovery and battery savings remain untested on hardware. Android location
priority/intervals and the tracking engine are retained; lower consumption has not
been measured. UI and database tests do not establish hardware reliability.

The legacy converter was tested with real SQLite/WAL archives. No ADB command was
run against a user's phone. It never removes the old app or clears its data. See
[Windows transfer requirements and steps](docs/WINDOWS_MIGRATION.md). ADB/run-as
can be unavailable on some installations; the tool cannot bypass Android's
private-data isolation. This cross-package transfer is unnecessary for the user's
current 1.3 → main 2.0 update.

Portable import accepts up to 100 MiB and an empty target only. It transfers
database records, not map files, theme/budget preferences or OS permissions.
Keep the original installation and backup until imported records are checked.

Offline map coverage remains Jeddah–Makkah, not all Saudi Arabia. Fuel prices are
the dated Aramco October 2026 snapshot. The confirmed vehicle is 1.6L 4A92 with a
4-speed automatic; consumption remains an explicitly provisional editable input
until reliable full-tank measurements exist. See `docs/FUEL_RESEARCH.md`.

# Verification — version 3.0, 2026-10-13

Toolchain: JDK 21, Gradle 9.8.0, AGP 9.4.1, Kotlin 2.4.20/KSP 2.3.12,
Android platform 37.2/build-tools 37.0.0, Compose BOM 2026.09.00, Room 2.8.5,
DataStore 1.2.1 and Robolectric 4.17. TLS, wrapper and source verification remain enabled.

```sh
./gradlew --no-daemon --max-workers=2 assembleClassicDebug assembleNextDebug testNextDebugUnitTest lintClassicDebug lintNextDebug
python3 tools/verify_schema.py
python3 tools/verify_country_map.py
python3 -m unittest discover -s tools -p 'test_*.py'
python3 tools/verify_release.py app/build/outputs/apk/classic/debug/app-classic-debug.apk --edition classic --previous ../releases/app-v2.0.apk
python3 tools/verify_release.py app/build/outputs/apk/next/debug/app-next-debug.apk --edition next
```

| Check | Result |
|---|---|
| Android tests | 72 passed; 0 failures/errors/skipped |
| Python transfer/signature/geometry tests | 12 passed |
| Classic and next builds | Passed; versionCode 30, versionName 3.0 |
| Both Android lint targets | 0 errors; convenience/obsolete-SDK/newer-Gradle notices remain |
| Historical Room migration | Every schema 1–5 opens as schema 6; all original column values retained |
| SQLite schema constraints | Migrations through 6, cascade/unlink behavior and singleton active slot passed |
| Fuel measurement | Full-tank odometer distances work across GPS gaps; partial fills included; decreasing readings rejected transactionally |
| Wallet rules | Most specific normalized merchant rule wins; unknown merchants stay other; actual purchase import checked |
| Recurring payments | Concurrent confirmations create one expense per month; next month can be paid; short months clamp correctly |
| Backup | Rules, schedules, odometers and existing IDs/receipts survive export/restore; existing target cannot be overwritten |
| Trip split/merge | Recursive splits preserve distance, stop/visit durations and visit count; expenses follow timestamps; original point IDs survive split/remerge; malformed endpoint edits roll back |
| Route comparison | Repeated geographic corridors grouped; different corridors and incomplete GPS traces distinguished |
| Tracking policy | Stale, mock, invalid and poor fixes rejected; balanced intervals retained at 5/30 seconds, optional precise intervals 3/15 seconds |
| Map asset | Database/pack checksums and SQLite integrity passed; no spatial extension required |
| Country coverage | Road samples in Riyadh, Jeddah, Makkah, Dammam, Tabuk, Jazan, Arar and Najran passed |
| Actual Android map access | Bundled asset copied/decompressed, Arabic search and viewport queries passed under native Android SQLite |
| UI map | Navigation, zoom, measurement selection, loaded native map rendering and screenshots checked |
| Stop taps | Existing coordinate-picking behavior retained; advanced map can select stops alongside point picking |
| Existing behavior | SMS parser/deduplication, migrations, fuel/cost/duration calculations, backup rejection and UI restoration tests retained |
| Main update identity | `sa.rihla`, same verified certificate as published 1.3 and 2.0; versionCode increases to 30 |
| Independent identity | `sa.rihla.next`, same retained certificate; versionCode 30 |

## Offline country data

The licensed Geofabrik/OSM snapshot is from 2026-10-12. It contains 1,422,261
road/coast sections and 8,516 settlement names in a rectangle covering Saudi
Arabia plus adjoining GCC areas. High-density views cap results and simplify
display geometry at low zoom; no navigation graph, live traffic or satellite
imagery is provided. See [map provenance and ODbL source distribution](maps/README.md).

Expanded database: 139,857,920 bytes, SHA-256
`5a9ba137a2c638f9706bf2cbebed611e4a40e42cbf0f26240e51d7d81d403159`.
Pack: 66,599,121 bytes, SHA-256
`fc47bcdafc88c41b9223666099fd327cf5386ceba5b9837695a831fbc659e7dd`.
The APK asset matches this pack exactly. Legacy Jeddah–Makkah data remains in
the repository and prepared phone caches; fresh 3.0 installs ship national data.

The Android asset merger automatically expanded `.gz` inputs; the gzip stream
now uses a `.pack` filename and is explicitly decoded by the app. The first
R-tree prototype was rejected by native Android SQLite; the shipped ordinary
grid tables have no extension dependency. Obsolete incremental APK ZIP gaps were
removed by repackaging; the publication guard refuses APKs at or above 100 MiB.

## Artifact identity

Public certificate SHA-256:
`c82d020159412f6dac6f0675f1b826f7335730bbe9b647224644f94937da8b34`.
No private signing key or password is committed or bundled. The secure existing
key file is retained outside Git. Published APK hashes are in `releases/README.md`.
The same-package main update preserves the installed 1.3/2.0 data. The independent
edition updates only an existing next installation; cross-package transfer remains
explicit and cannot bypass Android isolation or the unavailable original 1.0 key.

Main APK: 86,959,186 bytes; SHA-256
`cb8ba80418c2b2b6cb3c7c687eb7da7fc007fa03d2df65cbd3872b49c3c3ad45`.
Independent APK: 86,959,190 bytes; SHA-256
`1358da21d3ec791615b39a565bb2f616ba3ea7d6ee57dd7161d2b4923f46c4cf`.

## Hardware limits

These are JVM/native Android simulations, not installation or driving tests on a
RedMagic handset. Real GPS, Bluetooth broadcasts/background foreground-service
permission, OEM process killing, notification visibility, SAF document selection,
reboot behavior and battery consumption still require phone testing. Higher
precision is optional; balanced request intervals remain unchanged. No battery
savings or complete hardware reliability has been measured or asserted.

The map screenshot is actual loaded native rendering of public OSM data in
Robolectric. UI preview ledgers are empty; no user VIN, private routes or financial
data are published. The web/Expo application code remains untouched.

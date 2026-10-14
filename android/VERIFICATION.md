# Verification — version 4.0, 2026-10-14

Toolchain: JDK 21, Gradle 9.8.0, AGP 9.4.1, Kotlin 2.4.20/KSP 2.3.12,
Android platform 37.2/build-tools 37.0.0, Compose BOM 2026.09.00, Room 2.8.5,
DataStore 1.2.1, Media3 1.11.1, DocumentFile 1.1.0 and Robolectric 4.17. TLS, wrapper and source verification remain enabled.

```sh
./gradlew assembleClassicRelease assembleNextRelease testClassicDebugUnitTest lintClassicRelease lintNextRelease
python3 tools/verify_schema.py
python3 tools/verify_country_map.py
python3 -m unittest discover -s tools -p 'test_*.py'
python3 tools/verify_release.py app/build/outputs/apk/classic/release/app-classic-release.apk --edition classic --previous ../releases/app-v3.2.apk
python3 tools/verify_release.py app/build/outputs/apk/next/release/app-next-release.apk --edition next
```

| Check | Result |
|---|---|
| Android tests | 98 passed; 0 failures/errors/skipped |
| Python transfer/signature/geometry tests | 13 passed |
| Legacy server HTTP security tests | Previous 3.2 validation: 4 passed; anonymous device access, oversized body, Host parsing and public dev-token binding checked |
| Legacy Expo project | Previous 3.2 validation: TypeScript check and web export passed; 7 static routes |
| npm dependency audit | Previous 3.2 audit: 29 unresolved advisories: 18 high, 11 moderate, 0 critical; semver-compatible fixes attempted; no forced SDK downgrade |
| Classic and next builds | Passed; non-debuggable Release, versionCode 40, versionName 4.0 |
| Both Android lint targets | 0 errors, 21 warnings per target; KTX convenience, empty-super, obsolete-SDK and newer-Gradle notices, plus exported MediaSessionService. Service admission checks own UID or Media3 trusted controllers; exported access is required for system media controls |
| Audio catalog | Duplicate scan retains favorites/resume/playlists/bookmarks; relink uses stable record ID; ordered playlist edits and FK cascades tested |
| Audio persistence | Separate audio database reopens with saved position; catalog backup restores all records, rejects existing targets and rolls back orphan references |
| Audio playback service | Real Media3 service restores queue paused at saved position and retains queue on shutdown without changing an active journey; simulated content URI, not a decoded hardware playback test |
| Audio UI | Native rendered navigation, search with empty result, favorites and playlist creation tested; explicit-width dialogs avoid a native layout loop |
| Stereo processor | Disabled stereo is byte-identical, enabled output bounded, split stereo frames retained, unsupported mono/8-channel/float layouts left inactive; presets checked |
| Audio idle saving | Paused service avoids periodic progress writes; position-only save retains actual last-played time |
| Audio source isolation | Journey Room schema/migrations and tracking reducer unchanged; no destructive audio fallback |
| Supplied Rajhi Google Pay SMS | Exactly 3200 halalas and EXPRESS FOOD; Saudi bank time retained |
| SMS delivery formats | Empty MessagingStyle falls back; InboxStyle multiline purchase extraction passes |
| Cross-channel receipts | One dated bank message delivered at different times imports once; legacy deleted receipt still blocks import |
| Automatic departure candidate | Waiting beyond 90 seconds rolls the confirmation window and subsequent driving starts |
| Provider speed omission | Position-derived driving accepted; walking and uncertain drift rejected |
| Tracking service | Repeated automatic trigger retains GPS evidence, creates one automatic trip and continues its route; synthetic accepted fixes in native Android simulation |
| Disabled automatic setting | Delayed automatic trigger stops without creating a trip |
| Current-location picker | Stale, mock and zero-accuracy fixes cannot set home |
| Backup validation | Present non-array table, negative fuel, ended active trip and orphan active trip rejected transactionally |
| Tile safety | Oversized stream stops at 2MB; invalid bytes and 4096px dimension rejected before full decode |
| Home trigger configuration | Exit + entry, no synthetic initial departure, minimum wakeup radius 100m checked |
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
| Main update identity | `sa.rihla`, same verified certificate as published 1.3/2.0/3.0/3.1; versionCode increases to 40 |
| Independent identity | `sa.rihla.next`, same retained certificate; versionCode 40 |

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

Main APK: 82,945,115 bytes; SHA-256
`f492aa44a4504d3b229b714a47e0d2d30d6bbc8ce9e6f80f7e67d762a8ecec05`.
Independent APK: 82,945,115 bytes; SHA-256
`f6b721dbd290a4fec72c47e4d5ec81b49d4e97329f5f8cfdbe69c2aef76c3cc7`.

Release uses the retained certificate for update compatibility; it is not a key
rotation or a claim of Play Store signing readiness. Legacy run-as backup tools
cannot read private data from this non-debuggable build; use in-app export.

## Hardware limits

These are JVM/native Android simulations, not installation or driving tests on a
RedMagic handset. Real GPS, Bluetooth broadcasts/background foreground-service
permission, OEM process killing, notification visibility, SAF document selection,
reboot behavior and battery consumption still require phone testing. Higher
precision is optional; balanced request intervals remain unchanged. No battery
savings or complete hardware reliability has been measured or asserted.

Direct SMS uses Android's standard multipart broadcast decoder and optional
RECEIVE_SMS; no physical banking SMS delivery or installer permission allowlist
was tested. The home geofence request configuration is tested, but actual Google
Play Services delivery, wakeup latency and receiver launches on RedMagic are not.
The 10-minute automatic confirmation window can consume more location power
while waiting, and GPS still confirms a five-minute stop before ending at home.

The map screenshot is actual loaded native rendering of public OSM data in
Robolectric. UI preview ledgers are empty; no user VIN, private routes or financial
data are published. Legacy Expo UI code remains unchanged; its dependency lock,
server security and CI server-test step were updated as described in the audit.

## Audio device acceptance

The audio UI preview contains one clearly synthetic test catalog entry and is
rendered by native Robolectric graphics. The new tests do not exercise a physical
SAF provider or decode and measure sound on RedMagic. Check local-file selection,
Bluetooth buttons, lock-screen notification, headset removal, call interruption,
screen-off playback, sleep timer and EQ/output changes on the handset. No Android
Auto browsing, streaming subscription, real discrete 7.1 conversion or measured
battery/psychoacoustic quality is claimed. Audio backup stores references and
metadata only; device URI grants and the files themselves require separate care.

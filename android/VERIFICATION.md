# Verification — version 1.2, 2026-10-13

The final `./gradlew --no-daemon --max-workers=2 assembleDebug testDebugUnitTest lintDebug` run completed successfully using JDK 21, Gradle 9.8.0, AGP 9.4.1, Kotlin/Compose compiler 2.4.20, KSP 2.3.12, SDK platform 37.2, and build-tools 37.0.0. Dependencies are pinned (Compose BOM 2026.09.00, Activity 1.13.0, Lifecycle 2.11.0, Room 2.8.5, DataStore 1.2.1, Play Services Location 21.4.0, Coroutines 1.11.0, Robolectric 4.17). The test JVM uses the configured proxy and trusted CA for verified Android runtime downloads.

| Check | Result |
|---|---|
| `assembleDebug` | Passed; APK generated |
| `testDebugUnitTest` | 32 executed, 32 passed, 0 failed/errors, 0 skipped |
| Actual Room upgrades | Schemas 1, 2, 3 opened successfully; every original column value preserved in all seven tables |
| Android map gesture | Real single tap selects a stop; location picker retains its coordinate callback |
| `python3 tools/test_safe_update.py` | 5 executed/passed: signer formats, missing/wrong signer, version/app ID, WAL backup, missing DB |
| `lintDebug` | Passed; 0 errors, 1 notice that Gradle 9.8.1 is available |
| SQLite active slot | Passed: duplicate singleton rejected |
| SQLite migration 1→2 | Passed: rows preserved, columns/indexes match exported schema |
| SQLite migration 2→3 | Passed: existing routes/spending preserved and archived cost defaults match schema |
| Outing deletion | Passed: points/events/active slot cascade, expenses remain unlinked, fuel remains |
| Expense deletion | Passed: dependent fuel record cascades |
| `apksigner verify --verbose` | Passed; APK signature scheme v2, valid for Android 8+ |
| Bundled offline map | All 20,321 PNG tiles verified; SQLite integrity/metadata/coverage passed |
| APK offline asset | Confirmed `assets/maps/jeddah-makkah.mbtiles` is present (56,582,144 bytes) |
| Existing website tracked files | Unchanged |
| v1.1 → v1.2 signing compatibility | Verified against both real APKs: same app ID, increasing version, same certificate |
| Installed v1.0 signing compatibility | Not established: installed APK/certificate and original key unavailable |

APK: `app/build/outputs/apk/debug/app-debug.apk` (debug signed, approximately 58 MiB).
SHA-256: `7a1553e527b21812b22b7456b1d3e5c2344420995e785cd2fbf8c5f4f93d2f25`.
Public signing certificate SHA-256: `c82d020159412f6dac6f0675f1b826f7335730bbe9b647224644f94937da8b34`.

New tests exercise duration carries through seconds/minutes/hours, PG91/PG95 prices,
decimal cost rounding, unknown-engine ranges, measured-consumption priority,
invalid inputs, archived price stability, legacy outing fallback, and a regression
where confirming a stop after a GPS gap could erase previous driving time.

The basemap was visually inspected with a local tile mosaic for Jeddah and Makkah.
No network requests are made by `verify_offline_map.py`; it verifies high-detail
coverage in both cities/the university region and regional road-corridor coverage.
OSM source checksum, data date, attribution, and reproducible clipped source
database are documented in `maps/README.md`.

The Python schema checks execute the actual migration SQL and exported table/index definitions on SQLite. The added Robolectric test also opens databases through Room with native SQLite, runs its migrations/schema validation, compares all original values and checks foreign keys, including an active outing. This verifies the adapter in a simulated Android runtime; actual phone update/install and service lifecycles remain untested. The backup test includes committed rows still in an uncheckpointed WAL. No ADB backup or installation was executed against a phone.

Gradle reports compatibility deprecations for a future Gradle 10 upgrade. Android Lint reports no application errors; its sole notice is availability of a newer Gradle patch. Tool activation uses the cloud machine's maintained Java trust store; TLS/signature/checksum verification was retained.

## Device checks still required

No physical Android device or full emulator was available. Robolectric verified map tapping and Room upgrades. The complete Compose UI, live GPS, Android/OEM background callbacks, reboot recovery, Google Play Services integration, and real battery behavior remain unverified on hardware. Battery changes remove redundant work while retaining GPS priority/intervals; savings have not been measured. Idle screen refresh sleeps until Saudi midnight so day/week/month boundaries still update.

On the intended phone, verify:

1. Arabic RTL and light/dark/system modes, including date/time pickers.
2. Denied, approximate, precise, background, activity, and notification permission paths.
3. Home configuration by GPS and map; a user-defined university circle/polygon.
4. Manual start, route recording, short traffic lights, a multi-hour stop, resumed driving, and manual end.
5. Automatic start with the screen closed; passing near home versus parking for five minutes.
6. University arrival, a brief boundary exit/reentry, and a meaningful departure.
7. Reopening during a journey and recovery following a process/device restart within Android's restrictions.
8. Fresh installation in airplane mode: bundled maps, GPS recording, route display, optional online toggle, and imported-map priority.
9. Expenses, full/partial fuel fills, insufficient-data handling, classification, confirmed deletion, and complete data deletion.

The Jeddah–Makkah map is bundled and online requests are disabled by default.
Coverage is regional; other areas need imported data or the optional online setting.
The engine/transmission is now confirmed as 1.6L 4A92 with a 4-speed automatic.
Consumption starts with an editable, explicitly provisional input; no matching
official rating was verified. Prices are a dated Aramco snapshot. See `docs/FUEL_RESEARCH.md`.
New outings archive cost inputs; legacy estimates explicitly use current inputs.
Statistics retain outing start-date attribution and separate expense timestamps.

Debug builds must use the same signing key as an installed version to upgrade in
place. Version 1.0's signature still needs checking using `tools/safe_update.py`.
Build the update with your existing key; do not uninstall an app with
valuable local history just to bypass a signing mismatch. No signing credentials
are committed to the repository.

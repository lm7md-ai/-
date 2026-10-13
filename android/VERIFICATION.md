# Verification — version 1.1, 2026-10-13

The final `./cloud-setup.sh` run completed successfully using JDK 21, Gradle 9.8.0, AGP 9.4.1, Kotlin/Compose compiler 2.4.20, KSP 2.3.12, SDK platform 37.2, and build-tools 37.0.0. Dependencies are pinned to the stable releases retrieved from the available official repositories (Compose BOM 2026.09.00, Activity 1.13.0, Lifecycle 2.11.0, Room 2.8.5, DataStore 1.2.1, Play Services Location 21.4.0, Coroutines 1.11.0).

| Check | Result |
|---|---|
| `assembleDebug` | Passed; APK generated |
| `testDebugUnitTest` | 26 executed, 26 passed, 0 failed/errors, 0 skipped |
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

APK: `app/build/outputs/apk/debug/app-debug.apk` (debug signed, approximately 58 MiB).
SHA-256: `9cfdc58c98e23c44e80c56fa73d10835ba895073a8f563574bf7cb4f8e3f1114`.

New tests exercise duration carries through seconds/minutes/hours, PG91/PG95 prices,
decimal cost rounding, unknown-engine ranges, measured-consumption priority,
invalid inputs, archived price stability, legacy outing fallback, and a regression
where confirming a stop after a GPS gap could erase previous driving time.

The basemap was visually inspected with a local tile mosaic for Jeddah and Makkah.
No network requests are made by `verify_offline_map.py`; it verifies high-detail
coverage in both cities/the university region and regional road-corridor coverage.
OSM source checksum, data date, attribution, and reproducible clipped source
database are documented in `maps/README.md`.

The schema checks execute the real migration SQL and exported Room table/index definitions on SQLite. They verify constraints and data preservation; they do not substitute for device instrumentation of the Room adapter or Android service lifecycle.

Gradle reports compatibility deprecations for a future Gradle 10 upgrade. Android Lint reports no application errors; its sole notice is availability of a newer Gradle patch. Tool activation uses the cloud machine's maintained Java trust store; TLS/signature/checksum verification was retained.

## Device checks still required

No Android device/emulator was used for the final validation. ADB's default configuration directory is outside this sandbox, and this machine exposes no KVM device. UI interactions, live GPS, Android/OEM background callbacks, reboot recovery, Google Play Services integration, and real battery behavior remain unverified on hardware.

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
Consumption figures remain a reference range while the actual engine/transmission
is unknown; prices are a dated Aramco snapshot. See `docs/FUEL_RESEARCH.md`.
New outings archive cost inputs; legacy estimates explicitly use current inputs.
Statistics retain outing start-date attribution and separate expense timestamps.

Debug builds must use the same signing key as an installed version to upgrade in
place. Build the update with your existing key; do not uninstall an app with
valuable local history just to bypass a signing mismatch. No signing credentials
are committed to the repository.

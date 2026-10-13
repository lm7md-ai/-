# Updating without losing journeys

Version 1.2 keeps application ID `sa.rihla`, the `journeys.db` filename,
DataStore settings, and Room migrations 1 → 2 → 3. No destructive migration
or automatic clearing is enabled. Version 1.2 still uses schema 3.

Android retains app data when a newer APK has the same application ID and
compatible signing certificate. **Never uninstall version 1.0 to overcome a
signature error**: uninstalling deletes its private journeys.

The installed 1.0 APK/signing certificate was not available in this workspace.
Its compatibility cannot be established from its version name alone. The 1.2
build uses the current environment's existing debug key; the signing key is
not committed. Keep that key in secure environment storage for future builds.

## Safe update from a computer

Finish the current outing, enable USB debugging and connect your phone to a
trusted computer. Install Android SDK `adb`, `aapt2`, and `apksigner`, add them
to PATH (with Java available), then run:

```bash
python3 tools/safe_update.py app-v1.2.apk --backup-dir /path/to/private-rihla-backups
```

This tool reads the installed APK's signature, stops writes, takes a private
local backup including database WAL and preferences, validates the backup,
compares signatures/version/application ID, and uses only `adb install -r`.
It refuses incompatible updates and never runs uninstall or data clearing.
If multiple devices are attached, add `--serial DEVICE_SERIAL`. `--backup-only`
creates and validates the backup without installing. The old debug app must
allow `run-as`; if it doesn't, the tool refuses the update.

Keep backups outside this public repository. They contain private locations
and expenses. Retain them until journeys are checked on the phone. Restoring
a backup needs a separately reviewed operation; this tool does not overwrite
live app data. A signature mismatch needs the original signing key, supplied
securely through environment settings, to produce a compatible update.

## Verification and battery

Automated tests open actual historical databases through Room, validate the
migrations and compare all original column values for outings, points,
events, places, expenses, fuel, and active outings. Stop selection tests
cover touch targets, overlapping markers, completed and ongoing stops.

The screen clock sleeps until the next Saudi day boundary when no outing is
active, so monthly/week summaries still refresh at midnight. Map paths are reused until
points or viewport change, and location requests are not registered again
when already using the same interval. GPS priority, intervals, stop rules,
automatic detection and persisted route data are unchanged. Hardware battery
savings and background GPS reliability still need a real-phone road test.

## Vehicle estimate

The supplied profile is Lancer EX 2016, 4A92 1.6L, 4-speed automatic, minimum
91 octane, 59L tank. No VIN is stored or published. The earlier manual/CVT
reference choices are replaced by that profile without modifying archived
cost snapshots or expense records. A verified official combined consumption
figure for this exact configuration could not be obtained. New estimates use
an explicitly labeled editable provisional input (initially 8L/100km), then
prefer consumption calculated from reliable full-tank fill-ups. That input
is not a claimed manufacturer rating. Fuel prices remain the checked Aramco
October 2026 rates: 91 = SAR2.18/L; 95 = SAR2.33/L.

# Updating without losing journeys

## Version 4.1 playback fix

VersionCode 41 fixes the stereo processor's handling of Media3's shared empty
buffer while priming/draining the audio pipeline. Both zero-width and widened
stereo output are tested through DefaultAudioSink to Android AudioTrack in
Robolectric; a WAV content URI exercises the service/extractor/output path and
retry after a read error. Error messages now identify the failing stage, and a
same-item retry clears the old error. No files need reimporting for this fix.
Package, retained certificate, journey schema 6 and audio schema 1 stay unchanged.
Install over 4.0 or the earlier compatible main APK without uninstalling/clearing.
The independent APK is only for an existing `sa.rihla.next` installation.
See [fix evidence and limits](docs/AUDIO_FIX_4_1.md).

## Version 4.0 audio library

The signed non-debuggable main APK retains `sa.rihla` and the verified 1.3–3.2
certificate, raising versionCode to 40. Install over the existing application
without uninstalling or clearing storage, after ending any active trip.
`journeys.db` stays at schema 6 and all historical migration paths remain.
Audio uses its own `audio_library.db` schema 1; no journey/expense tables change.
Sound and read-audio permissions are optional; SAF selection works without the
whole-library permission. The mediaPlayback service is independent of tracking.
Audio catalog export/restore is separate from journey backup; it stores file
references, not original audio files or Android URI grants. [Audio guide](docs/AUDIO_4.md).
The independent 4.0 APK updates only an existing `sa.rihla.next` installation.

## Version 3.2 audit fixes

The published APK is now a signed, non-debuggable Release build. It retains the
certificate, package and database schema 6, raising versionCode to 32. Install
over published 1.3/2.0/3.0/3.1 without uninstalling. No history is cleared. Optional
Android permissions still require system grants. Finish the active trip first.
Use the in-app export/restore tools for future backups; legacy Windows `run-as`
transfer is intended for older debuggable APKs and cannot extract private files
from 3.2. See [audit findings and phone acceptance checks](docs/AUDIT_3_2.md).

## Version 3.1 hotfix

`releases/app-v3.1.apk` keeps package `sa.rihla`, the retained signing key and
database schema 6; versionCode increases to 31. Install over published 1.3/2.0/3.0
without uninstalling or clearing storage. Existing trips, receipts and preferences
are retained. Finish the current trip before installing. A newly requested
RECEIVE_SMS runtime permission remains optional and requires the user's Android
permission grant. Use the independent APK only for an existing `sa.rihla.next`
installation. See [SMS and home-trigger setup](docs/HOTFIX_3_1.md).

## Version 3.0

The primary `releases/app-v3.0.apk` keeps the package/key of the published 1.3 and
2.0 APKs and raises versionCode to 30. Room explicitly migrates schema 5→6 with
nullable fuel odometers and new merchant-rule/recurring-payment tables; no existing
rows are removed. End the active outing and install over the existing main APK.
Use `app-v3.0-independent.apk` only to update an existing `sa.rihla.next` installation.
See [the complete 3.0 guide](docs/VERSION_3.md) for measurements, maps, tracking,
wallet controls and explicit trip-edit confirmation. Backup format version 1
remains compatible, supporting source database schemas 1–6.

## Version 2.0 from the published 1.3 installation

The user has moved their data into 1.3. The primary `releases/app-v2.0.apk`
therefore keeps `sa.rihla` and the name **مشواري**. Its signature matches the
published 1.3 APK, its versionCode increases from 4 to 20, and Room explicitly
migrates the database to schema 5. Install over 1.3 without uninstalling or clearing
storage. No transfer between applications is needed. Finish the active outing first.

The original 1.0 key is still unavailable. For phones that remain on conflicting
1.0, `releases/app-v2.0-independent.apk` targets `sa.rihla.next`, installs beside
the old app, and requires [Windows USB local transfer](docs/WINDOWS_MIGRATION.md)
or the [phone-only alternative](docs/PHONE_MIGRATION.md). It cannot automatically
read the old private database. Both editions support JSON backup/restore.
The reported package-conflict message suggests a signer mismatch, but that exact
phone's certificate/install error has not been inspected. Do not uninstall the old
installation or clear its storage to overcome it. `tools/verify_release.py`
checks the explicit edition, version and retained certificate before publication;
classic publication additionally requires comparison with the verified 1.3 baseline.

The following section describes the older same-package update workflow, still
applicable when an installed version and candidate have compatible signatures.

Version 1.3 keeps application ID `sa.rihla`, the `journeys.db` filename,
DataStore settings, and explicit Room migrations 1 → 2 → 3 → 4. No destructive
migration or automatic clearing is enabled. Schema 4 adds expense import metadata
and persistent SMS receipt fingerprints, preserving old expense and journey data.

Android retains app data when a newer APK has the same application ID and
compatible signing certificate. **Never uninstall version 1.0 to overcome a
signature error**: uninstalling deletes its private journeys.

The installed 1.0 APK/signing certificate was not available in this workspace.
Its compatibility cannot be established from its version name alone. The 1.3
build uses the current environment's existing debug key; the signing key is
not committed. Keep that key in secure environment storage for future builds.

## Safe update from a computer

Finish the current outing, enable USB debugging and connect your phone to a
trusted computer. Install Android SDK `adb`, `aapt2`, and `apksigner`, add them
to PATH (with Java available), then run:

```bash
python3 tools/safe_update.py app-v2.0.apk --backup-dir /path/to/private-rihla-backups
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

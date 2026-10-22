# Verification — version 6.2, 2026-10-22

[Interactive segment details and calculation limits](docs/SECTIONS_6_2.md).

- 170 Android tests passed with zero failures, errors or skipped tests; includes all prior audio, SMS, journey, backup and migration suites. 13 Python tool tests and schema verification through journey schema 7 passed. Journey schema 7 and audio schema 2 remain unchanged.
- Native taps select the actual recorded edge without enabling drag inspection. Stop markers retain priority; place selection/measurement callers without a route callback retain their behavior. Programmatic selection refuses cached points belonging to a different displayed trip.
- Section tests distinguish persisted distance contributions, original GPS polyline length and endpoint displacement; reverse endpoints normalize; explicit/uncertain/time gaps contribute no connected distance or inferred speed. Overlapping stop intervals merge, boundaries and open stops clip correctly, and stop/gap overlap does not double-subtract known time.
- Historical fuel snapshots drive estimates; gaps, unavailable snapshots and zero contributions with clear GPS displacement suppress misleading fuel estimates. Shared text contains timestamps and metrics without coordinates or the complete route.
- Analysis uses cancellable background preparation, prefix arrays and block maxima. A 100,000-point recording and 1,000 range queries retain the original records. Selection geometry is prepared in the background; its two screen paths are cached by geometry/viewport and reused. Confidence dashes and real gaps survive highlighting.
- Compose tests cover direct edge details, an arbitrary anchored range, comparison capture, share intent, original-point expansion and closing. Reviewed RTL preview uses fixture data only. Replay analysis receives only the visible point prefix and drops comparisons outside it.

- Both signed release editions have versionCode 62/versionName 6.2 and minSdk 26, are non-debuggable, retain their respective `sa.rihla` / `sa.rihla.next` identities and the persistent signing certificate, and pass increasing-version checks against published 6.1. Classic is 102,292,935 bytes; next is 102,292,947 bytes, below 100 MiB each.
- ZIP alignment passes; all five ARM64 native libraries are byte-identical to 6.1 with 16 KiB ELF load alignment. The country-map asset SHA-256 is unchanged. No signing-store files are packaged. [Release hashes](../releases/SHA256SUMS-v6.2).

- Both full Android lint targets pass with 0 errors and 47 warnings each. Selection-path draw allocations were removed; remaining notices include existing convenience/API, dependency-update, ABI and exported media-session warnings.

No physical RedMagic 11 Pro driving or battery test was available. GPS geometry is not a verified road distance; historical speed samples are not continuous telemetry. Time outside confirmed stops does not prove continuous movement. Fuel estimates are not an OBD reading and omit engine idling.

## Previous verification history

# Verification — version 6.1, 2026-10-20

The eight selected route features and their limits are documented in [ROUTES_6_1.md](docs/ROUTES_6_1.md).

- 161 Android tests passed, with no failures, errors or skipped tests. Includes the existing audio, SMS, journey, backup and migration suites.
- 13 Python transfer/release/geometry tool tests passed; schema checks through journey version 7 passed. Journey schema 7 and audio schema 2 are unchanged in this release.
- Route tests cover confidence runs, explicit/uncertain/time gaps, stationary display cleanup, original-data conservation, color/turn boundaries, 100,000-point simplification with full-point inspection, conservative road ambiguity including hairpin legs on the same polyline, shared-polyline connectors and offline raw-road queries.
- Native View tap/drag events select the original points after simplification. Compose tests cover persisted options/original preset and More → recurring routes → recorded trip. Reviewed RTL screenshots use fixture data only.
- Turn tests cover gradual signed headings, angle wrap, oscillation, missing bearing, stops/gaps/poor accuracy. Service integration confirms balanced requests change from 5s to 2s and return to 5s when disabled.
- Recurring comparisons separate detours, opposite directions and gaps while tolerating small coordinate changes; hour summaries require three observations. Compact shapes avoid retaining full point lists for all historical journeys.
- Full lint exposed two pre-existing API-flow errors in local speech-model download. The Android 13 guard now uses an explicit conditional before calling the newer APIs; it preserves the same unsupported-device message.

- Both release APKs: versionCode 61/versionName 6.1, minSdk 26, non-debuggable; packages `sa.rihla` and `sa.rihla.next`. Signature verification confirms the retained certificate and increasing version against each edition's published 6.0 APK.
- Classic is 102,263,327 bytes and next is 102,263,343 bytes, below GitHub's 100 MiB limit. ZIP alignment passes; all five native ARM64 libraries are byte-identical to 6.0 with 16 KiB ELF load alignment. The country-map asset SHA-256 is unchanged. No signing-store files are packaged.
- Both full Android lint targets passed with 0 errors and 45 warnings each; warnings remain visible rather than suppressed. They include existing KTX/API convenience, compatibility, dependency notices and the exported media-session service.
- [Published file hashes](../releases/SHA256SUMS-v6.1) and [reviewed interface previews](../releases/README.md).

No physical RedMagic 11 Pro was available. Synthetic and native-rendered Android tests do not establish real-world GPS accuracy, provider cadence, road/lane correctness or battery savings. Road matching is explicitly estimated and optional; drawing improvements never rewrite saved coordinates, distance or cost.

## Previous verification history

# Verification — version 4.2, 2026-10-17

Toolchain: JDK 21, Gradle 9.8.0, AGP 9.4.1, Kotlin 2.4.20/KSP 2.3.12,
Android platform 37.2/build-tools 37.0.0, Compose BOM 2026.09.00, Room 2.8.5,
DataStore 1.2.1, Media3 1.11.1, DocumentFile 1.1.0 and Robolectric 4.17. TLS, wrapper and source verification remain enabled.

```sh
./gradlew assembleClassicRelease assembleNextRelease testClassicDebugUnitTest lintClassicRelease lintNextRelease --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8 -Djava.util.concurrent.ForkJoinPool.common.parallelism=16'
python3 tools/verify_schema.py
python3 tools/verify_country_map.py
python3 -m unittest discover -s tools -p 'test_*.py'
python3 tools/verify_release.py app/build/outputs/apk/classic/release/app-classic-release.apk --edition classic --previous ../releases/app-v4.1.apk
python3 tools/verify_release.py app/build/outputs/apk/next/release/app-next-release.apk --edition next
```

| Check | Result |
|---|---|
| Android tests | 106 passed; 0 failures/errors/skipped |
| Python transfer/signature/geometry tests | 13 passed |
| Legacy server HTTP security tests | Previous 3.2 validation: 4 passed; anonymous device access, oversized body, Host parsing and public dev-token binding checked |
| Legacy Expo project | Previous 3.2 validation: TypeScript check and web export passed; 7 static routes |
| npm dependency audit | Previous 3.2 audit: 29 unresolved advisories: 18 high, 11 moderate, 0 critical; semver-compatible fixes attempted; no forced SDK downgrade |
| Classic and next builds | Passed; non-debuggable Release, versionCode 42, versionName 4.2 |
| Both Android lint targets | 0 errors, 23 warnings per target; KTX convenience, empty-super, obsolete-SDK and newer-Gradle notices, plus exported MediaSessionService. Service admission checks own UID or Media3 trusted controllers; exported access is required for system media controls |
| Audio catalog | Duplicate scan retains favorites/resume/playlists/bookmarks; relink uses stable record ID; ordered playlist edits and FK cascades tested |
| Audio persistence | Separate audio database reopens with saved position; catalog backup restores all records, rejects existing targets and rolls back orphan references |
| Audio playback service | Media3 service restores queue paused and retains position/queue without changing an active journey. Synthetic WAV reads from a registered content provider through ExoPlayer to AudioTrack; same-item retry clears an initial read error |
| Audio UI | Native rendered navigation, search with empty result, favorites and playlist creation tested; explicit-width dialogs avoid a native layout loop |
| Stereo processor | Disabled stereo is byte-identical, enabled output bounded, split frames retained, unsupported custom mono/multichannel/float layouts inactive; shared EMPTY_BUFFER accepted before input/between chunks. DefaultAudioSink passes 4096 PCM bytes to AudioTrack at widths 0 and 1. This integration test reproduced a self-copy exception before the fix |
| Playback error feedback | Internal/output failures do not request relinking valid files; permissions and decoder errors give different advice; private URI text is omitted |
| Ready scenes | Natural uses the standard sink with no custom processor or native effects; 3D/virtual 7.1 software scenes produce distinct nonzero mono/stereo output; silence stays silent |
| PCM output values | Production renderer factory tested for every scene at both width settings; output PCM nonzero, natural/custom-zero settled frames byte-identical after Media3's 20ms startup ramp; WAV service output nonzero and app volume 1 |
| Scene switching | Native Compose buttons work; queue/index/position and paused intent remain through natural→3D→7.1→natural with an existing journey unchanged |
| Mute feedback/recovery | System media volume 0 is reported; recovery sets app volume to 1 while leaving system media volume 0; no automatic loudness escalation |
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
| Main update identity | `sa.rihla`, same verified certificate as published 1.3/2.0/3.0/3.1; versionCode increases to 42 |
| Independent identity | `sa.rihla.next`, same retained certificate; versionCode 42 |

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

Main APK: 82,945,111 bytes; SHA-256
`b8b8fe187a029d5d6e73ef9acb8ce76b15bf6321b6c2a4d7d6621b38fc3986b3`.
Independent APK: 82,945,115 bytes; SHA-256
`c72bc1e1c18a95f1d8dd09bcf352919aee0e7e39545818aa6420f2942ba2dac6`.

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
rendered by native Robolectric graphics. The tests use a synthetic content provider and WAV extractor; they do not exercise
a physical SAF provider, hardware AAC decoding or measured sound on RedMagic. Check local-file selection,
Bluetooth buttons, lock-screen notification, headset removal, call interruption,
screen-off playback, sleep timer and EQ/output changes on the handset. No Android
Auto browsing, streaming subscription, real discrete 7.1 conversion or measured
battery/psychoacoustic quality is claimed. Audio backup stores references and
metadata only; device URI grants and the files themselves require separate care.

## 4.1 regression evidence

Before the fix, AudioOutputTest failed with `IllegalArgumentException: The source
buffer is this buffer` in StereoSpaceProcessor.queueInput, reached through
AudioProcessingPipeline.getOutput and DefaultAudioSink.handleBuffer. The guard
returns before touching the output buffer for empty input and retains partial
frames. The integration test passes after the fix with stereo widening off/on.
A real generated WAV fixture traverses content URI, service, extraction, PCM sink
and shadow AudioTrack; missing-file → same-item prepare/play verifies error recovery.
Its test-only load policy avoids retries, and the simulated clock advances while
waiting for playback callbacks. No device playback is claimed. See
[the user-facing fix guide](docs/AUDIO_FIX_4_1.md).

The first concurrent packaging run stalled in signing digest tasks behind archive
locks. A fresh Gradle daemon, cleaned generated APK/package state, sequential
workers and a 16-thread common pool completed the build. Source inputs and the
retained key were unchanged; tests remained successful. The command above records
the successful build settings.

## 4.2 silent-output scope

The handset's silent output has not been reproduced or assigned a confirmed root
cause: counter movement, other-player playback and speaker/Bluetooth route were
asked about but no answer was available while implementing. Natural mode now
bypasses the custom processor entirely and allocates no vendor effects; software
scenes opt in to processing, and custom mode alone connects native effects.
System and app volumes are separate, suppression is visible, hardware keys target
media, and explicit recovery resets app volume/effects/silence/timer and lets the
system choose the route. It does not force the speaker or raise system volume.
No claim of measured handset audibility is made.

3D uses widening plus two short reflections. Virtual 7.1 uses eight differing
reflection delays, normalized to stereo; neither is a discrete 7.1 renderer or
measured HRTF implementation. PCM16 mono is expanded to stereo for these scenes.
Native callback guards prevent a released player's events overwriting its new
replacement state. The source remains Room-schema compatible.

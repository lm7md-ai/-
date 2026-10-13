# Rihla / مشواري

A native, Arabic-first, local-first personal driving journal for one Mitsubishi Lancer 2016. The existing website in the repository root is independent of this Android project.

Version 1.3 adds automatic purchase expenses from SMS notifications, retains the
Jeddah–Makkah offline map, displays durations as hours/minutes/seconds, and estimates
trip fuel costs using saved Saudi prices and personal or provisional consumption.

## Build and run

Requirements: JDK 17 or newer with `javac`, Android SDK platform 37.2 and build-tools 37.0.0. Open this directory in Android Studio, let Gradle sync, and run on an Android 8+ device. Automatic recognition requires Google Play Services; manual recording has an Android GPS fallback.

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds are for personal testing. Sign a release build with your own key before distributing it. There are no API keys, billing accounts, routing APIs, cloud accounts, ads, analytics, or remote journey storage.

## Architecture

- `core`: Android-independent deterministic tracking reducer, geodesic distance, money parsing, fuel calculations, and Saudi time periods.
- `data`: Room database, transactional repository, and DataStore preferences.
- `tracking`: activity-transition/activity updates, permission-aware foreground service, fused/native GPS, boot re-registration, and recovery.
- `map`: replaceable tile-provider interface, native pan/zoom route map, HTTP cache, and local raster MBTiles.
- `ui`: lifecycle-aware Flow ViewModel, localized text helpers, and navigation.
- `feature/*`: Compose Material 3 dashboard, live/detail, history, calendar, statistics, expenses, vehicle, settings, and onboarding.
- `RihlaApp`: small application-scoped dependency container. Business calculations live outside composables.

Settings support light, dark, and system themes. RTL is explicit. User text comes from Android string resources; numbers, Gregorian dates, and times use Arabic formatting and Asia/Riyadh. Week periods begin Sunday. Place configuration accepts current GPS or map selection; university supports circles and user-drawn polygons. No home coordinate or invented campus boundary is shipped.

## Tracking

Automatic mode registers IN_VEHICLE transitions plus low-power activity updates to detect a device that is already driving. Background start requires precise/background location and activity recognition. A transition is only a candidate: two sufficiently accurate positions must show at least 60 meters of travel over at least eight seconds and reported driving speed before an outing is created. A failed candidate expires in 150 seconds. Android cannot identify a private car versus a bus or taxi; incorrect recordings can be deleted.

Manual start is available from the foreground with precise location. GPS samples arrive approximately every five seconds during driving, and every thirty seconds after a confirmed stop. An ongoing system notification opens the app or ends the outing. No other notifications are scheduled.

The reducer rejects inaccurate samples (>60m), invalid/nonmonotonic timestamps, and implied speeds above 65m/s. Distance uses accepted significant movement samples, not a directions service or the straight-line origin/destination distance. Gaps longer than 90 seconds break the displayed polyline and do not invent distance. Missing driving GPS invalidates affected personal consumption intervals; reference cost estimates still apply to the recorded distance only. A phone left stationary after a confirmed stop does not infer missing driving.

A stop requires remaining within approximately 50 meters for three minutes. Stop arrival is backdated to the start of that stationary period. Ordinary lights and brief congestion do not create stops. A confirmed stop stays part of the same outing, even for hours. Renewed motion closes the stop and starts another driving segment. Confirmation rolls back only stationary time actually credited to driving, so a GPS gap cannot erase previous driving time. All duration displays show hours, minutes, and seconds; university exit grace uses the provisional departure time consistently.

Home return requires a prior confirmed departure outside the saved area, low motion, location inside the circle including accuracy, and five minutes of continuous sampled dwell. A long GPS gap resets home dwell. Driving past home never triggers completion. Manual end also works away from home.

University entry starts a candidate visit; two minutes inside confirms it. An exit remains provisional for five minutes. Reentry during grace keeps the same arrival and visit. A confirmed visit originating from home counts once per outing, independent of repeated callbacks. A university-originating outing that ends at home is classified UNIVERSITY_TO_HOME. Manual classification changes are supported and do not rewrite independently detected visit counts.

The database stores the complete reducer state on each accepted fix. A singleton active-slot primary key, transactions, and a repository mutex prevent duplicate active outings. A normal sticky service restart recovers that slot, and reopening the app resumes an existing outing. No route is filled in for time when Android could not deliver locations.

## Android permissions and restrictions

- Fine/coarse location: requested together, with explanatory UI. Approximate-only location is insufficient for reliable route recording.
- Background location: requested separately on Android 10; Android 11+ uses the application settings screen for "Allow all the time".
- Activity recognition: optional for manual use; required for automatic detection on Android 10+.
- Foreground service and foreground-service-location: declared with service type `location`.
- Notification permission: separately requested on Android 13+. A foreground notification remains required even when notification permission is denied.
- Boot completion: re-registers detection, rather than indiscriminately launching a location foreground service from boot.

Automatic recognition callbacks are Android foreground-service start exemption events, but background location remains necessary for location access when started without a visible activity. Restricted/denied starts return safely; users can open the app and start manually. Force-stop prevents callbacks until launch. OEM battery controls, device reboot, lost GPS, or process restrictions can delay or interrupt tracking. After reboot open the app to resume a stored active outing. No application can guarantee uninterrupted location through force-stop or when permissions/GPS are disabled.

Current platform behavior was checked against Android's AOSP `ActiveServices.java` source. Documentation links for ongoing release review:
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://developer.android.com/develop/background-work/services/fgs/service-types#location
- https://developer.android.com/develop/sensors-and-location/location/permissions/background
- https://github.com/aosp-mirror/platform_frameworks_base/blob/master/services/core/java/com/android/server/am/ActiveServices.java

The documentation website was blocked by this cloud instance's egress policy; the AOSP source was accessible. Device validation on the intended Android versions is still required before relying on automatic tracking.

## Maps, policies, and offline use

The map renders actual recorded points, start/end/current-last-fix markers, stop markers, and saved circle/polygon boundaries. Version 1.1 includes a **54 MiB raster MBTiles basemap** covering Jeddah, Makkah, Al-Abdiyyah and the connecting region (20,321 PNG tiles). It is copied from the APK to private storage on first use without a connection. See [map source, coverage, licensing and rebuild instructions](maps/README.md). No route coordinates are uploaded.

Online maps are **off by default**. Core tracking, maps within this region, calculations, settings, expenses, and history function offline from first launch. Imported user maps take priority, followed by the bundled map (including parent-tile overzoom), then cache. Only an explicit online-map setting enables interactive network tile requests outside local coverage. The renderer respects screen density for readable map labels and fits the route to the real viewport.

Online source: HTTPS `tile.openstreetmap.org`, © OpenStreetMap contributors. Attribution is drawn visibly on the map and repeated with its copyright URL below it. Only visible tiles are requested, with an identifying application User-Agent, modest concurrency, and a persistent minimum seven-day cache. Public tiles provide no SLA and may block access. The provider interface permits replacement.

The policy was inspected from the official OpenStreetMap operations working-group repository:
https://github.com/openstreetmap/owg-website/blob/master/policies/tiles.md
https://operations.osmfoundation.org/policies/tiles/

There is **no download-region or bulk-prefetch feature against OSM public servers**. The bundled map was rendered locally from the Geofabrik OSM extract dated 12 October 2026; its clipped derived geometry database is supplied under ODbL. You can also import a legally obtained raster MBTiles file using Android's document picker, acknowledge offline-use rights, and enter the provider's attribution. The importer checks SQLite integrity, format (PNG/JPEG), and decodable tile data before installing it privately. Vector/PBF MBTiles are not supported. Outside saved coverage, routes remain visible and optional online maps can fill the basemap.

## Database and privacy

Room tables: outings (persistent reducer state), active_slot, route points (indexed timestamp/outing, per-point distance), journey events (driving segments, stops, university visits with a type discriminator), saved places, expenses, and fuel fills. Expenses store integer halalas; liters store integer milliliters. Fuel records refer to expenses so fuel is never counted twice.

Outing deletion cascades route/events and clears expense links, preserving actual money spent. Fuel estimates that predate a deletion are invalidated, preventing a changed distance baseline from producing plausible but incorrect results. Full deletion stops tracking/detection, removes all Room data, resets preferences, and deletes imported tiles/cache. There is no coordinate logging or backup/sync integration. Android backup is disabled, and Android 12+ extraction rules exclude app data from cloud backup and device transfer.

Exported Room schemas 1–4 are retained in `app/schemas`. The explicit 1→2 migration adds the outing end-time index and GPS uncertainty flag. The 2→3 migration adds saved trip fuel-price/consumption/source fields and the stationary-time credit counter without deleting rows. The 3→4 migration adds expense import metadata and persistent SMS receipt fingerprints. Legacy outings are explicitly marked as lacking archived cost inputs. There is no destructive migration fallback.

## Spending and fuel

Expenses can be added live or retrospectively, with an optional outing association and editable timestamp. Categories are fuel, food, and other. Fuel accepts any two of amount/liters/price and calculates the third with decimal rounding, checks consistency, and stores a full-tank flag. Snapshot distance is obtained from recorded point increments at the selected fill-up time, never from a typed odometer.

Between successive full tanks, the initial tank's liters are excluded. All subsequent partial fills and the closing full fill are included. Completed valid intervals aggregate distance/liters/cost:

- km/L = recorded kilometers / added liters
- L/100km = added liters / recorded kilometers × 100
- SAR/km = interval fuel cost / recorded kilometers

Zero distance, insufficient full tanks, or detected unreliable intervals display an insufficient-data message for personal consumption. This is conditional on recording **all** driving and fills; undetected unrecorded driving cannot be recovered. Fuel efficiency is cumulative across completed intervals, clearly labeled, while spending follows the selected period.

## Automatic fuel-cost estimates

SMS purchases can now be imported locally from the default Messages application's
notifications after opt-in notification access. Clear SAR purchase amounts appear
in the ledger with merchant/source, classification controls and deletion.
OTP/refund/declined/ambiguous messages are skipped. See [setup, behavior and limits](docs/SMS_IMPORT.md).

See [the researched sources and assumptions](docs/FUEL_RESEARCH.md). Aramco's October 2026 retail prices were checked on 13 October: PG91 **2.18 SAR/L**, PG95 **2.33 SAR/L**. Both are saved offline and editable.

The owner confirmed a Lancer EX 2016, 1.6L 4A92, 4-speed automatic. The earlier manual/CVT choices are no longer used for this vehicle. No official consumption rating matching this exact configuration was verified: estimates start with an explicitly labeled, editable **provisional input** of 8L/100km, then prefer reliable personal full-tank measurements. This is not a manufacturer rating. See [safe update and data preservation](UPDATE.md) before upgrading version 1.0.

Trip estimates use recorded kilometers × consumption × selected price. Estimates are shown live, in trip detail, monthly/all-period statistics, and the vehicle dashboard, separately from actual paid expenses. They never create fictitious expenses or count fuel fills twice. New outings archive their inputs at start; changing prices later does not reprice them. Old outings use current inputs with an explicit label. Fuel-entry forms prefill the selected price and provide PG91/PG95 switches while retaining manual price entry.

## Statistics and calendar semantics

Week, month, year, and all-time summaries include outing/visit counts, distance, driving/stopped/university durations, average/fastest/longest university commute, expense category totals, average spend, and cost/km. Comparisons show absolute distance, SAR, visit-count, and travel-time changes so a zero baseline is safe.

Outing metrics belong to the outing's **start date**; expenses belong to their own recorded timestamp. An outing spanning midnight remains one outing and appears on each intersected calendar day, while summary metrics are counted once on its starting day. This policy is shown in the UI. It avoids duplicates but does not apportion distance/hour totals among calendar days. A university visit that ends without receiving an exit fix is closed at manual/end time.

## Verification

Unit tests cover tracking transitions, GPS filtering, fuel formulas, duration carries, Saudi prices, cost rounding, archived cost stability, and the stop-after-GPS-gap regression. Robolectric tests exercise actual Room migrations from schemas 1–3 and Android map tapping. SQLite checks verify migrations and deletion integrity. The safe-update tests reject wrong signatures and verify backups including uncheckpointed WAL records. Offline-map checks validate all 20,321 PNG tiles and representative regional coverage without network access. Build/lint results and remaining device checks are recorded in `VERIFICATION.md`.

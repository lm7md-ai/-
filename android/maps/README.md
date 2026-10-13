# Offline map data

## Saudi Arabia roads and settlements — version 3.0

The source database is distributed as
[`saudi-roads.pack`](../app/src/main/countryAssets/maps/saudi-roads.pack), under
**ODbL 1.0**. It is a gzip-compressed SQLite database, not a proprietary format;
`gzip -dc saudi-roads.pack > saudi-roads.db` exposes all derived rows and metadata.
The `.pack` name keeps Android's asset merger from automatically expanding a
`.gz` file and inflating APK size. The application prepares the database locally.

It contains 1,422,261 simplified road/coast sections and 8,516 settlement names
within longitude 34–56 / latitude 16–33, covering Saudi Arabia and adjoining GCC
areas in that rectangle. Roads are displayed at appropriate zoom levels; local
search covers named cities/neighborhoods, not every address or business.
The data has no navigation graph, political-boundary guarantee, live traffic,
satellite imagery or complete service directory. OSM completeness varies.

The same verified 2026-10-12 Geofabrik extract cited below supplied this database.
It uses ordinary SQLite tables, a grid index and standard precision-6 polyline
geometry; no R-tree/spatial extension is required on Android. The source snapshot
and upstream attribution are recorded in `metadata`. No public OSM tiles were
bulk-downloaded. Individual optional online tiles remain a separate user setting.

- Expanded database: 139,857,920 bytes; SHA-256
  `5a9ba137a2c638f9706bf2cbebed611e4a40e42cbf0f26240e51d7d81d403159`.
- Compressed source asset: 66,599,121 bytes; SHA-256
  `fc47bcdafc88c41b9223666099fd327cf5386ceba5b9837695a831fbc659e7dd`.

Schema 4 uses precision-6 integer viewport bounds and geometry to reduce storage;
the road-cell index is independent of SQLite extensions.

Rebuild from a legally downloaded PBF using a Python environment with `osmium`
and `shapely`:

```sh
python tools/build_saudi_map.py /path/to/gcc-states.osm.pbf /private/output/saudi-roads.db
```

Compress that database with gzip into `app/src/main/countryAssets/maps/saudi-roads.pack`.
Update checksums/data dates/cache file names when changing the snapshot, then run
`python3 tools/verify_country_map.py`. Native Android SQLite tests additionally
exercise actual local search and map viewport queries from the packaged asset.

## Original Jeddah–Makkah raster map — versions 1.x/2.0

Source: OpenStreetMap contributors, Geofabrik GCC extract:
https://download.geofabrik.de/asia/gcc-states.html

The downloaded extract contained OSM data up to **2026-10-12T20:21:35Z**.
Input source file MD5: `ed89486be92e4a7510517130731a8bd2` (verified against Geofabrik's published checksum).

The repository retains `app/src/main/assets/maps/jeddah-makkah.mbtiles` (54 MiB),
with 20,321 locally rendered PNG tiles. No OpenStreetMap tile server was used to
generate this offline map.
Version 3.0 ships the national vector database instead. An already prepared local
raster file survives compatible updates and remains usable; fresh installations
can import the retained MBTiles file from the repository if desired.

- Regional bounds: longitude 38.85–40.30, latitude 21.05–22.10.
- Full regional coverage: zoom levels 7–14.
- Detailed Jeddah and Makkah/Al-Abdiyyah coverage: zoom levels 15–16.
- Outside detailed coverage, the Android renderer overzooms the closest regional
  parent tile. Outside regional coverage, route overlays remain available.
- This is a simplified road/land-use/building basemap, not a navigation product
  or an authoritative university boundary. Detailed labels depend on available OSM data.

## License and source data

Map database © OpenStreetMap contributors, licensed under the **Open Database
License (ODbL) 1.0**: https://opendatacommons.org/licenses/odbl/1-0/
Attribution/copyright: https://www.openstreetmap.org/copyright
Geofabrik distribution terms: https://www.geofabrik.de/data/download.html

The clipped, derived geometry database is published as
`jeddah-makkah.geojson.gz` under the same ODbL license. The map styling and renderer
are project source code; the data license continues to apply to the OSM-derived
map and geometry database. Preserve attribution and these source/license links
when redistributing.

## Rebuild

Install `osmium`, `Pillow` (with RAQM/Arabic shaping), and `shapely` in a development
Python virtual environment. The script uses Noto Sans Arabic installed on the host.
Use the published GeoJSON cache to reproduce the regional raster map without
downloading the full source PBF again:

```sh
python tools/build_offline_map.py /path/to/gcc-states.osm.pbf \
  app/src/main/assets/maps/jeddah-makkah.mbtiles \
  --features maps/jeddah-makkah.geojson.gz
python tools/verify_offline_map.py
```

The PBF is not needed when the cached derived geometry file exists. Downloads of
future source snapshots require updating map dates/names and recording their
checksums; they are not performed automatically on a user's phone.

# Bundled Jeddah–Makkah map

Source: OpenStreetMap contributors, Geofabrik GCC extract:
https://download.geofabrik.de/asia/gcc-states.html

The downloaded extract contained OSM data up to **2026-10-12T20:21:35Z**.
Input source file MD5: `ed89486be92e4a7510517130731a8bd2` (verified against Geofabrik's published checksum).

The application includes `app/src/main/assets/maps/jeddah-makkah.mbtiles` (54 MiB),
with 20,321 locally rendered PNG tiles. No OpenStreetMap tile server was used to
generate this offline map.

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

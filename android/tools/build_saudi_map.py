#!/usr/bin/env python3
"""Build a searchable offline Saudi road/settlement database from licensed Geofabrik OSM PBF.
No tile-server download. SQLite geometries are the distributed derived database (ODbL).
"""
import argparse
import hashlib
import json
import math
import sqlite3
from pathlib import Path
import osmium
from shapely.geometry import LineString
from map_geometry import encode_points

LEVELS = {"motorway": 5, "trunk": 6, "primary": 9, "secondary": 10,
          "tertiary": 12, "residential": 14, "unclassified": 13,
          "service": 15, "living_street": 15}

class Extract(osmium.SimpleHandler):
    def __init__(self, db):
        super().__init__()
        self.db = db
        self.count = 0

    def node(self, node):
        if not node.location.valid():
            return
        lon, lat = node.location.lon, node.location.lat
        if not (34 <= lon <= 56 and 16 <= lat <= 33):
            return
        if node.tags.get("place") in ("city", "town", "village", "suburb", "neighbourhood"):
            name = node.tags.get("name:ar", node.tags.get("name", ""))
            if name:
                self.db.execute("INSERT INTO settlements VALUES(?,?,?,?)", (node.id, name, lat, lon))

    def way(self, way):
        kind = way.tags.get("highway", "").removesuffix("_link")
        coast = way.tags.get("natural") == "coastline"
        if kind not in LEVELS and not coast:
            return
        try:
            coordinates = [(node.lon, node.lat) for node in way.nodes]
        except osmium.InvalidLocationError:
            return
        if len(coordinates) < 2:
            return
        line = LineString(coordinates)
        west, south, east, north = line.bounds
        if east < 34 or west > 56 or north < 16 or south > 33:
            return
        simplified = line.simplify(.000035, preserve_topology=False)
        points = [[round(lat, 6), round(lon, 6)] for lon, lat in simplified.coords]
        name = way.tags.get("name:ar", way.tags.get("name", way.tags.get("ref", "")))
        self.db.execute("INSERT INTO roads VALUES(?,?,?,?,?,?,?,?,?)", (way.id, "coast" if coast else kind,
                        5 if coast else LEVELS[kind], name, encode_points(points), math.floor(west*1e6)-1, math.ceil(east*1e6)+1, math.floor(south*1e6)-1, math.ceil(north*1e6)+1))
        def tile(lon, lat):
            return int((lon+180)/360*256), int((1-math.asinh(math.tan(math.radians(lat)))/math.pi)/2*256)
        left, top=tile(west,north); right,bottom=tile(east,south)
        self.db.executemany("INSERT INTO road_cells VALUES(?,?)", ((y*256+x,way.id) for x in range(left,right+1) for y in range(top,bottom+1)))
        self.count += 1
        if self.count % 20000 == 0:
            self.db.commit()
            print(f"Roads/coast sections: {self.count}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pbf", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError("Output already exists")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(args.output) as db:
        db.executescript("""CREATE TABLE metadata(name TEXT PRIMARY KEY,value TEXT);
        CREATE TABLE roads(id INTEGER PRIMARY KEY,kind TEXT NOT NULL,minzoom INTEGER NOT NULL,name TEXT NOT NULL,points TEXT NOT NULL,west REAL,east REAL,south REAL,north REAL);
        CREATE TABLE road_cells(cell INTEGER,roadId INTEGER,PRIMARY KEY(cell,roadId)) WITHOUT ROWID;
        CREATE TABLE settlements(id INTEGER PRIMARY KEY,name TEXT NOT NULL,latitude REAL NOT NULL,longitude REAL NOT NULL);
        CREATE INDEX roads_minzoom ON roads(minzoom);
        CREATE INDEX settlements_name ON settlements(name);""")
        db.executemany("INSERT INTO metadata VALUES(?,?)", {
            "name": "Saudi Arabia offline roads and settlements", "license": "ODbL-1.0",
            "source": "https://download.geofabrik.de/asia/gcc-states.html", "data_date": "2026-10-12",
            "attribution": "© OpenStreetMap contributors · Geofabrik · ODbL 1.0",
            "bounds": "34,16,56,33", "schema": "4", "encoding": "polyline6", "bounds_precision": "6",
            "pbf_sha256": hashlib.sha256(args.pbf.read_bytes()).hexdigest(),
            "description": "Whole Saudi bounding region and adjacent GCC areas; simplified road/coast geometry and settlements, no routing graph or live traffic."}.items())
        extract = Extract(db)
        extract.apply_file(str(args.pbf), locations=True, idx="flex_mem")
        db.commit()
        assert db.execute("PRAGMA integrity_check").fetchone() == ("ok",)
        print("Settlements:", db.execute("SELECT COUNT(*) FROM settlements").fetchone()[0], flush=True)
        db.execute("VACUUM")
    print("Bytes:", args.output.stat().st_size, "SHA256:", hashlib.sha256(args.output.read_bytes()).hexdigest(), flush=True)


if __name__ == "__main__":
    main()

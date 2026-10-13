#!/usr/bin/env python3
"""Render a licensed OSM PBF extract into an offline regional raster MBTiles map.

Requires osmium, Pillow (RAQM), and shapely. No tile-server requests are made.
The input PBF stays outside the repository. The derived clipped geometries are
published alongside the produced work so ODbL recipients can reproduce it.
"""
import argparse
import gzip
import hashlib
import io
import json
import math
import sqlite3
from pathlib import Path

import osmium
from PIL import Image, ImageDraw, ImageFont
from shapely.geometry import LineString, Point, Polygon, box, mapping, shape
from shapely.ops import polygonize, unary_union
from shapely.strtree import STRtree

BOUNDS = (38.85, 21.05, 40.30, 22.10)
DETAIL_BOUNDS = [(39.05, 21.30, 39.38, 21.87), (39.70, 21.21, 40.10, 21.60)]
REGION = box(*BOUNDS)
BACKGROUND = "#f3efe6"
ROAD_STYLE = {
    "motorway": ("#edba62", 4.5, 8),
    "trunk": ("#ecc47c", 4.0, 8),
    "primary": ("#f2d399", 3.5, 10),
    "secondary": ("#fffdf8", 2.8, 11),
    "tertiary": ("#fffdf8", 2.2, 12),
    "residential": ("#ffffff", 1.8, 13),
    "unclassified": ("#ffffff", 1.5, 13),
    "service": ("#ffffff", 1.2, 14),
    "living_street": ("#ffffff", 1.2, 14),
    "footway": ("#d4cbbc", 0.8, 15),
    "path": ("#d4cbbc", 0.8, 15),
}
FONTS = "/usr/share/fonts/truetype/noto/NotoSansArabic-Regular.ttf"


def project(lon, lat, zoom):
    scale = 256 * 2**zoom
    r = math.radians(max(-85, min(85, lat)))
    return ((lon + 180) / 360 * scale,
            (1 - math.asinh(math.tan(r)) / math.pi) / 2 * scale)


def unproject(x, y, zoom):
    scale = 256 * 2**zoom
    return (x / scale * 360 - 180,
            math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * y / scale)))))


def tiles_for(bounds, zoom):
    west, south, east, north = bounds
    left, top = project(west, north, zoom)
    right, bottom = project(east, south, zoom)
    return {(x, y) for x in range(int(left // 256), int(right // 256) + 1)
            for y in range(int(top // 256), int(bottom // 256) + 1)}


class Extract(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.features = []
        self.coasts = []

    def node(self, node):
        if not node.location.valid():
            return
        tags = dict(node.tags)
        kind = tags.get("place")
        if kind not in ("city", "town", "suburb", "neighbourhood", "village"):
            return
        point = Point(node.location.lon, node.location.lat)
        if REGION.contains(point):
            self.features.append((point, "label", kind, tags.get("name:ar", tags.get("name", ""))))

    def way(self, way):
        tags = dict(way.tags)
        highway = tags.get("highway", "").removesuffix("_link")
        coast = tags.get("natural") == "coastline"
        area = (tags.get("natural") in ("water", "wood", "scrub") or
                tags.get("landuse") in ("forest", "grass", "recreation_ground", "reservoir") or
                tags.get("leisure") in ("park", "garden") or
                tags.get("amenity") in ("university", "school") or "building" in tags)
        if highway not in ROAD_STYLE and not coast and not area:
            return
        try:
            coords = [(n.lon, n.lat) for n in way.nodes]
        except osmium.InvalidLocationError:
            return
        if len(coords) < 2:
            return
        line = LineString(coords)
        if not line.intersects(REGION):
            return
        if coast:
            self.coasts.append(line.intersection(REGION))
        elif highway in ROAD_STYLE:
            self.features.append((line.intersection(REGION), "road", highway,
                                  tags.get("name:ar", tags.get("ref", tags.get("name", "")))))
        elif len(coords) > 3 and coords[0] == coords[-1]:
            polygon = Polygon(coords)
            if not polygon.is_valid:
                polygon = polygon.buffer(0)
            clipped = polygon.intersection(REGION)
            if clipped.is_empty:
                return
            kind = ("building" if "building" in tags else
                    "water" if tags.get("natural") == "water" or tags.get("landuse") == "reservoir" else
                    "campus" if tags.get("amenity") in ("university", "school") else "green")
            self.features.append((clipped, kind, "", tags.get("name:ar", tags.get("name", ""))))

    def finish_coast(self):
        if not self.coasts:
            return
        coast = unary_union(self.coasts)
        pieces = list(polygonize(unary_union([coast, REGION.boundary])))
        lines = list(coast.geoms) if hasattr(coast, "geoms") else [coast]
        for polygon in pieces:
            p = polygon.representative_point()
            nearest = min(lines, key=lambda g: g.distance(p))
            pos = nearest.project(p)
            a = nearest.interpolate(max(0, pos - .00005))
            b = nearest.interpolate(min(nearest.length, pos + .00005))
            cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
            # OSM coastline direction has land on its left, water on its right.
            if cross < 0:
                self.features.append((polygon, "water", "", ""))


def components(geom):
    if hasattr(geom, "geoms"):
        for sub in geom.geoms:
            yield from components(sub)
    else:
        yield geom


def render(zoom, x, y, features, tree, font, simplified):
    image = Image.new("RGB", (256, 256), BACKGROUND)
    draw = ImageDraw.Draw(image)
    west, north = unproject(x * 256 - 16, y * 256 - 16, zoom)
    east, south = unproject((x + 1) * 256 + 16, (y + 1) * 256 + 16, zoom)
    viewport = box(west, south, east, north)
    indices = sorted(tree.query(viewport), key=lambda i: {
        "water": 0, "green": 1, "campus": 2, "building": 3, "road": 4, "label": 5
    }[features[i][1]])
    label_boxes = []
    for i in indices:
        geom, kind, subtype, name = features[i]
        if kind == "building" and zoom < 15:
            continue
        if kind == "road" and zoom < ROAD_STYLE[subtype][2]:
            continue
        if kind == "label":
            threshold = {"city": 8, "town": 10, "suburb": 12, "village": 12, "neighbourhood": 14}
            if zoom < threshold[subtype] or not name:
                continue
        key = (int(i), zoom)
        if key not in simplified:
            tolerance = 360 / (256 * 2**zoom) * .6
            simplified[key] = geom.simplify(tolerance, preserve_topology=True)
        clipped = simplified[key].intersection(viewport)
        if clipped.is_empty:
            continue
        def pixels(coords):
            return [(px - x * 256, py - y * 256) for px, py in
                    (project(lon, lat, zoom) for lon, lat in coords)]
        for piece in components(clipped):
            if piece.geom_type == "Polygon":
                color = {"water": "#c2dfe7", "green": "#d5e2cb", "campus": "#e7dcc6",
                         "building": "#ded8d0"}.get(kind, BACKGROUND)
                draw.polygon(pixels(piece.exterior.coords), fill=color)
                for ring in piece.interiors:
                    draw.polygon(pixels(ring.coords), fill=BACKGROUND)
            elif piece.geom_type == "LineString" and kind == "road":
                color, width, _ = ROAD_STYLE[subtype]
                line = pixels(piece.coords)
                width = max(1, round(width * (1 if zoom >= 13 else .7)))
                if subtype in ("motorway", "trunk", "primary"):
                    draw.line(line, fill="#c6baa3", width=width + 2, joint="curve")
                draw.line(line, fill=color, width=width, joint="curve")
                if zoom >= 15 and name and piece.length > .003:
                    point = piece.interpolate(.5, normalized=True)
                    px, py = pixels([(point.x, point.y)])[0]
                    if 15 < px < 241 and 15 < py < 241:
                        bounds = draw.textbbox((px, py), name[:45], font=font, anchor="mm", direction="rtl")
                        if not any(box(*bounds).intersects(box(*b)) for b in label_boxes):
                            draw.text((px, py), name[:45], font=font, fill="#68635a", anchor="mm",
                                      direction="rtl", stroke_width=1, stroke_fill=BACKGROUND)
                            label_boxes.append(bounds)
            elif piece.geom_type == "Point" and kind == "label":
                px, py = pixels([(piece.x, piece.y)])[0]
                draw.text((px, py), name, font=font,
                          fill="#373f39", anchor="mm", direction="rtl", stroke_width=2,
                          stroke_fill=BACKGROUND)
    result = io.BytesIO()
    image.save(result, "PNG", optimize=False)
    return result.getvalue()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("pbf", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--features", type=Path)
    args = parser.parse_args()
    feature_file = args.features or args.output.with_suffix(".geojson.gz")
    if feature_file.exists():
        with gzip.open(feature_file, "rt") as source:
            data = json.load(source)
        features = [(shape(f["geometry"]), f["properties"]["kind"],
                     f["properties"]["subtype"], f["properties"]["name"]) for f in data["features"]]
        # Normalize an early extraction layout while retaining expensive valid geometry work.
        features = [(g, k, n, t) if k == "label" and n in
                    ("city", "town", "suburb", "neighbourhood", "village") else (g, k, t, n)
                    for g, k, t, n in features]
    else:
        extract = Extract()
        extract.apply_file(str(args.pbf), locations=True, idx="flex_mem")
        extract.finish_coast()
        features = extract.features
        feature_file.parent.mkdir(parents=True, exist_ok=True)
        with gzip.open(feature_file, "wt") as output:
            json.dump({"type": "FeatureCollection", "license": "ODbL-1.0",
                       "features": [{"type": "Feature", "geometry": mapping(g), "properties": {
                           "kind": k, "subtype": t, "name": n}} for g, k, t, n in features]}, output,
                      ensure_ascii=False, separators=(",", ":"))
    print(f"Regional features: {len(features)}", flush=True)
    tree = STRtree([f[0] for f in features])
    font = ImageFont.truetype(FONTS, 11)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    db = sqlite3.connect(args.output)
    db.execute("CREATE TABLE IF NOT EXISTS metadata (name TEXT PRIMARY KEY,value TEXT)")
    db.execute("CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER,tile_column INTEGER,tile_row INTEGER,tile_data BLOB,PRIMARY KEY(zoom_level,tile_column,tile_row))")
    metadata = {"name": "Jeddah–Makkah offline", "format": "png", "type": "baselayer", "version": "1",
                "minzoom": "7", "maxzoom": "16", "bounds": ",".join(map(str, BOUNDS)),
                "center": "39.6,21.55,10", "attribution": "© OpenStreetMap contributors · ODbL 1.0",
                "source": "https://download.geofabrik.de/asia/gcc-states.html", "data_date": "2026-10-12",
                "description": "Regional map rendered locally from Geofabrik OSM data; no public tiles downloaded."}
    db.executemany("INSERT OR REPLACE INTO metadata VALUES (?,?)", metadata.items())
    total = 0
    for zoom in range(7, 17):
        coordinates = tiles_for(BOUNDS, zoom) if zoom <= 14 else set().union(*(tiles_for(b, zoom) for b in DETAIL_BOUNDS))
        simplified = {}
        for x, y in sorted(coordinates):
            blob = render(zoom, x, y, features, tree, font, simplified)
            db.execute("INSERT OR REPLACE INTO tiles VALUES (?,?,?,?)", (zoom, x, 2**zoom - 1 - y, blob))
            total += 1
        db.commit()
        print(f"Zoom {zoom}: {len(coordinates)} tiles; total {total}", flush=True)
    assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    db.close()
    print(f"MBTiles: {args.output.stat().st_size / 1024**2:.1f} MiB; SHA-256 {hashlib.sha256(args.output.read_bytes()).hexdigest()}", flush=True)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Verify the shipped offline basemap without making any network requests."""
import hashlib
import math
import sqlite3
import struct
from pathlib import Path

path = Path(__file__).resolve().parents[1] / 'app/src/main/assets/maps/jeddah-makkah.mbtiles'
db = sqlite3.connect(f'file:{path}?mode=ro', uri=True)
assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
metadata = dict(db.execute('SELECT name,value FROM metadata'))
assert metadata['format'] == 'png'
assert metadata['attribution'] == '© OpenStreetMap contributors · ODbL 1.0'
assert metadata['data_date'] == '2026-10-12'
count = 0
for (data,) in db.execute('SELECT tile_data FROM tiles'):
    assert data[:8] == b'\x89PNG\r\n\x1a\n'
    assert struct.unpack('>II', data[16:24]) == (256, 256)
    count += 1
assert count == 20321
print(f'PASS: {count} valid PNG tiles, SQLite integrity, attribution, and snapshot date')

def exists(lat, lon, zoom):
    x = int((lon + 180) / 360 * 2**zoom)
    y = int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * 2**zoom)
    return db.execute('SELECT 1 FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?',
                      (zoom, x, 2**zoom - 1 - y)).fetchone() is not None

for name, lat, lon, zoom in [('Jeddah',21.5433,39.1728,16),('Makkah',21.4225,39.8262,16),
                             ('University area',21.33,39.94,16),('Jeddah–Makkah corridor',21.49,39.55,14)]:
    assert exists(lat,lon,zoom), name
    print(f'PASS: offline coverage of {name} at zoom {zoom}')
assert not exists(24.7136,46.6753,14), 'Unexpected coverage claim for Riyadh'
print('PASS: region coverage is explicit; no claim of a full Saudi Arabia map')
print('SHA-256:',hashlib.sha256(path.read_bytes()).hexdigest())

#!/usr/bin/env python3
"""Render a basemap-only inspection sheet from shipped tiles (no route/user data)."""
import math
import sqlite3
import io
import argparse
from pathlib import Path
from PIL import Image, ImageDraw

parser=argparse.ArgumentParser()
parser.add_argument('output',type=Path)
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
db=sqlite3.connect(f'file:{root}/app/src/main/assets/maps/jeddah-makkah.mbtiles?mode=ro',uri=True)
sheet=Image.new('RGB',(1280,690),'white')
for i,(name,lat,lon) in enumerate([('Jeddah — offline OSM basemap',21.5433,39.1728),('Makkah — offline OSM basemap',21.4225,39.8262)]):
    zoom=15
    cx=int((lon+180)/360*2**zoom)
    cy=int((1-math.asinh(math.tan(math.radians(lat)))/math.pi)/2*2**zoom)
    mosaic=Image.new('RGB',(1280,1280),'#f3efe6')
    for dx in range(-2,3):
        for dy in range(-2,3):
            blob=db.execute('SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?',(zoom,cx+dx,2**zoom-1-cy-dy)).fetchone()
            if blob:mosaic.paste(Image.open(io.BytesIO(blob[0])),((dx+2)*256,(dy+2)*256))
    sheet.paste(mosaic.resize((640,640)),(i*640,30))
    ImageDraw.Draw(sheet).text((i*640+15,9),name,fill='black')
ImageDraw.Draw(sheet).text((15,675),'© OpenStreetMap contributors — ODbL 1.0 / Geofabrik 2026-10-12',fill='black')
sheet.save(args.output)

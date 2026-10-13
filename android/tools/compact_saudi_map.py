#!/usr/bin/env python3
"""Convert a retained JSON-geometry map to the portable compact representation."""
import argparse
import json
import sqlite3
from map_geometry import encode_points

def main():
    parser=argparse.ArgumentParser();parser.add_argument('database');args=parser.parse_args()
    with sqlite3.connect(args.database) as db:
        assert db.execute("SELECT value FROM metadata WHERE name='schema'").fetchone()==('2',)
        cursor=db.execute('SELECT id,points FROM roads ORDER BY id')
        count=0
        while True:
            rows=cursor.fetchmany(10000)
            if not rows:break
            db.executemany('UPDATE roads SET points=? WHERE id=?',((encode_points(json.loads(points)),id) for id,points in rows))
            count+=len(rows)
            if count%200000==0:print('Encoded roads:',count,flush=True)
        db.execute('UPDATE roads SET west=CAST(west*1000000 AS INTEGER)-1,east=CAST(east*1000000 AS INTEGER)+1,south=CAST(south*1000000 AS INTEGER)-1,north=CAST(north*1000000 AS INTEGER)+1')
        db.execute("UPDATE metadata SET value='4' WHERE name='schema'")
        db.execute("INSERT INTO metadata VALUES('encoding','polyline6')")
        db.execute("INSERT INTO metadata VALUES('bounds_precision','6')")
        db.commit();db.execute('VACUUM')
        assert db.execute('PRAGMA integrity_check').fetchone()==('ok',)
        print('Compact map ready:',count,flush=True)

if __name__=='__main__':main()

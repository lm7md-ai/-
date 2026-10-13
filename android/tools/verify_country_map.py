#!/usr/bin/env python3
"""Validate the shipped compressed offline country database without SQLite extensions."""
import gzip
import hashlib
from pathlib import Path
import shutil
import sqlite3
import tempfile

root=Path(__file__).resolve().parents[1]
asset=root/'app/src/main/countryAssets/maps/saudi-roads.pack'
with tempfile.TemporaryDirectory() as directory:
    database=Path(directory)/'map.db'
    with gzip.open(asset,'rb') as source,database.open('wb') as output:shutil.copyfileobj(source,output)
    assert hashlib.sha256(database.read_bytes()).hexdigest()=='5a9ba137a2c638f9706bf2cbebed611e4a40e42cbf0f26240e51d7d81d403159'
    with sqlite3.connect(database) as db:
        assert db.execute('PRAGMA integrity_check').fetchone()==('ok',)
        metadata=dict(db.execute('SELECT name,value FROM metadata'))
        assert metadata['license']=='ODbL-1.0' and metadata['schema']=='4' and metadata['encoding']=='polyline6' and metadata['bounds_precision']=='6'
        assert db.execute('SELECT COUNT(*) FROM roads').fetchone()[0]==1422261
        assert db.execute('SELECT COUNT(*) FROM settlements').fetchone()[0]==8516
        assert not db.execute("SELECT name FROM sqlite_master WHERE sql LIKE '%VIRTUAL TABLE%'").fetchall()
        for name,lat,lon in [('Riyadh',24.7136,46.6753),('Jeddah',21.49,39.18),('Makkah',21.42,39.82),('Dammam',26.43,50.1),('Tabuk',28.39,36.57),('Jazan',16.89,42.55),('Arar',30.98,41.02),('Najran',17.54,44.22)]:
            count=db.execute('SELECT COUNT(*) FROM roads WHERE west<? AND east>? AND south<? AND north>?',tuple(value*1e6 for value in (lon+.1,lon-.1,lat+.1,lat-.1))).fetchone()[0]
            assert count>0,name
            print('PASS:',name,'roads:',count)
        assert db.execute("SELECT COUNT(*) FROM settlements WHERE name='الرياض'").fetchone()[0]>0
        print('PASS: country geometry checksum, integrity, portable grid index, source attribution and local Arabic search')
print('Compressed asset SHA256:',hashlib.sha256(asset.read_bytes()).hexdigest())

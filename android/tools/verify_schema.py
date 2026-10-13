#!/usr/bin/env python3
"""Exercise exported Room schemas with real SQLite constraints and migration SQL."""
import json
import re
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
schemas = root / 'app/schemas/sa.rihla.data.JourneyDatabase'
v1 = json.loads((schemas / '1.json').read_text())['database']
v2 = json.loads((schemas / '2.json').read_text())['database']
v3 = json.loads((schemas / '3.json').read_text())['database']
v4 = json.loads((schemas / '4.json').read_text())['database']
v5 = json.loads((schemas / '5.json').read_text())['database']
v6 = json.loads((schemas / '6.json').read_text())['database']
db = sqlite3.connect(':memory:')
db.execute('PRAGMA foreign_keys=ON')
for entity in v1['entities']:
    db.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
    for index in entity.get('indices', []):
        db.execute(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))

def seed(table, overrides=None):
    entity = next(e for e in v1['entities'] if e['tableName'] == table)
    values = {}
    for field in entity['fields']:
        name = field['columnName']
        values[name] = {'TEXT': 'test', 'REAL': 0.0, 'INTEGER': 0}.get(field['affinity'], None) if field.get('notNull', False) else None
    values.update(overrides or {})
    columns = ','.join(f'`{k}`' for k in values)
    db.execute(f'INSERT INTO `{table}` ({columns}) VALUES ({",".join("?" for _ in values)})', list(values.values()))

seed('outings', {'id': 1, 'startedAt': 1234})
seed('active_slot', {'singleton': 1, 'outingId': 1})
seed('points', {'id': 1, 'outingId': 1, 'breakBefore': 1})
seed('events', {'id': 1, 'outingId': 1})
seed('expenses', {'id': 1, 'outingId': 1, 'amountHalala': 12345})
seed('fuel', {'id': 1, 'expenseId': 1})
try:
    seed('active_slot', {'singleton': 1, 'outingId': 1})
except sqlite3.IntegrityError:
    pass
else:
    raise AssertionError('Duplicate active outing slot accepted')
print('PASS: singleton active-slot constraint')

source = (root / 'app/src/main/java/sa/rihla/data/Database.kt').read_text()
migration = source[source.index('val MIGRATION_1_2'):source.index('val MIGRATION_2_3')]
for sql in re.findall(r'db.execSQL\(\s*"([^"\n]+)"\s*\)', migration):
    db.execute(sql)
assert db.execute('SELECT startedAt FROM outings WHERE id=1').fetchone()[0] == 1234
assert db.execute('SELECT uncertainGap FROM points WHERE id=1').fetchone()[0] == 1
for entity in v2['entities']:
    actual = {row[1]: (row[2], bool(row[3])) for row in db.execute(f'PRAGMA table_info(`{entity["tableName"]}`)')}
    expected = {f['columnName']: (f['affinity'], f.get('notNull', False)) for f in entity['fields']}
    assert actual == expected, (entity['tableName'], actual, expected)
    indexes = {row[1] for row in db.execute(f'PRAGMA index_list(`{entity["tableName"]}`)')}
    assert all(i['name'] in indexes for i in entity.get('indices', []))
print('PASS: migration preserves rows and matches exported schema 2')

for sql in re.findall(r'db.execSQL\(\s*"([^"\n]+)"\s*\)', source[source.index('val MIGRATION_2_3'):source.index('val MIGRATION_3_4')]):
    db.execute(sql)
for entity in v3['entities']:
    actual = {row[1]: (row[2], bool(row[3])) for row in db.execute(f'PRAGMA table_info(`{entity["tableName"]}`)')}
    expected = {f['columnName']: (f['affinity'], f.get('notNull', False)) for f in entity['fields']}
    assert actual == expected, entity['tableName']
assert db.execute('SELECT startedAt,fuelPriceHalala,consumptionMinMl,estimateSource FROM outings').fetchone() == (1234, 0, 0, 'UNKNOWN')
assert db.execute('SELECT amountHalala FROM expenses').fetchone()[0] == 12345
print('PASS: migration 2→3 preserves existing outings/spending and marks missing cost snapshots')

for sql in re.findall(r'db.execSQL\(\s*"([^"\n]+)"\s*\)', source[source.index('val MIGRATION_3_4'):source.index('val MIGRATION_4_5')]):
    db.execute(sql)
for entity in v4['entities']:
    actual = {row[1]: (row[2], bool(row[3])) for row in db.execute(f'PRAGMA table_info(`{entity["tableName"]}`)')}
    expected = {f['columnName']: (f['affinity'], f.get('notNull', False)) for f in entity['fields']}
    assert actual == expected, entity['tableName']
assert db.execute('SELECT amountHalala,source,merchant,importKey FROM expenses').fetchone() == (12345, 'MANUAL', '', None)
assert db.execute('SELECT COUNT(*) FROM imported_sms').fetchone()[0] == 0
print('PASS: migration 3→4 preserves historical expenses and adds SMS deduplication')

for sql in re.findall(r'db.execSQL\(\s*"([^"\n]+)"\s*\)', source[source.index('val MIGRATION_4_5'):source.index('val MIGRATION_5_6')]):
    db.execute(sql)
for entity in v5['entities']:
    actual = {row[1]: (row[2], bool(row[3])) for row in db.execute(f'PRAGMA table_info(`{entity["tableName"]}`)')}
    expected = {f['columnName']: (f['affinity'], f.get('notNull', False)) for f in entity['fields']}
    assert actual == expected, entity['tableName']
db.execute("INSERT INTO trip_details VALUES (1,'trip','note',1)")
print('PASS: migration 4→5 adds trip titles, notes and favorites without altering old rows')

for sql in re.findall(r'db.execSQL\(\s*"([^"\n]+)"\s*\)', source[source.index('val MIGRATION_5_6'):]):
    db.execute(sql)
for entity in v6['entities']:
    actual = {row[1]: (row[2], bool(row[3])) for row in db.execute(f'PRAGMA table_info(`{entity["tableName"]}`)')}
    expected = {f['columnName']: (f['affinity'], f.get('notNull', False)) for f in entity['fields']}
    assert actual == expected, entity['tableName']
assert db.execute('SELECT odometerMeters FROM fuel').fetchone() == (None,)
assert db.execute('SELECT amountHalala FROM expenses').fetchone() == (12345,)
print('PASS: migration 5→6 preserves history and adds odometer, merchant rules and recurring schedules')

db.execute('DELETE FROM outings WHERE id=1')
for table in ('points', 'events', 'active_slot', 'trip_details'):
    assert db.execute(f'SELECT COUNT(*) FROM {table}').fetchone()[0] == 0
assert db.execute('SELECT outingId,amountHalala FROM expenses').fetchone() == (None, 12345)
assert db.execute('SELECT COUNT(*) FROM fuel').fetchone()[0] == 1
assert not db.execute('PRAGMA foreign_key_check').fetchall()
print('PASS: outing cascade preserves spending and unlinks expenses')
db.execute('DELETE FROM expenses WHERE id=1')
assert db.execute('SELECT COUNT(*) FROM fuel').fetchone()[0] == 0
print('PASS: expense deletion cascades its fuel record')

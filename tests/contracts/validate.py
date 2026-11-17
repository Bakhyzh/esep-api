#!/usr/bin/env python3
"""Validate actual committed producer payloads, without printing identity or financial contents."""
import argparse
from decimal import Decimal
import json
from pathlib import Path
import sys
from jsonschema import Draft202012Validator,FormatChecker
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'infrastructure/ops'))
import ops_common as ops
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--live',action='store_true');args=parser.parse_args()
validators={}
for path in (ROOT/'contracts/events').glob('*.schema.json'):
    schema=json.loads(path.read_text(),parse_float=Decimal);Draft202012Validator.check_schema(schema)
    kind=schema['properties']['eventType']['const'];validators[kind]=Draft202012Validator(schema,format_checker=FormatChecker())
print('PASS: four versioned schemas valid against Draft2020-12')
if args.live:
    ops.local_daemon();private=ops.environment();counts={key:0 for key in validators}
    for database in ['user_db','ledger_db']:
        text=ops.sql(database,"SELECT payload::text FROM outbox_events ORDER BY id DESC LIMIT 500;",'platform_backup',private['PLATFORM_BACKUP_PASSWORD'])
        for line in text.splitlines():
            event=json.loads(line,parse_float=Decimal);kind=event.get('eventType')
            if kind not in validators:raise RuntimeError('Unknown committed event contract')
            errors=list(validators[kind].iter_errors(event))
            if errors:raise RuntimeError('Committed contract validation failed for '+kind+' at '+str(list(errors[0].path)))
            counts[kind]+=1
    if any(count==0 for count in counts.values()):raise RuntimeError('A real producer contract had no committed sample')
    print(json.dumps({'result':'PASS','actual_committed_event_samples':counts,'payloads_logged':False}))

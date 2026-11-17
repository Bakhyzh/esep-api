#!/usr/bin/env python3
"""Switch ONLY the fenced synthetic local broker after an actual clone + rollback laboratory pass."""
import argparse
import json
from pathlib import Path
import runpy
import subprocess
import sys
ROOT=Path(__file__).resolve().parents[3];sys.path.insert(0,str(ROOT/'infrastructure/ops'))
import ops_common as ops
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--verified-lab-result',type=Path,required=True);args=p.parse_args()
ops.local_daemon();ops.quiescent()
result_path=args.verified_lab_result.resolve()
if not result_path.is_relative_to(ops.BACKUPS/'kafka-upgrade'):raise SystemExit('Expected our private, verified synthetic upgrade artifact')
lab=json.loads(result_path.read_text());image=json.loads(ops.run(['docker','image','inspect','esep-platform-kafka:local']))[0]
source=ops.container('esep-platform-local','kafka')
if lab.get('result')!='PASS' or lab['outcomes'][0]['imageId']!=image['Id'] or lab['outcomes'][1]['imageId']!=source['Image']:
    raise SystemExit('Candidate/rollback images differ from actual laboratory verification')
helpers=runpy.run_path(str(ROOT/'infrastructure/platform/kafka/upgrade-lab.py'))
ops.run(['docker','start',source['Id']]);helpers['ready'](source['Id']);before=helpers['state'](source['Id'])
if before['metadataVersion']!=lab['sourceMetadataVersion']:raise SystemExit('Original metadata feature level changed')
ops.run(['docker','stop','--timeout','30',source['Id']])
subprocess.run(ops.PLATFORM+['up','-d','--no-deps','--no-build','--wait','--wait-timeout','180','kafka'],check=True)
current=ops.container('esep-platform-local','kafka');after=helpers['state'](current['Id'])
if after!=before:
    ops.run(['docker','stop','--timeout','30',current['Id']]);raise SystemExit('Offsets/metadata differ; broker fenced, verified offline backup retained')
subprocess.run([sys.executable,str(ROOT/'tests/e2e/kafka-security.py')],check=True)
print(json.dumps({'result':'PASS','scope':'synthetic-local-only','imageId':current['Image'],'businessPartitions':len(after['ends']),
    'committedGroupPartitions':len(after['commits']),'metadataVersionRetained':after['metadataVersion'],'financialDatabasesModified':False,
    'productionChanged':False,'rollbackLaboratoryVerified':True}))

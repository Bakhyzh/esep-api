#!/usr/bin/env python3
"""Scan an exported image without exposing Docker socket, private workspace, credentials or live containers."""
import argparse
import json
import hashlib
from pathlib import Path
import re
import subprocess
import tempfile
SCANNER='aquasec/trivy:0.76.2@sha256:8f6bb947dbc528902e11f2f5be52f7b519f6a39d5d148f41166f2040499768fb'
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--image',required=True);p.add_argument('--output-dir',type=Path,required=True);args=p.parse_args()
if not re.fullmatch(r'[A-Za-z0-9_./:@-]+',args.image):raise SystemExit('Invalid image reference')
args.output_dir.mkdir(parents=True,exist_ok=True);destination=args.output_dir.resolve();name=args.image.replace('/','_').replace(':','_')
with tempfile.TemporaryDirectory(prefix='esep-image-scan-') as directory:
    source=Path(directory);image=json.loads(subprocess.check_output(['docker','image','inspect',args.image]))[0]
    retained=subprocess.check_output(['docker','ps','-aq','--filter','label=esep.verified-image='+image['Id']],text=True).strip()
    if not retained:
        subprocess.run(['docker','create','--label','esep.scope=verification-artifact','--label','esep.verified-image='+image['Id'],
            '--network','none','--entrypoint','sh',image['Id'],'-c','exit 0'],check=True,stdout=subprocess.DEVNULL)
    subprocess.run(['docker','save','-o',str(source/'image.tar'),image['Id']],check=True)
    digest=hashlib.file_digest((source/'image.tar').open('rb'),'sha256').hexdigest()
    result=subprocess.run(['docker','run','--rm','--cap-drop','ALL','--security-opt','no-new-privileges:true',
        '--memory','768m','--cpus','2','-e','TRIVY_SKIP_VERSION_CHECK=true',
        '-v',str(source)+':/input:ro','-v',str(destination)+':/results','-v','esep-security-cache:/cache',SCANNER,
        '--cache-dir','/cache','image','--input','/input/image.tar','--scanners','vuln','--parallel','2','--timeout','10m',
        '--format','json','--output','/results/'+name+'.json'],check=True)
report=json.loads((destination/(name+'.json')).read_text())
if report['Metadata']['DiffIDs']!=image['RootFS']['Layers']:raise RuntimeError('Scanned filesystem differs from exported image')
(destination/(name+'.provenance.json')).write_text(json.dumps({'dockerImageId':image['Id'],'diffIds':image['RootFS']['Layers'],'exportSha256':digest},indent=2)+'\n')
vulnerabilities=[v for r in report.get('Results',[]) for v in r.get('Vulnerabilities',[])]
counts={level:sum(v.get('Severity')==level for v in vulnerabilities) for level in ['CRITICAL','HIGH','MEDIUM','LOW','UNKNOWN']}
print(json.dumps({'image':args.image,'scanner':SCANNER,'actual_findings':counts,'critical_gate':'FAIL' if counts['CRITICAL'] else 'PASS'}))
raise SystemExit(1 if counts['CRITICAL'] else 0)

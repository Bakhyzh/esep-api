#!/usr/bin/env python3
"""Exercise a security-patched broker and rollback on an encrypted, offline synthetic clone only."""
from datetime import datetime,timezone
import hashlib
import io
import json
import os
from pathlib import Path
import re
import runpy
import secrets
import shutil
import subprocess
import sys
import time
ROOT=Path(__file__).resolve().parents[3];sys.path.insert(0,str(ROOT/'infrastructure/ops'))
import ops_common as ops
HELPER='postgres:17.11-alpine@sha256:562a6e0a66b5136bc28bef5944bbc2ddddf8ea0058d4016b96cee63f2edaf143'

def state(target):
    prefix=['docker','exec','-e','KAFKA_HEAP_OPTS=-Xms16m -Xmx64m',target]
    common=['--bootstrap-server','kafka:9092','--command-config','/tmp/esep-kafka-admin.properties']
    ends=ops.run(prefix+['/opt/kafka/bin/kafka-get-offsets.sh',*common,'--topic','^esep\\..*','--time','-1']).decode()
    groups=ops.run(prefix+['/opt/kafka/bin/kafka-consumer-groups.sh',*common,'--describe','--all-groups','--offsets']).decode()
    commits={}
    for line in groups.splitlines():
        fields=line.split()
        if len(fields)>=6 and fields[0]!='GROUP' and fields[1].startswith('esep.') and fields[3].isdigit():commits['/'.join(fields[:3])]=fields[3]
    listed=set(ops.run(prefix+['/opt/kafka/bin/kafka-consumer-groups.sh',*common,'--list']).decode().splitlines())
    if listed!={key.split('/')[0] for key in commits}:raise RuntimeError('Consumer group offset snapshot incomplete')
    features=ops.run(prefix+['/opt/kafka/bin/kafka-features.sh',*common,'describe']).decode()
    match=re.search(r'Feature:\s*metadata.version[^\n]*FinalizedVersionLevel:\s*(\S+)',features)
    if not match:raise RuntimeError('Metadata feature level unavailable')
    return {'ends':sorted(x for x in ends.splitlines() if x.startswith('esep.')),'commits':commits,'metadataVersion':match.group(1)}

def ready(target):
    deadline=time.monotonic()+120
    while time.monotonic()<deadline:
        running=json.loads(ops.run(['docker','inspect',target]))[0]['State']['Running']
        if not running:raise RuntimeError('Isolated broker exited during startup')
        result=subprocess.run(['docker','exec','-e','KAFKA_HEAP_OPTS=-Xms16m -Xmx64m',target,
            '/opt/kafka/bin/kafka-broker-api-versions.sh','--bootstrap-server','kafka:9092','--command-config','/tmp/esep-kafka-admin.properties'],capture_output=True)
        if result.returncode==0:return
        time.sleep(1)
    raise RuntimeError('Isolated broker readiness timeout')

def main():
    if sys.argv[1:]!=['--synthetic-clone-only']:raise RuntimeError('Explicit clone-only laboratory required')
    ops.local_daemon();ops.quiescent()
    source=ops.container('esep-platform-local','kafka');source_id=source['Id']
    source_env=dict(v.split('=',1) for v in source['Config']['Env'])
    if source_env.get('KAFKA_LOG_DIRS')!='/var/lib/kafka/data':raise RuntimeError('Preserve legacy writable-layer logs first')
    report=ROOT/'.platform-lab/security-scans/esep-platform-kafka_candidate.json'
    scan=json.loads(report.read_text());image=json.loads(ops.run(['docker','image','inspect','esep-platform-kafka:candidate']))[0]
    provenance=json.loads(report.with_suffix('.provenance.json').read_text())
    if provenance['dockerImageId']!=image['Id'] or scan['Metadata']['DiffIDs']!=image['RootFS']['Layers'] or any(v['Severity']=='CRITICAL' for r in scan['Results'] for v in r.get('Vulnerabilities',[])):
        raise RuntimeError('Candidate differs from the actual critical-free scan')
    if shutil.disk_usage(ROOT).free<2*1024**3:raise RuntimeError('Insufficient safe laboratory disk headroom')
    ops.run(['docker','start',source_id]);ready(source_id);before=state(source_id);ops.run(['docker','stop','--timeout','30',source_id])
    nonce=secrets.token_hex(6);volume='esep-kafka-upgrade-'+nonce
    source_volume='esep-platform-local_platform-kafka'
    if not any(m.get('Name')==source_volume for m in source['Mounts']):raise RuntimeError('Wrong source broker volume')
    snapshot=ops.run(['docker','run','--rm','--network','none','-v',source_volume+':/source:ro','--entrypoint','sh',HELPER,'-c','tar -cf - -C /source .'])
    facts=runpy.run_path(str(ROOT/'infrastructure/platform/kafka/preserve-local.py'))['facts'];original=facts(snapshot)
    parent=ops.BACKUPS/'kafka-upgrade';ops.private_dir(parent);backup=parent/(datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'-'+nonce);backup.mkdir(mode=0o700)
    path=backup/'snapshot.tar.age';path.write_bytes(ops.run(['age','-r',ops.keys()],data=snapshot));path.chmod(0o600)
    restored=ops.run(['age','-d','-i',str(ops.KEY),str(path)])
    if restored!=snapshot:raise RuntimeError('Encrypted backup verification failed')
    ops.run(['docker','volume','create','--label','esep.scope=synthetic-kafka-upgrade',volume])
    ops.run(['docker','run','--rm','-i','--network','none','--user','0','-v',volume+':/target','--entrypoint','sh',HELPER,'-c','test -z "$(ls -A /target)" || exit 1; tar -xpf - -C /target; chown -R 1000:1000 /target'],data=restored)
    copied=ops.run(['docker','run','--rm','--network','none','-v',volume+':/target:ro','--entrypoint','sh',HELPER,'-c','tar -cf - -C /target .'])
    if facts(copied)!=original:raise RuntimeError('Offline cloned files differ')
    env=dict(os.environ,**{k:v for k,v in source_env.items() if k.startswith('KAFKA_') or k=='CLUSTER_ID'})
    def launch(version):
        command=['docker','run','--rm','-d','--network','none','--hostname','kafka','--add-host','kafka:127.0.0.1','--memory','448m','--cpus','2',
            '--label','com.docker.compose.project=esep-kafka-upgrade-lab','--label','com.docker.compose.service=kafka','--label','esep.scope=synthetic-kafka-upgrade','--label','esep.lab='+nonce,
            '-v',volume+':/var/lib/kafka/data','-v',str(ROOT/'infrastructure/platform/kafka/start.sh')+':/opt/esep/kafka/start.sh:ro','--tmpfs','/tmp:size=128m,mode=1777']
        for name in env:
            if name.startswith('KAFKA_') or name=='CLUSTER_ID':command+=['-e',name]
        return ops.run(command+[version,'bash','/opt/esep/kafka/start.sh'],env=env).decode().strip()
    outcomes=[]
    for label,version in [('candidate',image['Id']),('rollback',source['Image'])]:
        target=launch(version)
        try:
            ready(target)
            if state(target)!=before:raise RuntimeError('Offsets, groups or metadata changed in '+label+' validation')
            subprocess.run([sys.executable,str(ROOT/'tests/e2e/kafka-security.py'),'--project','esep-kafka-upgrade-lab'],check=True)
            outcomes.append({'stage':label,'imageId':version,'offsetsGroupsMetadataPreserved':True,'authorizationProbes':'PASS'})
        finally:
            current=json.loads(ops.run(['docker','inspect',target]))[0]
            if current['Config']['Labels'].get('esep.lab')!=nonce:raise RuntimeError('Refusing unrelated fixture cleanup')
            ops.run(['docker','stop','--timeout','30',target])
    result={'result':'PASS','sourceUntouchedByCandidate':True,'encryptedBackupVerified':True,'filesCloned':len(original),'sourceMetadataVersion':before['metadataVersion'],
        'businessPartitions':len(before['ends']),'committedGroupPartitions':len(before['commits']),'cloneVolumeRetained':volume,'financialDatabasesAccessed':False,'outcomes':outcomes}
    (backup/'lab-result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
if __name__=='__main__':
    try:main()
    except Exception as error:
        print('FAIL: '+(str(error) if isinstance(error,RuntimeError) else type(error).__name__));raise SystemExit(1)

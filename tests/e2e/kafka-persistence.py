#!/usr/bin/env python3
"""Verify actual broker recreation preserves topic offsets, committed groups and ACLs. Synthetic project only."""
import json
from pathlib import Path
import subprocess
import sys
import time
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'infrastructure/ops'))
import ops_common as ops

def main():
    if sys.argv[1:]!=['--recreate-local']:raise RuntimeError('Explicit isolated recreation required')
    ops.local_daemon();ops.quiescent()
    def snapshots():
        c=ops.container('esep-platform-local','kafka');env=dict(v.split('=',1) for v in c['Config']['Env'])
        if env.get('KAFKA_LOG_DIRS')!='/var/lib/kafka/data':raise RuntimeError('Persistent broker storage required')
        prefix=['docker','exec','-e','KAFKA_HEAP_OPTS=-Xms16m -Xmx64m',c['Id']]
        common=['--bootstrap-server','kafka:9092','--command-config','/tmp/esep-kafka-admin.properties']
        offsets=ops.run(prefix+['/opt/kafka/bin/kafka-get-offsets.sh',*common,'--topic','^esep\\..*','--time','-1']).decode()
        groups=ops.run(prefix+['/opt/kafka/bin/kafka-consumer-groups.sh',*common,'--describe','--all-groups','--offsets']).decode()
        commits={}
        for line in groups.splitlines():
            fields=line.split()
            if len(fields)>=6 and fields[0]!='GROUP' and fields[1].startswith('esep.') and fields[3].isdigit():
                commits['/'.join(fields[:3])]=fields[3]
        ends=sorted(line.strip() for line in offsets.splitlines() if line.startswith('esep.'))
        if not ends:raise RuntimeError('No actual Kafka topic offsets collected')
        return ends,commits
    deadline=time.monotonic()+120
    while ops.container('esep-platform-local','kafka')['State'].get('Health',{}).get('Status')!='healthy':
        if time.monotonic()>deadline:raise RuntimeError('Broker readiness timeout before snapshot')
        time.sleep(1)
    before=snapshots();old=ops.container('esep-platform-local','kafka')['Id']
    subprocess.run(ops.PLATFORM+['up','-d','--no-deps','--force-recreate','--wait','--wait-timeout','180','kafka'],check=True,stdout=subprocess.DEVNULL)
    if ops.container('esep-platform-local','kafka')['Id']==old:raise RuntimeError('Broker was not actually recreated')
    after=snapshots()
    if after!=before:raise RuntimeError('Topic or committed consumer offsets changed across recreation')
    subprocess.run([sys.executable,str(ROOT/'tests/e2e/kafka-security.py')],check=True)
    print(json.dumps({'result':'PASS','actualBrokerRecreated':True,'businessPartitionOffsetsPreserved':len(before[0]),'committedGroupPartitionsPreserved':len(before[1]),'ACLnegativeProbesAfterRecreation':'PASS','financialDatabasesMutated':False}))
if __name__=='__main__':
    try:main()
    except Exception as error:
        print('FAIL: '+(str(error) if isinstance(error,RuntimeError) else type(error).__name__));raise SystemExit(1)

#!/usr/bin/env python3
"""One-time offline preservation of the original synthetic broker's misplaced log directory."""
import hashlib
import io
import json
from pathlib import Path,PurePosixPath
import subprocess
import sys
import tarfile
from datetime import datetime,timezone
ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'infrastructure/ops'))
import ops_common as ops

IMAGE='postgres:17.11-alpine@sha256:562a6e0a66b5136bc28bef5944bbc2ddddf8ea0058d4016b96cee63f2edaf143'
def facts(data):
    result={}
    with tarfile.open(fileobj=io.BytesIO(data)) as archive:
        for member in archive.getmembers():
            path=PurePosixPath(member.name)
            if path.is_absolute() or '..' in path.parts or not (member.isfile() or member.isdir()):raise RuntimeError('Unsafe synthetic snapshot member')
            if member.isfile():result[str(path)]=hashlib.sha256(archive.extractfile(member).read()).hexdigest()
    if 'meta.properties' not in result or len(result)<5:raise RuntimeError('Expected existing Kafka metadata and records')
    return result

def main():
    if sys.argv[1:]!=['--synthetic-platform']:raise RuntimeError('Explicit isolated synthetic platform scope required')
    ops.local_daemon()
    for service in ('user','ledger','ledger-2','analytics','notification','kafka'):
        if ops.container('esep-platform-local',service)['State']['Running']:raise RuntimeError('Stop all isolated writers/consumers and broker first')
    broker=ops.container('esep-platform-local','kafka');volume='esep-platform-local_platform-kafka'
    if not any(m.get('Name')==volume and m['Destination']=='/var/lib/kafka/data' for m in broker['Mounts']):raise RuntimeError('Wrong synthetic Kafka volume')
    helper=['docker','run','--rm','--network','none','--memory','128m','--user','0','-v',volume+':/target','--entrypoint','sh',IMAGE]
    ops.run(helper+['-c','test -z "$(ls -A /target)"'])
    snapshot=ops.run(['docker','cp',broker['Id']+':/tmp/kafka-logs/.','-'])
    if len(snapshot)>512*1024*1024:raise RuntimeError('Synthetic snapshot exceeds bounded laboratory budget')
    before=facts(snapshot);recipient=ops.keys()
    parent=ops.BACKUPS/'kafka';ops.private_dir(parent)
    destination=parent/datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ');destination.mkdir(mode=0o700)
    encrypted=ops.run(['age','--encrypt','-r',recipient],data=snapshot)
    path=destination/'snapshot.tar.age';path.write_bytes(encrypted);path.chmod(0o600)
    restored=ops.run(['age','--decrypt','-i',str(ops.KEY),str(path)])
    if restored!=snapshot:raise RuntimeError('Encrypted snapshot restore verification failed')
    # Recheck emptiness inside the copying process; never merge with or replace existing records.
    ops.run(['docker','run','--rm','-i','--network','none','--memory','128m','--user','0',
        '-v',volume+':/target','--entrypoint','sh',IMAGE,'-c',
        'test -z "$(ls -A /target)" || exit 1; tar -xpf - -C /target; chown -R 1000:1000 /target'],data=restored)
    copied=ops.run(helper+['-c','tar -cf - -C /target .'])
    if facts(copied)!=before:raise RuntimeError('Persistent copy differs from verified offline source')
    manifest={'sourceContainer':broker['Id'],'sourcePath':'/tmp/kafka-logs','targetVolume':volume,
        'files':len(before),'sourceTarSha256':hashlib.sha256(snapshot).hexdigest(),'fileHashes':before,
        'encryptedSha256':hashlib.sha256(encrypted).hexdigest(),'decryptVerified':True,'copyVerified':True,
        'financialDatabasesAccessed':False}
    (destination/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print('PASS: encrypted offline Kafka snapshot decrypts exactly; all',len(before),'files match persistent volume; source and volumes retained')
if __name__=='__main__':
    try:main()
    except Exception as error:
        print('FAIL: '+(str(error) if isinstance(error,RuntimeError) else type(error).__name__));raise SystemExit(1)

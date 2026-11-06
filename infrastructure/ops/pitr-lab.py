#!/usr/bin/env python3
"""Verified physical backup + named-point WAL recovery, using NEW synthetic volumes only. No source financial DB access."""
from datetime import datetime,timezone
import json
import os
import secrets
import subprocess
import time
from ops_common import local_daemon,run

IMAGE='postgres:17.11-alpine@sha256:562a6e0a66b5136bc28bef5944bbc2ddddf8ea0058d4016b96cee63f2edaf143'

def wait(check,description,seconds=60):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        if check():return
        time.sleep(.25)
    raise RuntimeError('PITR lab timeout: '+description)

def main():
    started=time.monotonic();local_daemon()
    identity='esep-pitr-'+datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')+'-'+secrets.token_hex(4)
    volumes={name:identity+'-'+name for name in ('primary','archive','recovery')}
    created=[];env=os.environ.copy();env['POSTGRES_PASSWORD']=secrets.token_urlsafe(36)
    for volume in volumes.values():
        exists=subprocess.run(['docker','volume','inspect',volume],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        if exists.returncode==0:raise RuntimeError('Refusing to reuse an existing laboratory volume')
        run(['docker','volume','create','--label','esep.scope=synthetic-pitr','--label','esep.lab='+identity,volume])
    # Only these freshly created empty lab volumes are initialized; existing development volumes are never mounted.
    run(['docker','run','--rm','--network','none','--entrypoint','sh','-v',volumes['archive']+':/archive',
         '-v',volumes['recovery']+':/backup',IMAGE,'-c','chown postgres:postgres /archive /backup'])
    def sql(container,query):
        return run(['docker','exec','-i',container,'psql','-X','-A','-t','-q','-v','ON_ERROR_STOP=1','-U','lab_owner','-d','postgres'],data=query.encode()).decode().strip()
    def healthy(container):
        return subprocess.run(['docker','exec',container,'pg_isready','-U','lab_owner'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0
    def launch(name,mounts,extra):
        command=['docker','run','-d','--name',name,'--network','none','--memory','256m','--cpus','1',
                 '--label','esep.scope=synthetic-pitr','--label','esep.lab='+identity,'-e','POSTGRES_PASSWORD','-e','POSTGRES_USER=lab_owner']
        for mount in mounts:command+=['-v',mount]
        command+=extra
        result=run(command,env=env).decode().strip();created.append(result);return result
    try:
        primary=launch(identity+'-primary',[volumes['primary']+':/var/lib/postgresql/data',volumes['archive']+':/archive',volumes['recovery']+':/backup'],
            [IMAGE,'postgres','-c','wal_level=replica','-c','archive_mode=on','-c',
             'archive_command=test -f /archive/%f || (cp %p /archive/%f.tmp && mv /archive/%f.tmp /archive/%f)'])
        wait(lambda:healthy(primary),'new primary readiness')
        sql(primary,"CREATE TABLE lab_events(marker text PRIMARY KEY); INSERT INTO lab_events VALUES ('keep');")
        run(['docker','exec','-u','postgres',primary,'pg_basebackup','-h','/var/run/postgresql','-U','lab_owner',
             '-D','/backup/recovered','--checkpoint=fast','--wal-method=stream','--manifest-checksums=SHA256','--no-password'])
        run(['docker','exec','-u','postgres',primary,'pg_verifybackup','/backup/recovered'])
        lsn=sql(primary,"SELECT pg_create_restore_point('esep_keep');")
        sql(primary,"INSERT INTO lab_events VALUES ('exclude'); SELECT pg_switch_wal();")
        wait(lambda:bool(sql(primary,'SELECT last_archived_wal FROM pg_stat_archiver;')),'WAL archived')
        if sql(primary,'SELECT string_agg(marker,\',\' ORDER BY marker) FROM lab_events;')!='exclude,keep':raise RuntimeError('Lab source markers missing')
        config="""cat >> /backup/recovered/postgresql.auto.conf <<'CONF'
restore_command = 'cp /archive/%f %p'
recovery_target_name = 'esep_keep'
recovery_target_action = 'promote'
CONF
touch /backup/recovered/recovery.signal
"""
        run(['docker','exec','-i','-u','postgres',primary,'sh','-c',config])
        run(['docker','stop','--time','30',primary])
        recovery=launch(identity+'-recovery',[volumes['recovery']+':/var/lib/postgresql/data',volumes['archive']+':/archive:ro'],
            ['-e','PGDATA=/var/lib/postgresql/data/recovered',IMAGE,'postgres'])
        wait(lambda:healthy(recovery),'named-point recovery')
        wait(lambda:sql(recovery,'SELECT pg_is_in_recovery();')=='f','recovery target promotion')
        if sql(recovery,'SELECT string_agg(marker,\',\' ORDER BY marker) FROM lab_events;')!='keep':
            raise RuntimeError('PITR did not exclude the post-target transaction')
        print(json.dumps({'pitrVerified':True,'baseBackupVerified':True,'target':'esep_keep','targetLsn':lsn,
            'sourceMarkers':['keep','exclude'],'recoveredMarkers':['keep'],'seconds':round(time.monotonic()-started,3),
            'volumesRetained':volumes,'financialDatabasesAccessed':False,'highAvailabilityProven':False}))
    finally:
        for container in created:
            model=json.loads(run(['docker','inspect',container]))[0]
            if model['Config']['Labels'].get('esep.lab')!=identity:raise RuntimeError('Refusing to stop an unrelated container')
            subprocess.run(['docker','stop','--time','30',container],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
if __name__=='__main__':
    try:main()
    except Exception as error:
        print('FAIL: '+(str(error) if isinstance(error,RuntimeError) else type(error).__name__));raise SystemExit(1)

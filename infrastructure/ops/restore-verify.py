#!/usr/bin/env python3
"""Authenticate then restore ONLY into new isolated databases; never overwrite a source database."""
import argparse
from datetime import datetime,timezone
import json
from pathlib import Path
import secrets
import subprocess
import time
from ops_common import *

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('backup',type=Path);args=parser.parse_args()
    started=time.monotonic();local_daemon();keys(create=False);data=manifest(args.backup);env=environment()
    receipt=[];prefix='restore_verify_'+datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')+'_'+secrets.token_hex(4)+'_'
    for source,item in data['databases'].items():
        target=prefix+source
        if not target.startswith('restore_verify_') or target in (*DATABASES,'keycloak_db'):raise RuntimeError('Unsafe restore target')
        # Only the DBA creates the new empty database; pg_restore itself uses a restricted, non-superuser owner.
        sql('postgres','CREATE DATABASE '+identifier(target)+' OWNER platform_restore;', 'platform_bootstrap',env['PLATFORM_DB_BOOTSTRAP_PASSWORD'])
        sql(target,'REVOKE CONNECT ON DATABASE '+identifier(target)+' FROM PUBLIC; DROP SCHEMA public;',
            'platform_restore',env['PLATFORM_RESTORE_PASSWORD'])
        # This brand-new random DB has no business tables; the authenticated dump recreates its public schema.
        # DROP has no CASCADE and can never target an existing source DB (CREATE DATABASE above must succeed).
        decrypt=subprocess.Popen(['age','-d','-i',str(KEY),str(args.backup/(source+'.dump.age'))],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
        restore=subprocess.Popen(db_command(target,'platform_restore','pg_restore',False,('--exit-on-error','--single-transaction','--no-owner','--no-privileges')),
            env=password_env(env['PLATFORM_RESTORE_PASSWORD']),stdin=decrypt.stdout,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
        decrypt.stdout.close();_,error=restore.communicate();restored=restore.returncode;decrypted=decrypt.wait()
        if restored or decrypted:
            known=['schema "public" already exists','must be owner of schema public','permission denied for schema public','must be owner of extension','unrecognized configuration parameter','permission denied for table']
            category=next((text for text in known if text in error.decode(errors='replace')),'protected details suppressed')
            raise RuntimeError('Isolated restore failed: '+category+'; source unchanged, target retained')
        actual=facts(target,'platform_restore',env['PLATFORM_RESTORE_PASSWORD'])
        if actual!=item['facts']:raise RuntimeError('Restored rows or sequence values differ; source unchanged')
        if source=='ledger_db':
            query=(ROOT/'tests/financial/ledger-invariants.sql').read_text()
            sql(target,query,'platform_restore',env['PLATFORM_RESTORE_PASSWORD'])
        receipt.append({'source':source,'target':target,'tables':len(actual['tables']),'rows':sum(t['rows'] for t in actual['tables'].values()),'sequences':len(actual['sequences'])})
    print(json.dumps({'verified':True,'restores':receipt,'seconds':round(time.monotonic()-started,3),'sourcesModified':False}))
if __name__=='__main__':
    try:main()
    except Exception as error:
        print('FAIL: '+(str(error) if isinstance(error,RuntimeError) else type(error).__name__));raise SystemExit(1)

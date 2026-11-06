#!/usr/bin/env python3
"""Consistent encrypted backup of explicitly quiescent synthetic databases. No data or credentials are printed."""
import argparse
from datetime import datetime,timezone
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
from ops_common import *

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--include-keycloak',action='store_true')
    parser.add_argument('--identity-dir',type=Path)
    args=parser.parse_args()
    if args.include_keycloak and not args.identity_dir:raise RuntimeError('Provider backup requires its verified private environment directory')
    started=time.monotonic();quiescent(args.include_keycloak);recipient=keys();env=environment()
    provider=environment(args.identity_dir/'.env') if args.include_keycloak else None
    directory=BACKUPS/('esep-backup-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'-'+secrets.token_hex(4));private_dir(directory)
    result={'format':1,'project':'esep-platform-local','createdAt':datetime.now(timezone.utc).isoformat(),
            'sourceCommit':run(['git','-C',str(ROOT),'rev-parse','HEAD']).decode().strip(),'databases':{}}
    for database in (*DATABASES,*(('keycloak_db',) if args.include_keycloak else ())):
        is_provider=database=='keycloak_db';user='keycloak' if is_provider else 'platform_backup'
        password=provider['KC_DB_PASSWORD'] if is_provider else env['PLATFORM_BACKUP_PASSWORD']
        before=facts(database,user,password,is_provider)
        dump=subprocess.Popen(db_command(database,user,'pg_dump',is_provider,('-Fc','--no-owner','--no-privileges','--schema=public','--exclude-extension=pg_stat_statements')),
            env=password_env(password),stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
        path=directory/(database+'.dump.age')
        with os.fdopen(os.open(path,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600),'wb') as output:
            encrypt=subprocess.Popen(['age','-r',recipient],stdin=dump.stdout,stdout=output,stderr=subprocess.DEVNULL)
            dump.stdout.close();encrypted=encrypt.wait();dumped=dump.wait()
        if encrypted or dumped:raise RuntimeError('Encrypted database stream failed; incomplete backup is not usable')
        after=facts(database,user,password,is_provider)
        if before!=after:raise RuntimeError('Source changed during backup; refuse consistency claim')
        result['databases'][database]={'facts':before,'ciphertextSha256':sha(path)}
    quiescent(args.include_keycloak)
    encrypt_bytes(json.dumps(result,sort_keys=True).encode(),directory/'manifest.json.age',recipient)
    manifest(directory) # Authenticate every complete encrypted stream before declaring success.
    print(json.dumps({'backup':str(directory),'databases':len(result['databases']),'seconds':round(time.monotonic()-started,3),'authenticated':True}))
if __name__=='__main__':
    try:main()
    except Exception as error:
        print('FAIL: '+(str(error) if isinstance(error,RuntimeError) else type(error).__name__));raise SystemExit(1)

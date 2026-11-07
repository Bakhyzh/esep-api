#!/usr/bin/env python3
"""Create private local credentials once; never rotate an existing DB or overwrite its environment."""
import argparse
import json
import os
from pathlib import Path
import secrets

root=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--frontend-dir',required=True,type=Path,help='Verified esep-web checkout containing its Dockerfile')
args=parser.parse_args()
frontend=args.frontend_dir.resolve()
if json.loads((frontend/'package.json').read_text()).get('name')!='esep-web' or not (frontend/'Dockerfile').is_file():
    raise SystemExit('Expected the verified esep-web platform checkout')
with os.fdopen(os.open(root/'.env.platform',os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600),'w') as f:
    for key in ['PLATFORM_DB_BOOTSTRAP_PASSWORD','USER_MIGRATION_PASSWORD','USER_DB_PASSWORD',
                'LEDGER_MIGRATION_PASSWORD','LEDGER_DB_PASSWORD','LEDGER_IDENTITY_DB_PASSWORD',
                'NOTIFICATION_MIGRATION_PASSWORD','NOTIFICATION_DB_PASSWORD','NOTIFICATION_IDENTITY_DB_PASSWORD',
                'ANALYTICS_MIGRATION_PASSWORD','ANALYTICS_DB_PASSWORD','ANALYTICS_IDENTITY_DB_PASSWORD','REDIS_PASSWORD',
                'PLATFORM_BACKUP_PASSWORD','PLATFORM_OPS_PASSWORD','PLATFORM_RESTORE_PASSWORD','GRAFANA_ADMIN_PASSWORD']:
        f.write(key+'='+secrets.token_urlsafe(36)+'\n')
    f.write('PLATFORM_FRONTEND_CONTEXT='+str(frontend)+'\n')
print('Created private .env.platform; no existing credentials or data changed.')

#!/usr/bin/env python3
"""Create private local platform credentials once. Never rotates an existing DB."""
import os
from pathlib import Path
import secrets
root=Path(__file__).resolve().parents[2]
with os.fdopen(os.open(root/'.env.platform',os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600),'w') as f:
    for key in ['PLATFORM_DB_BOOTSTRAP_PASSWORD','USER_MIGRATION_PASSWORD','USER_DB_PASSWORD',
                'LEDGER_MIGRATION_PASSWORD','LEDGER_DB_PASSWORD','LEDGER_IDENTITY_DB_PASSWORD']:
        f.write(key+'='+secrets.token_urlsafe(36)+'\n')
print('Created private .env.platform; no existing credentials or data changed.')

#!/usr/bin/env python3
"""Append missing local broker credentials once; never rotate/overwrite existing secrets."""
import fcntl
import os
from pathlib import Path
import re
import secrets

ROOT=Path(__file__).resolve().parents[3]
path=ROOT/'.env.platform'
if path.is_symlink() or path.stat().st_mode&0o077:raise SystemExit('Private0600 environment required')
keys=['KAFKA_'+role+'_PASSWORD' for role in ('BROKER','ADMIN','USER','LEDGER','NOTIFICATION','ANALYTICS','MONITOR')]
with os.fdopen(os.open(path,os.O_RDWR|os.O_APPEND|os.O_NOFOLLOW),'r+') as stream:
    fcntl.flock(stream,fcntl.LOCK_EX)
    values=dict(line.split('=',1) for line in stream.read().splitlines() if line and not line.startswith('#'))
    for key in keys:
        if key in values and not re.fullmatch(r'[A-Za-z0-9_-]{40,100}',values[key]):raise SystemExit('Unexpected broker credential format')
    missing=[key for key in keys if key not in values]
    for key in missing:stream.write(key+'='+secrets.token_urlsafe(36)+'\n')
    stream.flush();os.fsync(stream.fileno())
print('Added missing private broker credentials:',len(missing),'; existing credentials preserved')

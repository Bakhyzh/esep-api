#!/usr/bin/env python3
"""Authorize only a NEW empty synthetic broker volume; never replace an existing broker/volume."""
import json
from pathlib import Path
import subprocess
import sys
ROOT=Path(__file__).resolve().parents[3];sys.path.insert(0,str(ROOT/'infrastructure/ops'))
import ops_common as ops
ops.local_daemon()
if sys.argv[1:]!=['--new-synthetic']:raise SystemExit('Explicit --new-synthetic required')
name='esep-platform-local_platform-kafka'
existing=ops.run(['docker','ps','-aq','--filter','label=com.docker.compose.project=esep-platform-local','--filter','label=com.docker.compose.service=kafka']).strip()
if existing:raise SystemExit('Existing broker detected: preserve its storage before any recreation')
if subprocess.run(['docker','volume','inspect',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:
    raise SystemExit('Existing volume retained; fresh initialization refused')
ops.run(['docker','volume','create','--label','com.docker.compose.project=esep-platform-local','--label','com.docker.compose.volume=platform-kafka','--label','esep.scope=synthetic-platform',name])
ops.run(['docker','run','--rm','--network','none','--user','0','-v',name+':/target','--entrypoint','sh',
    'postgres:17.11-alpine@sha256:562a6e0a66b5136bc28bef5944bbc2ddddf8ea0058d4016b96cee63f2edaf143','-c',
    'test -z "$(ls -A /target)" || exit 1; touch /target/.esep-fresh-authorized; chown 1000:1000 /target /target/.esep-fresh-authorized'])
print('Authorized new empty synthetic Kafka volume once; no existing volume or broker was reused')

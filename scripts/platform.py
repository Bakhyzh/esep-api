#!/usr/bin/env python3
"""Guarded LOCAL platform lifecycle. No public deployment, production migration or volume deletion."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'infrastructure/ops'))
import ops_common as ops

def command(args,**kwargs):subprocess.run(args,check=True,cwd=ROOT,**kwargs)
def frontend(path):
    path=path.resolve();contract=json.loads((ROOT/'contracts/platform-versions.json').read_text())
    sha=subprocess.check_output(['git','-C',str(path),'rev-parse','HEAD'],text=True).strip()
    remote=subprocess.check_output(['git','-C',str(path),'remote','get-url','origin'],text=True).strip()
    if sha!=contract['frontendCommit'] or remote.removesuffix('.git') not in ('https://github.com/Bakhyzh/esep-web','git@github.com:Bakhyzh/esep-web'):
        raise RuntimeError('Expected the contract-pinned esep-web repository')
    if subprocess.check_output(['git','-C',str(path),'status','--porcelain'],text=True).strip():raise RuntimeError('Frontend has local changes; preserved, build refused')
    return path

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['init','build','start','stop','status'])
    p.add_argument('--frontend-dir',type=Path);p.add_argument('--identity-dir',type=Path,default=ROOT/'infrastructure/keycloak')
    p.add_argument('--monitoring',choices=['none','compact','full'],default='none');p.add_argument('--dashboards',action='store_true');args=p.parse_args()
    ops.local_daemon();identity=args.identity_dir.resolve()
    if args.action=='init':
        if args.frontend_dir is None:raise RuntimeError('--frontend-dir required for exact source verification')
        source=frontend(args.frontend_dir)
        if not (ROOT/'.env.platform').exists():
            if subprocess.run(['docker','volume','inspect','esep-platform-local_platform-pgdata'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:
                raise RuntimeError('Existing platform data requires its original private environment; credentials were not regenerated')
            command([sys.executable,'infrastructure/platform/init-env.py','--frontend-dir',str(source)])
        private=ops.environment()
        if Path(private['PLATFORM_FRONTEND_CONTEXT']).resolve()!=source:raise RuntimeError('Existing frontend context differs; private environment preserved')
        command([sys.executable,'infrastructure/platform/kafka/credentials.py'])
        if not (identity/'.env').exists():
            if subprocess.run(['docker','volume','inspect','esep-identity-dev_keycloak_pgdata'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:
                raise RuntimeError('Existing Keycloak data requires its original private environment; supply --identity-dir')
            command(['bash',str(identity/'keycloak.sh'),'init'])
        ops.environment(identity/'.env')
        existing=subprocess.check_output(['docker','ps','-aq','--filter','label=com.docker.compose.project=esep-platform-local','--filter','label=com.docker.compose.service=kafka'],text=True).strip()
        if not existing:
            volume='esep-platform-local_platform-kafka'
            present=subprocess.run(['docker','volume','inspect',volume],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0
            if not present:command([sys.executable,'infrastructure/platform/kafka/initialize-storage.py','--new-synthetic'])
            else:
                ops.run(['docker','run','--rm','--network','none','--read-only','-v',volume+':/target:ro','--entrypoint','sh',
                    'postgres:17.11-alpine@sha256:562a6e0a66b5136bc28bef5944bbc2ddddf8ea0058d4016b96cee63f2edaf143','-c',
                    'test -f /target/meta.properties || test -f /target/.esep-fresh-authorized'])
        print('Private local setup verified; existing data/credentials preserved');return
    private=ops.environment();ops.environment(identity/'.env')
    if args.action=='status':
        command(['docker','ps','-a','--filter','label=com.docker.compose.project=esep-platform-local','--format','{{.Names}} {{.Status}}']);return
    if args.action=='stop':
        ids=subprocess.check_output(['docker','ps','-q','--filter','label=com.docker.compose.project=esep-platform-local'],text=True).split()
        if ids:command(['docker','stop','--timeout','45',*ids])
        print('Local platform stopped; all volumes and shared Keycloak retained');return
    frontend(Path(private['PLATFORM_FRONTEND_CONTEXT']))
    # Guard BEFORE Compose can replace the old container and lose its writable-layer logs.
    ids=subprocess.check_output(['docker','ps','-aq','--filter','label=com.docker.compose.project=esep-platform-local','--filter','label=com.docker.compose.service=kafka'],text=True).split()
    if ids:
        broker=ops.container('esep-platform-local','kafka');env=dict(v.split('=',1) for v in broker['Config']['Env'])
        if env.get('KAFKA_LOG_DIRS')!='/var/lib/kafka/data':raise RuntimeError('Legacy Kafka storage detected: follow the offline preservation runbook BEFORE recreation')
    files=['compose.platform.yaml']
    if args.monitoring=='none':files+=['compose.m1.yaml']
    else:files+=['compose.monitoring.yaml']+(['compose.monitoring-compact.yaml'] if args.monitoring=='compact' else [])
    dc=['docker','compose','--env-file',str(ROOT/'.env.platform'),*[v for f in files for v in ['-f',str(ROOT/f)]]]
    if args.monitoring!='none':dc+=['--profile','monitoring']
    if args.dashboards:
        if args.monitoring=='none':raise RuntimeError('Dashboards require monitoring')
        dc+=['--profile','dashboards']
    command(dc+['config','-q'])
    if args.action=='build':
        for service in ('kafka','user','ledger','notification','analytics','gateway','web'):command(dc+['build',service])
        return
    kc=['docker','compose','--project-name','esep-identity-dev','--project-directory',str(identity),'--env-file',str(identity/'.env'),'-f',str(identity/'compose.yaml'),'-f',str(ROOT/'infrastructure/platform/compose.identity-m1.yaml')]
    secure=ROOT/'.env.identity-secure'
    if secure.exists():
        ops.environment(secure);kc+=['--env-file',str(secure),'-f',str(ROOT/'infrastructure/platform/compose.identity-security.yaml')]
    else:
        providers=subprocess.check_output(['docker','ps','-aq','--filter','label=com.docker.compose.project=esep-identity-dev','--filter','label=com.docker.compose.service=keycloak'],text=True).split()
        if providers and '26.8.2' not in ops.container('esep-identity-dev','keycloak')['Config']['Image']:
            raise RuntimeError('Existing identity data requires the verified clone upgrade; original data was not overwritten')
    command(kc+['up','-d','--wait','--wait-timeout','300'])
    command([sys.executable,'infrastructure/platform/configure-edge-client.py','--identity-dir',str(identity)])
    command(dc+['up','-d','--no-build','--wait','--wait-timeout','420'])
    print('Local platform ready: http://localhost:18081 ; no production traffic changed')
if __name__=='__main__':
    try:main()
    except Exception as error:
        print('FAIL: '+(str(error) if isinstance(error,RuntimeError) else type(error).__name__));raise SystemExit(1)

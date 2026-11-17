#!/usr/bin/env python3
"""Production template syntax + real isolated TLS edge checks. Never starts production business services."""
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
ROOT=Path(__file__).resolve().parents[2]
IMAGE='nginx:1.30.6-alpine@sha256:583b7f2fce1cdb8da5ad5c39ebd3c33b98b87d493190e548bdee1fa8c619c311'
def run(args,**kw):
    result=subprocess.run(args,capture_output=True,**kw)
    if result.returncode:
        diagnostic=result.stderr if isinstance(result.stderr,str) else result.stderr.decode(errors='replace')
        raise RuntimeError('Isolated TLS fixture failed: '+diagnostic[-2000:])
    return result
with tempfile.TemporaryDirectory(prefix='esep-tls-fixture-') as directory:
    private=Path(directory);envdir=private/'env';envdir.mkdir()
    for file in (ROOT/'infrastructure/production').glob('*.env.example'):(envdir/file.name.removesuffix('.example')).write_text('')
    env=dict(os.environ,PROD_SECRETS_DIR=directory,PROD_ENV_DIR=str(envdir),PROD_NETWORK_NAME='not-created-validation-only',
        APP_HOST='app.example.invalid',OIDC_ORIGIN='https://identity.example.invalid',GATEWAY_TLS_SERVER_NAME='gateway')
    for name in ['USER','LEDGER','NOTIFICATION','ANALYTICS','GATEWAY','WEB']:env[name+'_IMAGE']='example.invalid/esep/'+name.lower()+'@sha256:'+'0'*64
    rendered=run(['docker','compose','-f',str(ROOT/'compose.production.yaml'),'--profile','migration','config','--format','json'],env=env,text=True)
    model=json.loads(rendered.stdout)
    for name,service in model['services'].items():
        if '@sha256:' not in service['image']:raise RuntimeError('Unpinned production image')
        if name!='edge' and service.get('ports'):raise RuntimeError('Internal production service exposed')
        if name.endswith('-migrate') and service.get('environment',{}).get('SPRING_PROFILES_ACTIVE'):raise RuntimeError('Migration starts runtime context')
    if model.get('volumes'):raise RuntimeError('Production template must not mount local data volumes')
    run(['openssl','req','-x509','-newkey','rsa:2048','-nodes','-days','1','-subj','/CN=app.example.invalid',
        '-addext','subjectAltName=DNS:app.example.invalid','-keyout',str(private/'key.pem'),'-out',str(private/'cert.pem')])
    (private/'key.pem').chmod(0o644) # Ephemeral fixture only, parent directory0700; never a production key.
    nonce=secrets.token_hex(8)
    prefix=['docker','run','--rm','--network','none','--user','nginx','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true','--memory','64m',
        '--tmpfs','/tmp:size=32m,mode=1777','--add-host','gateway:127.0.0.1','--add-host','gateway-2:127.0.0.1','--add-host','web:127.0.0.1',
        '-e','APP_HOST=app.example.invalid','-e','OIDC_ORIGIN=https://identity.example.invalid','-e','GATEWAY_TLS_SERVER_NAME=gateway',
        '-v',str(ROOT/'infrastructure/production')+':/config:ro','-v',str(private/'cert.pem')+':/run/secrets/edge.crt:ro',
        '-v',str(private/'key.pem')+':/run/secrets/edge.key:ro','-v',str(private/'cert.pem')+':/run/secrets/service-ca.pem:ro']
    run(prefix+['--entrypoint','sh',IMAGE,'/config/edge-start.sh','--check'])
    identifier=run(prefix+['-d','--label','esep.scope=synthetic-tls','--label','esep.fixture='+nonce,'--entrypoint','sh',IMAGE,'/config/edge-start.sh'],text=True).stdout.strip()
    try:
        client=['docker','run','--rm','--network','container:'+identifier,'--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true','--memory','64m',
            '-v',str(private/'cert.pem')+':/fixture-ca.pem:ro','--entrypoint','curl',IMAGE,'-fsS','--connect-timeout','2','--max-time','5',
            '--resolve','app.example.invalid:8443:127.0.0.1']
        time.sleep(1)
        for version in ['1.2','1.3']:
            result=run(client+['--cacert','/fixture-ca.pem','--tlsv'+version,'--tls-max',version,'https://app.example.invalid:8443/ready'])
            if result.stdout!=b'ready':raise RuntimeError('TLS edge readiness response differs')
        untrusted=subprocess.run(client+['https://app.example.invalid:8443/ready'],capture_output=True)
        if untrusted.returncode!=60:raise RuntimeError('Untrusted certificate accepted')
        host=subprocess.run(client+['--cacert','/fixture-ca.pem','-H','Host: unrelated.invalid','https://app.example.invalid:8443/ready'],capture_output=True)
        if host.returncode==0:raise RuntimeError('Unconfigured public hostname accepted')
        print('PASS: production template syntax/immutable references; actual isolated TLS1.2/TLS1.3, CA verification and Host rejection. No production business deployment executed.')
    finally:
        model=json.loads(run(['docker','inspect',identifier],text=True).stdout)[0]
        if model['Config']['Labels'].get('esep.fixture')!=nonce:raise RuntimeError('Refusing to stop unrelated TLS container')
        run(['docker','stop','--timeout','10',identifier])

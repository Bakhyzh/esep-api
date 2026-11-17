#!/usr/bin/env python3
"""Encrypted backup, new DB clone, real PKCE/identity/key checks, then LOCAL-only provider cutover."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import runpy
import secrets
import subprocess
import sys
import time
import traceback
import urllib.request
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'infrastructure/ops'))
import ops_common as ops
IMAGE='quay.io/keycloak/keycloak:26.8.2@sha256:3879c623248408de29a5303178b5816c77e8dfef3890c361f8c0bbeaa979ba3b'

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--identity-dir',type=Path,required=True);args=parser.parse_args();identity=args.identity_dir.resolve()
    ops.local_daemon();ops.quiescent()
    if (ROOT/'.env.identity-secure').exists():raise RuntimeError('Existing verified identity cutover retained; no repeated clone created')
    original=ops.container('esep-identity-dev','keycloak');database=ops.container('esep-identity-dev','keycloak-db')
    if not any(m.get('Name')=='esep-identity-dev_keycloak_pgdata' for m in database['Mounts']):raise RuntimeError('Expected original isolated identity volume')
    image=json.loads(ops.run(['docker','image','inspect',IMAGE]))[0]
    report=ROOT/'.platform-lab/security-scans/quay.io_keycloak_keycloak_26.8.2@sha256_3879c623248408de29a5303178b5816c77e8dfef3890c361f8c0bbeaa979ba3b.json'
    scan=json.loads(report.read_text())
    if scan['Metadata']['DiffIDs']!=image['RootFS']['Layers'] or any(v['Severity']=='CRITICAL' for r in scan['Results'] for v in r.get('Vulnerabilities',[])):
        raise RuntimeError('Provider candidate does not match actual critical-free image scan')
    helper=runpy.run_path(str(ROOT/'infrastructure/keycloak/validate.py'))
    helper['DC'][helper['DC'].index('--env-file')+1]=str(identity/'.env')
    env=dict(item.split('=',1) for item in original['Config']['Env']);private=ops.environment(identity/'.env')
    def keys():
        return {realm:helper['get_json'](helper['BASE']+'/realms/'+realm+'/protocol/openid-connect/certs') for realm in ['master','esep']}
    def key_fingerprint(jwks):
        public=[{k:v for k,v in key.items() if k in ['kid','kty','use','alg','n','e','crv','x','y']} for key in jwks['keys']]
        return hashlib.sha256(json.dumps(sorted(public,key=lambda k:k['kid']),sort_keys=True).encode()).hexdigest()
    if not original['State']['Running']:
        ops.run(['docker','start',original['Id']])
        deadline=time.monotonic()+180
        while time.monotonic()<deadline:
            try:keys();break
            except Exception:time.sleep(1)
        else:raise RuntimeError('Original provider readiness timeout for verified backup')
    before_keys=keys()
    operator_tokens=helper['login']('master','security-admin-console',helper['BASE']+'/admin/master/console/',env['KC_BOOTSTRAP_ADMIN_USERNAME'],env['KC_BOOTSTRAP_ADMIN_PASSWORD'])
    before_admin=helper['verify_signature'](operator_tokens['id_token'],before_keys['master'])
    helper['verify_configuration'](operator_tokens['access_token'])
    def sql(name,query,role='keycloak',password=None):
        return ops.run(['docker','exec','-i','-e','PGPASSWORD',database['Id'],'psql','-h','127.0.0.1','-X','-Atq','-v','ON_ERROR_STOP=1','-U',role,'-d',name],env=ops.password_env(password or private['KC_DB_PASSWORD']),data=query.encode()).decode().strip()
    def fingerprints(name):
        queries={
          'users':"SELECT row_to_json(t)::text FROM (SELECT id,realm_id,username,enabled,email,email_verified,first_name,last_name FROM user_entity ORDER BY id) t;",
          'roles':"SELECT row_to_json(t)::text FROM (SELECT user_id,role_id FROM user_role_mapping ORDER BY user_id,role_id) t;",
          'credentials':"SELECT row_to_json(t)::text FROM (SELECT id,user_id,type,secret_data,credential_data FROM credential ORDER BY id) t;"}
        hashes={}
        for key,query in queries.items():
            rows=[json.loads(line) for line in sql(name,query).splitlines() if line]
            if key=='credentials':rows=[row for row in rows if row['user_id']!=before_admin['sub']]
            hashes[key]=hashlib.sha256(json.dumps(rows,sort_keys=True).encode()).hexdigest()
        return hashes
    ops.run(['docker','stop','--timeout','45',original['Id']]);before=fingerprints('keycloak_db')
    # Existing cryptographic backup/restore tooling is reused, not a plaintext custom export.
    result=subprocess.run([sys.executable,str(ROOT/'infrastructure/ops/backup.py'),'--include-keycloak','--identity-dir',str(identity)],capture_output=True,text=True,check=True)
    backup=Path(json.loads(result.stdout.splitlines()[-1])['backup']);manifest=ops.manifest(backup)
    if 'keycloak_db' not in manifest['databases']:raise RuntimeError('Authenticated provider backup absent')
    clone='keycloak_secure_'+time.strftime('%Y%m%d%H%M%S',time.gmtime())+'_'+secrets.token_hex(3)
    if not re.fullmatch(r'keycloak_secure_[0-9]{14}_[a-f0-9]{6}',clone):raise RuntimeError('Unexpected isolated clone identifier')
    sql('postgres','CREATE DATABASE '+ops.identifier(clone)+' OWNER keycloak;',role='kc_bootstrap',password=private['KC_DB_BOOTSTRAP_PASSWORD'])
    if sql(clone,"SELECT count(*) FROM pg_tables WHERE schemaname='public';")!='0':raise RuntimeError('New clone unexpectedly contains user tables')
    # Only the empty default namespace in this newly created database is replaced by the verified dump.
    sql(clone,'DROP SCHEMA public;')
    decoded=subprocess.Popen(['age','-d','-i',str(ops.KEY),str(backup/'keycloak_db.dump.age')],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
    restored=subprocess.run(['docker','exec','-i','-e','PGPASSWORD',database['Id'],'pg_restore','-h','127.0.0.1','-U','keycloak','-d',clone,'--exit-on-error','--no-owner','--no-privileges'],stdin=decoded.stdout,env=ops.password_env(private['KC_DB_PASSWORD']),capture_output=True)
    decoded.stdout.close()
    if decoded.wait()!=0:raise RuntimeError('Provider backup authentication failed during clone restore')
    if restored.returncode!=0:raise RuntimeError('Protected provider clone pg_restore failed')
    if fingerprints(clone)!=before:raise RuntimeError('Restored existing identity hashes differ')
    candidate_env=dict(os.environ,**env)
    candidate_env['KC_DB_URL']='jdbc:postgresql://keycloak-db:5432/'+clone
    candidate_env['JAVA_OPTS_KC_HEAP']='-Xms64m -Xmx256m -XX:ActiveProcessorCount=2 -XX:+ExitOnOutOfMemoryError'
    command=['docker','run','--rm','-d','--network','esep-identity-dev_identity','--memory','768m','--cpus','2','--label','esep.scope=synthetic-identity-upgrade',
        '-p','127.0.0.1:8180:8080']
    for key in candidate_env:
        if key.startswith('KC_') or key=='JAVA_OPTS_KC_HEAP':command+=['-e',key]
    target=ops.run(command+[image['Id'],'start-dev'],env=candidate_env).decode().strip();verified=False
    try:
        deadline=time.monotonic()+180
        while time.monotonic()<deadline:
            try:
                helper['get_json'](helper['BASE']+'/realms/esep/.well-known/openid-configuration');break
            except Exception:time.sleep(1)
        else:raise RuntimeError('Cloned provider readiness timeout')
        after_keys=keys()
        if {r:key_fingerprint(j) for r,j in before_keys.items()}!={r:key_fingerprint(j) for r,j in after_keys.items()}:
            raise RuntimeError('Realm signing keys changed')
        operator_tokens=helper['login']('master','security-admin-console',helper['BASE']+'/admin/master/console/',candidate_env['KC_BOOTSTRAP_ADMIN_USERNAME'],candidate_env['KC_BOOTSTRAP_ADMIN_PASSWORD'])
        after_admin=helper['verify_signature'](operator_tokens['id_token'],after_keys['master'])
        if any(before_admin[k]!=after_admin[k] for k in ['sub','iss']):raise RuntimeError('Existing operator issuer/subject changed')
        old_arguments=sys.argv;sys.argv=['validate.py']
        try:helper['main']()
        finally:sys.argv=old_arguments
        if fingerprints(clone)!=before:raise RuntimeError('Existing identity, role or human credential material changed')
        verified=True
    finally:
        # Stop only this new clone provider; original provider data/database are not changed.
        current=json.loads(ops.run(['docker','inspect',target]))[0]
        if current['Config']['Labels'].get('esep.scope')!='synthetic-identity-upgrade':raise RuntimeError('Fixture scope differs')
        ops.run(['docker','stop','--timeout','45',target])
        if not verified:print('Original provider remains fenced; its database and backup are retained')
    # No in-place database schema upgrade: the original keycloak_db remains a rollback source.
    with os.fdopen(os.open(ROOT/'.env.identity-secure',os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600),'w') as stream:stream.write('KEYCLOAK_SECURE_DB_NAME='+clone+'\n')
    # Retain the original image without starting another identity or financial writer.
    ops.run(['docker','create','--label','esep.scope=verification-artifact','--network','none','--entrypoint','sh',original['Image'],'-c','exit 0'])
    dc=['docker','compose','--project-name','esep-identity-dev','--project-directory',str(identity),'--env-file',str(identity/'.env'),'--env-file',str(ROOT/'.env.identity-secure'),
        '-f',str(identity/'compose.yaml'),'-f',str(ROOT/'infrastructure/platform/compose.identity-m1.yaml'),'-f',str(ROOT/'infrastructure/platform/compose.identity-security.yaml')]
    subprocess.run(dc+['up','-d','--wait','--wait-timeout','240'],check=True)
    helper['verify_configuration'](helper['admin_login'](candidate_env))
    summary={'result':'PASS','scope':'local-only','imageId':image['Id'],'authenticatedBackup':str(backup),'newDatabase':clone,'originalDatabaseRetained':'keycloak_db',
        'existingUserIdsRolesCredentialsPreserved':True,'signingKeysPreserved':True,'existingOperatorPKCEAndSubjectPreserved':True,'actualProviderSecuritySmoke':'PASS',
        'financialDatabasesMutated':False,'productionChanged':False}
    (backup/'identity-upgrade-result.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps(summary))
if __name__=='__main__':
    try:main()
    except Exception as error:
        print('FAIL: '+(str(error) if isinstance(error,RuntimeError) else type(error).__name__))
        for frame in traceback.extract_tb(error.__traceback__):print('  at '+Path(frame.filename).name+':'+str(frame.lineno)+' '+frame.name)
        if not (ROOT/'.env.identity-secure').exists():
            original=ops.container('esep-identity-dev','keycloak')
            if original['State']['Running']:ops.run(['docker','stop','--timeout','45',original['Id']])
        raise SystemExit(1)

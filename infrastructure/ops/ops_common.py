"""Local-only operations primitives. No credentials, SQL values or dump contents are logged."""
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

ROOT=Path(__file__).resolve().parents[2]
BACKUPS=ROOT/'.platform-backups'
KEYS=ROOT/'.platform-secrets'
KEY=KEYS/'backup.agekey'
DATABASES=('user_db','ledger_db','notification_db','analytics_db')
WRITERS={'user','ledger','ledger-2','notification','analytics'}
PLATFORM=['docker','compose','-p','esep-platform-local','-f',str(ROOT/'compose.platform.yaml'),'--env-file',str(ROOT/'.env.platform')]

def run(command,*,env=None,data=None):
    result=subprocess.run(command,input=data,env=env,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
    if result.returncode:raise RuntimeError('Protected operation failed: '+Path(command[0]).name+' (exit '+str(result.returncode)+')')
    return result.stdout

def environment(path=ROOT/'.env.platform'):
    if path.is_symlink() or path.stat().st_mode&0o077:raise RuntimeError('Credential file must be private0600 and not a symlink')
    values={}
    for line in path.read_text().splitlines():
        if not line or line.startswith('#'):continue
        key,value=line.split('=',1)
        if not re.fullmatch('[A-Z][A-Z0-9_]*',key) or key in values:raise RuntimeError('Invalid environment format')
        values[key]=value
    return values

def local_daemon():
    if os.environ.get('DOCKER_HOST') and not os.environ['DOCKER_HOST'].startswith('unix://'):
        raise RuntimeError('Operations require a local Unix Docker daemon')
    context=json.loads(run(['docker','context','inspect']))[0]
    if not context['Endpoints']['docker']['Host'].startswith('unix://'):raise RuntimeError('Remote Docker operations refused')

def private_dir(path):
    path.mkdir(mode=0o700,parents=True,exist_ok=True)
    if path.is_symlink() or path.stat().st_mode&0o077:raise RuntimeError('Operations directory must be private0700')

def keys(create=True):
    private_dir(KEYS);private_dir(BACKUPS)
    if not KEY.exists():
        if not create:raise RuntimeError('Original backup identity is required; a new key cannot decrypt an old backup')
        run(['age-keygen','-o',str(KEY)])
    if KEY.is_symlink() or KEY.stat().st_mode&0o077:raise RuntimeError('Backup identity must be private0600')
    return run(['age-keygen','-y',str(KEY)]).decode().strip()

def container(project,service):
    matches=run(['docker','ps','-aq','--filter','label=com.docker.compose.project='+project,
                 '--filter','label=com.docker.compose.service='+service]).decode().split()
    if len(matches)!=1:raise RuntimeError('Expected exactly one isolated '+service+' container')
    model=json.loads(run(['docker','inspect',matches[0]]))[0]
    labels=model['Config']['Labels']
    if labels.get('com.docker.compose.project')!=project or labels.get('com.docker.compose.service')!=service:
        raise RuntimeError('Container scope mismatch')
    return model

def quiescent(include_keycloak=False):
    local_daemon()
    active=set(run(PLATFORM+['ps','--status','running','--services']).decode().split())
    if active&WRITERS:raise RuntimeError('Stop all isolated business writers/consumers before a consistent backup')
    pg=container('esep-platform-local','postgres')
    if not pg['State']['Running'] or not any(m.get('Name')=='esep-platform-local_platform-pgdata' for m in pg['Mounts']):
        raise RuntimeError('Expected the isolated platform financial volume')
    if include_keycloak and container('esep-identity-dev','keycloak')['State']['Running']:
        raise RuntimeError('Stop the existing local Keycloak application, retaining its database, for a consistent provider backup')

def db_command(database,user,kind='psql',provider=False,extra=()):
    target=container('esep-identity-dev' if provider else 'esep-platform-local','keycloak-db' if provider else 'postgres')['Id']
    return ['docker','exec','-i','-e','PGPASSWORD',target,kind,'-h','127.0.0.1','-U',user,'-d',database,*extra]

def password_env(password):
    env=os.environ.copy();env['PGPASSWORD']=password;return env

def sql(database,query,user,password,provider=False):
    return run(db_command(database,user,provider=provider,extra=('-X','-A','-t','-q','-v','ON_ERROR_STOP=1')),
               env=password_env(password),data=query.encode()).decode().strip()

def identifier(value):
    if not re.fullmatch('[A-Za-z_][A-Za-z0-9_]*',value):raise RuntimeError('Unexpected catalog identifier')
    return '"'+value+'"'

def facts(database,user,password,provider=False):
    names=sql(database,"SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename;",user,password,provider).splitlines()
    tables={}
    for name in names:
        table=identifier(name)
        command=db_command(database,user,provider=provider,extra=('-X','-A','-t','-q','-v','ON_ERROR_STOP=1'))
        digest=hashlib.sha256();rows=0
        process=subprocess.Popen(command,env=password_env(password),stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)
        process.stdin.write(("SET TIME ZONE 'UTC'; COPY (SELECT to_jsonb(t)::text FROM public."+table+" t ORDER BY to_jsonb(t)::text) TO STDOUT;\n").encode());process.stdin.close()
        for line in process.stdout:digest.update(line);rows+=1
        process.stdout.close()
        if process.wait():raise RuntimeError('Could not fingerprint protected source rows')
        tables[name]={'rows':rows,'sha256':digest.hexdigest()}
    sequences={}
    names=sql(database,"SELECT sequencename FROM pg_sequences WHERE schemaname='public' ORDER BY sequencename;",user,password,provider).splitlines()
    for name in names:
        sequences[name]=sql(database,'SELECT last_value,is_called FROM public.'+identifier(name)+';',user,password,provider)
    return {'tables':tables,'sequences':sequences}

def sha(path):
    digest=hashlib.sha256()
    with path.open('rb') as stream:
        for part in iter(lambda:stream.read(1024*1024),b''):digest.update(part)
    return digest.hexdigest()

def encrypt_bytes(value,path,recipient):
    with os.fdopen(os.open(path,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600),'wb') as output:
        result=subprocess.run(['age','-r',recipient],input=value,stdout=output,stderr=subprocess.DEVNULL)
    if result.returncode:raise RuntimeError('Backup encryption failed')

def authenticate(path):
    result=subprocess.run(['age','-d','-i',str(KEY),str(path)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    if result.returncode:raise RuntimeError('Backup authentication failed before restore')

def manifest(directory):
    directory=directory.resolve()
    if directory.parent!=BACKUPS.resolve() or not re.fullmatch('esep-backup-[A-Za-z0-9_-]+',directory.name):
        raise RuntimeError('Restore accepts only private local backup artifacts')
    private_dir(directory)
    path=directory/'manifest.json.age'
    if path.is_symlink() or path.stat().st_size>2*1024*1024:raise RuntimeError('Unexpected encrypted manifest')
    data=json.loads(run(['age','-d','-i',str(KEY),str(path)]))
    if data.get('format')!=1 or data.get('project')!='esep-platform-local':raise RuntimeError('Backup scope mismatch')
    if set(data['databases']) not in (set(DATABASES),set(DATABASES)|{'keycloak_db'}):raise RuntimeError('Unexpected database set')
    for name,item in data['databases'].items():
        path=directory/(name+'.dump.age')
        if path.is_symlink() or sha(path)!=item['ciphertextSha256']:raise RuntimeError('Encrypted artifact checksum mismatch')
        authenticate(path)
    return data

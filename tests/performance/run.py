#!/usr/bin/env python3
"""Real bounded k6 workloads on isolated synthetic ESEP accounts. Never accepts a remote target."""
import argparse
import hashlib
from datetime import datetime, timezone
from decimal import Decimal
import json
import os
from pathlib import Path
import re
import runpy
import secrets
import subprocess
import sys
import threading
import traceback
import time
import urllib.request
import urllib.parse

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'infrastructure/ops'))
import ops_common as ops
EDGE='http://localhost:18081'
CASES=('baseline','normal','stress','spike','soak','transfers','duplicates','analytics','balancing')

def require(ok,message):
    if not ok:raise RuntimeError(message)

def prom(expression):
    with urllib.request.urlopen('http://localhost:19090/api/v1/query?'+urllib.parse.urlencode({'query':expression}),timeout=5) as r:
        return json.load(r)['data']['result']

def collect(stop,samples):
    while not stop.is_set():
        sample={'utc':datetime.now(timezone.utc).isoformat()}
        try:
            rows=subprocess.check_output(['docker','stats','--no-stream','--format','{{json .}}'],text=True,timeout=10)
            sample['containers']=[json.loads(x) for x in rows.splitlines() if x.strip() and
                                  json.loads(x)['Name'].startswith(('esep-platform-local-','esep-identity-dev-'))]
            sample['postgres_connections']=prom('sum(pg_stat_database_numbackends{datname=~"user_db|ledger_db|notification_db|analytics_db"})')
            sample['kafka_lag']=prom('sum(kafka_consumergroup_lag)')
            sample['outbox_pending']=prom('max(esep_outbox_backlog)')
            sample['jvm_cpu']=prom('process_cpu_usage')
            sample['hikari_active']=prom('hikaricp_connections_active')
            sample['hikari_pending']=prom('hikaricp_connections_pending')
            sample['hikari_timeouts']=prom('hikaricp_connections_timeout_total')
            sample['hikari_max']=prom('hikaricp_connections_max')
        except Exception as error:sample['collection_error']=type(error).__name__
        samples.append(sample);stop.wait(5)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--identity-dir',type=Path,required=True)
    parser.add_argument('--cases',nargs='+',choices=CASES,default=list(CASES))
    parser.add_argument('--engine',choices=['host','docker'],default='host',help='Host k6 minimizes M1 VM memory; Docker uses the pinned optional profile')
    parser.add_argument('--faults',action='store_true',help='Controlled protected replica/broker/cache/database/network faults after load')
    args=parser.parse_args()
    ops.local_daemon();private=ops.environment()
    pg=ops.container('esep-platform-local','postgres')
    require(any(m.get('Name')=='esep-platform-local_platform-pgdata' for m in pg['Mounts']),'Wrong financial volume')
    for service in ['user','ledger','analytics','notification','gateway','gateway-2']:
        require(ops.container('esep-platform-local',service)['State']['Running'],'Required application stopped')
    require(not ops.container('esep-platform-local','grafana')['State']['Running'],'Stop optional Grafana UI during M1 load tests')
    with urllib.request.urlopen(EDGE+'/ready',timeout=5) as response:
        require(response.status==200 and response.headers.get('X-Gateway-Instance') in ('gateway-1','gateway-2'),'Wrong isolated edge')
    image='grafana/k6:2.3.0@sha256:2dd4756ec6e14e62778916e1eb850108e4b499304c02df56760fa0bdb0fba0b8'
    version=subprocess.check_output(['k6','version'] if args.engine=='host' else ['docker','run','--rm',image,'version'],text=True).strip()
    nonce=secrets.token_hex(6)
    output=ROOT/'tests/performance/results'/(datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'-'+nonce)
    output.mkdir(parents=True,exist_ok=False)
    e2e=runpy.run_path(str(ROOT/'tests/e2e/identity-flow.py'));api=e2e['api'];wait=e2e['wait']
    api.__globals__['GATEWAY']=EDGE
    kc=runpy.run_path(str(ROOT/'infrastructure/keycloak/validate.py'))
    dc=kc['DC'];dc[dc.index('--env-file')+1]=str(args.identity_dir.resolve()/'.env')
    config=json.loads(subprocess.check_output(dc+['config','--format','json'],text=True))
    provider=config['services']['keycloak']['environment']
    admin=kc['admin_login'](provider);_,client,roles=kc['verify_configuration'](admin)
    request=kc['request'];urls=[];actors=[];credentials=[];operator_session=None
    def login(person):return kc['login']('esep','esep-web',kc['CALLBACK'],person['name'],person['password'])
    def financial_sql(query):return ops.sql('ledger_db',query,'platform_backup',private['PLATFORM_BACKUP_PASSWORD'])
    def invariants():
        result=financial_sql((ROOT/'tests/financial/ledger-invariants.sql').read_text())
        require('financial_invariants_passed' in result,'Financial invariant SQL failed')
    def fresh_operator():
        nonlocal operator_session
        if operator_session is None or time.time()>=operator_session['expiresAt']-30:
            tokens=login(credentials[0]);operator_session={'token':tokens['access_token'],'expiresAt':time.time()+tokens['expires_in']}
        return operator_session['token']
    def reconcile():
        token=fresh_operator()
        status,fence=api('http://localhost:18082','/internal/ledger/write-fence',token,{'enabled':False})
        require(status==200,'Could not fence synthetic writes for reconciliation');epoch=fence['epoch']
        try:
            status,source=api('http://localhost:18082','/internal/ledger/reconciliation?epoch='+str(epoch),token)
            require(status==200,'Ledger manifest unavailable')
            wait(lambda:api('http://localhost:18085','/internal/analytics/reconciliation',token)==(200,source),
                 'Actual Kafka projection failed reconciliation',90)
        finally:require(api('http://localhost:18082','/internal/ledger/write-fence',token,{'enabled':True})[0]==200,'Write fence recovery failed')
    try:
        for number,role in enumerate(['ADMIN']+['USER']*8):
            # Provider admin tokens are short lived. Reauthenticate through real Code+PKCE, never password grant.
            if number%3==0:admin=kc['admin_login'](provider)
            person={'name':'k6-local-'+nonce+'-'+str(number),'password':secrets.token_urlsafe(32)}
            status,headers,_=request('POST',kc['BASE']+'/admin/realms/esep/users',{
                'username':person['name'],'enabled':True,'firstName':'Synthetic','lastName':'Performance',
                'email':person['name']+'@example.invalid','emailVerified':True,
                'credentials':[{'type':'password','value':person['password'],'temporary':False}]},token=admin)
            require(status==201,'Synthetic provider fixture failed');url=headers['Location'];urls.append(url)
            require(request('POST',url+'/role-mappings/clients/'+client['id'],[roles[role]],token=admin)[0]==204,'Explicit fixture role assignment failed')
            tokens=login(person);status,profile=api(EDGE,'/api/auth/me',tokens['access_token'])
            require(status==200,'Automatic identity provisioning failed')
            credentials.append(person)
            for path in ['/api/accounts','/api/notifications','/api/analytics/spending?currency=KZT']:
                wait(lambda p=path,t=tokens['access_token']:api(EDGE,p,t)[0]==200,'Identity projection not ready')
            if role=='ADMIN':
                operator_session={'token':tokens['access_token'],'expiresAt':time.time()+tokens['expires_in']}
                continue
            status,account=api(EDGE,'/api/accounts',tokens['access_token'],{'currency':'KZT'})
            require(status==201 and account['userId']==profile['id'],'Account ownership mismatch')
            status,_=api(EDGE,'/api/deposits',fresh_operator(),{'accountId':account['id'],'amount':'100.0000'},'k6-fund-'+nonce+'-'+str(number))
            require(status==201,'Explicit synthetic funding failed')
            actors.append({'accountId':account['id'],'userId':profile['id']})
        # Paired actors transfer in opposite directions, always from their own account.
        for index,actor in enumerate(actors):actor['recipientId']=actors[index^1]['accountId']
        require(api(EDGE,'/api/accounts/'+str(actors[1]['accountId']),login(credentials[1])['access_token'])[0]==404,'Foreign account disclosed')
        require(api(EDGE,'/api/transfers',fresh_operator(),{'fromAccountId':actors[0]['accountId'],'toAccountId':actors[1]['accountId'],'amount':'0.0001'},'k6-denied-'+nonce)[0]==404,'ADMIN gained ownership')
        info=json.loads(subprocess.check_output(['docker','info','--format','{{json .}}']))
        hardware={'architecture':os.uname().machine,'physical_memory_bytes':int(subprocess.check_output(['sysctl','-n','hw.memsize'])) if sys.platform=='darwin' else os.sysconf('SC_PHYS_PAGES')*os.sysconf('SC_PAGE_SIZE'),
            'host_cpus':os.cpu_count(),'docker_memory_bytes':info['MemTotal'],'docker_cpus':info['NCPU'],
            'k6':version,'shared_host':True,'platform_base_sha':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
            'load_script_sha256':hashlib.sha256((ROOT/'tests/performance/platform.js').read_bytes()).hexdigest(),'actor_count':len(actors),'currency':'KZT',
            'initial_synthetic_funding':'800.0000','ledger_instances':1 if not ops.container('esep-platform-local','ledger-2')['State']['Running'] else 2}
        (output/'environment.json').write_text(json.dumps(hardware,indent=2)+'\n')
        results=[]
        for case in args.cases:
            fixture={'base':EDGE if args.engine=='host' else 'http://edge:8080','nonce':nonce,'actors':[]}
            fixture['tokenUrl']='http://localhost:8180/realms/esep/protocol/openid-connect/token' if args.engine=='host' else 'http://keycloak:8080/realms/esep/protocol/openid-connect/token'
            for account,person in zip(actors,credentials[1:]):
                tokens=login(person)
                fixture['actors'].append({**account,'token':tokens['access_token'],'refreshToken':tokens['refresh_token'],
                                          'expiresAt':time.time()+tokens['expires_in']})
            summary_path=output/(case+'.summary.json')
            process_env=os.environ.copy();process_env.update(ESEP_PERF_ACTORS=json.dumps(fixture),ESEP_PERF_CASE=case,ESEP_PERF_SUMMARY=str(summary_path))
            if args.engine=='docker':
                process_env.update(PERF_UID=str(os.getuid()),PERF_GID=str(os.getgid()),PERF_RESULTS_DIR=str(output))
            command=['k6','run','--quiet','--include-system-env-vars',str(ROOT/'tests/performance/platform.js')] if args.engine=='host' else ['docker','compose','--env-file',str(ROOT/'.env.platform'),'-f',str(ROOT/'compose.platform.yaml'),'-f',str(ROOT/'compose.monitoring.yaml'),'-f',str(ROOT/'compose.monitoring-compact.yaml'),'-f',str(ROOT/'compose.performance.yaml'),'--profile','performance','run','--rm','--no-deps','k6']
            script_hash=hashlib.sha256((ROOT/'tests/performance/platform.js').read_bytes()).hexdigest()
            stop=threading.Event();samples=[];collector=threading.Thread(target=collect,args=(stop,samples),daemon=True);collector.start()
            started=time.monotonic()
            try:
                with (output/(case+'.log')).open('w') as log:
                    run=subprocess.run(command,env=process_env,stdout=log,stderr=subprocess.STDOUT)
            finally:stop.set();collector.join(timeout=20)
            elapsed=time.monotonic()-started
            (output/(case+'.resources.json')).write_text(json.dumps(samples,indent=2)+'\n')
            require(summary_path.is_file(),'k6 summary missing; workload failed')
            summary=json.loads(summary_path.read_text());metrics=summary['metrics']
            count=int(financial_sql("SELECT count(*) FROM transactions WHERE status='COMPLETED' AND type='TRANSFER' AND idempotency_key LIKE 'k6-"+nonce+'-'+case+"-%';"))
            recorded=int(metrics.get('transactions_created',{}).get('values',{}).get('count',0))
            require(count==recorded,'Committed transaction count differs from observed creations')
            total=Decimal('0')
            for actor,person,session in zip(actors,credentials[1:],fixture['actors']):
                token=session['token'] if time.time()<session['expiresAt']-30 else login(person)['access_token']
                account={}
                def balance_ready():
                    status,value=api(EDGE,'/api/accounts/'+str(actor['accountId']),token)
                    if status==429:return False  # Preserve the real rate-limit window after deliberate saturation.
                    require(status==200,'Post-load account query failed')
                    account.update(value);return True
                wait(balance_ready,'Post-load rate-limit cooldown exceeded75 seconds',75)
                total+=account['balance']
            require(total==Decimal('800.0000'),'Post-load balance conservation failed');invariants();reconcile()
            # Secret checks inspect output without ever printing secret values.
            artifacts='\n'.join(p.read_text() for p in output.glob(case+'.*') if p.is_file())
            sensitive=[p['password'] for p in credentials]+[v for a in fixture['actors'] for k,v in a.items() if k in ('token','refreshToken')]
            require(not any(value in artifacts for value in sensitive),'Credential detected in performance artifact')
            require(not re.search(r'eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.',artifacts),'JWT detected in performance artifact')
            record={'case':case,'engine':args.engine,'load_script_sha256':script_hash,'exit_code':run.returncode,'elapsed_seconds':round(elapsed,3),'committed_transfers':count,
                'balance_conservation':'800.0000','double_entry':'PASS','actual_kafka_reconciliation':'PASS',
                'resources_collected':len(samples),'metrics':metrics}
            results.append(record);(output/'results.json').write_text(json.dumps(results,indent=2)+'\n')
            print(case+': exit='+str(run.returncode)+', transfers='+str(count)+', conservation/invariants/Kafka reconciliation PASS',flush=True)
            require(run.returncode==0,'k6 functional thresholds failed: '+case)
        if args.faults:
            faults=runpy.run_path(str(ROOT/'tests/performance/faults.py'))
            faults['run'](ROOT,actors,credentials,login,api,wait,require,private,invariants,reconcile,output)
        print('Sanitized results: '+str(output.relative_to(ROOT)),flush=True)
    finally:
        admin=kc['admin_login'](provider)
        for url in urls:require(request('DELETE',url,token=admin)[0] in (204,404),'Synthetic identity cleanup failed')
        print('Temporary provider identities removed; synthetic financial history retained',flush=True)
if __name__=='__main__':
    try:main()
    except Exception as error:
        print('FAIL: '+(str(error) if isinstance(error,RuntimeError) else type(error).__name__))
        for frame in traceback.extract_tb(error.__traceback__):
            print('  at '+Path(frame.filename).name+':'+str(frame.lineno)+' '+frame.name)
        raise SystemExit(1)

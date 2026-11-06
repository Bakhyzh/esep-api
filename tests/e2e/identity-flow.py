#!/usr/bin/env python3
"""Real Code+PKCE -> User authority -> Kafka -> Ledger. Synthetic identities only."""
import argparse
from decimal import Decimal
import json
from pathlib import Path
import runpy
import secrets
import subprocess
import time
import urllib.error
import urllib.request
ROOT=Path(__file__).resolve().parents[2]


def api(base,path,token=None,data=None,key=None):
    headers={'Accept':'application/json'}
    if token:headers['Authorization']='Bearer '+token
    if data is not None:headers['Content-Type']='application/json'
    if key:headers['Idempotency-Key']=key
    request=urllib.request.Request(base+path,headers=headers,data=None if data is None else json.dumps(data).encode())
    try:response=urllib.request.urlopen(request,timeout=10)
    except urllib.error.HTTPError as error:response=error
    with response:
        body=response.read()
        return response.status,json.loads(body,parse_float=Decimal) if body else None


def wait(check,message,seconds=30):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        if check():return
        time.sleep(.25)
    raise RuntimeError(message)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--identity-dir',type=Path,required=True)
    parser.add_argument('--user-url',default='http://localhost:18083')
    parser.add_argument('--ledger-url',default='http://localhost:18082')
    args=parser.parse_args()
    helper=runpy.run_path(str(ROOT/'infrastructure/keycloak/validate.py'))
    dc=helper['DC'];dc[dc.index('--env-file')+1]=str(args.identity_dir.resolve()/'.env')
    model=json.loads(subprocess.check_output(dc+['config','--format','json'],text=True))
    env=model['services']['keycloak']['environment'];admin=helper['admin_login'](env)
    _,client,roles=helper['verify_configuration'](admin)
    request,check,passed=helper['request'],helper['check'],helper['passed'];urls=[]
    try:
        people=[]
        for role in ('ADMIN','USER','USER'):
            name='platform-check-'+secrets.token_hex(8);password=secrets.token_urlsafe(32)
            status,headers,_=request('POST',helper['BASE']+'/admin/realms/esep/users',dict(username=name,
                enabled=True,firstName='Local',lastName='Platform',email=name+'@example.invalid',emailVerified=True,
                credentials=[dict(type='password',value=password,temporary=False)]),token=admin)
            check(status==201,'Temporary identity creation failed');url=headers['Location'];urls.append(url)
            check(request('POST',url+'/role-mappings/clients/'+client['id'],[roles[role]],token=admin)[0]==204,'Temporary role assignment failed')
            token=helper['login']('esep','esep-web',helper['CALLBACK'],name,password)['access_token']
            check(api(args.ledger_url,'/api/accounts',token)[0]==403,'Ledger accepted unknown identity')
            status,profile=api(args.user_url,'/api/auth/me',token)
            check(status==200 and profile['role']==role,'User authority provisioning failed')
            check(api(args.user_url,'/api/auth/me',token)[1]['id']==profile['id'],'Identity allocation was not repeatable')
            wait(lambda:api(args.ledger_url,'/api/accounts',token)[0]==200,'Identity event did not reach Ledger')
            people.append((token,profile['id']))
        operator,alice,bob=people
        status,a=api(args.ledger_url,'/api/accounts',alice[0],dict(currency='KZT'));check(status==201 and a['userId']==alice[1],'Ownership ID changed')
        status,b=api(args.ledger_url,'/api/accounts',bob[0],dict(currency='KZT'));check(status==201 and b['userId']==bob[1],'Recipient ownership ID changed')
        check(api(args.ledger_url,'/api/accounts/'+str(a['id']),bob[0])[0]==404,'Foreign account disclosed')
        check(api(args.ledger_url,'/api/deposits',operator[0],dict(accountId=a['id'],amount='100'),secrets.token_hex(12))[0]==201,'Top-up failed')
        transfer=dict(fromAccountId=a['id'],toAccountId=b['id'],amount='12.3456');key=secrets.token_hex(12)
        check(api(args.ledger_url,'/api/transfers',alice[0],transfer,key)[0]==201,'Transfer failed')
        check(api(args.ledger_url,'/api/transfers',alice[0],transfer,key)[0]==200,'Transfer replay failed')
        check(api(args.ledger_url,'/api/transfers',operator[0],transfer,secrets.token_hex(12))[0]==404,'ADMIN gained source ownership')
        check(api(args.ledger_url,'/api/accounts/'+str(a['id']),alice[0])[1]['balance']==Decimal('87.6544'),'Incorrect financial balance')
        passed('Real Keycloak -> automatic User provisioning -> transactional outbox -> Kafka -> Ledger projection; original allocated ID and money invariants preserved')
        check(api(args.user_url,'/api/users/'+str(alice[1])+'/disable',operator[0],{})[0]==200,'Disable request failed')
        wait(lambda:api(args.ledger_url,'/api/accounts',alice[0])[0]==403,'Disabled identity remained authorized in Ledger')
        check(api(args.user_url,'/api/auth/me',alice[0])[0]==403,'Disabled User remained active')
        check(api(args.user_url,'/api/users/'+str(alice[1])+'/enable',operator[0],{})[0]==200,'Enable request failed')
        wait(lambda:api(args.ledger_url,'/api/accounts',alice[0])[0]==200,'Enabled projection did not recover')
        check(api(args.user_url,'/internal/identity/replay',operator[0],{})[0]==200,'Snapshot replay failed')
        user_snapshots=api(args.user_url,'/internal/identity/snapshots',operator[0])[1]
        def reconciled():
            status,ledger=api(args.ledger_url,'/internal/identity/snapshots',operator[0])
            if status!=200:return False
            desired={(x['userId'],x['issuer'],x['subject'],x['version'],x['status']) for x in user_snapshots}
            actual={(x['user_id'],x['issuer'],x['subject'],x['source_version'],x['status']) for x in ledger}
            return desired==actual
        wait(reconciled,'Identity reconciliation mismatch')
        passed('Lifecycle disable/enable, idempotent snapshot replay and cross-service identity reconciliation passed')
    finally:
        admin=helper['admin_login'](env)
        for url in urls:check(request('DELETE',url,token=admin)[0] in (204,404),'Temporary identity cleanup failed')
        passed('Temporary Keycloak identities removed; synthetic business/audit data retained')


if __name__=='__main__':
    try:main()
    except Exception as error:
        print('FAIL: '+(str(error) if isinstance(error,RuntimeError) else type(error).__name__))
        raise SystemExit(1)

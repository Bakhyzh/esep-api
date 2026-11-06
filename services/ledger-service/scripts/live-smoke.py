#!/usr/bin/env python3
"""Real local Keycloak Code+PKCE -> isolated Ledger API/database acceptance.

Creates and removes only random Keycloak test identities. Ledger financial fixtures
and approved mapping audit records remain. Never touches the monolith database.
"""
import argparse
from decimal import Decimal
import json
from pathlib import Path
import runpy
import secrets
import subprocess
import urllib.error
import urllib.request
import time

SERVICE=Path(__file__).resolve().parents[1]
ROOT=SERVICE.parents[1]
DC=['docker','compose','-f',str(SERVICE/'compose.yaml'),'--env-file',str(SERVICE/'.env')]
BASE='http://localhost:8082'


def sql(statement,variables=()):
    cmd=DC+['exec','-T','postgres','psql','-X','-qAt','-U','ledger_bootstrap','-d','ledger_db','-v','ON_ERROR_STOP=1']
    for k,v in variables:
        cmd+=['-v',k+'='+str(v)]
    return subprocess.check_output(cmd,input=statement,text=True).strip()


def api(path,token=None,data=None,key=None):
    headers={'Accept':'application/json'}
    if token: headers['Authorization']='Bearer '+token
    if data is not None: headers['Content-Type']='application/json'
    if key: headers['Idempotency-Key']=key
    req=urllib.request.Request(BASE+path,headers=headers,data=None if data is None else json.dumps(data).encode())
    try: result=urllib.request.urlopen(req,timeout=10)
    except urllib.error.HTTPError as e: result=e
    with result:
        body=result.read()
        return result.status,json.loads(body,parse_float=Decimal) if body else None


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--identity-dir',type=Path,required=True)
    args=parser.parse_args()
    h=runpy.run_path(str(ROOT/'infrastructure/keycloak/validate.py'))
    dc=h['DC'];dc[dc.index('--env-file')+1]=str(args.identity_dir.resolve()/'.env')
    env=json.loads(subprocess.check_output(dc+['config','--format','json'],text=True))['services']['keycloak']['environment']
    admin=h['admin_login'](env)
    _,client,roles=h['verify_configuration'](admin)
    request,check,passed=h['request'],h['check'],h['passed']
    urls=[]
    try:
        tokens=[];ids=[]
        for assigned in ('USER','USER','ADMIN'):
            username='phase31-'+secrets.token_hex(8);password=secrets.token_urlsafe(32)
            status,headers,_=request('POST',h['BASE']+'/admin/realms/esep/users',dict(username=username,
                enabled=True,firstName='Local',lastName='Ledger',email=username+'@example.invalid',emailVerified=True,
                credentials=[dict(type='password',value=password,temporary=False)]),token=admin)
            check(status==201,'Temporary identity creation failed')
            url=headers['Location'];urls.append(url)
            check(request('POST',url+'/role-mappings/clients/'+client['id'],[roles[assigned]],token=admin)[0]==204,'Role assignment failed')
            raw=h['login']('esep','esep-web',h['CALLBACK'],username,password)['access_token']
            check(api('/api/accounts',raw)[0]==403,'Unbound identity obtained access')
            # Synthetic immutable business IDs reserved in this isolated database only.
            uid=1000000+secrets.randbelow(1000000000)
            params=[('user_id',uid),('issuer',h['ISSUER']),('subject',url.rsplit('/',1)[1]),('approved_operator','phase31/verified-synthetic-owner')]
            bootstrap=(SERVICE/'scripts/bootstrap-identity.sql').read_text()
            sql(bootstrap,params+[('apply','false')])
            check(sql("SELECT count(*) FROM ledger_principals WHERE id=:'id'::bigint;",[('id',uid)])=='0','Dry run committed')
            sql(bootstrap,params+[('apply','true')]);sql(bootstrap,params+[('apply','true')])
            check(api('/api/accounts',raw)[0]==200,'Approved mapping did not resolve')
            tokens.append(raw);ids.append(uid)
        alice,bob,operator=tokens
        status,a=api('/api/accounts',alice,dict(currency='KZT'));check(status==201 and a['userId']==ids[0],'Account owner ID changed')
        status,b=api('/api/accounts',bob,dict(currency='KZT'));check(status==201 and b['userId']==ids[1],'Recipient owner ID changed')
        check(api('/api/accounts/'+str(a['id']),bob)[0]==404,'Foreign account visible')
        check(api('/api/deposits',alice,dict(accountId=a['id'],amount='100'),secrets.token_hex(12))[0]==403,'USER gained ADMIN')
        check(api('/api/deposits',operator,dict(accountId=a['id'],amount='100'),secrets.token_hex(12))[0]==201,'ADMIN top-up failed')
        transfer=dict(fromAccountId=a['id'],toAccountId=b['id'],amount='12.3456')
        check(api('/api/transfers',operator,transfer,secrets.token_hex(12))[0]==404,'ADMIN gained source ownership')
        check(api('/api/transfers',bob,transfer,secrets.token_hex(12))[0]==404,'Foreign transfer accepted')
        key=secrets.token_hex(12)
        status,result=api('/api/transfers',alice,transfer,key);check(status==201,'Transfer failed')
        check(api('/api/transfers',alice,transfer,key)[0]==200,'Idempotent replay failed')
        check(api('/api/transfers',alice,dict(transfer,amount='1'),key)[0]==422,'Duplicate request conflict missing')
        check(api('/api/transfers',alice,dict(transfer,amount='1000'),secrets.token_hex(12))[0]==422,'Overdraft accepted')
        check(api('/api/accounts/'+str(a['id']),alice)[1]['balance']==Decimal('87.6544'),'Precision/balance failure')
        check(api('/api/transactions',alice)[0]==200,'History failed')
        for _ in range(20):
            if sql("SELECT count(*) FROM outbox_events WHERE aggregate_id=:'id'::bigint AND published_at IS NOT NULL;",[('id',result['id'])])=='1': break
            time.sleep(.5)
        check(sql("SELECT count(*) FROM outbox_events WHERE aggregate_id=:'id'::bigint AND published_at IS NOT NULL AND payload->>'schemaVersion'='1';",[('id',result['id'])])=='1','Versioned outbox not published')
        check(sql("SELECT count(*) FROM (SELECT transaction_id FROM ledger_entries GROUP BY transaction_id HAVING SUM(CASE direction WHEN 'DEBIT' THEN -amount ELSE amount END)<>0) x;")=='0','Unbalanced ledger')
        check(sql("SELECT count(*) FROM accounts a WHERE balance<>(SELECT coalesce(SUM(CASE direction WHEN 'DEBIT' THEN -amount ELSE amount END),0) FROM ledger_entries WHERE account_id=a.id);")=='0','Balances differ from postings')
        check(sql("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name IN ('users','notifications','processed_events');")=='0','Foreign-owned tables copied')
        check(sql("SELECT rolname FROM pg_roles WHERE rolname='ledger_runtime' AND NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole;")=='ledger_runtime','Runtime role elevated')
        check(sql("SELECT has_table_privilege('ledger_runtime','ledger_principals','INSERT') OR has_table_privilege('ledger_runtime','ledger_identity_bindings','UPDATE');")=='f','Runtime can modify identity')
        check(sql("SELECT has_table_privilege('ledger_runtime','ledger_entries','DELETE');")=='f','Runtime can delete history')
        passed('Real Keycloak Code+PKCE: unknown identity denied; approved/repeatable numeric mapping; USER/ADMIN and ownership; precise transfer/replay/conflict/overdraft/history')
        passed('Dedicated ledger_db: balanced postings, exact balances, versioned outbox published; no users/notification tables; restricted runtime role')
        # Prove financial commit survives a real stopped Kafka container, then retry drains.
        subprocess.run(DC+['stop','kafka'],check=True,stdout=subprocess.DEVNULL)
        try:
            status,pending=api('/api/transfers',alice,dict(transfer,amount='1'),secrets.token_hex(12))
            check(status==201,'Kafka outage blocked financial commit')
            check(api('/actuator/health/readiness')[0]==200,'Kafka outage made financial readiness unavailable')
            time.sleep(3)
            check(sql("SELECT published_at IS NULL FROM outbox_events WHERE aggregate_id=:'id'::bigint;",[('id',pending['id'])])=='t','Outage lost pending event')
        finally:
            subprocess.run(DC+['up','-d','--wait','kafka'],check=True,stdout=subprocess.DEVNULL)
        for _ in range(40):
            if sql("SELECT published_at IS NOT NULL FROM outbox_events WHERE aggregate_id=:'id'::bigint;",[('id',pending['id'])])=='t':break
            time.sleep(.5)
        check(sql("SELECT published_at IS NOT NULL FROM outbox_events WHERE aggregate_id=:'id'::bigint;",[('id',pending['id'])])=='t','Recovery did not drain outbox')
        passed('Stopped real Ledger Kafka: financial transaction committed, pending outbox retained, readiness UP, restart drained event')
    finally:
        admin=h['admin_login'](env)
        for url in urls:check(request('DELETE',url,token=admin)[0] in (204,404),'Temporary identity cleanup failed')
        passed('Temporary Keycloak test identities removed; existing monolith data/streams were not used')


if __name__=='__main__':
    try: main()
    except Exception as error:
        print('FAIL: '+(str(error) if isinstance(error,RuntimeError) else type(error).__name__))
        raise SystemExit(1)

#!/usr/bin/env python3
"""Imported by identity-flow: isolated local Compose replica faults, with real traffic and exact money checks."""
from concurrent.futures import ThreadPoolExecutor
from collections import Counter
from decimal import Decimal
import json
import http.client
from pathlib import Path
import secrets
import subprocess
import threading
import time
import urllib.error
import urllib.request


def run(root,edge,alice,bob,source,recipient,api,wait,check,passed):
    if edge not in ('http://localhost:18081','http://127.0.0.1:18081'):
        raise RuntimeError('Replica fault tests require the isolated loopback edge')
    platform=['docker','compose','-p','esep-platform-local','-f',str(root/'compose.platform.yaml'),'--env-file',str(root/'.env.platform')]
    # Refuse faults unless the exact targets belong to the isolated platform project.
    identifiers={}
    for service in ('gateway','ledger'):
        identifier=subprocess.check_output(platform+['ps','-q',service],text=True).strip()
        if not identifier:raise RuntimeError('Expected live synthetic replica')
        identifiers[service]=identifier
        model=json.loads(subprocess.check_output(['docker','inspect',identifier],text=True))[0]
        labels=model['Config']['Labels']
        if labels.get('com.docker.compose.project')!='esep-platform-local' or labels.get('com.docker.compose.service')!=service:
            raise RuntimeError('Refusing to fault an unrelated container')

    def restore(service):
        # Restart the same verified image/environment. Poll readiness without exhausting API rate limits.
        subprocess.run(['docker','start',identifiers[service]],check=True,stdout=subprocess.DEVNULL)
        deadline=time.monotonic()+120
        while time.monotonic()<deadline:
            state=subprocess.check_output(['docker','inspect','--format','{{.State.Status}} {{.State.Health.Status}}',identifiers[service]],text=True).strip()
            if state=='running healthy':return
            if not state.startswith('running '):raise RuntimeError('Replica exited during recovery')
            time.sleep(1)
        raise RuntimeError('Replica readiness did not recover within120 seconds')

    def probe():
        request=urllib.request.Request(edge+'/api/accounts',headers={'Authorization':'Bearer '+alice[0]})
        try:
            with urllib.request.urlopen(request,timeout=5) as response:
                response.read()
                return response.status,response.headers.get('X-Gateway-Instance'),response.headers.get('X-Ledger-Instance')
        except urllib.error.HTTPError as e:
            e.close();return e.code,None,None
        except (urllib.error.URLError,TimeoutError,http.client.HTTPException):return 0,None,None

    def both():
        results=[probe() for _ in range(12)]
        return (all(x[0]==200 for x in results) and {x[1] for x in results}=={'gateway-1','gateway-2'}
                and {x[2] for x in results}=={'ledger-1','ledger-2'})
    wait(both,'Traffic did not reach both Gateway and Ledger instances',40)
    passed('Actual edge traffic reached two independent Gateways and two independent Ledger instances')

    started=threading.Event()
    def reads():
        results=[]
        for n in range(50):
            results.append(probe())
            if n==2:started.set()
            time.sleep(.04)
        return results
    with ThreadPoolExecutor(max_workers=1) as workers:
        future=workers.submit(reads)
        check(started.wait(10),'Read traffic did not start')
        try:
            subprocess.run(platform+['kill','-s','SIGKILL','gateway'],check=True,stdout=subprocess.DEVNULL)
            wait(lambda:probe()[:2]==(200,'gateway-2'),'Surviving Gateway did not serve traffic',20)
            results=future.result(timeout=30)
            check(any(x[0]==200 and x[1]=='gateway-2' for x in results),'Remaining Gateway handled no live traffic')
            passed('Gateway killed during50 reads; observed HTTP statuses '+str(dict(Counter(x[0] for x in results))))
        finally:
            # Start the same verified container: preserve monitoring/auth overrides and image.
            restore('gateway')
    wait(both,'Gateway recovery did not restore balanced service',40)

    source_path='/api/accounts/'+str(source['id']);recipient_path='/api/accounts/'+str(recipient['id'])
    before=api(edge,source_path,alice[0])[1]['balance'];other=api(edge,recipient_path,bob[0])[1]['balance']
    request=dict(fromAccountId=source['id'],toAccountId=recipient['id'],amount='0.0001')
    keys=[secrets.token_hex(16) for _ in range(8)]
    started.clear()
    def transfer(index):
        started.set()
        try:
            status=api(edge,'/api/transfers',alice[0],request,keys[index%8])[0]
            time.sleep(.03)
            return index,status
        except (urllib.error.URLError,TimeoutError,http.client.HTTPException):return index,0
    try:
        with ThreadPoolExecutor(max_workers=4) as workers:
            futures=[workers.submit(transfer,n) for n in range(40)]
            check(started.wait(10),'Concurrent financial traffic did not start')
            subprocess.run(platform+['kill','-s','SIGKILL','ledger'],check=True,stdout=subprocess.DEVNULL)
            statuses=[f.result(timeout=30)[1] for f in futures]
        wait(lambda:probe()[::2]==(200,'ledger-2'),'Surviving Ledger not selected after health detection',20)
        ids=[]
        for key in keys:
            def settled():
                try:
                    status,result=api(edge,'/api/transfers',alice[0],request,key)
                    if status in (200,201):ids.append(result['id']);return True
                    return False
                except (urllib.error.URLError,TimeoutError,http.client.HTTPException):return False
            wait(settled,'Idempotent recovery did not settle a transfer',20)
        check(len(set(ids))==8,'Logical financial operations were duplicated or collapsed')
        expected=Decimal('0.0008')
        check(api(edge,source_path,alice[0])[1]['balance']==before-expected,'Source balance lost idempotency under replica failure')
        check(api(edge,recipient_path,bob[0])[1]['balance']==other+expected,'Recipient balance lost conservation under replica failure')
        passed('Ledger killed during40 concurrent duplicate requests /8 keys; statuses '+str(dict(Counter(statuses)))+'; all8 settled exactly once, conservation0.0008 verified')
    finally:
        restore('ledger')
    wait(both,'Ledger recovery did not restore balanced service',40)
    subprocess.run(platform+['exec','-T','postgres','psql','-X','-U','platform_bootstrap','-d','ledger_db'],
        input=(root/'tests/financial/ledger-invariants.sql').read_text(),text=True,check=True)
    passed('Both replica pairs restored; authoritative PostgreSQL invariants passed after faults')
